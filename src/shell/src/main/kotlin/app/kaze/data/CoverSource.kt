package app.kaze.data

import com.google.gson.JsonParser
import java.io.File












object CoverSource {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val gif = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte())
    private val bmp = byteArrayOf('B'.code.toByte(), 'M'.code.toByte())

    
    fun bytes(file: File): ByteArray? {
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        val body = if (isRpgmv(raw)) decrypt(raw, file) ?: return null else raw
        return if (looksLikeImage(body)) body else null
    }

    
    fun isUsable(file: File): Boolean {
        if (!file.isFile) return false
        val head = readHead(file, 64) ?: return false
        if (!isRpgmv(head)) return looksLikeImage(head)
        val key = keyFor(file) ?: return false
        val body = head.copyOfRange(16, head.size)
        for (index in 0 until minOf(16, body.size, key.size)) {
            body[index] = (body[index].toInt() xor key[index].toInt()).toByte()
        }
        return looksLikeImage(body)
    }

    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (startsWith(bytes, png) || startsWith(bytes, jpeg) ||
            startsWith(bytes, gif) || startsWith(bytes, bmp)
        ) {
            return true
        }
        return bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    }

    private fun isRpgmv(bytes: ByteArray): Boolean =
        bytes.size >= 16 && String(bytes, 0, 5, Charsets.US_ASCII) == "RPGMV"

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { bytes[it] == prefix[it] }
    }

    private fun readHead(file: File, count: Int): ByteArray? = runCatching {
        file.inputStream().use { stream -> stream.readNBytes(count) }
    }.getOrNull()

    private fun decrypt(raw: ByteArray, file: File): ByteArray? {
        val key = keyFor(file) ?: return null
        if (raw.size <= 16) return null
        val body = raw.copyOfRange(16, raw.size)
        for (index in 0 until minOf(16, body.size, key.size)) {
            body[index] = (body[index].toInt() xor key[index].toInt()).toByte()
        }
        return body
    }

    
    private fun keyFor(image: File): ByteArray? {
        var dir = image.parentFile
        repeat(6) {
            val current = dir ?: return null
            for (relative in listOf("data/System.json", "www/data/System.json")) {
                val system = File(current, relative)
                if (!system.isFile) continue
                val json = runCatching {
                    JsonParser.parseString(system.readText(Charsets.UTF_8)).asJsonObject
                }.getOrNull() ?: continue
                val hex = json.get("encryptionKey")
                    ?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                hexToBytes(hex)?.takeIf { it.size >= 16 }?.let { return it }
            }
            dir = current.parentFile
        }
        return null
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length < 32 || hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}
