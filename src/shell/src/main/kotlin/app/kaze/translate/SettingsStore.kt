package app.kaze.translate

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

data class TranslationSettings(
    val baseUrl: String = "",
    val model: String = "",
    val targetLang: String = "zh-CN",
    val encryptedKey: String = "",
    val batchSize: Int = 24,
    val parallel: Int = 4,
    val readOnly: Boolean = false,
    val dark: Boolean = false,
    val temperature: Double = 0.2,
    val requestTimeoutSec: Int = 180,
    
    val tone: String = "faithful",
)

object SettingsStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val defaultFile: Path = run {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        Paths.get(base, "Kaze", "settings.json")
    }

    
    internal var fileOverride: Path? = null

    private fun file(): Path = fileOverride ?: defaultFile

    var current: TranslationSettings = load()
        private set

    fun load(): TranslationSettings {
        val path = file()
        return try {
            if (!Files.exists(path)) {
                TranslationSettings()
            } else {
                val raw = com.google.gson.JsonParser.parseString(Files.readString(path)).asJsonObject
                val defaults = gson.toJsonTree(TranslationSettings()).asJsonObject
                defaults.entrySet().forEach { (key, value) ->
                    if (!raw.has(key) || raw.get(key).isJsonNull) raw.add(key, value)
                }
                val loaded = gson.fromJson(raw, TranslationSettings::class.java) ?: TranslationSettings()
                
                
                val oldTransport = raw.has("mode") || raw.has("provider")
                val legacySizes = loaded.baseUrl.isBlank() && loaded.model.isBlank() && loaded.batchSize > 100
                if (oldTransport || legacySizes) {
                    val cleaned = loaded.copy(
                        baseUrl = "",
                        model = "",
                        encryptedKey = "",
                        batchSize = 24,
                        parallel = 4,
                    )
                    runCatching {
                        Files.writeString(
                            path,
                            gson.toJson(cleaned),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        )
                    }
                    cleaned
                } else {
                    loaded
                }
            }
        } catch (_: Throwable) {
            runCatching {
                val path = file()
                if (Files.exists(path)) {
                    Files.move(
                        path,
                        path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
            TranslationSettings()
        }
    }

    fun save(settings: TranslationSettings): Boolean {
        val path = file()
        val temp = path.resolveSibling("${path.fileName}.tmp")
        return runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(
                temp,
                gson.toJson(settings),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Throwable) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            current = settings
            true
        }.getOrElse {
            runCatching { Files.deleteIfExists(temp) }
            false
        }
    }

    
    fun setApiKey(plain: String): Boolean {
        if (plain.isBlank()) {
            return save(current.copy(encryptedKey = ""))
        }
        val encrypted = Dpapi.protect(plain.toByteArray(Charsets.UTF_8)) ?: return false
        return save(current.copy(encryptedKey = Base64.getEncoder().encodeToString(encrypted)))
    }

    fun apiKey(): String {
        val encoded = current.encryptedKey
        if (encoded.isBlank()) return ""
        val encrypted = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return ""
        val plain = Dpapi.unprotect(encrypted) ?: return ""
        return String(plain, Charsets.UTF_8)
    }

    fun hasKey(): Boolean = apiKey().isNotBlank()

    fun settingsPath(): String = file().toString()


}
