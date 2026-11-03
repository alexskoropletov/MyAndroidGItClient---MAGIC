package com.alexskoropletov.magic

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File

data class StoredKey(val privateKey: File, val publicKey: String)

object SshKeys {
    fun generate(directory: File, comment: String): StoredKey {
        SshCrypto.install()
        directory.mkdirs()
        val privateKey = File(directory, "id_ed25519")
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try {
            pair.writeOpenSSHv1PrivateKey(privateKey.absolutePath)
            val publicKey = publicLine(pair, comment)
            File(directory, "id_ed25519.pub").writeText(publicKey + "\n")
            lockDown(privateKey)
            return StoredKey(privateKey, publicKey)
        } finally {
            pair.dispose()
        }
    }

    fun import(bytes: ByteArray, directory: File, passphrase: String?, comment: String): StoredKey {
        SshCrypto.install()
        directory.mkdirs()
        val privateKey = File(directory, "id_ed25519")
        privateKey.writeBytes(bytes)
        lockDown(privateKey)
        val pair = KeyPair.load(JSch(), privateKey.absolutePath)
        try {
            unlock(pair, passphrase)
            val publicKey = publicLine(pair, comment)
            File(directory, "id_ed25519.pub").writeText(publicKey + "\n")
            return StoredKey(privateKey, publicKey)
        } finally {
            pair.dispose()
        }
    }

    private fun unlock(pair: KeyPair, passphrase: String?) {
        if (!pair.isEncrypted) return
        val pass = passphrase?.takeIf { it.isNotEmpty() }
            ?: throw GitOpException("Ключ зашифрован. Впиши пароль и выбери файл ещё раз.")
        if (!pair.decrypt(pass.encodeToByteArray())) throw GitOpException("Пароль ключа не подошёл")
    }

    private fun publicLine(pair: KeyPair, comment: String): String {
        val out = ByteArrayOutputStream()
        pair.writePublicKey(out, comment.ifBlank { "magic" })
        val line = out.toString(Charsets.UTF_8).trim()
        if (line.isEmpty()) throw GitOpException("Не удалось прочитать публичный ключ")
        return line
    }

    private fun lockDown(privateKey: File) {
        privateKey.setReadable(false, false)
        privateKey.setReadable(true, true)
        privateKey.setWritable(false, false)
        privateKey.setWritable(true, true)
    }
}
