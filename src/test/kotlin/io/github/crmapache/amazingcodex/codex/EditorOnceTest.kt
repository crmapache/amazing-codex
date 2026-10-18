package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.crmapache.amazingcodex.editor.EditorContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The file chip's "Send with this message only" under a setting that is off, through the door every client's message
 * comes in by (see SessionCommands) and down to what reaches Codex.
 *
 * The page asked for the editor with the flag alone, and the IDE - which holds the flag to the setting, because a page
 * a version behind does not know the setting - dropped it: the agent got no file while the card under the message said
 * it went. The explicit choice now travels as a word of its own (`editorOnce`), which an old page never sends. And none
 * of it is a phone's to ask for: no editor stands beside a phone. On a process that speaks Codex's protocol (see
 * FakeCodex), with the editor stood in (see EditorContext.standIn).
 */
class EditorOnceTest : BasePlatformTestCase() {

    private class Recorder(override val id: String, override val isLocal: Boolean) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    private val TAB = "editor-once-${UUID.randomUUID()}"

    private val shareBefore = CodexPreferences.shareEditor

    override fun setUp() {
        super.setUp()
        project.basePath?.let { File(it).mkdirs() }
        codex = FakeCodex()
        EditorContext.getInstance(project).standIn(
            EditorContext.Snapshot(
                path = "src/Totals.kt",
                name = "Totals.kt",
                selection = EditorContext.Selection(startLine = 3, endLine = 5, text = "val total = 1"),
            ),
        )
    }

    override fun tearDown() {
        try {
            EditorContext.getInstance(project).standIn(null)
            CodexPreferences.shareEditor = shareBefore
            runCatching { CodexSessionHub.getInstance(project).closeSession(TAB) }
            codex.close()
        } finally {
            super.tearDown()
        }
    }

    private fun json(line: String): JsonObject = Json.parseToJsonElement(line) as JsonObject

    private fun text(element: Any?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun hub(vararg clients: Recorder): CodexSessionHub = CodexSessionHub.getInstance(project).also { hub ->
        clients.forEach {
            hub.register(it)
            hub.attach(it.id)
        }
    }

    /** A message as a client sends it, with the editor flags the page would set. */
    private fun send(hub: CodexSessionHub, from: Recorder, words: String, editor: Boolean, once: Boolean) {
        hub.commands.handle(
            from.id,
            buildJsonObject {
                put("type", "prompt")
                put("sessionId", TAB)
                put("text", words)
                if (!from.isLocal) put("id", "m-${UUID.randomUUID()}")
                if (editor) put("editor", true)
                if (once) put("editorOnce", true)
                putJsonArray("tokens") {
                    addJsonObject {
                        put("kind", "text")
                        put("value", words)
                    }
                }
                putJsonArray("quotes") {}
            },
        )
    }

    /** Every text item each turn started in the tab carried - the person's words, then the editor's note if any. */
    private fun turnInputs(hub: CodexSessionHub): List<List<String>> {
        val conversation = hub.conversations.conversation(TAB)!!
        assertTrue(FakeCodex.waitFor { hub.conversations.conversationIdOf(TAB) != null })
        codex.settled(conversation)
        return codex.requests("turn/start", hub.conversations.conversationIdOf(TAB)!!).map { request ->
            ((request["params"] as JsonObject)["input"] as JsonArray)
                .map { it as JsonObject }
                .filter { text(it["type"]) == "text" }
                .map { text(it["text"]) }
        }
    }

    /** The line every client draws under the message - the file the IDE says went along, or nothing. */
    private fun echoedEditor(recorder: Recorder): JsonObject? =
        recorder.received.map(::json)
            .last { text(it["type"]) == "promptEcho" && text(it["sessionId"]) == TAB }["editor"] as? JsonObject

    fun testOnlyWithThisMessageCarriesTheFileThoughTheSettingIsOff() {
        CodexPreferences.shareEditor = false
        val desk = Recorder("desk", isLocal = true)
        val hub = hub(desk)

        send(hub, desk, "What does this do?", editor = true, once = true)

        val inputs = turnInputs(hub)
        assertEquals(1, inputs.size)
        assertEquals(inputs.single().toString(), 2, inputs.single().size)
        assertTrue(inputs.single()[1], inputs.single()[1].contains("src/Totals.kt"))
        assertEquals("Totals.kt", text(echoedEditor(desk)?.get("name")))
    }

    /** A page a version behind sends the flag alone, and the setting it does not know of is still held. */
    fun testTheFlagAloneStillFollowsAnOffSetting() {
        CodexPreferences.shareEditor = false
        val desk = Recorder("desk", isLocal = true)
        val hub = hub(desk)

        send(hub, desk, "What does this do?", editor = true, once = false)

        assertEquals(listOf(listOf("What does this do?")), turnInputs(hub))
        assertNull(echoedEditor(desk))
    }

    /** Nothing of the desk's editor goes to whoever is on the other end of the relay, whatever it asks for. */
    fun testAPhoneOpensNothingWhicheverWordItSends() {
        CodexPreferences.shareEditor = true
        val desk = Recorder("desk", isLocal = true)
        val phone = Recorder("phone", isLocal = false)
        val hub = hub(desk, phone)

        send(hub, phone, "What does this do?", editor = true, once = true)

        assertEquals(listOf(listOf("What does this do?")), turnInputs(hub))
        assertNull(echoedEditor(desk))
    }

    /** The rule itself, in every combination that matters. */
    fun testTheRule() {
        assertTrue(EditorContext.goesWith(asked = true, once = false, shared = true, command = false))
        assertTrue(EditorContext.goesWith(asked = true, once = true, shared = false, command = false))
        assertFalse(EditorContext.goesWith(asked = true, once = false, shared = false, command = false))
        assertFalse("never asked for", EditorContext.goesWith(asked = false, once = true, shared = true, command = false))
        assertFalse("never with a command", EditorContext.goesWith(asked = true, once = true, shared = true, command = true))
    }
}
