package dev.lucasdone.tvremote.agent.transport

import android.util.Log
import dev.lucasdone.tvremote.agent.auth.AuthSession
import dev.lucasdone.tvremote.agent.auth.KeystoreCredentialStore
import dev.lucasdone.tvremote.agent.auth.PairingDecision
import dev.lucasdone.tvremote.agent.auth.PairingManager
import dev.lucasdone.tvremote.agent.auth.PairingSas
import dev.lucasdone.tvremote.agent.auth.PairingSubmission
import dev.lucasdone.tvremote.agent.auth.PairingWindow
import dev.lucasdone.tvremote.agent.auth.SessionManager
import dev.lucasdone.tvremote.agent.command.CommandDispatcher
import dev.lucasdone.tvremote.agent.media.MediaSessionCoordinator
import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.protocol.FrameCodec
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolCodec
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope
import dev.lucasdone.tvremote.agent.protocol.ProtocolException
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.protocol.optionalString
import dev.lucasdone.tvremote.agent.protocol.requireLong
import dev.lucasdone.tvremote.agent.protocol.requireString
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

interface ControlServerCallbacks {
    fun onPairingWindow(window: PairingWindow)
    fun onPairingSas(details: PairingSas)
    fun onPairingClosed(pairingId: String)
    fun onControllerConnected(controllerName: String)
    fun onControllerDisconnected()
    fun onNetworkFailure(reason: String)
}

