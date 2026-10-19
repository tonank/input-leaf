package com.inputleaf.android.shizuku

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** IClipboard.getPrimaryClip gained parameters across releases; each shape must be called right. */
class HiddenClipboardTest {

    @Test fun `Android 14+ passes attribution tag, user and device`() {
        val clipboard = UpsideDownCake()
        HiddenClipboard.getPrimaryClip(clipboard, userId = 10)
        assertThat(clipboard.calls).containsExactly(listOf("com.android.shell", null, 10, 0))
    }

    @Test fun `Android 13 passes attribution tag and user`() {
        val clipboard = Tiramisu()
        HiddenClipboard.getPrimaryClip(clipboard, userId = 0)
        assertThat(clipboard.calls).containsExactly(listOf("com.android.shell", null, 0))
    }

    @Test fun `Android 10 to 12 pass package and user`() {
        val clipboard = SnowCone()
        assertThat(HiddenClipboard.getPrimaryClip(clipboard, userId = 0)).isEqualTo("clip")
        assertThat(clipboard.calls).containsExactly(listOf("com.android.shell", 0))
    }

    @Test fun `older releases pass only the package`() {
        val clipboard = Oreo()
        HiddenClipboard.getPrimaryClip(clipboard, userId = 0)
        assertThat(clipboard.calls).containsExactly(listOf("com.android.shell"))
    }

    @Test fun `an unknown signature fails instead of guessing`() {
        assertThrows(IllegalStateException::class.java) {
            HiddenClipboard.getPrimaryClip(Unknown(), userId = 0)
        }
    }

    class UpsideDownCake {
        val calls = mutableListOf<List<Any?>>()
        fun getPrimaryClip(pkg: String, tag: String?, userId: Int, deviceId: Int): Any? {
            calls += listOf(pkg, tag, userId, deviceId)
            return null
        }
    }

    class Tiramisu {
        val calls = mutableListOf<List<Any?>>()
        fun getPrimaryClip(pkg: String, tag: String?, userId: Int): Any? {
            calls += listOf(pkg, tag, userId)
            return null
        }
    }

    class SnowCone {
        val calls = mutableListOf<List<Any?>>()
        fun getPrimaryClip(pkg: String, userId: Int): Any? {
            calls += listOf(pkg, userId)
            return "clip"
        }
    }

    class Oreo {
        val calls = mutableListOf<List<Any?>>()
        fun getPrimaryClip(pkg: String): Any? {
            calls += listOf(pkg)
            return null
        }
    }

    class Unknown {
        @Suppress("UNUSED_PARAMETER")
        fun getPrimaryClip(flag: Boolean): Any? = null
    }
}
