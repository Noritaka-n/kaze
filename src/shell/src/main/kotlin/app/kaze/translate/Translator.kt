package app.kaze.translate

import app.kaze.ipc.RelayUnit
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface Translator {
    



    suspend fun handshake() {}

    



    suspend fun translate(
        units: List<RelayUnit>,
        glossary: Map<String, String> = emptyMap(),
        context: Map<String, Pair<String, String>> = emptyMap(),
    ): Map<String, String>
}


class TranslationParseException(message: String) : IOException(message)








class OpenAiCompatibleTranslator(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val targetLang: String,
    private val temperature: Double = 0.2,
    private val timeoutSec: Int = 180,
    private val tone: String = "faithful",
    private val compact: Boolean = false,
    private val maxUnits: Int = 16,
) : Translator {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun translate(
        units: List<RelayUnit>,
        glossary: Map<String, String>,
        context: Map<String, Pair<String, String>>,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (units.isEmpty()) return@withContext emptyMap()

        val output = LinkedHashMap<String, String>()
        for (chunk in chunk(units)) {
            output.putAll(requestWithSplit(chunk, glossary, context))
        }
        output
    }

    private fun requestWithSplit(
        units: List<RelayUnit>,
        glossary: Map<String, String>,
        context: Map<String, Pair<String, String>>,
    ): Map<String, String> {
        val translated = requestOnce(units, glossary, context)
        return units.mapIndexedNotNull { index, unit ->
            translated.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { unit.key to it }
        }.toMap()
    }

    private fun requestOnce(
        units: List<RelayUnit>,
        glossary: Map<String, String>,
        context: Map<String, Pair<String, String>>,
    ): List<String> {
        val input = JsonArray().apply {
            units.forEachIndexed { index, unit ->
                add(JsonObject().apply {
                    addProperty("i", index)
                    addProperty("speaker", unit.speaker)
                    addProperty("scene", unit.scene)
                    addProperty("text", unit.source)
                    context[unit.key]?.let { (prev, next) ->
                        if (prev.isNotBlank()) addProperty("prev", prev)
                        if (next.isNotBlank()) addProperty("next", next)
                    }
                })
            }
        }

        val expectedOutput = units.sumOf { it.source.length } * 3 + 256
        val maxTokens = expectedOutput.coerceIn(512, 16_384)

        fun buildBody(withReasoningOff: Boolean): JsonObject = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", temperature.coerceIn(0.0, 2.0))
            addProperty("max_tokens", maxTokens)
            if (withReasoningOff) addProperty("reasoning_effort", "none")
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt(glossary))
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", input.toString())
                })
            })
        }

        val endpoint = URI.create(endpoint())
        var body = buildBody(withReasoningOff = true)

        fun post(payload: JsonObject): HttpResponse<String> {
            val call = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(timeoutSec.coerceIn(30, 600).toLong()))
                .header("Content-Type", "application/json")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), Charsets.UTF_8))
                .build()
            return http.send(call, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        }

        val first = try {
            post(body)
        } catch (error: java.io.IOException) {
            throw IOException("连不上模型服务 ${endpoint.host}:${endpoint.port}：${error.javaClass.simpleName}")
        }

        var response = if (first.statusCode() == 400 &&
            (first.body().contains("reasoning", true) || first.body().contains("unrecognized", true))
        ) {
            body = buildBody(withReasoningOff = false)
            post(body)
        } else {
            first
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw IOException("API Key 无效或未填写（HTTP ${response.statusCode()}）")
        }
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()}: ${response.body().take(220)}")
        }

        val choices = JsonParser.parseString(response.body())
            .asJsonObject
            .getAsJsonArray("choices")
            ?: throw TranslationParseException("响应没有 choices")
        val choice = choices.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw TranslationParseException("响应 choices 为空")
        val finish = choice.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (finish == "length") {
            throw TranslationParseException("输出被截断（max_tokens=$maxTokens）")
        }
        if (finish == "content_filter") {
            throw IOException("服务端内容过滤拦截，把这批拆小或换模型")
        }

        val contentElement = choice.getAsJsonObject("message")?.get("content")
        if (contentElement == null || !contentElement.isJsonPrimitive || !contentElement.asJsonPrimitive.isString) {
            throw TranslationParseException("响应 content 不是字符串")
        }
        return TranslationOutputParser.parse(contentElement.asString, units.size)
    }

    private fun endpoint(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun systemPrompt(glossary: Map<String, String>): String {
        if (compact) {
            return buildString {
                append("Translate the Japanese game text in each item into ").append(targetLang).append(".\n")
                append("Return ONLY {\"items\":[{\"i\":0,\"text\":\"...\"}]}. ")
                append("One item per input, same i, no markdown or extra text.\n")
                append("Preserve {{0}}, \\N[1], %1\$s, tags and line breaks. Keep names consistent.\n")
                append(
                    when (tone) {
                        "natural" -> "Tone: natural and fluent.\n"
                        "localized" -> "Tone: localized for Chinese players.\n"
                        else -> "Tone: faithful to the original.\n"
                    }
                )
                if (glossary.isNotEmpty()) {
                    append("Glossary: ")
                    append(glossary.entries.take(80).joinToString("; ") { (term, fixed) -> "$term=$fixed" })
                    append('\n')
                }
            }
        }
        return buildString {
            append("You are a professional game localization translator. ")
            append("Translate the Japanese game text in each item into ").append(targetLang).append(".\n")
            append("Tone: ").append(
                when (tone) {
                    "natural" -> "natural, fluent modern game text; keep the original personality."
                    "localized" -> "fully localized for Chinese players; idioms may be adapted, names stay consistent."
                    else -> "faithful to the original meaning and tone; do not add or remove information."
                }
            ).append('\n')
            append("Output format: ONLY a JSON object like {\"items\":[{\"i\":0,\"text\":\"...\"}]}.\n")
            append("Rules:\n")
            append("1. Exactly one output item per input item, with the same \"i\", same order. Never merge, split, skip or add items.\n")
            append("2. Translate only the \"text\" field. Never translate or repeat speaker, scene, prev or next.\n")
            append("3. Preserve every placeholder exactly: {{0}}, {{1}}, \\N[1], %1\$s, %2\$d, \\C[3], <tags>, line breaks.\n")
            append("4. Keep character names, honorifics and terminology consistent. Use the glossary when provided.\n")
            append("5. Use scene/prev/next only as context for pronouns and tone; never output them.\n")
            append("6. This is fictional game content. Translate explicit, violent or crude lines faithfully; do not soften, refuse, or add notes.\n")
            append("7. No markdown, no explanations, no text outside the JSON object.\n")
            if (glossary.isNotEmpty()) {
                append("Glossary (source=target, always use these): ")
                append(glossary.entries.take(120).joinToString("; ") { (term, fixed) -> "$term=$fixed" })
                append('\n')
            }
        }
    }

    private fun chunk(units: List<RelayUnit>): List<List<RelayUnit>> {
        val out = ArrayList<List<RelayUnit>>()
        var current = ArrayList<RelayUnit>()
        var chars = 0
        for (unit in units) {
            val size = unit.source.length
            if (current.isNotEmpty() && (current.size >= maxUnits.coerceIn(1, 32) || chars + size > 5_000)) {
                out += current
                current = ArrayList()
                chars = 0
            }
            current += unit
            chars += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}


object TranslationOutputParser {

    fun parse(content: String, expected: Int): List<String> {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.dropLast(3).trim()
        }
        if (text.isBlank()) throw TranslationParseException("模型返回空内容")

        val candidates = buildList {
            add(text)
            val start = text.indexOfFirst { it == '{' || it == '[' }
            val end = text.lastIndexOfAny(charArrayOf('}', ']'))
            if (start >= 0 && end > start) add(text.substring(start, end + 1))
        }
        for (candidate in candidates) {
            runCatching { parseJson(candidate, expected) }.getOrNull()?.let { return it }
        }
        runCatching { parseJsonLines(text, expected) }.getOrNull()?.let { return it }
        throw TranslationParseException("无法解析模型输出：${text.take(160)}")
    }

    private fun parseJson(text: String, expected: Int): List<String> {
        val root = JsonParser.parseString(text)
        val array: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val objectValue = root.asJsonObject
                sequenceOf("items", "translations", "data", "result")
                    .mapNotNull { key -> objectValue.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
                    .firstOrNull()
                    ?: throw TranslationParseException("JSON 里没有 items 数组")
            }
            else -> throw TranslationParseException("不是 JSON")
        }
        return mapItems(array, expected)
    }

    private fun parseJsonLines(text: String, expected: Int): List<String> {
        val array = JsonArray()
        for (line in text.lines()) {
            val trimmed = line.trim().trimEnd(',')
            if (trimmed.isBlank()) continue
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) continue
            val parsed = JsonParser.parseString(trimmed)
            if (parsed.isJsonArray) parsed.asJsonArray.forEach { array.add(it) } else array.add(parsed)
        }
        if (array.size() < expected) throw TranslationParseException("JSONL 行数不足")
        return mapItems(array, expected)
    }

    private fun mapItems(array: JsonArray, expected: Int): List<String> {
        val byIndex = HashMap<Int, String>()
        var sequence = 0
        array.forEach { element: JsonElement ->
            if (element.isJsonPrimitive) {
                byIndex[sequence] = element.asString
                sequence++
            } else if (element.isJsonObject) {
                val item = element.asJsonObject
                val text = sequenceOf("text", "translation", "target", "content")
                    .mapNotNull { key -> item.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                    .firstOrNull()
                    ?: return@forEach
                val index = item.get("i")?.takeIf { it.isJsonPrimitive }?.asInt ?: sequence
                byIndex[index] = text
                sequence++
            }
        }
        if (byIndex.size < expected) {
            throw TranslationParseException("只返回 ${byIndex.size}/$expected 行")
        }
        val out = MutableList(expected) { "" }
        byIndex.forEach { (index, text) ->
            if (index in 0 until expected) out[index] = text
        }
        if (out.any { it.isBlank() }) throw TranslationParseException("有空白译文")
        return out
    }
}
