package app.kaze.translate

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager







object TranslationMemory {
    private val dbFile: Path = run {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        Paths.get(base, "Kaze", "translation.db")
    }

    private var connection: Connection? = null
    private val lock = Any()

    var available: Boolean = false
        private set

    var count: Int = 0
        private set

    var lastError: String? = null
        private set

    fun path(): String = dbFile.toString()

    fun open() {
        if (connection != null) return
        synchronized(lock) {
            try {
                Files.createDirectories(dbFile.parent)
                
                if (Files.exists(dbFile)) {
                    val backup = dbFile.resolveSibling("translation.db.bak")
                    runCatching { Files.copy(dbFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                }
                Class.forName("org.sqlite.JDBC")
                val conn = DriverManager.getConnection("jdbc:sqlite:$dbFile")
                conn.createStatement().use { statement ->
                    migrateToGameDimension(statement)
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS memory (
                            game       TEXT NOT NULL DEFAULT '',
                            key        TEXT NOT NULL,
                            source     TEXT NOT NULL,
                            target     TEXT NOT NULL,
                            provider   TEXT,
                            model      TEXT,
                            norm       TEXT,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY (game, key)
                        )
                        """.trimIndent()
                    )
                    runCatching {
                        statement.executeUpdate("ALTER TABLE memory ADD COLUMN norm TEXT")
                    }
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS ix_memory_updated ON memory(updated_at)")
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS ix_memory_norm ON memory(game, norm)")
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS glossary (
                            game   TEXT NOT NULL DEFAULT '',
                            term   TEXT NOT NULL,
                            target TEXT NOT NULL,
                            PRIMARY KEY (game, term)
                        )
                        """.trimIndent()
                    )
                }
                connection = conn
                available = true
                backfillNorm(conn)
                refreshCount()
            } catch (t: Throwable) {
                available = false
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    
    private fun migrateToGameDimension(statement: java.sql.Statement) {
        fun hasTable(name: String): Boolean {
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'").use { rs ->
                return rs.next()
            }
        }
        fun hasGameColumn(name: String): Boolean {
            statement.executeQuery("PRAGMA table_info($name)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name") == "game") return true
                }
            }
            return false
        }

        if (hasTable("memory") && !hasGameColumn("memory")) {
            statement.executeUpdate("ALTER TABLE memory RENAME TO memory_legacy")
            statement.executeUpdate(
                """
                CREATE TABLE memory (
                    game       TEXT NOT NULL DEFAULT '',
                    key        TEXT NOT NULL,
                    source     TEXT NOT NULL,
                    target     TEXT NOT NULL,
                    provider   TEXT,
                    model      TEXT,
                    norm       TEXT,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (game, key)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO memory(game, key, source, target, provider, model, norm, updated_at)
                SELECT '', key, source, target, provider, model, norm, updated_at FROM memory_legacy
                """.trimIndent()
            )
            statement.executeUpdate("DROP TABLE memory_legacy")
        }

        if (hasTable("glossary") && !hasGameColumn("glossary")) {
            statement.executeUpdate("ALTER TABLE glossary RENAME TO glossary_legacy")
            statement.executeUpdate(
                """
                CREATE TABLE glossary (
                    game   TEXT NOT NULL DEFAULT '',
                    term   TEXT NOT NULL,
                    target TEXT NOT NULL,
                    PRIMARY KEY (game, term)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                "INSERT INTO glossary(game, term, target) SELECT '', term, target FROM glossary_legacy"
            )
            statement.executeUpdate("DROP TABLE glossary_legacy")
        }
    }

    private fun backfillNorm(conn: Connection) {
        runCatching {
            val pending = ArrayList<Pair<String, String>>()
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT key, source FROM memory WHERE norm IS NULL").use { rs ->
                    while (rs.next()) pending += rs.getString(1) to rs.getString(2)
                }
            }
            if (pending.isEmpty()) return

            conn.prepareStatement("UPDATE memory SET norm = ? WHERE key = ?").use { statement ->
                pending.forEach { (key, source) ->
                    statement.setString(1, normalize(source))
                    statement.setString(2, key)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\s\\p{P}\\p{S}]"), "")

    private fun refreshCount() {
        val conn = connection ?: return
        runCatching {
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM memory").use { rs ->
                    if (rs.next()) count = rs.getInt(1)
                }
            }
        }
    }

    

    fun lookupAll(keys: List<String>, game: String = ""): Map<String, String> {
        val conn = connection ?: return emptyMap()
        if (keys.isEmpty()) return emptyMap()

        synchronized(lock) {
            return runCatching {
                val placeholders = keys.joinToString(",") { "?" }
                val out = HashMap<String, String>()
                
                
                conn.prepareStatement("SELECT key, target FROM memory WHERE game = ? AND key IN ($placeholders)").use { statement ->
                    statement.setString(1, game)
                    keys.forEachIndexed { index, key -> statement.setString(index + 2, key) }
                    statement.executeQuery().use { rs ->
                        while (rs.next()) out[rs.getString(1)] = rs.getString(2)
                    }
                }
                val missing = keys.filter { it !in out }
                if (missing.isNotEmpty() && game != "") {
                    val placeholders2 = missing.joinToString(",") { "?" }
                    conn.prepareStatement("SELECT key, target FROM memory WHERE game = '' AND key IN ($placeholders2)").use { statement ->
                        missing.forEachIndexed { index, key -> statement.setString(index + 1, key) }
                        statement.executeQuery().use { rs ->
                            while (rs.next()) out.putIfAbsent(rs.getString(1), rs.getString(2))
                        }
                    }
                }
                out
            }.getOrDefault(emptyMap())
        }
    }

    
    fun lookupBySource(sources: List<String>, game: String = ""): Map<String, String> {
        val conn = connection ?: return emptyMap()
        if (sources.isEmpty()) return emptyMap()

        val norms = sources.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
        if (norms.isEmpty()) return emptyMap()

        synchronized(lock) {
            return runCatching {
                val placeholders = norms.joinToString(",") { "?" }
                conn.prepareStatement("SELECT norm, target FROM memory WHERE game = ? AND norm IN ($placeholders)").use { statement ->
                    statement.setString(1, game)
                    norms.forEachIndexed { index, norm -> statement.setString(index + 2, norm) }
                    statement.executeQuery().use { rs ->
                        val out = HashMap<String, String>()
                        while (rs.next()) out[rs.getString(1)] = rs.getString(2)
                        out
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }

    

    fun store(key: String, source: String, target: String, provider: String, model: String, game: String = "") {
        val conn = connection ?: return
        synchronized(lock) {
            runCatching {
                conn.prepareStatement(
                    """
                    INSERT INTO memory(game, key, source, target, provider, model, norm, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(game, key) DO UPDATE SET
                        target = excluded.target,
                        provider = excluded.provider,
                        model = excluded.model,
                        norm = excluded.norm,
                        updated_at = excluded.updated_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, game)
                    statement.setString(2, key)
                    statement.setString(3, source)
                    statement.setString(4, target)
                    statement.setString(5, provider)
                    statement.setString(6, model)
                    statement.setString(7, normalize(source))
                    statement.setLong(8, System.currentTimeMillis())
                    statement.executeUpdate()
                }
                refreshCount()
            }
        }
    }

    fun clear(game: String = "") {
        val conn = connection ?: return
        synchronized(lock) {
            runCatching {
                conn.prepareStatement("DELETE FROM memory WHERE game = ?").use { statement ->
                    statement.setString(1, game)
                    statement.executeUpdate()
                }
                refreshCount()
            }
        }
    }

    

    fun glossary(game: String = ""): Map<String, String> {
        val conn = connection ?: return emptyMap()
        synchronized(lock) {
            return runCatching {
                conn.prepareStatement("SELECT term, target FROM glossary WHERE game = ? ORDER BY term").use { statement ->
                    statement.setString(1, game)
                    statement.executeQuery().use { rs ->
                        val out = LinkedHashMap<String, String>()
                        while (rs.next()) out[rs.getString(1)] = rs.getString(2)
                        out
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }

    fun glossaryAsText(game: String = ""): String = glossary(game).entries.joinToString("\n") { "${it.key}=${it.value}" }

    
    fun replaceGlossaryFromText(text: String, game: String = ""): Int {
        val conn = connection ?: return 0
        val entries = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .map { line ->
                val index = line.indexOf('=')
                line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toList()

        synchronized(lock) {
            runCatching {
                conn.autoCommit = false
                conn.prepareStatement("DELETE FROM glossary WHERE game = ?").use { statement ->
                    statement.setString(1, game)
                    statement.executeUpdate()
                }
                conn.prepareStatement("INSERT INTO glossary(game, term, target) VALUES(?, ?, ?)").use { statement ->
                    entries.forEach { (term, target) ->
                        statement.setString(1, game)
                        statement.setString(2, term)
                        statement.setString(3, target)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                conn.commit()
                conn.autoCommit = true
            }.onFailure {
                runCatching { conn.rollback(); conn.autoCommit = true }
            }
        }
        return entries.size
    }

    

    
    fun exportPatch(path: Path, game: String? = null): Int {
        val conn = connection ?: return 0
        val entries = ArrayList<com.google.gson.JsonObject>()
        synchronized(lock) {
            runCatching {
                val sql = if (game == null) {
                    "SELECT key, source, target, provider, model FROM memory ORDER BY updated_at"
                } else {
                    "SELECT key, source, target, provider, model FROM memory WHERE game = ? ORDER BY updated_at"
                }
                conn.prepareStatement(sql).use { statement ->
                    if (game != null) statement.setString(1, game)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) {
                            entries += com.google.gson.JsonObject().apply {
                                addProperty("key", rs.getString(1))
                                addProperty("source", rs.getString(2))
                                addProperty("target", rs.getString(3))
                                addProperty("provider", rs.getString(4) ?: "")
                                addProperty("model", rs.getString(5) ?: "")
                            }
                        }
                    }
                }
            }
        }
        val root = com.google.gson.JsonObject().apply {
            addProperty("format", "kaze-tm")
            addProperty("version", 1)
            addProperty("exportedAt", java.time.Instant.now().toString())
            addProperty("count", entries.size)
            add("entries", com.google.gson.JsonArray().apply { entries.forEach { add(it) } })
        }
        Files.writeString(path, com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root))
        return entries.size
    }

    
    fun importPatch(path: Path): Pair<Int, Int> {
        val conn = connection ?: return 0 to 0
        val text = Files.readString(path)
        val root = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return 0 to 0
        if (root.get("format")?.asString != "kaze-tm") return 0 to 0
        val entries = root.getAsJsonArray("entries") ?: return 0 to 0

        var imported = 0
        var skipped = 0
        conn.prepareStatement(
            """
            INSERT INTO memory(game, key, source, target, provider, model, norm, updated_at)
            VALUES('', ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(game, key) DO UPDATE SET
                target = excluded.target, norm = excluded.norm, updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            entries.forEach { element ->
                val item = runCatching { element.asJsonObject }.getOrNull()
                val key = item?.get("key")?.asString
                val source = item?.get("source")?.asString
                val target = item?.get("target")?.asString
                if (key.isNullOrBlank() || source.isNullOrBlank() || target.isNullOrBlank()) {
                    skipped++
                    return@forEach
                }
                runCatching {
                    statement.setString(1, key)
                    statement.setString(2, source)
                    statement.setString(3, target)
                    statement.setString(4, item.get("provider")?.asString ?: "patch")
                    statement.setString(5, item.get("model")?.asString ?: "patch")
                    statement.setString(6, normalize(source))
                    statement.setLong(7, System.currentTimeMillis())
                    statement.executeUpdate()
                    imported++
                }.onFailure { skipped++ }
            }
        }
        refreshCount()
        return imported to skipped
    }

    

    fun exportCsv(path: Path): Int {
        val conn = connection ?: return 0
        val rows = ArrayList<String>()
        rows += "key,source,target,provider,model,updated_at"
        synchronized(lock) {
            runCatching {
                conn.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT key, source, target, provider, model, updated_at FROM memory ORDER BY updated_at"
                    ).use { rs ->
                        while (rs.next()) {
                            rows += listOf(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4) ?: "",
                                rs.getString(5) ?: "",
                                rs.getLong(6).toString(),
                            ).joinToString(",") { csvEscape(it) }
                        }
                    }
                }
            }
        }
        Files.writeString(path, "\uFEFF" + rows.joinToString("\r\n"), Charsets.UTF_8)
        return rows.size - 1
    }

    fun importCsv(path: Path): Pair<Int, Int> {
        val conn = connection ?: return 0 to 0
        val lines = Files.readAllLines(path, Charsets.UTF_8)
        var imported = 0
        var skipped = 0

        conn.prepareStatement(
            """
            INSERT INTO memory(game, key, source, target, provider, model, norm, updated_at)
            VALUES('', ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(game, key) DO UPDATE SET
                target = excluded.target, norm = excluded.norm, updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            lines.drop(1).forEach { line ->
                val cells = parseCsvLine(line)
                if (cells.size < 3 || cells[1].isBlank() || cells[2].isBlank()) {
                    skipped++
                    return@forEach
                }
                runCatching {
                    statement.setString(1, cells[0])
                    statement.setString(2, cells[1])
                    statement.setString(3, cells[2])
                    statement.setString(4, cells.getOrNull(3) ?: "")
                    statement.setString(5, cells.getOrNull(4) ?: "")
                    statement.setString(6, normalize(cells[1]))
                    statement.setLong(7, System.currentTimeMillis())
                    statement.executeUpdate()
                    imported++
                }.onFailure { skipped++ }
            }
        }
        refreshCount()
        return imported to skipped
    }

    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out += current.toString(); current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        out += current.toString()
        return out
    }
}
