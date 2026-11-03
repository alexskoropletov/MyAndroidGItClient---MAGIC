package com.alexskoropletov.magic

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.util.FS
import java.io.File

class GitOpException(message: String) : Exception(message)

/**
 * Thin operations on top of JGit: clone, commit, push, pull.
 * SSH is installed for the next network call via [installKey].
 */
object GitOps {
    private val lock = Any()
    private const val TIMEOUT_SECONDS = 120

    fun installKey(privateKey: File, passphrase: String?) {
        if (!privateKey.isFile) {
            throw GitOpException("Сначала выбери или создай приватный ключ")
        }
        val pass = passphrase?.takeIf { it.isNotEmpty() }
        val pair = com.jcraft.jsch.KeyPair.load(JSch(), privateKey.absolutePath)
        try {
            if (pair.isEncrypted) {
                if (pass == null || !pair.decrypt(pass.encodeToByteArray())) {
                    throw GitOpException("Пароль ключа не подошёл")
                }
            }
        } finally {
            pair.dispose()
        }
        SshSessionFactory.setInstance(KeySshFactory(privateKey, pass))
    }

    fun clone(url: String, directory: File, monitor: ProgressMonitor = NullProgressMonitor.INSTANCE): String {
        val remote = url.trim()
        if (remote.isEmpty()) throw GitOpException("Укажи адрес репозитория")
        if (!remote.startsWith("git@") && !remote.startsWith("ssh://")) {
            throw GitOpException("Нужен SSH-адрес: git@сервер:путь/репо.git")
        }
        if (directory.isFile) throw GitOpException("По этому пути файл, а не папка")
        if (File(directory, ".git").isDirectory) {
            throw GitOpException("В этой папке уже есть репозиторий. Нажми «Пулл».")
        }
        if (directory.exists() && directory.list().orEmpty().isNotEmpty()) {
            throw GitOpException("Папка не пустая")
        }
        val parent = directory.parentFile ?: throw GitOpException("Непонятный путь")
        if (!parent.exists() && !parent.mkdirs()) {
            throw GitOpException("Не удалось создать ${parent.path}")
        }

        return synchronized(lock) {
            Git.cloneRepository()
                .setURI(remote)
                .setDirectory(directory)
                .setTimeout(TIMEOUT_SECONDS)
                .setProgressMonitor(monitor)
                .call()
                .use { "Стянуто в ${directory.path}" }
        }
    }

    fun commit(directory: File, message: String, name: String, email: String): String {
        val text = message.trim()
        if (text.isEmpty()) throw GitOpException("Напиши сообщение коммита")
        rememberIdentity(directory, name, email)
        return open(directory).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val status = git.status().call()
            if (status.isClean) throw GitOpException("Нечего коммитить")
            val rev = git.commit()
                .setMessage(text)
                .setAuthor(name.trim(), email.trim())
                .setCommitter(name.trim(), email.trim())
                .call()
            val files = status.added.size + status.changed.size + status.removed.size
            "Коммит ${rev.name.take(7)}, файлов: $files"
        }
    }

    fun push(directory: File, monitor: ProgressMonitor = NullProgressMonitor.INSTANCE): String {
        return open(directory).use { git ->
            val branch = currentBranch(git)
            val results = synchronized(lock) {
                git.push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .setTimeout(TIMEOUT_SECONDS)
                    .setProgressMonitor(monitor)
                    .call()
            }
            val lines = mutableListOf<String>()
            for (result in results) {
                for (update in result.remoteUpdates) {
                    val ref = update.remoteName.removePrefix("refs/heads/")
                    lines += when (update.status) {
                        RemoteRefUpdate.Status.OK -> "Отправлено: $ref"
                        RemoteRefUpdate.Status.UP_TO_DATE -> "Уже на сервере: $ref"
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                            "Сервер впереди ($ref). Сначала пулл."
                        else -> "${update.status}: $ref ${update.message.orEmpty()}".trim()
                    }
                }
            }
            if (lines.isEmpty()) "Пуш ничего не отправил" else lines.joinToString("\n")
        }
    }

    fun pull(
        directory: File,
        name: String,
        email: String,
        monitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): String {
        rememberIdentity(directory, name, email)
        return open(directory).use { git ->
            val branch = currentBranch(git)
            val result = synchronized(lock) {
                git.pull()
                    .setRemote("origin")
                    .setRemoteBranchName(branch)
                    .setRebase(false)
                    .setTimeout(TIMEOUT_SECONDS)
                    .setProgressMonitor(monitor)
                    .call()
            }
            val merge = result.mergeResult
                ?: return "Пулл выполнен"
            when (val status = merge.mergeStatus) {
                org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> "Уже актуально"
                org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD -> "Пулл: fast-forward"
                org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED,
                org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED_SQUASHED,
                org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED_NOT_COMMITTED,
                -> "Пулл: слито"
                org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING -> {
                    val files = merge.conflicts?.keys?.joinToString(", ").orEmpty()
                    "Конфликт: $files. Разбери файлы в папке и закоммить."
                }
                else -> "Пулл: $status"
            }
        }
    }

    private fun rememberIdentity(directory: File, name: String, email: String) {
        val author = name.trim()
        val mail = email.trim()
        if (author.isEmpty() || mail.isEmpty()) {
            throw GitOpException("Нужны имя и почта для коммита")
        }
        open(directory).use { git ->
            val config = git.repository.config
            config.setString("user", null, "name", author)
            config.setString("user", null, "email", mail)
            config.save()
        }
    }

    private fun open(directory: File): Git {
        if (!File(directory, ".git").isDirectory) {
            throw GitOpException("В папке нет репозитория. Сначала «Стянуть».")
        }
        return try {
            Git.open(directory)
        } catch (e: Exception) {
            throw GitOpException(e.message ?: "Не открыть репозиторий")
        }
    }

    private fun currentBranch(git: Git): String {
        val full = git.repository.fullBranch
        val prefix = "refs/heads/"
        if (full == null || !full.startsWith(prefix)) {
            throw GitOpException("Сейчас не выбрана ветка")
        }
        return full.removePrefix(prefix)
    }
}

fun Throwable.explain(): String {
    if (this is GitOpException) return message ?: "Ошибка"
    return generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n")
        .ifBlank { (this as? GitAPIException)?.javaClass?.simpleName ?: javaClass.simpleName }
}

private class KeySshFactory(
    private val privateKey: File,
    private val passphrase: String?,
) : JschConfigSessionFactory() {
    override fun createDefaultJSch(fs: FS): JSch {
        val jsch = super.createDefaultJSch(fs)
        if (passphrase.isNullOrEmpty()) {
            jsch.addIdentity(privateKey.absolutePath)
        } else {
            jsch.addIdentity(privateKey.absolutePath, passphrase.encodeToByteArray())
        }
        return jsch
    }

    override fun configure(hc: OpenSshConfig.Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")
    }
}
