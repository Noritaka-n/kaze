package app.kaze.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryScannerTest {

    private fun temp(): File = Files.createTempDirectory("kaze-scan").toFile()

    private fun marker(root: File, path: String) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText("x")
    }

    @Test
    fun `finds games below a collection folder`() {
        val root = temp()
        marker(root, "A/js/rmmz_core.js")
        marker(root, "B/js/rmmz_core.js")
        marker(root, "C/readme.txt")

        val names = LibraryScanner.scan(root).folders.map { it.name }.toSet()
        assertEquals(setOf("A", "B"), names)
    }

    @Test
    fun `skips tooling and hidden folders`() {
        val root = temp()
        marker(root, "node_modules/pkg/js/rmmz_core.js")
        marker(root, ".cache/js/rmmz_core.js")
        marker(root, "real/js/rmmz_core.js")

        val names = LibraryScanner.scan(root).folders.map { it.name }.toSet()
        assertEquals(setOf("real"), names)
    }

    @Test
    fun `respects the depth limit`() {
        val root = temp()
        marker(root, "1/2/3/4/js/rmmz_core.js")

        assertTrue(LibraryScanner.scan(root, maxDepth = 2).folders.isEmpty())
        assertEquals(1, LibraryScanner.scan(root, maxDepth = 6).folders.size)
    }

    @Test
    fun `finds deeply nested games without a shallow depth cap`() {
        val root = temp()
        val deep = (1..20).joinToString("/") { "level$it" }
        marker(root, "$deep/js/rmmz_core.js")

        val names = LibraryScanner.scan(root).folders.map { it.name }
        assertEquals(listOf("level20"), names)
    }

    @Test
    fun `does not descend into a game it already found`() {
        val root = temp()
        marker(root, "G/js/rmmz_core.js")
        marker(root, "G/inner/js/rmmz_core.js")

        val names = LibraryScanner.scan(root).folders.map { it.name }
        assertEquals(listOf("G"), names)
    }

    @Test
    fun `a game folder itself is returned as one entry`() {
        val root = temp()
        marker(root, "js/rmmz_core.js")

        val scan = LibraryScanner.scan(root)
        assertEquals(listOf(root.absolutePath), scan.folders.map { it.absolutePath })
    }

    @Test
    fun `cancellation stops the scan and reports it`() {
        val root = temp()
        marker(root, "A/js/rmmz_core.js")

        val scan = LibraryScanner.scan(root, isCancelled = { true })
        assertTrue(scan.cancelled)
        assertTrue(scan.folders.isEmpty())
    }

    @Test
    fun `an empty folder yields nothing`() {
        val root = temp()
        assertTrue(LibraryScanner.scan(root).folders.isEmpty())
    }
}
