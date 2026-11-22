package app.kaze.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest










object LibraryStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    
    private val lock = Any()

    private val defaultStore: File = run {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        File(base, "Kaze/library.json")
    }

    
    internal var storeOverride: File? = null

    private fun storeFile(): File = storeOverride ?: defaultStore

    var games: List<Game> = emptyList()
        private set

    var lastError: String? = null
        private set

    init {
        load()
    }

    
    data class ScanSummary(
        val added: List<Game>,
        val alreadyKnown: Int,
        val failed: Int,
        val truncated: Boolean,
        val cancelled: Boolean = false,
    )

    fun load() {
        val file = storeFile()
        if (!file.isFile) {
            games = emptyList()
            lastError = null
            return
        }
        val parsed = parse(file)
        if (parsed != null) {
            val refreshed = parsed.map(::refreshCover)
            games = refreshed
            if (refreshed != parsed) save()
            mainLooksValid = true
            lastError = null
            return
        }
        val backup = File(file.parentFile, "${file.name}.bak")
        val restored = if (backup.isFile) parse(backup) else null
        if (restored != null) {
            val refreshed = restored.map(::refreshCover)
            games = refreshed
            if (refreshed != restored) save()
            
            
            val healed = runCatching { writeMain(file) }.isSuccess
            mainLooksValid = healed
            lastError = "library.json 无法解析，已从 ${backup.name} 恢复"
        } else {
            games = emptyList()
            lastError = "library.json 无法解析且没有可用备份；已按空库载入，原文件保留"
        }
    }

    private fun parse(file: File): List<Game>? = runCatching {
        val dto = gson.fromJson(file.readText(Charsets.UTF_8), LibraryDto::class.java)
        dto.games.orEmpty().map { it.toGame() }
    }.getOrNull()

    
    private var mainLooksValid = true

    



    private fun refreshCover(game: Game): Game {
        val normalized = game.cover.replace('\\', '/')
        if (normalized.isNotBlank() && !normalized.endsWith("/icon/icon.png") && !normalized.endsWith("/icon.png")) return game
        val launcher = game.launcher.takeIf { it.isNotBlank() }?.let(::File)
        val found = CoverFinder.find(File(game.path), game.engine, launcher)?.absolutePath
            ?: return game
        return if (found == game.cover) game else game.copy(cover = found)
    }

    private fun serialize(): String = gson.toJson(LibraryDto(games.map { EntryDto.from(it) }))

    private fun writeMain(file: File) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(serialize(), Charsets.UTF_8)
        runCatching {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun save(): Boolean {
        val file = storeFile()
        return runCatching {
            file.parentFile?.mkdirs()
            
            
            if (mainLooksValid && file.isFile) {
                runCatching {
                    file.copyTo(File(file.parentFile, "${file.name}.bak"), overwrite = true)
                }
            }
            writeMain(file)
            mainLooksValid = true
            lastError = null
            true
        }.getOrElse {
            lastError = "保存游戏库失败：${it.message}"
            false
        }
    }

    



    fun refreshCovers() {
        val updates = games.mapNotNull { game ->
            val current = game.cover.takeIf { it.isNotBlank() }?.let { File(it) }
            if (current != null && CoverSource.isUsable(current)) return@mapNotNull null
            val cover = CoverFinder.find(File(game.path), game.engine)?.absolutePath.orEmpty()
            if (cover == game.cover) null else game.id to cover
        }
        if (updates.isEmpty()) return
        val byId = updates.toMap()
        synchronized(lock) {
            games = games.map { game -> byId[game.id]?.let { game.copy(cover = it) } ?: game }
            save()
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            games = games.filterNot { it.id == id }
            save()
        }
    }

    
    fun touch(id: String) {
        synchronized(lock) {
            val index = games.indexOfFirst { it.id == id }
            if (index < 0) return
            val updated = games.toMutableList()
            updated[index] = updated[index].copy(lastPlayed = System.currentTimeMillis())
            games = updated
            save()
        }
    }

    
    fun addFolder(folder: File, launcherHint: File? = null): Result<Game> {
        if (!folder.isDirectory) return Result.failure(IllegalArgumentException("不是文件夹"))
        val id = entryId(folder)
        synchronized(lock) { games.firstOrNull { it.id == id } }?.let { return Result.success(it) }

        val game = buildGame(folder, launcherHint, id).getOrElse { return Result.failure(it) }
        synchronized(lock) {
            games.firstOrNull { it.id == id }?.let { return Result.success(it) }
            games = games + game
            save()
        }
        return Result.success(game)
    }

    private fun entryId(folder: File): String = hash(folder.absolutePath)

    
    private fun buildGame(folder: File, launcherHint: File?, id: String): Result<Game> {
        val engine = detectEngine(folder)
            ?: return Result.failure(
                IllegalArgumentException("没识别出 RPG Maker 引擎（仅支持 MZ / MV / XP / VX / VX Ace）"),
            )
        val title = readTitle(folder, engine) ?: folder.name
        val launcher = launcherHint?.takeIf { it.isFile } ?: detectLauncher(folder)
        return Result.success(
            Game(
                id = id,
                title = title,
                subtitle = folder.name,
                engine = engine,
                sourceLang = "自动",
                monogram = title.trim().take(1).ifBlank { "?" },
                path = folder.absolutePath,
                launcher = launcher?.absolutePath.orEmpty(),
                cover = CoverFinder.find(folder, engine, launcher)?.absolutePath.orEmpty(),
            ),
        )
    }

    




    fun scanFolder(
        root: File,
        onProgress: (visited: Int, found: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result<ScanSummary> = runCatching {
        if (!root.isDirectory) throw IllegalArgumentException("不是文件夹")
        val scan = LibraryScanner.scan(
            root,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
        if (scan.cancelled) {
            return@runCatching ScanSummary(emptyList(), 0, 0, false, cancelled = true)
        }

        val knownIds = synchronized(lock) { games.map { it.id }.toSet() }
        var known = 0
        var failed = 0
        val built = mutableListOf<Game>()
        scan.folders.forEach { folder ->
            val id = entryId(folder)
            if (id in knownIds || built.any { it.id == id }) {
                known++
                return@forEach
            }
            buildGame(folder, null, id).onSuccess { built += it }.onFailure { failed++ }
        }

        synchronized(lock) {
            val existing = games.map { it.id }.toSet()
            val fresh = built.filter { it.id !in existing }
            if (fresh.isNotEmpty()) {
                games = games + fresh
                save()
            }
        }
        ScanSummary(built, known, failed, scan.truncated)
    }

    private fun detectLauncher(folder: File): File? {
        val exes = folder.listFiles { file -> file.isFile && file.extension.equals("exe", true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (exes.isEmpty()) return null
        val preferred = listOf("game.exe", "nw.exe", "player.exe", "game32.exe")
        preferred.forEach { name ->
            exes.firstOrNull { it.name.equals(name, true) }?.let { return it }
        }
        return exes.firstOrNull { !it.name.contains("uninstall", true) && !it.name.contains("update", true) }
            ?: exes.first()
    }

    

    internal fun detectEngine(dir: File): Engine? {
        fun has(vararg path: String) = File(dir, path.joinToString("/")).exists()

        return when {
            has("js", "rmmz_core.js") || has("js", "rmmz_objects.js") ||
                has("www", "js", "rmmz_core.js") || has("www", "js", "rmmz_objects.js") -> Engine.RMMZ
            has("js", "rpg_core.js") || has("www", "js", "rpg_core.js") -> Engine.RMMV
            
            has("Game.rgss3a") || has("Game.rgss2a") || has("Game.rgssad") -> Engine.RGSS
            has("Data", "Scripts.rvdata2") || has("Data", "Scripts.rvdata") ||
                has("Data", "Scripts.rxdata") -> Engine.RGSS
            has("Data", "System.rvdata2") || has("Data", "System.rvdata") ||
                has("Data", "System.rxdata") -> Engine.RGSS
            has("Game.ini") && (has("Data") || has("data")) -> Engine.RGSS
            else -> null
        }
    }

    private fun readTitle(dir: File, engine: Engine): String? = runCatching {
        when (engine) {
            Engine.RMMV, Engine.RMMZ -> {
                val system = listOf(
                    File(dir, "data/System.json"),
                    File(dir, "www/data/System.json"),
                    File(dir, "data/system.json"),
                ).firstOrNull { it.exists() } ?: return null
                val json = gson.fromJson(system.readText(Charsets.UTF_8), Map::class.java)
                (json["gameTitle"] as? String)?.takeIf { it.isNotBlank() }
            }

            Engine.RGSS -> app.kaze.offline.RgssText.readTitle(dir)

            Engine.OTHER -> null
        }
    }.getOrNull()

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    

    private data class LibraryDto(val games: List<EntryDto>? = emptyList())

    private data class EntryDto(
        val id: String = "",
        val title: String = "",
        val subtitle: String = "",
        val engine: String = "OTHER",
        val sourceLang: String = "自动",
        val path: String = "",
        val launcher: String = "",
        val cover: String = "",
        val lastPlayed: Long = 0L,
    ) {
        fun toGame(): Game {
                return Game(
                id = id,
                title = title,
                subtitle = subtitle,
                engine = runCatching { Engine.valueOf(engine) }.getOrDefault(Engine.OTHER),
                sourceLang = sourceLang,
                monogram = title.trim().take(1).ifBlank { "?" },
                path = path,
                launcher = launcher,
                cover = cover,
                lastPlayed = lastPlayed,
            )
        }

        companion object {
            fun from(game: Game) = EntryDto(
                id = game.id,
                title = game.title,
                subtitle = game.subtitle,
                engine = game.engine.name,
                sourceLang = game.sourceLang,
                path = game.path,
                launcher = game.launcher,
                cover = game.cover,
                lastPlayed = game.lastPlayed,
            )
        }
    }
}
