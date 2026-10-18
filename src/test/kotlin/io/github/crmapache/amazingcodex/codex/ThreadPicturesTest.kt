package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.webview.ImagePreviews
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A sent message's picture read back out of a Codex thread (see ThreadPictures). The lines are the shapes Codex 0.160
 * and 0.163 write into `~/.codex/sessions` - copied from real threads, with the pictures shrunk to a few pixels - laid
 * out in the sessions folder by day, the way Codex files them, so a revert's segment and a fork made on 0.163 are
 * followed back the way Codex resumes them (see CodexRollout).
 */
class ThreadPicturesTest {

    private val root: File = Files.createTempDirectory("thread-pictures").toFile()
    private val day = File(root, "2026/10/17").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val thread = "0199f0a1-0215-7520-aa6f-00000000a001"
    private val firstTurn = "0199f0a1-031c-72a3-b16d-00000000b001"
    private val secondTurn = "0199f0a1-4d79-7023-9403-00000000b002"

    private fun png(width: Int, height: Int): String {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private val red = png(2, 1)
    private val blue = png(1, 3)
    private val green = png(4, 4)

    private fun ImagePreviews.Picture?.url(): String? = this?.let { "data:$mediaType;base64," + Base64.getEncoder().encodeToString(bytes) }

    private fun meta(id: String, base: String? = null, offset: Long = 0): String {
        val forked = base?.let { ""","forked_from_id":"$it","history_base":{"thread_id":"$it","end_ordinal_exclusive":53,"end_byte_offset":$offset}""" }.orEmpty()
        return """{"timestamp":"2026-10-17T19:59:15.004Z","ordinal":0,"type":"session_meta","payload":{"session_id":"$id","id":"$id"$forked,""" +
            """"timestamp":"2026-10-17T19:59:15.004Z","cwd":"/work/probe","originator":"acx","cli_version":"0.163.0","source":"vscode"}}"""
    }

    /** The model's copy of the person's message - `response_item`, the pictures as `input_image`. */
    private fun modelCopy(turn: String, text: String, vararg pictures: String): String =
        """{"timestamp":"2026-10-04T02:40:51.699Z","ordinal":9,"type":"response_item","payload":{"type":"message","id":"msg_${turn.take(23)}-aa572975bb4d",""" +
            """"role":"user","content":[{"type":"input_text","text":"$text"}""" +
            pictures.joinToString("") { """,{"type":"input_image","image_url":"$it","detail":"high"}""" } +
            """],"internal_chat_message_metadata_passthrough":{"turn_id":"$turn","create_time":1791081651.699706,"content_item_kinds":["user.text","user.image"]}},""" +
            """"metadata":{"retained_source":{"id":{"message_id":"msg_x","turn_id":"$turn","role":"user"},"revision":"retained_x","complete":false},""" +
            """"client_authored":false,"user_input_order":0,"mcp_attribution":{"status":"none"}}}"""

    /** The item a client shows - `item_completed` of a `UserMessage`, the pictures as given ([pictures] is the JSON of each). */
    private fun said(turn: String, id: String, text: String, vararg pictures: String): String =
        """{"timestamp":"2026-10-04T02:40:51.702Z","ordinal":10,"type":"event_msg","payload":{"type":"item_completed","thread_id":"$thread",""" +
            """"turn_id":"$turn","item":{"type":"UserMessage","id":"$id","content":[{"type":"text","text":"$text","text_elements":[]}""" +
            pictures.joinToString("") { ",$it" } +
            """]},"started_at_ms":1791081651701,"completed_at_ms":1791081651701}}"""

    private fun image(url: String) = """{"type":"image","image_url":"$url"}"""

    private fun local(path: String) = """{"type":"local_image","path":"$path"}"""

    /** A message pasted with its pictures, both of its lines, the way Codex writes them one after the other. */
    private fun message(turn: String, id: String, text: String, vararg urls: String): List<String> =
        listOf(modelCopy(turn, text, *urls), said(turn, id, text, *urls.map(::image).toTypedArray()))

    private fun answer(turn: String, id: String, text: String) =
        """{"timestamp":"2026-10-04T02:41:02.110Z","ordinal":20,"type":"event_msg","payload":{"type":"item_completed","thread_id":"$thread",""" +
            """"turn_id":"$turn","item":{"type":"AgentMessage","id":"$id","content":[{"type":"Text","text":"$text"}],"phase":"final_answer"}}}"""

    private fun context(turn: String) =
        """{"timestamp":"2026-10-04T02:40:51.690Z","ordinal":5,"type":"turn_context","payload":{"turn_id":"$turn","cwd":"/work/probe","model":"gpt-5.6-luna"}}"""

    private fun write(name: String, lines: List<String>): File = File(day, name).apply { writeText(lines.joinToString("") { "$it\n" }) }

    private fun bytesOf(lines: List<String>): Long = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }

