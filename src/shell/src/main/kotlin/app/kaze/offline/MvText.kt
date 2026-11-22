package app.kaze.offline

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File


data class TextEntry(
    val locator: String,
    val kind: String,
    val text: String,
)

data class ScanResult(
    val entries: List<TextEntry>,
    val byKind: Map<String, Int>,
    val files: Int,
    val warnings: List<String> = emptyList(),
) {
    val uniqueTexts: Int get() = entries.map { it.text }.toSet().size
}





private fun JsonArray.at(index: Int): JsonElement? =
    if (index >= 0 && index < size()) get(index) else null

object MvText {

    private val dbFields = mapOf(
        "Actors.json" to listOf("name", "nickname", "profile"),
        "Classes.json" to listOf("name"),
        "Items.json" to listOf("name", "description"),
        "Weapons.json" to listOf("name", "description"),
        "Armors.json" to listOf("name", "description"),
        "Skills.json" to listOf("name", "description", "message1", "message2"),
        "States.json" to listOf("name", "message1", "message2", "message3", "message4"),
        "Enemies.json" to listOf("name"),
    )

    fun dataDir(gameDir: File): File? = listOf(
        File(gameDir, "www/data"),
        File(gameDir, "data"),
    ).firstOrNull { it.isDirectory }

    




    fun sourceDir(gameDir: File): File? {
        val live = dataDir(gameDir) ?: return null
        val backup = File(live.parentFile, "${live.name}.kaze-backup")
        return if (backup.isDirectory) backup else live
    }

    fun scan(gameDir: File): ScanResult {
        val dir = sourceDir(gameDir) ?: return ScanResult(emptyList(), emptyMap(), 0)
        val entries = ArrayList<TextEntry>()
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }.orEmpty()

        files.forEach { file ->
            val name = file.name
            val root = runCatching { JsonParser.parseString(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return@forEach
            when {
                name.startsWith("Map") && root.isJsonObject -> scanMap(name, root.asJsonObject, entries)
                name == "CommonEvents.json" && root.isJsonArray -> scanCommonEvents(root.asJsonArray, entries)
                name == "System.json" && root.isJsonObject -> scanSystem(root.asJsonObject, entries)
                root.isJsonArray -> scanDatabase(name, root.asJsonArray, entries)
            }
        }

        val byKind = entries.groupingBy { it.kind }.eachCount()
        return ScanResult(entries, byKind, files.size)
    }

    private fun scanMap(name: String, root: JsonObject, out: MutableList<TextEntry>) {
        root.get("displayName")?.let { value ->
            if (value.isJsonPrimitive && value.asString.isNotBlank()) {
                out += TextEntry("$name:displayName", "map_name", value.asString)
            }
        }
        root.getAsJsonArray("events")?.forEach { element ->
            val event = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val eventId = event.get("id")?.asInt ?: 0
            event.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() && !it.startsWith("EV") }
                ?.let { out += TextEntry("$name:event$eventId/name", "event_name", it) }
            event.get("pages")?.asJsonArray?.forEachIndexed { pageIndex, pageElement ->
                val page = pageElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
                scanCommands(
                    page.getAsJsonArray("list"),
                    "$name:event$eventId/page$pageIndex",
                    out,
                )
            }
        }
    }

