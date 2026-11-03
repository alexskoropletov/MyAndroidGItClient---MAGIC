package com.alexskoropletov.sync

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoTest {
    @Test
    fun roundTripAndTamper() {
        val key = Crypto.keygen()
        val payload = Crypto.encrypt(key, "заметка".encodeToByteArray())
        assertArrayEquals("заметка".encodeToByteArray(), Crypto.decrypt(key, payload))
        assertArrayEquals(ByteArray(0), Crypto.decrypt(key, Crypto.encrypt(key, ByteArray(0))))
        payload[payload.lastIndex] = (payload[payload.lastIndex] + 1).toByte()
        assertThrows(Exception::class.java) { Crypto.decrypt(key, payload) }
    }

    @Test
    fun keyFileRoundTrip() {
        val file = Files.createTempDirectory("key").resolve("sync.key").toFile()
        val key = Crypto.keygen()
        Crypto.writeKey(file, key)
        assertArrayEquals(key, Crypto.readKey(file))
        assertArrayEquals(key, Crypto.readKey(key.toHex()))
        assertEquals(Crypto.fingerprint(key), Crypto.fingerprint(Crypto.readKey(file)))
        file.delete()
    }
}
