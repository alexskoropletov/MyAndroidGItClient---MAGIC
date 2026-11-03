package com.alexskoropletov.sync.cli

import com.alexskoropletov.sync.Crypto
import com.alexskoropletov.sync.HttpRemote
import com.alexskoropletov.sync.SyncEngine
import com.alexskoropletov.sync.defaultStateFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSyncTest {
    private val key = Crypto.keygen()

    @Test
    fun twoClientsExchangeAFile() {
        withServer { remote, _, _ ->
            val a = Files.createTempDirectory("vault-a").toFile()
            val b = Files.createTempDirectory("vault-b").toFile()
            val states = Files.createTempDirectory("states").toFile()
            try {
                fun engine(vault: File, device: String) = SyncEngine(
                    vault = vault,
                    remote = remote,
                    key = key,
                    device = device,
                    stateFile = defaultStateFile(states, vault),
                )
                File(a, "note.md").writeText("hello")
                engine(a, "computer").sync()
                engine(b, "phone").sync()
                assertEquals("hello", File(b, "note.md").readText())
                File(b, "note.md").writeText("hello!")
                engine(b, "phone").sync()
                engine(a, "computer").sync()
                assertEquals("hello!", File(a, "note.md").readText())
            } finally {
                a.deleteRecursively()
                b.deleteRecursively()
                states.deleteRecursively()
            }
        }
    }

    @Test
    fun authAndRevisionCheck() {
        withServer { remote, _, port ->
            val connection = URL("http://127.0.0.1:$port/v1/index").openConnection() as HttpURLConnection
            try {
                assertEquals(401, connection.responseCode)
            } finally {
                connection.disconnect()
            }
            val bytes = "abc".encodeToByteArray()
            val id = Crypto.sha256(bytes)
            remote.putBlob(id, bytes)
            assertFalse(remote.tryPushIndex(4, id))
            assertTrue(remote.tryPushIndex(0, id))
            assertEquals(1L, remote.fetchPointer().revision)
        }
    }

    @Test
    fun rolledBackServerIsRefused() {
        withServer { remote, data, _ ->
            val vault = Files.createTempDirectory("vault").toFile()
            val states = Files.createTempDirectory("states").toFile()
            try {
                File(vault, "note.md").writeText("hello")
                val engine = SyncEngine(
                    vault = vault,
                    remote = remote,
                    key = key,
                    device = "computer",
                    stateFile = defaultStateFile(states, vault),
                )
                engine.sync()
                File(data, "index.json").writeText("""{"revision":0,"blobId":null}""")
                val error = runCatching { engine.sync() }.exceptionOrNull()
                assertTrue(error?.message, error?.message?.contains("старую ревизию") == true)
                assertEquals("hello", File(vault, "note.md").readText())
            } finally {
                vault.deleteRecursively()
                states.deleteRecursively()
            }
        }
    }

    private fun withServer(block: (HttpRemote, File, Int) -> Unit) {
        val data = Files.createTempDirectory("magic-data").toFile()
        val http = BlobServer(data, "secret", "127.0.0.1", 0).start()
        try {
            val port = http.address.port
            block(HttpRemote("http://127.0.0.1:$port", "secret"), data, port)
        } finally {
            http.stop(0)
            data.deleteRecursively()
        }
    }
}
