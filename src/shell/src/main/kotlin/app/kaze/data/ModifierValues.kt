package app.kaze.data









object ModifierValues {

    fun encode(value: String, type: String?): String? {
        val text = value.trim()
        return when (type?.lowercase()) {
            "bool", "boolean" -> if (isTruthy(text)) "true" else "false"
            "number", "money" -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::format)
            "string" -> quote(value)
            else -> {
                
                
                val parsed = runCatching {
                    com.google.gson.JsonParser.parseString(text)
                }.getOrNull()
                if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) text else quote(value)
            }
        }
    }

    fun isTruthy(text: String): Boolean =
        text.trim().lowercase() in setOf("true", "1", "on", "yes")

    fun quote(value: String): String = com.google.gson.Gson().toJson(value)

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
