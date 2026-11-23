package app.kaze.data

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverSourceTest {

    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private fun game(keyHex: String): File {
        val root = Files.createTempDirectory("kaze-cover-source").toFile()
        val data = File(root, "data").apply { mkdirs() }
        File(data, "System.json").writeText("""{"encryptionKey":"$keyHex"}""", Charsets.UTF_8)
        return root
    }

    private fun packed(root: File, name: String, keyHex: String): File {
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }
        val body = png.copyOf()
        for (index in 0 until 16) body[index] = (body[index].toInt() xor key[index].toInt()).toByte()
        val header = byteArrayOf(
            'R'.code.toByte(), 'P'.code.toByte(), 'G'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(),
            0, 0, 0, 0, 3, 1, 0, 0, 0, 0, 0,
        )
        return File(root, name).apply {
            parentFile.mkdirs()
            writeBytes(header + body)
        }
    }

    @Test
    fun `plain images pass validation`() {
        val file = Files.createTempFile("plain", ".png").toFile().apply { writeBytes(png) }
        assertTrue(CoverSource.isUsable(file))
        assertNotNull(CoverSource.bytes(file))
    }

    @Test
    fun `rpgmv assets decode with the game key`() {
        val key = "00112233445566778899aabbccddeeff"
        val root = game(key)
        val cover = packed(root, "img/titles1/title.png_", key)

        assertTrue(CoverSource.isUsable(cover))
        assertNotNull(CoverSource.bytes(cover), "wrong header handling would return null here")
    }

    @Test
    fun `a wrong key is rejected instead of shown as art`() {
        val root = game("00112233445566778899aabbccddeeff")
        val cover = packed(root, "img/titles1/title.png_", "ffeeddccbbaa99887766554433221100")

        assertFalse(CoverSource.isUsable(cover))
        assertNull(CoverSource.bytes(cover))
    }

    @Test
    fun `missing key falls back to unusable`() {
        val root = Files.createTempDirectory("kaze-nokey").toFile()
        val cover = packed(root, "img/titles1/title.png_", "00112233445566778899aabbccddeeff")
        assertFalse(CoverSource.isUsable(cover))
    }

    @Test
    fun `text files are not images`() {
        val file = Files.createTempFile("notes", ".txt").toFile().apply { writeText("hello") }
        assertFalse(CoverSource.isUsable(file))
        assertNull(CoverSource.bytes(file))
    }
}
