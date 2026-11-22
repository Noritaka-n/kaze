package app.kaze.translate

import app.kaze.ipc.RelayUnit
import app.kaze.offline.TextKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext








object UiTranslation {
    private val mutex = Mutex()

    fun key(source: String): String = "ui:" + TextKey.of("ui:$source")

    suspend fun translate(labels: List<String>, gameId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val sources = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (sources.isEmpty()) return@withContext emptyMap()

        TranslationMemory.open()
        if (!TranslationMemory.available) return@withContext emptyMap()

        mutex.withLock {
            val game = gameId.ifBlank { "ui" }
            val keys = sources.associateWith(::key)
            val output = LinkedHashMap<String, String>()

            TranslationMemory.lookupAll(keys.values.toList(), game).forEach { (key, value) ->
                sources.firstOrNull { keys[it] == key }?.let { output[it] = value }
            }

            val pending = sources.filter { it !in output }
            if (pending.isEmpty()) return@withLock output

            val settings = SettingsStore.current
            if (!TranslatorFactory.ready(settings)) return@withLock output

            val translator = TranslatorFactory.create(settings)
            val provider = TranslatorFactory.provider(settings)
            val model = TranslatorFactory.model(settings)
            try {
                translator.handshake()
                pending.chunked(40).forEach { part ->
                    val units = part.map { source ->
                        RelayUnit(
                            key = keys.getValue(source),
                            source = source,
                            speaker = "",
                            scene = "",
                            target = "",
                        )
                    }
                    val translated = translator.translate(units)
                    part.forEach { source ->
                        val value = translated[keys.getValue(source)]?.trim().orEmpty()
                        if (value.isEmpty()) return@forEach
                        output[source] = value
                        TranslationMemory.store(
                            key = keys.getValue(source),
                            source = source,
                            target = value,
                            provider = provider,
                            model = model,
                            game = game,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                
                
            }
            output
        }
    }
}
