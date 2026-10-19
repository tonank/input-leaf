package com.inputleaf.android.service

import com.inputleaf.android.model.InputLeapEvent
import com.inputleaf.android.protocol.ClipboardAssembler
import com.inputleaf.android.protocol.ClipboardCodec

data class ClipboardContent(val text: String, val html: String? = null)

/**
 * Client side of clipboard sharing, following the Input Leap / Deskflow client:
 *
 * - Server → device: DCLP after an Enter carries the server clipboard, applied locally.
 * - Device → server: a local change grabs ownership with CCLP; the data follows at once
 *   when the pointer is elsewhere, otherwise on the next Leave. Leave also re-reads the
 *   device clipboard, since Android only reports changes to the foreground app or the
 *   default IME and a copy can go unnoticed.
 *
 * Every send carries the sequence number of the last Enter; the server ignores grabs and
 * data older than its current owner's. Pure Kotlin so it runs under JVM tests; callbacks
 * are invoked on the caller's thread and may block on the socket.
 */
class ClipboardSync(
    private val readLocal: () -> ClipboardContent?,
    private val applyLocal: (ClipboardContent) -> Unit,
    private val sendGrab: (id: Int, seqNum: Int) -> Unit,
    private val sendData: (id: Int, seqNum: Int, marshalled: ByteArray) -> Unit,
) {
    private val assembler = ClipboardAssembler()
    private var seqNum = 0
    private var active = false

    /** Text last exchanged with the server in either direction; local changes equal to it are echoes. */
    private var lastSynced: String? = null

    @Synchronized
    fun onEnter(seqNum: Int) {
        this.seqNum = seqNum
        active = true
    }

    @Synchronized
    fun onLeave() {
        active = false
        pushIfChanged()
    }

    /** From the platform clipboard listener. */
    @Synchronized
    fun onLocalChanged() {
        val local = readLocal() ?: return
        if (local.text.isEmpty() || local.text == lastSynced) return
        sendGrab(ClipboardCodec.CLIPBOARD_ID, seqNum)
        // While the pointer is here the server cannot use the data yet; Leave sends it.
        if (!active) send(local)
    }

    /** @return true when [event] was a clipboard message, consumed here. */
    @Synchronized
    fun onServerEvent(event: InputLeapEvent): Boolean = when (event) {
        // Another screen owns the clipboard now; its data arrives with the next Enter.
        is InputLeapEvent.ClipboardGrab -> true
        is InputLeapEvent.ClipboardData -> {
            if (event.id == ClipboardCodec.CLIPBOARD_ID) {
                assembler.accept(event.mark, event.data)?.let(::applyFromServer)
            }
            true
        }
        else -> false
    }

    private fun applyFromServer(marshalled: ByteArray) {
        val formats = runCatching { ClipboardCodec.unmarshall(marshalled) }.getOrNull() ?: return
        val text = formats[ClipboardCodec.FORMAT_TEXT]?.toString(Charsets.UTF_8) ?: return
        val html = formats[ClipboardCodec.FORMAT_HTML]?.toString(Charsets.UTF_8)
        if (text.isEmpty() || text == lastSynced) return
        lastSynced = text
        applyLocal(ClipboardContent(text, html))
    }

    private fun pushIfChanged() {
        val local = readLocal() ?: return
        if (local.text.isEmpty() || local.text == lastSynced) return
        sendGrab(ClipboardCodec.CLIPBOARD_ID, seqNum)
        send(local)
    }

    private fun send(content: ClipboardContent) {
        val formats = buildMap {
            put(ClipboardCodec.FORMAT_TEXT, content.text.toByteArray(Charsets.UTF_8))
            content.html?.let { put(ClipboardCodec.FORMAT_HTML, it.toByteArray(Charsets.UTF_8)) }
        }
        val marshalled = ClipboardCodec.marshall(formats)
        if (marshalled.size > ClipboardCodec.MAX_CLIPBOARD_BYTES) return
        sendData(ClipboardCodec.CLIPBOARD_ID, seqNum, marshalled)
        lastSynced = content.text
    }
}
