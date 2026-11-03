package com.alexskoropletov.sync

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val key = Crypto.keygen()

    @Test
    fun emptyVaultDownloadsAndDoesNotWipeTheServer() {
        World(key).use { world ->
            File(world.a, "from-a.md").writeText("A")
            world.engine(world.a, "computer").sync()
            assertEquals(1L, world.remote.revision)
            world.engine(world.b, "phone").sync()
            assertEquals("A", File(world.b, "from-a.md").readText())
            assertEquals(1L, world.remote.revision)
            File(world.b, "from-b.md").writeText("B")
            world.engine(world.b, "phone").sync()
            world.engine(world.a, "computer").sync()
            assertEquals("B", File(world.a, "from-b.md").readText())
            assertEquals("A", File(world.b, "from-a.md").readText())
        }
    }

    @Test
    fun editAndSingleDeletePropagate() {
        World(key).use { world ->
            repeat(15) { File(world.a, "n$it.md").writeText("v$it") }
            world.engine(world.a, "computer").sync()
            world.engine(world.b, "phone").sync()
            File(world.a, "n0.md").writeText("changed")
            File(world.a, "n1.md").delete()
            world.engine(world.a, "computer").sync()
            world.engine(world.b, "phone").sync()
            assertEquals("changed", File(world.b, "n0.md").readText())
            assertFalse(File(world.b, "n1.md").exists())
            assertEquals("v2", File(world.b, "n2.md").readText())
        }
    }

    @Test
    fun missingFolderIsRestored() {
        World(key).use { world ->
            File(world.a, "dir").mkdirs()
            File(world.a, "dir/a.md").writeText("secret")
            world.engine(world.a, "computer").sync()
            world.engine(world.b, "phone").sync()
            File(world.b, "dir").deleteRecursively()
            val log = world.engine(world.b, "phone").sync()
            assertTrue(log.joinToString("\n"), log.any { "нет папки" in it })
            assertEquals("secret", File(world.b, "dir/a.md").readText())
            assertEquals("secret", File(world.a, "dir/a.md").readText())
        }
    }

    @Test
    fun massDeleteIsRestoredUnlessAllowed() {
        World(key).use { world ->
            repeat(40) { File(world.a, "f$it.md").writeText("x$it") }
            world.engine(world.a, "computer").sync()
            world.engine(world.b, "phone").sync()
            repeat(40) { File(world.b, "f$it.md").delete() }
            val log = world.engine(world.b, "phone").sync()
            assertTrue(log.joinToString("\n"), log.any { "пропало" in it })
            assertEquals("x0", File(world.b, "f0.md").readText())
            assertEquals("x39", File(world.a, "f39.md").readText())

            File(world.b, "dir").mkdirs()
            File(world.b, "dir/gone.md").writeText("bye")
            world.engine(world.b, "phone").sync()
            world.engine(world.a, "computer").sync()
            File(world.b, "dir").deleteRecursively()
            world.engine(world.b, "phone", allowMassDelete = true).sync()
            world.engine(world.a, "computer").sync()
            assertFalse(File(world.a, "dir/gone.md").exists())
            assertEquals("x0", File(world.a, "f0.md").readText())
        }
    }

    @Test
    fun conflictKeepsBothCopies() {
        World(key).use { world ->
            File(world.a, "note.md").writeText("one")
            world.engine(world.a, "computer").sync()
            world.engine(world.b, "phone").sync()
            File(world.a, "note.md").writeText("two")
            File(world.b, "note.md").writeText("three")
            world.engine(world.a, "computer").sync()
            world.engine(world.b, "phone").sync()
            world.engine(world.a, "computer").sync()
            assertEquals(setOf("two", "three"), texts(world.a))
            assertEquals(setOf("two", "three"), texts(world.b))
        }
    }

    @Test
    fun gitAndWorkspaceStayLocal() {
        World(key).use { world ->
            File(world.a, ".git").mkdirs()
            File(world.a, ".git/config").writeText("secret")
            File(world.a, ".obsidian").mkdirs()
            File(world.a, ".obsidian/workspace.json").writeText("desk")
            File(world.a, ".obsidian/app.json").writeText("app")
            File(world.a, "note.md").writeText("hi")
            world.engine(world.a, "computer").sync()
            world.engine(world.b, "phone").sync()
            assertEquals("hi", File(world.b, "note.md").readText())
            assertEquals("app", File(world.b, ".obsidian/app.json").readText())
            assertFalse(File(world.b, ".git/config").exists())
            assertFalse(File(world.b, ".obsidian/workspace.json").exists())
        }
    }

    @Test
    fun tamperedBlobIsNotWritten() {
        World(key).use { world ->
            File(world.a, "note.md").writeText("hello")
            world.engine(world.a, "computer").sync()
            val id = world.remote.blobs.keys.first { it != world.remote.indexId }
            val bytes = world.remote.blobs.getValue(id)
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex] + 1).toByte()
            val error = runCatching { world.engine(world.b, "phone").sync() }.exceptionOrNull()
            assertTrue(error?.message, error?.message?.contains("расшифровать") == true)
            assertFalse(File(world.b, "note.md").exists())
        }
    }
}

private class World(private val key: ByteArray) : Closeable {
    val remote = MemoryRemote()
    val a: File = Files.createTempDirectory("vault-a").toFile()
    val b: File = Files.createTempDirectory("vault-b").toFile()
    private val states: File = Files.createTempDirectory("states").toFile()

    fun engine(vault: File, device: String, allowMassDelete: Boolean = false) = SyncEngine(
        vault = vault,
        remote = remote,
        key = key,
        device = device,
        stateFile = defaultStateFile(states, vault),
        allowMassDelete = allowMassDelete,
    )

    override fun close() {
        a.deleteRecursively()
        b.deleteRecursively()
        states.deleteRecursively()
    }
}

private class MemoryRemote : Remote {
    var revision = 0L
    var indexId: String? = null
    val blobs = mutableMapOf<String, ByteArray>()

    override fun fetchPointer() = IndexPointer(revision, indexId)

    override fun tryPushIndex(expectedRevision: Long, blobId: String): Boolean {
        if (expectedRevision != revision) return false
        if (blobId !in blobs) error("нет блоба")
        revision += 1
        indexId = blobId
        return true
    }

    override fun putBlob(id: String, bytes: ByteArray) {
        blobs[id] = bytes.copyOf()
    }

    override fun getBlob(id: String): ByteArray = blobs[id]?.copyOf() ?: error("нет файла")
}

private fun texts(root: File) = root.walkTopDown().filter { it.isFile }.map { it.readText() }.toSet()
