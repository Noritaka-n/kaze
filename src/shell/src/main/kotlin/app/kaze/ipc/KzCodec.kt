package app.kaze.ipc

import java.nio.ByteBuffer
import java.nio.ByteOrder





object KzCodec {
    const val MAGIC: Int = 0x00315A4B
    const val VERSION: Int = 1
    const val HEADER: Int = 20
    const val MAX_PAYLOAD: Int = 1 shl 20

    fun encode(msgId: Long, type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val buffer = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putShort(0)
        buffer.putInt(payload.size)
        buffer.putLong(msgId)
        buffer.put(payload)
        return buffer.array()
    }

    data class Header(val type: Int, val msgId: Long, val length: Int)

    fun decodeHeader(bytes: ByteArray): Header {
        require(bytes.size == HEADER) { "bad header size" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        require(magic == MAGIC) { "bad magic 0x%08X".format(magic) }
        val version = buffer.get().toInt()
        require(version == VERSION) { "unsupported version $version" }
        val type = buffer.get().toInt()
        buffer.short 
        val length = buffer.int
        require(length in 0..MAX_PAYLOAD) { "payload too large: $length" }
        val msgId = buffer.long
        return Header(type, msgId, length)
    }
}