class ControlServer(
    private val identity: TlsIdentity,
    private val credentialStore: KeystoreCredentialStore,
    private val pairingManager: PairingManager,
    private val sessionManager: SessionManager,
    private val dispatcherFactory: () -> CommandDispatcher,
    private val mediaCoordinator: MediaSessionCoordinator,
    private val mediaAvailable: () -> Boolean,
    private val capabilities: () -> JsonValue.ObjectValue,
    private val callbacks: ControlServerCallbacks,
    private val port: Int = DiscoveryServer.CONTROL_PORT,
) : AutoCloseable {
    private data class ActiveConnection(
        val socket: SSLSocket,
        val connection: Connection,
        val controllerId: String,
        val sessionId: String,
        val dispatcher: CommandDispatcher,
    )

    private class RequestRateLimiter {
        private var windowStartMs = elapsedMs()
        private var count = 0

        @Synchronized
        fun allow(): Boolean {
            val now = elapsedMs()
            if (now - windowStartMs >= COMMAND_RATE_WINDOW_MS) {
                windowStartMs = now
                count = 0
            }
            if (count >= MAX_COMMANDS_PER_WINDOW) return false
            count += 1
            return true
        }
    }

    private class Connection(private val socket: SSLSocket) {
        private var inboundSequence = 0L
        private var outboundSequence = 0L

        fun read(): ProtocolEnvelope? {
            val frame = FrameCodec.read(socket.inputStream) ?: return null
            val envelope = ProtocolCodec.decode(frame)
            if (envelope.sequence <= inboundSequence) throw ProtocolException("sequence is not increasing")
            inboundSequence = envelope.sequence
            return envelope
        }

        @Synchronized
        fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue) {
            if (outboundSequence == Long.MAX_VALUE) throw ProtocolException("outbound sequence exhausted")
            outboundSequence += 1
            FrameCodec.write(
                socket.outputStream,
                ProtocolCodec.encode(
                    ProtocolEnvelope(
                        protocolVersion = ProtocolCodec.VERSION,
                        requestId = requestId,
                        sessionId = sessionId,
                        sequence = outboundSequence,
                        type = type,
                        payload = payload,
                    ),
                ),
            )
        }

        fun nextServerRequestId(): String = "server-${outboundSequence + 1}"
    }

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<ActiveConnection?>(null)
    private val openSockets = Collections.synchronizedSet(mutableSetOf<SSLSocket>())
    private val workers = ThreadPoolExecutor(
        MAX_CONNECTIONS,
        MAX_CONNECTIONS,
        30,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CONNECTIONS),
        { runnable -> Thread(runnable, "tvrc-control-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "control server is closed" }
        if (!running.compareAndSet(false, true)) return
        try {
            credentialStore.cleanupPending()
            val opened = identity.sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
            opened.reuseAddress = true
            opened.bind(InetSocketAddress(port), MAX_CONNECTIONS)
            TlsPolicy.configure(opened)
            serverSocket = opened
            acceptThread = Thread({ acceptLoop(opened) }, "tvrc-control-accept").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            running.set(false)
            serverSocket?.close()
            serverSocket = null
            throw error
        }
    }

    fun openPairingWindow(): PairingWindow {
        val window = pairingManager.openWindow()
        callbacks.onPairingWindow(window)
        return window
    }

    fun confirmPairing(pairingId: String, accepted: Boolean): Boolean =
        pairingManager.confirm(pairingId, accepted) != null

    fun disconnectActive() {
        val current = active.get() ?: return
        mediaCoordinator.stopSession(current.sessionId)
        current.dispatcher.disconnect()
        current.socket.close()
    }

    fun revokeController(controllerId: String) {
        credentialStore.remove(controllerId)
        sessionManager.revokeController(controllerId)
        active.get()?.takeIf { it.controllerId == controllerId }?.let { current ->
            mediaCoordinator.stopSession(current.sessionId)
            current.dispatcher.disconnect()
            current.socket.close()
        }
    }

    fun notifyMediaState(state: String) {
        val current = active.get() ?: return
        runCatching {
            current.connection.send(
                "server-media-${elapsedMs()}",
                current.sessionId,
                "media_state",
                jsonObject("state" to jsonString(state)),
            )
        }.onFailure { current.socket.close() }
    }

    private fun acceptLoop(opened: SSLServerSocket) {
        while (running.get()) {
            try {
                val socket = opened.accept() as SSLSocket
                if (!DiscoveryServer.isLocalAddress(socket.inetAddress)) {
                    socket.close()
                    continue
                }
                socket.soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
                TlsPolicy.configure(socket)
                openSockets.add(socket)
                try {
                    workers.execute { handleSocket(socket) }
                } catch (_: RejectedExecutionException) {
                    openSockets.remove(socket)
                    socket.close()
                }
            } catch (error: SocketException) {
                if (running.get()) callbacks.onNetworkFailure("control socket failed")
                break
            } catch (error: RuntimeException) {
                if (running.get()) Log.e(TAG, "Control accept loop rejected a socket", error)
            }
        }
    }

    private fun handleSocket(socket: SSLSocket) {
        try {
            socket.startHandshake()
            socket.soTimeout = PRE_AUTH_TIMEOUT_MS
            val connection = Connection(socket)
            val first = connection.read() ?: return
            when (first.type) {
                "pair_request" -> {
                    if (first.sessionId.isNotEmpty()) throw ProtocolException("pre-authentication sessionId must be empty")
                    handlePairing(connection, first)
                }
                "auth_begin" -> {
                    if (first.sessionId.isNotEmpty()) throw ProtocolException("pre-authentication sessionId must be empty")
                    handleAuthentication(connection, first, socket)
                }
                "media_attach" -> handleMediaAttachment(connection, first, socket)
                else -> sendError(connection, first, "AUTHENTICATION_REQUIRED", "authentication required")
            }
        } catch (_: SSLHandshakeException) {
            Log.w(TAG, "Rejected TLS handshake")
        } catch (_: SocketTimeoutException) {
            Log.w(TAG, "Control connection timed out")
        } catch (_: EOFException) {
            Unit
        } catch (_: ProtocolException) {
            Log.w(TAG, "Rejected malformed control protocol message")
        } catch (_: SocketException) {
            Unit
        } catch (_: IOException) {
            Unit
        } catch (error: RuntimeException) {
            Log.e(TAG, "Control connection failed: ${error.javaClass.simpleName}")
        } finally {
            openSockets.remove(socket)
            val current = active.get()
            if (current?.socket === socket && active.compareAndSet(current, null)) {
                mediaCoordinator.stopSession(current.sessionId)
                current.dispatcher.disconnect()
                sessionManager.revokeSession(current.sessionId)
                callbacks.onControllerDisconnected()
            }
            socket.close()
        }
    }

    private fun handleMediaAttachment(connection: Connection, request: ProtocolEnvelope, socket: SSLSocket) {
        requireFields(request.payload, setOf("token"))
        val current = active.get()
        if (current == null || request.sessionId != current.sessionId ||
            !sessionManager.validate(current.controllerId, current.sessionId)
        ) {
            sendError(connection, request, "MEDIA_ATTACH_REJECTED", "media attachment rejected")
            return
        }
        val attachment = mediaCoordinator.attach(
            controllerId = current.controllerId,
            sessionId = current.sessionId,
            tokenHex = request.payload.requireString("token", 64),
            output = socket.outputStream,
            closeTransport = { socket.close() },
        )
        if (attachment == null) {
            sendError(connection, request, "MEDIA_ATTACH_REJECTED", "media attachment rejected")
            return
        }
        val channel = attachment.channel
        val disconnectWatcher = Thread(
            {
                try {
                    if (socket.inputStream.read() >= 0) Log.w(TAG, "Rejected client data on media stream")
                } catch (_: IOException) {
                    Unit
                } finally {
                    channel.close()
                }
            },
            "tvrc-media-disconnect",
        ).apply { isDaemon = true }
        try {
            connection.send(request.requestId, current.sessionId, "media_attach_ack", jsonObject())
            socket.soTimeout = 0
            disconnectWatcher.start()
            channel.writeLoop()
        } finally {
            val cleanupDeadlineMs = elapsedMs() + MEDIA_CLEANUP_TIMEOUT_MS
            mediaCoordinator.stopAttachment(attachment.attachmentId)
            runCatching { socket.close() }
            disconnectWatcher.interrupt()
            val remainingMs = cleanupDeadlineMs - elapsedMs()
            if (remainingMs > 0L) runCatching { disconnectWatcher.join(remainingMs) }
        }
    }

    private fun handlePairing(connection: Connection, request: ProtocolEnvelope) {
        requireFields(request.payload, setOf("code", "controllerName", "controllerNonce"))
        val code = request.payload.requireString("code", 6)
        val controllerName = request.payload.requireString("controllerName", 64)
        val controllerNonce = Hex.decode(request.payload.requireString("controllerNonce", 64), expectedBytes = 32)
        when (val submission = pairingManager.submit(code, controllerName, controllerNonce, identity.certificateFingerprint)) {
            is PairingSubmission.Rejected -> {
                sendError(connection, request, "PAIRING_REJECTED", "pairing rejected")
                return
            }
            is PairingSubmission.AwaitingTvConfirmation -> {
                val details = submission.details
                callbacks.onPairingSas(details)
                try {
                    connection.send(
                        request.requestId,
                        "",
                        "pairing_sas",
                        jsonObject(
                            "pairingId" to jsonString(details.pairingId),
                            "sas" to jsonString(details.sas),
                            "tvNonce" to jsonString(Hex.encode(details.tvNonce)),
                            "expiresInMs" to jsonLong(maxOf(0L, details.expiresAtMs - elapsedMs())),
                        ),
                    )
                    finishPairing(connection, details)
                } catch (error: Exception) {
                    pairingManager.cancel(details.pairingId)
                    callbacks.onPairingClosed(details.pairingId)
                    throw error
                }
            }
        }
    }

    private fun finishPairing(connection: Connection, details: PairingSas) {
        val decision = pairingManager.awaitDecision(details.pairingId, PairingManager.PAIRING_TTL_MS)
        if (decision !is PairingDecision.Accepted) {
            connection.send(
                connection.nextServerRequestId(),
                "",
                "pair_rejected",
                jsonObject("reason" to jsonString("pairing was not confirmed")),
            )
            pairingManager.complete(details.pairingId)
            callbacks.onPairingClosed(details.pairingId)
            return
        }

        val credential = decision.credential
        var activated = false
        try {
            credentialStore.putPending(credential)
            connection.send(
                connection.nextServerRequestId(),
                "",
                "pair_credential",
                jsonObject(
                    "pairingId" to jsonString(details.pairingId),
                    "controllerId" to jsonString(credential.controllerId),
                    "secret" to jsonString(Hex.encode(credential.secret)),
                ),
            )
            val acknowledgement = connection.read() ?: return
            if (acknowledgement.sessionId.isNotEmpty() || acknowledgement.type != "pair_store_ack") {
                throw ProtocolException("expected pair_store_ack")
            }
            requireFields(acknowledgement.payload, setOf("pairingId", "controllerId"))
            if (acknowledgement.payload.requireString("pairingId", 32) != details.pairingId ||
                acknowledgement.payload.requireString("controllerId", 32) != credential.controllerId
            ) throw ProtocolException("pair_store_ack does not match pending credential")
            credentialStore.activate(credential.controllerId)
            activated = true
            pairingManager.complete(details.pairingId)
            connection.send(
                acknowledgement.requestId,
                "",
                "pair_complete",
                jsonObject("controllerId" to jsonString(credential.controllerId)),
            )
        } finally {
            if (!activated) credentialStore.removePending(credential.controllerId)
            pairingManager.complete(details.pairingId)
            callbacks.onPairingClosed(details.pairingId)
        }
    }

    private fun handleAuthentication(connection: Connection, request: ProtocolEnvelope, socket: SSLSocket) {
        requireFields(request.payload, setOf("controllerId", "clientNonce"))
        val controllerId = request.payload.requireString("controllerId", 32)
        val clientNonce = Hex.decode(request.payload.requireString("clientNonce", 64), expectedBytes = 32)
        val challenge = sessionManager.createChallenge(controllerId, clientNonce)
        connection.send(
            request.requestId,
            "",
            "auth_challenge",
            jsonObject(
                "challengeId" to jsonString(challenge.challengeId),
                "serverNonce" to jsonString(Hex.encode(challenge.serverNonce)),
                "expiresInMs" to jsonLong(SessionManager.CHALLENGE_TTL_MS),
            ),
        )

        val responseMessage = connection.read() ?: return
        if (responseMessage.sessionId.isNotEmpty() || responseMessage.type != "auth_response") {
            throw ProtocolException("expected auth_response")
        }
        requireFields(
            responseMessage.payload,
            setOf("controllerId", "challengeId", "clientNonce", "serverNonce", "response"),
        )
        val responseControllerId = responseMessage.payload.requireString("controllerId", 32)
        val challengeId = responseMessage.payload.requireString("challengeId", 32)
        val responseClientNonce = Hex.decode(responseMessage.payload.requireString("clientNonce", 64), 32)
        val responseServerNonce = Hex.decode(responseMessage.payload.requireString("serverNonce", 64), 32)
        val response = Hex.decode(responseMessage.payload.requireString("response", 64), 32)
        val stored = credentialStore.getActive(responseControllerId)
        val secret = stored?.takeIf {
            MessageDigest.isEqual(it.certificateFingerprint, identity.certificateFingerprint)
        }?.secret
        val session = sessionManager.authenticate(
            controllerId = responseControllerId,
            challengeId = challengeId,
            certificateFingerprint = identity.certificateFingerprint,
            clientNonce = responseClientNonce,
            serverNonce = responseServerNonce,
            secret = secret,
            response = response,
        )
        if (session == null || responseControllerId != controllerId || stored == null) {
            sendError(connection, responseMessage, "AUTHENTICATION_FAILED", "authentication failed")
            return
        }

        val dispatcher = dispatcherFactory()
        val activeConnection = ActiveConnection(socket, connection, controllerId, session.sessionId, dispatcher)
        if (!active.compareAndSet(null, activeConnection)) {
            sessionManager.revokeSession(session.sessionId)
            sendError(connection, responseMessage, "BUSY", "another controller is active")
            return
        }
        callbacks.onControllerConnected(stored.controllerName)
        connection.send(
            responseMessage.requestId,
            session.sessionId,
            "auth_complete",
            jsonObject(
                "sessionId" to jsonString(session.sessionId),
                "expiresInMs" to jsonLong(SessionManager.SESSION_TTL_MS),
                "capabilities" to capabilities(),
            ),
        )
        controlLoop(connection, activeConnection, session)
    }

    private fun controlLoop(connection: Connection, current: ActiveConnection, session: AuthSession) {
        current.socket.soTimeout = HEARTBEAT_INTERVAL_MS.toInt()
        var lastReceivedAt = elapsedMs()
        var lastPingAt = 0L
        val limiter = RequestRateLimiter()
        while (running.get() && active.get() === current) {
            val message = try {
                connection.read()
            } catch (_: SocketTimeoutException) {
                val now = elapsedMs()
                if (now - lastReceivedAt >= HEARTBEAT_TIMEOUT_MS) return
                if (now - lastPingAt >= HEARTBEAT_INTERVAL_MS) {
                    connection.send(connection.nextServerRequestId(), session.sessionId, "ping", jsonObject())
                    lastPingAt = now
                }
                continue
            } ?: return
            lastReceivedAt = elapsedMs()
            if (message.sessionId != session.sessionId || !sessionManager.validate(session.controllerId, session.sessionId)) {
                sendError(connection, message, "INVALID_SESSION", "session is invalid or expired")
                return
            }
            when (message.type) {
                "ping" -> {
                    requireFields(message.payload, emptySet())
                    connection.send(message.requestId, session.sessionId, "pong", jsonObject())
                }
                "pong" -> requireFields(message.payload, emptySet())
                "capabilities_request" -> {
                    requireFields(message.payload, emptySet())
                    connection.send(message.requestId, session.sessionId, "capabilities", capabilities())
                }
                "key_event" -> handleKeyEvent(connection, current, message, limiter)
                "media_start" -> handleMediaStart(connection, current, message)
                "media_stop" -> handleMediaStop(connection, current, message)
                "disconnect" -> {
                    requireFields(message.payload, emptySet())
                    connection.send(message.requestId, session.sessionId, "disconnect_ack", jsonObject())
                    return
                }
                else -> sendError(connection, message, "UNKNOWN_TYPE", "unknown message type")
            }
        }
    }

    private fun handleMediaStart(
        connection: Connection,
        current: ActiveConnection,
        message: ProtocolEnvelope,
    ) {
        requireFields(message.payload, emptySet())
        if (!mediaAvailable()) {
            sendError(connection, message, "MEDIA_UNAVAILABLE", "media transport is unavailable")
            return
        }
        val offer = mediaCoordinator.issueOffer(current.controllerId, current.sessionId)
        if (offer == null) {
            sendError(connection, message, "MEDIA_ALREADY_ACTIVE", "media transport is already active")
            return
        }
        connection.send(
            message.requestId,
            current.sessionId,
            "media_offer",
            jsonObject(
                "token" to jsonString(offer.token),
                "expiresInMs" to jsonLong(maxOf(0L, offer.expiresAtMs - elapsedMs())),
            ),
        )
    }

    private fun handleMediaStop(
        connection: Connection,
        current: ActiveConnection,
        message: ProtocolEnvelope,
    ) {
        requireFields(message.payload, emptySet())
        mediaCoordinator.stopSession(current.sessionId)
        connection.send(message.requestId, current.sessionId, "media_stop_ack", jsonObject())
    }

    private fun handleKeyEvent(
        connection: Connection,
        current: ActiveConnection,
        message: ProtocolEnvelope,
        limiter: RequestRateLimiter,
    ) {
        requireFields(message.payload, setOf("key", "state"), setOf("repeatCount"))
        val ack = if (!limiter.allow()) {
            dev.lucasdone.tvremote.agent.model.CommandAck(message.sequence, AckStatus.REJECTED, "rate limit exceeded")
        } else {
            val key = enumValueOrNull<LogicalKey>(message.payload.requireString("key", 32))
            val state = enumValueOrNull<KeyState>(message.payload.requireString("state", 16))
            val repeatCount = if (message.payload["repeatCount"] == null) 0 else message.payload.requireLong("repeatCount")
            if (key == null || state == null || repeatCount !in 0..MAX_REPEAT_COUNT.toLong()) {
                dev.lucasdone.tvremote.agent.model.CommandAck(message.sequence, AckStatus.REJECTED, "invalid key event")
            } else {
                current.dispatcher.dispatch(KeyEventCommand(message.sequence, key, state, repeatCount.toInt()))
            }
        }
        val fields = linkedMapOf<String, JsonValue>(
            "commandSequence" to jsonLong(ack.sequence),
            "status" to jsonString(ack.status.name),
        )
        ack.reason?.let { fields["reason"] = jsonString(it) }
        connection.send(message.requestId, current.sessionId, "command_ack", JsonValue.ObjectValue(fields))
    }

    private fun sendError(connection: Connection, request: ProtocolEnvelope, code: String, message: String) {
        connection.send(
            request.requestId,
            request.sessionId,
            "error",
            jsonObject("code" to jsonString(code), "message" to jsonString(message)),
        )
    }

    private fun requireFields(
        payload: JsonValue.ObjectValue,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        if (!payload.fields.keys.containsAll(required) || payload.fields.keys.any { it !in required && it !in optional }) {
            throw ProtocolException("payload fields do not match message schema")
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().find { it.name == value }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        pairingManager.cancel()
        disconnectActive()
        mediaCoordinator.close()
        serverSocket?.close()
        serverSocket = null
        synchronized(openSockets) { openSockets.toList() }.forEach { it.close() }
        workers.shutdownNow()
        workers.awaitTermination(2, TimeUnit.SECONDS)
        acceptThread?.interrupt()
        acceptThread = null
    }

    companion object {
        private const val TAG = "TvrcControl"
        private const val TLS_HANDSHAKE_TIMEOUT_MS = 10_000
        private const val PRE_AUTH_TIMEOUT_MS = 30_000
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
        private const val COMMAND_RATE_WINDOW_MS = 1_000L
        private const val MAX_COMMANDS_PER_WINDOW = 60
        private const val MAX_REPEAT_COUNT = 10_000
        private const val MAX_CONNECTIONS = 4
        private const val MAX_QUEUED_CONNECTIONS = 4
        private const val MEDIA_CLEANUP_TIMEOUT_MS = 2_000L

        private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
    }
}
