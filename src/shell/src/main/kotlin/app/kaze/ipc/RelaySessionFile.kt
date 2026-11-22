package app.kaze.ipc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class RelaySession(val pipe: String, val token: String, val pid: Int)


object RelaySessionFile {
    private val pipeRegex = Regex("\"pipe\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val tokenRegex = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val pidRegex = Regex("\"pid\"\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)

    fun path(): Path {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        return Paths.get(base, "Kaze", "session", "relay.json")
    }

    fun read(): RelaySession? {
        val file = path()
        if (!Files.exists(file)) return null

        val text = Files.readString(file)
        val pipe = pipeRegex.find(text)?.groupValues?.get(1) ?: return null
        val token = tokenRegex.find(text)?.groupValues?.get(1) ?: return null
        val pid = pidRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return RelaySession(pipe, token, pid)
    }
}
