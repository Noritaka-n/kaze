package app.kaze.translate

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStoreMigrationTest {

    @Test
    fun `old free settings migrate to empty api`() {
        val file = Files.createTempFile("kaze-settings", ".json")
        try {
            Files.writeString(
                file,
                """{"provider":"free","baseUrl":"free","model":"mymemory","batchSize":20}""",
            )
            SettingsStore.fileOverride = file
            val loaded = SettingsStore.load()
            assertEquals("", loaded.baseUrl)
            assertEquals("", loaded.model)
            assertEquals(24, loaded.batchSize)
            assertEquals(0.2, loaded.temperature)
            assertEquals(180, loaded.requestTimeoutSec)
            assertEquals("faithful", loaded.tone)
        } finally {
            SettingsStore.fileOverride = null
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `api settings survive a save and load round trip`() {
        val file = Files.createTempFile("kaze-settings-api", ".json")
        try {
            SettingsStore.fileOverride = file
            SettingsStore.save(
                TranslationSettings(
                    baseUrl = "https://api.example.com/v1",
                    model = "deepseek-chat",
                    batchSize = 24,
                ),
            )
            val loaded = SettingsStore.load()
            assertEquals("https://api.example.com/v1", loaded.baseUrl)
            assertEquals("deepseek-chat", loaded.model)
            assertEquals(24, loaded.batchSize)
        } finally {
            SettingsStore.fileOverride = null
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `legacy transport settings are discarded`() {
        val file = Files.createTempFile("kaze-settings-dsh", ".json")
        try {
            Files.writeString(
                file,
                """{"provider":"local","mode":"dsh","dshUrl":"http://127.0.0.1:3081","model":"old-local"}""",
            )
            SettingsStore.fileOverride = file
            val loaded = SettingsStore.load()
            assertEquals("", loaded.baseUrl)
            assertEquals("", loaded.model)
        } finally {
            SettingsStore.fileOverride = null
            Files.deleteIfExists(file)
        }
    }


}
