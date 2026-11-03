package com.alexskoropletov.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    @Test
    fun remoteNewFileIsKept() {
        val remote = mapOf("a.md" to file("a.md", "h", 1, by = "phone"))
        val plan = merge(emptyMap(), emptyMap(), remote, emptySet(), "computer")
        assertEquals("h", plan.entries.getValue("a.md").hash)
        assertEquals(0, plan.conflicts)
    }

    @Test
    fun omittedRemotePathIsNotADelete() {
        val base = mapOf("note.md" to file("note.md", "a", 1))
        val plan = merge(base, base, emptyMap(), emptySet(), "computer")
        assertEquals("a", plan.entries.getValue("note.md").hash)
        assertFalse(plan.entries.getValue("note.md").deleted)
    }

    @Test
    fun bothEditsKeepLocalAndRemoteCopy() {
        val base = mapOf("note.md" to file("note.md", "a", 1, blob = "b1"))
        val local = mapOf("note.md" to file("note.md", "b", 2, blob = ""))
        val remote = mapOf("note.md" to file("note.md", "c", 2, blob = "b2", by = "phone"))
        val plan = merge(base, local, remote, setOf("note.md"), "computer")
        assertEquals("b", plan.entries.getValue("note.md").hash)
        assertEquals("", plan.entries.getValue("note.md").blobId)
        val copy = plan.entries.values.single { it.path.contains("sync-conflict") }
        assertEquals("c", copy.hash)
        assertEquals("b2", copy.blobId)
        assertTrue(copy.path.contains("phone"))
        assertEquals(1, plan.conflicts)
    }

    @Test
    fun sameBytesAreNotAConflict() {
        val base = mapOf("note.md" to file("note.md", "a", 1, blob = "b1"))
        val local = mapOf("note.md" to file("note.md", "b", 2, blob = ""))
        val remote = mapOf("note.md" to file("note.md", "b", 3, blob = "b2", by = "phone"))
        val plan = merge(base, local, remote, setOf("note.md"), "computer")
        assertEquals(1, plan.entries.size)
        assertEquals("b2", plan.entries.getValue("note.md").blobId)
        assertEquals(0, plan.conflicts)
    }

    @Test
    fun editBeatsDelete() {
        val base = mapOf("note.md" to file("note.md", "a", 1))
        val local = mapOf("note.md" to file("note.md", "b", 2, blob = ""))
        val remote = mapOf("note.md" to file("note.md", "", 2, deleted = true, blob = ""))
        val plan = merge(base, local, remote, setOf("note.md"), "computer")
        assertEquals("b", plan.entries.getValue("note.md").hash)
        assertFalse(plan.entries.getValue("note.md").deleted)
        assertEquals(0, plan.conflicts)
    }

    @Test
    fun remoteEditBeatsLocalDelete() {
        val base = mapOf("note.md" to file("note.md", "a", 1))
        val local = mapOf("note.md" to file("note.md", "", 2, deleted = true, blob = ""))
        val remote = mapOf("note.md" to file("note.md", "c", 3, blob = "b9", by = "phone"))
        val plan = merge(base, local, remote, setOf("note.md"), "computer")
        assertEquals("c", plan.entries.getValue("note.md").hash)
        assertFalse(plan.entries.getValue("note.md").deleted)
        assertEquals("b9", plan.entries.getValue("note.md").blobId)
    }

    @Test
    fun localDeleteStandsWhenRemoteIsUnchanged() {
        val base = mapOf("note.md" to file("note.md", "a", 1))
        val local = mapOf("note.md" to file("note.md", "", 2, deleted = true, blob = ""))
        val plan = merge(base, local, base, setOf("note.md"), "computer")
        assertTrue(plan.entries.getValue("note.md").deleted)
    }
}

private fun file(
    path: String,
    hash: String,
    version: Long,
    deleted: Boolean = false,
    blob: String = "blob",
    by: String = "computer",
) = FileEntry(path, hash, version, deleted, by, blob)
