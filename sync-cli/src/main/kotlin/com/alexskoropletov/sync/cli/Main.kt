package com.alexskoropletov.sync.cli

import com.alexskoropletov.sync.Crypto
import com.alexskoropletov.sync.HttpRemote
import com.alexskoropletov.sync.SyncEngine
import com.alexskoropletov.sync.VaultPaths
import com.alexskoropletov.sync.defaultStateFile
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

private const val HELP = """
magic-sync синхронизирует папку через ваш сервер.
Сервер хранит только шифр. Ключ остаётся на компьютере и телефоне.

  magic-sync keygen --out ФАЙЛ
  magic-sync token
  magic-sync serve --data ПАПКА [--token ТОКЕН] [--listen 127.0.0.1:8787]
  magic-sync sync --vault ПАПКА --server АДРЕС --token ТОКЕН --key ФАЙЛ
      [--device ИМЯ] [--state ФАЙЛ] [--allow-mass-delete]

Удаление одного файла доходит до других устройств.
Если пропадает целая папка, это не удаление: файлы скачиваются обратно.
Большое удаление тоже останавливается, пока нет --allow-mass-delete.
Адрес http:// годится только внутри Tailscale или WireGuard. Иначе нужен https.

Токен сервера можно передать переменной MAGIC_SYNC_TOKEN, без --token.
Сервер: java -jar magic-sync.jar serve --data ПАПКА --listen 127.0.0.1:8787
"""

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "keygen" -> keygen(args.drop(1))
            "token" -> println(Crypto.token())
            "serve" -> serve(args.drop(1))
            "sync" -> sync(args.drop(1))
            else -> {
                println(HELP.trim())
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        System.err.println(e.message ?: e.toString())
        exitProcess(1)
    }
}

private fun keygen(args: List<String>) {
    val flags = flags(args)
    val out = File(flags.values["--out"] ?: error("нужен --out"))
    val key = Crypto.keygen()
    Crypto.writeKey(out, key)
    try {
        Files.setPosixFilePermissions(
            out.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (_: UnsupportedOperationException) {
    }
    println("Ключ записан в ${out.path}")
    println("Отпечаток ${Crypto.fingerprint(key)}")
}

private fun serve(args: List<String>) {
    val flags = flags(args)
    val data = File(flags.values["--data"] ?: error("нужна --data"))
    val token = flags.values["--token"]?.takeIf { it.isNotBlank() }
        ?: System.getenv("MAGIC_SYNC_TOKEN")?.takeIf { it.isNotBlank() }
        ?: error("нужен --token или переменная MAGIC_SYNC_TOKEN")
    val listen = flags.values["--listen"]?.takeIf { it.isNotBlank() }
        ?: System.getenv("MAGIC_SYNC_LISTEN")?.takeIf { it.isNotBlank() }
        ?: "127.0.0.1:8787"
    val (host, port) = parseListen(listen)
    if (host == "0.0.0.0" || host == "::") {
        System.err.println("Сервер слушает все интерфейсы. Не открывайте этот порт в интернет без https.")
    }
    val http = BlobServer(data, token, host, port).start()
    Runtime.getRuntime().addShutdownHook(Thread { http.stop(1) })
    println("Слушаю ${http.address.hostString}:${http.address.port}, данные в ${data.canonicalPath}")
    Thread.currentThread().join()
}

private fun sync(args: List<String>) {
    val flags = flags(args)
    val vault = File(flags.values["--vault"] ?: error("нужна --vault"))
    val server = flags.values["--server"] ?: error("нужен --server")
    val token = flags.values["--token"] ?: error("нужен --token")
    val keyFile = File(flags.values["--key"] ?: error("нужен --key"))
    val device = flags.values["--device"] ?: hostname()
    val state = flags.values["--state"]?.let { File(it) }
        ?: defaultStateFile(File(System.getProperty("user.home"), ".config/magic-sync"), vault)
    if (server.startsWith("http://")) {
        System.err.println("Адрес без https. Токен виден сети, если это не Tailscale или WireGuard.")
    }
    val engine = SyncEngine(
        vault = vault,
        remote = HttpRemote(server, token),
        key = Crypto.readKey(keyFile),
        device = VaultPaths.sanitizeDevice(device),
        stateFile = state,
        allowMassDelete = "--allow-mass-delete" in flags.switches,
    )
    engine.sync().forEach(::println)
}

private data class Flags(val values: Map<String, String>, val switches: Set<String>)

private fun flags(args: List<String>): Flags {
    val values = linkedMapOf<String, String>()
    val switches = linkedSetOf<String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (!arg.startsWith("--")) error("не понял аргумент $arg")
        if (arg == "--allow-mass-delete") {
            switches += arg
            i++
            continue
        }
        val value = args.getOrNull(i + 1) ?: error("нет значения у $arg")
        if (value.startsWith("--")) error("нет значения у $arg")
        values[arg] = value
        i += 2
    }
    return Flags(values, switches)
}

private fun parseListen(value: String): Pair<String, Int> {
    val colon = value.lastIndexOf(':')
    require(colon > 0) { "адрес слушать так: 127.0.0.1:8787" }
    val host = value.substring(0, colon)
    val port = value.substring(colon + 1).toIntOrNull() ?: error("порт не число: $value")
    require(port in 1..65535) { "плохой порт: $port" }
    return host to port
}

private fun hostname(): String {
    val env = System.getenv("HOSTNAME") ?: System.getenv("COMPUTERNAME")
    if (!env.isNullOrBlank()) return env
    return try {
        InetAddress.getLocalHost().hostName ?: "computer"
    } catch (_: Exception) {
        "computer"
    }
}
