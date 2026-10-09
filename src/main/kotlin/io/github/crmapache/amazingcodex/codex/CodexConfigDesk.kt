package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The screen of Codex's own settings - what `/config` opens in the panel, and what `/config key=value`
 * writes (see CodexConfig for what is shown and why).
 *
 * Asked of Codex itself, through the shared catalog process (see CodexCatalog): `config/read` for the
 * values and where each comes from, `configRequirements/read` for what an organisation allows, and
 * `experimentalFeature/list` for the switches Codex offers a person. Written the same way, with
 * `config/batchWrite` - always into the person's own `config.toml`, named outright: an added account's
 * Codex home holds that file as a link (see AccountDrawer), and a write that replaced the link with a file
 * would part that account's settings from everybody else's.
 *
 * A write is checked by reading again rather than by Codex's "ok": Codex answers "ok" to a value it
 * writes into the person's file while the project's own config sets the same key above it (measured on
 * 0.152), and a screen that ticked the new value would be showing something no conversation will run on.
 *
 * Machine-wide, like the file: a change is told to every open project's screen, and every conversation
 * with a process is raised again over its thread to read it - between turns, never through one (see
 * CodexSessions.restartAll), since most of these settings are read by a process when it starts.
 */
internal class CodexConfigDesk(
    private val project: Project,
    private val hub: CodexSessionHub,
) {

    /** One write at a time: two at once would each read the file's version and one would be refused. */
    private val changing = AtomicBoolean(false)

    /** Answer the screen: the settings in force here, with the project's own layer above them. */
    fun send() {
        if (CodexExecutable.find() == null) {
            say(error = NO_CLI)
            return
        }
        say(loading = true)
        say(state = read())
    }

    /**
     * Write one setting and answer with the screen read afresh and how it went. [key] and [value] come from
     * a message, so only a key this screen offers is ever written, and only a value that fits it.
     */
    fun change(key: String, value: String) {
        val before = read()
        val setting = before?.settings?.firstOrNull { it.key == key }
        val features = before?.settings.orEmpty().filter { it.key.startsWith("features.") }.map { it.key.removePrefix("features.") }.toSet()
        val wire = setting?.let { CodexConfig.wireValue(key, value, it.options) }

        if (before == null || setting == null || !CodexConfig.writable(key, features) || wire == null || setting.lockedBy != null) {
            say(state = before, outcome = Outcome(key, ok = false, message = ""))
            return
        }
        if (!changing.compareAndSet(false, true)) {
            say(state = before, outcome = Outcome(key, ok = false, message = ""))
            return
        }

        val answer = try {
            write(
                buildJsonArray {
                    addJsonObject {
                        put("keyPath", key)
                        put("mergeStrategy", "replace")
                        put("value", wire)
                    }
                },
                expectedVersion = before.userVersion,
            )
        } finally {
            changing.set(false)
        }

        val after = read()
        val now = after?.settings?.firstOrNull { it.key == key }
        val outcome = when {
            answer.error != null -> Outcome(key, ok = false, message = answer.error)
            now?.lockedBy != null -> Outcome(key, ok = false, message = OVERRIDDEN)
            else -> Outcome(key, ok = true, message = "")
        }

        say(state = after, outcome = outcome)
        if (answer.error == null) changed()
    }

    /**
     * Trust this project, or stop trusting it - the same record Codex's terminal writes when it asks
     * "do you trust this folder?" at its first start in one, so a choice made here holds in a terminal
     * too, and one made there holds here.
     *
     * Until a project is trusted Codex reads nothing of its own `.codex/config.toml`, hooks or exec
     * policies, and says so only in a field nobody sees; the panel says it where somebody does (see
     * [projectLayer] and the row raised at a conversation's start).
     */
    fun trust(trusted: Boolean) {
        val path = project.basePath ?: return
        if (!changing.compareAndSet(false, true)) return

        val answer = try {
            // Upserted as an object rather than written by a dotted key: a project's path is full of dots
            // and slashes, and the object is how Codex itself writes it - `[projects."/path"]`.
            write(
                buildJsonArray {
                    addJsonObject {
                        put("keyPath", "projects")
                        put("mergeStrategy", "upsert")
                        putJsonObject("value") {
                            putJsonObject(path) { put("trust_level", if (trusted) "trusted" else "untrusted") }
                        }
                    }
                },
                expectedVersion = null,
            )
        } finally {
            changing.set(false)
        }

        if (answer.error != null) thisLogger().info("Codex would not record the project's trust: ${answer.error}")
        say(state = read(), outcome = Outcome(TRUST_KEY, ok = answer.error == null, message = answer.error.orEmpty()))
        if (answer.error == null) changed()
    }

    /** The project's own layer as Codex sees it now - for the trust row a conversation raises at its start. */
    fun projectLayer(): CodexConfig.ProjectLayer? = read()?.project

    private class State(
        val settings: List<CodexConfig.Setting>,
        val project: CodexConfig.ProjectLayer,
        val userVersion: String?,
    )

    private fun read(): State? {
        val directory = project.basePath
        val config = CodexCatalog.call(
            "config/read",
            buildJsonObject {
                directory?.let { put("cwd", it) }
                put("includeLayers", true)
            },
        ) as? JsonObject ?: return null

        val requirements = (CodexCatalog.call("configRequirements/read") as? JsonObject)?.get("requirements") as? JsonObject
        val features = experimentalFeatures()

        // The panel's own per-turn settings go by what is read here (see CodexConfigDesk.workspaceWrite),
        // so a read for the screen refreshes them as well.
        remember(directory, config)

        return State(
            settings = CodexConfig.settings(config, requirements, features),
            project = CodexConfig.projectLayer(config),
            userVersion = CodexConfig.userVersion(config),
        )
    }

    private fun write(edits: kotlinx.serialization.json.JsonArray, expectedVersion: String?): CodexCatalog.Answer =
        CodexCatalog.ask(
            "config/batchWrite",
            buildJsonObject {
                put("edits", edits)
                put("filePath", userConfigFile().path)
                expectedVersion?.let { put("expectedVersion", it) }
                put("reloadUserConfig", true)
            },
        )

    /** Every open project hears the new picture, and every live conversation reads it at its next start. */
    private fun changed() {
        // What every turn goes by was read before the change (see [workspaceWrite]): read again.
        clearKnown()
        CodexSessionHub.everyHub { other ->
            if (other !== hub) other.codexConfig.send()
            other.conversations.restartAll()
            other.catalog.sendNewTabDefaults()
        }
    }

    private class Outcome(val key: String, val ok: Boolean, val message: String)

    private fun say(state: State? = null, loading: Boolean = false, error: String = "", outcome: Outcome? = null) {
        hub.broadcastProject(
            buildJsonObject {
                put("type", "codexConfig")
                if (loading) put("loading", true)
                if (error.isNotEmpty()) put("error", error)
                putJsonArray("settings") {
                    state?.settings?.forEach { setting ->
                        addJsonObject {
                            put("key", setting.key)
                            putJsonArray("options") { setting.options.forEach { add(it) } }
                            if (setting.free) put("free", true)
                            setting.value?.let { put("value", it) }
                            put("group", setting.group.wire)
                            setting.lockedBy?.let { put("lockedBy", it) }
                        }
                    }
                }
                state?.project?.let { layer ->
                    putJsonObject("project") {
                        put("present", layer.present)
                        put("trusted", layer.trusted)
                        putJsonArray("sets") { layer.sets.forEach { add(it) } }
                    }
                }
                outcome?.let {
                    putJsonObject("outcome") {
                        put("key", it.key)
                        put("ok", it.ok)
                        if (it.message.isNotEmpty()) put("message", it.message.take(MESSAGE_LIMIT))
                    }
                }
            }.toString(),
        )
    }

    companion object {
        /** How the experimental features are read - see [experimentalFeatures]. */
        private const val FEATURES_PER_PAGE = 200
        private const val MAX_FEATURE_PAGES = 10

        /** The screen's words for the ways it can fail - the panel says them in its own language. */
        private const val NO_CLI = "noCli"

        /** The outcome's message for a write that went in and is overruled by the project or a policy. */
        private const val OVERRIDDEN = "overridden"

        /** The outcome's key for the project's trust - not a setting on the list. */
        const val TRUST_KEY = "projects.trust_level"

        private const val MESSAGE_LIMIT = 400

        /** The person's own config file, through any link: an account's home holds it as one. */
        fun userConfigFile(): File {
            val file = File(HostOs.configDirectory(), "config.toml")
            return runCatching { file.canonicalFile }.getOrDefault(file)
        }

        /**
         * What the panel sends with every turn in the workspace-write mode, out of the settings in force:
         * whether the sandbox lets the network through, and which folders beyond the project it may write.
         * The turn names the sandbox outright (see PermissionModes), so these have to be read rather than
         * left to Codex - sent as "no network, no other folders" they silently override the person's own
         * `[sandbox_workspace_write]`.
         *
         * From the last read for this directory: a turn must not wait for one. Null until a read has
         * happened, and a conversation's start asks for one (see [warm]).
         */
        fun workspaceWrite(directory: String?): JsonObject? =
            known[directory.orEmpty()]?.let { (it["config"] as? JsonObject)?.get("sandbox_workspace_write") as? JsonObject }

        /** Whether the settings in force set the reasoning summary - then a turn does not name one (see CodexSession). */
        fun setsSummary(directory: String?): Boolean =
            CodexConfig.valueOf(known[directory.orEmpty()]?.get("config") as? JsonObject, "model_reasoning_summary") != null

        /**
         * Read the settings for [directory] in the background, if they are not known yet or were read long
         * enough ago that a hand edit of the file may have happened since.
         */
        fun warm(directory: String?) {
            val at = readAt[directory.orEmpty()]
            if (at != null && System.currentTimeMillis() - at < FRESH_MS) return
            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute {
                val config = CodexCatalog.call(
                    "config/read",
                    buildJsonObject {
                        directory?.let { put("cwd", it) }
                        put("includeLayers", true)
                    },
                ) as? JsonObject ?: return@execute
                remember(directory, config)
            }
        }

        private fun remember(directory: String?, config: JsonObject) {
            known[directory.orEmpty()] = config
            readAt[directory.orEmpty()] = System.currentTimeMillis()
        }

        private fun clearKnown() {
            readAt.clear()
        }

        private val known = ConcurrentHashMap<String, JsonObject>()
        private val readAt = ConcurrentHashMap<String, Long>()

        /** How long a read stands before a conversation's start reads again - see [warm]. */
        private const val FRESH_MS = 5 * 60 * 1000L
    }

    /**
     * Every experimental feature Codex offers, in one answer of the list's shape (`data`). Asked with params:
     * Codex 0.160 refuses the request without them ("missing field `params`"), and the settings screen then
     * showed no experimental switch at all. Read page by page, with a ceiling on the pages.
     */
    private fun experimentalFeatures(): JsonObject? {
        val all = ArrayList<JsonElement>()
        var cursor: String? = null
        var pages = 0
        while (pages++ < MAX_FEATURE_PAGES) {
            val page = CodexCatalog.call(
                "experimentalFeature/list",
                buildJsonObject {
                    put("limit", FEATURES_PER_PAGE)
                    cursor?.let { put("cursor", it) }
                },
            ) as? JsonObject ?: break
            all += (page["data"] as? JsonArray).orEmpty()
            cursor = AppServer.text(page["nextCursor"]).ifEmpty { null } ?: break
        }
        if (all.isEmpty() && pages == 1) return null
        return buildJsonObject { put("data", JsonArray(all)) }
    }

}
