package dev.tvremote.agent.device

import android.content.Context
import android.os.Build
import dev.tvremote.agent.adapter.BackendProvider
import dev.tvremote.agent.adapter.xiaomi.XiaomiLoopbackProvider

/**
 * Xiaomi/Redmi boxes: vendor loopback first, then user-enabled local ADB, and IME text input.
 * MediaProjection and accessibility stay unreported until a specific model is verified on hardware.
 */
object XiaomiProfile : DeviceProfile {
    override val id = "xiaomi"
    override val backendProviders: List<BackendProvider> = listOf(XiaomiLoopbackProvider)
    override val localAdbEnabled = true
    override val accessibilityEnabled = false
    override val mediaEnabled = false
    override val textInput = TextInputChannel.IME
    override val keySupportStyle = KeySupportStyle.VENDOR_BACKEND
    override val capabilityOverride = CapabilityOverride(
        accessibility = CapabilityStatus.UNSUPPORTED,
        mediaProjection = CapabilityStatus.UNSUPPORTED,
        playbackAudio = CapabilityStatus.UNSUPPORTED,
        h264Encoder = CapabilityStatus.UNSUPPORTED,
        mediaTransport = CapabilityStatus.UNSUPPORTED,
        modelVerification = CapabilityStatus.UNVERIFIED,
    )

    override fun matches(context: Context): Boolean {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return vendor.contains("xiaomi") || vendor.contains("redmi")
    }
}
