package app.kaze.ipc

import app.kaze.proto.AttachRequest
import app.kaze.proto.Envelope
import app.kaze.proto.InvokeRequest
import app.kaze.proto.InvokeResult
import app.kaze.proto.TextPush
import app.kaze.proto.TextUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface RelayStatus {
    data object Offline : RelayStatus
    data object Connecting : RelayStatus
    data class Online(val session: String, val version: String) : RelayStatus
    data class Failed(val message: String) : RelayStatus
}

data class RelayUnit(
    val key: String,
    val source: String,
    val speaker: String,
    val scene: String,
    val target: String,
    val adapter: String = "",
)

object RelayState {
    private val logFile: java.nio.file.Path = run {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        java.nio.file.Paths.get(base, "Kaze", "logs", "ipc.log").also {
            runCatching { java.nio.file.Files.createDirectories(it.parent) }
        }
    }

    private fun log(message: String) {
        val line = "${java.time.LocalTime.now()} $message\n"
        runCatching {
            java.nio.file.Files.writeString(
                logFile,
                line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }

    @Volatile
    var status: RelayStatus = RelayStatus.Offline
        private set

    @Volatile
    var units: List<RelayUnit> = emptyList()
        private set

    @Volatile
    var note: String? = null
        private set

    private data class PendingInvoke(
        val generation: Long,
        val callback: (InvokeResult) -> Unit,
    )

    private val connectionLock = Any()
    private val generationCounter = AtomicLong(0)

    @Volatile
    private var client: RelayClient? = null

    @Volatile
    private var clientGeneration = 0L

    private val connecting = AtomicBoolean(false)
    private val pendingInvokes = ConcurrentHashMap<Long, PendingInvoke>()

    private val watchdogStarted = AtomicBoolean(false)
    private val attachRetrying = AtomicBoolean(false)

    private fun failedResult(detail: String): InvokeResult =
        InvokeResult.newBuilder().setOk(false).setDetail(detail).build()

    private fun deliver(pending: PendingInvoke, result: InvokeResult) {
        runCatching { pending.callback(result) }.onFailure {
            log("invoke callback failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun complete(msgId: Long, pending: PendingInvoke, result: InvokeResult) {
        if (pendingInvokes.remove(msgId, pending)) deliver(pending, result)
    }

    private fun failPending(generation: Long? = null, detail: String) {
        val failed = mutableListOf<PendingInvoke>()
        pendingInvokes.forEach { (msgId, pending) ->
            if ((generation == null || pending.generation == generation) &&
                pendingInvokes.remove(msgId, pending)
            ) {
                failed += pending
            }
        }
        failed.forEach { deliver(it, failedResult(detail)) }
    }

    private fun isCurrent(connection: RelayClient, generation: Long): Boolean =
        generationCounter.get() == generation && client === connection && clientGeneration == generation

    private fun disconnect(
        connection: RelayClient,
        generation: Long,
        detail: String,
        resultingStatus: RelayStatus = RelayStatus.Offline,
    ): Boolean {
        val owned = synchronized(connectionLock) {
            if (!isCurrent(connection, generation)) {
                false
            } else {
                client = null
                status = resultingStatus
                true
            }
        }
        if (!owned) return false
        runCatching { connection.close() }
        failPending(generation, detail)
        return true
    }

    private fun scheduleAttachRetry() {
        if (!attachRetrying.compareAndSet(false, true)) return
        Thread({
            repeat(20) {
                Thread.sleep(3000)
                if (!attachRetrying.get()) return@Thread
                if (status !is RelayStatus.Online || client == null) {
                    attachRetrying.set(false)
                    return@Thread
                }
                log("attach retry")
                attach("")
            }
            attachRetrying.set(false)
        }, "kaze-attach-retry").apply {
            isDaemon = true
            start()
        }
    }

    fun ensureWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        Thread({
            var lastAttempt = 0L
            while (true) {
                Thread.sleep(2000)
                if (status is RelayStatus.Online || status is RelayStatus.Connecting) continue
                val now = System.currentTimeMillis()
                if (now - lastAttempt < 3000) continue
                lastAttempt = now
                log("watchdog: reconnecting (status=$status)")
                connectAsync()
            }
        }, "kaze-relay-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun connectAsync() {
        ensureWatchdog()
        if (!connecting.compareAndSet(false, true)) return

        val generation: Long
        val previous: RelayClient?
        synchronized(connectionLock) {
            generation = generationCounter.incrementAndGet()
            previous = client
            client = null
            clientGeneration = generation
            status = RelayStatus.Connecting
        }
        runCatching { previous?.close() }
        failPending(detail = "relay 正在重连")

        Thread({
            var connection: RelayClient? = null
            var published = false
            try {
                log("connect: reading session file")
                val session = RelaySessionFile.read()
                    ?: throw IllegalStateException("未找到 relay.json")
                log("connect: pipe=${session.pipe} pid=${session.pid}")
                val transport = NamedPipeTransport("\\\\.\\pipe\\${session.pipe}")
                val newConnection = RelayClient(transport)
                connection = newConnection
                val welcome = newConnection.handshake(session.token, "shell-${System.nanoTime()}")

                val accepted = synchronized(connectionLock) {
                    if (generationCounter.get() != generation) {
                        false
                    } else {
                        client = newConnection
                        clientGeneration = generation
                        published = true
                        status = RelayStatus.Online(welcome.session, welcome.relayVersion)
                        true
                    }
                }
                if (!accepted) {
                    runCatching { newConnection.close() }
                    return@Thread
                }

                log("connect: welcome session=${welcome.session} version=${welcome.relayVersion}")
                log("connect: attaching default adapter")
                attach("")
                Thread({ readLoop(newConnection, generation) }, "kaze-relay-reader").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                log("connect failed: ${t.javaClass.simpleName}: ${t.message}")
                val currentConnection = connection
                if (published && currentConnection != null) {
                    disconnect(currentConnection, generation, "relay 连接失败：${t.message}")
                }
                synchronized(connectionLock) {
                    if (generationCounter.get() == generation) {
                        status = RelayStatus.Failed(t.message ?: t.javaClass.simpleName)
                    }
                }
            } finally {
                if (!published) runCatching { connection?.close() }
                connecting.set(false)
            }
        }, "kaze-relay-connect").apply {
            isDaemon = true
            start()
        }
    }

    fun attach(adapter: String = "") {
        val connection = client
        val generation = clientGeneration
        if (connection == null || !isCurrent(connection, generation)) {
            note = "relay 未连接"
            return
        }
        Thread({
            try {
                if (!isCurrent(connection, generation)) return@Thread
                log("attach sending adapter=$adapter")
                connection.send(
                    Envelope.newBuilder()
                        .setMsgId(connection.nextId())
                        .setAttach(
                            AttachRequest.newBuilder()
                                .setAdapter(adapter)
                                .setGameId("ui")
                        )
                        .build()
                )
                log("attach sent adapter=$adapter")
            } catch (t: Throwable) {
                log("attach failed: ${t.javaClass.simpleName}: ${t.message}")
                if (isCurrent(connection, generation)) {
                    publish { note = "attach 失败：${t.message}" }
                }
                disconnect(connection, generation, "attach 发送失败：${t.message}")
            }
        }, "kaze-relay-attach").apply {
            isDaemon = true
            start()
        }
    }

    fun invoke(
        op: String,
        argsJson: String = "{}",
        adapter: String = "",
        onResult: (InvokeResult) -> Unit = {},
    ) {
        val connection = client
        val generation = clientGeneration
        if (connection == null || !isCurrent(connection, generation)) {
            onResult(failedResult("relay 未连接"))
            return
        }

        val msgId = connection.nextId()
        val pending = PendingInvoke(generation, onResult)
        pendingInvokes[msgId] = pending

        Thread({
            Thread.sleep(8000)
            complete(msgId, pending, failedResult("超时：适配器未响应"))
        }, "kaze-relay-invoke-watchdog").apply {
            isDaemon = true
            start()
        }

        Thread({
            if (!isCurrent(connection, generation)) {
                complete(msgId, pending, failedResult("relay 已重连"))
                return@Thread
            }
            try {
                connection.send(
                    Envelope.newBuilder()
                        .setMsgId(msgId)
                        .setInvokeRequest(
                            InvokeRequest.newBuilder()
                                .setAdapter(adapter)
                                .setOp(op)
                                .setArgsJson(argsJson)
                        )
                        .build()
                )
            } catch (t: Throwable) {
                complete(msgId, pending, failedResult("invoke 发送失败：${t.message}"))
                if (isCurrent(connection, generation)) {
                    publish { note = "invoke 失败：${t.message}" }
                }
                disconnect(connection, generation, "invoke 发送失败：${t.message}")
            }
        }, "kaze-relay-invoke").apply {
            isDaemon = true
            start()
        }
    }

    fun push(unit: RelayUnit, target: String) = pushAll(listOf(unit to target))

    fun pushAll(translations: List<Pair<RelayUnit, String>>) {
        if (translations.isEmpty()) return
        val connection = client ?: return
        val generation = clientGeneration
        if (!isCurrent(connection, generation)) return

        Thread({
            try {
                if (!isCurrent(connection, generation)) return@Thread
                val adapter = translations.first().first.adapter.ifBlank { "fake" }
                val builder = TextPush.newBuilder().setAdapter(adapter)
                translations.forEach { (unit, target) ->
                    builder.addUnits(TextUnit.newBuilder().setKey(unit.key).setTarget(target))
                }

                val env = Envelope.newBuilder()
                    .setMsgId(connection.nextId())
                    .setTextPush(builder)
                    .build()
                connection.send(env)
            } catch (t: Throwable) {
                disconnect(connection, generation, "text push 发送失败：${t.message}")
            }
        }, "kaze-relay-push").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(connection: RelayClient, generation: Long) {
        try {
            while (isCurrent(connection, generation)) {
                if (connection.available() < KzCodec.HEADER) {
                    Thread.sleep(8)
                    continue
                }
                val envelope = connection.receive(RelayClient.FRAME_READ_TIMEOUT_MS) ?: break
                if (!isCurrent(connection, generation)) break
                log("recv ${envelope.bodyCase}")
                when (envelope.bodyCase) {
                    Envelope.BodyCase.TEXT_BATCH -> {
                        val adapter = envelope.textBatch.adapter
                        val incoming = envelope.textBatch.unitsList.map {
                            RelayUnit(it.key, it.source, it.speaker, it.scene, it.target, adapter)
                        }
                        publish {
                            val known = units.associateBy { it.key }
                            val merged = incoming.map { unit ->
                                val hit = known[unit.key]
                                if (hit != null && hit.target.isNotBlank()) unit.copy(target = hit.target) else unit
                            }
                            val freshKeys = merged.map { it.key }.toSet()
                            units = (merged + units.filter { it.key !in freshKeys }).take(60)
                        }
                    }

                    Envelope.BodyCase.ATTACH_RESULT -> {
                        val result = envelope.attachResult
                        if (result.ok) {
                            attachRetrying.set(false)
                            publish {
                                note = "适配器 ${result.engine} 已连接（${result.tookMs} ms）"
                            }
                        } else {
                            publish { note = "适配器失败：${result.detail}" }
                            scheduleAttachRetry()
                        }
                    }

                    Envelope.BodyCase.ERROR -> {
                        val error = envelope.error
                        publish { note = "relay 错误 ${error.code}：${error.message}" }
                        if (error.code == 409) {
                            disconnect(
                                connection,
                                generation,
                                "relay 拒绝连接：${error.message}",
                                RelayStatus.Failed(error.message),
                            )
                        } else {
                            scheduleAttachRetry()
                        }
                    }

                    Envelope.BodyCase.INVOKE_RESULT -> {
                        val pending = pendingInvokes[envelope.msgId]
                        if (pending != null && pending.generation == generation) {
                            complete(envelope.msgId, pending, envelope.invokeResult)
                        }
                    }

                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            log("read loop ended: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            log("read loop exited generation=$generation")
            disconnect(connection, generation, "relay 连接已断开")
        }
    }

    private fun publish(block: () -> Unit) {
        block()
    }
}
