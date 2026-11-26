package io.github.crmapache.amazingcodex.feedback

import com.intellij.openapi.project.Project
import io.github.crmapache.amazingcodex.codex.CodexSessionHub
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The IDE's side of the feedback screen: the debug report it offers to copy.
 *
 * The screen used to be a form whose message, files and report this desk posted to a service that put them
 * in the author's Telegram. That is gone - the screen now names the two ways a change gets in (a tip with
 * the wish written in it, a pull request) and takes no message at all - and with it went the one request
 * the plugin made of the internet on a person's behalf. What stayed is the report: built here from what the
 * plugin itself saw, shown whole, and copied by the person into a pull request or a reply.
 *
 * It lives with the panel's window rather than with the conversation hub, and that placement is still the
 * point. Everything about a conversation goes through SessionCommands, which is also the door a paired
 * phone comes in by; this goes through the window's own handler instead, where a remote client does not
 * arrive at all (see CodexPanel). The report is built out of a conversation's journal, and a message that
 * reads that should be unreachable from outside by construction, not only by being on a list - though it
 * is on that list too (see RemoteCommands).
 */
internal class FeedbackDesk(
    private val project: Project,
    private val hub: CodexSessionHub,
    /** How an answer gets back to the panel - the window's own channel, not the conversation's journal. */
    private val answer: (String) -> Unit,
) {

    /**
     * The screen has been opened. The CLI's version costs a process, so it is started now: by the time the
     * report one step further is asked for it will be there, and nobody waits on a screen for it.
     */
    fun opened() {
        FeedbackEnvironment.warmUp(project.basePath)
    }

    /** The report for one conversation, built afresh. */
    fun report(sessionId: String) {
        val text = FeedbackReport.build(
            environment = FeedbackEnvironment.lines(),
            journal = hub.journalTail(sessionId, JOURNAL_ENTRIES, JOURNAL_CHARS),
            events = DiagnosticsLog.getInstance().tail(),
        )

        answer(buildJsonObject { put("type", "feedbackLog"); put("text", text) }.toString())
    }

    private companion object {
        /**
         * How much of the journal the report looks at. A few hundred entries is the last several minutes
         * of a conversation, which is where a bug that has just been noticed lives - and the ceiling in
         * characters is there because a handful of entries carrying whole files outweighs a great many
         * small ones.
         */
        const val JOURNAL_ENTRIES = 400
        const val JOURNAL_CHARS = 2L * 1024 * 1024
    }
}
