package app.kaze.ipc

import app.kaze.proto.Envelope
import app.kaze.proto.Hello
import app.kaze.proto.Role
import app.kaze.proto.Welcome
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.atomic.AtomicLong





class RelayClient(private val transport: NamedPipeTransport) : Closeable {
    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val FRAME_READ_TIMEOUT_MS = 10_000L
    }

    private val seq = AtomicLong(0)

    @Volatile
    var welcome: Welcome? = null
        private set

    fun nextId(): Long = seq.incrementAndGet()

    fun handshake(token: String, nonce: String): Welcome {
        val hello = Envelope.newBuilder()
            .setMsgId(nextId())
            .setHello(
                Hello.newBuilder()
                    .setProto(1)
                    .setRole(Role.ROLE_SHELL)
                    .setPid(ProcessHandle.current().pid().toInt())
                    .setToken(token)
                    .setNonce(nonce)
            )
            .build()

        send(hello)
        val reply = receive(HANDSHAKE_TIMEOUT_MS)
            ?: throw IllegalStateException("relay closed during handshake")
        if (reply.bodyCase == Envelope.BodyCase.ERROR) {
            throw IllegalStateException("relay rejected: ${reply.error.code} ${reply.error.message}")
        }
        if (reply.bodyCase != Envelope.BodyCase.WELCOME) {
            throw IllegalStateException("unexpected handshake reply: ${reply.bodyCase}")
        }
        val value = reply.welcome
        welcome = value
        return value
    }

    fun send(envelope: Envelope) {
        transport.write(KzCodec.encode(envelope.msgId, typeOf(envelope), envelope.toByteArray()))
    }

    fun available(): Int = transport.available()

    fun receive(timeoutMillis: Long? = null): Envelope? {
        val started = System.nanoTime()
        val header = try {
            transport.readExact(KzCodec.HEADER, timeoutMillis)
        } catch (_: EOFException) {
            return null
        }
        val decoded = KzCodec.decodeHeader(header)
        val remainingTimeout = timeoutMillis?.let {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            (it - elapsedMs).coerceAtLeast(1L)
        }
        val payload = if (decoded.length == 0) {
            ByteArray(0)
        } else {
            transport.readExact(decoded.length, remainingTimeout)
        }
        return Envelope.parseFrom(payload)
    }

    override fun close() = transport.close()

    private fun typeOf(envelope: Envelope): Int = when (envelope.bodyCase) {
        Envelope.BodyCase.HELLO -> MsgType.HELLO
        Envelope.BodyCase.WELCOME -> MsgType.WELCOME
        Envelope.BodyCase.ACK -> MsgType.ACK
        Envelope.BodyCase.ERROR -> MsgType.ERROR
        Envelope.BodyCase.GAME -> MsgType.GAME
        Envelope.BodyCase.ATTACH -> MsgType.ATTACH
        Envelope.BodyCase.ATTACH_RESULT -> MsgType.ATTACH_RESULT
        Envelope.BodyCase.TEXT_BATCH -> MsgType.TEXT_BATCH
        Envelope.BodyCase.TEXT_PUSH -> MsgType.TEXT_PUSH
        Envelope.BodyCase.CONTROL -> MsgType.CONTROL
        Envelope.BodyCase.PING -> MsgType.PING
        Envelope.BodyCase.PONG -> MsgType.PONG
        Envelope.BodyCase.INVOKE_REQUEST -> MsgType.INVOKE_REQUEST
        Envelope.BodyCase.INVOKE_RESULT -> MsgType.INVOKE_RESULT
        else -> 0
    }
}

object MsgType {
    const val HELLO = 1
    const val WELCOME = 2
    const val ACK = 3
    const val ERROR = 4
    const val GAME = 5
    const val ATTACH = 6
    const val ATTACH_RESULT = 7
    const val TEXT_BATCH = 8
    const val TEXT_PUSH = 9
    const val CONTROL = 10
    const val PING = 11
    const val PONG = 12
    const val INVOKE_REQUEST = 13
    const val INVOKE_RESULT = 14
}
