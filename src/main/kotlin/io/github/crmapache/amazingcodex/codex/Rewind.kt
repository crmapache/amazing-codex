package io.github.crmapache.amazingcodex.codex

import java.io.File
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Cutting a conversation back to one of the person's messages - what Esc Esc does in Codex's terminal, which
 * is a screen of its own there and no request a client sends as such.
 *
 * The conversation part is Codex's own `thread/revert {threadId, beforeTurnId}`: the turn the message began
 * and every later one leave the thread's history, and the agent asked afterwards remembers only what came
 * before (measured on 0.160 - three turns in, a revert before the third, and asked what it had been asked
 * last, the agent named the second). Codex starts a new file for the thread rather than rewriting the old
 * one (see CodexRollout), answers with the thread and says `thread/reverted` to its listeners. A turn still
 * running is stopped first (see CodexSession.rewind).
 *
 * The rest Codex leaves to its client, and the conversation does it around the revert, the way Claude Code's
 * control requests did it for the panel this was built for:
 * - which turn a message began: the panel names a message by its own uuid while it is being written and by
 *   Codex's item id once it is read from the history (see CodexSession.turnOf);
 * - whether the conversation moved on while the person was deciding - a phone's message, sent while the
 *   dialog stood open at the desk, stops the rewind instead of being thrown away unread (see
 *   CodexSession.movedPast);
 * - putting the files back: Codex keeps every patch it applied, and they are undone newest first (see
 *   CodeRewind). A file changed since by something else stops that part before anything is written.
 *
 * This object only reads and writes the shapes; the conversation does the asking.
 */
internal object Rewind {

    /**
     * Why a rewind did not happen, as the panel words it - several of Codex's refusals are one thing to a
     * person reading the dialog.
     */
    enum class Refusal(val wire: String) {
        /** Something is still being said: a turn the interrupt could not stop in time. */
        BUSY("busy"),

        /** The conversation does not hold the message any more - a rewind took it already, or it never got there. */
        GONE("gone"),

        /** The conversation moved on while the person was deciding: a message nobody here has seen yet. */
        MOVED("moved"),

        /** The message sits inside work that cannot be cut in two - written into a turn rather than starting one. */
        MID_CALL("midCall"),

        /** Codex could not write the cut down, so it did not make it. */
        NOT_SAVED("notSaved"),

        /** The code part was asked for and cannot be done - see [Code]. Nothing was touched. */
        CODE("code"),

        /** This version of Codex does not know the request at all - `thread/revert` came with 0.160. */
        UNSUPPORTED("unsupported"),

        /** There is no process to ask, and none could be raised. */
        NO_PROCESS("noProcess"),

        /**
         * The process went while the rewind was out - stopped, restarted, moved to another account. Whether Codex
         * got as far as writing the cut down is not known, so the panel cuts nothing and says so.
         */
        ENDED("ended"),

        /** Anything else Codex said - its own words travel beside it. */
        OTHER("other");

        companion object {
            /**
             * A refusal of `thread/revert`, or of a request on the way to it, by Codex's answer: an error with a
             * code and words rather than a reason of its own, so they are what there is to go by.
             */
            fun ofError(code: Int, message: String): Refusal = when {
                code == AppServer.PROCESS_GONE -> ENDED
                code == AppServer.TIMED_OUT -> BUSY
                code == AppServer.METHOD_NOT_FOUND || message.contains("method not found", ignoreCase = true) ||
                    message.contains("unknown variant", ignoreCase = true) -> UNSUPPORTED
                message.contains("in progress", ignoreCase = true) || message.contains("running", ignoreCase = true) -> BUSY
                message.contains("not found", ignoreCase = true) || message.contains("no such", ignoreCase = true) -> GONE
                message.contains("persist", ignoreCase = true) -> NOT_SAVED
                else -> OTHER
            }
        }
    }

    /** What the files since a message come to - what the dialog offers before anything is touched. */
    sealed interface Code {
        /** The files the agent's patches changed since then, and by how much. */
        data class Ready(val files: List<String>, val insertions: Int, val deletions: Int) : Code

        /** No patch of the agent's since then. */
        data object None : Code

        /**
         * Files something else changed after the agent's patches - a command, the person, another tool - so the
         * patches no longer fit them and cannot be undone (see CodeRewind). Nothing is touched.
         */
        data class Changed(val files: List<String>) : Code

        /** Codex could not say what was patched - [detail] is its own words. */
        data class Unavailable(val detail: String) : Code
    }

    /** What undoing the patches came to, in the dialog's states. */
    fun codeOf(plan: CodeRewind.Plan): Code = when (plan) {
        is CodeRewind.Plan.Ready -> Code.Ready(plan.files.keys.toList(), plan.insertions, plan.deletions)
        CodeRewind.Plan.None -> Code.None
        is CodeRewind.Plan.Conflict -> Code.Changed(plan.files)
    }

    /**
     * Why the code cannot be put back, as the detail of a [Refusal.CODE] - the preview's own state name, so the
     * panel words it the same way the dialog did (with the files' names after it for [Code.Changed]), or
     * Codex's words when it said something else.
     */
    fun detailOf(code: Code): String = when (code) {
        is Code.Unavailable -> code.detail
        is Code.Changed -> CHANGED + code.files.take(MAX_FILES_LISTED).joinToString(", ") { File(it).name }
        Code.None -> "none"
        is Code.Ready -> ""
    }

    /** How a [Code.Changed] detail begins - see changedFiles in RewindDialog.tsx. */
    private const val CHANGED = "changed:"

