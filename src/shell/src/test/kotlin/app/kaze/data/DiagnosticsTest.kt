package app.kaze.data

import app.kaze.translate.SettingsStore
import app.kaze.translate.TranslationMemory
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {

    @Test
    fun `tail returns only the end of a large log`() {
        val file = Files.createTempFile("kaze-log", ".log").toFile()
        file.bufferedWriter().use { writer ->
            repeat(100_000) { writer.write("line $it\n") }
        }
        val lines = Diagnostics.tail(file, 3)
        assertEquals(listOf("line 99997", "line 99998", "line 99999"), lines)
    }

    @Test
    fun `tail on an empty file is empty`() {
        val file = Files.createTempFile("kaze-empty", ".log").toFile()
        assertEquals(emptyList(), Diagnostics.tail(file, 10))
    }

    @Test
    fun `gbk logs from python adapters are readable`() {
        val file = Files.createTempFile("kaze-gbk", ".log").toFile()
        file.writeBytes("适配器已连接\n".toByteArray(java.nio.charset.Charset.forName("GBK")))
        assertEquals(listOf("适配器已连接"), Diagnostics.tail(file, 5))
    }

    @Test
    fun `export uses real store paths and unique adapter entries`() {
        val root = Files.createTempDirectory("kaze-diagnostics").toFile()
        val oldLibrary = LibraryStore.storeOverride
        val oldSettings = SettingsStore.fileOverride
        val oldDb = TranslationMemory.dbFileOverride
        try {
            Diagnostics.baseDirOverride = root.resolve("local")
            Diagnostics.workingDirOverride = root.resolve("work")
            LibraryStore.storeOverride = root.resolve("roaming/Kaze/library.json").apply {
                parentFile.mkdirs()
                writeText("{\"games\":[]}")
            }
            SettingsStore.fileOverride = root.resolve("roaming/Kaze/settings.json").toPath().apply {
                Files.writeString(this, "{\"encryptedKey\":\"secret\",\"dark\":true}")
            }
            TranslationMemory.dbFileOverride = root.resolve("roaming/Kaze/translation.db").toPath().apply {
                Files.write(this, byteArrayOf(1, 2, 3))
            }
            listOf("mvmz", "renpy").forEach { adapter ->
                root.resolve("work/adapters/$adapter/adapter-run.log").apply {
                    parentFile.mkdirs()
                    writeText(adapter)
                }
            }

            val bundle = Diagnostics.exportBundle().getOrThrow()
            ZipFile(bundle).use { zip ->
                val names = zip.entries().toList().map { it.name }.toSet()
                assertTrue("library.json" in names)
                assertTrue("translation.db.txt" in names)
                assertTrue("adapters/mvmz/adapter-run.log" in names)
                assertTrue("adapters/renpy/adapter-run.log" in names)
                val settings = zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().readText()
                assertTrue("secret" !in settings)
                assertTrue(com.google.gson.JsonParser.parseString(settings).asJsonObject.get("dark").asBoolean)
            }
        } finally {
            Diagnostics.baseDirOverride = null
            Diagnostics.workingDirOverride = null
            LibraryStore.storeOverride = oldLibrary
            SettingsStore.fileOverride = oldSettings
            TranslationMemory.dbFileOverride = oldDb
            root.deleteRecursively()
        }
    }
}
