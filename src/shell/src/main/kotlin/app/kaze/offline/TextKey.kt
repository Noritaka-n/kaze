package app.kaze.offline

import org.bouncycastle.crypto.digests.Blake2bDigest





object TextKey {
    fun of(text: String): String {
        val digest = Blake2bDigest(64)
        val bytes = text.toByteArray(Charsets.UTF_8)
        digest.update(bytes, 0, bytes.size)
        val out = ByteArray(8)
        digest.doFinal(out, 0)
        return out.joinToString("") { byte -> "%02x".format(byte) }
    }
}
