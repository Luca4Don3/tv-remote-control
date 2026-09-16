package dev.lucasdone.tvremote.xiaomi.backend

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey

/** Android's 524-byte little-endian RSAPublicKey wire structure. */
object AdbPublicKeyEncoder {
    fun encode(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() == 2048 && key.publicExponent == BigInteger.valueOf(65537))
        val word = BigInteger.ONE.shiftLeft(32)
        val modulus = key.modulus
        val inverse = modulus.mod(word).modInverse(word).negate().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        return ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(64); putInt(inverse)
            for (index in 0 until 64) putInt(modulus.shiftRight(index * 32).toInt())
            for (index in 0 until 64) putInt(rr.shiftRight(index * 32).toInt())
            putInt(key.publicExponent.toInt())
        }.array()
    }
}
