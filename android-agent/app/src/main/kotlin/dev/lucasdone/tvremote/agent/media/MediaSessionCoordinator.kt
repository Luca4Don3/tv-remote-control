package dev.lucasdone.tvremote.agent.media

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import dev.lucasdone.tvremote.agent.protocol.Hex
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class MediaAttachOffer(val token: String, val expiresAtMs: Long)
data class MediaAttachment(val attachmentId: Long, val channel: MediaPacketChannel)

class MediaSessionCoordinator(
    private val random: SecureRandom = SecureRandom(),
    // 与 ControlServer/PairingManager/SessionManager 共用 System.nanoTime() 单调时钟，
    // 避免跨组件比较 expiresAtMs 时出现时钟源偏移。
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onState: (String) -> Unit = {},
    private val scheduleExpiry: (Long, () -> Unit) -> Unit = { delayMs, action ->
        EXPIRY_EXECUTOR.schedule(action, delayMs, TimeUnit.MILLISECONDS)
    },
) : AutoCloseable {
    private data class Pending(
        val controllerId: String,
        val sessionId: String,
        val token: ByteArray,
        val expiresAtMs: Long,
    )

    private var pending: Pending? = null
    private var offerGeneration = 0L
    private var nextAttachmentId = 0L
    private var attachmentId: Long? = null
    private var attachedControllerId: String? = null
    private var attachedSessionId: String? = null
    private var channel: MediaPacketChannel? = null
    private var video: ProjectionCapture? = null
    private var audio: PlaybackAudioCapture? = null

    @Synchronized
    fun issueOffer(controllerId: String, sessionId: String): MediaAttachOffer? {
        require(controllerId.isNotEmpty())
        require(sessionId.isNotEmpty())
        if (pending != null || attachedSessionId != null) return null
        val value = ByteArray(32).also(random::nextBytes)
        val expiresAt = nowMs() + ATTACH_TTL_MS
        offerGeneration = nextGeneration(offerGeneration)
        val generation = offerGeneration
        pending = Pending(controllerId, sessionId, value, expiresAt)
        onState("media_requested")
        scheduleExpiry(ATTACH_TTL_MS) { expireOffer(generation) }
        return MediaAttachOffer(Hex.encode(value), expiresAt)
    }

    @Synchronized
    fun attach(
        controllerId: String,
        sessionId: String,
        tokenHex: String,
        output: OutputStream,
        closeTransport: () -> Unit = { output.close() },
    ): MediaAttachment? {
        val expected = pending ?: return null
        val supplied = runCatching { Hex.decode(tokenHex, 32) }.getOrNull() ?: return null
        val now = nowMs()
        if (now >= expected.expiresAtMs) {
            pending = null
            offerGeneration = nextGeneration(offerGeneration)
            onState("media_idle")
            return null
        }
        if (expected.controllerId != controllerId || expected.sessionId != sessionId ||
            !MessageDigest.isEqual(expected.token, supplied) || channel != null
        ) {
            return null
        }
        pending = null
        offerGeneration = nextGeneration(offerGeneration)
        nextAttachmentId = if (nextAttachmentId == Long.MAX_VALUE) 1L else nextAttachmentId + 1L
        attachmentId = nextAttachmentId
        attachedControllerId = controllerId
        attachedSessionId = sessionId
        val attachedChannel = MediaPacketChannel(output, closeTransport)
        channel = attachedChannel
        onState("media_permission_required")
        val expectedAttachmentId = checkNotNull(attachmentId)
        scheduleExpiry(AUTHORIZATION_TTL_MS) { expireAuthorization(expectedAttachmentId) }
        return MediaAttachment(expectedAttachmentId, attachedChannel)
    }

    @Synchronized
    fun startProjection(context: Context, projection: MediaProjection, captureAudio: Boolean, expectedAttachmentId: Long) {
        check(Build.VERSION.SDK_INT >= 21) { "MediaProjection requires API 21" }
        check(attachmentId == expectedAttachmentId) { "media authorization is stale" }
        val sink = channel ?: throw IllegalStateException("media channel is not attached")
        check(attachedSessionId != null) { "media session is not attached" }
        check(video == null) { "projection is already active" }
        lateinit var videoCapture: ProjectionCapture
        videoCapture = ProjectionCapture(
            context,
            projection,
            sink,
            onFailure = { reason -> handleVideoFailure(videoCapture, expectedAttachmentId, reason) },
            onTerminated = { stopAttachment(expectedAttachmentId) },
        )
        video = videoCapture
        videoCapture.start()
        if (captureAudio && Build.VERSION.SDK_INT >= 29) {
            audio = runCatching {
                lateinit var candidate: PlaybackAudioCapture
                candidate = PlaybackAudioCapture(
                    projection,
                    sink,
                    onFailure = { handleAudioFailure(candidate) },
                )
                audio = candidate
                candidate.also { it.start() }
            }.getOrElse {
                onState("video_only_audio_unavailable")
                null
            }
        }
        if (audio?.isRunning() == true) {
            onState("media_streaming")
        } else {
            audio = null
            onState("media_streaming_video_only")
        }
    }

    @Synchronized
    fun stopSession(sessionId: String) {
        if (pending?.sessionId != sessionId && attachedSessionId != sessionId) return
        val detached = detachLocked()
        onState("media_idle")
        closeDetached(detached)
    }

    @Synchronized
    fun hasAttachedSession(controllerId: String, sessionId: String): Boolean {
        return attachedControllerId == controllerId && attachedSessionId == sessionId && channel != null
    }

    @Synchronized
    fun currentAttachmentId(): Long? = attachmentId

    @Synchronized
    fun stopAttachment(expectedAttachmentId: Long) {
        if (attachmentId != expectedAttachmentId) return
        val detached = detachLocked()
        onState("media_idle")
        closeDetached(detached)
    }

    @Synchronized
    private fun handleAudioFailure(failed: PlaybackAudioCapture) {
        if (audio !== failed) return
        audio = null
        onState("video_only_audio_unavailable")
    }

    @Synchronized
    private fun handleVideoFailure(failed: ProjectionCapture, expectedAttachmentId: Long, reason: String) {
        if (video !== failed || attachmentId != expectedAttachmentId) return
        onState(reason)
    }

    @Synchronized
    private fun expireOffer(generation: Long) {
        val expected = pending ?: return
        if (offerGeneration != generation) return
        val remaining = expected.expiresAtMs - nowMs()
        if (remaining > 0L) {
            scheduleExpiry(remaining) { expireOffer(generation) }
            return
        }
        pending = null
        offerGeneration = nextGeneration(offerGeneration)
        onState("media_idle")
    }

    // 到期回调运行在单线程 EXPIRY_EXECUTOR 上；锁内只做状态摘除，
    // 耗时资源释放（channel/audio/video 的 join）移到锁外 closeDetached。
    private fun expireAuthorization(expectedAttachmentId: Long) {
        val detached = synchronized(this) {
            if (attachmentId != expectedAttachmentId || video != null) return
            detachLocked()
        }
        onState("media_authorization_timeout")
        closeDetached(detached)
    }

    @Synchronized
    override fun close() {
        val hadMedia = pending != null || attachedSessionId != null
        val detached = detachLocked()
        if (hadMedia) onState("media_idle")
        closeDetached(detached)
    }

    private fun detachLocked(): List<AutoCloseable> {
        val previousChannel = channel
        val previousAudio = audio
        val previousVideo = video
        channel = null
        audio = null
        video = null
        pending = null
        offerGeneration = nextGeneration(offerGeneration)
        attachmentId = null
        attachedControllerId = null
        attachedSessionId = null
        return listOfNotNull(previousChannel, previousAudio, previousVideo)
    }

    private fun closeDetached(detached: List<AutoCloseable>) {
        detached.forEach { runCatching { it.close() } }
    }

    companion object {
        const val ATTACH_TTL_MS = 10_000L
        const val AUTHORIZATION_TTL_MS = 120_000L
        private val EXPIRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "tvrc-media-expiry").apply { isDaemon = true }
        }

        private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}
