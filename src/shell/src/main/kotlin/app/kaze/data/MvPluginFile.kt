package app.kaze.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File













object MvPluginFile {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun install(file: File, entryJson: String): Boolean {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val patched = insert(text, entryJson) ?: return false
        if (patched == text) return true
        return runCatching {
            file.writeText(patched, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun remove(file: File, name: String): Boolean {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val patched = removeEntry(text, name) ?: return false
        if (patched == text) return true
        return runCatching {
            file.writeText(patched, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    
    internal fun insert(text: String, entryJson: String): String? {
        val (open, close) = arrayBounds(text) ?: return null
        val names = namesIn(text.substring(open, close)) ?: return null
        val entry = runCatching { JsonParser.parseString(entryJson).asJsonObject }.getOrNull()
            ?: return null
        val name = entry.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        if (name != null && name in names) return text

        val inner = text.substring(open + 1, close - 1)
        val body = inner.trimEnd()
        val separator = when {
            body.isBlank() -> ""
            body.endsWith(",") -> ""
            else -> ","
        }
        val indent = text.substring(0, close - 1).substringAfterLast('\n')
            .takeWhile { it == ' ' || it == '\t' }
        val patchedInner = body + separator + "\n" + indent + "  " + entryJson + "\n" + indent
        return text.substring(0, open + 1) + patchedInner + text.substring(close - 1)
    }

    internal fun removeEntry(text: String, name: String): String? {
        val (open, close) = arrayBounds(text) ?: return null
        val arrayEnd = close - 1
        val target = elementRanges(text, open + 1, arrayEnd).firstOrNull { range ->
            nameOf(text.substring(range.first, range.last + 1)) == name
        } ?: return text

        var start = target.first
        val end = target.last + 1
        var back = start - 1
        while (back > open && text[back].isWhitespace()) back--
        var forward = end
        while (forward < arrayEnd && text[forward].isWhitespace()) forward++

        val delete = when {
            back > open && text[back] == ',' -> back until end
            forward < arrayEnd && text[forward] == ',' -> start until (forward + 1)
            else -> start until end
        }
        return text.removeRange(delete.first, delete.last + 1)
    }

    
    private fun namesIn(arrayText: String): List<String>? = runCatching {
        JsonParser.parseString(lenient(arrayText)).asJsonArray.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        }
    }.getOrNull()

    private fun nameOf(element: String): String? = runCatching {
        JsonParser.parseString(element).asJsonObject.get("name")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    



    private fun lenient(json: String): String {
        val out = StringBuilder(json.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < json.length) {
            val char = json[index]
            if (inString) {
                out.append(char)
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                index++
                continue
            }
            if (char == '"') {
                inString = true
                out.append(char)
                index++
                continue
            }
            if (char == ',') {
                var next = index + 1
                while (next < json.length && json[next].isWhitespace()) next++
                if (next < json.length && (json[next] == ']' || json[next] == '}')) {
                    index++ 
                    continue
                }
            }
            out.append(char)
            index++
        }
        return out.toString()
    }

    
    private fun elementRanges(text: String, start: Int, endExclusive: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var elementStart = -1
        var inString = false
        var escaped = false
        var index = start
        while (index < endExclusive) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                index++
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) elementStart = index
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0 && elementStart >= 0) {
                        ranges += elementStart..index
                        elementStart = -1
                    }
                }
            }
            index++
        }
        return ranges
    }

    



    private fun arrayBounds(text: String): Pair<Int, Int>? {
        
        val declaration = Regex("""var\s+\${'$'}plugins\s*=""").find(text)?.range?.first
            ?: Regex("""\${'$'}plugins\s*=""").find(text)?.range?.first
            ?: return null
        val open = text.indexOf('[', declaration)
        if (open < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false
        var index = open
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return open to (index + 1)
                    }
                }
            }
            index++
        }
        return null
    }
}
