package dev.tvremote.agent.adapter.adb

import dadb.Dadb
import dadb.AdbKeyPair
import dev.tvremote.agent.adapter.BackendKind
import dev.tvremote.agent.adapter.KeyBackend
import dev.tvremote.agent.model.AckStatus
import dev.tvremote.agent.model.LogicalKey
import java.io.IOException
import okio.Buffer

class AdbKeyBackend private constructor(private val client: Dadb) : KeyBackend {
    override val kind = BackendKind.ADB
    override val keys = AdbCommands.keys
    @Volatile override var available = true
        private set
    @Synchronized override fun press(key: LogicalKey): AckStatus {
        val command = AdbCommands.keyCommand(key) ?: return AckStatus.UNSUPPORTED
        if (!available) return AckStatus.EXECUTION_FAILED
        return try {
            val (exit, _) = executeFixed(client, command)
            // Some firmware prints a warning while still injecting the key, so only the exit status counts.
            if (exit == 0) AckStatus.SUCCESS else fail()
        } catch (_: IOException) { fail() }
        catch (_: IllegalArgumentException) { fail() }
    }
    private fun fail(): AckStatus { available = false; client.close(); return AckStatus.EXECUTION_FAILED }
    @Synchronized override fun close() { available = false; client.close() }
    companion object {
        fun connect(keyPair: AdbKeyPair): AdbKeyBackend {
            val client = Dadb.create("127.0.0.1", 5555, keyPair, connectTimeout = 1500, socketTimeout = 2500)
            try {
                val (exit, uid) = executeFixed(client, AdbCommands.PROBE)
                check(exit == 0 && uid == "2000") { "ADB must be authorized as shell, without root" }
                val (inputExit, _) = executeFixed(client, AdbCommands.INPUT_PROBE)
                check(inputExit == 0) { "system input command is unavailable" }
                return AdbKeyBackend(client)
            } catch (error: Exception) { client.close(); throw error }
        }
        private fun executeFixed(client: Dadb, command: String): Pair<Int, String> {
            client.open(AdbCommands.destination(command)).use { stream ->
                val buffer = Buffer()
                val deadline = System.nanoTime() + 3_000_000_000L
                while (true) {
                    if (System.nanoTime() > deadline) throw IOException("ADB command deadline exceeded")
                    if (stream.source.read(buffer, 1024) < 0) break
                    if (buffer.size > 8192) throw IOException("ADB response exceeds limit")
                }
                return AdbCommands.parseResponse(buffer.readUtf8())
            }
        }
    }
}
