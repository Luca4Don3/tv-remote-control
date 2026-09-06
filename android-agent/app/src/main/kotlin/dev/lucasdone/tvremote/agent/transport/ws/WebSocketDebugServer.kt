package dev.lucasdone.tvremote.agent.transport.ws

import android.util.Log
import dev.lucasdone.tvremote.agent.auth.KeystoreCredentialStore
import dev.lucasdone.tvremote.agent.command.AccessibilityCommandExecutor
import dev.lucasdone.tvremote.agent.command.AccessibilityTextCommandExecutor
import dev.lucasdone.tvremote.agent.command.CommandDispatcher
import dev.lucasdone.tvremote.agent.command.KeyStateTracker
import dev.lucasdone.tvremote.agent.command.MediaCommandExecutor
import dev.lucasdone.tvremote.agent.command.TextCommandDispatcher
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.model.TextAction
import dev.lucasdone.tvremote.agent.model.TextCommand
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 明文 WebSocket 调试通道（端口 47833）的 Android 托管壳。
 *
 * 协议处理全部在 `:protocol-core` 的 [WsDebugChannel]——回环测试锚定**同一实现**，
 * 消除测试服务端与真实服务端的行为漂移；本类只负责 socket 接受线程池、
 * Android 凭据存储与命令执行器注入、连接生命周期日志。
 */
class WebSocketDebugServer(
    private val credentialStore: KeystoreCredentialStore,
    private val dispatcherFactory: () -> CommandDispatcher,
    private val textDispatcherFactory: () -> TextCommandDispatcher,
    private val port: Int = DEBUG_PORT,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(4)) { runnable ->
        Thread(runnable, "tvrc-ws-worker").apply { isDaemon = true }
    }
    private val openSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())

    private val credentials = object : WsDebugCredentialLookup {
        override fun getActive(controllerId: String): WsDebugChannelCredential? =
            credentialStore.getActive(controllerId)?.let { stored ->
                WsDebugChannelCredential(
                    controllerId = stored.controllerId,
                    controllerName = stored.controllerName,
                    secret = stored.secret,
                )
            }
    }

    private val channel = WsDebugChannel(credentials) {
        val dispatcher = dispatcherFactory()
        val textDispatcher = textDispatcherFactory()
        object : WsDebugCommandHandler {
            override fun keyEvent(sequence: Long, key: LogicalKey, state: KeyState, repeatCount: Int) =
                dispatcher.dispatch(KeyEventCommand(sequence, key, state, repeatCount))

            override fun text(sequence: Long, action: TextAction, text: String) =
                textDispatcher.dispatch(TextCommand(sequence, action, text))

            override fun onConnectionClosed() {
                // 断连时释放按住的键（对齐 TLS 服务端语义）
                dispatcher.disconnect()
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), 4)
            serverSocket = socket
        } catch (error: IOException) {
            running.set(false)
            throw error
        }
        val thread = Thread({ acceptLoop() }, "tvrc-ws-accept")
        thread.isDaemon = true
        thread.start()
        acceptThread = thread
        Log.i(TAG, "WebSocket debug channel listening on port $port")
    }

    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        serverSocket?.close()
        serverSocket = null
        synchronized(openSockets) { openSockets.toList() }.forEach { runCatching { it.close() } }
        openSockets.clear()
        workers.shutdownNow()
        workers.awaitTermination(2, TimeUnit.SECONDS)
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                break
            }
            openSockets.add(socket)
            try {
                workers.execute { handleConnection(socket) }
            } catch (_: Exception) {
                runCatching { socket.close() }
                openSockets.remove(socket)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            channel.serve(socket)
        } catch (_: WsFrameCodec.ClosedException) {
            Unit
        } catch (_: java.net.SocketTimeoutException) {
            Log.w(TAG, "WebSocket debug connection timed out")
        } catch (_: SocketException) {
            Unit
        } catch (_: IOException) {
            Unit
        } catch (error: dev.lucasdone.tvremote.agent.protocol.ProtocolException) {
            Log.w(TAG, "Rejected malformed debug channel message: ${error.message}")
        } catch (error: RuntimeException) {
            Log.e(TAG, "WebSocket debug connection failed: ${error.javaClass.simpleName}")
        } finally {
            openSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "TvrcWsDebug"
        const val DEBUG_PORT = 47833
    }
}
