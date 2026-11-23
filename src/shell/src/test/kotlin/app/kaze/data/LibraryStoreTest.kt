package app.kaze.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryStoreTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("kaze-store").toFile()
        LibraryStore.storeOverride = File(root, "library.json")
        LibraryStore.load()
    }

    @AfterTest
    fun tearDown() {
        LibraryStore.storeOverride = null
        LibraryStore.load()
    }

    private fun game(name: String): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "js").mkdirs()
        File(dir, "js/rmmz_core.js").writeText("// engine")
        File(dir, "data").mkdirs()
        File(dir, "data/System.json").writeText("""{"gameTitle":"$name"}""", Charsets.UTF_8)
        return dir
    }

    @Test
    fun `added games survive a reload`() {
        val added = LibraryStore.addFolder(game("Alpha")).getOrThrow()
        assertEquals("Alpha", added.title)

        LibraryStore.load()
        assertEquals(1, LibraryStore.games.size)
        assertEquals(added.id, LibraryStore.games.single().id)
        assertTrue(File(root, "library.json").isFile)
    }

    @Test
    fun `corrupt main file falls back to the backup`() {
        LibraryStore.addFolder(game("Alpha")).getOrThrow()
        val beta = LibraryStore.addFolder(game("Beta")).getOrThrow()
        LibraryStore.touch(beta.id)

        val main = File(root, "library.json")
        val backup = File(root, "library.json.bak")
        assertTrue(backup.isFile, "a second save must leave a backup")
        main.writeText("{ broken", Charsets.UTF_8)

        LibraryStore.load()
        assertEquals(2, LibraryStore.games.size)
        assertNotNull(LibraryStore.lastError)
    }

    @Test
    fun `a corrupt load heals main without clobbering the backup`() {
        LibraryStore.addFolder(game("Alpha")).getOrThrow()
        val beta = LibraryStore.addFolder(game("Beta")).getOrThrow()
        LibraryStore.touch(beta.id)

        val main = File(root, "library.json")
        val backup = File(root, "library.json.bak")
        assertTrue(backup.isFile)
        main.writeText("{ broken", Charsets.UTF_8)

        LibraryStore.load()
        assertEquals(2, LibraryStore.games.size)
        assertNotNull(
            runCatching {
                com.google.gson.JsonParser.parseString(main.readText(Charsets.UTF_8)).asJsonObject
            }.getOrNull(),
            "main must be rewritten from the backup",
        )


        LibraryStore.touch(beta.id)
        main.writeText("{ broken again", Charsets.UTF_8)
        LibraryStore.load()
        assertEquals(2, LibraryStore.games.size)
    }

    @Test
    fun `a cancelled scan commits nothing`() {
        val collection = File(root, "collection").apply { mkdirs() }
        val dir = File(collection, "One").apply { mkdirs() }
        File(dir, "js").mkdirs()
        File(dir, "js/rmmz_core.js").writeText("//")

        val summary = LibraryStore.scanFolder(collection, isCancelled = { true }).getOrThrow()
        assertTrue(summary.cancelled)
        assertEquals(0, LibraryStore.games.size)
    }

    @Test
    fun `scan folder ignores non rpg maker games`() {
        val collection = File(root, "collection").apply { mkdirs() }
        val unity = File(collection, "UnityGame").apply { mkdirs() }
        File(unity, "UnityGame_Data").mkdirs()
        File(unity, "UnityGame.exe").writeText("x")
        val renpy = File(collection, "RenPyGame").apply { mkdirs() }
        File(renpy, "game").mkdirs()
        File(renpy, "game/script.rpy").writeText("x")

        val summary = LibraryStore.scanFolder(collection).getOrThrow()
        assertEquals(0, summary.added.size)
        assertEquals(0, LibraryStore.games.size)
    }

    @Test
    fun `scan folder adds everything in one pass`() {
        val collection = File(root, "collection").apply { mkdirs() }
        listOf("One", "Two").forEach { name ->
            val dir = File(collection, name).apply { mkdirs() }
            File(dir, "js").mkdirs()
            File(dir, "js/rmmz_core.js").writeText("//")
        }

        val summary = LibraryStore.scanFolder(collection).getOrThrow()
        assertEquals(2, summary.added.size)
        assertEquals(0, summary.failed)
        assertEquals(2, LibraryStore.games.size)
    }
}
