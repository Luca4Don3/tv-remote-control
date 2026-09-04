package dev.lucasdone.tvremote.agent.auth

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PairingTranscript {
    private val DOMAIN = "TVRC-PAIR-v1".toByteArray(StandardCharsets.US_ASCII)

    fun build(
        protocolVersion: Int,
        certificateFingerprint: ByteArray,
        tvNonce: ByteArray,
        controllerNonce: ByteArray,
        controllerName: String,
    ): ByteArray {
        require(protocolVersion > 0) { "protocolVersion must be positive" }
        require(certificateFingerprint.size == 32) { "certificate fingerprint must be 32 bytes" }
        require(tvNonce.size == 32) { "TV nonce must be 32 bytes" }
        require(controllerNonce.size == 32) { "controller nonce must be 32 bytes" }
        val name = controllerName.toByteArray(StandardCharsets.UTF_8)
        require(name.isNotEmpty() && name.size <= 0xffff) { "controller name length is invalid" }

        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(DOMAIN)
                output.writeInt(protocolVersion)
                output.write(certificateFingerprint)
                output.write(tvNonce)
                output.write(controllerNonce)
                output.writeShort(name.size)
                output.write(name)
            }
        }.toByteArray()
    }

    fun computeSas(
        code: String,
        protocolVersion: Int,
        certificateFingerprint: ByteArray,
        tvNonce: ByteArray,
        controllerNonce: ByteArray,
        controllerName: String,
    ): String {
        require(code.length == 6 && code.all(Char::isDigit)) { "pairing code must contain six digits" }
        val mac = hmac(code, protocolVersion, certificateFingerprint, tvNonce, controllerNonce, controllerName)
        val firstWord = ((mac[0].toLong() and 0xffL) shl 24) or
            ((mac[1].toLong() and 0xffL) shl 16) or
            ((mac[2].toLong() and 0xffL) shl 8) or
            (mac[3].toLong() and 0xffL)
        return (firstWord % 1_000_000L).toString().padStart(6, '0')
    }

    fun hmac(
        code: String,
        protocolVersion: Int,
        certificateFingerprint: ByteArray,
        tvNonce: ByteArray,
        controllerNonce: ByteArray,
        controllerName: String,
    ): ByteArray {
        require(code.length == 6 && code.all(Char::isDigit)) { "pairing code must contain six digits" }
        val transcript = build(protocolVersion, certificateFingerprint, tvNonce, controllerNonce, controllerName)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(code.toByteArray(StandardCharsets.US_ASCII), "HmacSHA256"))
            doFinal(transcript)
        }
    }
}
