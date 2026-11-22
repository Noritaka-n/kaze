package app.kaze.ipc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.io.Closeable
import java.io.EOFException
import java.io.IOException

private const val GENERIC_READ = 0x80000000.toInt()
private const val GENERIC_WRITE = 0x40000000
private const val OPEN_EXISTING = 3

private interface Kernel32 : Library {
    fun CreateFileW(
        fileName: WString,
        desiredAccess: Int,
        shareMode: Int,
        securityAttributes: Pointer?,
        creationDisposition: Int,
        flagsAndAttributes: Int,
        templateFile: Pointer?,
    ): Pointer?

    fun ReadFile(
        handle: Pointer,
        buffer: ByteArray,
        bytesToRead: Int,
        bytesRead: IntByReference,
        overlapped: Pointer?,
    ): Boolean

    fun WriteFile(
        handle: Pointer,
        buffer: ByteArray,
        bytesToWrite: Int,
        bytesWritten: IntByReference,
        overlapped: Pointer?,
    ): Boolean

    fun PeekNamedPipe(
        handle: Pointer,
        buffer: Pointer?,
        bufferSize: Int,
        bytesRead: IntByReference?,
        bytesAvailable: IntByReference,
        bytesLeftThisMessage: IntByReference?,
    ): Boolean

    fun CloseHandle(handle: Pointer): Boolean
}


class NamedPipeTransport(path: String) : Closeable {
    private val kernel: Kernel32 = Native.load("kernel32", Kernel32::class.java)

    private val handle: Pointer = run {
        val h = kernel.CreateFileW(
            WString(path),
            GENERIC_READ or GENERIC_WRITE,
            0,
            null,
            OPEN_EXISTING,
            0,
            null,
        )
        if (h == null || Pointer.nativeValue(h) == -1L) {
            throw IOException("cannot open pipe $path (win32=${Native.getLastError()})")
        }
        h
    }

    fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val chunk = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
            val written = IntByReference()
            if (!kernel.WriteFile(handle, chunk, chunk.size, written, null)) {
                throw IOException("WriteFile failed (win32=${Native.getLastError()})")
            }
            if (written.value <= 0) throw IOException("WriteFile wrote 0 bytes")
            offset += written.value
        }
    }

    




    fun available(): Int {
        val count = IntByReference(0)
        return if (kernel.PeekNamedPipe(handle, null, 0, null, count, null)) count.value else 0
    }

    fun readExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val chunk = ByteArray(length - offset)
            val read = IntByReference()
            if (!kernel.ReadFile(handle, chunk, chunk.size, read, null)) {
                throw IOException("ReadFile failed (win32=${Native.getLastError()})")
            }
            if (read.value == 0) throw EOFException("pipe closed")
            System.arraycopy(chunk, 0, result, offset, read.value)
            offset += read.value
        }
        return result
    }

    override fun close() {
        try {
            kernel.CloseHandle(handle)
        } catch (_: Throwable) {
            
        }
    }
}
