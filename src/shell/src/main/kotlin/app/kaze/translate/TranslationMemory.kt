package app.kaze.translate

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager







object TranslationMemory {
    private val defaultDbFile: Path = run {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        Paths.get(base, "Kaze", "translation.db")
    }

    internal var dbFileOverride: Path? = null

    @Volatile
    private var connection: Connection? = null
    private val lock = Any()

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var count: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    private fun dbFile(): Path = dbFileOverride ?: defaultDbFile

    fun path(): String = dbFile().toString()

    fun open() {
        synchronized(lock) {
            if (connection != null) return
            available = false
            lastError = null
            var conn: Connection? = null
            try {
                val db = dbFile()
                Files.createDirectories(db.parent)
                Class.forName("org.sqlite.JDBC")

                var failure: Throwable? = null
                try {
                    conn = prepare(db)
                } catch (t: Throwable) {
                    failure = t
                }

                val backup = backupFile(db)
                if (conn == null && isCorruption(failure) && Files.isRegularFile(backup)) {
                    validateBackup(backup)
                    restoreBackup(backup, db)
                    conn = prepare(db)
                }
                if (conn == null) throw failure ?: IllegalStateException("无法打开翻译记忆库")

                connection = conn
                backfillNorm(conn)
                refreshCount()
                available = true
                runCatching { backupMain(db) }
            } catch (t: Throwable) {
                runCatching { conn?.close() }
                connection = null
                available = false
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    fun close() = synchronized(lock) {
        val conn = connection
        connection = null
        available = false
        runCatching { conn?.close() }
    }

    private fun backupFile(db: Path): Path = db.resolveSibling("${db.fileName}.bak")

    private fun isCorruption(error: Throwable?): Boolean = generateSequence(error) { it.cause }.any {
        val message = it.message.orEmpty().lowercase()
        "not a database" in message || "malformed" in message || "corrupt" in message
    }

    private fun prepare(db: Path): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$db")
        try {
            checkIntegrity(conn)
            conn.autoCommit = false
            try {
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
                conn.commit()
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                conn.autoCommit = true
            }
            return conn
        } catch (t: Throwable) {
            runCatching { conn.close() }
            throw t
        }
    }

    private fun checkIntegrity(conn: Connection) {
        conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA integrity_check").use { rs ->
                var valid = false
                while (rs.next()) {
                    if (rs.getString(1).equals("ok", ignoreCase = true)) valid = true
                    else throw IllegalStateException("SQLite 校验失败：${rs.getString(1)}")
                }
                if (!valid) throw IllegalStateException("SQLite 校验没有返回结果")
            }
        }
    }

