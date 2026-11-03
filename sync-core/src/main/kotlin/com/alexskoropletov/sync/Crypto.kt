package com.alexskoropletov.sync

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val HEADER = "MAGIC-SYNC-KEY-1"

    fun keygen(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key
    }

    fun token(): String {
        val raw = ByteArray(24)
        SecureRandom().nextBytes(raw)
        return raw.toHex()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun fingerprint(key: ByteArray): String = sha256(key).take(16)

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 32) { "ключ должен быть 32 байта" }
        val nonce = ByteArray(NONCE)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    fun decrypt(key: ByteArray, payload: ByteArray): ByteArray {
        require(key.size == 32) { "ключ должен быть 32 байта" }
        require(payload.size >= NONCE + 16) { "битый шифр" }
        val nonce = payload.copyOfRange(0, NONCE)
        val body = payload.copyOfRange(NONCE, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(body)
    }

    fun writeKey(file: File, key: ByteArray) {
        file.parentFile?.mkdirs()
        atomicWrite(file, "$HEADER\n${key.toHex()}\n".encodeToByteArray())
    }

    fun readKey(file: File): ByteArray = readKey(file.readText())

    fun readKey(text: String): ByteArray {
        val hex = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line != HEADER && line.length == 64 && line.all { it in "0123456789abcdefABCDEF" }
            }
            ?: error("файл ключа не похож на ключ синхронизации")
        return hex.hexToBytes()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "битая шестнадцатеричная строка" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
