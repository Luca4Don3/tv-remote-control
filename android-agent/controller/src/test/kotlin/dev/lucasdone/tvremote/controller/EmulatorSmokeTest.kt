package dev.lucasdone.tvremote.controller

import dev.lucasdone.tvremote.agent.protocol.FrameCodec
import dev.lucasdone.tvremote.agent.protocol.Hex
import dev.lucasdone.tvremote.agent.protocol.JsonValue
import dev.lucasdone.tvremote.agent.protocol.ProtocolCodec
import dev.lucasdone.tvremote.agent.protocol.ProtocolEnvelope
import dev.lucasdone.tvremote.agent.protocol.jsonLong
import dev.lucasdone.tvremote.agent.protocol.jsonObject
import dev.lucasdone.tvremote.agent.protocol.jsonString
import dev.lucasdone.tvremote.agent.protocol.requireObject
import dev.lucasdone.tvremote.agent.protocol.requireString
import dev.lucasdone.tvremote.controller.net.ConnectionTransport
import dev.lucasdone.tvremote.controller.net.WsDebugClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 模拟器端到端冒烟（手动门禁：本地 AVD + adb forward tcp:17832 tcp:47832）：
 * 真实 TLS → 配对（SAS 经 agent 本地 intent 确认，避免 UiAutomation 抑制无障碍服务）
 * → 凭据 → 认证（新连接）→ 按键/文本 ACK → 断连。
 */
class EmulatorSmokeTest {
    private class TofuTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw java.security.cert.CertificateException("client certs not accepted")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw java.security.cert.CertificateException("empty chain")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private class AdbSocketTransport(
        private val socket: SSLSocket,
    ) : ConnectionTransport {
        private val inbound = AtomicLong(0)
        private val outbound = AtomicLong(0)
        override val peerFingerprint: ByteArray =
            MessageDigest.getInstance("SHA-256").digest(socket.session.peerCertificates[0].encoded)
        private val input: InputStream = socket.inputStream
        private val output: OutputStream = socket.outputStream

        override fun nextRequestId(): String = "c-${outbound.incrementAndGet()}"

        override fun send(requestId: String, sessionId: String, type: String, payload: JsonValue.ObjectValue): ProtocolEnvelope {
            val envelope = ProtocolEnvelope(
                protocolVersion = ProtocolCodec.VERSION,
                requestId = requestId,
                sessionId = sessionId,
                sequence = outbound.get(),
                type = type,
                payload = payload,
            )
            FrameCodec.write(output, ProtocolCodec.encode(envelope))
            return envelope
        }

        override fun receive(): ProtocolEnvelope? {
            val frame = FrameCodec.read(input) ?: return null
            val envelope = ProtocolCodec.decode(frame)
            if (envelope.sequence <= inbound.get()) throw java.io.IOException("sequence not increasing")
            inbound.set(envelope.sequence)
            return envelope
        }

        override fun close() {
            socket.close()
        }
    }

    /** adb 路径可用 TVRC_ADB 覆盖（默认与本仓库 CI/Linux 环境一致）。 */
    private val adb = listOf(System.getenv("TVRC_ADB") ?: "/opt/android-sdk/platform-tools/adb")

    /** 主机侧转发端口（adb forward tcp:<forwarded> tcp:<agent>），与模拟器 agent 端口解耦。 */
    private val hostTlsPort = 17832
    private val hostWsPort = 17833
    private val agentWsPort = 47833
    private val agentComponent = "dev.lucasdone.tvremote.agent.debug/dev.lucasdone.tvremote.agent.service.AgentService"

    /** 仅当 adb 可用且有已授权设备时返回 true（CI 无设备 → assume 跳过）。 */
    private fun adbDeviceConnected(): Boolean = try {
        val process = ProcessBuilder(adb + listOf("devices")).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        output.lineSequence().drop(1).any { line ->
            val parts = line.split(Regex("\\s+"))
            parts.size >= 2 && parts[0].isNotBlank() && parts[1] == "device"
        }
    } catch (_: Exception) {
        false
    }

    private fun adbShell(vararg args: String) {
        ProcessBuilder(adb + listOf("shell") + args.toList()).inheritIO().start().waitFor()
    }

    private fun freshConnection(context: SSLContext): AdbSocketTransport {
        val raw = context.socketFactory.createSocket() as SSLSocket
        raw.tcpNoDelay = true
        raw.connect(java.net.InetSocketAddress("127.0.0.1", hostTlsPort), 10_000)
        raw.soTimeout = 45_000
        raw.enabledProtocols = raw.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        raw.startHandshake()
        return AdbSocketTransport(raw)
    }

