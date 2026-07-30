package dev.lucasdone.tvremote.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { AgentService.start(context) }
            .onFailure { Log.e("BootReceiver", "Unable to start foreground service after boot", it) }
    }
}
