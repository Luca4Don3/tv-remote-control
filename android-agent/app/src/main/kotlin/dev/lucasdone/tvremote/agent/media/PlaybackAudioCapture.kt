package dev.lucasdone.tvremote.agent.media

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(29)
@SuppressLint("MissingPermission", "UseRequiresApi")
class PlaybackAudioCapture(
    projection: MediaProjection,
    private val sink: MediaPacketSink,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private data class PreparedAudio(
        val recorder: AudioRecord,
        val codec: MediaCodec,
        val minimumBuffer: Int,
    )

    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val prepared = prepare(projection)
    private val minimumBuffer = prepared.minimumBuffer
    private val recorder = prepared.recorder
    private val codec = prepared.codec
    private var feeder: Thread? = null
    private var drainer: Thread? = null
    private var sequence = 0L
    private var configId = 0L
    private var lastPresentationTimeUs = 0L

    fun start() {
        check(running.compareAndSet(false, true)) { "playback capture is already active" }
        try {
            codec.start()
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "playback capture did not start" }
            feeder = Thread(::feedLoop, "tvrc-playback-capture").apply { isDaemon = true; start() }
            drainer = Thread(::drainLoop, "tvrc-aac-encoder").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            fail("playback_audio_start_failed")
            throw error
        }
    }

    fun isRunning(): Boolean = running.get() && !released.get()

    private fun feedLoop() {
        val pcm = ByteArray(minimumBuffer)
        try {
            while (running.get()) {
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read < 0) throw IllegalStateException("AudioRecord read failed: $read")
                if (read == 0) continue
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex < 0) continue
                val input = codec.getInputBuffer(inputIndex) ?: throw IllegalStateException("AAC input buffer is unavailable")
                input.clear()
                input.put(pcm, 0, read)
                codec.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1_000L, 0)
            }
        } catch (_: RuntimeException) {
            fail("playback_audio_capture_failed")
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                when (val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sendConfig(codec.outputFormat)
                    else -> if (index >= 0) sendOutput(index, info)
                }
            }
        } catch (_: RuntimeException) {
            fail("playback_audio_encoder_failed")
        }
    }

    @Synchronized
    private fun sendConfig(format: MediaFormat) {
        val csd = format.getByteBuffer("csd-0")?.let { source ->
            val copy = source.duplicate()
            ByteArray(copy.remaining()).also(copy::get)
        } ?: throw IllegalStateException("AAC AudioSpecificConfig is unavailable")
        configId = (configId + 1) and UINT32_MAX
        sequence = (sequence + 1) and UINT32_MAX
        check(
            sink.offer(
                MediaPacket(
                    MediaPacketHeader(
                        track = MediaTrack.AUDIO,
                        codecConfig = true,
                        sequence = sequence,
                        presentationTimeUs = 0,
                        payloadLength = csd.size,
                        codecConfigId = configId,
                    ),
                    csd,
                ),
            ),
        ) { "media queue rejected AAC configuration" }
    }

    @Synchronized
    private fun sendOutput(index: Int, info: MediaCodec.BufferInfo) {
        try {
            if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            val output = codec.getOutputBuffer(index) ?: return
            output.position(info.offset)
            output.limit(info.offset + info.size)
            val payload = ByteArray(info.size).also(output::get)
            sequence = (sequence + 1) and UINT32_MAX
            lastPresentationTimeUs = maxOf(0L, info.presentationTimeUs)
            sink.offer(
                MediaPacket(
                    MediaPacketHeader(
                        track = MediaTrack.AUDIO,
                        endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0,
                        sequence = sequence,
                        presentationTimeUs = lastPresentationTimeUs,
                        payloadLength = payload.size,
                        codecConfigId = configId,
                    ),
                    payload,
                ),
            )
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        runCatching { recorder.stop() }
        feeder?.interrupt()
        drainer?.interrupt()
        val current = Thread.currentThread()
        if (feeder !== current) runCatching { feeder?.join(WORKER_JOIN_TIMEOUT_MS) }
        if (drainer !== current) runCatching { drainer?.join(WORKER_JOIN_TIMEOUT_MS) }
        runCatching { codec.stop() }
        runCatching { recorder.release() }
        runCatching { codec.release() }
    }

    private fun fail(reason: String) {
        if (!running.compareAndSet(true, false)) return
        runCatching { sendEndOfStream() }
        close()
        onFailure(reason)
    }

    @Synchronized
    private fun sendEndOfStream() {
        sequence = (sequence + 1) and UINT32_MAX
        sink.offer(
            MediaPacket(
                MediaPacketHeader(
                    track = MediaTrack.AUDIO,
                    endOfStream = true,
                    sequence = sequence,
                    presentationTimeUs = lastPresentationTimeUs,
                    payloadLength = 0,
                    codecConfigId = configId,
                ),
                byteArrayOf(),
            ),
        )
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 2
        private const val BITRATE = 128_000
        private const val CODEC_TIMEOUT_US = 20_000L
        private const val WORKER_JOIN_TIMEOUT_MS = 500L
        private const val UINT32_MAX = 0xffff_ffffL

        private fun prepare(projection: MediaProjection): PreparedAudio {
            var recorder: AudioRecord? = null
            var codec: MediaCodec? = null
            try {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minimumBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(SAMPLE_RATE / 5)
                recorder = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minimumBuffer * 2)
                    .setAudioPlaybackCaptureConfig(
                        AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .build(),
                    )
                    .build()
                require(recorder.state == AudioRecord.STATE_INITIALIZED) { "playback AudioRecord is unavailable" }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minimumBuffer)
                    },
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                return PreparedAudio(checkNotNull(recorder), checkNotNull(codec), minimumBuffer)
            } catch (error: Exception) {
                runCatching { recorder?.release() }
                runCatching { codec?.release() }
                throw error
            }
        }
    }
}
