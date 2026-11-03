package com.alexskoropletov.sync

import java.io.File
import java.nio.file.Files
import kotlin.io.path.isSymbolicLink

data class Scan(val files: Map<String, String>)

fun scanVault(root: File): Scan {
    val files = linkedMapOf<String, String>()
    if (!root.isDirectory) return Scan(files)
    val rootPath = root.toPath()
    fun walk(dir: java.nio.file.Path) {
        val children = try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (e: Exception) {
            val where = if (dir == rootPath) "." else VaultPaths.relative(rootPath, dir)
            throw IllegalStateException("не удалось открыть папку $where", e)
        }
        for (path in children) {
            if (path.isSymbolicLink()) continue
            val rel = VaultPaths.relative(rootPath, path)
            if (Files.isDirectory(path)) {
                if (VaultPaths.isSkippedDir(path.fileName.toString())) continue
                walk(path)
                continue
            }
            if (!Files.isRegularFile(path) || VaultPaths.isIgnored(rel)) continue
            val size = Files.size(path)
            if (size > Limits.MAX_FILE) error("файл больше 64 МБ, синк остановлен: $rel")
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                throw IllegalStateException("не удалось прочитать $rel", e)
            }
            files[rel] = Crypto.sha256(bytes)
        }
    }
    walk(rootPath)
    return Scan(files)
}

data class LocalDelta(
    val overlay: Map<String, FileEntry>,
    val hidden: List<String>,
    val massDeleteBlocked: Boolean,
    val massDeleteCount: Int,
)

fun localDelta(
    base: Map<String, FileEntry>,
    disk: Map<String, String>,
    vault: File,
    device: String,
    allowMassDelete: Boolean,
): LocalDelta {
    val overlay = linkedMapOf<String, FileEntry>()
    for ((path, hash) in disk) {
        val previous = base[path]
        if (previous == null || previous.deleted || previous.hash != hash) {
            overlay[path] = FileEntry(
                path = path,
                hash = hash,
                version = (previous?.version ?: 0L) + 1L,
                deleted = false,
                updatedBy = device,
                blobId = "",
            )
        }
    }

    val hidden = mutableListOf<String>()
    val tombstones = mutableListOf<String>()
    if (vault.isDirectory) {
        for ((path, previous) in base) {
            if (previous.deleted || VaultPaths.isIgnored(path) || path in disk) continue
            if (parentExists(vault, path)) tombstones += path else hidden += path
        }
    }

    val living = base.values.count { !it.deleted }
    val tooMany = tombstones.size > Limits.MASS_DELETE_LIMIT ||
        (living >= 10 && tombstones.size * 2 > living)
    val blocked = tooMany && !allowMassDelete
    if (!blocked) {
        for (path in tombstones) overlay[path] = tombstone(base.getValue(path), device)
        if (allowMassDelete) {
            for (path in hidden) overlay[path] = tombstone(base.getValue(path), device)
        }
    }
    return LocalDelta(
        overlay = overlay,
        hidden = if (allowMassDelete) emptyList() else hidden,
        massDeleteBlocked = blocked,
        massDeleteCount = tombstones.size,
    )
}

private fun tombstone(previous: FileEntry, device: String) = FileEntry(
    path = previous.path,
    hash = "",
    version = previous.version + 1,
    deleted = true,
    updatedBy = device,
    blobId = "",
)

private fun parentExists(vault: File, path: String): Boolean {
    val slash = path.lastIndexOf('/')
    if (slash < 0) return vault.isDirectory
    return VaultPaths.resolve(vault, path.substring(0, slash)).isDirectory
}
