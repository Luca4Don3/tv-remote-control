package dev.tvremote.agent.device

import android.content.Context

/**
 * Ordered profile registry. The first matching profile wins; unmatched devices fall back to the
 * Android-standard profile.
 */
object DeviceProfiles {
    fun candidates(): List<DeviceProfile> = listOf(XiaomiProfile)

    fun resolve(context: Context, candidates: List<DeviceProfile> = candidates()): DeviceProfile =
        candidates.firstOrNull { it.matches(context) } ?: AndroidStandardProfile
}
