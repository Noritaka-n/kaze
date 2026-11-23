package app.kaze.data

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverFinderTest {

    private fun temp(): File = Files.createTempDirectory("kaze-cover").toFile()


    private fun image(dir: File, path: String, side: Int = 32): File {
        val file = File(dir, path)
        file.parentFile.mkdirs()
        val buffered = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val random = Random(path.hashCode().toLong())
        for (y in 0 until side) {
            for (x in 0 until side) buffered.setRGB(x, y, random.nextInt())
        }
        ImageIO.write(buffered, "png", file)
        return file
    }

    @Test
    fun `mv and mz prefer the title screen over the icon`() {
        val dir = temp()
        val title = image(dir, "img/titles1/Title.png")
        image(dir, "icon/icon.png", side = 48)

        assertEquals(title.absolutePath, CoverFinder.find(dir, Engine.RMMZ)?.absolutePath)
        assertEquals(title.absolutePath, CoverFinder.find(dir, Engine.RMMV)?.absolutePath)
    }

    @Test
    fun `mv falls back to the icon`() {
        val dir = temp()
        val icon = image(dir, "icon/icon.png", side = 48)
        assertEquals(icon.absolutePath, CoverFinder.find(dir, Engine.RMMV)?.absolutePath)
    }

    @Test
    fun `rgss uses the titles folder`() {
        val dir = temp()
        val title = image(dir, "Graphics/Titles1/title.png")
        assertEquals(title.absolutePath, CoverFinder.find(dir, Engine.RGSS)?.absolutePath)
    }

    @Test
    fun `packed assets with a trailing underscore are still artwork`() {
        val dir = temp()
        val title = image(dir, "img/titles1/title_a.png_")
        image(dir, "icon/icon.png", side = 48)
        assertEquals(title.absolutePath, CoverFinder.find(dir, Engine.RMMZ)?.absolutePath)
    }

    @Test
    fun `tiny placeholders are ignored`() {
        val dir = temp()
        image(dir, "img/titles1/blank.png", side = 4)
        image(dir, "icon/icon.png", side = 3)
        assertNull(CoverFinder.find(dir, Engine.RMMZ))
    }

    @Test
    fun `unrelated folders have no cover`() {
        val dir = temp()
        File(dir, "readme.txt").writeText("x")
        assertNull(CoverFinder.find(dir, Engine.OTHER))
    }
}
