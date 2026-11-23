package app.kaze.offline

import app.kaze.translate.SettingsStore
import app.kaze.translate.TranslationSettings
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyGuardTest {

    private var original: TranslationSettings? = null

    @BeforeTest
    fun setUp() {
        original = SettingsStore.current
        SettingsStore.fileOverride = Files.createTempDirectory("kaze-settings").resolve("settings.json")
        SettingsStore.save(SettingsStore.current.copy(readOnly = true))
    }

    @AfterTest
    fun tearDown() {


        SettingsStore.fileOverride = Files.createTempDirectory("kaze-settings-restore")
            .resolve("settings.json")
        original?.let { SettingsStore.save(it) }
        SettingsStore.fileOverride = null
    }

    @Test
    fun `apply is blocked in read only mode`() {
        val dir = Files.createTempDirectory("kaze-ro").toFile()
        val report = OfflineText.apply(dir, mapOf("a" to "b"))
        assertEquals(0, report.replaced)
        assertTrue(report.skipped.any { it.contains("只读") }, "skips=${report.skipped}")
    }

    @Test
    fun `restore is blocked in read only mode`() {
        val dir = Files.createTempDirectory("kaze-ro").toFile()
        val (count, ok) = OfflineText.restore(dir)
        assertEquals(0, count)
        assertFalse(ok)
    }
}
