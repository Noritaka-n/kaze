package app.kaze.translate

import app.kaze.ipc.RelayUnit
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslatorHttpTest {

    @Test
    fun `openai compatible endpoint is parsed`() = runBlocking {
        val inner = """{"items":[{"i":0,"text":"你好"}]}"""
        val response = JsonObject().apply {
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    add("message", JsonObject().apply { addProperty("content", inner) })
                    addProperty("finish_reason", "stop")
                })
            })
        }.toString()

        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }
        }

        try {
            val translator = OpenAiCompatibleTranslator(
                baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                apiKey = "",
                model = "mock",
                targetLang = "zh-CN",
            )
            val unit = RelayUnit(
                key = "k1",
                source = "こんにちは",
                speaker = "",
                scene = "",
                target = "",
            )
            val result = translator.translate(listOf(unit))
            assertEquals("你好", result["k1"])
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
