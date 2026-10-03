package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Codex's own settings as the screen `/config` opens shows them - read out of Codex's answers rather than
 * out of its files (see CodexConfigDesk for the asking).
 *
 * `config/read` answers with the settings in force for a directory, where each one comes from (`origins`
 * - the user's file, the project's `.codex/config.toml`, a policy of the organisation), and the layers
 * themselves, the project's with the reason it is not loaded when it is not. That is everything a screen
 * of switches needs and nothing it has to work out by reading TOML by hand, which is how a panel comes to
 * show a value the project overrides as though it were in force.
 *
 * Pure, so the rules - what is shown, what is locked and by whom, what a value is written as - are held
 * by tests.
 */
internal object CodexConfig {

    enum class Group(val wire: String) { WORK("work"), TERMINAL("terminal"), OTHER("other") }

    enum class Kind { TEXT, BOOL, NUMBER }

    /**
     * One setting the screen offers. [options] empty with [free] means a value typed by hand; [requirement]
     * names the list in `configRequirements/read` an organisation narrows its options with.
     */
    data class Spec(
        val key: String,
        val group: Group,
        val options: List<String> = emptyList(),
        val free: Boolean = false,
        val kind: Kind = Kind.TEXT,
        val requirement: String? = null,
    )

    /** A setting as the screen draws it. */
    data class Setting(
        val key: String,
        val options: List<String>,
        val free: Boolean,
        val value: String?,
        val group: Group,
        /** Who decides it instead of the person: the project's own config, or a policy. Null when nobody. */
        val lockedBy: String?,
    )

