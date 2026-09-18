package dev.tvremote.agent.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.MediaCodecList
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import dev.tvremote.agent.adapter.KeyBackend
import dev.tvremote.agent.input.RemoteInputMethodService
import dev.tvremote.agent.model.LogicalKey
import dev.tvremote.agent.protocol.JsonValue
import dev.tvremote.agent.protocol.jsonBoolean
import dev.tvremote.agent.protocol.jsonLong
import dev.tvremote.agent.protocol.jsonObject
import dev.tvremote.agent.protocol.jsonString
import dev.tvremote.agent.transport.TlsPolicy
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
    val isTelevision: Boolean,
    val accessibility: CapabilityStatus,
    val mediaProjection: CapabilityStatus,
    val playbackAudio: CapabilityStatus,
    val h264Encoder: CapabilityStatus,
    val networkControl: CapabilityStatus,
    val tls12: CapabilityStatus,
    val modelVerification: CapabilityStatus,
    val mediaTransport: CapabilityStatus,
    val textInput: CapabilityStatus,
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
        .put("isTelevision", isTelevision)
        .put("accessibility", accessibility.name)
        .put("mediaProjection", mediaProjection.name)
        .put("playbackAudio", playbackAudio.name)
        .put("h264Encoder", h264Encoder.name)
        .put("networkControl", networkControl.name)
        .put("tls12", tls12.name)
        .put("modelVerification", modelVerification.name)
        .put("mediaTransport", mediaTransport.name)
        .put("textInput", textInput.name)
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
        "isTelevision" to jsonBoolean(isTelevision),
        "networkControl" to jsonString(networkControl.name),
        "tls12" to jsonString(tls12.name),
        "accessibility" to jsonString(accessibility.name),
        "mediaProjection" to jsonString(mediaProjection.name),
        "playbackAudio" to jsonString(playbackAudio.name),
        "h264Encoder" to jsonString(h264Encoder.name),
        "modelVerification" to jsonString(modelVerification.name),
        "mediaTransport" to jsonString(mediaTransport.name),
        "textInput" to jsonString(textInput.name),
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

    fun detect(
        context: Context,
        profile: DeviceProfile = DeviceProfiles.resolve(context),
        backend: KeyBackend? = null,
    ): CapabilitySnapshot {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityEnabled = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }

        val tlsProbe = TlsPolicy.probe()
        val detectedAccessibility = if (accessibilityEnabled) CapabilityStatus.SUPPORTED else CapabilityStatus.PERMISSION_REQUIRED
        val volumeFixed = Build.VERSION.SDK_INT >= 21 &&
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isVolumeFixed
        val override = profile.capabilityOverride
        return CapabilitySnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            firmware = Build.DISPLAY.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            abis = supportedAbis(),
            isTelevision = isTelevision(context),
            accessibility = override.accessibility ?: detectedAccessibility,
            mediaProjection = override.mediaProjection ?: if (Build.VERSION.SDK_INT >= 21 && context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) is MediaProjectionManager) {
                CapabilityStatus.PERMISSION_REQUIRED
            } else {
                CapabilityStatus.UNSUPPORTED
            },
            playbackAudio = override.playbackAudio ?: CapabilityStatus.UNVERIFIED,
            h264Encoder = override.h264Encoder ?: if (hasH264Encoder()) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            networkControl = if (tlsProbe.networkControlAvailable) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            tls12 = if (tlsProbe.tls12Available) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            modelVerification = override.modelVerification ?: CapabilityStatus.UNVERIFIED,
            mediaTransport = override.mediaTransport ?: if (isMediaTransportAvailable(context)) CapabilityStatus.PERMISSION_REQUIRED else CapabilityStatus.UNSUPPORTED,
            textInput = textInputStatus(profile, detectedAccessibility),
            keySupport = when (profile.keySupportStyle) {
                KeySupportStyle.VENDOR_BACKEND -> backendKeySupport(backend, volumeFixed)
                KeySupportStyle.ACCESSIBILITY -> detectKeySupport(context, accessibilityEnabled)
            },
        )
    }

    private fun textInputStatus(profile: DeviceProfile, detectedAccessibility: CapabilityStatus): CapabilityStatus =
        when (profile.textInput) {
            TextInputChannel.NONE -> CapabilityStatus.UNSUPPORTED
            TextInputChannel.IME ->
                if (RemoteInputMethodService.session.ticket() != null) CapabilityStatus.SUPPORTED else CapabilityStatus.PERMISSION_REQUIRED
            TextInputChannel.ACCESSIBILITY ->
                if (Build.VERSION.SDK_INT >= 21) detectedAccessibility else CapabilityStatus.UNSUPPORTED
        }

    /** Backend-aware keys: a live vendor channel owns its keys; volume/media degrade to best effort. */
    private fun backendKeySupport(backend: KeyBackend?, volumeFixed: Boolean): Map<LogicalKey, KeyCapability> =
        LogicalKey.values().associateWith { keyState(it, backend, volumeFixed) }

    /** A dead backend must never keep reporting BEST_EFFORT (or anything above) for its keys. */
    internal fun keyState(key: LogicalKey, backend: KeyBackend?, volumeFixed: Boolean): KeyCapability = when {
        backend?.available == true && key in backend.keys -> KeyCapability.BEST_EFFORT
        key in VOLUME_KEYS -> if (volumeFixed) KeyCapability.UNSUPPORTED else KeyCapability.BEST_EFFORT
        key in MEDIA_KEYS -> KeyCapability.BEST_EFFORT
        else -> KeyCapability.UNSUPPORTED
    }

    private fun isTelevision(context: Context): Boolean =
        context.packageManager.hasSystemFeature(FEATURE_LEANBACK) ||
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private val VOLUME_KEYS = setOf(LogicalKey.VOLUME_UP, LogicalKey.VOLUME_DOWN, LogicalKey.VOLUME_MUTE)
    private val MEDIA_KEYS = setOf(
        LogicalKey.MEDIA_PLAY_PAUSE, LogicalKey.MEDIA_STOP, LogicalKey.MEDIA_NEXT, LogicalKey.MEDIA_PREVIOUS,
    )

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

    // Literal (not PackageManager.FEATURE_LEANBACK, which is API 21) keeps minSdk 19 explicit.
    private const val FEATURE_LEANBACK = "android.software.leanback"
}
