package dev.tvremote.agent.device

import android.content.Context
import dev.tvremote.agent.adapter.BackendProvider

/** How the profile derives per-key support for the capability report. */
enum class KeySupportStyle { ACCESSIBILITY, VENDOR_BACKEND }

/** Which text-input channel the profile exposes. */
enum class TextInputChannel { NONE, ACCESSIBILITY, IME }

/** Capability values a profile pins regardless of runtime detection; null keeps the detected value. */
data class CapabilityOverride(
    val accessibility: CapabilityStatus? = null,
    val mediaProjection: CapabilityStatus? = null,
    val playbackAudio: CapabilityStatus? = null,
    val h264Encoder: CapabilityStatus? = null,
    val mediaTransport: CapabilityStatus? = null,
    val modelVerification: CapabilityStatus? = null,
)

/**
 * Per set-top-box adaptation. Profiles are code-defined and matched in order; the first match wins,
 * otherwise the Android-standard profile applies. Adding a supported box means adding a profile and,
 * at most, a [BackendProvider] — never a separate APK.
 */
interface DeviceProfile {
    val id: String

    /** Vendor-local control channels, probed in preference order. */
    val backendProviders: List<BackendProvider>

    /** Whether the user-enabled local ADB backend may be used as fallback. */
    val localAdbEnabled: Boolean

    /** AccessibilityService executor is offered for this box. */
    val accessibilityEnabled: Boolean

    /** MediaProjection screen/audio transport is offered for this box. */
    val mediaEnabled: Boolean

    val textInput: TextInputChannel
    val keySupportStyle: KeySupportStyle
    val capabilityOverride: CapabilityOverride

    fun matches(context: Context): Boolean
}