    /**
     * The settings on the screen, in its order: what shapes the work first, then what only a terminal and
     * a tab left on "Default" go by - the panel's own chips decide those per tab.
     *
     * Left out on purpose: the developer instructions (the panel sends a briefing of its own), the MCP
     * servers (they have a screen of their own), the projects and their trust (this screen's own block),
     * model providers and the forced sign-in (an account's business, see the accounts screen).
     */
    val SPECS: List<Spec> = listOf(
        Spec("model_reasoning_summary", Group.WORK, listOf("auto", "concise", "detailed", "none")),
        Spec("model_verbosity", Group.WORK, listOf("low", "medium", "high")),
        Spec("personality", Group.WORK, listOf("none", "friendly", "pragmatic")),
        Spec("web_search", Group.WORK, listOf("disabled", "cached", "indexed", "live"), requirement = "allowedWebSearchModes"),
        Spec("sandbox_workspace_write.network_access", Group.WORK, listOf("true", "false"), kind = Kind.BOOL),
        Spec("approvals_reviewer", Group.WORK, listOf("user", "auto_review"), requirement = "allowedApprovalsReviewers"),
        Spec("service_tier", Group.WORK, free = true),
        Spec("model_auto_compact_token_limit", Group.WORK, free = true, kind = Kind.NUMBER),
        Spec("review_model", Group.WORK, free = true),
        Spec("model", Group.TERMINAL, free = true),
        Spec("model_reasoning_effort", Group.TERMINAL, listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")),
        Spec("approval_policy", Group.TERMINAL, listOf("untrusted", "on-request", "never"), requirement = "allowedApprovalPolicies"),
        Spec("sandbox_mode", Group.TERMINAL, listOf("read-only", "workspace-write", "danger-full-access"), requirement = "allowedSandboxModes"),
    )

    /** The experimental features a person may switch, as `features.<name>` rows - see [settings]. */
    private const val FEATURES = "features."

    /** Stages of an experimental feature a person is offered; the rest are Codex's own business. */
    private val OFFERED_STAGES = setOf("beta", "experimental")

    /**
     * The rows for the screen out of the three answers: `config/read` for [config], `configRequirements/read`
     * for [requirements] (null when the organisation sets none), `experimentalFeature/list` for [features].
     */
    fun settings(config: JsonObject?, requirements: JsonObject?, features: JsonObject?): List<Setting> {
        val values = config?.get("config") as? JsonObject
        val origins = config?.get("origins") as? JsonObject

        val fixed = SPECS.map { spec ->
            val allowed = spec.requirement?.let { name -> (requirements?.get(name) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
            Setting(
                key = spec.key,
                options = allowed?.let { list -> spec.options.filter { it in list } } ?: spec.options,
                free = spec.free,
                value = valueOf(values, spec.key),
                group = spec.group,
                lockedBy = lockedBy(origins, spec.key),
            )
        }

        val offered = (features?.get("data") as? JsonArray).orEmpty().mapNotNull { element ->
            val feature = element as? JsonObject ?: return@mapNotNull null
            val name = AppServer.text(feature["name"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (AppServer.text(feature["stage"]) !in OFFERED_STAGES) return@mapNotNull null
            val key = FEATURES + name
            Setting(
                key = key,
                options = listOf("true", "false"),
                free = false,
                value = ((feature["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false).toString(),
                group = Group.OTHER,
                lockedBy = lockedBy(origins, key),
            )
        }

        return fixed + offered
    }

    /** Whether [key] is one this screen may write - nothing else is ever written from a message. */
    fun writable(key: String, features: Set<String>): Boolean =
        SPECS.any { it.key == key } || (key.startsWith(FEATURES) && key.removePrefix(FEATURES) in features)

    /**
     * What [value] is written as for [key]: a switch as a boolean, a number as a number, an empty value as
     * nothing at all - the setting goes back to Codex's own default rather than to an empty string. Null
     * when the value does not fit the setting: an option it does not offer, a number that is not one.
     */
    fun wireValue(key: String, value: String, offered: List<String>): JsonElement? {
        val spec = SPECS.firstOrNull { it.key == key }
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return JsonNull

        return when {
            key.startsWith(FEATURES) || spec?.kind == Kind.BOOL -> trimmed.toBooleanStrictOrNull()?.let(::JsonPrimitive)
            spec?.kind == Kind.NUMBER -> trimmed.toLongOrNull()?.takeIf { it > 0 }?.let(::JsonPrimitive)
            spec != null && !spec.free -> trimmed.takeIf { it in offered }?.let(::JsonPrimitive)
            else -> JsonPrimitive(trimmed.take(FREE_LIMIT))
        }
    }

    /** The longest value typed by hand - a model's name, a tier, not a document. */
    private const val FREE_LIMIT = 200

    /** The value in force for a dotted key, as the screen writes it; null when nothing sets it. */
    fun valueOf(values: JsonObject?, key: String): String? {
        var at: JsonElement? = values
        for (part in key.split('.')) at = (at as? JsonObject)?.get(part)
        return when (val found = at) {
            null, JsonNull -> null
            is JsonPrimitive -> found.booleanOrNull?.toString() ?: found.longOrNull?.toString() ?: found.contentOrNull
            else -> null
        }
    }

    /**
     * Who decides [key] instead of the person - "project" for the project's own config, "policy" for
     * anything an organisation lays down - or null when it is the person's own file or Codex's default.
     */
    fun lockedBy(origins: JsonObject?, key: String): String? {
        val origin = origins?.get(key) as? JsonObject ?: return null
        val type = AppServer.text((origin["name"] as? JsonObject)?.get("type"))
        return when {
            type == "project" -> "project"
            type in POLICY_LAYERS -> "policy"
            else -> null
        }
    }

    private val POLICY_LAYERS = setOf("system", "mdm", "enterpriseManaged", "legacyManagedConfigTomlFromFile", "legacyManagedConfigTomlFromMdm")

    /** What the project's own Codex config is, as far as this screen and the trust row tell it. */
    data class ProjectLayer(
        /** The project has a `.codex/config.toml` of its own (or hooks, or exec policies) at all. */
        val present: Boolean,
        /** Codex loads it - the project is trusted. */
        val trusted: Boolean,
        /** The settings it sets, by name - never their values. */
        val sets: List<String>,
        /** The sign-in it demands (`forced_login_method`: chatgpt, api), when it demands one. */
        val forcedLogin: String? = null,
        /** The ChatGPT workspace it demands (`forced_chatgpt_workspace_id`) - compared, never shown. */
        val forcedWorkspace: String? = null,
    )

    /** The project layer out of `config/read` with its layers. */
    fun projectLayer(config: JsonObject?): ProjectLayer {
        val layer = (config?.get("layers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { AppServer.text((it["name"] as? JsonObject)?.get("type")) == "project" }
            ?: return ProjectLayer(present = false, trusted = false, sets = emptyList())

        val disabled = AppServer.text(layer["disabledReason"]).isNotEmpty()
        val values = layer["config"] as? JsonObject
        return ProjectLayer(
            present = true,
            trusted = !disabled,
            sets = namesIn(values),
            forcedLogin = valueOf(values, "forced_login_method"),
            forcedWorkspace = valueOf(values, "forced_chatgpt_workspace_id"),
        )
    }

    /**
     * What a trusted project's own settings demand that the credential a conversation runs on does not
     * have, by setting name - empty when nothing stands in the way. [method] and [workspace] are the
     * credential's (see AccountIdentity.Who), empty when it would not say.
     *
     * Codex obeys the project: a checked-in `forced_login_method = "chatgpt"` makes an API key in the drawer
     * count as no sign-in at all in that directory (measured on 0.152) - the account looks signed out here
     * and signed in everywhere else, and nothing says why.
     */
    fun demands(layer: ProjectLayer, method: String, workspace: String): List<String> {
        if (!layer.present || !layer.trusted) return emptyList()
        return listOfNotNull(
            "forced_login_method".takeIf { layer.forcedLogin != null && method.isNotEmpty() && layer.forcedLogin != method },
            "forced_chatgpt_workspace_id".takeIf {
                layer.forcedWorkspace != null && workspace.isNotEmpty() && !workspace.startsWith("key-") && layer.forcedWorkspace != workspace
            },
        )
    }

    /** The dotted names a layer sets, leaves only, sorted - what the screen lists under the project. */
    private fun namesIn(config: JsonObject?, prefix: String = ""): List<String> =
        config.orEmpty().flatMap { (name, value) ->
            val key = prefix + name
            if (value is JsonObject && value.isNotEmpty()) namesIn(value, "$key.") else listOf(key)
        }.sorted().take(NAMES_LIMIT)

    private const val NAMES_LIMIT = 40

    /** The version of the person's own config file, for a write that must not undo somebody else's. */
    fun userVersion(config: JsonObject?): String? =
        (config?.get("layers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { AppServer.text((it["name"] as? JsonObject)?.get("type")) == "user" }
            ?.let { AppServer.text(it["version"]).ifEmpty { null } }
}
