package dev.lucasdone.tvremote.xiaomi

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.jsonBoolean
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.transport.TlsPolicy
import dev.lucasdone.tvremote.xiaomi.backend.KeyBackend
import dev.lucasdone.tvremote.xiaomi.input.RemoteInputMethodService

object TvCapabilities {
    fun snapshot(context: Context, backend: KeyBackend?): JsonValue.ObjectValue {
        val volumeFixed = Build.VERSION.SDK_INT >= 21 &&
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isVolumeFixed
        val tls = TlsPolicy.probe()
        return jsonObject(
            "device" to jsonObject(
                "manufacturer" to jsonString(Build.MANUFACTURER.orEmpty()), "brand" to jsonString(Build.BRAND.orEmpty()),
                "model" to jsonString(Build.MODEL.orEmpty()), "device" to jsonString(Build.DEVICE.orEmpty()),
                "product" to jsonString(Build.PRODUCT.orEmpty()), "firmware" to jsonString(Build.DISPLAY.orEmpty()),
                "apiLevel" to jsonLong(Build.VERSION.SDK_INT.toLong()),
                "abis" to JsonValue.ArrayValue(abis().map(::jsonString)),
            ),
            "networkControl" to jsonString(if (tls.networkControlAvailable) "SUPPORTED" else "UNSUPPORTED"),
            "tls12" to jsonString(if (tls.tls12Available) "SUPPORTED" else "UNSUPPORTED"),
            "isTelevision" to jsonBoolean(isTelevision(context)),
            "accessibility" to jsonString("UNSUPPORTED"), "mediaProjection" to jsonString("UNSUPPORTED"),
            "playbackAudio" to jsonString("UNSUPPORTED"), "h264Encoder" to jsonString("UNSUPPORTED"),
            "mediaTransport" to jsonString("UNSUPPORTED"), "modelVerification" to jsonString("UNVERIFIED"),
            "textInput" to jsonString(if (RemoteInputMethodService.session.ticket() != null) "SUPPORTED" else "PERMISSION_REQUIRED"),
            "keySupport" to JsonValue.ObjectValue(linkedMapOf<String, JsonValue>().also { result ->
                LogicalKey.values().forEach { key -> result[key.name] = jsonString(keyState(key, backend, volumeFixed)) }
            }),
        )
    }
    /** A dead backend must never keep reporting BEST_EFFORT (or anything above) for its keys. */
    internal fun keyState(key: LogicalKey, backend: KeyBackend?, volumeFixed: Boolean): String = when {
        backend?.available == true && key in backend.keys -> "BEST_EFFORT"
        key in VOLUME_KEYS -> if (volumeFixed) "UNSUPPORTED" else "BEST_EFFORT"
        key in MEDIA_KEYS -> "BEST_EFFORT"
        else -> "UNSUPPORTED"
    }
    /** Best-effort device form; used for UI hints, never to assume control permissions. */
    private fun isTelevision(context: Context): Boolean =
        context.packageManager.hasSystemFeature(FEATURE_LEANBACK) ||
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    @Suppress("DEPRECATION")
    private fun abis() = if (Build.VERSION.SDK_INT >= 21) Build.SUPPORTED_ABIS.toList()
        else listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotBlank() }
    val VOLUME_KEYS = setOf(LogicalKey.VOLUME_UP, LogicalKey.VOLUME_DOWN, LogicalKey.VOLUME_MUTE)
    val MEDIA_KEYS = setOf(LogicalKey.MEDIA_PLAY_PAUSE, LogicalKey.MEDIA_STOP, LogicalKey.MEDIA_NEXT, LogicalKey.MEDIA_PREVIOUS)
    // Literal (not PackageManager.FEATURE_LEANBACK, which is API 21) keeps minSdk 19 explicit.
    private const val FEATURE_LEANBACK = "android.software.leanback"
}
