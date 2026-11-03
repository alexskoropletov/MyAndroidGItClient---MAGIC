package com.alexskoropletov.sync.cli

import com.alexskoropletov.sync.Crypto
import com.alexskoropletov.sync.IndexPointer
import com.alexskoropletov.sync.Limits
import com.alexskoropletov.sync.PushIndexRequest
import com.alexskoropletov.sync.Wire
import com.alexskoropletov.sync.atomicWrite
import com.alexskoropletov.sync.readLimited
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

class BlobServer(
    private val data: File,
    private val token: String,
    private val host: String,
    private val port: Int,
) {
    private val blobs = File(data, "blobs")
    private val indexFile = File(data, "index.json")
    private val lock = Any()

    init {
        require(token.isNotBlank()) { "нужен токен" }
        if (!data.isDirectory && !data.mkdirs()) error("не создалась папка ${data.path}")
        if (!blobs.isDirectory && !blobs.mkdirs()) error("не создалась папка ${blobs.path}")
    }

    fun start(): HttpServer {
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext("/v1/index") { exchange -> guard(exchange) { index(exchange) } }
        http.createContext("/v1/blobs/") { exchange -> guard(exchange) { blob(exchange) } }
        http.executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "magic-sync").apply { isDaemon = true }
        }
        http.start()
        return http
    }

    private fun guard(exchange: HttpExchange, block: () -> Unit) {
        try {
            if (!authorized(exchange)) {
                send(exchange, 401, "нужен токен".toByteArray(), TEXT)
                return
            }
            block()
        } catch (e: Exception) {
            try {
                send(exchange, 500, (e.message ?: "ошибка").toByteArray(), TEXT)
            } catch (_: Exception) {
            }
        } finally {
            exchange.close()
        }
    }

    private fun index(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "GET" -> {
                val pointer = synchronized(lock) { readPointer() }
                send(exchange, 200, Wire.encode(pointer).toByteArray(), JSON)
            }
            "PUT" -> {
                val body = exchange.requestBody.use { it.readLimited(1024 * 1024) }
                val request = try {
                    Wire.decode<PushIndexRequest>(body.toString(Charsets.UTF_8))
                } catch (_: Exception) {
                    send(exchange, 400, "не понял запрос".toByteArray(), TEXT)
                    return
                }
                if (!request.blobId.matches(BLOB_ID)) {
                    send(exchange, 400, "плохое имя блоба".toByteArray(), TEXT)
                    return
                }
                synchronized(lock) {
                    val current = readPointer()
                    if (current.revision != request.expectedRevision) {
                        send(exchange, 409, "ревизия уже другая".toByteArray(), TEXT)
                        return
                    }
                    if (!File(blobs, request.blobId).isFile) {
                        send(exchange, 400, "нет такого блоба".toByteArray(), TEXT)
                        return
                    }
                    val next = IndexPointer(current.revision + 1, request.blobId)
                    writePointer(next)
                    send(exchange, 200, Wire.encode(next).toByteArray(), JSON)
                }
            }
            else -> send(exchange, 405, "метод не нужен".toByteArray(), TEXT)
        }
    }

    private fun blob(exchange: HttpExchange) {
        val id = exchange.requestURI.path.removePrefix("/v1/blobs/")
        if (!id.matches(BLOB_ID)) {
            send(exchange, 400, "плохое имя блоба".toByteArray(), TEXT)
            return
        }
        val file = File(blobs, id)
        when (exchange.requestMethod) {
            "GET" -> {
                if (!file.isFile) {
                    send(exchange, 404, "нет файла".toByteArray(), TEXT)
                    return
                }
                send(exchange, 200, file.readBytes(), BYTES)
            }
            "PUT" -> {
                val body = exchange.requestBody.use { it.readLimited(Limits.MAX_BLOB) }
                if (Crypto.sha256(body) != id) {
                    send(exchange, 400, "id не совпал с содержимым".toByteArray(), TEXT)
                    return
                }
                atomicWrite(file, body)
                send(exchange, 200, "ок".toByteArray(), TEXT)
            }
            else -> send(exchange, 405, "метод не нужен".toByteArray(), TEXT)
        }
    }

    private fun readPointer(): IndexPointer {
        if (!indexFile.isFile) return IndexPointer(0, null)
        return Wire.decode(indexFile.readText())
    }

    private fun writePointer(pointer: IndexPointer) {
        atomicWrite(indexFile, Wire.encode(pointer).encodeToByteArray())
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Bearer ")) return false
        val got = header.removePrefix("Bearer ").trim()
        return MessageDigest.isEqual(got.encodeToByteArray(), token.encodeToByteArray())
    }

    private fun send(exchange: HttpExchange, code: Int, body: ByteArray, type: String) {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { if (body.isNotEmpty()) it.write(body) }
    }

    private companion object {
        val BLOB_ID = Regex("[0-9a-f]{64}")
        const val JSON = "application/json; charset=utf-8"
        const val TEXT = "text/plain; charset=utf-8"
        const val BYTES = "application/octet-stream"
    }
}
