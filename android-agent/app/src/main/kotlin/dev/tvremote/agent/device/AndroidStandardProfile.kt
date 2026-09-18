package dev.tvremote.agent.device

import android.content.Context
import dev.tvremote.agent.adapter.BackendProvider

/** Android-standard fallback: accessibility control and MediaProjection, with no vendor channel. */
object AndroidStandardProfile : DeviceProfile {
    override val id = "android-standard"
    override val backendProviders: List<BackendProvider> = emptyList()
    override val localAdbEnabled = false
    override val accessibilityEnabled = true
    override val mediaEnabled = true
    override val textInput = TextInputChannel.ACCESSIBILITY
    override val keySupportStyle = KeySupportStyle.ACCESSIBILITY
    override val capabilityOverride = CapabilityOverride()

    override fun matches(context: Context): Boolean = true
}
