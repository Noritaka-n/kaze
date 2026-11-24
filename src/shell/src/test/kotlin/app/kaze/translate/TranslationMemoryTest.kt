package app.kaze.translate

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationMemoryTest {
    private lateinit var root: Path
    private lateinit var db: Path

    @BeforeTest
    fun setUp() {
        TranslationMemory.close()
        root = Files.createTempDirectory("kaze-tm-test")
        db = root.resolve("translation.db")
        TranslationMemory.dbFileOverride = db
        TranslationMemory.open()
        assertTrue(TranslationMemory.available, TranslationMemory.lastError)
    }

    @AfterTest
    fun tearDown() {
        TranslationMemory.close()
        TranslationMemory.dbFileOverride = null
        root.toFile().deleteRecursively()
    }

    @Test
    fun `patch round trip preserves game and duplicate keys`() {
        TranslationMemory.store("shared", "same", "甲", "p1", "m1", "game-a")
        TranslationMemory.store("shared", "same", "乙", "p2", "m2", "game-b")
        val patch = root.resolve("memory.json")

        assertEquals(2, TranslationMemory.exportPatch(patch))
        TranslationMemory.clear("game-a")
        TranslationMemory.clear("game-b")
        assertEquals(2 to 0, TranslationMemory.importPatch(patch))

        assertEquals("甲", TranslationMemory.lookupAll(listOf("shared"), "game-a")["shared"])
        assertEquals("乙", TranslationMemory.lookupAll(listOf("shared"), "game-b")["shared"])
        assertTrue(TranslationMemory.lookupAll(listOf("shared")).isEmpty())
        val json = com.google.gson.JsonParser.parseString(Files.readString(patch)).asJsonObject
        assertEquals(2, json.get("version").asInt)
        assertEquals(setOf("game-a", "game-b"), json.getAsJsonArray("entries").map {
            it.asJsonObject.get("game").asString
        }.toSet())
    }

    @Test
    fun `version one patch imports as global memory`() {
        val patch = root.resolve("legacy.json")
        Files.writeString(
            patch,
            """{"format":"kaze-tm","version":1,"entries":[{"key":"legacy","source":"old","target":"旧"}]}""",
        )

        assertEquals(1 to 0, TranslationMemory.importPatch(patch))
        assertEquals("旧", TranslationMemory.lookupAll(listOf("legacy"))["legacy"])
        assertEquals("旧", TranslationMemory.lookupAll(listOf("legacy"), "any-game")["legacy"])
    }

    @Test
    fun `csv round trip preserves games multiline fields quotes and commas`() {
        val source = "first\r\nsecond, \"quoted\""
        TranslationMemory.store("shared", source, "译文一\n第二行", "p1", "m1", "game-a")
        TranslationMemory.store("shared", source, "另一个, \"译文\"", "p2", "m2", "game-b")
        val csv = root.resolve("memory.csv")

        assertEquals(2, TranslationMemory.exportCsv(csv))
        TranslationMemory.clear("game-a")
        TranslationMemory.clear("game-b")
        assertEquals(2 to 0, TranslationMemory.importCsv(csv))

        assertEquals("译文一\n第二行", TranslationMemory.lookupAll(listOf("shared"), "game-a")["shared"])
        assertEquals("另一个, \"译文\"", TranslationMemory.lookupAll(listOf("shared"), "game-b")["shared"])
        assertTrue(Files.readString(csv, Charsets.UTF_8).startsWith("\uFEFFgame,key,source,target"))
    }

    @Test
    fun `legacy six column csv remains compatible`() {
        val csv = root.resolve("legacy.csv")
        Files.writeString(
            csv,
            "\uFEFFkey,source,target,provider,model,updated_at\r\n" +
                "legacy,\"hello, world\",\"你好,世界\",p,m,1\r\n",
            Charsets.UTF_8,
        )

        assertEquals(1 to 0, TranslationMemory.importCsv(csv))
        assertEquals("你好,世界", TranslationMemory.lookupAll(listOf("legacy"))["legacy"])
    }

    @Test
    fun `corrupt main database restores verified backup without clobbering it`() {
        TranslationMemory.store("key", "source", "target", "p", "m", "game-a")
        TranslationMemory.close()
        TranslationMemory.open()
        assertTrue(TranslationMemory.available, TranslationMemory.lastError)
        TranslationMemory.close()

        val backup = db.resolveSibling("translation.db.bak")
        val goodBackup = Files.readAllBytes(backup)
        Files.write(db, byteArrayOf(1, 2, 3, 4, 5))

        TranslationMemory.open()
        assertTrue(TranslationMemory.available, TranslationMemory.lastError)
        assertEquals("target", TranslationMemory.lookupAll(listOf("key"), "game-a")["key"])
        assertContentEquals(goodBackup, Files.readAllBytes(backup))
    }

    @Test
    fun `legacy schema without norm migrates and backfills`() {
        TranslationMemory.close()
        Files.delete(db)
        Files.deleteIfExists(db.resolveSibling("translation.db.bak"))
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE memory(key TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, " +
                        "provider TEXT, model TEXT, updated_at INTEGER NOT NULL)",
                )
                statement.executeUpdate("INSERT INTO memory VALUES('old', 'Old Source', '旧译文', 'p', 'm', 1)")
            }
        }

        TranslationMemory.open()
        assertTrue(TranslationMemory.available, TranslationMemory.lastError)
        assertEquals("旧译文", TranslationMemory.lookupAll(listOf("old"))["old"])
        assertEquals("旧译文", TranslationMemory.lookupBySource(listOf("old source"))[
            TranslationMemory.normalize("Old Source"),
        ])
    }
}
