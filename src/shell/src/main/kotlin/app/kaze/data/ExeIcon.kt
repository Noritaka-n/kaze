package app.kaze.data

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.filechooser.FileSystemView








object ExeIcon {
    fun find(exe: File?): File? {
        if (exe == null || !exe.isFile) return null
        val cacheDir = File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
            "Kaze/icons",
        )
        val cache = File(cacheDir, "${hash(exe.absolutePath)}.png")
        if (cache.isFile && cache.length() > 0) return cache
        return runCatching {
            Files.createDirectories(cacheDir.toPath())
            val icon: Icon = FileSystemView.getFileSystemView().getSystemIcon(exe, 64, 64)
            val width = icon.iconWidth.coerceAtLeast(1)
            val height = icon.iconHeight.coerceAtLeast(1)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                icon.paintIcon(null, graphics, 0, 0)
            } finally {
                graphics.dispose()
            }
            val temp = File(cacheDir, "${cache.name}.tmp")
            ImageIO.write(image, "png", temp)
            if (cache.exists()) cache.delete()
            if (!temp.renameTo(cache)) {
                temp.copyTo(cache, overwrite = true)
                temp.delete()
            }
            cache.takeIf { it.isFile && it.length() > 0 }
        }.getOrNull()
    }

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
