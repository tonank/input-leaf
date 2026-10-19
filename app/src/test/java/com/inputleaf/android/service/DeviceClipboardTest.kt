package com.inputleaf.android.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DeviceClipboardTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(ClipboardManager::class.java)

    @Test fun `write sets plain text, or html with its text fallback`() {
        val clipboard = DeviceClipboard(context) { null }

        clipboard.write(ClipboardContent("plain"))
        assertThat(manager.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("plain")
        assertThat(manager.primaryClip!!.getItemAt(0).htmlText).isNull()

        clipboard.write(ClipboardContent("bold", "<b>bold</b>"))
        assertThat(manager.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("bold")
        assertThat(manager.primaryClip!!.getItemAt(0).htmlText).isEqualTo("<b>bold</b>")
    }

    @Test fun `read returns the visible clip without asking the privileged reader`() {
        var privilegedCalls = 0
        val clipboard = DeviceClipboard(context) { privilegedCalls++; "privileged" }
        manager.setPrimaryClip(ClipData.newHtmlText("test", "visible", "<i>visible</i>"))

        assertThat(clipboard.read()).isEqualTo(ClipboardContent("visible", "<i>visible</i>"))
        assertThat(privilegedCalls).isEqualTo(0)
    }

    @Test fun `read falls back to the privileged reader when the clip is hidden or empty`() {
        manager.clearPrimaryClip()

        assertThat(DeviceClipboard(context) { "seen by shell" }.read())
            .isEqualTo(ClipboardContent("seen by shell"))
        assertThat(DeviceClipboard(context) { "" }.read()).isNull()
        assertThat(DeviceClipboard(context) { null }.read()).isNull()
        assertThat(DeviceClipboard(context) { throw IllegalStateException("binder died") }.read()).isNull()
    }

    @Test fun `listeners are told about changes`() {
        val clipboard = DeviceClipboard(context) { null }
        var changes = 0
        val listener = ClipboardManager.OnPrimaryClipChangedListener { changes++ }

        clipboard.addListener(listener)
        clipboard.write(ClipboardContent("one"))
        clipboard.removeListener(listener)
        clipboard.write(ClipboardContent("two"))

        assertThat(changes).isEqualTo(1)
    }
}
