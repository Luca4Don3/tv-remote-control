package dev.lucasdone.tvremote.agent.auth

import dev.lucasdone.tvremote.agent.protocol.Hex

/**
 * 调试凭据导出（仅 Debug 构建 UI 暴露）：把已配对 ACTIVE 凭据格式化为
 * 小程序/其他调试客户端可手动录入的四元组（host 由 UI 另行提示，不含在内）。
 *
 * 安全边界：PSK 等价于该控制端对 agent 的完整控制权——输出文本应仅用于
 * 开发者自有设备，与 README/SECURITY 的调试通道边界一致。
 */
object DebugCredentialExporter {
    data class Entry(
        val controllerName: String,
        val controllerId: String,
        val pskHex: String,
    )

    fun export(store: KeystoreCredentialStore): List<Entry> =
        store.controllerSummaries().mapNotNull { summary ->
            val secret = store.getActive(summary.controllerId)?.secret ?: return@mapNotNull null
            Entry(
                controllerName = summary.controllerName,
                controllerId = summary.controllerId,
                pskHex = Hex.encode(secret),
            )
        }

    fun format(entries: List<Entry>): String = when {
        entries.isEmpty() -> "当前没有已配对的控制端。"
        else -> entries.joinToString("\n\n") { entry ->
            buildString {
                appendLine("控制端：${entry.controllerName}")
                appendLine("controllerId：${entry.controllerId}")
                append("PSK：${entry.pskHex}")
            }
        }
    }
}
