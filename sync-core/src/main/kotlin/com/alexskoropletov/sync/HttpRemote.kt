package com.alexskoropletov.sync

import java.net.HttpURLConnection
import java.net.URL

class HttpRemote(server: String, private val token: String) : Remote {
    val address: String = server.trim().trimEnd('/')

    init {
        require(address.startsWith("http://") || address.startsWith("https://")) {
            "адрес должен начинаться с http:// или https://"
        }
        require(token.isNotBlank()) { "нужен токен" }
    }

    override fun fetchPointer(): IndexPointer {
        val (code, bytes) = request("GET", "/v1/index", null, null)
        if (code !in 200..299) fail(code, bytes)
        return Wire.decode(bytes.toString(Charsets.UTF_8))
    }

    override fun tryPushIndex(expectedRevision: Long, blobId: String): Boolean {
        val body = Wire.encode(PushIndexRequest(expectedRevision, blobId)).encodeToByteArray()
        val (code, bytes) = request("PUT", "/v1/index", body, "application/json; charset=utf-8")
        if (code == 409) return false
        if (code !in 200..299) fail(code, bytes)
        return true
    }

    override fun putBlob(id: String, bytes: ByteArray) {
        require(id.matches(BLOB_ID)) { "плохое имя блоба" }
        require(Crypto.sha256(bytes) == id) { "id блоба не совпал с содержимым" }
        val (code, response) = request("PUT", "/v1/blobs/$id", bytes, "application/octet-stream")
        if (code !in 200..299) fail(code, response)
    }

    override fun getBlob(id: String): ByteArray {
        require(id.matches(BLOB_ID)) { "плохое имя блоба" }
        val (code, bytes) = request("GET", "/v1/blobs/$id", null, null)
        if (code == 404) error("на сервере нет файла")
        if (code !in 200..299) fail(code, bytes)
        return bytes
    }

    private fun request(method: String, path: String, body: ByteArray?, type: String?): Pair<Int, ByteArray> {
        val connection = URL(address + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code in 300..399) error("сервер перенаправил на другой адрес. Впишите конечный адрес.")
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.readLimited(Limits.MAX_BLOB) ?: ByteArray(0)
            return code to bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun fail(code: Int, bytes: ByteArray): Nothing {
        val text = bytes.toString(Charsets.UTF_8).trim()
        error(if (text.isEmpty()) "сервер ответил $code" else "сервер ответил $code: $text")
    }

    private companion object {
        val BLOB_ID = Regex("[0-9a-f]{64}")
    }
}
