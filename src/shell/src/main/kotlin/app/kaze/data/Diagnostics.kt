package app.kaze.data

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream





object Diagnostics {
    private val baseDir: File =
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Kaze")

    val logDir: File get() = File(baseDir, "logs")
    val sessionDir: File get() = File(baseDir, "session")

    fun knownFiles(): List<File> {
        val candidates = mutableListOf<File>()
        candidates += File(baseDir, "library.json")
        candidates += File(baseDir, "translation.db")
        candidates += File(System.getenv("APPDATA") ?: "", "Kaze/settings.json")
        candidates += sessionDir.listFiles().orEmpty().toList()
        candidates += File(System.getProperty("user.dir"), "adapters/mvmz/adapter-run.log")
        candidates += File(System.getProperty("user.dir"), "adapters/mvmz/adapter-run.err")
        candidates += File(System.getProperty("user.dir"), "adapters/renpy/adapter-run.log")
        return candidates.filter { it.exists() && it.isFile }
    }

    



    fun tail(file: File, lines: Int = 200): List<String> = runCatching {
        val length = file.length()
        if (length == 0L) return@runCatching emptyList()
        val maxBytes = 512 * 1024
        val start = maxOf(0L, length - maxBytes)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray((length - start).toInt())
            raf.readFully(buffer)
            val utf8 = String(buffer, Charsets.UTF_8)
            
            
            val text = if (utf8.contains('\uFFFD')) {
                String(buffer, java.nio.charset.Charset.forName("GBK"))
            } else {
                utf8
            }
            val all = text.lines()
            
            val usable = if (start > 0 && all.isNotEmpty()) all.drop(1) else all
            usable.dropLastWhile { it.isEmpty() }.takeLast(lines)
        }
    }.getOrDefault(emptyList())

    
    fun exportBundle(): Result<File> = runCatching {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val out = File(baseDir, "diagnostics-$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            knownFiles().forEach { file ->
                val name = file.name
                if (name.equals("translation.db", true)) {
                    
                    zip.putNextEntry(ZipEntry("translation.db.txt"))
                    zip.write("size=${file.length()} bytes".toByteArray())
                    zip.closeEntry()
                    return@forEach
                }
                if (name.equals("settings.json", true)) {
                    val redacted = file.readText(Charsets.UTF_8)
                        .replace(Regex("\"encryptedKey\"\\s*:\\s*\"[^\"]*\""), "\"encryptedKey\":\"\"")
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(redacted.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    return@forEach
                }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("environment.txt"))
            zip.write(
                """
                time=${LocalDateTime.now()}
                os=${System.getProperty("os.name")} ${System.getProperty("os.version")}
                java=${System.getProperty("java.version")}
                user.dir=${System.getProperty("user.dir")}
                adapter.log=${File(System.getProperty("user.dir"), "adapters/mvmz/adapter-run.log")}
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
        out
    }
}
