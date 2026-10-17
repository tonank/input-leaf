package com.inputleaf.android.service

import com.google.common.truth.Truth.assertThat
import com.inputleaf.android.model.InputLeapEvent
import com.inputleaf.android.protocol.ClipboardCodec
import com.inputleaf.android.protocol.ProtocolConstants
import org.junit.Test

class ClipboardSyncTest {

    private var local: ClipboardContent? = null
    private val applied = mutableListOf<ClipboardContent>()
    private val grabs = mutableListOf<Pair<Int, Int>>()
    private val sent = mutableListOf<Triple<Int, Int, String>>()

    private val sync = ClipboardSync(
        readLocal = { local },
        applyLocal = { applied += it; local = it },
        sendGrab = { id, seq -> grabs += id to seq },
        sendData = { id, seq, data ->
            val text = ClipboardCodec.unmarshall(data)[ClipboardCodec.FORMAT_TEXT]!!.toString(Charsets.UTF_8)
            sent += Triple(id, seq, text)
        },
    )

    @Test fun `server clipboard is applied once all chunks arrive`() {
        sync.onEnter(3)
        serverSends("from pc", seq = 0)

        assertThat(applied).containsExactly(ClipboardContent("from pc"))
    }

    @Test fun `html from the server is kept alongside the text`() {
        val marshalled = ClipboardCodec.marshall(
            mapOf(
                ClipboardCodec.FORMAT_TEXT to "bold".toByteArray(),
                ClipboardCodec.FORMAT_HTML to "<b>bold</b>".toByteArray(),
            ),
        )
        for ((mark, bytes) in ClipboardCodec.chunks(marshalled)) {
            sync.onServerEvent(InputLeapEvent.ClipboardData(0, 0, mark, bytes))
        }

        assertThat(applied).containsExactly(ClipboardContent("bold", "<b>bold</b>"))
    }

    @Test fun `the X11 selection clipboard is ignored`() {
        serverSends("selection", id = 1)

        assertThat(applied).isEmpty()
    }

    @Test fun `a copy while the pointer is here is grabbed now and sent on leave`() {
        sync.onEnter(7)
        local = ClipboardContent("from tablet")

        sync.onLocalChanged()
        assertThat(grabs).containsExactly(0 to 7)
        assertThat(sent).isEmpty()

        sync.onLeave()
        assertThat(sent).containsExactly(Triple(0, 7, "from tablet"))
    }

    @Test fun `a copy while the pointer is away is sent at once`() {
        sync.onEnter(2)
        sync.onLeave()
        local = ClipboardContent("touch copy")

        sync.onLocalChanged()

        assertThat(sent).containsExactly(Triple(0, 2, "touch copy"))
    }

    @Test fun `leave picks up a copy the listener never reported`() {
        sync.onEnter(4)
        local = ClipboardContent("unnoticed")

        sync.onLeave()

        assertThat(grabs).containsExactly(0 to 4)
        assertThat(sent).containsExactly(Triple(0, 4, "unnoticed"))
    }

    @Test fun `applying the server clipboard does not echo it back`() {
        sync.onEnter(1)
        serverSends("from pc")

        sync.onLocalChanged()
        sync.onLeave()

        assertThat(grabs).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test fun `an unchanged clipboard is not resent on every leave`() {
        sync.onEnter(1)
        local = ClipboardContent("once")
        sync.onLeave()
        sync.onEnter(2)
        sync.onLeave()

        assertThat(sent).containsExactly(Triple(0, 1, "once"))
    }

    @Test fun `empty or unreadable clipboards are not sent`() {
        sync.onEnter(1)
        sync.onLeave()
        local = ClipboardContent("")
        sync.onLocalChanged()
        sync.onLeave()

        assertThat(grabs).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test fun `sharing switched off by the server stops both directions`() {
        sync.onOptions(mapOf(ProtocolConstants.OPTION_CLIPBOARD_SHARING to 0))
        sync.onEnter(1)
        serverSends("from pc")
        local = ClipboardContent("from tablet")
        sync.onLocalChanged()
        sync.onLeave()

        assertThat(applied).isEmpty()
        assertThat(grabs).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test fun `a zero size limit also switches sharing off`() {
        sync.onOptions(mapOf(ProtocolConstants.OPTION_CLIPBOARD_SIZE to 0))
        sync.onEnter(1)
        local = ClipboardContent("from tablet")
        sync.onLeave()

        assertThat(sent).isEmpty()
    }

    @Test fun `a clipboard at or over the server limit is not sent`() {
        sync.onOptions(mapOf(ProtocolConstants.OPTION_CLIPBOARD_SIZE to 1))
        sync.onEnter(1)
        // 4-byte count + 8-byte format header + 1012 bytes = exactly the 1 KB limit.
        local = ClipboardContent("x".repeat(1024 - 12))
        sync.onLeave()
        assertThat(sent).isEmpty()

        local = ClipboardContent("x".repeat(1024 - 13))
        sync.onEnter(2)
        sync.onLeave()
        assertThat(sent.map { it.second }).containsExactly(2)
    }

    @Test fun `options keep values the server did not resend, and reset restores defaults`() {
        sync.onOptions(mapOf(ProtocolConstants.OPTION_CLIPBOARD_SHARING to 0))
        sync.onOptions(mapOf(ProtocolConstants.OPTION_CLIPBOARD_SIZE to 3072))
        sync.onEnter(1)
        local = ClipboardContent("still off")
        sync.onLeave()
        assertThat(sent).isEmpty()

        sync.onResetOptions()
        sync.onEnter(2)
        sync.onLeave()
        assertThat(sent).containsExactly(Triple(0, 2, "still off"))
    }

    @Test fun `other events pass through`() {
        assertThat(sync.onServerEvent(InputLeapEvent.KeepAlive)).isFalse()
        assertThat(sync.onServerEvent(InputLeapEvent.ClipboardGrab(0, 1))).isTrue()
    }

    private fun serverSends(text: String, id: Int = 0, seq: Int = 0) {
        val marshalled = ClipboardCodec.marshall(mapOf(ClipboardCodec.FORMAT_TEXT to text.toByteArray()))
        for ((mark, bytes) in ClipboardCodec.chunks(marshalled)) {
            assertThat(sync.onServerEvent(InputLeapEvent.ClipboardData(id, seq, mark, bytes))).isTrue()
        }
    }
}
