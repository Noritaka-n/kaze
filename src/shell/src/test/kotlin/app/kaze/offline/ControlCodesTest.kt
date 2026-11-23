package app.kaze.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlCodesTest {

    @Test
    fun `rpg maker codes are protected and restored`() {
        val source = "\\N[1]：\\C[2]おはよう\\{"
        val protected = ControlCodes.protect(source)
        assertEquals("{{0}}：{{1}}おはよう{{2}}", protected.text)
        assertEquals(listOf("\\N[1]", "\\C[2]", "\\{"), protected.tokens)
        assertEquals(source, ControlCodes.restore(protected.text, protected.tokens))
    }

    @Test
    fun `printf placeholders survive`() {
        val protected = ControlCodes.protect("%1\$s は %2\$d 回")
        assertEquals("{{0}} は {{1}} 回", protected.text)
        assertEquals("%1\$s は %2\$d 回", ControlCodes.restore(protected.text, protected.tokens))
    }

    @Test
    fun `a translation that loses a marker is rejected`() {
        val protected = ControlCodes.protect("\\N[1]こんにちは")
        assertNull(ControlCodes.restore("你好", protected.tokens))
    }

    @Test
    fun `markers tolerate spaces the model may add`() {
        val protected = ControlCodes.protect("\\N[1]こんにちは")
        assertEquals("\\N[1]你好", ControlCodes.restore("{{ 0 }}你好", protected.tokens))
    }

    @Test
    fun `text without codes is left alone`() {
        val protected = ControlCodes.protect("ただの文章")
        assertTrue(protected.tokens.isEmpty())
        assertEquals("普通的句子", ControlCodes.restore("普通的句子", protected.tokens))
    }
}
