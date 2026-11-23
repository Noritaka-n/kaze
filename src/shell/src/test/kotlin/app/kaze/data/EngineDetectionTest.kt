package app.kaze.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull






class EngineDetectionTest {

    private fun game(vararg files: String): File {
        val dir = Files.createTempDirectory("kaze-detect").toFile()
        files.forEach { path ->
            val file = File(dir, path)
            file.parentFile.mkdirs()
            file.writeText("x")
        }
        return dir
    }

    @Test
    fun `mv and mz are told apart by their core scripts`() {
        assertEquals(Engine.RMMV, LibraryStore.detectEngine(game("js/rpg_core.js")))
        assertEquals(Engine.RMMZ, LibraryStore.detectEngine(game("js/rmmz_core.js")))
        assertEquals(Engine.RMMZ, LibraryStore.detectEngine(game("www/js/rmmz_core.js")))
    }

    @Test
    fun `rgss is detected for xp vx and ace`() {
        assertEquals(Engine.RGSS, LibraryStore.detectEngine(game("Data/Scripts.rxdata")))
        assertEquals(Engine.RGSS, LibraryStore.detectEngine(game("Data/Scripts.rvdata")))
        assertEquals(Engine.RGSS, LibraryStore.detectEngine(game("Data/Scripts.rvdata2")))
        assertEquals(Engine.RGSS, LibraryStore.detectEngine(game("Game.rgss3a")))
        assertEquals(Engine.RGSS, LibraryStore.detectEngine(game("Game.ini", "Data/Actors.rxdata")))
    }

    @Test
    fun `non rpg maker engines are rejected`() {
        assertNull(LibraryStore.detectEngine(game("game/script.rpy")))
        assertNull(LibraryStore.detectEngine(game("Game.exe", "Data.wolf")))
        assertNull(LibraryStore.detectEngine(game("data.xp3")))
        assertNull(LibraryStore.detectEngine(game("project.godot")))
        assertNull(LibraryStore.detectEngine(game("Game_Data/app.info")))
    }

    @Test
    fun `an unrelated folder has no engine`() {
        assertNull(LibraryStore.detectEngine(game("readme.txt")))
    }
}