    /**
     * [code] with its files said from [root] when they are inside it, and from `~` when they are elsewhere
     * under [home] - see CodexSessionHub.previewRewind. The second happens: a patch writes wherever the
     * sandbox lets it. Either separator, since on Windows the IDE says the project's folder with `/`.
     *
     * Only the preview: the files read again after a restore need the whole path, and get it from the plan.
     */
    fun relativeTo(code: Code, root: String?, home: String? = System.getProperty("user.home")): Code {
        val said = { file: String ->
            inside(file, root) ?: inside(file, home)?.let { "~" + file[file.length - it.length - 1] + it } ?: file
        }
        return when (code) {
            is Code.Ready -> code.copy(files = code.files.map(said))
            is Code.Changed -> code.copy(files = code.files.map(said))
            else -> code
        }
    }

    /**
     * What is left of [file] past [folder] and the separator after it - null when it is not inside. The two
     * are compared with their separators made one: on Windows the IDE says the project's folder with `/`
     * and Codex says its files with `\`, and a drive letter may come in either case.
     */
    private fun inside(file: String, folder: String?): String? {
        if (folder.isNullOrEmpty()) return null
        val base = folder.replace('\\', '/').trimEnd('/')
        val plain = file.replace('\\', '/')
        if (base.isEmpty() || plain.length <= base.length + 1 || plain[base.length] != '/') return null
        if (!plain.startsWith(base, ignoreCase = DRIVE.matches(base))) return null
        return file.substring(base.length + 1)
    }

    private val DRIVE = Regex("^[A-Za-z]:.*")

    /** What the code part of a rewind came to, once the conversation part is settled. */
    enum class Files(val wire: String) {
        /** Not asked for. */
        SKIPPED("skipped"),

        /** Put back. */
        RESTORED("restored"),

        /** Asked for, checked beforehand, and still refused when it came to it - the conversation went anyway. */
        FAILED("failed"),
    }

    /** What the whole rewind came to - the asker's answer (see [outcomeJson]). */
    sealed interface Outcome {
        /**
         * [conversation] - the messages were dropped; [prefill] is the dropped message's text then. [changed]
         * is the files put back, for the IDE to read again.
         */
        data class Done(
            val conversation: Boolean,
            val prefill: String,
            val files: Files,
            val changed: List<String>,
            val filesDetail: String = "",
        ) : Outcome

        data class Refused(val refusal: Refusal, val detail: String) : Outcome
    }

    /**
     * Whether [text] is a uuid in the shape the panel and Codex name messages and turns by - the only thing a
     * client may name a message by. Anything else is not passed on.
     */
    fun isUuid(text: String?): Boolean = text != null && UUID.matches(text)

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** The answer to the dialog opening: what restoring the code would put back. */
    fun previewJson(sessionId: String, uuid: String, code: Code): String = buildJsonObject {
        put("type", "rewindPreview")
        put("sessionId", sessionId)
        put("uuid", uuid)
        putJsonObject("code") { writeCode(this, code) }
    }.toString()

    private fun writeCode(builder: JsonObjectBuilder, code: Code) {
        when (code) {
            is Code.Ready -> {
                builder.put("state", "ready")
                builder.putJsonArray("files") { code.files.take(MAX_FILES_LISTED).forEach { add(JsonPrimitive(it)) } }
                builder.put("count", code.files.size)
                builder.put("insertions", code.insertions)
                builder.put("deletions", code.deletions)
            }
            Code.None -> builder.put("state", "none")
            is Code.Changed -> {
                builder.put("state", "changed")
                builder.putJsonArray("files") { code.files.take(MAX_FILES_LISTED).forEach { add(JsonPrimitive(it)) } }
                builder.put("count", code.files.size)
            }
            is Code.Unavailable -> {
                builder.put("state", "unavailable")
                builder.put("detail", code.detail)
            }
        }
    }

    /**
     * How many paths the preview carries at most. A rewind over a long turn can touch hundreds, a phone's
     * frame is 256 KB, and the dialog shows a handful with the count beside them anyway.
     */
    const val MAX_FILES_LISTED = 40

    /** The answer to whoever pressed the button - and only to them: the field the message goes back into is theirs. */
    fun outcomeJson(sessionId: String, uuid: String, outcome: Outcome): String = buildJsonObject {
        put("type", "rewindOutcome")
        put("sessionId", sessionId)
        put("uuid", uuid)
        when (outcome) {
            is Outcome.Done -> {
                put("ok", true)
                put("conversation", outcome.conversation)
                put("prefill", outcome.prefill)
                put("files", outcome.files.wire)
                if (outcome.filesDetail.isNotEmpty()) put("detail", outcome.filesDetail)
            }
            is Outcome.Refused -> {
                put("ok", false)
                put("reason", outcome.refusal.wire)
                if (outcome.detail.isNotEmpty()) put("detail", outcome.detail)
            }
        }
    }.toString()

    /**
     * Code a fork was to take along (the dialog's "In a new tab" with both chosen) and could not put back, said
     * into the parent tab's feed as a code the panel words - the dialog is gone by then. `FORK_CODE|reason|detail`
     * (see forkCodeOf in feed/rewind.ts).
     */
    fun forkCodeError(outcome: Outcome.Refused): String = "FORK_CODE|${outcome.refusal.wire}|${outcome.detail}"

    /**
     * The conversation was cut at [uuid], said to every client and into the journal: each one drops that
     * message and everything after it from its feed, and a feed rebuilt later finds the journal already cut
     * (see CodexSessionHub.rewind). [fromSeq] is where the journal's cut began (see SessionJournal.cutFrom):
     * a client without the message that held anything numbered from there on holds only what came after it.
     */
    fun rewoundJson(sessionId: String, uuid: String, fromSeq: Long): String = buildJsonObject {
        put("type", "rewound")
        put("sessionId", sessionId)
        put("uuid", uuid)
        put("fromSeq", fromSeq)
    }.toString()
}
