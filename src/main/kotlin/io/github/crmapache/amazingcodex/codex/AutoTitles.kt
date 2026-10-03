package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.diagnostic.thisLogger
import io.github.crmapache.amazingcodex.scenario.ScenarioFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The names this panel's model gave to threads, by thread id - so that a name can be told from one a
 * person gave.
 *
 * Codex keeps one name per thread (`thread/name/set`, read back by `thread/list` and the session index),
 * and both kinds land in it: the one asked of a small model after the first message (see CodexTitles)
 * and the one a person typed into the tab, into `/rename`, or into another Codex client. The difference
 * matters wherever a name is ranked - a tab, the history, the search: a person's name is shown whole and
 * outranks everything, while a model's is a stand-in that a person's rename replaces, and a past
 * conversation reopened with its name taken for a person's would never be renamed again.
 *
 * So the model's names are written down here when they are set, and anything else in that field is a
 * person's. Codex itself names no thread on its own, which is what makes "not in this book" mean "a
 * person's". A name the model gave and a person then changed reads as the person's, because it no longer
 * matches; one the person typed identical to the model's reads as the model's, and loses nothing for it.
 *
 * One small file per project, kept on this machine by the rules of the scenario books (see ScenarioFile),
 * with the newest names kept and the oldest dropped past [LIMIT].
 */
internal class AutoTitles(private val store: ScenarioFile) {

    constructor(workingDirectory: String?) : this(ScenarioFile.of(FOLDER, workingDirectory, FILE))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Book(val names: List<Named> = emptyList())

    @Serializable
    private data class Named(val id: String, val name: String)

    /** Every name the model gave, by thread id; empty when the file cannot be read. */
    fun all(): Map<String, String> = store.underLock { known().associate { it.id to it.name } }

    /** The model has just named [threadId] - see CodexSession.requestTitle. */
    fun note(threadId: String, name: String) {
        if (threadId.isBlank() || name.isBlank()) return

        store.underLock {
            val kept = known().filter { it.id != threadId } + Named(threadId, name)
            store.put(json.encodeToString(Book(kept.takeLast(LIMIT))))
        }
    }

    private fun known(): List<Named> =
        when (val stored = store.read()) {
            ScenarioFile.Stored.Missing, ScenarioFile.Stored.Unreadable -> emptyList()
            is ScenarioFile.Stored.Text ->
                runCatching { json.decodeFromString<Book>(stored.text).names }
                    .onFailure { thisLogger().warn("Could not read the names the model gave", it) }
                    .getOrDefault(emptyList())
        }

    companion object {
        /**
         * Where a thread's name came from, in the tab's terms (see SessionSnapshot): nothing given is a
         * guess from the first line, the model's own name is the model's, and anything else is a person's.
         */
        fun sourceOf(name: String, givenByModel: String?): String = when {
            name.isBlank() -> SessionSnapshot.TITLE_HEURISTIC
            name == givenByModel -> SessionSnapshot.TITLE_LLM
            else -> SessionSnapshot.TITLE_USER
        }

        private const val FOLDER = "auto-titles"
        private const val FILE = "titles.json"
        private const val LIMIT = 4_000
    }
}
