package com.alexskoropletov.magic

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class GitOpsTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("magic-git").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun fileRoundTrip() {
        val remote = bareWithSeed().toURI().toString()

        val first = java.io.File(root, "first")
        cloneFile(remote, first)
        java.io.File(first, "readme.txt").writeText("one\n")
        val committed = GitOps.commit(first, "first", "Ada", "ada@example.com")
        assertTrue(committed.startsWith("Коммит "))
        GitOps.push(first)

        val second = java.io.File(root, "second")
        cloneFile(remote, second)
        assertEquals("one\n", java.io.File(second, "readme.txt").readText())

        java.io.File(second, "more.txt").writeText("two\n")
        GitOps.commit(second, "second", "Ada", "ada@example.com")
        GitOps.push(second)

        val pulled = GitOps.pull(first, "Ada", "ada@example.com")
        assertTrue(pulled.contains("fast-forward"))
        assertEquals("two\n", java.io.File(first, "more.txt").readText())
    }

    @Test
    fun emptyCommitIsReported() {
        val work = java.io.File(root, "work")
        cloneFile(bareWithSeed().toURI().toString(), work)
        java.io.File(work, "a.txt").writeText("a\n")
        GitOps.commit(work, "add", "Ada", "ada@example.com")

        val error = runCatching { GitOps.commit(work, "again", "Ada", "ada@example.com") }
            .exceptionOrNull()
        assertTrue(error is GitOpException)
        assertEquals("Нечего коммитить", error?.message)
    }

    @Test
    fun httpsAddressIsRejected() {
        val error = runCatching {
            GitOps.clone("https://example.com/repo.git", java.io.File(root, "nope"))
        }.exceptionOrNull()
        assertTrue(error is GitOpException)
        assertTrue(error?.message.orEmpty().contains("SSH"))
    }

    @Test
    fun generatedKeyHasEd25519PublicLine() {
        val dir = java.io.File(root, "keys")
        val stored = SshKeys.generate(dir, "magic@phone")
        assertTrue(stored.privateKey.isFile)
        assertTrue(stored.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(stored.publicKey.endsWith("magic@phone"))
        val loaded = KeyPair.load(JSch(), stored.privateKey.absolutePath)
        assertFalse(loaded.isEncrypted)
        loaded.dispose()
    }

    private fun bareWithSeed(): java.io.File {
        val work = java.io.File(root, "seed-work")
        Git.init().setDirectory(work).setInitialBranch("main").call().use { git ->
            java.io.File(work, "seed.txt").writeText("seed\n")
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("seed")
                .setAuthor("Ada", "ada@example.com")
                .setCommitter("Ada", "ada@example.com")
                .call()
        }
        val bare = java.io.File(root, "origin.git")
        Git.cloneRepository()
            .setURI(work.toURI().toString())
            .setDirectory(bare)
            .setBare(true)
            .call()
            .close()
        return bare
    }

    private fun cloneFile(remote: String, directory: java.io.File) {
        org.eclipse.jgit.api.Git.cloneRepository()
            .setURI(remote)
            .setDirectory(directory)
            .call()
            .close()
    }
}
