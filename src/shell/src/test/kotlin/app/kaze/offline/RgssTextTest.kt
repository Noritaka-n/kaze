package app.kaze.offline

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RgssTextTest {

    private fun copyFixture(resource: String): File {
        val url = javaClass.getResource(resource) ?: error("missing test resource $resource")
        val source = File(url.toURI())
        val target = Files.createTempDirectory("kaze-rgss").toFile()
        source.copyRecursively(target, overwrite = true)
        target.deleteOnExit()
        return target
    }

    private fun texts(gameDir: File): List<String> = RgssText.scan(gameDir).entries.map { it.text }

    @Test
    fun `rgss3 data is detected and scanned`() {
        val dir = copyFixture("/rgss/rgss3")
        assertEquals(TextRoute.RGSS, OfflineText.detect(dir))

        val scan = OfflineText.scan(dir).result
        assertTrue(scan.warnings.isEmpty(), "unexpected warnings: ${scan.warnings}")
        assertEquals(44, scan.entries.size, "entry count changed: ${scan.byKind}")

        val kinds = scan.byKind
        assertEquals(5, kinds["dialogue"])
        assertEquals(2, kinds["choice"])
        assertEquals(2, kinds["choice_label"])
        assertEquals(2, kinds["scroll_text"])
        assertEquals(1, kinds["event_name"])
        assertEquals(8, kinds["db"])
        assertEquals(3, kinds["map_name"])
        assertEquals(1, kinds["system"])
        assertEquals(20, kinds["system_term"])

        assertTrue("今日は何をする？" in texts(dir))
        assertTrue("ゆっくり休んでいってね。" in texts(dir))
        assertTrue("テストゲーム" in texts(dir))
        assertTrue("始まりの村" in texts(dir))
        assertTrue("ミナ" in texts(dir))
    }

    @Test
    fun `write back replaces text and restore puts it back`() {
        val dir = copyFixture("/rgss/rgss3")
        val file = File(dir, "Data/Map001.rvdata2")
        val before = file.readBytes()

        val report = RgssText.apply(dir, mapOf("今日は何をする？" to "今天要做什么？"))
        assertEquals(1, report.replaced, "report=$report")
        assertEquals(1, report.files)
        assertTrue(report.skipped.isEmpty(), "unexpected skips: ${report.skipped}")

        val backup = File(dir, "Data.kaze-backup")
        assertTrue(backup.isDirectory, "backup directory must exist after a write")


        assertTrue(
            String(file.readBytes(), Charsets.UTF_8).contains("今天要做什么？"),
            "live data file must contain the translation",
        )


        assertTrue("今日は何をする？" in texts(dir))

        val (restored, ok) = RgssText.restore(dir)
        assertTrue(ok)
        assertEquals(6, restored)
        assertTrue(before.contentEquals(file.readBytes()), "restore must bring the original bytes back")
        assertTrue("今日は何をする？" in texts(dir))
    }

    @Test
    fun `shift_jis data can be read but not written`() {
        val dir = copyFixture("/rgss/rgss1")
        assertEquals(TextRoute.RGSS, OfflineText.detect(dir))

        val scan = RgssText.scan(dir)
        assertTrue("こんにちは、旅の人。" in scan.entries.map { it.text })
        assertTrue("はい" in scan.entries.map { it.text })
        assertTrue(scan.warnings.isEmpty())

        val file = File(dir, "Data/Map002.rxdata")
        val before = file.readBytes()
        val report = RgssText.apply(dir, mapOf("こんにちは、旅の人。" to "你好，旅人。"))
        assertEquals(0, report.replaced)
        assertTrue(report.skipped.any { it.contains("Shift_JIS") }, "skips=${report.skipped}")
        assertTrue(before.contentEquals(file.readBytes()), "read-only file must not change")
        assertFalse(File(dir, "Data.kaze-backup").exists())
    }

    @Test
    fun `untagged ascii data is readable and still read-only`() {
        val dir = copyFixture("/rgss/rgss1")
        val scan = RgssText.scan(dir)
        assertTrue("Hello, traveler." in scan.entries.map { it.text })

        val report = RgssText.apply(dir, mapOf("Hello, traveler." to "你好，旅人。"))
        assertEquals(0, report.replaced)
        assertTrue(report.skipped.any { it.contains("ASCII") }, "skips=${report.skipped}")
    }

    @Test
    fun `title comes from the system table`() {
        val dir = copyFixture("/rgss/rgss3")
        assertEquals("テストゲーム", RgssText.readTitle(dir))
    }

    @Test
    fun `xp title falls back to Game ini`() {
        val dir = Files.createTempDirectory("kaze-xp").toFile()
        File(dir, "Data").mkdirs()
        File(dir, "Game.ini").writeText("[Game]\nTitle=村の物語\n", Charsets.UTF_8)
        assertEquals("村の物語", RgssText.readTitle(dir))
    }

    @Test
    fun `encrypted archive is reported instead of silence`() {
        val dir = Files.createTempDirectory("kaze-rgssa").toFile()
        File(dir, "Game.rgss3a").writeBytes(byteArrayOf(1, 2, 3, 4))
        assertEquals(TextRoute.RGSS, OfflineText.detect(dir))
        val scan = OfflineText.scan(dir).result
        assertEquals(0, scan.entries.size)
        assertTrue(scan.warnings.any { it.contains("rgss3a") }, "warnings=${scan.warnings}")
    }

    @Test
    fun `unknown engines report an unsupported route`() {
        val empty = Files.createTempDirectory("kaze-empty").toFile()
        assertEquals(TextRoute.UNSUPPORTED, OfflineText.detect(empty))
        assertEquals(0, OfflineText.scan(empty).result.entries.size)
    }

    @Test
    fun `mv json data still routes to the json reader`() {
        val dir = Files.createTempDirectory("kaze-mv").toFile()
        File(dir, "www/data").mkdirs()
        File(dir, "www/data/System.json").writeText("""{"gameTitle":"Demo"}""", Charsets.UTF_8)
        assertEquals(TextRoute.MV, OfflineText.detect(dir))
        assertEquals(1, OfflineText.scan(dir).result.entries.size)
    }
}
