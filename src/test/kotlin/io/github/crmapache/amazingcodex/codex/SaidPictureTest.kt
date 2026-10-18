package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The picture behind a chip in a sent message, asked of the IDE the way the panel asks (`previewImage` with a `said`
 * place, see CodexSessionHub.saidPicture) and read out of the tab's thread.
 *
 * A message sent in this run carries the panel's name for it, and Codex never hears that name: its thread knows the
 * message by an id of its own. The conversation tells where the panel's message stands - its turn, and its place among
 * the person's messages there (see CodexSession.placeOf) - so a message written into a running turn is found as the
 * second of that turn. One read off the history carries Codex's own id already; a fork not born yet is looked for in
 * its source. On a process that speaks Codex's protocol (see FakeCodex) and takes steers, over a thread's file in the
 * shape Codex 0.163 writes it.
 */
class SaidPictureTest : BasePlatformTestCase() {

    private class Recorder(override val id: String) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex
    private lateinit var folder: File

    private val TAB = "said-picture-${UUID.randomUUID()}"
    private val FORK = "said-picture-fork-${UUID.randomUUID()}"

    override fun setUp() {
        super.setUp()
        project.basePath?.let { File(it).mkdirs() }
        codex = FakeCodex().apply { takesSteers = true }
        folder = Files.createTempDirectory("said-picture").toFile()
    }

    override fun tearDown() {
        try {
            val hub = CodexSessionHub.getInstance(project)
            hub.conversations.conversationIdOf(TAB)?.let(CodexHistory::forget)
            runCatching { hub.closeSession(FORK) }
            runCatching { hub.closeSession(TAB) }
            codex.close()
            folder.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun png(width: Int, height: Int): String {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private val red = png(2, 1)
    private val blue = png(1, 3)

    private fun hub(recorder: Recorder): CodexSessionHub = CodexSessionHub.getInstance(project).also {
        it.register(recorder)
        it.attach(recorder.id)
    }

    private fun echo(text: String) = buildJsonObject {
        putJsonArray("tokens") {
            addJsonObject {
                put("kind", "text")
                put("value", text)
            }
        }
        putJsonArray("quotes") {}
    }

    private fun conversation(hub: CodexSessionHub): CodexSession = hub.conversations.conversation(TAB)!!

    private fun thread(hub: CodexSessionHub): String = hub.conversations.conversationIdOf(TAB)!!

    private fun json(line: String): JsonObject = Json.parseToJsonElement(line) as JsonObject

    private fun text(element: Any?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** The name the hub gave the last message the person sent into the tab. */
    private fun lastSent(recorder: Recorder): String =
        recorder.received.last { it.contains("\"type\":\"promptEcho\"") && it.contains("\"sessionId\":\"$TAB\"") }
            .let { text(json(it)["uuid"]) }

    /** The thread's file as Codex 0.163 writes it: each message as the model's input and as the item a client shows. */
    private fun threadFile(thread: String, turn: String, vararg messages: Pair<String, String>): File {
        val lines = messages.flatMap { (id, picture) ->
            val url = "data:image/png;base64,$picture"
            listOf(
                """{"timestamp":"2026-10-17T19:59:18.868Z","ordinal":8,"type":"response_item","payload":{"type":"message","id":"msg_$id","role":"user",""" +
                    """"content":[{"type":"input_text","text":"[Image #1]"},{"type":"input_image","image_url":"$url","detail":"high"}]}}""",
                """{"timestamp":"2026-10-17T19:59:18.868Z","ordinal":9,"type":"event_msg","payload":{"type":"item_completed","thread_id":"$thread",""" +
                    """"turn_id":"$turn","item":{"type":"UserMessage","id":"$id","content":[{"type":"text","text":"[Image #1]","text_elements":[]},""" +
                    """{"type":"image","image_url":"$url"}]},"started_at_ms":1792267158868,"completed_at_ms":1792267158868}}""",
            )
        }
        return File(folder, "rollout-2026-10-17T19-59-15-$thread.jsonl").apply { writeText(lines.joinToString("") { "$it\n" }) }
    }

    /** What the IDE answers the page asking for the [image]-th picture of the message [uuid] in [tab]. */
    private fun preview(hub: CodexSessionHub, recorder: Recorder, tab: String, uuid: String, image: Int = 0): String {
        val ask = UUID.randomUUID().toString()
        hub.previewImage(
            recorder.id,
            ask,
            buildJsonObject {
                put("kind", "said")
                put("sessionId", tab)
                put("uuid", uuid)
                put("image", image)
            },
        )
        val answered = { recorder.received.firstOrNull { it.contains("\"type\":\"imagePreview\"") && it.contains("\"id\":\"$ask\"") } }
        assertTrue("the IDE did not answer the preview", FakeCodex.waitFor { answered() != null })
        return text(json(answered()!!)["data"])
    }

    fun testAPictureIsReadOutOfTheThreadByTheNameTheMessageGoesBy() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        hub.prompt(TAB, "[Image #1] look", images = listOf(ImageAttachment("image/png", red)), echo = echo("[Image #1] look"))
        val first = lastSent(recorder)
        assertTrue("the message never reached Codex", FakeCodex.waitFor { conversation(hub).turnOf(first) != null })
        val turn = conversation(hub).turnOf(first)!!
        conversation(hub).heardFromCodex(FakeCodex.turnStarted(thread(hub), turn))

        // Written into the running turn: Codex takes it in as the turn's second message of the person's.
        hub.prompt(TAB, "[Image #2] and this", images = listOf(ImageAttachment("image/png", blue)), echo = echo("[Image #2] and this"))
        val second = lastSent(recorder)
        assertTrue("the words never went into the turn", FakeCodex.waitFor { conversation(hub).turnOf(second) != null })
        assertEquals(listOf("[Image #2] and this"), codex.steered(thread(hub)))
        assertEquals(turn to 0, hub.conversations.placeOf(TAB, first))
        assertEquals(turn to 1, hub.conversations.placeOf(TAB, second))

        CodexHistory.standIn(thread(hub), threadFile(thread(hub), turn, "cx-1" to red, "cx-2" to blue))

        assertEquals("data:image/png;base64,$red", preview(hub, recorder, TAB, first))
        assertEquals("data:image/png;base64,$blue", preview(hub, recorder, TAB, second))
        // Read off the history, the card carries Codex's own id.
        assertEquals("data:image/png;base64,$blue", preview(hub, recorder, TAB, "cx-2"))
        // Nothing to show is said, rather than left to the page's patience.
        assertEquals("", preview(hub, recorder, TAB, "cx-404"))
        assertEquals("", preview(hub, recorder, TAB, first, image = 1))

        // A fork not born yet has no thread of its own: its feed is its source's, and so are the pictures in it.
        hub.conversations.restoreFork(FORK, ForkOrigin(source = thread(hub), title = "Look", cut = false, at = turn))
        assertEquals("data:image/png;base64,$red", preview(hub, recorder, FORK, "cx-1"))
    }
}
