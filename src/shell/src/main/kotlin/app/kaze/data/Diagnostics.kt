package app.kaze.data

import app.kaze.translate.SettingsStore
import app.kaze.translate.TranslationMemory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream





object Diagnostics {
    private val defaultBaseDir: File =
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Kaze")

    internal var baseDirOverride: File? = null
    internal var workingDirOverride: File? = null

    private fun baseDir(): File = baseDirOverride ?: defaultBaseDir
    private fun workingDir(): File = workingDirOverride ?: File(System.getProperty("user.dir"))

    val logDir: File get() = File(baseDir(), "logs")
    val sessionDir: File get() = File(baseDir(), "session")

    fun knownFiles(): List<File> {
        val candidates = mutableListOf<File>()
        candidates += File(LibraryStore.path())
        candidates += File(TranslationMemory.path())
        candidates += File(SettingsStore.settingsPath())
        candidates += sessionDir.listFiles().orEmpty().toList()
        candidates += File(workingDir(), "adapters/mvmz/adapter-run.log")
        candidates += File(workingDir(), "adapters/mvmz/adapter-run.err")
        candidates += File(workingDir(), "adapters/renpy/adapter-run.log")
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
        val out = File(baseDir(), "diagnostics-$stamp.zip")
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val usedNames = HashSet<String>()
            fun uniqueName(base: String): String {
                var name = base
                var suffix = 1
                while (!usedNames.add(name)) name = "${suffix++}-$base"
                return name
            }
            knownFiles().forEach { file ->
                val parent = file.parentFile?.name.orEmpty()
                val baseName = when {
                    file.name.startsWith("adapter-run") && parent.isNotEmpty() -> "adapters/$parent/${file.name}"
                    file.parentFile == sessionDir -> "session/${file.name}"
                    else -> file.name
                }
                val name = uniqueName(baseName)
                if (file.name.equals("translation.db", true)) {
                    val textName = uniqueName("translation.db.txt")
                    zip.putNextEntry(ZipEntry(textName))
                    zip.write("size=${file.length()} bytes".toByteArray())
                    zip.closeEntry()
                    return@forEach
                }
                if (file.name.equals("settings.json", true)) {
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

            zip.putNextEntry(ZipEntry(uniqueName("environment.txt")))
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
