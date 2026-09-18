package dev.tvremote.agent.adapter.xiaomi

import dev.tvremote.agent.adapter.BackendProvider
import dev.tvremote.agent.adapter.KeyBackend

/** Xiaomi firmware's loopback control surface on 127.0.0.1:6095. */
object XiaomiLoopbackProvider : BackendProvider {
    override val id = "xiaomi-loopback"
    override val displayName = "本机接口"
    override fun probe(): KeyBackend? {
        val client = XiaomiLoopbackClient()
        return if (client.probe()) XiaomiKeyBackend(client) else null
    }
}