    private fun scanCommonEvents(root: JsonArray, out: MutableList<TextEntry>) {
        root.forEach { element ->
            val event = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val eventId = event.get("id")?.asInt ?: 0
            event.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() && !it.startsWith("EV") }
                ?.let { out += TextEntry("CommonEvents.json:event$eventId/name", "event_name", it) }
            scanCommands(event.getAsJsonArray("list"), "CommonEvents.json:event$eventId", out)
        }
    }

    private fun scanCommands(list: JsonArray?, locator: String, out: MutableList<TextEntry>) {
        list ?: return
        list.forEachIndexed { index, element ->
            val command = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val code = command.get("code")?.asInt ?: -1
            val params = command.get("parameters")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEachIndexed
            val here = "$locator/cmd$index"
            when (code) {
                401, 405 -> params.at(0)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }?.let { out += TextEntry(here, "dialogue", it) }

                102 -> params.at(0)?.takeIf { it.isJsonArray }?.asJsonArray?.forEachIndexed { i, choice ->
                    if (choice.isJsonPrimitive && choice.asString.isNotBlank()) {
                        out += TextEntry("$here/choice$i", "choice", choice.asString)
                    }
                }

                402 -> params.at(1)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }?.let { out += TextEntry("$here/label", "choice_label", it) }

                320 -> params.at(0)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }?.let { out += TextEntry("$here/name", "actor_name", it) }

                324 -> params.at(1)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }?.let { out += TextEntry("$here/nick", "actor_nick", it) }

                325 -> params.at(1)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }?.let { out += TextEntry("$here/profile", "actor_profile", it) }
            }
        }
    }

    private fun scanDatabase(name: String, root: JsonArray, out: MutableList<TextEntry>) {
        val fields = dbFields[name] ?: return
        root.forEach { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val rowId = row.get("id")?.asInt ?: 0
            fields.forEach { field ->
                val value = row.get(field)?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                if (value.isNotBlank()) out += TextEntry("$name:$rowId/$field", "db", value)
            }
        }
    }

    private fun scanSystem(root: JsonObject, out: MutableList<TextEntry>) {
        root.get("gameTitle")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }?.let { out += TextEntry("System.json:gameTitle", "system", it) }

        root.get("currencyUnit")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }?.let { out += TextEntry("System.json:currencyUnit", "system_term", it) }

        val terms = root.get("terms")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        listOf("basic", "commands", "params").forEach { group ->
            terms.get(group)?.takeIf { it.isJsonArray }?.asJsonArray?.forEachIndexed { index, value ->
                val text = value.takeIf { it.isJsonPrimitive }?.asString ?: return@forEachIndexed
                if (text.isNotBlank()) {
                    out += TextEntry("System.json:terms/$group[$index]", "system_term", text)
                }
            }
        }
        terms.get("messages")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { entry ->
            val text = entry.value.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            if (text.isNotBlank()) {
                out += TextEntry("System.json:terms/messages.${entry.key}", "system_term", text)
            }
        }
    }

    

    fun exportTable(result: ScanResult, file: File) {
        val root = JsonObject()
        root.addProperty("format", "kaze-mvtext")
        root.addProperty("version", 1)
        root.add("entries", JsonArray().apply {
            result.entries.forEach { entry ->
                add(JsonObject().apply {
                    addProperty("locator", entry.locator)
                    addProperty("kind", entry.kind)
                    addProperty("source", entry.text)
                })
            }
        })
        file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root), Charsets.UTF_8)
    }

    fun exportCsv(result: ScanResult, file: File) {
        val builder = StringBuilder("locator\tkind\tsource\ttarget\n")
        result.entries.forEach { entry ->
            builder.append(entry.locator).append('\t')
                .append(entry.kind).append('\t')
                .append(entry.text.replace("\t", " ").replace("\n", "\\n")).append('\t')
                .append('\n')
        }
        file.writeText(builder.toString(), Charsets.UTF_8)
    }

    
    fun readTranslations(file: File): Map<String, String> {
        val text = file.readText(Charsets.UTF_8)
        if (text.trimStart().startsWith("{")) {
            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return emptyMap()
            val entries = root.getAsJsonArray("entries") ?: return emptyMap()
            val map = LinkedHashMap<String, String>()
            entries.forEach { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val source = item.get("source")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val target = item.get("target")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                if (target.isNotBlank() && source.isNotBlank()) map[source] = target
            }
            return map
        }

        val map = LinkedHashMap<String, String>()
        text.lineSequence().drop(1).forEach { line ->
            val cells = line.split('\t')
            if (cells.size >= 4) {
                val source = cells[2].replace("\\n", "\n")
                val target = cells[3].trim()
                if (target.isNotBlank() && source.isNotBlank()) map[source] = target
            }
        }
        return map
    }

    



    fun apply(gameDir: File, translations: Map<String, String>): Pair<Int, Int> {
        val dir = dataDir(gameDir) ?: return 0 to 0
        if (translations.isEmpty()) return 0 to 0

        val backup = File(dir.parentFile, "${dir.name}.kaze-backup")
        if (!backup.exists()) {
            dir.copyRecursively(backup, overwrite = false)
        }

        var replaced = 0
        var files = 0
        dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }.orEmpty().forEach { file ->
            val name = file.name
            val root = runCatching { JsonParser.parseString(file.readText(Charsets.UTF_8)) }.getOrNull()
                ?: return@forEach
            var changed = 0
            when {
                name.startsWith("Map") && root.isJsonObject -> changed += applyMap(root.asJsonObject, translations)
                name == "CommonEvents.json" && root.isJsonArray -> changed += applyCommandsDeep(root.asJsonArray, translations)
                name == "System.json" && root.isJsonObject -> changed += applySystem(root.asJsonObject, translations)
                root.isJsonArray -> changed += applyDatabase(name, root.asJsonArray, translations)
            }
            if (changed > 0) {
                
                
                file.writeText(
                    GsonBuilder().disableHtmlEscaping().create().toJson(root),
                    Charsets.UTF_8,
                )
                replaced += changed
                files++
            }
        }
        return replaced to files
    }

    
    fun restore(gameDir: File): Pair<Int, Boolean> {
        val dir = dataDir(gameDir) ?: return 0 to false
        val backup = File(dir.parentFile, "${dir.name}.kaze-backup")
        if (!backup.isDirectory) return 0 to false
        var count = 0
        backup.listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                file.copyTo(File(dir, file.name), overwrite = true)
                count++
            }
        }
        return count to true
    }

    private fun translate(value: JsonElement?, translations: Map<String, String>): String? {
        val text = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        return translations[text]
    }

    private fun applyMap(root: JsonObject, translations: Map<String, String>): Int {
        var count = 0
        translate(root.get("displayName"), translations)?.let {
            root.addProperty("displayName", it); count++
        }
        root.getAsJsonArray("events")?.forEach { element ->
            val event = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            translate(event.get("name"), translations)?.let { event.addProperty("name", it); count++ }
            event.get("pages")?.asJsonArray?.forEach { pageElement ->
                val page = pageElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                count += applyCommands(page.getAsJsonArray("list"), translations)
            }
        }
        return count
    }

    private fun applyCommandsDeep(root: JsonArray, translations: Map<String, String>): Int {
        var count = 0
        root.forEach { element ->
            val event = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            translate(event.get("name"), translations)?.let { event.addProperty("name", it); count++ }
            count += applyCommands(event.getAsJsonArray("list"), translations)
        }
        return count
    }

    private fun applyCommands(list: JsonArray?, translations: Map<String, String>): Int {
        list ?: return 0
        var count = 0
        list.forEach { element ->
            val command = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val code = command.get("code")?.asInt ?: -1
            val params = command.get("parameters")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
            when (code) {
                401, 405 -> {
                    translate(params.at(0), translations)?.let {
                        params.set(0, com.google.gson.JsonPrimitive(it)); count++
                    }
                }

                102 -> params.at(0)?.takeIf { it.isJsonArray }?.asJsonArray?.let { choices ->
                    for (i in 0 until choices.size()) {
                        translate(choices.get(i), translations)?.let {
                            choices.set(i, com.google.gson.JsonPrimitive(it)); count++
                        }
                    }
                }

                402 -> {
                    translate(params.at(1), translations)?.let {
                        params.set(1, com.google.gson.JsonPrimitive(it)); count++
                    }
                }

                320 -> {
                    translate(params.at(0), translations)?.let {
                        params.set(0, com.google.gson.JsonPrimitive(it)); count++
                    }
                }

                324, 325 -> {
                    translate(params.at(1), translations)?.let {
                        params.set(1, com.google.gson.JsonPrimitive(it)); count++
                    }
                }
            }
        }
        return count
    }

    private fun applyDatabase(name: String, root: JsonArray, translations: Map<String, String>): Int {
        val fields = dbFields[name] ?: return 0
        var count = 0
        root.forEach { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            fields.forEach { field ->
                translate(row.get(field), translations)?.let {
                    row.addProperty(field, it); count++
                }
            }
        }
        return count
    }

    private fun applySystem(root: JsonObject, translations: Map<String, String>): Int {
        var count = 0
        translate(root.get("gameTitle"), translations)?.let { root.addProperty("gameTitle", it); count++ }
        translate(root.get("currencyUnit"), translations)?.let { root.addProperty("currencyUnit", it); count++ }
        val terms = root.get("terms")?.takeIf { it.isJsonObject }?.asJsonObject ?: return count
        listOf("basic", "commands", "params").forEach { group ->
            terms.get(group)?.takeIf { it.isJsonArray }?.asJsonArray?.let { array ->
                for (i in 0 until array.size()) {
                    translate(array.get(i), translations)?.let {
                        array.set(i, com.google.gson.JsonPrimitive(it)); count++
                    }
                }
            }
        }
        terms.get("messages")?.takeIf { it.isJsonObject }?.asJsonObject?.let { messages ->
            messages.entrySet().toList().forEach { entry ->
                translate(entry.value, translations)?.let {
                    messages.addProperty(entry.key, it); count++
                }
            }
        }
        return count
    }
}
