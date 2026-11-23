package app.kaze.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationOutputParserTest {

    @Test
    fun `plain json array of strings`() {
        val out = TranslationOutputParser.parse("""["你好","世界"]""", 2)
        assertEquals(listOf("你好", "世界"), out)
    }

    @Test
    fun `object items keep their index order`() {
        val json = """{"items":[{"i":1,"text":"第二句"},{"i":0,"text":"第一句"}]}"""
        assertEquals(listOf("第一句", "第二句"), TranslationOutputParser.parse(json, 2))
    }

    @Test
    fun `markdown fence is stripped`() {
        val text = """
            ```json
            {"items":[{"i":0,"text":"甲"}]}
            ```
        """.trimIndent()
        assertEquals(listOf("甲"), TranslationOutputParser.parse(text, 1))
    }

    @Test
    fun `json lines fallback`() {
        val text = """{"i":0,"text":"甲"}
{"i":1,"text":"乙"}"""
        assertEquals(listOf("甲", "乙"), TranslationOutputParser.parse(text, 2))
    }

    @Test
    fun `missing items raise a parse error so the caller can split`() {
        assertFailsWith<TranslationParseException> {
            TranslationOutputParser.parse("""["only one"]""", 2)
        }
    }

    @Test
    fun `blank content raises`() {
        assertFailsWith<TranslationParseException> {
            TranslationOutputParser.parse("   ", 1)
        }
    }
}

class TranslatorFactoryTest {

    @Test
    fun `api mode uses the configured endpoint`() {
        val settings = TranslationSettings(
            baseUrl = "https://api.example.com/v1",
            model = "deepseek-chat",
        )
        assertEquals("api", TranslatorFactory.provider(settings))
        assertEquals("deepseek-chat", TranslatorFactory.model(settings))
        assertTrue(TranslatorFactory.label(settings).startsWith("大模型 API"))
    }

    @Test
    fun `blank api is not ready`() {
        assertFalse(TranslatorFactory.ready(TranslationSettings()))
    }

}
