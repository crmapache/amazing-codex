package io.github.crmapache.amazingcodex.codex

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal typealias AppRequest = (String, JsonObject, (JsonElement) -> Unit, (String) -> Unit) -> Unit

/** Plugin membership comes from Codex, including materialized workspace app templates. */
internal data class PluginAppGroup(val pluginId: String, val apps: List<PluginApp>, val error: String? = null)

internal data class PluginApp(
    val id: String,
    val name: String,
    val installUrl: String? = null,
    val accessible: Boolean? = null,
    val enabled: Boolean? = null,
    val callable: Boolean? = null,
    val reason: String? = null,
    val needsAuth: Boolean = false,
)

/** Control RPCs only. Callable and accessible are deliberately never translated into "signed in". */
internal object CodexApps {
    private const val DIRECTORY_PAGE_SIZE = 1000
    fun read(
        plugins: List<InstalledPlugin>,
        cwd: String?,
        request: AppRequest,
        onResult: (List<PluginAppGroup>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (plugins.isEmpty()) return onResult(emptyList())
        val groups = mutableListOf<PluginAppGroup>()

        fun runtime(metadata: Map<String, JsonObject>, directory: Map<String, JsonObject>, error: String?) {
            request("app/installed", buildJsonObject { put("forceRefresh", true) }, { result ->
                val apps = objects(result, "apps") ?: return@request onFailure("Codex returned an invalid app runtime snapshot.")
                val byId = apps.associateBy { text(it["id"]) }
                onResult(groups.map { group ->
                    group.copy(apps = group.apps.map { app ->
                        val info = metadata[app.id]
                        val listed = directory[app.id]
                        val live = byId[app.id]
                        app.copy(
                            name = text(info?.get("name")).ifEmpty { app.name },
                            installUrl = connectionUrl(info?.get("installUrl"))
                                ?: connectionUrl(listed?.get("installUrl")) ?: app.installUrl,
                            accessible = boolean(listed?.get("isAccessible")),
                            enabled = boolean(live?.get("enabled")),
                            callable = boolean(live?.get("callable")) ?: if (live == null) false else null,
                            reason = app.reason ?: error,
                        )
                    })
                })
            }, onFailure)
        }

        fun directory(metadata: Map<String, JsonObject>, metadataError: String?) {
            val listed = mutableMapOf<String, JsonObject>()
            val cursors = mutableSetOf<String>()
            val wanted = groups.flatMap { it.apps }.map { it.id }.toSet()
            fun page(cursor: String?) {
                request("app/list", buildJsonObject {
                    put("limit", DIRECTORY_PAGE_SIZE)
                    put("forceRefetch", cursor == null)
                    cursor?.let { put("cursor", it) }
                }, { result ->
                    val apps = objects(result, "data") ?: return@request onFailure("Codex returned an invalid app directory.")
                    apps.forEach { app -> text(app["id"]).takeIf { it in wanted }?.let { listed[it] = app } }
                    val next = text((result as? JsonObject)?.get("nextCursor")).ifEmpty { null }
                    when {
                        next == null || listed.keys.containsAll(wanted) -> runtime(metadata, listed, metadataError)
                        !cursors.add(next) -> onFailure("Codex repeated an app directory cursor.")
                        else -> page(next)
                    }
                }, { runtime(metadata, emptyMap(), it) })
            }
            page(null)
        }

        fun metadata() {
            val ids = groups.flatMap { it.apps }.map { it.id }.distinct().chunked(100)
            val read = mutableMapOf<String, JsonObject>()
            var error: String? = null
            fun chunk(index: Int) {
                if (index == ids.size) return directory(read, error)
                request("app/read", buildJsonObject {
                    put("appIds", JsonArray(ids[index].map(::JsonPrimitive)))
                    put("includeTools", false)
                }, { result ->
                    val apps = objects(result, "apps")
                    if (apps == null) error = "Codex returned invalid app metadata."
                    else apps.forEach { read[text(it["id"])] = it }
                    chunk(index + 1)
                }, { error = it; chunk(index + 1) })
            }
            chunk(0)
        }

        fun details(markets: List<JsonObject>) {
            fun next(index: Int) {
                if (index == plugins.size) return metadata()
                val plugin = plugins[index]
                val marketplace = plugin.id.substringAfterLast('@', "")
                val market = markets.firstOrNull { text(it["name"]) == marketplace }
                request("plugin/read", buildJsonObject {
                    put("pluginName", plugin.id.substringBeforeLast('@', plugin.id))
                    val path = text(market?.get("path"))
                    if (path.isNotEmpty()) put("marketplacePath", path)
                    else if (marketplace.isNotEmpty()) put("remoteMarketplaceName", marketplace)
                }, { result ->
                    groups += group(plugin.id, result)
                    next(index + 1)
                }, { groups += PluginAppGroup(plugin.id, emptyList(), it); next(index + 1) })
            }
            next(0)
        }

        request("plugin/list", buildJsonObject {
            cwd?.let { put("cwds", JsonArray(listOf(JsonPrimitive(it)))) }
            put("marketplaceKinds", JsonArray(listOf(JsonPrimitive("local"))))
        }, { details(objects(it, "marketplaces").orEmpty()) }, { details(emptyList()) })
    }

    internal fun group(pluginId: String, result: JsonElement): PluginAppGroup {
        val root = result as? JsonObject
        val detail = root?.get("plugin") as? JsonObject ?: root
        val declared = objects(detail, "apps")
            ?: return PluginAppGroup(pluginId, emptyList(), "Codex returned invalid plugin app declarations.")
        val summary = detail?.get("summary") as? JsonObject
        val blocked = text(summary?.get("disabledReason")).ifEmpty {
            if (text(summary?.get("availability")) == "DISABLED_BY_ADMIN") "disabled_by_admin" else ""
        }.ifEmpty { null }
        val apps = declared.mapNotNull { app ->
            val id = text(app["id"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PluginApp(id, text(app["name"]).ifEmpty { id }, connectionUrl(app["installUrl"]), reason = blocked)
        }.toMutableList()
        objects(detail, "appTemplates").orEmpty().forEach { template ->
            val materialized = template["materializedAppIds"] as? JsonArray
            if (materialized.isNullOrEmpty()) {
                apps += PluginApp(
                    text(template["templateId"]), text(template["name"]),
                    reason = text(template["reason"]).ifEmpty { blocked ?: "unresolved-template" },
                )
            } else materialized.forEach { id -> apps += PluginApp(text(id), text(template["name"]), reason = blocked) }
        }
        return PluginAppGroup(pluginId, apps.distinctBy { it.id })
    }

    internal fun connectionUrl(value: JsonElement?): String? = text(value).takeIf { address ->
        runCatching { URI(address).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    }

    /** Only a tool's actual authentication failure establishes a need to sign in. */
    internal fun authenticationFailure(item: JsonObject): String? {
        if (text(item["type"]) != "mcpToolCall") return null
        val context = item["appContext"] as? JsonObject ?: return null
        val error = item["error"] as? JsonObject
        val result = item["result"] as? JsonObject
        val structured = result?.get("structuredContent") as? JsonObject
        val needsLogin = text(structured?.get("error_code")) == "USER_NOT_LOGGED_IN" ||
            text(error?.get("message")).contains("USER_NOT_LOGGED_IN")
        if (!needsLogin) return null
        return text(context["connectorId"]).takeIf { it.isNotBlank() }
    }

    private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.content?.takeUnless { it == "null" }.orEmpty()
    private fun boolean(value: JsonElement?): Boolean? = (value as? JsonPrimitive)?.booleanOrNull
    private fun objects(value: JsonElement?, key: String): List<JsonObject>? =
        (((value as? JsonObject)?.get(key)) as? JsonArray)?.takeIf { values -> values.all { it is JsonObject } }?.map { it as JsonObject }
}
