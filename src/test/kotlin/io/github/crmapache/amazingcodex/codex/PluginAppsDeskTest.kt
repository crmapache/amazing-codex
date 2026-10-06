package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginAppsDeskTest {
    private class Fixture {
        var selected = "account-a"
        var conversationAccount = "account-a"
        val reads = mutableListOf<Pair<(List<PluginAppGroup>) -> Unit, (String) -> Unit>>()
        val messages = mutableListOf<JsonObject>()
        val openedUrls = mutableListOf<String>()
        var browserReady: (() -> Boolean)? = null
        var browserOpened: (() -> Unit)? = null
        var browserFailed: ((String) -> Unit)? = null
        val desk = PluginAppsDesk({ conversationAccount }, { selected },
            { _, result, failure -> reads += result to failure },
            { url, current, opened, failure ->
                openedUrls += url
                browserReady = current
                browserOpened = opened
                browserFailed = failure
            }, { messages += it })
        val groups = listOf(PluginAppGroup("example@catalog", listOf(
            PluginApp("app-1", "Example", "https://example.com/connect?state=codex", true, true, true),
        )))
        fun answer(index: Int = reads.lastIndex, result: List<PluginAppGroup> = groups) = reads[index].first(result)
        fun phase() = messages.last()["phase"]!!.jsonPrimitive.content
    }

    @Test
    fun `browser return refreshes tools without claiming authentication succeeded`() {
        val f = Fixture()
        f.desk.connect("main", "example@catalog", "app-1")
        assertEquals("opening", f.phase())
        f.answer()
        assertTrue(f.browserReady!!())
        f.browserOpened!!()
        assertEquals("waiting", f.phase())
        f.desk.returnedToIde()
        assertEquals(2, f.reads.size)
        assertEquals("loading", f.phase())
        f.answer()
        assertEquals("ready", f.phase())
        val app = f.messages.last()["plugins"]!!.jsonArray.single().jsonObject["apps"]!!.jsonArray.single().jsonObject
        assertEquals("true", app["callable"].toString())
        assertEquals("false", app["needsAuth"].toString())
        assertFalse(app.containsKey("authenticated"))
        assertFalse(f.messages.any { it.toString().contains("https://") })
    }

    @Test
    fun `cancel invalidates lookup and does not disconnect the account`() {
        val f = Fixture()
        f.desk.connect("main", "example@catalog", "app-1")
        f.desk.cancel("main")
        f.answer()
        assertEquals("cancelled", f.phase())
        assertTrue(f.openedUrls.isEmpty())
        f.desk.returnedToIde()
        assertEquals(1, f.reads.size)
    }

    @Test
    fun `cancel between lookup and IDE browser dispatch prevents opening`() {
        val f = Fixture()
        f.desk.connect("main", "example@catalog", "app-1")
        f.answer()
        f.desk.cancel("main")
        assertFalse(f.browserReady!!())
        f.browserOpened!!()
        assertEquals("cancelled", f.phase())
    }

    @Test
    fun `switching Codex account rejects late results even before the conversation moves`() {
        val f = Fixture()
        f.desk.connect("main", "example@catalog", "app-1")
        f.selected = "account-b"
        f.answer()
        assertTrue(f.openedUrls.isEmpty())
        f.desk.connect("main", "example@catalog", "app-1")
        assertEquals("error", f.phase())
        assertEquals(1, f.reads.size)
        f.conversationAccount = "account-b"
        f.desk.accountChanged()
        f.answer()
        assertEquals("account-b", f.messages.last()["accountId"]!!.jsonPrimitive.content)
        assertEquals("ready", f.phase())
    }

    @Test
    fun `a later refresh wins and unknown app IDs never open a browser`() {
        val f = Fixture()
        f.desk.connect("main", "example@catalog", "app-1")
        f.desk.refresh("main")
        f.answer(0)
        assertTrue(f.openedUrls.isEmpty())
        f.answer(1)
        assertEquals("ready", f.phase())
        f.desk.connect("main", "other-plugin", "app-1")
        f.answer()
        assertEquals("error", f.phase())
        assertTrue(f.openedUrls.isEmpty())
    }

    @Test
    fun `RPC and browser failures release the pending action and keep the reason`() {
        val f = Fixture()
        f.desk.refresh("main")
        f.reads.last().second("Codex method not found")
        assertEquals("error", f.phase())
        assertEquals("Codex method not found", f.messages.last()["error"]!!.jsonPrimitive.content)
        f.desk.connect("main", "example@catalog", "app-1")
        f.answer()
        f.browserFailed!!("No browser available")
        assertEquals("error", f.phase())
        assertFalse(f.messages.last().containsKey("pendingAppId"))
        assertEquals("No browser available", f.messages.last()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool login failure overrides neither enabled nor callable`() {
        val f = Fixture()
        f.desk.needsAuth("main", "app-1")
        f.desk.refresh("main")
        f.answer()
        val app = f.messages.last()["plugins"]!!.jsonArray.single().jsonObject["apps"]!!.jsonArray.single().jsonObject
        assertEquals("true", app["needsAuth"].toString())
        assertEquals("true", app["callable"].toString())
        f.desk.reset()
        f.desk.refresh("main")
        f.answer()
        assertFalse(f.messages.last().toString().contains("\"needsAuth\":true"))
    }
}
