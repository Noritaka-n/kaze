package app.kaze.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsTest {

    @Test
    fun `tail returns only the end of a large log`() {
        val file = Files.createTempFile("kaze-log", ".log").toFile()
        file.bufferedWriter().use { writer ->
            repeat(100_000) { writer.write("line $it\n") }
        }
        val lines = Diagnostics.tail(file, 3)
        assertEquals(listOf("line 99997", "line 99998", "line 99999"), lines)
    }

    @Test
    fun `tail on an empty file is empty`() {
        val file = Files.createTempFile("kaze-empty", ".log").toFile()
        assertEquals(emptyList(), Diagnostics.tail(file, 10))
    }

    @Test
    fun `gbk logs from python adapters are readable`() {
        val file = Files.createTempFile("kaze-gbk", ".log").toFile()
        file.writeBytes("适配器已连接\n".toByteArray(java.nio.charset.Charset.forName("GBK")))
        assertEquals(listOf("适配器已连接"), Diagnostics.tail(file, 5))
    }
}
