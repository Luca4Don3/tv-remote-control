package dev.lucasdone.tvremote.agent.media

import android.content.Context
import android.media.projection.MediaProjection

object MediaRuntime {
    @Volatile private var coordinator: MediaSessionCoordinator? = null

    @Synchronized
    fun install(value: MediaSessionCoordinator) {
        check(coordinator == null) { "media runtime is already installed" }
        coordinator = value
    }

    @Synchronized
    fun uninstall(value: MediaSessionCoordinator) {
        if (coordinator !== value) return
        coordinator = null
        value.close()
    }

    // 与 install/uninstall 互斥，避免在已关闭的 coordinator 上操作。
    @Synchronized
    fun startProjection(
        context: Context,
        projection: MediaProjection,
        captureAudio: Boolean,
        attachmentId: Long,
    ): Boolean {
        val active = coordinator ?: return false
        return runCatching { active.startProjection(context, projection, captureAudio, attachmentId) }.isSuccess
    }

    @Synchronized
    fun currentAttachmentId(): Long? = coordinator?.currentAttachmentId()

    @Synchronized
    fun stopAttachment(attachmentId: Long) {
        coordinator?.stopAttachment(attachmentId)
    }

    @Synchronized
    fun stopMedia() {
        coordinator?.close()
    }
}
