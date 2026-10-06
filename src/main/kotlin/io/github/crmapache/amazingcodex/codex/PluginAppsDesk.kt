package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Account-scoped refresh and browser lifecycle. Cancelling never disconnects an external account. */
internal class PluginAppsDesk(
    private val accountOf: (String) -> String,
    private val currentAccount: () -> String,
    private val read: (String, (List<PluginAppGroup>) -> Unit, (String) -> Unit) -> Unit,
    private val browse: (String, () -> Boolean, () -> Unit, (String) -> Unit) -> Unit,
    private val emit: (JsonObject) -> Unit,
) {
    private data class Entry(
        val account: String,
        val selectedAccount: String,
        val revision: Long,
        val groups: List<PluginAppGroup> = emptyList(),
        val phase: String = "loading",
        val pendingApp: String? = null,
        val error: String? = null,
    )

    private val lock = Any()
    private var revision = 0L
    private val entries = mutableMapOf<String, Entry>()
    private val authFailures = mutableMapOf<Pair<String, String>, MutableSet<String>>()

    fun refresh(sessionId: String) = load(sessionId, null, null)

    fun connect(sessionId: String, pluginId: String, appId: String) = load(sessionId, pluginId, appId)

    private fun load(sessionId: String, pluginId: String?, appId: String?) {
        val ticket = synchronized(lock) {
            Entry(accountOf(sessionId), currentAccount(), ++revision,
                phase = if (appId == null) "loading" else "opening", pendingApp = appId)
                .also { entries[sessionId] = it; send(sessionId, it) }
        }
        if (appId != null && ticket.account != ticket.selectedAccount) {
            fail(sessionId, ticket, "This conversation is still switching Codex accounts. Try again after the switch.")
            return
        }
        read(sessionId, { groups ->
            synchronized(lock) {
                if (!valid(sessionId, ticket)) return@read
                val failed = authFailures[sessionId to ticket.account].orEmpty()
                val updated = ticket.copy(groups = groups.map { group ->
                    group.copy(apps = group.apps.map { it.copy(needsAuth = it.id in failed) })
                }, phase = "ready", pendingApp = null)
                entries[sessionId] = updated
                if (appId == null) {
                    send(sessionId, updated)
                    return@read
                }
                val app = updated.groups.firstOrNull { it.pluginId == pluginId }?.apps?.firstOrNull { it.id == appId }
                val url = app?.installUrl
                if (url == null) {
                    fail(sessionId, ticket, "Codex did not provide a connection address for this app.")
                    return@read
                }
                entries[sessionId] = updated.copy(phase = "opening", pendingApp = appId)
                send(sessionId, entries.getValue(sessionId))
                browse(url, { synchronized(lock) { valid(sessionId, ticket) } }, {
                    synchronized(lock) {
                        if (valid(sessionId, ticket)) {
                            entries[sessionId] = updated.copy(phase = "waiting", pendingApp = appId)
                            send(sessionId, entries.getValue(sessionId))
                        }
                    }
                }, { fail(sessionId, ticket, it) })
            }
        }, { fail(sessionId, ticket, it) })
    }

    private fun valid(sessionId: String, ticket: Entry): Boolean =
        entries[sessionId]?.revision == ticket.revision && accountOf(sessionId) == ticket.account && currentAccount() == ticket.selectedAccount

    private fun fail(sessionId: String, ticket: Entry, message: String) = synchronized(lock) {
        if (valid(sessionId, ticket)) {
            entries[sessionId] = entries.getValue(sessionId).copy(phase = "error", pendingApp = null, error = message)
            send(sessionId, entries.getValue(sessionId))
        }
    }

    fun cancel(sessionId: String) = synchronized(lock) {
        val entry = entries[sessionId] ?: return@synchronized
        entries[sessionId] = entry.copy(revision = ++revision, phase = "cancelled", pendingApp = null, error = null)
        send(sessionId, entries.getValue(sessionId))
    }

    fun returnedToIde() {
        val waiting = synchronized(lock) { entries.filterValues { it.phase == "waiting" }.keys.toList() }
        waiting.forEach(::refresh)
    }

    fun needsAuth(sessionId: String, appId: String) = synchronized(lock) {
        val account = accountOf(sessionId)
        authFailures.getOrPut(sessionId to account) { mutableSetOf() }.add(appId)
        val entry = entries[sessionId]?.takeIf { it.account == account } ?: return@synchronized
        entries[sessionId] = entry.copy(groups = entry.groups.map { group ->
            group.copy(apps = group.apps.map { if (it.id == appId) it.copy(needsAuth = true) else it })
        })
        send(sessionId, entries.getValue(sessionId))
    }

    fun reset() = synchronized(lock) {
        val sessions = entries.keys.toList()
        entries.clear()
        authFailures.clear()
        ++revision
        sessions.forEach { send(it, Entry(accountOf(it), currentAccount(), revision, phase = "stale")) }
    }

    fun accountChanged() {
        val sessions = synchronized(lock) { entries.keys.toList() }
        reset()
        sessions.forEach(::refresh)
    }

    private fun send(sessionId: String, entry: Entry) {
        emit(buildJsonObject {
            put("type", "pluginApps")
            put("sessionId", sessionId)
            put("accountId", entry.account)
            put("phase", entry.phase)
            entry.pendingApp?.let { put("pendingAppId", it) }
            entry.error?.let { put("error", it) }
            putJsonArray("plugins") {
                entry.groups.forEach { group -> addJsonObject {
                    put("pluginId", group.pluginId)
                    group.error?.let { put("error", it) }
                    putJsonArray("apps") {
                        group.apps.forEach { app -> addJsonObject {
                            put("id", app.id)
                            put("name", app.name)
                            put("canConnect", app.installUrl != null)
                            put("needsAuth", app.needsAuth)
                            app.accessible?.let { put("accessible", it) }
                            app.enabled?.let { put("enabled", it) }
                            app.callable?.let { put("callable", it) }
                            app.reason?.let { put("reason", it) }
                        } }
                    }
                } }
            }
        })
    }
}
