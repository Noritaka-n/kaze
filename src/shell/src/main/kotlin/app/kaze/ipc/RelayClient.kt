package app.kaze.ipc

import app.kaze.proto.Envelope
import app.kaze.proto.Hello
import app.kaze.proto.Role
import app.kaze.proto.Welcome
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.atomic.AtomicLong





class RelayClient(private val transport: NamedPipeTransport) : Closeable {
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
        val reply = receive() ?: throw IllegalStateException("relay closed during handshake")
        if (reply.bodyCase == Envelope.BodyCase.ERROR) {
            throw IllegalStateException("relay rejected: ${reply.error.code} ${reply.error.message}")
        }
        val value = reply.welcome
        welcome = value
        return value
    }

    fun send(envelope: Envelope) {
        transport.write(KzCodec.encode(envelope.msgId, typeOf(envelope), envelope.toByteArray()))
    }

    fun available(): Int = transport.available()

    fun receive(): Envelope? {
        val header = try {
            transport.readExact(KzCodec.HEADER)
        } catch (_: EOFException) {
            return null
        }
        val decoded = KzCodec.decodeHeader(header)
        val payload = if (decoded.length == 0) ByteArray(0) else transport.readExact(decoded.length)
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
}