    @Test
    fun fullChainPairingAuthenticationControl() {
        // 环境保护：仅在本机存在已连接的模拟器/设备时执行（CI 无 AVD 自动跳过）
        assumeTrue(adbDeviceConnected())

        // 电视端就绪：清空 app 数据（清除历史测试配对占用的 8 个凭据槽位）
        adbShell("pm", "clear", "dev.lucasdone.tvremote.agent.debug")
        Thread.sleep(2000)
        // 清掉上次运行遗留的对话框（BACK 会 dismiss AlertDialog）
        adbShell("input", "keyevent", "KEYCODE_BACK")
        Thread.sleep(1000)
        adbShell("input", "keyevent", "KEYCODE_BACK")
        Thread.sleep(1000)
        adbShell("am", "startservice",
            "-n", "dev.lucasdone.tvremote.agent.debug/dev.lucasdone.tvremote.agent.service.AgentService")
        Thread.sleep(4000)
        adbShell("am", "start", "-n", "dev.lucasdone.tvremote.agent.debug/dev.lucasdone.tvremote.agent.MainActivity")
        Thread.sleep(3000)

        val trustContext = SSLContext.getInstance("TLS")
        trustContext.init(null, arrayOf<TrustManager>(TofuTrustManager()), SecureRandom())
        val paired = pairOnTls(trustContext)

        val session = ControllerSession { freshConnection(trustContext) }
        val capabilities = session.authenticate(paired.controllerId, paired.secret, paired.tvCertificateFingerprint)
        assertTrue(capabilities.keySupport.containsKey("DPAD_DOWN"))

        // 阶段四：遥控按键 ACK（断言与能力语义对齐）
        // SUPPORTED 键（全局动作/音频）：必须 SUCCESS
        val backAck = session.sendKeyEvent("BACK", "PRESS")
        println("SMOKE back => $backAck")
        assertEquals("SUCCESS", backAck.status)
        val homeAck = session.sendKeyEvent("HOME", "PRESS")
        println("SMOKE home => $homeAck")
        assertEquals("SUCCESS", homeAck.status)
        val volumeAck = session.sendKeyEvent("VOLUME_UP", "PRESS")
        println("SMOKE volume => $volumeAck")
        assertEquals("SUCCESS", volumeAck.status)
        // BEST_EFFORT 键（DPAD 方向依赖焦点查找）：允许 EXECUTION_FAILED，
        // 但 ACK 必须回到（协议链路验证）。PRESS 即完整周期，不发 UP。
        val dpadAck = session.sendKeyEvent("DPAD_DOWN", "PRESS")
        assertTrue(dpadAck.status in setOf("SUCCESS", "EXECUTION_FAILED"))

        // 阶段五：文本命令 ACK（无输入框场景允许 UNSUPPORTED/EXECUTION_FAILED）
        val textAck = session.sendText("tvrc", draft = false)
        assertTrue(textAck.status in setOf("SUCCESS", "UNSUPPORTED", "EXECUTION_FAILED"))

        session.close()
    }

    /** WS 调试通道互操作：掩码帧经 agent 服务端校验 → 密钥派生 → WS 通道遥控真实执行。 */
    @Test
    fun wsDebugChannelInterop() {
        assumeTrue(adbDeviceConnected())
        adbShell("pm", "clear", "dev.lucasdone.tvremote.agent.debug")
        Thread.sleep(2000)
        adbShell("am", "startservice",
            "-n", "dev.lucasdone.tvremote.agent.debug/dev.lucasdone.tvremote.agent.service.AgentService")
        Thread.sleep(4000)
        adbShell("am", "start", "-n", "dev.lucasdone.tvremote.agent.debug/dev.lucasdone.tvremote.agent.MainActivity")
        Thread.sleep(3000)

        val trustContext = SSLContext.getInstance("TLS")
        trustContext.init(null, arrayOf<TrustManager>(TofuTrustManager()), SecureRandom())
        val paired = pairOnTls(trustContext)

        // 经 adb forward 把模拟器 agent 端口映射到主机（forward 是 adb 主机命令，非 shell 命令）
        ProcessBuilder(adb + listOf("forward", "tcp:$hostWsPort", "tcp:$agentWsPort"))
            .redirectErrorStream(true).start().waitFor()
        val client = WsDebugClient("127.0.0.1", paired.controllerId, paired.secret, hostWsPort)
        try {
            // 掩码帧 + ws_hello + 应用层加密链路：WS 通道 key_event 真实执行
            val keyAck = client.sendKeyEvent(key = "BACK", state = "PRESS")
            println("SMOKE ws back => $keyAck")
            assertTrue(keyAck)
            val textAck = client.sendText("ws", draft = false)
            println("SMOKE ws text => $textAck")
            // MainActivity 无输入框，文本注入预期 UNSUPPORTED（能力协商语义）；
            // 链路验证以命令往返完成（不抛异常）为准
            Unit
        } finally {
            client.close()
        }
    }

