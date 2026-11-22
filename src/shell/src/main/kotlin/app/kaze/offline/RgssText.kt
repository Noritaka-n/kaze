package app.kaze.offline

import java.io.File
import java.nio.charset.Charset


data class ApplyReport(
    val replaced: Int,
    val files: Int,
    val skipped: List<String> = emptyList(),
)














object RgssText {
    private val extensions = setOf("rxdata", "rvdata", "rvdata2")
    private val mapName = Regex("^map\\d{3}$", RegexOption.IGNORE_CASE)

    private val databaseFields = mapOf(
        "actors" to listOf("@name", "@nickname", "@description", "@profile"),
        "classes" to listOf("@name"),
        "skills" to listOf("@name", "@description", "@message1", "@message2"),
        "items" to listOf("@name", "@description"),
        "weapons" to listOf("@name", "@description"),
        "armors" to listOf("@name", "@description"),
        "enemies" to listOf("@name"),
        "states" to listOf("@name", "@message1", "@message2", "@message3", "@message4"),
        "troops" to listOf("@name"),
    )

    fun dataDir(gameDir: File): File? = listOf(
        File(gameDir, "Data"),
        File(gameDir, "data"),
    ).firstOrNull { it.isDirectory }

    fun sourceDir(gameDir: File): File? {
        val live = dataDir(gameDir) ?: return null
        val backup = File(live.parentFile, "${live.name}.kaze-backup")
        return if (backup.isDirectory) backup else live
    }

    fun isRgssGame(gameDir: File): Boolean {
        val dir = dataDir(gameDir) ?: return false
        return dir.listFiles()?.any { isDataFile(it) } == true
    }

    
    fun archiveFile(gameDir: File): File? = listOf("Game.rgss3a", "Game.rgss2a", "Game.rgssad")
        .map { File(gameDir, it) }
        .firstOrNull { it.isFile }

    



