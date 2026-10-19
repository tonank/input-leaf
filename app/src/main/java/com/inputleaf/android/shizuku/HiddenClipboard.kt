package com.inputleaf.android.shizuku

import android.content.ClipData
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Reads the primary clip through the hidden IClipboard service from the privileged
 * injector process. ClipboardService lets any package holding READ_CLIPBOARD_IN_BACKGROUND
 * read it ("shell can access the clipboard for testing purposes"), and shell holds it.
 */
internal object HiddenClipboard {
    private const val TAG = "HiddenClipboard"
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val PER_USER_RANGE = 100_000

    fun readText(userId: Int = Process.myUid() / PER_USER_RANGE): String? = try {
        val clip = primaryClip(userId)
        if (clip == null || clip.itemCount == 0) {
            null
        } else {
            val item = clip.getItemAt(0)
            // coerceToText needs a Context only for URIs; plain and HTML text need none.
            (item.text ?: item.htmlText)?.toString()
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Could not read clipboard", e)
        null
    }

    private fun primaryClip(userId: Int): ClipData? {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder? ?: return null
        val clipboard = Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return null
        return getPrimaryClip(clipboard, userId) as ClipData?
    }

    /** Calls whichever IClipboard.getPrimaryClip this Android version has. */
    internal fun getPrimaryClip(clipboard: Any, userId: Int): Any? {
        val method = clipboard.javaClass.methods.first { it.name == "getPrimaryClip" }
        val string = String::class.java
        val int = Int::class.javaPrimitiveType
        val params = method.parameterTypes.toList()
        return when (params) {
            // U+: (pkg, attributionTag, userId, deviceId)
            listOf(string, string, int, int) -> method.invoke(clipboard, SHELL_PACKAGE, null, userId, 0)
            // T: (pkg, attributionTag, userId)
            listOf(string, string, int) -> method.invoke(clipboard, SHELL_PACKAGE, null, userId)
            // Q-S: (pkg, userId)
            listOf(string, int) -> method.invoke(clipboard, SHELL_PACKAGE, userId)
            listOf(string) -> method.invoke(clipboard, SHELL_PACKAGE)
            else -> error("Unknown getPrimaryClip signature $params")
        }
    }
}
