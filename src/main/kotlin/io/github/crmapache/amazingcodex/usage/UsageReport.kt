package io.github.crmapache.amazingcodex.usage

import io.github.crmapache.amazingcodex.codex.ModelNames
import io.github.crmapache.amazingcodex.stats.DayRecord
import io.github.crmapache.amazingcodex.stats.MinuteSet
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.SortedMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The anonymous usage report: what a machine's days in the panel come to, in counts.
 *
 * Built out of the statistics book (see DayRecord) by picking figures by name - never by copying a record
 * and taking things out. That is the whole of the promise and it is kept by the shape of this file: a new
 * field added to the book does not travel until somebody writes it in here, and every name that does
 * travel is held to a list first.
 *
 * - The day's counts: minutes, messages, answers, edits and the like. Numbers only.
 * - The stretches of work that day - how long each sitting lasted, in minutes, never when it began.
 * - Tools by their built-in name (every MCP tool is "MCP", so no server's name leaves), models by their id
 *   in Codex's own catalogue (a model added by hand, or a name not shaped like OpenAI's, is "Other"),
 *   built-in commands by name (a prompt or a skill of one's own is "custom"), and the panel's features by
 *   the ids in UsageFeatures.
 * - The environment on one line: versions, the operating system's family, the processor's.
 * - The settings, as words the plugin itself chose ("bottom", "dark") and counts - never a model's name,
 *   a key, a path or a relay's address.
 *
 * What never travels, because nothing here reads it: the project's name or key, the hashes of the files
 * edited (only how many), the hours of the day, the tokens and the cost, the account, the text of
 * anything.
 *
 * The service it goes to is shared with the plugin this one was forked from and keeps the two apart by
 * [PRODUCT]; the names in a day are held there to this plugin's lists, the same as they are held here.
 */
internal object UsageReport {

    data class Environment(
        val plugin: String,
        val ide: String,
        val ideVersion: String,
        val os: String,
        val arch: String,
        val cli: String,
        val lang: String,
    )

    /** One day as it travels, with a digest of it - what tells a day that changed from one already sent. */
    data class Day(val day: String, val json: JsonObject, val digest: String)

    const val SCHEMA = 1

    /**
     * Which plugin a report is from, for the service both plugins report to. It is the address the figures
     * are filed under rather than a figure, so the service refuses a report naming a plugin it does not
     * count instead of guessing - and a report without it would be filed as the original plugin's, whose
     * published versions send none. The deletion names it too (see UsageSender.forget).
     */
    const val PRODUCT = "acx"

    /** A gap longer than this ends a sitting: half an hour away from the panel is a break, not a pause. */
    const val SITTING_GAP_MINUTES = 30

    /** How far back a report reaches. A day not sent within a fortnight is let go rather than sent late. */
    const val DAYS_BACK = 14L

    /**
     * The days worth sending: from [from] on (the day the person said yes, or two weeks back, whichever is
     * later), and only those that saw anything at all.
     *
     * [ownModels] are the models added by hand in the panel (see CodexPreferences.customModels): each of
     * them travels as "Other", whatever its name looks like (see [modelName]).
     */
    fun days(together: SortedMap<String, DayRecord>, from: String, ownModels: Collection<String>): List<Day> =
        together.tailMap(from).mapNotNull { (day, record) ->
            if (!record.isActive() && record.features.isEmpty()) return@mapNotNull null
            val json = dayJson(day, record, ownModels)
            Day(day, json, digest(json))
        }

    fun report(id: String, environment: Environment, settings: Map<String, Any>, days: List<Day>): JsonObject =
        buildJsonObject {
            put("schema", SCHEMA)
            put("product", PRODUCT)
            put("install", id)
            put(
                "env",
                buildJsonObject {
                    put("plugin", environment.plugin)
                    put("ide", environment.ide)
                    put("ideVersion", environment.ideVersion)
                    put("os", environment.os)
                    put("arch", environment.arch)
                    put("cli", environment.cli)
                    put("lang", environment.lang)
                },
            )
            put("settings", buildJsonObject { for ((name, value) in settings) put(name, primitive(value)) })
            put("days", buildJsonArray { days.forEach { add(it.json) } })
        }

    fun dayJson(day: String, record: DayRecord, ownModels: Collection<String>): JsonObject = buildJsonObject {
        put("day", day)
        put("minutes", record.minutes.count())
        put("conversations", record.sessions)
        put("prompts", record.prompts)
        put("turns", record.turns)
        put("turnSeconds", record.turnMillis / 1000)
        put("phonePrompts", record.phonePrompts)
        put("phoneActions", record.phoneActions)
        put("forks", record.forks)
        put("edits", record.edits)
        put("linesAdded", record.linesAdded)
        put("linesRemoved", record.linesRemoved)
        put("filesEdited", record.files.size)
        put("permissionsAsked", record.permissionsAsked)
        put("permissionsDenied", record.permissionsDenied)
        put("plansApproved", record.plansApproved)
        put("todosDone", record.todosDone)
        put("attachments", record.attachments)
        put("quotes", record.quotes)
        put("ranOutFiveHour", record.ranOutFiveHour)
        put("watched", record.watched)
        put("mcpConnected", record.mcpConnected)
        put("plugins", record.plugins)
        put("longestConversation", record.longestSession)
        put("sittings", buildJsonArray { sittings(record.minutes).forEach { add(it) } })
        put("tools", counts(record.tools, ::toolName))
        put("models", counts(record.models) { modelName(it, ownModels) })
        put("slash", counts(record.slash.associateWith { 1 }, ::commandName))
        put("features", counts(record.features.filterKeys { UsageFeatures.isKnown(it) }) { it })
    }

    /**
     * How long each stretch of work lasted, in minutes, in the order they came - the lengths and nothing
     * of when. A stretch ends at a gap of more than [SITTING_GAP_MINUTES]; a minute on its own is a
     * stretch of one.
     */
    fun sittings(minutes: MinuteSet): List<Int> {
        val marked = minutes.minutes()
        if (marked.isEmpty()) return emptyList()

        val out = mutableListOf<Int>()
        var start = marked[0]
        var end = marked[0]
        for (index in 1 until marked.size) {
            val minute = marked[index]
            if (minute - end > SITTING_GAP_MINUTES) {
                out += end - start + 1
                start = minute
            }
            end = minute
        }
        out += end - start + 1
        return out
    }

    /** A tool by its built-in name. The book already folds MCP tools into one name; anything odd is "other". */
    fun toolName(name: String): String = if (TOOL.matches(name)) name else "other"

    /**
     * A model by its id in Codex's catalogue - "gpt-5.6-sol", "o3", "codex-mini-latest" - or "Other".
     *
     * The catalogue changes with every Codex release, so it is the id's shape that is held rather than a
     * list: lower case, a start only OpenAI's own families have, dots and hyphens between letters and
     * digits, and a short length. The service holds a report to the same shape (modelName in its
     * products.ts), so a name that passes here is one it keeps.
     *
     * A model added by hand ([ownModels], see CodexPreferences.customModels) is "Other" whatever it looks
     * like: it is there because Codex does not offer it - a proxy's model, a fine-tune - and its name is
     * somebody's own choice, which may say whose it is even when it starts with "gpt-".
     */
    fun modelName(name: String, ownModels: Collection<String>): String {
        val id = modelIdOf(name)
        if (ownModels.any { ModelNames.same(it, id) }) return OTHER_MODEL
        return if (id.length <= MODEL_LENGTH && CODEX_MODEL.matches(id)) id else OTHER_MODEL
    }

    /**
     * The id behind the name the book keeps a model under. The statistics card's rows are dressed for
     * reading (StatsCollector.familyOf: "gpt-5.6-sol" is written down as "GPT-5.6 Sol"), and every day
     * already in the book is written that way, so the report undresses it rather than the book changing:
     * lower case, and the spaces back into the hyphens they were. A name that was never dressed - "o3",
     * "codex-mini-latest" - comes out as it went in.
     */
    fun modelIdOf(name: String): String = name.trim().lowercase().replace(' ', '-')

    /** A built-in command by its name; a prompt of one's own (`/prompts:<name>`) or a skill is "custom". */
    fun commandName(name: String): String = if (name in BUILT_IN_COMMANDS) name else "custom"

    private fun counts(map: Map<String, Int>, rename: (String) -> String): JsonObject {
        val folded = LinkedHashMap<String, Int>()
        for ((name, count) in map) {
            if (count <= 0) continue
            val key = rename(name)
            folded[key] = (folded[key] ?: 0) + count
        }
        return buildJsonObject { for ((name, count) in folded) put(name, count) }
    }

    private fun primitive(value: Any): JsonElement = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    fun digest(json: JsonObject): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(json.toString().toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(16)
    }

    /**
     * The tool names the panel draws Codex's items under, which are Claude Code's - Read, Edit, Bash,
     * WebSearch, TodoWrite (see CodexDialect). MCP arrives as "MCP" already.
     */
    private val TOOL = Regex("^[A-Z][A-Za-z]{1,39}$")

    /** An id of Codex's catalogue, by shape - see [modelName]. The same pattern as the service's. */
    private val CODEX_MODEL = Regex("^(?:gpt-[a-z0-9]+|o\\d[a-z0-9]*|codex-[a-z0-9]+)(?:[.-][a-z0-9]+)*$")

    private const val MODEL_LENGTH = 40

    private const val OTHER_MODEL = "Other"

    /**
     * The commands the panel knows for Codex, since app-server knows none: the ones the IDE turns into
     * requests (see CodexCommands) and the ones the panel carries out itself (panelCommands and
     * builtinCommands in catalog.ts; `/side` is Codex's own name for `/btw`). A name not on this list - a
     * prompt from ~/.codex/prompts, a skill - is somebody's own and travels as "custom". The service holds
     * the same list (COMMANDS in its products.ts) and its test reads this one.
     */
    val BUILT_IN_COMMANDS: Set<String> = setOf(
        "compact", "clear", "new", "init", "review", "btw", "side", "rename", "config", "model", "effort",
        "resume", "fork", "login", "logout",
    )
}
