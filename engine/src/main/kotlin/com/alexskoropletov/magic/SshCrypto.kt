package com.alexskoropletov.magic

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import java.util.ArrayDeque

/**
 * Android loads the Java 8 view of JSch, which signs Ed25519 through BouncyCastle.
 * The JDK Ed25519 classes in that jar only throw "requires Java 15+".
 */
internal object SshCrypto {
    private val lines = ArrayDeque<String>()

    fun install() {
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        JSch.setLogger(object : Logger {
            override fun isEnabled(level: Int): Boolean = level <= Logger.INFO

            override fun log(level: Int, message: String?) {
                val line = message?.trim().orEmpty()
                if (line.isEmpty()) return
                synchronized(lines) {
                    lines.addLast(line)
                    while (lines.size > 40) lines.removeFirst()
                }
            }
        })
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    fun recent(): String = synchronized(lines) { lines.joinToString("\n") }
}
