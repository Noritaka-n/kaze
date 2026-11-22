package app.kaze

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import app.kaze.ui.KazeApp
import app.kaze.ui.theme.KazeTheme

private var lockChannel: java.nio.channels.FileChannel? = null
private var lock: java.nio.channels.FileLock? = null

private fun runCli(args: Array<String>): Boolean {
    val command = args.firstOrNull() ?: return false
    if (command == "--key") {
        val text = args.getOrNull(1) ?: run { println("usage: --key <text>"); return true }
        println(app.kaze.offline.TextKey.of(text))
        return true
    }
    if (command == "--relay") {
        val op = args.getOrNull(1) ?: run { println("usage: --relay <op> [json]"); return true }
        val payload = args.drop(2).joinToString(" ").ifBlank { "{}" }
        val result = kotlinx.coroutines.runBlocking {
            app.kaze.ipc.RelayState.connectAsync()
            kotlinx.coroutines.withTimeout(20_000) {
                while (app.kaze.ipc.RelayState.status !is app.kaze.ipc.RelayStatus.Online) {
                    kotlinx.coroutines.delay(150)
                }
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            var value: app.kaze.proto.InvokeResult? = null
            app.kaze.ipc.RelayState.invoke(op, payload) { reply ->
                value = reply
                latch.countDown()
            }
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            value
        }
        if (result == null) {
            println("relay timeout")
        } else {
            println(if (result.ok) "ok ${result.detail}" else "error ${result.detail}")
            println(result.dataJson)
        }
        return true
    }
    if (command == "--relay-file") {
        val op = args.getOrNull(1) ?: run { println("usage: --relay-file <op> <jsonFile>"); return true }
        val file = args.getOrNull(2) ?: run { println("usage: --relay-file <op> <jsonFile>"); return true }
        val payload = runCatching { java.io.File(file).readText(Charsets.UTF_8) }.getOrDefault("{}")
        val result = kotlinx.coroutines.runBlocking {
            app.kaze.ipc.RelayState.connectAsync()
            kotlinx.coroutines.withTimeout(20_000) {
                while (app.kaze.ipc.RelayState.status !is app.kaze.ipc.RelayStatus.Online) {
                    kotlinx.coroutines.delay(150)
                }
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            var value: app.kaze.proto.InvokeResult? = null
            app.kaze.ipc.RelayState.invoke(op, payload) { reply ->
                value = reply
                latch.countDown()
            }
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            value
        }
        if (result == null) println("relay timeout")
        else {
            println(if (result.ok) "ok ${result.detail}" else "error ${result.detail}")
            println(result.dataJson)
        }
        return true
    }
    if (command == "--translate-ui") {
        val gameId = args.getOrNull(1).orEmpty()
        val file = args.getOrNull(2) ?: run { println("usage: --translate-ui <gameId> <labelsFile>"); return true }
        val labels = runCatching { java.io.File(file).readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        val result = kotlinx.coroutines.runBlocking {
            app.kaze.translate.UiTranslation.translate(labels, gameId)
        }
        val json = com.google.gson.JsonObject()
        result.forEach { (source, target) -> json.addProperty(source, target) }
        println(com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(json))
        return true
    }
    if (command == "--scan-text") {
        val dir = java.io.File(args.getOrNull(1) ?: ".")
        val scan = app.kaze.offline.OfflineText.scan(dir)
        val result = scan.result
        println("route ${scan.route} entries ${result.entries.size} unique ${result.uniqueTexts} files ${result.files}")
        result.byKind.entries.sortedByDescending { it.value }.forEach { (kind, count) ->
            println("  $kind $count")
        }
        result.warnings.forEach { println("  ! $it") }
        return true
    }
    if (command == "--apply-text") {
        val dir = java.io.File(args.getOrNull(1) ?: ".")
        val table = args.getOrNull(2) ?: run { println("usage: --apply-text <gameDir> <table>"); return true }
        val map = app.kaze.offline.MvText.readTranslations(java.io.File(table))
        val report = app.kaze.offline.OfflineText.apply(dir, map)
        println("translations ${map.size} replaced ${report.replaced} files ${report.files}")
        report.skipped.forEach { println("  skipped: $it") }
        return true
    }
    if (command == "--restore-text") {
        val dir = java.io.File(args.getOrNull(1) ?: ".")
        if (app.kaze.translate.SettingsStore.current.readOnly) {
            println("read-only mode: enable write in Settings → Safety first")
            return true
        }
        val (count, ok) = app.kaze.offline.OfflineText.restore(dir)
        println(if (ok) "restored $count files" else "no backup found")
        return true
    }
    if (command == "--translate-text") {
        val dir = java.io.File(args.getOrNull(1) ?: ".")
        val out = args.getOrNull(2) ?: run { println("usage: --translate-text <gameDir> <outFile> [limit]"); return true }
        val limit = args.getOrNull(3)?.toIntOrNull() ?: 20
        val cliGameId = args.getOrNull(4) ?: ""
        val cliForce = args.getOrNull(5) == "force"
        val result = app.kaze.offline.OfflineText.scan(dir).result
        val picked = result.entries.distinctBy { it.text }.take(limit)
        println("picked ${picked.size} of ${result.entries.size}")
        val map = kotlinx.coroutines.runBlocking {
            app.kaze.offline.OfflineTranslator.run(picked, cliGameId, cliForce)
        }
        println("translated ${map.size}: ${app.kaze.offline.OfflineTranslator.message}")
        val table = com.google.gson.JsonObject()
        table.addProperty("format", "kaze-mvtext")
        table.addProperty("version", 1)
        table.add("entries", com.google.gson.JsonArray().apply {
            picked.forEach { entry ->
                add(com.google.gson.JsonObject().apply {
                    addProperty("locator", entry.locator)
                    addProperty("kind", entry.kind)
                    addProperty("source", entry.text)
                    addProperty("target", map[entry.text] ?: "")
                })
            }
        })
        java.io.File(out).writeText(
            com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(table),
            Charsets.UTF_8,
        )
        println("written $out" + (if (cliGameId.isBlank()) " (game=\"\"，运行时若按游戏分区则命中不到)" else " (game=$cliGameId)"))
        return true
    }
    if (command == "--dump-text") {
        val dir = java.io.File(args.getOrNull(1) ?: ".")
        val out = args.getOrNull(2) ?: run { println("usage: --dump-text <gameDir> <file>"); return true }
        val result = app.kaze.offline.OfflineText.scan(dir).result
        app.kaze.offline.MvText.exportTable(result, java.io.File(out))
        println("entries ${result.entries.size} -> $out")
        return true
    }
    if (command == "--launch") {
        app.kaze.data.LibraryStore.load()
        val id = args.getOrNull(1)
        val game = id?.let { wanted -> app.kaze.data.LibraryStore.games.firstOrNull { it.id == wanted } }
            ?: app.kaze.data.LibraryStore.games.firstOrNull()
        if (game == null) {
            println("no games in library")
            return true
        }
        val result = app.kaze.data.Runtime.launchGame(game) { println("[launch] $it") }
        println(result.fold(onSuccess = { "OK $it" }, onFailure = { "FAIL ${it.message}" }))
        return true
    }
    if (command !in setOf("--export-patch", "--import-patch")) return false
    val path = args.getOrNull(1) ?: run {
        println("usage: $command <file>")
        return true
    }
    app.kaze.translate.TranslationMemory.open()
    if (!app.kaze.translate.TranslationMemory.available) {
        println("memory unavailable: ${app.kaze.translate.TranslationMemory.lastError}")
        return true
    }
    val target = java.nio.file.Path.of(path)
    when (command) {
        "--export-patch" -> println("exported ${app.kaze.translate.TranslationMemory.exportPatch(target)} entries -> $path")
        else -> {
            val (imported, skipped) = app.kaze.translate.TranslationMemory.importPatch(target)
            println("imported $imported, skipped $skipped")
        }
    }
    return true
}

fun main(args: Array<String>) {
    runCatching {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        val dir = java.io.File(base, "Kaze/tmp").apply { mkdirs() }
        System.setProperty("java.io.tmpdir", dir.absolutePath)
    }
    if (runCli(args)) return

    runCatching {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        val lockFile = java.io.File(base, "Kaze/kaze.lock")
        lockFile.parentFile?.mkdirs()
        lockChannel = java.nio.channels.FileChannel.open(
            lockFile.toPath(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        )
        lock = lockChannel?.tryLock()
    }
    if (lock == null) {
        app.kaze.ui.WindowCorners.focusExisting("Kaze")
        return
    }

    application {
        var dark by remember { mutableStateOf(app.kaze.translate.SettingsStore.current.dark) }
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        val width = (screen.width - 72).coerceAtMost(1280).coerceAtLeast(1024)
        val height = (screen.height - 96).coerceAtMost(832).coerceAtLeast(640)
        val state = rememberWindowState(
            size = DpSize(width.dp, height.dp),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = {
                runCatching { lock?.release() }
                runCatching { lockChannel?.close() }
                exitApplication()
                kotlin.system.exitProcess(0)
            },
            state = state,
            undecorated = true,
            title = "Kaze",
        ) {
            window.minimumSize = java.awt.Dimension(1024, 640)
            LaunchedEffect(Unit) {
                delay(180)
                window.extendedState = java.awt.Frame.NORMAL
                val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
                window.setLocation(
                    ((screen.width - window.width) / 2).coerceAtLeast(0),
                    ((screen.height - window.height) / 2).coerceAtLeast(0),
                )
                delay(120)
                app.kaze.ui.WindowCorners.attach(window)
            }
            KazeTheme(dark = dark) {
                KazeApp(window = window, dark = dark, onDarkChange = {
                    dark = it
                    app.kaze.translate.SettingsStore.save(
                        app.kaze.translate.SettingsStore.current.copy(dark = it),
                    )
                })
            }
        }
    }
}
