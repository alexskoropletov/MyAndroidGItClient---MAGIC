package com.alexskoropletov.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun InputStream.readLimited(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) error("слишком большой файл")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

fun atomicWrite(target: File, bytes: ByteArray) {
    val parent = target.parentFile ?: error("нет папки для ${target.path}")
    if (!parent.isDirectory && !parent.mkdirs()) error("не создалась папка ${parent.path}")
    val tmp = File(parent, ".magic-sync-tmp-${System.nanoTime()}")
    try {
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}
