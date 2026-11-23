package app.kaze.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ModifierValuesTest {

    @Test
    fun `switch values become json booleans`() {
        assertEquals("true", ModifierValues.encode("True", "bool"))
        assertEquals("false", ModifierValues.encode("False", "bool"))
        assertEquals("true", ModifierValues.encode("1", "boolean"))
        assertEquals("false", ModifierValues.encode("0", "boolean"))
    }

    @Test
    fun `numbers are not quoted`() {
        assertEquals("123", ModifierValues.encode("123", "number"))
        assertEquals("1.5", ModifierValues.encode("1.5", "number"))
        assertEquals("1000", ModifierValues.encode("1000", "money"))
        assertEquals("0", ModifierValues.encode("not a number", "number"))
    }

    @Test
    fun `strings stay quoted and escaped`() {
        assertEquals("\"hello\"", ModifierValues.encode("hello", "string"))
        assertEquals("\"say \\\"hi\\\"\"", ModifierValues.encode("say \"hi\"", "string"))
    }

    @Test
    fun `json containers pass through`() {
        assertEquals("{\"a\":1}", ModifierValues.encode("{\"a\":1}", "object"))
        assertEquals("[1,2]", ModifierValues.encode("[1,2]", "object"))
    }

    @Test
    fun `unknown types fall back to strings`() {
        assertEquals("\"123\"", ModifierValues.encode("123", null))
        assertEquals("\"plain\"", ModifierValues.encode("plain", "undefined"))
    }
}