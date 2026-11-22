package app.kaze.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File


object Favorites {
    private val gson = Gson()
    private val file: File = run {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        File(base, "Kaze/favorites.json")
    }

    var names: List<String> = emptyList()
        private set

    fun load() {
        names = runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(file.readText(Charsets.UTF_8), type).orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    fun toggle(name: String) {
        names = if (name in names) names - name else names + name
        save()
    }

    fun contains(name: String): Boolean = name in names

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(names), Charsets.UTF_8)
        }
    }
}
