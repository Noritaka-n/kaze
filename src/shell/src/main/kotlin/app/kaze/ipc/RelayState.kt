package app.kaze.ipc

import app.kaze.proto.AttachRequest
import app.kaze.proto.Envelope
import app.kaze.proto.InvokeRequest
import app.kaze.proto.InvokeResult
import app.kaze.proto.TextPush
import app.kaze.proto.TextUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
        runCatching { java.nio.file.Files.writeString(logFile, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) }
    }

    var status: RelayStatus = RelayStatus.Offline
        private set

    var units: List<RelayUnit> = emptyList()
        private set

    var note: String? = null
        private set

    private var client: RelayClient? = null
    private val connecting = AtomicBoolean(false)
    private val pendingInvokes = ConcurrentHashMap<Long, (InvokeResult) -> Unit>()

    private val watchdogStarted = AtomicBoolean(false)
    private val attachRetrying = AtomicBoolean(false)

    



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
                if (status is RelayStatus.Online) continue
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
        runCatching { client?.close() }
        client = null
        status = RelayStatus.Connecting

        Thread({
            try {
                log("connect: reading session file")
                val session = RelaySessionFile.read()
                    ?: throw IllegalStateException("未找到 relay.json")
                log("connect: pipe=${session.pipe} pid=${session.pid}")
                val transport = NamedPipeTransport("\\\\.\\pipe\\${session.pipe}")
                val connection = RelayClient(transport)
                val welcome = connection.handshake(session.token, "shell-${System.nanoTime()}")
                client = connection
                log("connect: welcome session=${welcome.session} version=${welcome.relayVersion}")
                publish { status = RelayStatus.Online(welcome.session, welcome.relayVersion) }
                log("connect: attaching default adapter")
                attach("")
                Thread({ readLoop(connection) }, "kaze-relay-reader").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                log("connect failed: ${t.javaClass.simpleName}: ${t.message}")
                publish { status = RelayStatus.Failed(t.message ?: t.javaClass.simpleName) }
            } finally {
                connecting.set(false)
            }
        }, "kaze-relay-connect").apply {
            isDaemon = true
            start()
        }
    }

    fun attach(adapter: String = "") {
        val connection = client
        if (connection == null) {
            note = "relay 未连接"
            return
        }
        Thread({
            try {
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
                publish { note = "attach 失败：${t.message}" }
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
        if (connection == null) {
            onResult(InvokeResult.newBuilder().setOk(false).setDetail("relay 未连接").build())
            return
        }

        val msgId = connection.nextId()
        pendingInvokes[msgId] = onResult

        
        Thread({
            Thread.sleep(8000)
            val pending = pendingInvokes.remove(msgId)
            pending?.invoke(
                InvokeResult.newBuilder().setOk(false).setDetail("超时：适配器未响应").build()
            )
        }, "kaze-relay-invoke-watchdog").apply {
            isDaemon = true
            start()
        }

        Thread({
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
                pendingInvokes.remove(msgId)
                publish { note = "invoke 失败：${t.message}" }
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

        Thread({
            try {
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
            } catch (_: Throwable) {
                
            }
        }, "kaze-relay-push").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(connection: RelayClient) {
        try {
            while (true) {
                if (connection.available() < KzCodec.HEADER) {
                    Thread.sleep(8)
                    continue
                }
                val envelope = connection.receive() ?: break
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
                            
                            
                            publish { status = RelayStatus.Failed(error.message) }
                            runCatching { client?.close() }
                            client = null
                        } else {
                            scheduleAttachRetry()
                        }
                    }

                    Envelope.BodyCase.INVOKE_RESULT -> {
                        pendingInvokes.remove(envelope.msgId)?.invoke(envelope.invokeResult)
                    }

                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            log("read loop ended: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            log("read loop exited")
            publish { status = RelayStatus.Offline }
        }
    }

    private fun publish(block: () -> Unit) {
        block()
    }
}

