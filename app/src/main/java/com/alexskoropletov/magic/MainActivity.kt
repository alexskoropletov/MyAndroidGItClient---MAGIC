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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alexskoropletov.sync.Crypto
import com.alexskoropletov.sync.HttpRemote
import com.alexskoropletov.sync.SyncEngine
import com.alexskoropletov.sync.VaultPaths
import com.alexskoropletov.sync.defaultStateFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MagicTheme {
                Surface(Modifier.fillMaxSize()) {
                    SyncScreen()
                }
            }
        }
    }
}

@Composable
private fun SyncScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("magic-sync", Context.MODE_PRIVATE) }
    val keyFile = remember { File(context.filesDir, "sync.key") }
    var server by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var device by remember { mutableStateOf(prefs.getString("device", "phone") ?: "phone") }
    var path by remember { mutableStateOf(prefs.getString("path", "") ?: "") }
    var fingerprint by remember {
        mutableStateOf(
            runCatching { if (keyFile.isFile) Crypto.fingerprint(Crypto.readKey(keyFile)) else "" }.getOrDefault(""),
        )
    }
    var allowMass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("Ключ один и тот же на телефоне и компьютере. На сервер его не отправляют.") }
    val scope = rememberCoroutineScope()

    fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolved = primaryStoragePath(uri)
        if (resolved == null) {
            log = "Не смог понять путь. Впишите его вручную, например /storage/emulated/0/Documents/vault"
        } else {
            path = resolved
            save("path", resolved)
        }
    }
    val keyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.decodeToString()
                ?: error("файл пустой")
            val key = Crypto.readKey(text)
            Crypto.writeKey(keyFile, key)
            fingerprint = Crypto.fingerprint(key)
            log = "Ключ записан. Отпечаток $fingerprint. На компьютере он должен совпасть."
        } catch (e: Exception) {
            log = e.message ?: "не прочитал ключ"
        }
    }
    val legacyPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("MAGIC", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Папка на этом телефоне и на компьютере. Сервер хранит только шифр и не видит названия заметок.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it; save("server", it) },
            label = { Text("Сервер") },
            placeholder = { Text("https://vps:8787") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Голый http только внутри Tailscale или WireGuard. Иначе токен виден сети.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; save("token", it) },
            label = { Text("Токен") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = device,
            onValueChange = { device = it; save("device", it) },
            label = { Text("Имя устройства") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it; save("path", it) },
            label = { Text("Папка заметок") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { askStorage(context, legacyPermissions) }) { Text("Доступ к файлам") }
            OutlinedButton(onClick = { treeLauncher.launch(null) }) { Text("Выбрать") }
        }
        OutlinedButton(
            onClick = {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                path = dir.absolutePath
                save("path", path)
            },
        ) { Text("Папка приложения") }
        Text(
            if (fingerprint.isEmpty()) "Ключа ещё нет." else "Отпечаток ключа $fingerprint",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fingerprint.isEmpty()) {
                Button(onClick = {
                    val key = Crypto.keygen()
                    Crypto.writeKey(keyFile, key)
                    fingerprint = Crypto.fingerprint(key)
                    log = "Новый ключ. Скопируйте его на компьютер. Отпечаток $fingerprint"
                }) { Text("Создать ключ") }
            } else {
                OutlinedButton(onClick = {
                    val clip = ClipData.newPlainText("magic-sync", keyFile.readText())
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    log = "Ключ скопирован. Вставьте его в файл на компьютере. Отпечаток $fingerprint"
                }) { Text("Скопировать ключ") }
            }
            OutlinedButton(onClick = { keyLauncher.launch(arrayOf("*/*")) }) { Text("Взять из файла") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allowMass, onCheckedChange = { allowMass = it })
            Text("Это удаление папки, а не сбой")
        }
        Button(
            onClick = {
                if (busy) return@Button
                val vaultPath = path.trim()
                if (server.isBlank() || token.isBlank() || vaultPath.isBlank()) {
                    log = "Нужны адрес сервера, токен и папка."
                    return@Button
                }
                if (!keyFile.isFile) {
                    log = "Нет ключа. Создайте его здесь или принесите с компьютера."
                    return@Button
                }
                if (!context.canRead(vaultPath)) {
                    log = "Нет доступа к этой папке. Нажмите «Доступ к файлам» или возьмите папку приложения."
                    return@Button
                }
                busy = true
                scope.launch {
                    try {
                        val lines = withContext(Dispatchers.IO) {
                            val key = Crypto.readKey(keyFile)
                            val vault = File(vaultPath)
                            SyncEngine(
                                vault = vault,
                                remote = HttpRemote(server.trim(), token.trim()),
                                key = key,
                                device = VaultPaths.sanitizeDevice(device),
                                stateFile = defaultStateFile(context.filesDir, vault),
                                allowMassDelete = allowMass,
                            ).sync()
                        }
                        log = lines.joinToString("\n")
                    } catch (e: Exception) {
                        log = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Синхронизирую…" else "Синхронизировать") }
        OutlinedButton(
            onClick = {
                val vault = File(path.trim())
                val state = defaultStateFile(context.filesDir, vault)
                val removed = state.delete()
                log = if (removed) {
                    "Забыл состояние этой папки. Удалённые раньше файлы могут вернуться."
                } else {
                    "Для этой папки ещё нечего забывать."
                }
            },
            enabled = !busy,
        ) { Text("Забыть состояние папки") }
        Text(log, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MagicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Color(0xFF8FCBAA),
                background = Color(0xFF121613),
                surface = Color(0xFF121613),
            )
        } else {
            lightColorScheme(primary = Color(0xFF1F6B4A))
        },
        content = content,
    )
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

private fun Context.storageGranted(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.canRead(path: String): Boolean {
    val file = File(path)
    val own = listOfNotNull(filesDir.absolutePath, getExternalFilesDir(null)?.absolutePath)
    if (own.any { file.absolutePath.startsWith(it) }) return true
    return storageGranted()
}

private fun askStorage(
    context: Context,
    legacy: androidx.activity.result.ActivityResultLauncher<Array<String>>,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    } else {
        legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
}