    @Test
    fun `a message's picture is read out of its line by its place among the pictures`() {
        val file = write(
            "rollout-2026-10-17T16-59-15-$thread.jsonl",
            listOf(meta(thread), context(firstTurn)) + message(firstTurn, "u-1", "[Image #1] and [Image #2] side by side", red, blue) +
                answer(firstTurn, "m-1", "They differ in size."),
        )

        assertEquals(red, (ThreadPictures.ofFile(file, ThreadPictures.Message.Item("u-1"), 0) as ThreadPictures.Read.Found).picture.url())
        assertEquals(blue, (ThreadPictures.ofFile(file, ThreadPictures.Message.Item("u-1"), 1) as ThreadPictures.Read.Found).picture.url())
        // The message is there, the picture is not: nothing to show, and nothing more to ask.
        assertNull((ThreadPictures.ofFile(file, ThreadPictures.Message.Item("u-1"), 2) as ThreadPictures.Read.Found).picture)
        assertIs<ThreadPictures.Read.Missing>(ThreadPictures.ofFile(file, ThreadPictures.Message.Item("u-404"), 0))
        // An answer's id is no message of the person's.
        assertIs<ThreadPictures.Read.Missing>(ThreadPictures.ofFile(file, ThreadPictures.Message.Item("m-1"), 0))
    }

    /*
     * A message the panel sent in this run is known to Codex by an id of its own; the conversation knows it as the n-th
     * of the person's messages in its turn - the one that began it, then the ones written into it.
     */
    @Test
    fun `a message the panel named is found as the n-th of the person's in its turn`() {
        val file = write(
            "rollout-2026-10-17T16-59-15-$thread.jsonl",
            listOf(meta(thread), context(firstTurn)) + message(firstTurn, "u-1", "[Image #1] look", red) +
                answer(firstTurn, "m-1", "Looking.") + message(firstTurn, "u-2", "[Image #2] and this", blue) +
                listOf(context(secondTurn)) + message(secondTurn, "u-3", "[Image #3] next", green),
        )

        assertEquals(red, (ThreadPictures.ofFile(file, ThreadPictures.Message.InTurn(firstTurn, 0), 0) as ThreadPictures.Read.Found).picture.url())
        assertEquals(blue, (ThreadPictures.ofFile(file, ThreadPictures.Message.InTurn(firstTurn, 1), 0) as ThreadPictures.Read.Found).picture.url())
        assertEquals(green, (ThreadPictures.ofFile(file, ThreadPictures.Message.InTurn(secondTurn, 0), 0) as ThreadPictures.Read.Found).picture.url())
        // Not written yet - a phone's message lands a moment after its card.
        assertIs<ThreadPictures.Read.Missing>(ThreadPictures.ofFile(file, ThreadPictures.Message.InTurn(secondTurn, 1), 0))
    }

    // Three turns, a revert before the second, a turn after it: what the revert cut is not the thread's any more.
    @Test
    fun `a reverted thread is read as Codex resumes it`() {
        val segment = "01a14b7a-0000-7000-8000-00000000000a"
        val kept = listOf(meta(thread), context(firstTurn)) + message(firstTurn, "u-1", "[Image #1] kept", red)
        write("rollout-2026-10-17T16-59-15-$thread.jsonl", kept + listOf(context(secondTurn)) + message(secondTurn, "u-2", "[Image #2] cut", blue))
        val last = write(
            "rollout-2026-10-17T17-02-00-${thread}_$segment.jsonl",
            listOf("""{"type":"session_meta","payload":{"id":"$thread","history_base":{"thread_id":"$thread","end_ordinal_exclusive":3,"end_byte_offset":${bytesOf(kept)}}}}""") +
                message("01a14b7a-1111-7000-8000-000000000001", "u-3", "[Image #2] after", green),
        )

        assertEquals(red, (ThreadPictures.ofFile(last, ThreadPictures.Message.Item("u-1"), 0) as ThreadPictures.Read.Found).picture.url())
        assertIs<ThreadPictures.Read.Missing>(ThreadPictures.ofFile(last, ThreadPictures.Message.Item("u-2"), 0))
        assertEquals(green, (ThreadPictures.ofFile(last, ThreadPictures.Message.Item("u-3"), 0) as ThreadPictures.Read.Found).picture.url())
    }

    /*
     * Measured on 0.163: the fork's file starts with a base into its source's file, where the source stood when it was
     * forked, and holds nothing it inherited - the inherited message is found in the source, and what the source said
     * after the fork is not the fork's.
     */
    @Test
    fun `a fork made on 0_163 finds what it inherited in its source`() {
        val fork = "0199f0a2-f1e8-7b61-9862-00000000a002"
        val before = listOf(meta(thread), context(firstTurn)) + message(firstTurn, "u-1", "[Image #1] before the fork", red)
        write("rollout-2026-10-17T16-59-15-$thread.jsonl", before + listOf(context(secondTurn)) + message(secondTurn, "u-2", "[Image #2] after it", blue))
        File(root, "2026/10/18").mkdirs()
        val forked = File(root, "2026/10/18/rollout-2026-10-18T09-07-25-$fork.jsonl").apply {
            writeText(
                (listOf(meta(fork, base = thread, offset = bytesOf(before))) + message("01a14b79-2222-7000-8000-000000000002", "u-9", "[Image #2] in the fork", green))
                    .joinToString("") { "$it\n" },
            )
        }

        assertEquals(red, (ThreadPictures.ofFile(forked, ThreadPictures.Message.Item("u-1"), 0) as ThreadPictures.Read.Found).picture.url())
        assertIs<ThreadPictures.Read.Missing>(ThreadPictures.ofFile(forked, ThreadPictures.Message.Item("u-2"), 0))
        assertEquals(green, (ThreadPictures.ofFile(forked, ThreadPictures.Message.Item("u-9"), 0) as ThreadPictures.Read.Found).picture.url())
    }

