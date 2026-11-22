package app.kaze.offline










object ControlCodes {
    private val pattern = Regex(
        """\\[A-Za-z]+\[[^\]]{0,32}\]""" +
            """|\\[|!><^.{}\\]""" +
            """|%\d+\${'$'}?[sd]""" +
            """|%\d+""" +
            """|%%""",
    )

    data class Protected(val text: String, val tokens: List<String>)

    fun protect(source: String): Protected {
        if (source.isEmpty()) return Protected(source, emptyList())
        val tokens = mutableListOf<String>()
        val text = pattern.replace(source) { match ->
            tokens += match.value
            "{{${tokens.size - 1}}}"
        }
        return Protected(text, tokens)
    }

    
    fun restore(translated: String, tokens: List<String>): String? {
        if (tokens.isEmpty()) return translated
        var result = translated
        var missing = false
        tokens.forEachIndexed { index, token ->
            val marker = Regex("""\{\{\s*$index\s*}}""")
            if (!marker.containsMatchIn(result)) {
                missing = true
            } else {
                result = marker.replace(result) { token }
            }
        }
        if (missing) return null
        
        return result.replace(Regex("""\{\{\s*\d+\s*}}"""), "")
    }
}