    private fun validateBackup(backup: Path) {
        DriverManager.getConnection("jdbc:sqlite:$backup").use { conn ->
            checkIntegrity(conn)
            conn.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='memory'",
                ).use { rs ->
                    if (!rs.next()) throw IllegalStateException("备份中没有 memory 表")
                }
            }
        }
    }

    private fun replaceFile(temp: Path, target: Path) {
        try {
            Files.move(
                temp,
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restoreBackup(backup: Path, db: Path) {
        val temp = db.resolveSibling("${db.fileName}.restore.tmp")
        Files.copy(backup, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        replaceFile(temp, db)
    }

    private fun backupMain(db: Path) {
        val backup = backupFile(db)
        val temp = db.resolveSibling("${db.fileName}.bak.tmp")
        Files.copy(db, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        replaceFile(temp, backup)
    }

    
    private fun migrateToGameDimension(statement: java.sql.Statement) {
        fun hasTable(name: String): Boolean {
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'").use { rs ->
                return rs.next()
            }
        }
        fun hasColumn(table: String, column: String): Boolean {
            statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name") == column) return true
                }
            }
            return false
        }

        if (hasTable("memory") && !hasColumn("memory", "game")) {
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
            val norm = if (hasColumn("memory_legacy", "norm")) "norm" else "NULL"
            statement.executeUpdate(
                """
                INSERT INTO memory(game, key, source, target, provider, model, norm, updated_at)
                SELECT '', key, source, target, provider, model, $norm, updated_at FROM memory_legacy
                """.trimIndent()
            )
            statement.executeUpdate("DROP TABLE memory_legacy")
        }

        if (hasTable("glossary") && !hasColumn("glossary", "game")) {
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
            val pending = ArrayList<Triple<String, String, String>>()
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT game, key, source FROM memory WHERE norm IS NULL").use { rs ->
                    while (rs.next()) pending += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                }
            }
            if (pending.isEmpty()) return

            conn.prepareStatement("UPDATE memory SET norm = ? WHERE game = ? AND key = ?").use { statement ->
                pending.forEach { (game, key, source) ->
                    statement.setString(1, normalize(source))
                    statement.setString(2, game)
                    statement.setString(3, key)
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
                    "SELECT game, key, source, target, provider, model FROM memory ORDER BY updated_at"
                } else {
                    "SELECT game, key, source, target, provider, model FROM memory WHERE game = ? ORDER BY updated_at"
                }
                conn.prepareStatement(sql).use { statement ->
                    if (game != null) statement.setString(1, game)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) {
                            entries += com.google.gson.JsonObject().apply {
                                addProperty("game", rs.getString(1))
                                addProperty("key", rs.getString(2))
                                addProperty("source", rs.getString(3))
                                addProperty("target", rs.getString(4))
                                addProperty("provider", rs.getString(5) ?: "")
                                addProperty("model", rs.getString(6) ?: "")
                            }
                        }
                    }
                }
            }
        }
        val root = com.google.gson.JsonObject().apply {
            addProperty("format", "kaze-tm")
            addProperty("version", 2)
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
        if (runCatching { root.get("format")?.asString }.getOrNull() != "kaze-tm") return 0 to 0
        val entries = runCatching { root.getAsJsonArray("entries") }.getOrNull() ?: return 0 to 0

        fun value(item: com.google.gson.JsonObject, name: String): String? = runCatching {
            item.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

        synchronized(lock) {
            var imported = 0
            var skipped = 0
            conn.prepareStatement(
                """
                INSERT INTO memory(game, key, source, target, provider, model, norm, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(game, key) DO UPDATE SET
                    source = excluded.source,
                    target = excluded.target,
                    provider = excluded.provider,
                    model = excluded.model,
                    norm = excluded.norm,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                entries.forEach { element ->
                    val item = runCatching { element.asJsonObject }.getOrNull()
                    if (item == null) {
                        skipped++
                        return@forEach
                    }
                    val game = value(item, "game").orEmpty()
                    val key = value(item, "key")
                    val source = value(item, "source")
                    val target = value(item, "target")
                    if (key.isNullOrBlank() || source.isNullOrBlank() || target.isNullOrBlank()) {
                        skipped++
                        return@forEach
                    }
                    runCatching {
                        statement.setString(1, game)
                        statement.setString(2, key)
                        statement.setString(3, source)
                        statement.setString(4, target)
                        statement.setString(5, value(item, "provider") ?: "patch")
                        statement.setString(6, value(item, "model") ?: "patch")
                        statement.setString(7, normalize(source))
                        statement.setLong(8, System.currentTimeMillis())
                        statement.executeUpdate()
                        imported++
                    }.onFailure { skipped++ }
                }
            }
            refreshCount()
            return imported to skipped
        }
    }

    

    fun exportCsv(path: Path): Int {
        val conn = connection ?: return 0
        val rows = ArrayList<String>()
        rows += "game,key,source,target,provider,model,updated_at"
        synchronized(lock) {
            runCatching {
                conn.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT game, key, source, target, provider, model, updated_at FROM memory ORDER BY updated_at"
                    ).use { rs ->
                        while (rs.next()) {
                            rows += listOf(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5) ?: "",
                                rs.getString(6) ?: "",
                                rs.getLong(7).toString(),
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
        val records = runCatching { parseCsv(Files.readString(path, Charsets.UTF_8)) }.getOrNull()
            ?: return 0 to 0
        val rawHeader = records.firstOrNull() ?: return 0 to 0
        val header = rawHeader.mapIndexed { index, value ->
            if (index == 0) value.removePrefix("\uFEFF") else value
        }
        val columns = header.mapIndexed { index, name -> name to index }.toMap()
        val keyColumn = columns["key"] ?: 0
        val sourceColumn = columns["source"] ?: 1
        val targetColumn = columns["target"] ?: 2
        val gameColumn = columns["game"] ?: -1
        val providerColumn = columns["provider"] ?: 3
        val modelColumn = columns["model"] ?: 4

        fun cell(cells: List<String>, index: Int): String =
            if (index >= 0) cells.getOrElse(index) { "" } else ""

        synchronized(lock) {
            var imported = 0
            var skipped = 0
            conn.prepareStatement(
                """
                INSERT INTO memory(game, key, source, target, provider, model, norm, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(game, key) DO UPDATE SET
                    source = excluded.source,
                    target = excluded.target,
                    provider = excluded.provider,
                    model = excluded.model,
                    norm = excluded.norm,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                records.drop(1).forEach { cells ->
                    if (cells.all { it.isEmpty() }) return@forEach
                    val game = cell(cells, gameColumn)
                    val key = cell(cells, keyColumn)
                    val source = cell(cells, sourceColumn)
                    val target = cell(cells, targetColumn)
                    if (key.isBlank() || source.isBlank() || target.isBlank()) {
                        skipped++
                        return@forEach
                    }
                    runCatching {
                        statement.setString(1, game)
                        statement.setString(2, key)
                        statement.setString(3, source)
                        statement.setString(4, target)
                        statement.setString(5, cell(cells, providerColumn))
                        statement.setString(6, cell(cells, modelColumn))
                        statement.setString(7, normalize(source))
                        statement.setLong(8, System.currentTimeMillis())
                        statement.executeUpdate()
                        imported++
                    }.onFailure { skipped++ }
                }
            }
            refreshCount()
            return imported to skipped
        }
    }

    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun parseCsv(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        val record = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false

        fun finishField() {
            record += field.toString()
            field.clear()
        }

        fun finishRecord() {
            finishField()
            records += record.toList()
            record.clear()
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch == ',' -> finishField()
                !inQuotes && (ch == '\n' || ch == '\r') -> {
                    finishRecord()
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> field.append(ch)
            }
            i++
        }
        if (inQuotes) throw IllegalArgumentException("CSV 引号未闭合")
        if (field.isNotEmpty() || record.isNotEmpty()) finishRecord()
        return records
    }
}
