package app.kaze.data

import java.io.File






object CoverFinder {
    private const val MIN_BYTES = 2_000L
    private val extensions = setOf("png", "jpg", "jpeg", "webp")

    fun find(gameDir: File, engine: Engine, launcher: File? = null): File? {
        if (!gameDir.isDirectory) return null
        return candidates(gameDir, engine, launcher).firstOrNull { file ->
            isImage(file) && file.length() >= MIN_BYTES && CoverSource.isUsable(file)
        }
    }

    




    private fun isImage(file: File): Boolean {
        if (!file.isFile) return false
        val extension = file.name.substringAfterLast('.', "").lowercase().removeSuffix("_")
        return extension in extensions
    }

    private fun candidates(gameDir: File, engine: Engine, launcher: File?): List<File> {
        val out = mutableListOf<File>()
        when (engine) {
            Engine.RMMV, Engine.RMMZ -> {
                listOf(gameDir, File(gameDir, "www")).forEach { root ->
                    largestImage(File(root, "img/titles1"))?.let(out::add)
                    largestImage(File(root, "img/titles2"))?.let(out::add)
                }
                
                
                ExeIcon.find(launcher)?.let(out::add)
                listOf(gameDir, File(gameDir, "www")).forEach { root ->
                    out += File(root, "icon/icon.png")
                }
            }

            Engine.RGSS -> {
                largestImage(File(gameDir, "Graphics/Titles1"))?.let(out::add)
                largestImage(File(gameDir, "Graphics/Titles"))?.let(out::add)
                ExeIcon.find(launcher)?.let(out::add)
            }

            Engine.OTHER -> Unit
        }

        out += File(gameDir, "cover.png")
        out += File(gameDir, "cover.jpg")
        out += File(gameDir, "icon.png")
        return out
    }

    
    private fun largestImage(dir: File): File? = dir.listFiles()
        ?.filter { isImage(it) }
        ?.maxByOrNull { it.length() }
}
