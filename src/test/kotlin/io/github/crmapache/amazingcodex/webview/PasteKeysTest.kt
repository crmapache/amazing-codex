package io.github.crmapache.amazingcodex.webview

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The page swallows the letter a paste shortcut may still leak, and it is told which letter by the IDE. A
 * wrong one means either a "v" left in the message after the paste or somebody's own letter eaten.
 */
class PasteKeysTest {

    @Test
    fun `Ctrl+V leaks a small v and Ctrl+Shift+V a capital one`() {
        assertEquals("v", PasteKeys.letterOf(KeyEvent.VK_V, shift = false))
        assertEquals("V", PasteKeys.letterOf(KeyEvent.VK_V, shift = true))
    }

    @Test
    fun `Shift+Insert writes nothing, so there is nothing to swallow`() {
        assertEquals("", PasteKeys.letterOf(KeyEvent.VK_INSERT, shift = true))
        assertEquals("", PasteKeys.letterOf(KeyEvent.VK_UNDEFINED, shift = false))
    }
}
