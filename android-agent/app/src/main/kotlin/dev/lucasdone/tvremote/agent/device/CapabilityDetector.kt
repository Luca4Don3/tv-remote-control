package dev.lucasdone.tvremote.agent.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.media.MediaCodecList
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import dev.lucasdone.tvremote.agent.model.LogicalKey
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.transport.TlsPolicy
import org.json.JSONArray
import org.json.JSONObject

enum class CapabilityStatus { SUPPORTED, UNSUPPORTED, PERMISSION_REQUIRED, UNVERIFIED }
enum class KeyCapability { SUPPORTED, BEST_EFFORT, PERMISSION_REQUIRED, UNSUPPORTED, UNVERIFIED }

data class CapabilitySnapshot(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val firmware: String,
    val apiLevel: Int,
    val abis: List<String>,
    val accessibility: CapabilityStatus,
    val mediaProjection: CapabilityStatus,
    val playbackAudio: CapabilityStatus,
    val h264Encoder: CapabilityStatus,
    val networkControl: CapabilityStatus,
    val tls12: CapabilityStatus,
    val modelVerification: CapabilityStatus,
    val mediaTransport: CapabilityStatus,
    val keySupport: Map<LogicalKey, KeyCapability>,
) {
    fun toJson(): String = JSONObject()
        .put("manufacturer", manufacturer)
        .put("brand", brand)
        .put("model", model)
        .put("device", device)
        .put("product", product)
        .put("firmware", firmware)
        .put("apiLevel", apiLevel)
        .put("abis", JSONArray(abis))
        .put("accessibility", accessibility.name)
        .put("mediaProjection", mediaProjection.name)
        .put("playbackAudio", playbackAudio.name)
        .put("h264Encoder", h264Encoder.name)
        .put("networkControl", networkControl.name)
        .put("tls12", tls12.name)
        .put("modelVerification", modelVerification.name)
        .put("mediaTransport", mediaTransport.name)
        .put("keySupport", JSONObject().also { output -> keySupport.forEach { (key, value) -> output.put(key.name, value.name) } })
        .toString(2)

    fun toProtocolJson(): JsonValue.ObjectValue = jsonObject(
        "device" to jsonObject(
            "manufacturer" to jsonString(manufacturer),
            "brand" to jsonString(brand),
            "model" to jsonString(model),
            "device" to jsonString(device),
            "product" to jsonString(product),
            "firmware" to jsonString(firmware),
            "apiLevel" to jsonLong(apiLevel.toLong()),
            "abis" to JsonValue.ArrayValue(abis.map(::jsonString)),
        ),
        "networkControl" to jsonString(networkControl.name),
        "tls12" to jsonString(tls12.name),
        "accessibility" to jsonString(accessibility.name),
        "mediaProjection" to jsonString(mediaProjection.name),
        "playbackAudio" to jsonString(playbackAudio.name),
        "h264Encoder" to jsonString(h264Encoder.name),
        "modelVerification" to jsonString(modelVerification.name),
        "mediaTransport" to jsonString(mediaTransport.name),
        "keySupport" to JsonValue.ObjectValue(linkedMapOf<String, JsonValue>().also { output ->
            keySupport.forEach { (key, value) -> output[key.name] = jsonString(value.name) }
        }),
    )
}

object CapabilityDetector {
    fun isMediaTransportAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 21 &&
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) is MediaProjectionManager &&
            hasH264Encoder()

    fun detect(context: Context): CapabilitySnapshot {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityEnabled = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }

        val tlsProbe = TlsPolicy.probe()
        val accessibilityStatus = if (accessibilityEnabled) CapabilityStatus.SUPPORTED else CapabilityStatus.PERMISSION_REQUIRED
        return CapabilitySnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            firmware = Build.DISPLAY.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            abis = supportedAbis(),
            accessibility = accessibilityStatus,
            mediaProjection = if (Build.VERSION.SDK_INT >= 21 && context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) is MediaProjectionManager) {
                CapabilityStatus.PERMISSION_REQUIRED
            } else {
                CapabilityStatus.UNSUPPORTED
            },
            playbackAudio = CapabilityStatus.UNVERIFIED,
            h264Encoder = if (hasH264Encoder()) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            networkControl = if (tlsProbe.networkControlAvailable) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            tls12 = if (tlsProbe.tls12Available) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            modelVerification = CapabilityStatus.UNVERIFIED,
            mediaTransport = if (isMediaTransportAvailable(context)) CapabilityStatus.PERMISSION_REQUIRED else CapabilityStatus.UNSUPPORTED,
            keySupport = detectKeySupport(context, accessibilityEnabled),
        )
    }

    private fun detectKeySupport(context: Context, accessibilityEnabled: Boolean): Map<LogicalKey, KeyCapability> {
        val permissionStatus = if (accessibilityEnabled) KeyCapability.SUPPORTED else KeyCapability.PERMISSION_REQUIRED
        val dpadStatus = if (!accessibilityEnabled) {
            KeyCapability.PERMISSION_REQUIRED
        } else if (Build.VERSION.SDK_INT >= 22) {
            KeyCapability.BEST_EFFORT
        } else {
            KeyCapability.UNSUPPORTED
        }
        val centerStatus = if (accessibilityEnabled) KeyCapability.BEST_EFFORT else KeyCapability.PERMISSION_REQUIRED
        val volumeStatus = if (Build.VERSION.SDK_INT >= 21 &&
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isVolumeFixed
        ) KeyCapability.UNSUPPORTED else KeyCapability.SUPPORTED
        return LogicalKey.values().associateWith { key ->
            when (key) {
                LogicalKey.BACK, LogicalKey.HOME -> permissionStatus
                LogicalKey.DPAD_UP, LogicalKey.DPAD_DOWN, LogicalKey.DPAD_LEFT, LogicalKey.DPAD_RIGHT -> dpadStatus
                LogicalKey.DPAD_CENTER -> centerStatus
                LogicalKey.VOLUME_UP,
                LogicalKey.VOLUME_DOWN,
                LogicalKey.VOLUME_MUTE,
                -> volumeStatus
                LogicalKey.MEDIA_PLAY_PAUSE,
                LogicalKey.MEDIA_STOP,
                LogicalKey.MEDIA_NEXT,
                LogicalKey.MEDIA_PREVIOUS,
                -> KeyCapability.BEST_EFFORT
                LogicalKey.MENU,
                LogicalKey.CHANNEL_UP,
                LogicalKey.CHANNEL_DOWN,
                LogicalKey.POWER,
                -> KeyCapability.UNSUPPORTED
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun supportedAbis(): List<String> = if (Build.VERSION.SDK_INT >= 21) {
        Build.SUPPORTED_ABIS.toList()
    } else {
        listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotBlank() }
    }

    private fun hasH264Encoder(): Boolean = if (Build.VERSION.SDK_INT >= 21) {
        hasH264EncoderModern()
    } else {
        hasH264EncoderLegacy()
    }

    @TargetApi(21)
    @SuppressLint("UseRequiresApi")
    private fun hasH264EncoderModern(): Boolean = try {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        codecs.any { codec -> codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
    } catch (_: RuntimeException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun hasH264EncoderLegacy(): Boolean = try {
        (0 until MediaCodecList.getCodecCount())
            .map(MediaCodecList::getCodecInfoAt)
            .any { codec -> codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
    } catch (_: RuntimeException) {
        false
    }
}
