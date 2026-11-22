package app.kaze.translate

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference





object Dpapi {
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }

    private interface Crypt32 : Library {
        fun CryptProtectData(
            dataIn: DataBlob,
            description: WString?,
            entropy: DataBlob?,
            reserved: Pointer?,
            prompt: Pointer?,
            flags: Int,
            dataOut: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            dataIn: DataBlob,
            description: PointerByReference?,
            entropy: DataBlob?,
            reserved: Pointer?,
            prompt: Pointer?,
            flags: Int,
            dataOut: DataBlob,
        ): Boolean
    }

    private val crypt32: Crypt32? = runCatching { Native.load("crypt32", Crypt32::class.java) }.getOrNull()

    private fun toBlob(bytes: ByteArray): Pair<DataBlob, Memory> {
        val memory = Memory(bytes.size.toLong().coerceAtLeast(1))
        memory.write(0, bytes, 0, bytes.size)
        val blob = DataBlob()
        blob.cbData = bytes.size
        blob.pbData = memory
        return blob to memory
    }

    fun protect(plain: ByteArray): ByteArray? {
        val lib = crypt32 ?: return null
        val (input, _) = toBlob(plain)
        val output = DataBlob()
        val ok = lib.CryptProtectData(
            input,
            WString("Kaze API key"),
            null,
            null,
            null,
            CRYPTPROTECT_UI_FORBIDDEN,
            output,
        )
        if (!ok) return null

        val pointer = output.pbData ?: return null
        return try {
            pointer.getByteArray(0, output.cbData)
        } finally {
            Native.free(Pointer.nativeValue(pointer))
        }
    }

    fun unprotect(encrypted: ByteArray): ByteArray? {
        val lib = crypt32 ?: return null
        val (input, _) = toBlob(encrypted)
        val output = DataBlob()
        val ok = lib.CryptUnprotectData(
            input,
            null,
            null,
            null,
            null,
            CRYPTPROTECT_UI_FORBIDDEN,
            output,
        )
        if (!ok) return null

        val pointer = output.pbData ?: return null
        return try {
            pointer.getByteArray(0, output.cbData)
        } finally {
            Native.free(Pointer.nativeValue(pointer))
        }
    }
}
