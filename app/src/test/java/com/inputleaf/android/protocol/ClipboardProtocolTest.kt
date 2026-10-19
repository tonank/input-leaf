package com.inputleaf.android.protocol

import com.google.common.truth.Truth.assertThat
import com.inputleaf.android.model.InputLeapEvent
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ProtocolException

class ClipboardProtocolTest {

    @Test fun `marshall writes count then format id, length and bytes, big-endian`() {
        val marshalled = ClipboardCodec.marshall(mapOf(ClipboardCodec.FORMAT_TEXT to "hi".toByteArray()))

        assertThat(marshalled).isEqualTo(
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2, 'h'.code.toByte(), 'i'.code.toByte()),
        )
    }

    @Test fun `unmarshall reverses marshall with several formats`() {
        val formats = mapOf(
            ClipboardCodec.FORMAT_TEXT to "plain ✓".toByteArray(),
            ClipboardCodec.FORMAT_HTML to "<b>plain ✓</b>".toByteArray(),
        )

        val parsed = ClipboardCodec.unmarshall(ClipboardCodec.marshall(formats))

        assertThat(parsed.keys).containsExactly(ClipboardCodec.FORMAT_TEXT, ClipboardCodec.FORMAT_HTML)
        assertThat(parsed[ClipboardCodec.FORMAT_TEXT]!!.toString(Charsets.UTF_8)).isEqualTo("plain ✓")
        assertThat(parsed[ClipboardCodec.FORMAT_HTML]!!.toString(Charsets.UTF_8)).isEqualTo("<b>plain ✓</b>")
    }

    @Test fun `unmarshall rejects a format longer than the data`() {
        val truncated = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 9, 1)

        assertThrows(ProtocolException::class.java) { ClipboardCodec.unmarshall(truncated) }
    }

    @Test fun `chunks start with the decimal size and end empty`() {
        val data = ByteArray(ClipboardCodec.CHUNK_SIZE + 10) { it.toByte() }

        val chunks = ClipboardCodec.chunks(data)

        assertThat(chunks.map { it.first }).containsExactly(
            ClipboardCodec.MARK_START, ClipboardCodec.MARK_CHUNK, ClipboardCodec.MARK_CHUNK, ClipboardCodec.MARK_END,
        ).inOrder()
        assertThat(chunks[0].second.toString(Charsets.US_ASCII)).isEqualTo(data.size.toString())
        assertThat(chunks[1].second.size + chunks[2].second.size).isEqualTo(data.size)
        assertThat(chunks[3].second).isEmpty()
    }

    @Test fun `assembler rebuilds chunked data and rejects a size mismatch`() {
        val data = ByteArray(ClipboardCodec.CHUNK_SIZE * 2 + 1) { (it % 7).toByte() }
        val assembler = ClipboardAssembler()

        val results = ClipboardCodec.chunks(data).map { (mark, bytes) -> assembler.accept(mark, bytes) }

        assertThat(results.dropLast(1).all { it == null }).isTrue()
        assertThat(results.last()).isEqualTo(data)

        assembler.accept(ClipboardCodec.MARK_START, "5".toByteArray())
        assembler.accept(ClipboardCodec.MARK_CHUNK, byteArrayOf(1, 2, 3))
        assertThat(assembler.accept(ClipboardCodec.MARK_END, byteArrayOf())).isNull()
    }

    @Test fun `assembler ignores chunks without a start`() {
        val assembler = ClipboardAssembler()

        assembler.accept(ClipboardCodec.MARK_CHUNK, byteArrayOf(1))

        assertThat(assembler.accept(ClipboardCodec.MARK_END, byteArrayOf())).isNull()
    }

    @Test fun `assembler drops a transfer whose start size is not usable`() {
        val tooBig = (ClipboardCodec.MAX_CLIPBOARD_BYTES + 1).toString()
        for (size in listOf("abc", "-1", tooBig)) {
            val assembler = ClipboardAssembler()

            assembler.accept(ClipboardCodec.MARK_START, size.toByteArray())
            assembler.accept(ClipboardCodec.MARK_CHUNK, byteArrayOf(1))

            assertThat(assembler.accept(ClipboardCodec.MARK_END, byteArrayOf())).isNull()
        }
    }

    @Test fun `an unknown mark abandons the transfer in progress`() {
        val assembler = ClipboardAssembler()
        assembler.accept(ClipboardCodec.MARK_START, "1".toByteArray())

        assertThat(assembler.accept(42, byteArrayOf(1))).isNull()
        assembler.accept(ClipboardCodec.MARK_CHUNK, byteArrayOf(1))

        assertThat(assembler.accept(ClipboardCodec.MARK_END, byteArrayOf())).isNull()
    }

    @Test fun `parser reads chunked DCLP and CCLP`() {
        val chunk = byteArrayOf(9, 8, 7)
        val dclp = byteArrayOf(0) + u32(42) + byteArrayOf(ClipboardCodec.MARK_CHUNK.toByte()) + u32(chunk.size) + chunk

        val data = parse("DCLP", dclp) as InputLeapEvent.ClipboardData
        val grab = parse("CCLP", byteArrayOf(1) + u32(7))

        assertThat(data.id).isEqualTo(0)
        assertThat(data.seqNum).isEqualTo(42)
        assertThat(data.mark).isEqualTo(ClipboardCodec.MARK_CHUNK)
        assertThat(data.data).isEqualTo(chunk)
        assertThat(grab).isEqualTo(InputLeapEvent.ClipboardGrab(1, 7))
    }

    @Test fun `parser reads pre-1_6 DCLP as one complete message`() {
        val body = byteArrayOf(1, 2)
        val parser = ProtocolParser(ByteArrayInputStream(frame("DCLP", byteArrayOf(0) + u32(3) + u32(body.size) + body)))
        parser.protocolMinor = 5

        val data = parser.readNext() as InputLeapEvent.ClipboardData

        assertThat(data.mark).isEqualTo(ClipboardCodec.MARK_COMPLETE)
        assertThat(data.data).isEqualTo(body)
    }

    @Test fun `parser rejects a DCLP whose string length disagrees with the frame`() {
        val bad = byteArrayOf(0) + u32(0) + byteArrayOf(ClipboardCodec.MARK_CHUNK.toByte()) + u32(10) + byteArrayOf(1)

        assertThrows(ProtocolException::class.java) { parse("DCLP", bad) }
    }

    @Test fun `clipboard data toString never includes the contents`() {
        val event = InputLeapEvent.ClipboardData(0, 1, ClipboardCodec.MARK_CHUNK, "secret".toByteArray())

        assertThat(event.toString()).doesNotContain("secret")
    }

    @Test fun `writer output parses back to the same messages`() {
        val out = ByteArrayOutputStream()
        val writer = ProtocolWriter(out)
        writer.writeGrabClipboard(0, 5)
        writer.writeClipboard(0, 5, ClipboardCodec.MARK_START, "3".toByteArray())

        val parser = ProtocolParser(DataInputStream(ByteArrayInputStream(out.toByteArray())))

        assertThat(parser.readNext()).isEqualTo(InputLeapEvent.ClipboardGrab(0, 5))
        val start = parser.readNext() as InputLeapEvent.ClipboardData
        assertThat(start.mark).isEqualTo(ClipboardCodec.MARK_START)
        assertThat(start.data.toString(Charsets.US_ASCII)).isEqualTo("3")
    }

    private fun parse(tag: String, payload: ByteArray) =
        ProtocolParser(ByteArrayInputStream(frame(tag, payload))).readNext()

    private fun frame(tag: String, payload: ByteArray): ByteArray {
        val body = tag.toByteArray(Charsets.US_ASCII) + payload
        return u32(body.size) + body
    }

    private fun u32(value: Int) = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )
}
