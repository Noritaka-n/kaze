package app.kaze.translate

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary





object Dpapi {
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }

    private interface Kernel32 : StdCallLibrary {
        fun LocalFree(pointer: Pointer): Pointer?
    }

    private interface Crypt32 : StdCallLibrary {
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
    private val kernel32: Kernel32? = runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()

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
            kernel32?.LocalFree(pointer)
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
            kernel32?.LocalFree(pointer)
        }
    }
}
