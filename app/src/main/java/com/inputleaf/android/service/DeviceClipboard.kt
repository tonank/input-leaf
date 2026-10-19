package com.inputleaf.android.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * The Android side of [ClipboardSync]: reads and writes the primary clip.
 *
 * Android 10+ returns no clip to an app that is neither the default IME nor in the
 * foreground, so [read] falls back to [privilegedRead] (the shell-privileged injector),
 * which may see what the app cannot.
 */
class DeviceClipboard(
    private val context: Context,
    private val privilegedRead: () -> String?,
) {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    /** Off the main thread: may resolve a content URI or make a binder call. */
    fun read(): ClipboardContent? {
        val item = runCatching { manager?.primaryClip }.getOrNull()
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
        val text = item?.let { runCatching { it.coerceToText(context)?.toString() }.getOrNull() }
        if (!text.isNullOrEmpty()) return ClipboardContent(text, item.htmlText)
        val privileged = runCatching { privilegedRead() }.getOrNull()
        return privileged?.takeIf { it.isNotEmpty() }?.let { ClipboardContent(it) }
    }

    /** On the main thread. */
    fun write(content: ClipboardContent) {
        val clip = if (content.html != null) {
            ClipData.newHtmlText(LABEL, content.text, content.html)
        } else {
            ClipData.newPlainText(LABEL, content.text)
        }
        runCatching { manager?.setPrimaryClip(clip) }
            .onFailure { Log.w(TAG, "Could not set clipboard from server", it) }
    }

    fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        runCatching { manager?.addPrimaryClipChangedListener(listener) }
    }

    fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        runCatching { manager?.removePrimaryClipChangedListener(listener) }
    }

    private companion object {
        const val TAG = "DeviceClipboard"
        const val LABEL = "Input Leaf"
    }
}
