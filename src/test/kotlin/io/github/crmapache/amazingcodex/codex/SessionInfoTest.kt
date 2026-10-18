package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.crmapache.amazingcodex.codex.accounts.AccountsState
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `/status` under Codex (see SessionInfoRow): the answer the IDE builds, and a phone's `/status`, which arrives as a
 * message and used to go to the model as one - a turn spent on "I don't have a /status command", since the app-server
 * knows no slash commands. Now it is answered at the door, to the phone alone and without the machine's paths. On a
 * process that speaks Codex's protocol (see FakeCodex).
 */
class SessionInfoTest : BasePlatformTestCase() {

    private class Recorder(override val id: String, override val isLocal: Boolean) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    private val TAB = "status-${UUID.randomUUID()}"

    private var drawer: File? = null

    override fun setUp() {
        super.setUp()
        project.basePath?.let { File(it).mkdirs() }
        codex = FakeCodex()
    }

    override fun tearDown() {
        try {
            runCatching { CodexSessionHub.getInstance(project).closeSession(TAB) }
            codex.close()
            runCatching { AccountsState.getInstance().forget(ACCOUNT) }
            // An empty folder this test made, with nothing linked into it: nothing here ever synced it as a drawer.
            drawer?.delete()
        } finally {
            super.tearDown()
        }
    }

    private fun json(line: String): JsonObject = Json.parseToJsonElement(line) as JsonObject

    private fun text(element: Any?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun prompt(words: String, id: String? = null) = buildJsonObject {
        put("type", "prompt")
        put("sessionId", TAB)
        put("text", words)
        id?.let { put("id", it) }
        putJsonArray("tokens") {
            addJsonObject {
                put("kind", "text")
                put("value", words)
            }
        }
        putJsonArray("quotes") {}
    }

    private fun about(recorder: Recorder, type: String): List<JsonObject> =
        recorder.received.map(::json).filter { text(it["type"]) == type && text(it["sessionId"]) == TAB }

    fun testAPhonesStatusIsAnsweredRatherThanSaidToTheModel() {
        val desk = Recorder("desk", isLocal = true)
        val phone = Recorder("phone", isLocal = false)
        val hub = CodexSessionHub.getInstance(project).also { hub ->
            listOf(desk, phone).forEach {
                hub.register(it)
                hub.attach(it.id)
            }
        }

        hub.commands.handle(desk.id, prompt("Move the totals onto the new money type"))
        assertTrue(FakeCodex.waitFor { hub.conversations.conversationIdOf(TAB) != null })
        val thread = hub.conversations.conversationIdOf(TAB)!!
        val conversation = hub.conversations.conversation(TAB)!!
        val echoes = about(desk, "promptEcho").size

        hub.commands.handle(phone.id, prompt("/status", id = "m-status"))

        val answer = about(phone, "sessionInfo").single()
        assertEquals(thread, text(answer["conversationId"]))
        // A phone is not handed the machine's paths; on the default sign-in there is nothing to say about accounts.
        assertNull(answer["resume"])
        assertNull(answer["onDefault"])
        // Taken, as any message from a phone is, so its outbox lets go of it...
        assertEquals("m-status", text(about(phone, "promptReceived").single()["id"]))
        // ...and nothing reached the conversation: no card, no turn.
        assertEquals(echoes, about(desk, "promptEcho").size)
        assertTrue(about(desk, "sessionInfo").isEmpty())
        codex.settled(conversation)
        assertEquals(listOf("Move the totals onto the new money type"), codex.turnsStarted(thread))
        assertTrue(codex.steered(thread).isEmpty())
    }

    /** Queued, it would wait for the turn only to reach Codex as words - answered at once instead. */
    fun testAQueuedStatusIsAnsweredToo() {
        val phone = Recorder("phone", isLocal = false)
        val hub = CodexSessionHub.getInstance(project).also {
            it.register(phone)
            it.attach(phone.id)
        }

        hub.commands.handle(
            phone.id,
            buildJsonObject {
                put("type", "queuePrompt")
                put("sessionId", TAB)
                put("id", "q-status")
                put("text", "/status")
            },
        )

        assertEquals(1, about(phone, "sessionInfo").size)
        assertTrue(hub.conversations.unsentOf(TAB).isEmpty())
        assertTrue(about(phone, "queue").none { it.toString().contains("/status") })
    }

    /** The drawer a terminal would be given: the account's folder and the ordinary home - and nothing it could not run. */
    fun testTheHomesNamedForATerminalAreTheAccountsOwn() {
        val folder = Files.createTempDirectory("acx-drawer").toFile().also { drawer = it }
        AccountsState.getInstance().remember(
            AccountsState.Account().apply {
                id = ACCOUNT
                storeDir = folder.absolutePath
                alias = "Work"
            },
        )
        val accounts = CodexAccounts.getInstance()

        assertEquals(folder.absolutePath to HostOs.configDirectory().absolutePath, accounts.terminalHomes(ACCOUNT, project.basePath))
        assertEquals("Work", accounts.labelOf(ACCOUNT))
        assertNull("the default sign-in has no drawer to name", accounts.terminalHomes("", project.basePath))

        folder.delete()
        assertNull("a drawer that is gone would not run", accounts.terminalHomes(ACCOUNT, project.basePath))
    }

    private companion object {
        const val ACCOUNT = "status-test-account"
    }
}
