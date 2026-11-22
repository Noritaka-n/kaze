package app.kaze.data

import java.io.File

enum class Engine(val label: String, val family: String) {
    RMMZ("RPG Maker MZ", "RPG Maker"),
    RMMV("RPG Maker MV", "RPG Maker"),
    RGSS("RPG Maker XP/VX/VX Ace", "RPG Maker"),

    
    OTHER("不支持的引擎", "其他"),
}

fun Game.isAvailable(): Boolean =
    engine != Engine.OTHER && path.isNotBlank() && File(path).isDirectory

data class Game(
    val id: String,
    val title: String,
    val subtitle: String,
    val engine: Engine,
    val sourceLang: String,
    val monogram: String,
    val path: String = "",
    val launcher: String = "",
    
    val cover: String = "",
    
    val lastPlayed: Long = 0L,
)
