package com.alexskoropletov.sync

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTest {
    @Test
    fun colonInNameIsStoredAsFullwidthWhenFilesystemForbidsIt() {
        val logical = "Навыки/Иврит 15 — Монолог: еда.md"
        val stored = VaultPaths.toDisk(logical, forbidColon = true)
        assertFalse(stored.contains(':'))
        assertEquals(logical, VaultPaths.fromDisk(stored, forbidColon = true))
        assertEquals(logical, VaultPaths.toDisk(logical, forbidColon = false))
    }

    @Test
    fun skipsGitWorkspaceSymlinkAndKeepsRealNotes() {
        val root = Files.createTempDirectory("scan").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            FileDir(root, ".git").mkdirs()
            FileDir(root, ".git/config").writeText("secret")
            FileDir(root, ".obsidian").mkdirs()
            FileDir(root, ".obsidian/workspace.json").writeText("desk")
            FileDir(root, ".obsidian/app.json").writeText("app")
            FileDir(root, "note.md").writeText("hi")
            Files.createSymbolicLink(root.toPath().resolve("link.md"), outside.toPath().resolve("x"))
            val scan = scanVault(root)
            assertEquals(setOf("note.md", ".obsidian/app.json"), scan.files.keys)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun singleMissingFileIsATombstone() {
        val root = Files.createTempDirectory("scan").toFile()
        try {
            val file = FileDir(root, "note.md")
            file.writeText("one")
            val hash = scanVault(root).files.getValue("note.md")
            file.delete()
            val base = mapOf("note.md" to FileEntry("note.md", hash, 1, updatedBy = "computer", blobId = "b"))
            val delta = localDelta(base, scanVault(root).files, root, "computer", allowMassDelete = false)
            assertTrue(delta.overlay.getValue("note.md").deleted)
            assertFalse(delta.massDeleteBlocked)
            assertTrue(delta.hidden.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingParentIsNotADelete() {
        val root = Files.createTempDirectory("scan").toFile()
        try {
            FileDir(root, "dir").mkdirs()
            FileDir(root, "dir/a.md").writeText("a")
            val hash = scanVault(root).files.getValue("dir/a.md")
            FileDir(root, "dir").deleteRecursively()
            val base = mapOf("dir/a.md" to FileEntry("dir/a.md", hash, 1, updatedBy = "computer", blobId = "b"))
            val delta = localDelta(base, scanVault(root).files, root, "computer", allowMassDelete = false)
            assertTrue(delta.overlay.isEmpty())
            assertEquals(listOf("dir/a.md"), delta.hidden)
            val allowed = localDelta(base, scanVault(root).files, root, "computer", allowMassDelete = true)
            assertTrue(allowed.overlay.getValue("dir/a.md").deleted)
            assertTrue(allowed.hidden.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun massDeleteIsBlockedUntilAllowed() {
        val root = Files.createTempDirectory("scan").toFile()
        try {
            val base = (1..40).associate { n ->
                "f$n.md" to FileEntry("f$n.md", "h$n", 1, updatedBy = "computer", blobId = "b$n")
            }
            val blocked = localDelta(base, emptyMap(), root, "computer", allowMassDelete = false)
            assertTrue(blocked.massDeleteBlocked)
            assertTrue(blocked.overlay.isEmpty())
            assertEquals(40, blocked.massDeleteCount)
            val allowed = localDelta(base, emptyMap(), root, "computer", allowMassDelete = true)
            assertFalse(allowed.massDeleteBlocked)
            assertEquals(40, allowed.overlay.size)
            assertTrue(allowed.overlay.values.all { it.deleted })
        } finally {
            root.deleteRecursively()
        }
    }
}

private typealias FileDir = java.io.File
