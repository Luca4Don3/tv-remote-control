package dev.lucasdone.tvremote.agent.command

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import dev.lucasdone.tvremote.agent.model.AckStatus
import dev.lucasdone.tvremote.agent.model.KeyEventCommand
import dev.lucasdone.tvremote.agent.model.KeyState
import dev.lucasdone.tvremote.agent.model.LogicalKey

class MediaCommandExecutor(context: Context) : CommandExecutor {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var legacyMuted = false

    override fun supports(key: LogicalKey): Boolean = key in supportedKeys

    override fun execute(command: KeyEventCommand): AckStatus = when (command.key) {
        LogicalKey.VOLUME_UP -> adjustVolume(command.state, AudioManager.ADJUST_RAISE)
        LogicalKey.VOLUME_DOWN -> adjustVolume(command.state, AudioManager.ADJUST_LOWER)
        LogicalKey.VOLUME_MUTE -> toggleMute(command.state)
        else -> dispatchMediaKey(command)
    }

    override fun release(key: LogicalKey): AckStatus {
        val keyCode = mediaKeyCode(key) ?: return AckStatus.SUCCESS
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return AckStatus.SUCCESS
    }

    private fun adjustVolume(state: KeyState, direction: Int): AckStatus {
        if (state == KeyState.UP) return AckStatus.SUCCESS
        audioManager.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
        return AckStatus.SUCCESS
    }

    private fun toggleMute(state: KeyState): AckStatus {
        if (state == KeyState.UP) return AckStatus.SUCCESS
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        } else {
            legacyMuted = !legacyMuted
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, legacyMuted)
        }
        return AckStatus.SUCCESS
    }

    private fun dispatchMediaKey(command: KeyEventCommand): AckStatus {
        val keyCode = mediaKeyCode(command.key) ?: return AckStatus.UNSUPPORTED
        val eventTime = SystemClock.uptimeMillis()
        when (command.state) {
            KeyState.PRESS -> {
                audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
                audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
            }
            KeyState.DOWN, KeyState.REPEAT -> audioManager.dispatchMediaKeyEvent(
                KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, command.repeatCount),
            )
            KeyState.UP -> audioManager.dispatchMediaKeyEvent(
                KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, command.repeatCount),
            )
        }
        return AckStatus.SUCCESS
    }

    private fun mediaKeyCode(key: LogicalKey): Int? = when (key) {
        LogicalKey.MEDIA_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        LogicalKey.MEDIA_STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        LogicalKey.MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        LogicalKey.MEDIA_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> null
    }

    companion object {
        private val supportedKeys = setOf(
            LogicalKey.VOLUME_UP,
            LogicalKey.VOLUME_DOWN,
            LogicalKey.VOLUME_MUTE,
            LogicalKey.MEDIA_PLAY_PAUSE,
            LogicalKey.MEDIA_STOP,
            LogicalKey.MEDIA_NEXT,
            LogicalKey.MEDIA_PREVIOUS,
        )
    }
}
