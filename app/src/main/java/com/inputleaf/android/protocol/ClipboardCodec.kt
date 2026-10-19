package com.inputleaf.android.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ProtocolException

/**
 * Clipboard wire format shared by Barrier, Input Leap and Deskflow.
 *
 * A clipboard is marshalled as a big-endian format count followed by
 * (format id, byte length, bytes) for each format. From protocol 1.6 a DCLP is split
 * into a start message carrying the decimal byte count, chunk messages, and an empty
 * end message; the receiver rejects the clipboard if the sizes disagree.
 */
object ClipboardCodec {
    /** kClipboardClipboard. Id 1 is the X11 primary selection, which Android lacks. */
    const val CLIPBOARD_ID = 0

    const val FORMAT_TEXT = 0
    const val FORMAT_HTML = 1

    /** Pre-1.6 DCLP: the message carries the whole clipboard. */
    const val MARK_COMPLETE = 0
    const val MARK_START = 1
    const val MARK_CHUNK = 2
    const val MARK_END = 3

    const val CHUNK_SIZE = 32 * 1024

    /** Larger clipboards are dropped rather than buffered. */
    const val MAX_CLIPBOARD_BYTES = 16 * 1024 * 1024

    fun marshall(formats: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(formats.size)
            for ((format, bytes) in formats.toSortedMap()) {
                writeInt(format)
                writeInt(bytes.size)
                write(bytes)
            }
            flush()
        }
        return out.toByteArray()
    }

    fun unmarshall(data: ByteArray): Map<Int, ByteArray> {
        var offset = 0
        fun readU32(): Long {
            if (data.size - offset < 4) throw ProtocolException("Truncated clipboard data")
            val value = ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
            offset += 4
            return value
        }
        val count = readU32()
        val formats = LinkedHashMap<Int, ByteArray>()
        repeat(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            val format = readU32().toInt()
            val size = readU32()
            if (size > data.size - offset) throw ProtocolException("Clipboard format exceeds data")
            formats[format] = data.copyOfRange(offset, offset + size.toInt())
            offset += size.toInt()
        }
        return formats
    }

    /** The (mark, payload) messages a 1.6+ sender emits for [data]. */
    fun chunks(data: ByteArray): List<Pair<Int, ByteArray>> = buildList {
        add(MARK_START to data.size.toString().toByteArray(Charsets.US_ASCII))
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            add(MARK_CHUNK to data.copyOfRange(offset, end))
            offset = end
        }
        add(MARK_END to ByteArray(0))
    }
}

/** Reassembles one clipboard id's DCLP messages. Not thread-safe. */
class ClipboardAssembler {
    private var expected = -1
    private var buffer: ByteArrayOutputStream? = null

    /** @return the whole marshalled clipboard once it is complete, else null. */
    fun accept(mark: Int, data: ByteArray): ByteArray? = when (mark) {
        ClipboardCodec.MARK_COMPLETE -> {
            reset()
            data
        }
        ClipboardCodec.MARK_START -> {
            val size = String(data, Charsets.US_ASCII).trim().toIntOrNull()
            if (size == null || size < 0 || size > ClipboardCodec.MAX_CLIPBOARD_BYTES) {
                reset()
            } else {
                expected = size
                buffer = ByteArrayOutputStream(size)
            }
            null
        }
        ClipboardCodec.MARK_CHUNK -> {
            val buf = buffer
            if (buf != null && buf.size() + data.size <= expected) {
                buf.write(data)
            } else {
                reset()
            }
            null
        }
        ClipboardCodec.MARK_END -> {
            val buf = buffer
            val complete = if (buf != null && buf.size() == expected) buf.toByteArray() else null
            reset()
            complete
        }
        else -> {
            reset()
            null
        }
    }

    private fun reset() {
        expected = -1
        buffer = null
    }
}
