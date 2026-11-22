package app.kaze.translate




object TranslatorFactory {

    fun create(settings: TranslationSettings): Translator = OpenAiCompatibleTranslator(
        baseUrl = settings.baseUrl,
        apiKey = SettingsStore.apiKey(),
        model = settings.model,
        targetLang = settings.targetLang,
        temperature = settings.temperature,
        timeoutSec = settings.requestTimeoutSec,
        tone = settings.tone,
        maxUnits = settings.batchSize.coerceIn(1, 32),
    )

    fun ready(settings: TranslationSettings): Boolean {
        if (settings.baseUrl.isBlank() || settings.model.isBlank()) return false
        return SettingsStore.apiKey().isNotBlank()
    }

    fun provider(settings: TranslationSettings): String = "api"

    fun model(settings: TranslationSettings): String = settings.model

    fun label(settings: TranslationSettings): String =
        "大模型 API · ${settings.model.ifBlank { "未配置模型" }}"
}
