package app.kaze.translate

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration


object ModelCatalog {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build()

    suspend fun list(baseUrl: String, apiKey: String = ""): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = baseUrl.trim().trimEnd('/')
                val endpoint = when {
                    root.endsWith("/chat/completions") ->
                        root.removeSuffix("/chat/completions") + "/models"
                    root.endsWith("/v1") -> "$root/models"
                    else -> "$root/v1/models"
                }
                val request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(8))
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .GET()
                    .build()
                val response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
                )
                if (response.statusCode() !in 200..299) {
                    error("HTTP ${response.statusCode()}: ${response.body().take(160)}")
                }
                val data = JsonParser.parseString(response.body()).asJsonObject
                    .getAsJsonArray("data") ?: return@runCatching emptyList()
                val names = data.mapNotNull { element ->
                    runCatching { element.asJsonObject.get("id")?.asString }.getOrNull()
                }.filter { it.isNotBlank() }
                if (names.isEmpty()) error("服务在线，但模型列表为空")
                names
            }
        }
}