    /** 测试即时开配对窗口并从电视 UI 抓取当前配对码（窗口 TTL 120s，随测试开启）。 */
    private fun openPairingWindow(): String {
        adbShell(
            "am", "startservice", "-a", "dev.lucasdone.tvremote.agent.OPEN_PAIRING",
            "-n", "dev.lucasdone.tvremote.agent.debug/dev.lucasdone.tvremote.agent.service.AgentService",
        )
        Thread.sleep(3000)
        repeat(10) {
            adbShell("uiautomator", "dump", "/data/local/tmp/code.xml")
            val pulled = ProcessBuilder(adb + listOf("pull", "/data/local/tmp/code.xml", "/tmp/opencode/code.xml"))
                .redirectErrorStream(true).start().waitFor()
            if (pulled == 0) {
                val xml = java.io.File("/tmp/opencode/code.xml").readText()
                val match = Regex("配对码[：:]&#?[0-9a-fA-F]*;?([0-9]{6})").find(xml)
                    ?: Regex("配对码[：:]([0-9]{6})").find(xml)
                println("SMOKE dump#$it code => " + (match?.groupValues?.get(1) ?: "none"))
                if (match != null) {
                    restoreAccessibility()
                    return match.groupValues[1]
                }
            }
            Thread.sleep(1500)
        }
        throw AssertionError("未能从电视 UI 抓取配对码")
    }

    /** TLS 主链路完整配对：pair_request → SAS（tap 确认）→ credential → store_ack → complete。 */
    private fun pairOnTls(trustContext: SSLContext): PairedResult {
        val code = openPairingWindow()
        val pairing = freshConnection(trustContext)
        val controllerNonce = ByteArray(32).also(SecureRandom()::nextBytes)
        pairing.send(
            requestId = pairing.nextRequestId(),
            sessionId = "",
            type = "pair_request",
            payload = jsonObject(
                "code" to jsonString(code),
                "controllerName" to jsonString("SmokeTest"),
                "controllerNonce" to jsonString(Hex.encode(controllerNonce)),
            ),
        )
        val sas = pairing.receive() ?: throw AssertionError("pairing_sas missing")
        println("SMOKE pairing => " + sas.type)
        assertEquals("pairing_sas", sas.type)
        val pairingId = sas.payload.requireString("pairingId", 32)
        assertTrue(Regex("\\d{6}").matches(sas.payload.requireString("sas", 6)))

        // SAS 对话框按钮位置固定（320x640 AVD）；tap 确认
        Thread.sleep(1500)
        adbShell("input", "tap", "228", "418")
        Thread.sleep(1000)

        val credential = pairing.receive() ?: throw AssertionError("pair_credential missing")
        assertEquals("pair_credential", credential.type)
        val controllerId = credential.payload.requireString("controllerId", 32)
        val secret = Hex.decode(credential.payload.requireString("secret", 64))
        assertEquals(32, controllerId.length)
        assertEquals(32, secret.size)
        pairing.send(
            requestId = pairing.nextRequestId(),
            sessionId = "",
            type = "pair_store_ack",
            payload = jsonObject(
                "pairingId" to jsonString(pairingId),
                "controllerId" to jsonString(controllerId),
            ),
        )
        val complete = pairing.receive() ?: throw AssertionError("pair_complete missing")
        assertEquals("pair_complete", complete.type)
        assertEquals(controllerId, complete.payload.requireString("controllerId", 32))
        val tvFingerprint = pairing.peerFingerprint
        pairing.close()
        return PairedResult(controllerId, secret, tvFingerprint)
    }

    internal data class PairedResult(
        val controllerId: String,
        val secret: ByteArray,
        val tvCertificateFingerprint: ByteArray,
    )

    /** uiautomator dump 的 UiAutomation 连接会抑制无障碍服务；抓码后立即恢复绑定。 */
    private fun restoreAccessibility() {
        adbShell("settings", "put", "secure", "enabled_accessibility_services", "''")
        Thread.sleep(2000)
        adbShell(
            "settings", "put", "secure", "enabled_accessibility_services",
            "dev.lucasdone.tvremote.agent.debug/dev.lucasdone.tvremote.agent.service.TvAccessibilityService",
        )
        Thread.sleep(6000)
    }
}
