package com.alexskoropletov.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SetupQrTest {
    private val card = SetupCard(
        server = "https://sync.example:8787",
        token = "abc123",
        device = "телефон",
    )

    @Test
    fun roundTrip() {
        assertEquals(card, SetupQr.decode(SetupQr.encode(card)))
    }

    @Test
    fun futureFieldsAreIgnored() {
        val text = """MAGIC-SETUP-1 {"v":1,"server":"https://sync.example","token":"abc","device":"s24u","plan":"plus"}"""
        val decoded = SetupQr.decode(text)
        assertEquals("https://sync.example", decoded.server)
        assertEquals("abc", decoded.token)
        assertEquals("s24u", decoded.device)
    }

    @Test
    fun rejectsOtherCodes() {
        assertThrows(IllegalArgumentException::class.java) { SetupQr.decode("https://example") }
        assertThrows(IllegalArgumentException::class.java) {
            SetupQr.decode("""MAGIC-SETUP-1 {"v":2,"server":"https://sync.example","token":"abc","device":"s24u"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SetupQr.encode(card.copy(server = "sync.example"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SetupQr.encode(card.copy(device = " "))
        }
    }
}
