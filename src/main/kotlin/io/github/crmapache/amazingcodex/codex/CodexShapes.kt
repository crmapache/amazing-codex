package io.github.crmapache.amazingcodex.codex

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Codex's answers about the account and the session, in the shapes the rest of the plugin reads.
 *
 * The same border as CodexDialect, for the answers rather than the events: the usage rings, the model
 * menu and the MCP screen each have one reader that takes one shape (see CodexUsage.parse,
 * ProjectUsage.sendModels, ProjectCatalog.sendMcpServers). Translating here keeps those readers - and
 * their tests - speaking one language.
 */
internal object CodexShapes {

    /**
     * The model list, as `{"models": [...]}` with the fields the menu draws: the name a person picks and
     * Codex is launched with, the label, the description, and - new with Codex - which reasoning efforts
     * the model takes and which one it starts on, and whether it has a fast tier.
     *
     * [configuredModel] and [configuredEffort] are what the person's own config.toml sets (`model`,
     * `model_reasoning_effort`) for the directory the conversation runs in - what Codex runs on a tab left
     * on "Default" and "auto". `model/list` marks its own default with `isDefault` and knows nothing of the
     * config, so a menu labelled from it alone named one model over a tab that ran another; the config's
     * model, when it is on the list, is the default here instead.
     */
    fun models(
        result: JsonElement?,
        configuredModel: String = "",
        configuredEffort: String = "",
        contexts: Map<String, ModelContexts.Limits> = emptyMap(),
    ): JsonObject {
        val data = (result as? JsonObject)?.get("data") as? JsonArray ?: JsonArray(emptyList())
        val ids = data.mapNotNull { (it as? JsonObject)?.let { model -> AppServer.text(model["model"]).ifEmpty { AppServer.text(model["id"]) } } }
        val configured = configuredModel.takeIf { it.isNotEmpty() && it in ids }

        return buildJsonObject {
            putJsonArray("models") {
                for (element in data) {
                    val model = element as? JsonObject ?: continue
                    if (model["hidden"] == JsonPrimitive(true)) continue
                    val id = AppServer.text(model["model"]).ifEmpty { AppServer.text(model["id"]) }
                    if (id.isEmpty()) continue

                    addJsonObject {
                        put("value", id)
                        put("displayName", AppServer.text(model["displayName"]).ifEmpty { id })
                        put("description", AppServer.text(model["description"]))
                        put("resolvedModel", id)
                        put("isDefault", if (configured != null) id == configured else model["isDefault"] == JsonPrimitive(true))
                        val efforts = (model["supportedReasoningEfforts"] as? JsonArray).orEmpty()
                            .map { AppServer.text((it as? JsonObject)?.get("reasoningEffort")) }
                            .filter { it.isNotEmpty() }
                        // What "auto" comes to on this model: the config's effort when the model takes it.
                        val configuredLevel = configuredEffort.takeIf { it in efforts }
                        put("defaultEffort", configuredLevel ?: AppServer.text(model["defaultReasoningEffort"]))
                        if (configuredLevel != null) put("defaultEffortConfigured", true)
                        putJsonArray("efforts") { efforts.forEach { add(JsonPrimitive(it)) } }
                        contexts[id]?.let { limits ->
                            put("standardContext", limits.standardEffective)
                            put("longContext", limits.longEffective)
                        }
                        // Fast mode in the word the rest of the plugin reads (`supportsFastMode`, see
                        // ProjectUsage.sendModels), with Codex's own line about the tier ("1.5x speed, increased
                        // usage") for the menu to quote. A model that says nothing about speeds says nothing here.
                        fastTierOf(model)?.let { tier ->
                            put(SUPPORTS_FAST_MODE, tier.has)
                            tier.description.takeIf { it.isNotEmpty() }?.let { put(FAST_MODE_DESCRIPTION, it) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Which of the [models] the menu's list holds (the `models` array the function above answers with) have fast
     * mode, as CodexAccounts.noteFastModels takes them - value, full name, whether it has it - and one row more for
     * "default", which has it exactly when the
     * model it comes to does (the one marked `isDefault`): a tab that never picked a model is on that value, and a
     * pick of it must not read as "a model without fast mode" (see CodexSessions.setModel).
     *
     * Null when no row says anything either way: a Codex older than the tiers is silence, not "none". Disabled rows
     * are kept - they are still names a conversation may be running on.
     */
    fun fastModels(models: JsonArray): List<Triple<String, String, Boolean>>? {
        val rows = models.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val value = AppServer.text(model["value"]).ifEmpty { return@mapNotNull null }
            Triple(value, AppServer.text(model["resolvedModel"]), (model[SUPPORTS_FAST_MODE] as? JsonPrimitive)?.booleanOrNull)
        }
        if (rows.none { it.third != null }) return null

        val standard = models.firstNotNullOfOrNull { element ->
            (element as? JsonObject)?.takeIf { it["isDefault"] == JsonPrimitive(true) }
        }
        val byDefault = standard?.let { Triple(ModelNames.DEFAULT, "", it[SUPPORTS_FAST_MODE] == JsonPrimitive(true)) }
        return rows.map { (value, resolved, fast) -> Triple(value, resolved, fast == true) } + listOfNotNull(byDefault)
    }

    /** What the catalogue says of a model's fast tier - null when it says nothing about speeds at all. */
    private class FastTier(val has: Boolean, val description: String)

    private fun fastTierOf(model: JsonObject): FastTier? {
        val tiers = model["serviceTiers"] as? JsonArray
        if (tiers != null) {
            val fast = tiers.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { tier -> AppServer.text(tier["id"]) == ServiceTiers.FAST } }
            return FastTier(fast != null, AppServer.text(fast?.get("description")).trim())
        }

        // A Codex from before `serviceTiers` named its speeds in a list of words; it had no line about them.
        val legacy = model["additionalSpeedTiers"] as? JsonArray ?: return null
        return FastTier(legacy.any { AppServer.text(it) == ServiceTiers.LEGACY_FAST }, "")
    }

    /** The fields [models] says fast mode in - the first is Claude Code's word, read as it is by ProjectUsage. */
    const val SUPPORTS_FAST_MODE = "supportsFastMode"
    const val FAST_MODE_DESCRIPTION = "fastModeDescription"

    /**
     * The account's limits, in the shape of the usage answer the rings read (see CodexUsage.parse).
     *
     * Codex reports up to two rolling windows - `primary` and `secondary` - and says how long each one is.
     * The rings are a short window and a long one, so each window goes to the ring its length belongs to
     * rather than by its name: the five-hour window is "primary" on one plan and the only window on another.
     *
     * A business seat can have neither, only a spend limit set by the workspace (`individualLimit`). That
     * is drawn on the third ring, the one for usage past the plan, because that is what it is: money,
     * counted against a cap somebody set. Codex's own word on that cap (`spendControlReached`) rides beside
     * its share as `reached` (see CodexUsage.Extra.reached).
     *
     * [answer] is the whole answer to `account/rateLimits/read` when that is what [snapshot] came out of - see
     * [usageAnswer]. A rolling update leaves it null, and that difference is the one CodexUsage.Tracker needs.
     */
    fun usage(
        snapshot: JsonObject?,
        contextWindow: Int? = null,
        buckets: JsonObject? = null,
        answer: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        putJsonObject("rate_limits") {
            // A whole answer, marked as one: the backend was asked about now, which a rolling update - the
            // figures a process last heard in a turn's headers - is not (see CodexUsage.Snapshot.fromServer).
            // With it goes the one recovery signal Codex documents, said only when the answer had it: absent
            // is "unavailable", and must not read as "no".
            if (answer != null) {
                put(CodexUsage.FULL_ANSWER, true)
                (answer["ordinaryUsageAllowed"] as? JsonPrimitive)?.booleanOrNull?.let { put(CodexUsage.ORDINARY_USAGE, it) }
            }
            // The limits Codex keeps beside the plan's own two - `rateLimitsByLimitId`, a bucket per metered
            // limit, the plan's own being `codex`. Each one with a window of its own is a ring of its own,
            // retold as the list a model's own week came in on the Claude side (`model_scoped`), which the
            // rest of the plugin already reads and draws. Said only when the full answer was asked for:
            // absent means "not said this time", while an empty list means "none" and takes a ring away.
            if (buckets != null) {
                putJsonArray("model_scoped") {
                    for ((id, element) in buckets) {
                        if (id == MAIN_BUCKET) continue
                        val bucket = element as? JsonObject ?: continue
                        val window = longestWindow(bucket) ?: continue
                        addJsonObject {
                            put("display_name", bucketLabel(id, AppServer.text(bucket["limitName"])))
                            window(window)
                        }
                    }
                }
            }
            if (snapshot != null) {
                val windows = listOfNotNull(
                    snapshot["primary"] as? JsonObject,
                    snapshot["secondary"] as? JsonObject,
                )

                val short = windows.firstOrNull { minutesOf(it) in 1..SHORT_WINDOW_MAX_MINUTES }
                    ?: windows.firstOrNull { minutesOf(it) == null }
                val long = windows.firstOrNull { it !== short && (minutesOf(it) ?: Int.MAX_VALUE) > SHORT_WINDOW_MAX_MINUTES }
                    ?: windows.firstOrNull { it !== short }

                short?.let { putJsonObject("five_hour") { window(it) } }
                long?.let { putJsonObject("seven_day") { window(it) } }

                (snapshot["individualLimit"] as? JsonObject)?.let { limit ->
                    val remaining = AppServer.intOf(limit["remainingPercent"])
                    val used = AppServer.text(limit["used"]).toDoubleOrNull()
                    val cap = AppServer.text(limit["limit"]).toDoubleOrNull()
                    val percent = when {
                        remaining != null -> (100 - remaining).coerceIn(0, 100)
                        used != null && cap != null && cap > 0 -> (used / cap * 100).toInt().coerceIn(0, 100)
                        else -> null
                    }
                    putJsonObject("extra_usage") {
                        put("is_enabled", true)
                        percent?.let { put("utilization", it) }
                        AppServer.longOf(limit["resetsAt"])?.let { put("resets_at", iso(it)) }
                        // "None is unavailable, not a sparse-update recovery": only a said true or false goes.
                        (snapshot["spendControlReached"] as? JsonPrimitive)?.booleanOrNull?.let { put(CodexUsage.SPEND_REACHED, it) }
                    }
                }
            }
        }

        if (contextWindow != null && contextWindow > 0) {
            putJsonObject("session") {
                putJsonObject("model_usage") {
                    putJsonObject("codex") { put("contextWindow", contextWindow) }
                }
            }
        }
    }

    /**
     * The whole answer to `account/rateLimits/read`: the plan's own bucket and the others beside it, marked as
     * a whole answer (see [usage]).
     *
     * `rateLimitResetCredits` - how many one-off resets of the limit the account has in hand - is left where it
     * is. It counts what can still be redeemed, not what was: a count going down is a credit spent or a credit
     * expired, and nothing in it says the windows were reset. The reset itself is told by the shares falling and
     * by `ordinaryUsageAllowed`, which this answer carries.
     */
    fun usageAnswer(result: JsonObject?, contextWindow: Int? = null): JsonObject =
        usage(
            result?.get("rateLimits") as? JsonObject,
            contextWindow,
            result?.get("rateLimitsByLimitId") as? JsonObject,
            answer = result,
        )

    /**
     * Whether a rolling limits update is about the plan's own bucket. One about another bucket carries
     * THAT bucket's windows in the same fields, and read as the plan's it would draw them on the five-hour
     * and weekly rings - and its "limit reached" as the plan's.
     */
    fun isMainBucket(snapshot: JsonObject?): Boolean =
        AppServer.text(snapshot?.get("limitId")).let { it.isEmpty() || it == MAIN_BUCKET }

    /** What a bucket is called on a ring: the server's own name, or its id said like a word ("Premium"). */
    fun bucketLabel(id: String, name: String): String =
        name.trim().ifEmpty { id.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() } }.take(BUCKET_LABEL_LIMIT)

    private fun longestWindow(bucket: JsonObject): JsonObject? =
        listOfNotNull(bucket["primary"] as? JsonObject, bucket["secondary"] as? JsonObject)
            .maxByOrNull { minutesOf(it) ?: 0 }

    /** The plan's own limit among Codex's buckets. */
    private const val MAIN_BUCKET = "codex"

    private const val BUCKET_LABEL_LIMIT = 40

    /** Whether a limit is standing in the way right now - the reason Codex gives for refusing a turn. */
    fun reachedWindow(snapshot: JsonObject?): String? =
        AppServer.text(snapshot?.get("rateLimitReachedType")).takeIf { it.isNotEmpty() }

    /**
     * Which of the plan's windows stopped the work, in the panel's names - `five_hour` or `seven_day`, by the
     * window's length as [usage] tells them apart - or Codex's own reason when no window is full. A scenario run
     * files a refusal of a shared window against the account until it resets (see AccountUsage.SHARED_WINDOWS),
     * and the panel words a window it knows.
     */
    fun stoppedWindow(snapshot: JsonObject?): String? {
        val reached = reachedWindow(snapshot) ?: return null
        val full = listOfNotNull(snapshot?.get("primary") as? JsonObject, snapshot?.get("secondary") as? JsonObject)
            .filter { (AppServer.intOf(it["usedPercent"]) ?: 0) >= 100 }
            .maxByOrNull { minutesOf(it) ?: 0 } ?: return reached
        return if ((minutesOf(full) ?: 0) > SHORT_WINDOW_MAX_MINUTES) "seven_day" else "five_hour"
    }

    /**
     * When the limit that stopped the work opens again, in seconds - for the panel's countdown, and for everything
     * that decides by it: a tab carrying itself on after the reset only when that is hours away rather than days
     * (CarryOns.limitWait), and a scenario run filing the refusal against the account until then (see
     * AccountUsage.SHARED_WINDOWS).
     *
     * A full window of the plan when there is one. With none, the spending cap when Codex names it as the reason
     * (see [SPEND_CAP_REASONS]): a business seat has no window at all, and its cap starts over with its budget.
     * Without that the reset was unknown, and a run went back to the seat every quarter of an hour, refused each
     * time, until the end of the month.
     */
    fun resetOf(snapshot: JsonObject?): Long? {
        if (snapshot == null) return null
        val window = listOfNotNull(snapshot["primary"] as? JsonObject, snapshot["secondary"] as? JsonObject)
            .filter { (AppServer.intOf(it["usedPercent"]) ?: 0) >= 100 }
            .mapNotNull { AppServer.longOf(it["resetsAt"]) }
            .maxOrNull()
        if (window != null) return window

        if (reachedWindow(snapshot) !in SPEND_CAP_REASONS) return null
        return AppServer.longOf((snapshot["individualLimit"] as? JsonObject)?.get("resetsAt"))
    }

    /**
     * Codex's reasons for a spending cap stopping the work - the person's own cap in a workspace: "You hit your
     * spend cap set in your workspace" (an owner) or "set by the owner of your workspace" (a member). The cap is
     * `individualLimit`, and it starts over at its `resetsAt`.
     */
    val SPEND_CAP_REASONS = setOf("workspace_owner_usage_limit_reached", "workspace_member_usage_limit_reached")

    /**
     * Codex's reasons for the workspace running out of credits: "Ask your workspace owner to refill". It stops
     * every model of the account, as the cap does, but nothing ends it on a clock - it is over when somebody adds
     * credits - so it has no reset to give, and the individual cap's month would be a wrong one.
     */
    val CREDITS_REASONS = setOf("workspace_owner_credits_depleted", "workspace_member_credits_depleted")

    private fun kotlinx.serialization.json.JsonObjectBuilder.window(window: JsonObject) {
        put("utilization", AppServer.text(window["usedPercent"]).toDoubleOrNull() ?: 0.0)
        AppServer.longOf(window["resetsAt"])?.let { put("resets_at", iso(it)) }
    }

    private fun minutesOf(window: JsonObject): Int? = AppServer.intOf(window["windowDurationMins"])

    private fun iso(seconds: Long): String = Instant.ofEpochSecond(seconds).toString()

    /** Anything up to a day is the short ring; a week, a month is the long one. */
    private const val SHORT_WINDOW_MAX_MINUTES = 24 * 60

    /**
     * The MCP servers, as `{"mcpServers": [...]}` with the words the MCP screen draws - the same five the
     * Claude-era screen knew: connected, needs-auth, failed, pending, disabled.
     *
     * Three sources, because Codex splits the answer three ways. The configuration (`codex mcp list`)
     * knows every server a person set up, including the switched-off ones, and how each is started. The
     * live list knows which of them this conversation actually reached and whether they want a sign-in.
     * The start-up notifications know which failed and why - the only place the error text lives.
     */
    fun mcpStatus(
        live: JsonElement?,
        configured: JsonArray?,
        startup: Map<String, Startup>,
    ): JsonObject {
        val runtime = ((live as? JsonObject)?.get("data") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.associateBy { AppServer.text(it["name"]) }
            .orEmpty()
        val configs = configured
            ?.mapNotNull { it as? JsonObject }
            ?.associateBy { AppServer.text(it["name"]) }
            .orEmpty()

        val names = (configs.keys + runtime.keys + startup.keys).filter { it.isNotEmpty() }.distinct()

        return buildJsonObject {
            putJsonArray("mcpServers") {
                for (name in names) {
                    val config = configs[name]
                    val server = runtime[name]
                    val started = startup[name]
                    val status = statusOf(config, server, started)

                    addJsonObject {
                        put("name", name)
                        put("status", status)
                        put("scope", scopeOf(config, server))
                        put("config", configOf(config))
                        val error = started?.error.orEmpty().ifEmpty {
                            if (started?.reauthenticate == true) "The server wants you to sign in again." else ""
                        }
                        if (error.isNotEmpty()) put("error", error)
                    }
                }
            }
        }
    }

    /** What a start-up notification said about one server - see [mcpStatus]. */
    data class Startup(val state: String, val error: String?, val reauthenticate: Boolean)

    private fun statusOf(config: JsonObject?, server: JsonObject?, started: Startup?): String {
        if (config != null && config["enabled"] == JsonPrimitive(false)) return "disabled"

        val auth = AppServer.text(server?.get("authStatus")).ifEmpty { AppServer.text(config?.get("auth_status")) }
        val runtime = AppServer.text(server?.get("runtimeStatus"))

        return when {
            runtime == "authenticationRequired" || started?.reauthenticate == true -> "needs-auth"
            runtime == "connected" -> "connected"
            runtime == "failed" || runtime == "cancelled" -> "failed"
            runtime == "disabled" -> "disabled"
            started?.state == "ready" -> if (auth == "notLoggedIn") "needs-auth" else "connected"
            started?.state == "failed" || started?.state == "cancelled" -> "failed"
            started?.state == "starting" || runtime == "starting" -> "pending"
            auth == "notLoggedIn" -> "needs-auth"
            server != null -> "connected"
            else -> "pending"
        }
    }

    private fun scopeOf(config: JsonObject?, server: JsonObject?): String = when {
        AppServer.text(server?.get("pluginId")).isNotEmpty() -> "plugin"
        config == null -> "builtin"
        else -> "user"
    }

    private fun configOf(config: JsonObject?): JsonObject {
        val transport = config?.get("transport") as? JsonObject ?: return buildJsonObject {}
        return buildJsonObject {
            put("type", AppServer.text(transport["type"]).ifEmpty { "stdio" })
            AppServer.text(transport["url"]).takeIf { it.isNotEmpty() }?.let { put("url", it) }
            AppServer.text(transport["command"]).takeIf { it.isNotEmpty() }?.let { put("command", it) }
            (transport["args"] as? JsonArray)?.let { args ->
                put("args", buildJsonArray { args.forEach { add(it) } })
            }
        }
    }

    /**
     * The context meter's figures out of Codex's token counts: what the window holds, and its size.
     *
     * [windowNow] is the window the conversation has now, when Codex's count was made under another one: a count
     * read from the thread's file or kept from the process before this one (Std switched to Long, a model with a
     * bigger window). What the window holds stays Codex's - the conversation is the same - but measured against
     * the window it was counted in, a switch to Long was undone by the very next figure (see
     * CodexSession.contextNow). Null keeps Codex's own window.
     */
    fun contextOf(tokenUsage: JsonObject?, windowNow: Int? = null): Pair<Int, Int>? {
        val last = tokenUsage?.get("last") as? JsonObject ?: return null
        val counted = AppServer.intOf(tokenUsage["modelContextWindow"]) ?: return null
        if (counted <= 0) return null
        val window = windowNow?.takeIf { it > 0 } ?: counted

        val total = AppServer.longOf(last["totalTokens"]) ?: return null
        val reasoning = AppServer.longOf(last["reasoningOutputTokens"]) ?: 0
        // What stays in the window after this step: everything that went in and came out, less the
        // model's own reasoning, which the next request does not carry.
        val used = (total - reasoning).coerceAtLeast(0).toInt()
        return used to window
    }
}
