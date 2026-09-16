package dev.lucasdone.tvremote.xiaomi.backend

import dev.lucasdone.tvremote.agent.model.LogicalKey

/** Only fixed commands; neither text nor command fragments can cross this interface. */
object AdbCommands {
    private val codes = mapOf(
        LogicalKey.DPAD_UP to 19, LogicalKey.DPAD_DOWN to 20,
        LogicalKey.DPAD_LEFT to 21, LogicalKey.DPAD_RIGHT to 22,
        LogicalKey.DPAD_CENTER to 23, LogicalKey.BACK to 4, LogicalKey.HOME to 3,
        LogicalKey.VOLUME_UP to 24, LogicalKey.VOLUME_DOWN to 25, LogicalKey.VOLUME_MUTE to 164,
        LogicalKey.MEDIA_PLAY_PAUSE to 85, LogicalKey.MEDIA_STOP to 86,
        LogicalKey.MEDIA_NEXT to 87, LogicalKey.MEDIA_PREVIOUS to 88,
    )
    val keys: Set<LogicalKey> get() = codes.keys
    fun keyCommand(key: LogicalKey): String? = codes[key]?.let { "/system/bin/input keyevent $it" }
    const val PROBE = "id -u"
    const val INPUT_PROBE = "test -x /system/bin/input"
    const val EXIT_MARKER = "TVRC_EXIT:"
    fun destination(command: String): String {
        require(command == PROBE || command == INPUT_PROBE || command in codes.values.map { "/system/bin/input keyevent $it" })
        // Legacy shell works on Android 6, where shell_v2 may be unavailable.
        return "shell:$command; printf '\\nTVRC_EXIT:%s\\n' \"\$?\""
    }
    fun parseResponse(response: String): Pair<Int, String> {
        val marker = response.lastIndexOf("\n$EXIT_MARKER")
        require(marker >= 0) { "ADB command did not return an exit status" }
        val exit = response.substring(marker + EXIT_MARKER.length + 1).trim().toIntOrNull()
            ?: throw IllegalArgumentException("invalid ADB exit status")
        return exit to response.substring(0, marker).trim()
    }
}
