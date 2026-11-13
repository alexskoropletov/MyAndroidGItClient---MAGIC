package com.alexskoropletov.sync

import kotlinx.serialization.Serializable

/**
 * Текст QR-кода настройки. Будущий сайт должен отдавать ту же строку:
 * префикс [PREFIX], затем JSON с полями v, server, token, device.
 * Незнакомые поля телефон пропускает. Ключ в код не входит.
 */
@Serializable
data class SetupCard(
    val v: Int = 1,
    val server: String,
    val token: String,
    val device: String,
)

object SetupQr {
    const val PREFIX = "MAGIC-SETUP-1 "

    fun encode(card: SetupCard): String {
        require(card.v == 1) { "неизвестная версия кода" }
        val clean = clean(card)
        return PREFIX + Wire.encode(clean)
    }

    fun decode(text: String): SetupCard {
        val body = text.trim()
        require(body.startsWith(PREFIX)) { "это не код настройки MAGIC" }
        val card = try {
            Wire.decode<SetupCard>(body.removePrefix(PREFIX).trim())
        } catch (_: Exception) {
            error("код настройки повреждён")
        }
        require(card.v == 1) { "этот код новее приложения, обновите MAGIC" }
        return clean(card)
    }

    private fun clean(card: SetupCard): SetupCard {
        val server = card.server.trim()
        val token = card.token.trim()
        val device = card.device.trim()
        require(server.startsWith("https://") || server.startsWith("http://")) { "в коде нет адреса сервера" }
        require(server.none { it.isWhitespace() }) { "адрес сервера с пробелом" }
        require(token.isNotEmpty() && token.none { it.isWhitespace() }) { "в коде нет токена" }
        require(device.isNotEmpty() && device.none { it == '\n' || it == '\r' }) { "в коде нет имени устройства" }
        return card.copy(server = server, token = token, device = device)
    }
}
