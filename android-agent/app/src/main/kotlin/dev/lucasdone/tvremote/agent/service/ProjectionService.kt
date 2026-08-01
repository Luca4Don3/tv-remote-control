package dev.lucasdone.tvremote.agent.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import dev.lucasdone.tvremote.agent.media.MediaRuntime

class ProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var attachmentId: Long? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 21 || intent?.action != ACTION_START_AUTHORIZED) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val permissionData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PERMISSION_DATA)
        } ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedAttachmentId = intent.getLongExtra(EXTRA_ATTACHMENT_ID, -1L)
        if (requestedAttachmentId <= 0L || projection != null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        attachmentId = requestedAttachmentId
        projection = runCatching { manager.getMediaProjection(Activity.RESULT_OK, permissionData) }.getOrNull() ?: run {
            MediaRuntime.stopAttachment(requestedAttachmentId)
            attachmentId = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!MediaRuntime.startProjection(
                this,
                projection!!,
                intent.getBooleanExtra(EXTRA_CAPTURE_AUDIO, false),
                requestedAttachmentId,
            )
        ) {
            val failedProjection = projection
            projection = null
            attachmentId = null
            runCatching { failedProjection?.stop() }
            MediaRuntime.stopAttachment(requestedAttachmentId)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val stoppedAttachmentId = attachmentId
        val stoppedProjection = projection
        attachmentId = null
        projection = null
        stoppedAttachmentId?.let(MediaRuntime::stopAttachment)
        if (Build.VERSION.SDK_INT >= 21) runCatching { stoppedProjection?.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TV Remote screen sharing", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stop = PendingIntent.getService(
            this,
            100,
            Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            flags,
        )
        return builder.setContentTitle("TV Remote Agent 屏幕共享")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止共享", stop)
            .build()
    }

    companion object {
        private const val ACTION_START_AUTHORIZED = "dev.lucasdone.tvremote.agent.START_PROJECTION_AUTHORIZED"
        private const val ACTION_STOP = "dev.lucasdone.tvremote.agent.STOP_PROJECTION"
        private const val EXTRA_PERMISSION_DATA = "permission_data"
        private const val EXTRA_CAPTURE_AUDIO = "capture_audio"
        private const val EXTRA_ATTACHMENT_ID = "attachment_id"
        private const val CHANNEL_ID = "tv_remote_projection"
        private const val NOTIFICATION_ID = 1002

        fun startAuthorized(context: Context, permissionData: Intent, captureAudio: Boolean, attachmentId: Long) {
            val intent = Intent(context, ProjectionService::class.java)
                .setAction(ACTION_START_AUTHORIZED)
                .putExtra(EXTRA_PERMISSION_DATA, permissionData)
                .putExtra(EXTRA_CAPTURE_AUDIO, captureAudio)
                .putExtra(EXTRA_ATTACHMENT_ID, attachmentId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }
}
