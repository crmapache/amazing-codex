package io.github.crmapache.amazingcodex.codex

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Naming a conversation by its first message, in a throwaway thread of the conversation's own process.
 *
 * Codex does not name threads through this protocol, and a tab called by the first sixty characters of a
 * message reads like the stand-in it is. The name is one short turn: an ephemeral thread (nothing of it is
 * written to disk), Codex's base instructions replaced by one sentence about titles so the turn costs a
 * few hundred tokens rather than the full agent prompt, the lowest effort, and no project documents.
 *
 * The thread's events arrive on the same process as the conversation's, and the conversation hands every
 * notification that is not its own here (see CodexSession.notification).
 */
internal object CodexTitles {

    private class Asked(val server: AppServer, val onTitle: (String) -> Unit, var text: String = "")

    private val asked = ConcurrentHashMap<String, Asked>()

    fun ask(server: AppServer, workingDirectory: String?, model: String, description: String, onTitle: (String) -> Unit) {
        server.request(
            "thread/start",
            buildJsonObject {
                workingDirectory?.let { put("cwd", it) }
                if (model.isNotEmpty()) put("model", model)
                put("ephemeral", true)
                put("approvalPolicy", "never")
                put("sandbox", PermissionModes.SANDBOX_READ_ONLY)
                put("baseInstructions", INSTRUCTIONS)
                putJsonObject("config") { put("project_doc_max_bytes", 0) }
            },
            onResult = { result ->
                val thread = ((result as? JsonObject)?.get("thread") as? JsonObject)?.let { AppServer.text(it["id"]) }.orEmpty()
                if (thread.isEmpty()) return@request
                asked[thread] = Asked(server, onTitle)

                server.request(
                    "turn/start",
                    buildJsonObject {
                        put("threadId", thread)
                        putJsonArray("input") {
                            addJsonObject {
                                put("type", "text")
                                put("text", description)
                                putJsonArray("text_elements") {}
                            }
                        }
                        put("effort", "low")
                        put("summary", "none")
                    },
                    onError = { done(thread) },
                )

                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { done(thread) },
                    GIVE_UP_SECONDS,
                    TimeUnit.SECONDS,
                )
            },
        )
    }

    /** A notification of some thread that is not a conversation's own - ours, if we are waiting on it. */
    fun notification(method: String, params: JsonObject) {
        val thread = AppServer.text(params["threadId"])
        val waiting = asked[thread] ?: return

        when (method) {
            "item/completed" -> {
                val item = params["item"] as? JsonObject ?: return
                if (AppServer.text(item["type"]) == "agentMessage") waiting.text = AppServer.text(item["text"])
            }

            "turn/completed" -> {
                done(thread)
                clean(waiting.text)?.let(waiting.onTitle)
            }
        }
    }

    /**
     * Stop waiting on a title thread, and let the process go of it: an ephemeral thread stays loaded until
     * it is unsubscribed from, and a conversation names itself once per tab over a long day.
     */
    private fun done(thread: String) {
        val waiting = asked.remove(thread) ?: return
        waiting.server.request("thread/unsubscribe", buildJsonObject { put("threadId", thread) })
    }

    /** One line, no quotes, no trailing full stop, and not a paragraph pretending to be a title. */
    internal fun clean(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val bare = line.trim('"', '\'', '«', '»', '“', '”', '`', '*', '#', ' ').removeSuffix(".").trim()
        return bare.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE }
    }

    private const val MAX_TITLE = 80
    private const val GIVE_UP_SECONDS = 60L

    private val INSTRUCTIONS = """
        You name conversations. Read the message below - it is the first message of a conversation with
        a coding agent - and reply with a title of two to six words describing its subject, in the
        language the message is written in. Reply with the title only: no quotes, no full stop, no tools.
    """.trimIndent().replace('\n', ' ')
}
