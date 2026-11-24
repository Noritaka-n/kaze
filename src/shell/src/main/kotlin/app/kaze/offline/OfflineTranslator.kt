package app.kaze.offline

import app.kaze.ipc.RelayUnit
import app.kaze.translate.SettingsStore
import app.kaze.translate.TranslationMemory
import app.kaze.translate.TranslatorFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout





object OfflineTranslator {
    private val stateLock = Any()

    @Volatile
    var busy: Boolean = false
        private set

    @Volatile
    var done: Int = 0
        private set

    @Volatile
    var total: Int = 0
        private set

    @Volatile
    var message: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun reset() = synchronized(stateLock) {
        if (busy) return@synchronized
        done = 0
        total = 0
        message = ""
        lastError = null
    }

    suspend fun run(
        entries: List<TextEntry>,
        gameId: String,
        ignoreMemory: Boolean = false,
    ): Map<String, String> {
        TranslationMemory.open()
        val settings = SettingsStore.current
        
        
        val ordered = entries.filter { it.text.isNotBlank() }
        val unique = LinkedHashMap<String, TextEntry>()
        ordered.forEach { unique.putIfAbsent(it.text, it) }
        val position = HashMap<String, Int>(ordered.size)
        ordered.forEachIndexed { index, entry -> position[entry.locator] = index }

        
        
        fun sceneKey(locator: String): String {
            val file = locator.substringBefore(':')
            val rest = locator.substringAfter(':', "")
            val event = rest.substringBefore('/')
            return if (event.startsWith("event")) "$file:$event" else file
        }
        val sceneText = HashMap<String, String>()
        ordered.forEach { entry ->
            val key = sceneKey(entry.locator)
            val current = sceneText[key].orEmpty()
            if (current.length < 900) {
                sceneText[key] = if (current.isEmpty()) entry.text else current + "\n" + entry.text
            }
        }

        synchronized(stateLock) {
            if (busy) return emptyMap()
            if (unique.isEmpty()) {
                total = 0
                done = 0
                message = "没有可翻文本"
                lastError = null
                return emptyMap()
            }
            total = unique.size
            done = 0
            message = "准备中…"
            lastError = null
            busy = true
        }
        val output = LinkedHashMap<String, String>()
        try {
            
            val keys = unique.keys.associateWith { TextKey.of(it) }
            val hits = if (ignoreMemory) emptyMap() else TranslationMemory.lookupAll(keys.values.toList(), gameId)
            keys.forEach { (source, key) ->
                hits[key]?.let {
                    output[source] = it
                    done++
                }
            }
            message = "记忆库命中 ${output.size}"

            
            val misses = unique.keys.filter { it !in output }
            if (!ignoreMemory && misses.isNotEmpty()) {
                val byNorm = TranslationMemory.lookupBySource(misses, gameId)
                misses.forEach { source ->
                    byNorm[TranslationMemory.normalize(source)]?.let {
                        output[source] = it
                        done++
                    }
                }
            }

            
            val pendingEntries = ordered.filter { it.text !in output }
            val pending = unique.keys.filter { it !in output }
            if (pending.isEmpty()) {
                message = "全部来自记忆库（$done/$total）"
                return output
            }

            if (!TranslatorFactory.ready(settings)) {
                message = "未配置大模型 API：设置 → 翻译模型 → 填 Base URL、模型和 API Key"
                return output
            }

            val translator: app.kaze.translate.Translator = TranslatorFactory.create(settings)
            val providerName = TranslatorFactory.provider(settings)
            val modelName = TranslatorFactory.model(settings)
            
            
            try {
                message = "连接服务器…"
                translator.handshake()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                val reason = error.message ?: error.javaClass.simpleName
                lastError = "握手失败：$reason"
                message = "翻译未开始：$lastError"
                return output
            }

            val batch = settings.batchSize.coerceIn(4, 5000)
            val glossary = TranslationMemory.glossary(gameId)

            val started = System.currentTimeMillis()
            val lock = Any()
            val remaining = unique.values.filter { it.text !in output }
            val parallel = settings.parallel.coerceIn(1, 12)
            val permits = Semaphore(parallel)
            if (remaining.isNotEmpty()) {
                coroutineScope {
                    val chunks = remaining.chunked(batch)
                    val work: suspend (List<TextEntry>) -> Unit = work@ { chunk ->
                        suspend fun translateChunk(part: List<TextEntry>) {
                            if (part.isEmpty()) return
                            
                            
                            
                            val sentScenes = HashSet<String>()
                            val rawByKey = HashMap<String, String>()
                            val protectedByKey = HashMap<String, ControlCodes.Protected>()
                            val units = part.map { entry ->
                                val key = sceneKey(entry.locator)
                                val scene = if (sentScenes.add(key)) {
                                    sceneText[key].orEmpty().take(600)
                                } else {
                                    ""
                                }
                                val textKey = TextKey.of(entry.text)
                                val protected = ControlCodes.protect(entry.text)
                                rawByKey[textKey] = entry.text
                                protectedByKey[textKey] = protected
                                RelayUnit(
                                    key = textKey,
                                    source = protected.text,
                                    speaker = "",
                                    scene = scene,
                                    target = "",
                                    adapter = "offline",
                                )
                            }
                            val context = HashMap<String, Pair<String, String>>()
                            part.forEachIndexed { index, entry ->
                                val at = position[entry.locator] ?: return@forEachIndexed
                                val prev = ordered.getOrNull(at - 1)?.text.orEmpty()
                                val next = ordered.getOrNull(at + 1)?.text.orEmpty()
                                if (prev.isNotBlank() || next.isNotBlank()) {
                                    context[units[index].key] = prev to next
                                }
                            }

                            val outcome = permits.withPermit {
                                runCatching {
                                    withTimeout(180_000) {
                                        translator.translate(units, glossary, context)
                                    }
                                }
                            }
                            val failure = outcome.exceptionOrNull()
                            if (failure != null) {
                                if (failure is CancellationException && failure !is TimeoutCancellationException) {
                                    throw failure
                                }
                                lastError = "${failure.javaClass.simpleName}: ${failure.message ?: ""}".take(200)
                                return
                            }
                            outcome.getOrNull()?.forEach { (key, target) ->
                                if (target.isBlank()) return@forEach
                                val raw = rawByKey[key] ?: units.firstOrNull { it.key == key }?.source
                                    ?: return@forEach
                                val protected = protectedByKey[key] ?: return@forEach
                                val restored = ControlCodes.restore(
                                    target,
                                    protected.tokens,
                                    protected.startIndex,
                                )
                                if (restored == null) {
                                    
                                    
                                    return@forEach
                                }
                                TranslationMemory.store(
                                    key, raw, restored, providerName, modelName, gameId,
                                )
                                synchronized(lock) { output[raw] = restored }
                            }
                            synchronized(lock) {
                                done = output.size
                                val seconds = (System.currentTimeMillis() - started) / 1000.0
                                val rate = if (seconds > 0) done / seconds else 0.0
                                message = "已译 $done/$total · %.1f 行/秒".format(rate)
                            }
                        }
                        translateChunk(chunk)
                    }
                    chunks.map { chunk -> async(Dispatchers.IO) { work(chunk) } }
                        .forEach { it.await() }
                }
            }
            message = when {
                lastError != null -> "翻译失败：$done/$total · $lastError"
                else -> "完成：$done/$total（记忆库优先）"
            }
            return output
        } finally {
            synchronized(stateLock) { busy = false }
        }
    }
}

