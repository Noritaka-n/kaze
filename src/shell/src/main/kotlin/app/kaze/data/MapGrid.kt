package app.kaze.data

import java.util.Base64





data class MapGrid(
    val mapId: Int,
    val width: Int,
    val height: Int,
    private val blocked: BooleanArray,
) {
    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun isBlocked(x: Int, y: Int): Boolean =
        contains(x, y) && blocked[y * width + x]

    companion object {
        fun decode(mapId: Int, width: Int, height: Int, base64: String): MapGrid {
            val bytes = runCatching { Base64.getDecoder().decode(base64) }
                .getOrDefault(ByteArray(0))
            val total = width * height
            val bits = BooleanArray(total)
            for (index in 0 until total) {
                val byte = bytes.getOrNull(index / 8)?.toInt() ?: 0
                bits[index] = (byte and (1 shl (index % 8))) != 0
            }
            return MapGrid(mapId, width, height, bits)
        }
    }
}
