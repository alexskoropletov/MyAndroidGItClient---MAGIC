package com.alexskoropletov.magic

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF143028)
private val Leaf = Color(0xFF1F6B4A)
private val Mist = Color(0xFFF3F7F4)
private val Paper = Color(0xFFFFFFFF)

@Composable
fun MagicTheme(content: @Composable () -> Unit) {
    val dark = darkColorScheme(
        primary = Color(0xFF8FDBB2),
        onPrimary = Color(0xFF003824),
        background = Color(0xFF101916),
        surface = Color(0xFF1A2C24),
        onBackground = Color(0xFFE7F5EC),
        onSurface = Color(0xFFE7F5EC),
    )
    val light = lightColorScheme(
        primary = Leaf,
        onPrimary = Color.White,
        background = Mist,
        surface = Paper,
        onBackground = Ink,
        onSurface = Ink,
    )
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
}

@Composable
fun MagicScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("magic", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val logScroll = rememberScrollState()

    var url by rememberSaveable { mutableStateOf(prefs.getString("url", "")!!) }
    var path by rememberSaveable {
        mutableStateOf(prefs.getString("path", "/storage/emulated/0/Git/repo")!!)
    }
    var name by rememberSaveable { mutableStateOf(prefs.getString("name", "")!!) }
    var email by rememberSaveable { mutableStateOf(prefs.getString("email", "")!!) }
    var keyPath by rememberSaveable { mutableStateOf(prefs.getString("keyPath", "")!!) }
    var publicKey by rememberSaveable { mutableStateOf(prefs.getString("publicKey", "")!!) }
    var passphrase by rememberSaveable { mutableStateOf(prefs.getString("passphrase", "")!!) }
    var message by rememberSaveable { mutableStateOf(prefs.getString("message", "")!!) }
    val journal = remember {
        Journal(prefs.getString("log", "")!!)
    }
    var busy by remember { mutableStateOf(false) }
    var storageOk by remember { mutableStateOf(context.storageAccessGranted()) }

    LaunchedEffect(url, path, name, email, keyPath, publicKey, passphrase, message) {
        prefs.edit()
            .putString("url", url)
            .putString("path", path)
            .putString("name", name)
            .putString("email", email)
            .putString("keyPath", keyPath)
            .putString("publicKey", publicKey)
            .putString("passphrase", passphrase)
            .putString("message", message)
            .apply()
    }
    LaunchedEffect(journal.text) {
        prefs.edit().putString("log", journal.text.takeLast(4000)).apply()
        logScroll.animateScrollTo(logScroll.maxValue)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageOk = context.storageAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolved = primaryStoragePath(uri)
        if (resolved == null) {
            journal.add("Эту папку нельзя превратить в путь. Впиши его вручную.")
        } else {
            path = resolved
        }
    }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val line = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw GitOpException("Файл ключа не прочитался")
                    val stored = SshKeys.import(
                        bytes = bytes,
                        directory = File(context.filesDir, "ssh"),
                        passphrase = passphrase,
                        comment = email.ifBlank { "magic@phone" },
                    )
                    stored
                }
            }
            line.onSuccess { stored ->
                keyPath = stored.privateKey.absolutePath
                publicKey = stored.publicKey
                journal.add("Ключ сохранён в приложении")
            }.onFailure { error ->
                journal.add(error.explain())
            }
        }
    }
    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageOk = granted || context.storageAccessGranted()
    }

    fun runGit(title: String, block: (ProgressMonitor) -> String) {
        if (busy) return
        if (!context.ownsPath(path) && !context.storageAccessGranted()) {
            journal.add("Нет доступа к этой папке. Разреши запись или возьми папку приложения.")
            context.askStorageAccess { askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            return
        }
        busy = true
        journal.add("— $title")
        val monitor = LineMonitor { line ->
            scope.launch { journal.add(line) }
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { block(monitor) }
            }
            journal.add(outcome.fold(onSuccess = { it }, onFailure = { it.explain() }))
            busy = false
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("MAGIC", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Стянуть в папку, закоммитить, запушить, запуллить.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )

            if (!storageOk && !context.ownsPath(path)) {
                OutlinedButton(
                    onClick = { context.askStorageAccess { askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Разрешить запись в выбранную папку")
                }
            }

            Field("Адрес", url, { url = it }, "git@сервер:путь/репо.git")
            Field("Папка", path, { path = it }, "/storage/emulated/0/Git/repo")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pickDir.launch(null) }) { Text("Выбрать папку") }
                TextButton(onClick = {
                    val base = context.getExternalFilesDir(null) ?: context.filesDir
                    path = File(base, "repo").absolutePath
                }) { Text("Папка приложения") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Имя", name, { name = it }, "Имя", Modifier.weight(1f))
                Field("Почта", email, { email = it }, "you@mail", Modifier.weight(1f))
            }

            Text("Приватный ключ", style = MaterialTheme.typography.labelLarge)
            if (keyPath.isNotBlank()) {
                Text(keyPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text("Выбрать")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val created = withContext(Dispatchers.IO) {
                                runCatching {
                                    SshKeys.generate(File(context.filesDir, "ssh"), email.ifBlank { "magic@phone" })
                                }
                            }
                            created.onSuccess { stored ->
                                keyPath = stored.privateKey.absolutePath
                                publicKey = stored.publicKey
                                journal.add("Новый ключ создан. Публичную часть добавь на сервер.")
                            }.onFailure { journal.add(it.explain()) }
                        }
                    },
                    enabled = !busy,
                ) { Text("Создать") }
            }
            Field(
                "Пароль ключа",
                passphrase,
                { passphrase = it },
                "если ключ зашифрован",
                password = true,
            )
            if (publicKey.isNotBlank()) {
                SelectionContainer {
                    Text(publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("ssh public key", publicKey))
                    journal.add("Публичный ключ скопирован")
                }) { Text("Скопировать публичный ключ") }
            }
            Text(
                "Ключ сервера не проверяется: телефон подключается без вопроса «доверяешь ли хосту».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Field("Сообщение коммита", message, { message = it }, "что изменилось", singleLine = false)

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        runGit("Стянуть") { monitor ->
                            GitOps.installKey(File(keyPath), passphrase)
                            GitOps.clone(url, File(path), monitor)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Стянуть") }
                OutlinedButton(
                    onClick = {
                        runGit("Пулл") { monitor ->
                            GitOps.installKey(File(keyPath), passphrase)
                            GitOps.pull(File(path), name, email, monitor)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Пулл") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        runGit("Коммит") { _ -> GitOps.commit(File(path), message, name, email) }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Коммит") }
                OutlinedButton(
                    onClick = {
                        runGit("Пуш") { monitor ->
                            GitOps.installKey(File(keyPath), passphrase)
                            GitOps.push(File(path), monitor)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Пуш") }
            }

            Text("Журнал", style = MaterialTheme.typography.labelLarge)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 280.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                SelectionContainer {
                    Text(
                        text = if (journal.text.isBlank()) "Здесь будут ответы Git." else journal.text,
                        modifier = Modifier
                            .verticalScroll(logScroll)
                            .padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
    )
}

private class LineMonitor(private val onLine: (String) -> Unit) : ProgressMonitor {
    private var last = ""

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(title: String?, totalWork: Int) {
        val text = title?.trim().orEmpty()
        if (text.isEmpty() || text == last) return
        last = text
        onLine(text)
    }

    override fun update(completed: Int) = Unit
    override fun endTask() = Unit
    override fun isCancelled(): Boolean = false
    override fun showDuration(enabled: Boolean) = Unit
}

private class Journal(initial: String) {
    var text by mutableStateOf(initial)
        private set

    fun add(line: String) {
        text = appendLog(text, line)
    }
}

private fun appendLog(current: String, line: String): String {
    val stamp = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalTime.now())
    val chunk = line.lineSequence().filter { it.isNotBlank() }.joinToString("\n") { "[$stamp] $it" }
    return (if (current.isBlank()) chunk else "$current\n$chunk").takeLast(6000)
}

private fun primaryStoragePath(uri: Uri): String? {
    val doc = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Exception) {
        return null
    }
    val parts = doc.split(":", limit = 2)
    if (parts.size != 2 || !parts[0].equals("primary", ignoreCase = true)) return null
    val rest = parts[1].trim('/')
    return if (rest.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$rest"
}

private fun Context.storageAccessGranted(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.ownsPath(path: String): Boolean {
    val target = File(path).absolutePath.trimEnd('/') + "/"
    val roots = listOfNotNull(filesDir, getExternalFilesDir(null)).map { it.absolutePath.trimEnd('/') + "/" }
    return roots.any { target.startsWith(it) }
}

private fun Context.askStorageAccess(askLegacy: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    } else {
        askLegacy()
    }
}
