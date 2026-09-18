package dev.tvremote.agent

import androidx.multidex.MultiDexApplication

/** Installs the legacy multidex support library on API 19/20 (minSdk 19, Dalvik). */
class TvRemoteApplication : MultiDexApplication()
