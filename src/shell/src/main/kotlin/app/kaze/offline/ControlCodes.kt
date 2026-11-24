package app.kaze.offline










object ControlCodes {
    private val pattern = Regex(
        """\\[A-Za-z]+\[[^\]]{0,32}\]""" +
            """|\\[|!><^.{}\\]""" +
            """|%\d+\${'$'}?[sd]""" +
            """|%\d+""" +
            """|%%""",
    )

    private val numericMarker = Regex("""\{\{\s*(\d+)\s*}}""")

    data class Protected(
        val text: String,
        val tokens: List<String>,
        val startIndex: Int = 0,
    )

    fun protect(source: String): Protected {
        if (source.isEmpty()) return Protected(source, emptyList())
        val tokenCount = pattern.findAll(source).count()
        val occupied = numericMarker.findAll(source)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toHashSet()
        var startIndex = 0
        while ((startIndex until startIndex + tokenCount).any(occupied::contains)) startIndex++

        val tokens = mutableListOf<String>()
        var nextIndex = startIndex
        val text = pattern.replace(source) { match ->
            tokens += match.value
            "{{${nextIndex++}}}"
        }
        return Protected(text, tokens, startIndex)
    }

    
    fun restore(translated: String, tokens: List<String>, startIndex: Int = 0): String? {
        if (tokens.isEmpty()) return translated
        var result = translated
        var invalid = false
        tokens.forEachIndexed { index, token ->
            val markerIndex = startIndex + index
            val marker = Regex("""\{\{\s*$markerIndex\s*}}""")
            if (marker.findAll(result).count() != 1) {
                invalid = true
            } else {
                result = marker.replace(result) { token }
            }
        }
        if (invalid) return null
        return result
    }
}