    fun readTitle(gameDir: File): String? {
        val dir = dataDir(gameDir)
        if (dir != null) {
            val system = files(dir).firstOrNull { it.nameWithoutExtension.equals("System", true) }
            if (system != null) {
                val root = runCatching { RubyMarshal.decode(system.readBytes()) }.getOrNull()
                val title = root?.let { attr(it, "@game_title") } as? RString
                if (title != null) {
                    val text = String(title.bytes, detectCharset(root).charset).trim()
                    if (text.isNotEmpty()) return text
                }
            }
        }
        val ini = File(gameDir, "Game.ini")
        if (ini.isFile) {
            val bytes = runCatching { ini.readBytes() }.getOrNull() ?: return null
            val text = String(bytes, Charsets.UTF_8)
            val decoded = if (text.contains('\uFFFD')) String(bytes, Charset.forName("windows-31j")) else text
            return Regex("(?m)^\\s*Title\\s*=\\s*(.+)$").find(decoded)
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun isDataFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() in extensions

    

    fun scan(gameDir: File): ScanResult {
        val dir = sourceDir(gameDir)
        if (dir == null) {
            val archive = archiveFile(gameDir)
            val warning = archive?.let { "${it.name}：加密归档，文件路线暂不支持（需要解包）" }
            return ScanResult(emptyList(), emptyMap(), 0, listOfNotNull(warning))
        }
        val entries = ArrayList<TextEntry>()
        val warnings = ArrayList<String>()
        var scanned = 0

        files(dir).forEach { file ->
            val parsed = load(file, warnings) ?: return@forEach
            scanned++
            val sites = collect(parsed.root, file.name, parsed.charset)
            sites.forEach { site ->
                if (site.text.isNotBlank()) entries += TextEntry(site.locator, site.kind, site.text)
            }
        }

        val byKind = entries.groupingBy { it.kind }.eachCount()
        return ScanResult(entries, byKind, scanned, warnings.toList())
    }

    private fun files(dir: File): List<File> = dir.listFiles()
        ?.filter { isDataFile(it) }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    

    




    fun apply(gameDir: File, translations: Map<String, String>): ApplyReport {
        val dir = dataDir(gameDir) ?: return ApplyReport(0, 0, listOf("找不到 Data 目录"))
        if (translations.isEmpty()) return ApplyReport(0, 0)

        val skipped = ArrayList<String>()
        val pending = ArrayList<Triple<File, RValue, List<StringSite>>>()

        files(dir).forEach { file ->
            val parsed = load(file, skipped) ?: return@forEach
            val sites = collect(parsed.root, file.name, parsed.charset)
            
            
            if (sites.none { translations.containsKey(it.text) }) return@forEach
            if (!parsed.charset.writable) {
                skipped += "${file.name}：${parsed.charset.label} 数据只读（写回中文会乱码）"
                return@forEach
            }
            if (!RubyMarshal.isLossless(parsed.bytes, parsed.root)) {
                skipped += "${file.name}：结构不支持无损重写"
                return@forEach
            }
            pending += Triple(file, parsed.root, sites)
        }

        if (pending.isEmpty()) return ApplyReport(0, 0, skipped.toList())

        val backup = File(dir.parentFile, "${dir.name}.kaze-backup")
        if (!backup.exists()) dir.copyRecursively(backup, overwrite = false)

        var replaced = 0
        var written = 0
        pending.forEach { (file, root, sites) ->
            var changed = 0
            siteLoop@ for (site in sites) {
                val target = translations[site.text] ?: continue@siteLoop
                if (target.isBlank() || target == site.text) continue@siteLoop
                site.node.bytes = target.toByteArray(Charsets.UTF_8)
                markUtf8(site.node)
                changed++
            }
            if (changed == 0) return@forEach
            file.writeBytes(RubyMarshal.encode(root))
            replaced += changed
            written++
        }
        return ApplyReport(replaced, written, skipped.toList())
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

    

    private class Parsed(
        val bytes: ByteArray,
        val root: RValue,
        val charset: RgssCharset,
    )

    private enum class RgssCharset(val label: String, val charset: Charset, val writable: Boolean) {
        UTF8("UTF-8", Charsets.UTF_8, true),
        ASCII_UNKNOWN("ASCII（无编码标记）", Charsets.UTF_8, false),
        SJIS("Shift_JIS", Charset.forName("windows-31j"), false),
    }

    private fun load(file: File, warnings: MutableList<String>): Parsed? {
        val bytes = runCatching { file.readBytes() }.getOrElse {
            warnings += "${file.name}：读取失败 ${it.message}"
            return null
        }
        val root = runCatching { RubyMarshal.decode(bytes) }.getOrElse {
            warnings += "${file.name}：不是可解析的 Marshal 数据"
            return null
        }
        return Parsed(bytes, root, detectCharset(root))
    }

    





    private fun detectCharset(root: RValue): RgssCharset {
        var tagged = false
        var nonAscii = false
        forEachString(root) { node ->
            if (node.ivars.any { it.first.name == "E" }) tagged = true
            if (node.bytes.any { (it.toInt() and 0xFF) >= 0x80 }) nonAscii = true
        }
        return when {
            tagged -> RgssCharset.UTF8
            nonAscii -> RgssCharset.SJIS
            else -> RgssCharset.ASCII_UNKNOWN
        }
    }

    private fun forEachString(value: RValue, visit: (RString) -> Unit) {
        when (value) {
            is RString -> visit(value)
            is RArray -> value.items.forEach { forEachString(it, visit) }
            is RHash -> value.pairs.forEach { (key, item) ->
                forEachString(key, visit)
                forEachString(item, visit)
            }

            is RObject -> value.attrs.forEach { forEachString(it.second, visit) }
            is RStructLike -> value.attrs.forEach { forEachString(it.second, visit) }
            is RUserMarshal -> forEachString(value.data, visit)
            is RUClass -> forEachString(value.value, visit)
            is RExtended -> forEachString(value.value, visit)
            else -> Unit
        }
    }

    

    private class StringSite(
        val locator: String,
        val kind: String,
        val node: RString,
        val text: String,
    )

    private fun attr(node: RValue?, name: String): RValue? =
        (node as? RObject)?.attrs?.firstOrNull { it.first.name == name }?.second

    private fun attrsOf(node: RValue): MutableList<Pair<RSymbol, RValue>> = when (node) {
        is RObject -> node.attrs
        is RStructLike -> node.attrs
        else -> mutableListOf()
    }

    private fun asInt(node: RValue?): Int? = (node as? RInt)?.value?.toInt()

    private fun asList(node: RValue?): List<RValue> = (node as? RArray)?.items.orEmpty()

    private fun asPairs(node: RValue?): List<Pair<RValue, RValue>> = (node as? RHash)?.pairs.orEmpty()

    private fun collect(root: RValue, fileName: String, charset: RgssCharset): List<StringSite> {
        val out = ArrayList<StringSite>()
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        val label = "$base${if (ext.isNotEmpty()) ".$ext" else ""}"
        when {
            mapName.matches(base) -> collectMap(root, label, charset, out)
            base.equals("Scripts", true) -> Unit 
            base.equals("CommonEvents", true) -> {
                asList(root).forEach { event ->
                    val id = asInt(attr(event, "@id")) ?: 0
                    val here = "$label:event$id"
                    stringSite(attr(event, "@name"), "$here/name", "event_name", charset, out)
                    collectCommands(attr(event, "@list"), here, charset, out)
                }
            }

            base.equals("Troops", true) -> {
                asList(root).forEach { troop ->
                    val id = asInt(attr(troop, "@id")) ?: 0
                    collectDatabaseObject(
                        troop, "$label:$id", "troop_name", charset, out, listOf("@name"),
                    )
                    asList(attr(troop, "@pages")).forEachIndexed { pageIndex, page ->
                        collectCommands(
                            attr(page, "@list"),
                            "$label:$id/page$pageIndex",
                            charset,
                            out,
                        )
                    }
                }
            }

            base.equals("MapInfos", true) -> {
                asPairs(root).forEach { (key, info) ->
                    val id = asInt(key) ?: 0
                    stringSite(attr(info, "@name"), "$label:$id/name", "map_name", charset, out)
                }
            }

            base.equals("System", true) -> collectSystem(root, label, charset, out)
            else -> {
                val fields = databaseFields[base.lowercase()] ?: return out
                asList(root).forEach { row ->
                    val id = asInt(attr(row, "@id")) ?: 0
                    collectDatabaseObject(row, "$label:$id", "db", charset, out, fields)
                }
            }
        }
        return out
    }

    private fun collectMap(root: RValue, label: String, charset: RgssCharset, out: MutableList<StringSite>) {
        stringSite(attr(root, "@display_name"), "$label:display_name", "map_name", charset, out)
        asPairs(attr(root, "@events")).forEach { (key, event) ->
            val id = asInt(key) ?: asInt(attr(event, "@id")) ?: 0
            asList(attr(event, "@pages")).forEachIndexed { pageIndex, page ->
                collectCommands(attr(page, "@list"), "$label:event$id/page$pageIndex", charset, out)
            }
        }
    }

    private fun collectCommands(list: RValue?, here: String, charset: RgssCharset, out: MutableList<StringSite>) {
        asList(list).forEachIndexed { index, command ->
            val code = asInt(attr(command, "@code")) ?: return@forEachIndexed
            val params = attr(command, "@parameters")
            val at = "$here/cmd$index"
            when (code) {
                401 -> stringSite(param(params, 0), at, "dialogue", charset, out)
                405 -> stringSite(param(params, 0), at, "scroll_text", charset, out)
                102 -> asList(param(params, 0)).forEachIndexed { choiceIndex, choice ->
                    stringSite(choice, "$at/choice$choiceIndex", "choice", charset, out)
                }

                402 -> stringSite(param(params, 1), "$at/label", "choice_label", charset, out)
                320 -> stringSite(param(params, 1), "$at/name", "actor_name", charset, out)
                324 -> stringSite(param(params, 1), "$at/nick", "actor_nick", charset, out)
                325 -> stringSite(param(params, 1), "$at/profile", "actor_profile", charset, out)
            }
        }
    }

    private fun param(params: RValue?, index: Int): RValue? =
        (params as? RArray)?.items?.getOrNull(index)

    private fun collectDatabaseObject(
        row: RValue,
        here: String,
        kind: String,
        charset: RgssCharset,
        out: MutableList<StringSite>,
        fields: List<String>,
    ) {
        fields.forEach { field ->
            stringSite(attr(row, field), "$here/${field.removePrefix("@")}", kind, charset, out)
        }
    }

    private fun collectSystem(root: RValue, label: String, charset: RgssCharset, out: MutableList<StringSite>) {
        stringSite(attr(root, "@game_title"), "$label:game_title", "system", charset, out)
        stringSite(attr(root, "@currency_unit"), "$label:currency_unit", "system_term", charset, out)

        listOf("@switches", "@variables", "@elements", "@skill_types", "@weapon_types", "@armor_types")
            .forEach { field ->
                asList(attr(root, field)).forEachIndexed { index, value ->
                    stringSite(value, "$label:${field.removePrefix("@")}[$index]", "system_term", charset, out)
                }
            }

        val terms = attr(root, "@terms")
        if (terms != null) {
            listOf("@basic", "@commands", "@params").forEach { field ->
                asList(attr(terms, field)).forEachIndexed { index, value ->
                    stringSite(value, "$label:terms/${field.removePrefix("@")}[$index]", "system_term", charset, out)
                }
            }
            val messages = attr(terms, "@messages")
            attrsOf(messages ?: RNil).forEach { (name, value) ->
                stringSite(value, "$label:terms/messages.${name.name}", "system_term", charset, out)
            }
        }

        
        attrsOf(attr(root, "@words") ?: RNil).forEach { (name, value) ->
            stringSite(value, "$label:words.${name.name}", "system_term", charset, out)
        }
    }

    private fun stringSite(
        node: RValue?,
        locator: String,
        kind: String,
        charset: RgssCharset,
        out: MutableList<StringSite>,
    ) {
        val text = node as? RString ?: return
        val decoded = String(text.bytes, charset.charset)
        if (decoded.isBlank()) return
        out += StringSite(locator, kind, text, decoded)
    }

    



    private fun markUtf8(node: RString) {
        val index = node.ivars.indexOfFirst { it.first.name == "E" }
        if (index >= 0) {
            node.ivars[index] = RSymbol("E") to RTrue
        } else {
            node.ivars.add(RSymbol("E") to RTrue)
        }
    }
}
