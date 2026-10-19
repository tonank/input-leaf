package com.inputleaf.android.network

import com.google.common.truth.Truth.assertThat
import com.inputleaf.android.model.InputLeapEvent
import com.inputleaf.android.protocol.ClipboardAssembler
import com.inputleaf.android.protocol.ClipboardCodec
import com.inputleaf.android.protocol.ProtocolParser
import com.inputleaf.android.protocol.ProtocolWriter
import com.inputleaf.android.service.ClipboardContent
import com.inputleaf.android.service.ClipboardSync
import com.inputleaf.android.testutil.LOOPBACK_HOST
import com.inputleaf.android.testutil.LoopbackServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Clipboard sharing over a real socket against a scripted server. The server side
 * mirrors what Deskflow sends after an Enter: the clipboard as start/chunk/end DCLP
 * messages for id 0, then the same for id 1 (the X11 selection).
 */
class InputLeapConnectionClipboardTest {

    @Test fun `server clipboard after Enter reaches the device as text`() = runBlocking {
        val listening = CountDownLatch(1)
        LoopbackServer { socket, _ ->
            performServerHandshake(socket, serverMinor = 8)
            assertThat(listening.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()
            val out = DataOutputStream(socket.outputStream)
            writeFrame(out, enterBody(seqNum = 19))
            val writer = ProtocolWriter(socket.outputStream)
            val marshalled = marshalledText("copied on windows ✓")
            for (id in listOf(ClipboardCodec.CLIPBOARD_ID, 1)) {
                for ((mark, chunk) in ClipboardCodec.chunks(marshalled)) {
                    writer.writeClipboard(id, 0, mark, chunk)
                }
            }
            // Keep the socket open until the client is done reading.
            socket.inputStream.read()
        }.use { server ->
            connection(server.port).useConnection { connection ->
                assertThat(connection.connect("android", 1920, 1080)).isInstanceOf(ConnectResult.Ok::class.java)
                val applied = CompletableDeferred<ClipboardContent>()
                val sync = ClipboardSync(
                    readLocal = { null },
                    applyLocal = { applied.complete(it) },
                    sendGrab = { _, _ -> },
                    sendData = { _, _, _ -> },
                )
                val collector = async(start = CoroutineStart.UNDISPATCHED) {
                    connection.events.takeWhile { !applied.isCompleted }.collect { event ->
                        if (!sync.onServerEvent(event) && event is InputLeapEvent.Enter) {
                            sync.onEnter(event.seqNum)
                        }
                    }
                }
                listening.countDown()

                assertThat(withTimeout(TIMEOUT_MS) { applied.await() })
                    .isEqualTo(ClipboardContent("copied on windows ✓"))
                collector.cancel()
            }
        }
    }

    @Test fun `device clipboard goes to a 1_6 server as grab then start, chunks and end`() = runBlocking {
        val text = "x".repeat(ClipboardCodec.CHUNK_SIZE + 100)
        val received = CompletableDeferred<List<InputLeapEvent>>()
        LoopbackServer { socket, _ ->
            performServerHandshake(socket, serverMinor = 8)
            received.complete(readClipboardMessages(socket.getInputStream(), protocolMinor = 6))
        }.use { server ->
            connection(server.port).useConnection { connection ->
                connection.connect("android", 1920, 1080)
                assertThat(connection.protocolMinor).isEqualTo(6)

                connection.sendClipboardGrab(ClipboardCodec.CLIPBOARD_ID, 19)
                connection.sendClipboard(ClipboardCodec.CLIPBOARD_ID, 19, marshalledText(text))

                val messages = withTimeout(TIMEOUT_MS) { received.await() }
                assertThat(messages.first()).isEqualTo(InputLeapEvent.ClipboardGrab(0, 19))
                val data = messages.drop(1).map { it as InputLeapEvent.ClipboardData }
                assertThat(data.map { it.mark }).containsExactly(
                    ClipboardCodec.MARK_START, ClipboardCodec.MARK_CHUNK,
                    ClipboardCodec.MARK_CHUNK, ClipboardCodec.MARK_END,
                ).inOrder()
                assertThat(data.map { it.seqNum }.toSet()).containsExactly(19)
                assertThat(textOf(data)).isEqualTo(text)
            }
        }
    }

    @Test fun `a pre-1_6 server gets the clipboard as one unmarked message`() = runBlocking {
        val received = CompletableDeferred<List<InputLeapEvent>>()
        LoopbackServer { socket, _ ->
            performServerHandshake(socket, serverMinor = 5)
            received.complete(readClipboardMessages(socket.getInputStream(), protocolMinor = 5, count = 2))
        }.use { server ->
            connection(server.port).useConnection { connection ->
                connection.connect("android", 1920, 1080)
                assertThat(connection.protocolMinor).isEqualTo(5)

                connection.sendClipboardGrab(ClipboardCodec.CLIPBOARD_ID, 3)
                connection.sendClipboard(ClipboardCodec.CLIPBOARD_ID, 3, marshalledText("legacy"))

                val messages = withTimeout(TIMEOUT_MS) { received.await() }
                val data = messages[1] as InputLeapEvent.ClipboardData
                assertThat(data.mark).isEqualTo(ClipboardCodec.MARK_COMPLETE)
                assertThat(textOf(listOf(data))).isEqualTo("legacy")
            }
        }
    }

    /** Reads frames until a DCLP end (or [count] messages), parsed as the server would. */
    private fun readClipboardMessages(
        input: java.io.InputStream,
        protocolMinor: Int,
        count: Int = Int.MAX_VALUE,
    ): List<InputLeapEvent> {
        val parser = ProtocolParser(DataInputStream(input)).also { it.protocolMinor = protocolMinor }
        val messages = mutableListOf<InputLeapEvent>()
        while (messages.size < count) {
            val event = parser.readNext()
            if (event !is InputLeapEvent.ClipboardGrab && event !is InputLeapEvent.ClipboardData) continue
            messages += event
            if (event is InputLeapEvent.ClipboardData && event.mark == ClipboardCodec.MARK_END) break
        }
        return messages
    }

    private fun textOf(messages: List<InputLeapEvent.ClipboardData>): String {
        val assembler = ClipboardAssembler()
        val whole = messages.firstNotNullOf { assembler.accept(it.mark, it.data) }
        return ClipboardCodec.unmarshall(whole)[ClipboardCodec.FORMAT_TEXT]!!.toString(Charsets.UTF_8)
    }

    private fun marshalledText(text: String) =
        ClipboardCodec.marshall(mapOf(ClipboardCodec.FORMAT_TEXT to text.toByteArray(Charsets.UTF_8)))

    /** CINN%2i%2i%4i%2i: x, y, sequence number, modifier mask. */
    private fun enterBody(seqNum: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.write("CINN".toByteArray())
            it.writeShort(100); it.writeShort(0)
            it.writeInt(seqNum)
            it.writeShort(0)
        }
    }.toByteArray()

    private fun connection(port: Int) = InputLeapConnection(
        ip = LOOPBACK_HOST,
        port = port,
        transportPolicy = ConnectionTransportPolicy.PLAIN_ONLY,
        onCertificate = { true },
    )

    private suspend fun <T> InputLeapConnection.useConnection(block: suspend (InputLeapConnection) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
