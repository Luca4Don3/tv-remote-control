package dev.lucasdone.tvremote.agent.model

enum class LogicalKey {
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    DPAD_CENTER,
    BACK,
    HOME,
    MENU,
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_MUTE,
    CHANNEL_UP,
    CHANNEL_DOWN,
    MEDIA_PLAY_PAUSE,
    MEDIA_STOP,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    POWER,
}

enum class KeyState { DOWN, REPEAT, UP, PRESS }

enum class AckStatus {
    SUCCESS,
    UNSUPPORTED,
    PERMISSION_DENIED,
    MAPPING_MISSING,
    REJECTED,
    EXECUTION_FAILED,
}

data class KeyEventCommand(
    val sequence: Long,
    val key: LogicalKey,
    val state: KeyState,
    val repeatCount: Int = 0,
)

data class CommandAck(
    val sequence: Long,
    val status: AckStatus,
    val reason: String? = null,
)
