package com.alexskoropletov.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

object Wire {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(serializer(), value)

    inline fun <reified T> decode(text: String): T = json.decodeFromString(serializer(), text)
}

@Serializable
data class FileEntry(
    val path: String,
    val hash: String = "",
    val version: Long = 0,
    val deleted: Boolean = false,
    val updatedBy: String = "",
    val blobId: String = "",
)

@Serializable
data class IndexDocument(
    val revision: Long = 0,
    val entries: List<FileEntry> = emptyList(),
) {
    fun byPath(): Map<String, FileEntry> = entries.associateBy { it.path }
}

@Serializable
data class IndexPointer(
    val revision: Long = 0,
    val blobId: String? = null,
)

@Serializable
data class PushIndexRequest(
    val expectedRevision: Long,
    val blobId: String,
)

@Serializable
data class LocalState(
    val vaultPath: String = "",
    val device: String = "",
    val revision: Long = 0,
    val indexBlobId: String = "",
    val entries: List<FileEntry> = emptyList(),
) {
    fun byPath(): Map<String, FileEntry> = entries.associateBy { it.path }
}
