package dev.lucasdone.tvremote.agent.media

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureQuality(val width: Int, val height: Int, val framesPerSecond: Int, val bitrate: Int) {
    init {
        require(width > 0 && height > 0 && framesPerSecond > 0 && bitrate > 0)
    }

    companion object {
        val DEFAULT = CaptureQuality(1280, 720, 15, 2_000_000)
        val REDUCED = CaptureQuality(960, 540, 10, 1_000_000)
    }
}

@TargetApi(21)
@SuppressLint("UseRequiresApi")
class ProjectionCapture(
    context: Context,
    private val projection: MediaProjection,
    private val sink: MediaPacketSink,
    private val onFailure: (String) -> Unit,
    private val onTerminated: () -> Unit = {},
    initialQuality: CaptureQuality = CaptureQuality.DEFAULT,
) : AutoCloseable {
    private val densityDpi = context.resources.displayMetrics.densityDpi
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val running = AtomicBoolean(false)
    private val worker = HandlerThread("tvrc-projection-encoder")
    private val reduction = QualityReductionController()
    private lateinit var handler: Handler
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var legacyDrainThread: Thread? = null
    private val legacyEncoderReaper = LegacyEncoderReaper<MediaCodec>(
        WORKER_JOIN_TIMEOUT_MS,
        FORCED_RELEASE_TIMEOUT_MS,
        ::elapsedRealtimeMs,
    )
    private var currentQuality = initialQuality
    private val packetState = ProjectionPacketState()
    private var thermalListenerRegistered = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop(stopProjection = false, notifyTermination = true)
        }
    }

    private val thermalListener = if (Build.VERSION.SDK_INT >= 29) {
        PowerManager.OnThermalStatusChangedListener { status ->
            if (reduction.onThermalStatus(status)) scheduleReduction()
        }
    } else null

    @Synchronized
    fun start() {
        check(running.compareAndSet(false, true)) { "projection capture is already active" }
        try {
            worker.start()
            handler = Handler(worker.looper)
            projection.registerCallback(projectionCallback, handler)
            createEncoder(currentQuality)
            registerThermalListener()
        } catch (error: Exception) {
            onFailure("video_encoder_start_failed")
            stop(stopProjection = true, notifyTermination = false)
            throw error
        }
    }

    fun requestKeyFrame() {
        if (!running.get()) return
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    override fun close() = stop(stopProjection = true, notifyTermination = false)

    private fun createEncoder(quality: CaptureQuality, reduced: Boolean = false) {
        val encoderGeneration = packetState.beginEncoder(reduced)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = encoder
        if (Build.VERSION.SDK_INT >= 23) encoder.setCallback(callbackFor(encoderGeneration, quality), handler)
        encoder.configure(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, quality.width, quality.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, quality.framesPerSecond)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
            },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        inputSurface = encoder.createInputSurface()
        display = projection.createVirtualDisplay(
            "TV Remote screen",
            quality.width,
            quality.height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            handler,
        )
        encoder.start()
        if (Build.VERSION.SDK_INT < 23) {
            legacyDrainThread = Thread(
                { legacyDrainLoop(encoder, encoderGeneration, quality) },
                "tvrc-projection-drain-$encoderGeneration",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun callbackFor(encoderGeneration: Long, quality: CaptureQuality) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!isCurrentGeneration(encoderGeneration)) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            handleOutput(codec, index, info, encoderGeneration, quality)
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (isCurrentGeneration(encoderGeneration)) failCurrentEncoder("video_encoder_failed")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (isCurrentGeneration(encoderGeneration)) handleFormatChanged(format, encoderGeneration, quality)
        }
    }

    private fun legacyDrainLoop(encoder: MediaCodec, encoderGeneration: Long, quality: CaptureQuality) {
        val info = MediaCodec.BufferInfo()
        try {
            while (isCurrentGeneration(encoderGeneration) && !Thread.currentThread().isInterrupted) {
                when (val index = encoder.dequeueOutputBuffer(info, 20_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleFormatChanged(encoder.outputFormat, encoderGeneration, quality)
                    else -> if (index >= 0) handleOutput(encoder, index, info, encoderGeneration, quality)
                }
            }
        } catch (_: RuntimeException) {
            if (isCurrentGeneration(encoderGeneration)) failCurrentEncoder("video_encoder_failed")
        } finally {
            legacyEncoderReaper.reap()
        }
    }

    private fun handleOutput(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        encoderGeneration: Long,
        quality: CaptureQuality,
    ) {
        try {
            if (!isCurrentGeneration(encoderGeneration)) return
            val output = encoder.getOutputBuffer(index) ?: return
            if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            output.position(info.offset)
            output.limit(info.offset + info.size)
            val payload = H264Normalizer.accessUnit(ByteArray(info.size).also(output::get))
            val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val offered = packetState.offerFrame(encoderGeneration, keyFrame, sink) { ticket ->
                MediaPacket(
                    MediaPacketHeader(
                        track = MediaTrack.VIDEO,
                        keyFrame = keyFrame,
                        discontinuity = ticket.discontinuity,
                        endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0,
                        sequence = ticket.sequence,
                        presentationTimeUs = maxOf(0L, info.presentationTimeUs),
                        payloadLength = payload.size,
                        codecConfigId = ticket.configId,
                        width = quality.width,
                        height = quality.height,
                    ),
                    payload,
                )
            } ?: return
            when (offered.result) {
                ProjectionPacketState.OfferResult.REDUCTION_FAILED -> return failReduction()
                ProjectionPacketState.OfferResult.STALE -> return
                else -> Unit
            }
            if (offered.firstReducedKeyFrame) {
                check(reduction.markReduced()) { "quality reduction state changed unexpectedly" }
                return
            }
            val ordinaryVideo = !keyFrame
            val accepted = offered.result == ProjectionPacketState.OfferResult.ACCEPTED
            if (reduction.onVideoResult(accepted, ordinaryVideo)) scheduleReduction()
            if (!accepted) {
                requestKeyFrame()
            }
        } catch (_: RuntimeException) {
            failCurrentEncoder("video_encoder_output_failed")
        } finally {
            runCatching { encoder.releaseOutputBuffer(index, false) }
        }
    }

    private fun handleFormatChanged(format: MediaFormat, encoderGeneration: Long, quality: CaptureQuality) {
        try {
            if (!isCurrentGeneration(encoderGeneration)) return
            val csd0 = format.getByteBuffer("csd-0")?.copyBytes() ?: error("missing SPS")
            val csd1 = format.getByteBuffer("csd-1")?.copyBytes() ?: error("missing PPS")
            val configuration = H264Normalizer.configurationRecord(csd0, csd1)
            when (packetState.offerConfig(encoderGeneration, sink) { ticket ->
                MediaPacket(
                    MediaPacketHeader(
                        track = MediaTrack.VIDEO,
                        codecConfig = true,
                        sequence = ticket.sequence,
                        presentationTimeUs = 0,
                        payloadLength = configuration.size,
                        codecConfigId = ticket.configId,
                        width = quality.width,
                        height = quality.height,
                    ),
                    configuration,
                )
            }) {
                ProjectionPacketState.OfferResult.REDUCTION_FAILED -> return failReduction()
                ProjectionPacketState.OfferResult.REJECTED -> error("media queue rejected codec configuration")
                ProjectionPacketState.OfferResult.STALE -> return
                ProjectionPacketState.OfferResult.ACCEPTED -> requestKeyFrame()
            }
        } catch (_: RuntimeException) {
            failCurrentEncoder("video_codec_configuration_failed")
        }
    }

    private fun scheduleReduction() {
        if (!running.get()) return
        handler.post {
            // 与 stop()/releaseEncoder() 互斥，避免降级流程与停止竞态。
            synchronized(this) {
                if (!running.get() || reduction.snapshot() != QualityReductionController.State.PENDING) return@post
                try {
                    if (!releaseEncoder()) return@post failReduction()
                    currentQuality = CaptureQuality.REDUCED
                    createEncoder(currentQuality, reduced = true)
                } catch (_: Exception) {
                    failReduction()
                }
            }
        }
    }

    @Synchronized
    private fun releaseEncoder(): Boolean {
        packetState.invalidateEncoder()
        // 先给已退休条目一次 join 机会，配合超时强制释放保证 codec 最终被 release。
        legacyEncoderReaper.reap(wait = true)
        runCatching { display?.release() }
        display = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        val drain = legacyDrainThread
        val encoder = codec
        val drainExited = when {
            encoder == null -> {
                drain?.interrupt()
                if (drain !== Thread.currentThread()) runCatching { drain?.join(WORKER_JOIN_TIMEOUT_MS) }
                drain == null || drain === Thread.currentThread() || !drain.isAlive
            }
            drain == null -> {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                true
            }
            else -> legacyEncoderReaper.retire(
                encoder,
                drain,
                stop = { it.stop() },
                release = { it.release() },
            )
        }
        legacyDrainThread = null
        codec = null
        return drainExited
    }

    private fun failCurrentEncoder(reason: String) {
        if (reduction.snapshot() == QualityReductionController.State.PENDING) failReduction() else fail(reason)
    }

    private fun failReduction() {
        reduction.markFailed()
        fail("video_quality_reduction_failed")
    }

    private fun fail(reason: String) {
        // 仅首个调用触发失败回调与终止流程，重复调用为空操作。
        if (!running.compareAndSet(true, false)) return
        onFailure(reason)
        stopNow(stopProjection = true, notifyTermination = true)
    }

    private fun stop(stopProjection: Boolean, notifyTermination: Boolean) {
        if (!running.compareAndSet(true, false)) return
        stopNow(stopProjection, notifyTermination)
    }

    private fun stopNow(stopProjection: Boolean, notifyTermination: Boolean) {
        synchronized(this) {
            unregisterThermalListener()
            releaseEncoder()
            legacyEncoderReaper.reap(wait = true)
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (stopProjection) runCatching { projection.stop() }
            worker.quitSafely()
        }
        if (worker !== Thread.currentThread()) runCatching { worker.join(WORKER_JOIN_TIMEOUT_MS) }
        if (notifyTermination) onTerminated()
    }

    private fun isCurrentGeneration(expected: Long): Boolean = running.get() && packetState.isCurrent(expected)

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= 29 && thermalListener != null) {
            powerManager.addThermalStatusListener(thermalListener)
            thermalListenerRegistered = true
        }
    }

    private fun unregisterThermalListener() {
        val listener = thermalListener
        if (Build.VERSION.SDK_INT >= 29 && thermalListenerRegistered && listener != null) {
            runCatching { powerManager.removeThermalStatusListener(listener) }
            thermalListenerRegistered = false
        }
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val duplicate = duplicate()
        return ByteArray(duplicate.remaining()).also(duplicate::get)
    }

    companion object {
        private const val WORKER_JOIN_TIMEOUT_MS = 250L
        private const val FORCED_RELEASE_TIMEOUT_MS = 5_000L

        private fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L
    }
}
