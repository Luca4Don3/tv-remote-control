package dev.lucasdone.tvremote.agent.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.media.MediaCodecList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject

enum class CapabilityStatus { SUPPORTED, UNSUPPORTED, PERMISSION_REQUIRED, UNVERIFIED }

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
        .toString(2)
}

object CapabilityDetector {
    fun detect(context: Context): CapabilitySnapshot {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityEnabled = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }

        return CapabilitySnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            firmware = Build.DISPLAY.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            abis = supportedAbis(),
            accessibility = if (accessibilityEnabled) CapabilityStatus.SUPPORTED else CapabilityStatus.PERMISSION_REQUIRED,
            mediaProjection = if (Build.VERSION.SDK_INT >= 21 && context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) is MediaProjectionManager) {
                CapabilityStatus.PERMISSION_REQUIRED
            } else {
                CapabilityStatus.UNSUPPORTED
            },
            playbackAudio = if (Build.VERSION.SDK_INT >= 29) CapabilityStatus.PERMISSION_REQUIRED else CapabilityStatus.UNSUPPORTED,
            h264Encoder = if (hasH264Encoder()) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
        )
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
