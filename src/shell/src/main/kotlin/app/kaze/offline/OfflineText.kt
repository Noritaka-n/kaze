package app.kaze.offline

import app.kaze.data.Engine
import app.kaze.data.Game
import app.kaze.translate.SettingsStore
import java.io.File


enum class TextRoute(val label: String) {
    MV("MV/MZ data/*.json"),
    RGSS("RGSS Data/*.rxdata"),
    UNSUPPORTED("未支持的引擎"),
}

data class TextScan(
    val route: TextRoute,
    val result: ScanResult,
)






object OfflineText {

    fun detect(gameDir: File): TextRoute {
        
        
        if (RgssText.isRgssGame(gameDir) || RgssText.archiveFile(gameDir) != null) return TextRoute.RGSS
        val dir = MvText.dataDir(gameDir) ?: return TextRoute.UNSUPPORTED
        val hasJson = dir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            ?.isNotEmpty() == true
        return if (hasJson) TextRoute.MV else TextRoute.UNSUPPORTED
    }

    fun routeFor(game: Game): TextRoute = when (game.engine) {
        Engine.RMMV, Engine.RMMZ -> TextRoute.MV
        Engine.RGSS -> if (detect(File(game.path)) == TextRoute.MV) TextRoute.MV else TextRoute.RGSS
        else -> detect(File(game.path))
    }

    fun routeFor(gameDir: File): TextRoute = detect(gameDir)

    fun scan(gameDir: File, route: TextRoute = detect(gameDir)): TextScan = when (route) {
        TextRoute.MV -> TextScan(route, MvText.scan(gameDir))
        TextRoute.RGSS -> TextScan(route, RgssText.scan(gameDir))
        TextRoute.UNSUPPORTED -> TextScan(route, ScanResult(emptyList(), emptyMap(), 0))
    }

    fun apply(gameDir: File, translations: Map<String, String>, route: TextRoute = detect(gameDir)): ApplyReport {
        if (SettingsStore.current.readOnly) {
            return ApplyReport(0, 0, listOf("只读模式已开启（设置 → 安全），未写入任何文件"))
        }
        return when (route) {
            TextRoute.MV -> {
                val (replaced, files) = MvText.apply(gameDir, translations)
                ApplyReport(replaced, files)
            }

            TextRoute.RGSS -> RgssText.apply(gameDir, translations)
            TextRoute.UNSUPPORTED -> ApplyReport(0, 0, listOf("该引擎还没有文件读写支持"))
        }
    }

    fun restore(gameDir: File, route: TextRoute = detect(gameDir)): Pair<Int, Boolean> {
        if (SettingsStore.current.readOnly) return 0 to false
        return when (route) {
            TextRoute.MV -> MvText.restore(gameDir)
            TextRoute.RGSS -> RgssText.restore(gameDir)
            TextRoute.UNSUPPORTED -> 0 to false
        }
    }

    
    fun unsupportedReason(game: Game): String = when (game.engine) {
        Engine.RMMV, Engine.RMMZ, Engine.RGSS -> "游戏目录里没有找到文本数据文件"
        Engine.OTHER -> "仅支持 RPG Maker MZ / MV / XP / VX / VX Ace"
    }
}