    /*
     * A picture attached by its path - Codex's terminal does that with a paste, into a temporary file - is read off the
     * disk while the file is there, and out of the model's copy of the message once it is gone.
     */
    @Test
    fun `a picture attached by a path that is gone is read out of the model's copy`() {
        val shot = File(root, "codex-clipboard-1.png").apply { writeBytes(Base64.getDecoder().decode(red.substringAfter(','))) }
        val gone = File(root, "codex-clipboard-2.png").absolutePath
        val file = write(
            "rollout-2026-10-17T16-59-15-$thread.jsonl",
            listOf(meta(thread), modelCopy(firstTurn, "[Image #1] [Image #2]", red, blue), said(firstTurn, "u-1", "[Image #1] [Image #2]", local(shot.absolutePath), local(gone))),
        )

        assertEquals(red, (ThreadPictures.ofFile(file, ThreadPictures.Message.Item("u-1"), 0) as ThreadPictures.Read.Found).picture.url())
        assertEquals(blue, (ThreadPictures.ofFile(file, ThreadPictures.Message.Item("u-1"), 1) as ThreadPictures.Read.Found).picture.url())
    }

    // The copy before the item is another message's when the item's own was not written: as many pictures, or none.
    @Test
    fun `a model's copy with another count of pictures is not taken`() {
        val file = write(
            "rollout-2026-10-17T16-59-15-$thread.jsonl",
            listOf(meta(thread), modelCopy(firstTurn, "[Image #1]", red), said(firstTurn, "u-1", "[Image #1] [Image #2]", local("/gone/a.png"), local("/gone/b.png"))),
        )

        assertNull((ThreadPictures.ofFile(file, ThreadPictures.Message.Item("u-1"), 0) as ThreadPictures.Read.Found).picture)
    }

    // An older Codex wrote the person's words as `user_message` events, which name no message: Codex is asked then.
    @Test
    fun `a thread of the older form is told apart from a miss`() {
        val file = write(
            "rollout-2026-09-20T10-00-00-$thread.jsonl",
            listOf(
                meta(thread),
                """{"timestamp":"2026-09-20T10:00:01.000Z","type":"event_msg","payload":{"type":"user_message","message":"[Image #1] look","images":["$red"],"local_images":[]}}""",
            ),
        )

        assertIs<ThreadPictures.Read.Unnamed>(ThreadPictures.ofFile(file, ThreadPictures.Message.Item("item-1"), 0))
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** Codex's turns as `thread/turns/list` pages them, newest first, two turns a page. */
    private fun pages(vararg turns: String): (String, JsonObject) -> JsonElement? = { _, params ->
        val at = (params["cursor"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() } ?: 0)
        val page = turns.drop(at).take(2)
        val next = if (at + 2 < turns.size) ""","nextCursor":"${at + 2}"""" else ""
        json("""{"data":[${page.joinToString(",")}]$next}""")
    }

    private fun turn(id: String, vararg items: String) = """{"id":"$id","status":"completed","items":[${items.joinToString(",")}]}"""

    private fun asked(id: String, url: String) =
        """{"type":"userMessage","id":"$id","content":[{"type":"text","text":"[Image #1]","text_elements":[]},{"type":"image","url":"$url"}]}"""

    @Test
    fun `Codex is asked page by page until the message is in one`() {
        val ask = pages(
            turn("t-3", asked("item-5", green)),
            turn("t-2", asked("item-3", blue), """{"type":"agentMessage","id":"item-4","text":"ok"}""", asked("item-6", red)),
            turn("t-1", asked("item-1", red)),
        )

        assertEquals(red, ThreadPictures.ofTurns(thread, ThreadPictures.Message.Item("item-1"), 0, ask).url())
        assertEquals(red, ThreadPictures.ofTurns(thread, ThreadPictures.Message.InTurn("t-2", 1), 0, ask).url())
        assertEquals(blue, ThreadPictures.ofTurns(thread, ThreadPictures.Message.InTurn("t-2", 0), 0, ask).url())
        assertNull(ThreadPictures.ofTurns(thread, ThreadPictures.Message.InTurn("t-2", 2), 0, ask))
        assertNull(ThreadPictures.ofTurns(thread, ThreadPictures.Message.Item("item-404"), 0, ask))
        assertNull(ThreadPictures.ofTurns(thread, ThreadPictures.Message.Item("item-1"), 1, ask))
    }
}
