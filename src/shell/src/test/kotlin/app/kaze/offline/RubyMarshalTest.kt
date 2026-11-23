package app.kaze.offline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue






class RubyMarshalTest {

    private fun resource(path: String): ByteArray =
        javaClass.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("missing test resource $path")

    @Test
    fun `small integers use the biased encoding`() {
        assertContentEquals(byteArrayOf(4, 8, 'i'.code.toByte(), 6), RubyMarshal.encode(RInt(1)))
        assertContentEquals(byteArrayOf(4, 8, 'i'.code.toByte(), 0xFA.toByte()), RubyMarshal.encode(RInt(-1)))

        val decoded = RubyMarshal.decode(byteArrayOf(4, 8, 'i'.code.toByte(), 6))
        assertEquals(1L, (decoded as RInt).value)
    }

    @Test
    fun `three byte integer uses the long form`() {
        val bytes = RubyMarshal.encode(RInt(123))
        assertContentEquals(byteArrayOf(4, 8, 'i'.code.toByte(), 1, 123), bytes)
        assertEquals(123L, (RubyMarshal.decode(bytes) as RInt).value)
    }

    @Test
    fun `string without encoding ivar round trips`() {
        val bytes = byteArrayOf(4, 8, 0x22, 10, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte())
        val value = RubyMarshal.decode(bytes)
        assertEquals("hello", String((value as RString).bytes, Charsets.UTF_8))
        assertContentEquals(bytes, RubyMarshal.encode(value))
    }

    @Test
    fun `codec fixture re-encodes byte for byte`() {
        val bytes = resource("/rgss/codec/sample.rvdata2")
        val value = RubyMarshal.decode(bytes)
        assertTrue(RubyMarshal.isLossless(bytes, value), "fixture must survive decode -> encode")
    }

    @Test
    fun `shared strings stay shared`() {
        val root = RubyMarshal.decode(resource("/rgss/codec/sample.rvdata2")) as RObject
        val events = root.attrs.first { it.first.name == "@events" }.second as RHash
        fun keyOf(pair: Pair<RValue, RValue>) =
            String((pair.first as RString).bytes, Charsets.UTF_8)

        val shared = events.pairs.first { keyOf(it) == "shared" }.second as RArray
        assertSame(shared.items[0], shared.items[1], "second reference must be a link, not a copy")
        assertEquals("同じ台詞", String((shared.items[0] as RString).bytes, Charsets.UTF_8))
    }

    @Test
    fun `utf8 strings keep their encoding ivar`() {
        val root = RubyMarshal.decode(resource("/rgss/codec/sample.rvdata2")) as RObject
        val title = root.attrs.first { it.first.name == "@display_name" }.second as RString
        assertTrue(title.ivars.any { it.first.name == "E" && it.second === RTrue })
        assertEquals("コーデック", String(title.bytes, Charsets.UTF_8))
    }

    @Test
    fun `editing one string keeps the graph writable`() {
        val root = RubyMarshal.decode(resource("/rgss/codec/sample.rvdata2")) as RObject
        val blob = root.attrs.first { it.first.name == "@blob" }.second as RString
        blob.bytes = "第二行".toByteArray(Charsets.UTF_8)

        val again = RubyMarshal.decode(RubyMarshal.encode(root)) as RObject
        val title = again.attrs.first { it.first.name == "@blob" }.second as RString
        assertEquals("第二行", String(title.bytes, Charsets.UTF_8))
    }

    @Test
    fun `every fixture re-encodes byte for byte`() {
        val names = listOf(
            "/rgss/codec/sample.rvdata2",
            "/rgss/rgss3/Data/Map001.rvdata2",
            "/rgss/rgss3/Data/CommonEvents.rvdata2",
            "/rgss/rgss3/Data/Items.rvdata2",
            "/rgss/rgss3/Data/Actors.rvdata2",
            "/rgss/rgss3/Data/System.rvdata2",
            "/rgss/rgss3/Data/MapInfos.rvdata2",
            "/rgss/rgss1/Data/Map002.rxdata",
        )
        names.forEach { name ->
            val bytes = resource(name)
            val encoded = RubyMarshal.encode(RubyMarshal.decode(bytes))
            if (!encoded.contentEquals(bytes)) {
                val first = bytes.indices.firstOrNull { it >= encoded.size || bytes[it] != encoded[it] }
                val from = maxOf(0, (first ?: 0) - 6)
                println("$name orig=${bytes.size} enc=${encoded.size} first=$first")
                println("  orig: " + bytes.copyOfRange(from, minOf(bytes.size, from + 20)).joinToString(" ") { "%02x".format(it) })
                println("  enc : " + encoded.copyOfRange(from, minOf(encoded.size, from + 20)).joinToString(" ") { "%02x".format(it) })
            }
            assertTrue(encoded.contentEquals(bytes), "$name must round trip byte for byte")
        }
    }

    @Test
    fun `truncated data is rejected`() {
        val bytes = resource("/rgss/codec/sample.rvdata2")
        val error = runCatching { RubyMarshal.decode(bytes.copyOf(bytes.size - 3)) }.exceptionOrNull()
        assertTrue(error is MarshalException, "expected MarshalException, got $error")
    }
}
