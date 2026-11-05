package io.mo.xatype.keyboard

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class DeleteSwipeBufferTest {
    private var clock = 0L
    private fun buffer() = DeleteSwipeBuffer(now = { clock })

    @Test fun `cleared text comes back once in the same editor`() {
        val buffer = buffer()
        buffer.store("你好", "com.tencent.mm#1#1", sensitive = false)
        assertEquals("你好", buffer.take("com.tencent.mm#1#1"))
        assertNull(buffer.take("com.tencent.mm#1#1"))
    }

    @Test fun `text is never restored into another app or field`() {
        val buffer = buffer()
        buffer.store("验证码 123456", "com.bank#7#2", sensitive = false)
        assertNull(buffer.take("com.tencent.mm#1#1"))
        // A failed attempt also discards it, so it cannot leak later.
        assertNull(buffer.take("com.bank#7#2"))
    }

    @Test fun `password fields and unknown editors are not retained`() {
        val buffer = buffer()
        buffer.store("hunter2", "com.app#3#129", sensitive = true)
        assertNull(buffer.take("com.app#3#129"))
        buffer.store("text", null, sensitive = false)
        assertNull(buffer.take(null))
    }

    @Test fun `restore expires`() {
        val buffer = buffer()
        buffer.store("草稿", "app#1#1", sensitive = false)
        clock += DeleteSwipeBuffer.LIFETIME_MS + 1
        assertNull(buffer.take("app#1#1"))
    }

    @Test fun `password input types are detected`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(KeyboardShortcutActions.isPasswordType(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(KeyboardShortcutActions.isPasswordType(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(KeyboardShortcutActions.isPasswordType(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(KeyboardShortcutActions.isPasswordType(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(KeyboardShortcutActions.isPasswordType(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(KeyboardShortcutActions.isPasswordType(text))
    }
}
