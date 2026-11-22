package app.kaze.data

import java.io.File
import java.util.concurrent.TimeUnit





object Runtime {
    private const val CDP_PORT = 9222
    private const val PLUGIN_PORT = 9223

    private val repoRoot: File? by lazy { findRepoRoot() }

    
    private val appHome: File? by lazy {
        runCatching {
            val jar = File(Runtime::class.java.protectionDomain.codeSource.location.toURI())
            jar.parentFile?.parentFile
        }.getOrNull()
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = dir ?: return null
            if (File(candidate, "adapters").isDirectory || File(candidate, "proto").isDirectory) {
                return candidate
            }
            dir = candidate.parentFile
        }
        return null
    }

    private fun adapterRoot(): File? {
        repoRoot?.let { File(it, "adapters") }?.takeIf { it.isDirectory }?.let { return it }
        
        File(System.getProperty("user.dir"), "adapters").takeIf { it.isDirectory }?.let { return it }
        appHome?.let { File(it, "adapters") }?.takeIf { it.isDirectory }?.let { return it }
        File(System.getProperty("user.dir"), "resources/adapters").takeIf { it.isDirectory }?.let { return it }
        return null
    }

    private fun pythonExe(): String {
        System.getenv("KAZE_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
        
        appHome?.let { File(it, "python/python.exe") }?.takeIf { it.exists() }?.let { return it.absolutePath }
        repoRoot?.let { File(it, "runtime/python/python.exe") }?.takeIf { it.exists() }?.let { return it.absolutePath }
        val candidates = listOf(
            File(repoRoot ?: return "python", "../python/venv-re/Scripts/python.exe"),
            File(repoRoot ?: return "python", "python/venv-re/Scripts/python.exe"),
        )
        candidates.firstOrNull { it.exists() }?.let { return it.absolutePath }
        return "python"
    }

    private fun relayExe(): File? {
        val candidates = listOfNotNull(
            System.getenv("KAZE_RELAY")?.let { File(it) },
            repoRoot?.let { File(it, "src/relay-cpp/build/kaze-relay.exe") },
            File(System.getProperty("user.dir"), "runtime/kaze-relay.exe").takeIf { it.exists() },
            appHome?.let { File(it, "kaze-relay.exe") }?.takeIf { it.exists() },
            File(System.getProperty("user.dir"), "kaze-relay.exe").takeIf { it.exists() },
        )
        return candidates.firstOrNull { it.exists() } ?: extractRelay()
    }

    



    private fun extractRelay(): File? {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?.let { File(it, "Kaze/runtime") }
            ?: File(System.getProperty("java.io.tmpdir"), "Kaze/runtime")
        val target = File(root, "kaze-relay.exe")
        if (target.isFile && target.length() > 0) return target
        val resource = Runtime::class.java.getResourceAsStream("/kaze-relay-runtime.zip") ?: return null
        return runCatching {
            root.mkdirs()
            java.util.zip.ZipInputStream(resource).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(root, entry.name).canonicalFile
                    if (!out.path.startsWith(root.canonicalPath + File.separator)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            target.setExecutable(true)
            target.takeIf { it.isFile && it.length() > 0 }
        }.getOrNull()
    }

    fun ensureRelay(log: (String) -> Unit = {}): Boolean {
        val session = File(System.getenv("LOCALAPPDATA") ?: "", "Kaze/session/relay.json")
        val relayAlive = runCatching {
            ProcessHandle.allProcesses().anyMatch { handle ->
                val command = handle.info().command().orElse("")
                command.endsWith("kaze-relay.exe", ignoreCase = true) && handle.isAlive
            }
        }.getOrDefault(false)
        if (session.exists() && relayAlive) {
            log("relay 已在运行")
            return true
        }
        if (session.exists() && !relayAlive) {
            runCatching { session.delete() }
            log("清理过期 relay 会话")
        }
        val exe = relayExe() ?: run {
            log("找不到 kaze-relay.exe")
            return false
        }
        runCatching {
            ProcessBuilder(exe.absolutePath)
                .directory(exe.parentFile)
                .redirectErrorStream(true)
                .start()
        }.onFailure {
            log("启动 relay 失败：${it.message}")
            return false
        }
        repeat(20) {
            if (session.exists()) {
                log("relay 已启动")
                return true
            }
            TimeUnit.MILLISECONDS.sleep(100)
        }
        log("relay 启动超时")
        return false
    }

    private var supervisor: Thread? = null

    
    private fun startSupervisor(game: Game, exePath: String, log: (String) -> Unit) {
        synchronized(this) {
            if (supervisor?.isAlive == true) return
            
            
            val watchesPort = game.engine == Engine.RMMV || game.engine == Engine.RMMZ
            supervisor = Thread({
                while (true) {
                    Thread.sleep(5000)
                    val running = runCatching {
                        ProcessHandle.allProcesses().anyMatch { handle ->
                            handle.info().command().orElse("").equals(exePath, ignoreCase = true)
                        }
                    }.getOrDefault(false)
                    if (!running) break
                    if (watchesPort && !isPortOpen(PLUGIN_PORT)) {
                        log("适配器掉线，自动重启")
                        startAdapter(game, log)
                    }
                }
                if (game.engine == Engine.RMMV || game.engine == Engine.RMMZ) {
                    removeMvPlugin(File(game.path), log)
                }
                log("游戏已退出，监控结束")
            }, "kaze-adapter-supervisor").apply { isDaemon = true; start() }
        }
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    }.getOrDefault(false)

    
    fun startAdapter(game: Game, log: (String) -> Unit = {}): String {
        if ((game.engine == Engine.RMMV || game.engine == Engine.RMMZ) && isPortOpen(PLUGIN_PORT)) {
            log("适配器已在运行，复用")
            return "适配器已在运行"
        }
        val adapters = adapterRoot()
        if (adapters == null) {
            log("找不到 adapters 目录，跳过适配器")
            return "未启动适配器"
        }

        val script = when (game.engine) {
            Engine.RMMV, Engine.RMMZ -> File(adapters, "mvmz/mvmz_adapter.py")
            Engine.RGSS -> File(adapters, "memory/memory_adapter.py")
            Engine.OTHER -> return "不支持的引擎：仅支持 RPG Maker 系列"
        }
        if (!script.exists()) {
            log("适配器脚本缺失：${script.path}")
            return "适配器缺失"
        }

        val exeName = when (game.engine) {
            Engine.RMMV, Engine.RMMZ -> "kaze-mvmz-adapter.exe"
            Engine.RGSS -> "kaze-memory-adapter.exe"
            Engine.OTHER -> return "不支持的引擎：仅支持 RPG Maker 系列"
        }
        val packagedExe = exeName?.let { name ->
            listOfNotNull(
                appHome?.let { File(it, "runtime-bin/$name") },
                repoRoot?.let { File(it, "runtime/bin/$name") },
            ).firstOrNull { it.exists() }
        }

        val command = if (packagedExe != null) {
            mutableListOf(packagedExe.absolutePath)
        } else {
            mutableListOf(pythonExe(), "-u", script.absolutePath)
        }
        when (game.engine) {
            Engine.RMMV, Engine.RMMZ -> command += listOf("--port", CDP_PORT.toString(), "--plugin-only")
            Engine.RGSS -> {
                
                val launcherName = game.launcher.takeIf { it.isNotBlank() }?.let { File(it).name }
                    ?: File(game.path).listFiles { f -> f.isFile && f.extension.equals("exe", true) }
                        ?.firstOrNull()?.name.orEmpty()
                if (launcherName.isNotBlank()) command += listOf("--exe", launcherName)
            }
            Engine.OTHER -> return "不支持的引擎：仅支持 RPG Maker 系列"
        }
        log(if (packagedExe != null) "使用内置适配器 ${packagedExe.name}" else "使用脚本适配器 ${script.name}")
        return runCatching {
            ProcessBuilder(command)
                .directory(script.parentFile)
                .redirectErrorStream(true)
                .redirectOutput(File(script.parentFile, "adapter-run.log"))
                .start()
            log("已启动 ${game.engine.label} 适配器")
            "${game.engine.label} 适配器已启动"
        }.getOrElse {
            log("适配器启动失败：${it.message}")
            "适配器启动失败"
        }
    }

    
    
    private fun busyReason(game: Game): String? {
        
        
        val root = File(game.path).absolutePath
        val running = ProcessHandle.allProcesses().anyMatch { handle ->
            isUnder(root, handle.info().command().orElse(""))
        }
        if (running) return "该游戏已经在运行，请先关闭游戏窗口"

        return when (game.engine) {
            Engine.RMMV, Engine.RMMZ -> null
            Engine.RGSS ->
                if (ProcessHandle.allProcesses().anyMatch { handle ->
                        handle.info().command().orElse("").endsWith("kaze-memory-adapter.exe", true)
                    }
                ) "已有一个内存适配器在运行（另一个游戏），请先关闭它" else null
            Engine.OTHER -> "不支持的引擎：仅支持 RPG Maker 系列"
        }
    }

    
    internal fun isUnder(root: String, command: String): Boolean {
        if (command.isBlank()) return false
        val trimmed = root.trimEnd('\\', '/')
        return command.equals(trimmed, ignoreCase = true) ||
            command.startsWith(trimmed + File.separator, ignoreCase = true) ||
            command.startsWith("$trimmed/", ignoreCase = true)
    }

    fun launchGame(game: Game, log: (String) -> Unit = {}): Result<String> = runCatching {
        busyReason(game)?.let { reason -> throw IllegalStateException(reason) }
        val folder = File(game.path)
        if (!folder.isDirectory) throw IllegalStateException("游戏目录不存在：${game.path}")

        val exePath = game.launcher.ifBlank {
            folder.listFiles { f -> f.isFile && f.extension.equals("exe", true) }
                ?.firstOrNull { it.name.equals("game.exe", true) }
                ?.absolutePath
                ?: throw IllegalStateException("没有找到启动程序")
        }
        val exe = File(exePath)
        if (!exe.exists()) throw IllegalStateException("启动程序不存在：$exePath")

        ensureRelay(log)
        ActiveGame.set(game.id, game.title)

        val builder = ProcessBuilder()
        builder.directory(folder)
        when (game.engine) {
            Engine.RMMV, Engine.RMMZ -> {
                installMvPlugin(folder, log)
                builder.command(exe.absolutePath)
                log("已安装 Kaze 插件，按原样启动")
            }

            Engine.RGSS -> builder.command(exe.absolutePath)
            Engine.OTHER -> throw IllegalStateException("不支持的引擎：仅支持 RPG Maker 系列")
        }
        builder.redirectErrorStream(false)
        try {
            builder.start()
        } catch (error: java.io.IOException) {
            val text = error.message.orEmpty()
            if (text.contains("740") || text.contains("elevation", true)) {
                throw IllegalStateException("该游戏需要管理员权限：请用管理员身份运行 Kaze 再启动")
            }
            throw error
        }
        LibraryStore.touch(game.id)

        val adapter = startAdapter(game, log)
        startSupervisor(game, exe.absolutePath, log)

        "已启动「${game.title}」· $adapter"
    }

    fun pluginInstalled(gameDir: File): Boolean {
        val www = listOf(File(gameDir, "www"), gameDir).firstOrNull { File(it, "js").isDirectory } ?: return false
        return File(www, "js/plugins/KazeBridge.js").exists()
    }

    
    fun removeMvPlugin(gameDir: File, log: (String) -> Unit = {}): Boolean {
        val www = listOf(File(gameDir, "www"), gameDir).firstOrNull { File(it, "js").isDirectory } ?: return false
        val plugin = File(www, "js/plugins/KazeBridge.js")
        if (plugin.exists()) {
            plugin.delete()
            log("已删除 KazeBridge.js")
        }
        val pluginsJs = File(www, "js/plugins.js")
        val backup = File(www, "js/plugins.js.kaze-backup")
        
        
        val removed = pluginsJs.exists() && MvPluginFile.remove(pluginsJs, "KazeBridge")
        if (removed) {
            backup.delete()
            log("已从 plugins.js 移除条目")
        } else if (backup.exists()) {
            backup.copyTo(pluginsJs, overwrite = true)
            backup.delete()
            log("已从备份还原 plugins.js")
        } else if (pluginsJs.exists()) {
            log("plugins.js 结构异常，未能移除条目")
        }
        return true
    }

    



    private fun installMvPlugin(gameDir: File, log: (String) -> Unit) {
        val source = adapterRoot()?.let { File(it, "mvmz/kaze_plugin.js") } ?: return
        if (!source.exists()) {
            log("插件模板缺失：${source.path}")
            return
        }
        val wwwDir = listOf(File(gameDir, "www"), gameDir).firstOrNull { File(it, "js").isDirectory } ?: return
        val pluginDir = File(wwwDir, "js/plugins").apply { mkdirs() }
        val pluginFile = File(pluginDir, "KazeBridge.js")
        val body = source.readText(Charsets.UTF_8).replace("KAZE_PORT", PLUGIN_PORT.toString())
        if (!pluginFile.exists() || pluginFile.readText(Charsets.UTF_8) != body) {
            pluginFile.writeText(body, Charsets.UTF_8)
            log("已写入插件 ${pluginFile.path}")
        }

        val pluginsJs = File(wwwDir, "js/plugins.js")
        if (!pluginsJs.exists()) {
            log("没有 plugins.js，跳过注册")
            return
        }
        val text = pluginsJs.readText(Charsets.UTF_8)
        if (text.contains("KazeBridge")) {
            log("插件已注册")
            return
        }
        val backup = File(wwwDir, "js/plugins.js.kaze-backup")
        if (!backup.exists()) backup.writeText(text, Charsets.UTF_8)

        val entry = """{"name":"KazeBridge","status":true,"description":"Kaze runtime bridge","parameters":{}}"""
        val patched = MvPluginFile.insert(text, entry)
        if (patched == null) {
            log("plugins.js 结构异常，未修改")
            return
        }
        pluginsJs.writeText(patched, Charsets.UTF_8)
        log("已注册 KazeBridge 插件（备份 plugins.js.kaze-backup）")
    }

}
