package io.github.crmapache.amazingcodex.search

import io.github.crmapache.amazingcodex.codex.SessionSnapshot
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The search index over Codex's conversation files. The thread names Codex keeps in its own home are not
 * reachable from a test (the home is the machine's), so the titles here are the other half of the rule:
 * the person's first words.
 */
class SearchIndexTest {

    private val home = Files.createTempDirectory("acx-search").toFile()
    private val transcripts = File(home, "transcripts").apply { mkdirs() }
    private val store = File(home, "index").toPath()

    /** A thread id as Codex writes it at the end of a conversation file's name. */
    private val a = "0199a000-0000-7000-8000-00000000000a"
    private val b = "0199a000-0000-7000-8000-00000000000b"

    /** A finished message, as Codex writes it into a conversation file. */
    private fun said(kind: String, id: String, text: String, at: String = "2026-08-14T09:12:00.000Z"): String {
        val type = if (kind == "user") "UserMessage" else "AgentMessage"
        val block = if (kind == "user") "text" else "Text"
        return """{"timestamp":"$at","type":"event_msg","payload":{"type":"item_completed","item":{"type":"$type","id":"$id","content":[{"type":"$block","text":"$text"}]}}}""" + "\n"
    }

    private fun meta(id: String) =
        """{"timestamp":"2026-08-14T09:11:59.000Z","type":"session_meta","payload":{"id":"$id","cwd":"/work/project"}}""" + "\n"

    /** The same words as model traffic - what the search must not read twice. */
    private fun traffic(text: String) =
        """{"timestamp":"2026-08-14T09:12:00.000Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$text"}]}}""" + "\n"

    private fun transcript(id: String, vararg lines: String): File =
        File(transcripts, "rollout-2026-08-14T09-12-00-$id.jsonl").apply { writeText(lines.joinToString("")) }

    private fun index() = SearchIndex(store, transcripts = { transcripts.listFiles { f -> f.extension == "jsonl" }!!.toList() })

    @Test
    fun `the words of every conversation are found`() {
        transcript(a, meta(a), traffic("the meters do not shrink"), said("user", "u1", "the meters do not shrink"), said("agent", "a1", "I will let them give way"))
        transcript(b, meta(b), said("user", "u2", "почему баланс не показывается"))

        val index = index()
        assertTrue(index.refresh(force = true))
        assertEquals(3, index.size)

        val found = index.search("баланс", conversation = null, onlyThatChat = false, limit = 10)
        assertEquals(listOf("u2"), found.hits.map { it.message.uuid })
        // The conversation is the thread named at the end of the file's name.
        assertEquals(b, found.hits.single().message.conversation)
    }

    @Test
    fun `a file without a thread id in its name is known by its name`() {
        File(transcripts, "hand-made.jsonl").writeText(said("user", "u1", "odd one out"))

        val index = index()
        index.refresh(force = true)

        assertEquals("hand-made", index.search("odd", null, false, 10).hits.single().message.conversation)
    }

    @Test
    fun `a transcript that grew is read from where the reading stopped`() {
        val file = transcript(a, said("user", "u1", "first words"))
        val index = index()
        index.refresh(force = true)
        assertEquals(1, index.size)

        file.appendText(said("agent", "a1", "second words"))
        // A half-written last line stays for the next reading.
        file.appendText("""{"timestamp":"2026-08-14T09:12:00.000Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u9","content":[{"type":"text","text":""")

        assertTrue(index.refresh(force = true))
        assertEquals(2, index.size)
        assertEquals(listOf("a1"), index.search("second", null, false, 10).hits.map { it.message.uuid })

        file.appendText(""""unfinished"}]}}}""" + "\n")
        assertTrue(index.refresh(force = true))
        assertEquals(3, index.size)
        assertEquals(listOf("u9"), index.search("unfinished", null, false, 10).hits.map { it.message.uuid })
    }

    @Test
    fun `the copy on disk is what the next start reads`() {
        transcript(a, said("user", "u1", "kept on disk"))
        index().refresh(force = true)

        // A second instance over the same folder reads the copy rather than the transcript: the
        // transcript is gone, and the words are still there - and so is the title.
        File(transcripts, "rollout-2026-08-14T09-12-00-$a.jsonl").delete()
        val again = SearchIndex(store, transcripts = { emptyList() })
        assertEquals(1, again.size)
        assertEquals("kept on disk", again.titleOf(a))

        // Until it refreshes and sees the transcript is gone.
        assertTrue(again.refresh(force = true))
        assertEquals(0, again.size)
    }

    /**
     * The copy on disk is a cache, and a cache that fails to write must not claim to have written. A
     * failed write used to move the mark of how far the copy reaches all the same, and the next start
     * read on from that mark: the messages of the failed write were out of the search for good.
     */
    @Test
    fun `a write of the copy that fails is not counted as written`() {
        val file = transcript(a, said("user", "u1", "first words"))
        val index = index()
        index.refresh(force = true)

        // The copy cannot be added to: the disk is full, an antivirus holds the file - here it is read-only.
        val copy = store.resolve("$a.jsonl").toFile()
        assertTrue(copy.setWritable(false))
        file.appendText(said("agent", "a1", "words that failed to reach the disk"))
        assertTrue(index.refresh(force = true))
        assertEquals(2, index.size, "the words are in memory whatever the disk did")

        // The next start reads the copy and the manifest: the copy stops short, and the manifest says so
        // rather than claiming the words that never reached it - so the rest is read out of the
        // transcript again, from where the copy really ends.
        assertTrue(copy.setWritable(true))
        val again = index()
        assertEquals(1, again.size, "the copy holds what reached it, and the manifest claims no more")
        assertTrue(again.refresh(force = true))
        assertEquals(2, again.size)
        assertEquals(listOf("a1"), again.search("failed", null, false, 10).hits.map { it.message.uuid })
    }

    /** And the same instance catches the copy up on its own, on the next refresh the disk lets it. */
    @Test
    fun `a copy behind the words is rewritten whole on the next refresh`() {
        val file = transcript(a, said("user", "u1", "first words"))
        val index = index()
        index.refresh(force = true)

        val copy = store.resolve("$a.jsonl").toFile()
        assertTrue(copy.setWritable(false))
        file.appendText(said("agent", "a1", "second words"))
        index.refresh(force = true)

        // The disk is fine again, the transcript has not moved - the refresh still owes it the copy.
        assertTrue(copy.setWritable(true))
        index.refresh(force = true)

        val again = SearchIndex(store, transcripts = { emptyList() })
        assertEquals(2, again.size)
        assertEquals(listOf("a1"), again.search("second", null, false, 10).hits.map { it.message.uuid })
    }

    @Test
    fun `a rewritten transcript is read afresh`() {
        val file = transcript(a, said("user", "u1", "old words that will go"), said("user", "u2", "and these"))
        val index = index()
        index.refresh(force = true)
        assertEquals(2, index.size)

        file.writeText(said("user", "u3", "brand new"))
        assertTrue(index.refresh(force = true))
        assertEquals(1, index.size)
        assertTrue(index.search("old", null, false, 10).hits.isEmpty())
        assertEquals("brand new", index.titleOf(a))
    }

    /** A name read on the very refresh that first reads the thread - not wiped by that reading. */
    @Test
    fun `Codex's name for a thread is its title from the first refresh`() {
        transcript(a, meta(a), said("user", "u1", "fix the search button please"))
        val names = mutableMapOf(a to "Search button fix")
        val index = SearchIndex(store, transcripts = { transcripts.listFiles { f -> f.extension == "jsonl" }!!.toList() }, names = { names })

        index.refresh(force = true)
        assertEquals("Search button fix", index.titleOf(a))

        names[a] = "Search button, fixed"
        index.refresh(force = true)
        assertEquals("Search button, fixed", index.titleOf(a))
    }

    @Test
    fun `without a name of Codex's own the title is the person's first words`() {
        transcript(a, said("user", "u1", "\\n  Fix the search button in compact layout please\\nand more"))
        transcript(b, said("agent", "a1", "nobody asked me anything"))
        val long = "0199a000-0000-7000-8000-00000000000c"
        transcript(long, said("user", "u2", "word ".repeat(40)))

        val index = index()
        index.refresh(force = true)

        assertEquals("Fix the search button in compact layout please", index.titleOf(a))
        assertEquals("untitled", index.titleOf(b))
        assertEquals(80, index.titleOf(long).length)
        assertEquals("untitled", index.titleOf("never-seen"))
        assertEquals(SessionSnapshot.TITLE_HEURISTIC, index.titleSourceOf(a))
        assertEquals(1, index.messagesIn(a))
    }

    /**
     * Codex keeps one name per thread, and both kinds land in it: the panel's model's (see AutoTitles) and
     * a person's. The one the model gave is the model's; anything else is a person's and outranks it.
     */
    @Test
    fun `a name the model did not give is the person's`() {
        transcript(a, meta(a), said("user", "u3", "hi"))
        transcript(b, meta(b), said("user", "u4", "hello"))
        val names = mapOf(a to "Named by me", b to "Named by the model")
        val index = SearchIndex(
            store,
            transcripts = { transcripts.listFiles { f -> f.extension == "jsonl" }!!.toList() },
            names = { names },
            givenByModel = { mapOf(b to "Named by the model") },
        )
        index.refresh(force = true)

        assertEquals("Named by me", index.titleOf(a))
        assertEquals(SessionSnapshot.TITLE_USER, index.titleSourceOf(a))
        assertEquals("Named by the model", index.titleOf(b))
        assertEquals(SessionSnapshot.TITLE_LLM, index.titleSourceOf(b))
    }

    // A model's name renamed by a person is the person's from then on: it no longer matches the book.
    @Test
    fun `a model's name renamed by hand becomes the person's`() {
        transcript(a, meta(a), said("user", "u3", "hi"))
        val names = mutableMapOf(a to "Named by the model")
        val index = SearchIndex(
            store,
            transcripts = { transcripts.listFiles { f -> f.extension == "jsonl" }!!.toList() },
            names = { names },
            givenByModel = { mapOf(a to "Named by the model") },
        )
        index.refresh(force = true)
        assertEquals(SessionSnapshot.TITLE_LLM, index.titleSourceOf(a))

        names[a] = "Named by me"
        index.refresh(force = true)

        assertEquals("Named by me", index.titleOf(a))
        assertEquals(SessionSnapshot.TITLE_USER, index.titleSourceOf(a))
    }

    @Test
    fun `the corpus is the conversations as text, one header per message`() {
        transcript(a, said("user", "u1", "Кнопка поиска", at = "2026-08-14T09:12:00.000Z"), said("agent", "a1", "Looked at it", at = "2026-08-14T09:13:00.000Z"))
        val index = index()
        index.refresh(force = true)

        val corpus = index.corpus()
        val text = Files.readString(corpus.resolve("$a.txt"))
        assertTrue(text.contains("## u1 2026-08-14T09:12:00Z you\nКнопка поиска\n"), text)
        assertTrue(text.contains("## a1 2026-08-14T09:13:00Z claude\nLooked at it\n"), text)

        val sessions = Files.readString(corpus.resolve(SearchIndex.SESSIONS_FILE))
        assertTrue(sessions.contains("$a | "), sessions)
        assertTrue(sessions.contains("| 2 | Кнопка поиска"), sessions)

        assertNotNull(index.lookup(a, "a1"))
    }

    @Test
    fun `a conversation that went away leaves the corpus too`() {
        transcript(a, said("user", "u1", "staying"))
        val going = transcript(b, said("user", "u2", "leaving"))
        val index = index()
        index.refresh(force = true)
        val corpus = index.corpus()
        assertTrue(Files.exists(corpus.resolve("$b.txt")))

        assertTrue(going.delete())
        index.refresh(force = true)
        index.corpus()

        assertTrue(Files.exists(corpus.resolve("$a.txt")))
        assertTrue(!Files.exists(corpus.resolve("$b.txt")))
    }

    @Test
    fun `a refresh is not repeated within its interval unless forced`() {
        transcript(a, said("user", "u1", "words"))
        val index = index()
        assertTrue(index.refresh(now = 10_000))
        transcript(b, said("user", "u2", "more"))
        assertTrue(!index.refresh(now = 10_500))
        assertTrue(index.refresh(now = 10_000 + SearchIndex.REFRESH_INTERVAL_MS))
        assertEquals(2, index.size)
    }
}
