package com.alexskoropletov.sync

import java.io.File
import java.io.IOException
import java.nio.file.Files

class SyncEngine(
    private val vault: File,
    private val remote: Remote,
    private val key: ByteArray,
    private val device: String,
    private val stateFile: File,
    private val allowMassDelete: Boolean = false,
) {
    fun sync(): List<String> {
        val log = mutableListOf<String>()
        val root = try {
            vault.canonicalFile
        } catch (e: IOException) {
            throw IllegalStateException("не открывается папка ${vault.path}", e)
        }
        require(root.isDirectory) { "нет папки хранилища: ${root.path}" }
        require(key.size == 32) { "ключ должен быть 32 байта" }
        var state = loadState()
        if (state.vaultPath.isNotEmpty() && state.vaultPath != root.path) {
            error("файл состояния от другого хранилища: ${state.vaultPath}")
        }
        val deviceName = VaultPaths.sanitizeDevice(state.device.ifEmpty { device })
        var noted = false

        for (attempt in 1..5) {
            val pointer = remote.fetchPointer()
            if (state.revision > 0 && pointer.revision < state.revision) {
                error("сервер отдал старую ревизию ${pointer.revision}, локально уже ${state.revision}. Ничего не записываю.")
            }
            val remoteEntries = loadRemote(pointer)
            val disk = scanVault(root).files
            val delta = localDelta(state.byPath(), disk, root, deviceName, allowMassDelete)
            if (!noted) {
                if (delta.massDeleteBlocked) {
                    log += "С диска пропало ${delta.massDeleteCount} файлов. Не удаляю их на других устройствах и скачиваю обратно."
                }
                if (delta.hidden.isNotEmpty()) {
                    log += "Не видно ${delta.hidden.size} файлов: нет папки, где они лежали. Не удаляю их и скачиваю обратно."
                }
                noted = true
            }
            val quiet = pointer.revision == state.revision &&
                pointer.blobId.orEmpty() == state.indexBlobId &&
                delta.overlay.isEmpty() &&
                delta.hidden.isEmpty() &&
                !delta.massDeleteBlocked
            if (quiet) {
                log += "Изменений нет."
                return log
            }

            val base = state.byPath()
            val local = base.toMutableMap()
            local.putAll(delta.overlay)
            val plan = merge(base, local, remoteEntries, delta.overlay.keys, deviceName)
            val entries = plan.entries.toMutableMap()
            var uploaded = 0
            for (entry in entries.values.toList()) {
                if (entry.deleted || entry.blobId.isNotEmpty()) continue
                val file = VaultPaths.resolve(root, entry.path)
                if (!file.isFile) error("файл исчез во время синка: ${entry.path}")
                val bytes = file.readBytes()
                if (bytes.size > Limits.MAX_FILE) error("файл больше 64 МБ: ${entry.path}")
                val hash = Crypto.sha256(bytes)
                if (hash != entry.hash) error("файл изменился во время синка: ${entry.path}")
                val cipher = Crypto.encrypt(key, bytes)
                val id = Crypto.sha256(cipher)
                remote.putBlob(id, cipher)
                entries[entry.path] = entry.copy(blobId = id)
                uploaded++
            }
            if (entries.values.any { !it.deleted && it.blobId.isEmpty() }) {
                error("не у всех файлов есть содержимое на сервере")
            }

            if (delta.overlay.isEmpty() && sameSnapshot(entries, remoteEntries)) {
                val applied = applyDisk(root, entries, disk)
                val next = state.copy(
                    vaultPath = root.path,
                    device = deviceName,
                    revision = pointer.revision,
                    indexBlobId = pointer.blobId.orEmpty(),
                    entries = entries.values.sortedBy { it.path },
                )
                return finish(log, next, pointer.revision, entries.values, 0, applied, plan.conflicts)
            }

            val document = IndexDocument(
                revision = pointer.revision + 1,
                entries = entries.values.sortedBy { it.path },
            )
            val indexCipher = Crypto.encrypt(key, Wire.encode(document).encodeToByteArray())
            val indexId = Crypto.sha256(indexCipher)
            remote.putBlob(indexId, indexCipher)
            if (!remote.tryPushIndex(pointer.revision, indexId)) {
                log += "Сервер уже на новой ревизии, повторяю."
                continue
            }
            val applied = applyDisk(root, entries, disk)
            val next = LocalState(
                vaultPath = root.path,
                device = deviceName,
                revision = document.revision,
                indexBlobId = indexId,
                entries = document.entries,
            )
            return finish(log, next, document.revision, entries.values, uploaded, applied, plan.conflicts)
        }
        error("не удалось записать индекс: сервер пять раз успел обновиться раньше")
    }

    private fun finish(
        log: MutableList<String>,
        state: LocalState,
        revision: Long,
        entries: Collection<FileEntry>,
        uploaded: Int,
        applied: Applied,
        conflicts: Int,
    ): List<String> {
        if (applied.failed.isEmpty()) {
            saveState(state)
        }
        log += done(revision, entries, uploaded, applied, conflicts)
        applied.failed.forEach { log += it }
        return log
    }

    private fun done(revision: Long, entries: Collection<FileEntry>, uploaded: Int, applied: Applied, conflicts: Int): String {
        val alive = entries.count { !it.deleted }
        val head = "Готово. Ревизия $revision. Файлов $alive. Отправлено $uploaded, скачано ${applied.downloaded}, удалено ${applied.deleted}, конфликтов $conflicts."
        return if (applied.failed.isEmpty()) {
            head
        } else {
            "$head Не записал ${applied.failed.size}, повторю их в следующий раз."
        }
    }

    private fun loadState(): LocalState {
        if (!stateFile.isFile) return LocalState()
        return try {
            Wire.decode(stateFile.readText())
        } catch (e: Exception) {
            throw IllegalStateException("не читается файл состояния ${stateFile.path}", e)
        }
    }

    private fun saveState(state: LocalState) {
        atomicWrite(stateFile, Wire.encode(state).encodeToByteArray())
    }

    private fun loadRemote(pointer: IndexPointer): Map<String, FileEntry> {
        val id = pointer.blobId ?: return emptyMap()
        val plain = decrypt(remote.getBlob(id), "индекс")
        return try {
            Wire.decode<IndexDocument>(plain.toString(Charsets.UTF_8)).byPath()
        } catch (e: Exception) {
            throw IllegalStateException("индекс повреждён", e)
        }
    }

    private fun decrypt(bytes: ByteArray, label: String): ByteArray {
        try {
            return Crypto.decrypt(key, bytes)
        } catch (e: Exception) {
            throw IllegalStateException("не удалось расшифровать $label. Ключ не подходит или данные повреждены.", e)
        }
    }

    private fun sameSnapshot(local: Map<String, FileEntry>, remote: Map<String, FileEntry>): Boolean {
        if (local.size != remote.size) return false
        return local.all { (path, entry) ->
            val other = remote[path] ?: return false
            entry.hash == other.hash &&
                entry.deleted == other.deleted &&
                entry.blobId == other.blobId &&
                entry.version == other.version
        }
    }

    private fun applyDisk(root: File, entries: Map<String, FileEntry>, disk: Map<String, String>): Applied {
        var downloaded = 0
        var deleted = 0
        val failed = mutableListOf<String>()
        for (entry in entries.values.sortedBy { it.path }) {
            if (VaultPaths.isIgnored(entry.path)) continue
            try {
                val file = VaultPaths.resolve(root, entry.path)
                if (entry.deleted) {
                    if (file.isFile) {
                        if (!file.delete()) error("не удалось удалить ${entry.path}")
                        deleted++
                    }
                    continue
                }
                if (disk[entry.path] == entry.hash && file.isFile) continue
                if (file.isDirectory) error("на месте файла папка: ${entry.path}")
                val plain = decrypt(remote.getBlob(entry.blobId), entry.path)
                if (Crypto.sha256(plain) != entry.hash) error("хэш не сошёлся: ${entry.path}")
                atomicWrite(file, plain)
                downloaded++
            } catch (e: Exception) {
                failed += "${entry.path} — ${e.message ?: "не записался"}"
            }
        }
        pruneEmptyDirs(root)
        return Applied(downloaded, deleted, failed)
    }

    private fun pruneEmptyDirs(root: File) {
        val dirs = mutableListOf<File>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (!child.isDirectory || Files.isSymbolicLink(child.toPath()) || child.name.startsWith(".")) continue
                walk(child)
            }
            dirs += dir
        }
        walk(root)
        for (dir in dirs) {
            if (dir == root) continue
            if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
        }
    }

    private data class Applied(val downloaded: Int, val deleted: Int, val failed: List<String> = emptyList())
}
