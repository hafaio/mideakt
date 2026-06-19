package io.hafa.mideakt

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Low-level cryptographic primitives for the Midea LAN protocol. */
internal object Crypto {
    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "xor operands must be equal length" }
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    /** AES-CBC, zero IV, no padding (input must be block-aligned). */
    fun encryptCBC(key: ByteArray, data: ByteArray): ByteArray = cbc(Cipher.ENCRYPT_MODE, key, data)

    fun decryptCBC(key: ByteArray, data: ByteArray): ByteArray = cbc(Cipher.DECRYPT_MODE, key, data)

    /** AES-ECB with PKCS7 padding (Java calls it PKCS5, identical for AES). */
    fun encryptECB(key: ByteArray, data: ByteArray): ByteArray = ecb(Cipher.ENCRYPT_MODE, key, data)

    fun decryptECB(key: ByteArray, data: ByteArray): ByteArray = ecb(Cipher.DECRYPT_MODE, key, data)

    private fun cbc(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(data)
    }

    private fun ecb(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    val clean = trim()
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
