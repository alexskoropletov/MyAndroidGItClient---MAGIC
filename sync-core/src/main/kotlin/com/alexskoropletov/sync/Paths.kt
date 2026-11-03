package com.alexskoropletov.sync

import java.io.File
import java.io.IOException

object VaultPaths {
    private val skippedDirs = setOf(".git", ".stfolder", ".trash")

    fun isSkippedDir(name: String): Boolean = name in skippedDirs

    fun isIgnored(relative: String): Boolean {
        val parts = relative.split('/')
        if (parts.any { it in skippedDirs }) return true
        val name = parts.last()
        if (name == ".DS_Store" || name.startsWith(".magic-sync-tmp-")) return true
        return relative == ".obsidian/workspace.json" || relative == ".obsidian/workspace-mobile.json"
    }

    fun colonAllowed(): Boolean {
        val vm = System.getProperty("java.vm.name").orEmpty()
        return !vm.contains("Dalvik", ignoreCase = true)
    }

    fun toDisk(relative: String, forbidColon: Boolean = !colonAllowed()): String {
        return if (forbidColon) relative.replace(':', '\uFF1A') else relative
    }

    fun fromDisk(relative: String, forbidColon: Boolean = !colonAllowed()): String {
        return if (forbidColon) relative.replace('\uFF1A', ':') else relative
    }

    fun relative(root: java.nio.file.Path, file: java.nio.file.Path): String {
        val rel = root.relativize(file).toString().replace('\\', '/')
        checkPath(rel)
        return fromDisk(rel)
    }

    fun resolve(root: File, relative: String): File {
        checkPath(relative)
        val rootPath = root.canonicalFile.toPath()
        val target = rootPath.resolve(toDisk(relative)).normalize()
        require(target.startsWith(rootPath)) { "путь выходит из хранилища: $relative" }
        return target.toFile()
    }

    fun sanitizeDevice(raw: String): String {
        val clean = raw.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24)
        return clean.ifEmpty { "device" }
    }

    fun conflictName(original: String, device: String, version: Long, taken: Set<String>): String {
        val slash = original.lastIndexOf('/')
        val dir = if (slash >= 0) original.substring(0, slash + 1) else ""
        val file = if (slash >= 0) original.substring(slash + 1) else original
        val dot = file.lastIndexOf('.')
        val stem = if (dot > 0) file.substring(0, dot) else file
        val ext = if (dot > 0) file.substring(dot) else ""
        val safe = sanitizeDevice(device)
        var name = "$dir$stem.sync-conflict-$safe$ext"
        var n = version
        var guard = 0
        while (name in taken) {
            name = "$dir$stem.sync-conflict-$safe-$n$ext"
            n++
            guard++
            if (guard > 10000) error("не нашёл имя для копии конфликта: $original")
        }
        checkPath(name)
        return name
    }

    private fun checkPath(relative: String) {
        require(relative.isNotBlank()) { "пустой путь" }
        require(relative.length <= 1024) { "слишком длинный путь" }
        require(!relative.startsWith("/") && !relative.startsWith("\\")) { "путь выходит из хранилища: $relative" }
        val parts = relative.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "плохой путь: $relative" }
    }
}

fun defaultStateFile(directory: File, vault: File): File {
    val path = try {
        vault.canonicalPath
    } catch (_: IOException) {
        vault.absolutePath
    }
    val id = Crypto.sha256(path.encodeToByteArray()).take(12)
    return File(directory, "state-$id.json")
}
