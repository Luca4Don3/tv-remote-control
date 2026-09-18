package dev.tvremote.agent.service

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("ApplySharedPref")
class AgentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("agent_preferences", Context.MODE_PRIVATE)
    var adbAllowed: Boolean
        get() = prefs.getBoolean("adb_allowed", false)
        set(value) { check(prefs.edit().putBoolean("adb_allowed", value).commit()) { "failed to save ADB preference" } }
    var startAtBoot: Boolean
        get() = prefs.getBoolean("start_at_boot", false)
        set(value) { check(prefs.edit().putBoolean("start_at_boot", value).commit()) { "failed to save startup preference" } }
    var startupFailure: Boolean
        get() = prefs.getBoolean("startup_failure", false)
        set(value) { check(prefs.edit().putBoolean("startup_failure", value).commit()) { "failed to save startup state" } }
}
