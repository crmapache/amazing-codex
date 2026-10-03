package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Whether Codex is signed in, and as whom - asked of Codex itself (`account/read`) in the environment the
 * conversations will run in.
 *
 * Blocking, and never on the UI thread: it raises a process for a few seconds. The answer names the sign-in
 * method (ChatGPT or an API key), the address and the plan, which is everything the login screen and the
 * accounts screen show about it.
 */
internal object CodexAuth {

    data class Status(
        val installed: Boolean,
        val loggedIn: Boolean,
        val email: String = "",
        val plan: String = "",
        val orgId: String = "",
        val orgName: String = "",
        /** `chatgpt` or `apiKey` - how Codex is signed in. */
        val method: String = "",
        /**
         * Where this environment's Codex keeps its conversations, symlinks resolved. The same folder for
         * every account is what proves the accounts share one history (see accounts/IsolationProof).
         */
        val projectsDirectory: String = "",
    ) {
        /** Who this is, as one string - what tells one sign-in from another. */
        val identity: String
            get() = if (!loggedIn) "" else listOf(email, orgId, method).filter { it.isNotEmpty() }.joinToString("|")
    }

    fun switchedAccount(known: String, next: Status): Boolean =
        known.isNotEmpty() && next.identity.isNotEmpty() && known != next.identity

    fun status(
        environment: Map<String, String> = CodexExecutable.environment(),
        workingDirectory: String? = null,
    ): Status {
        val executable = CodexExecutable.find() ?: return Status(installed = false, loggedIn = false)

        val answer = CompletableFuture<Status>()
        lateinit var server: AppServer
        server = AppServer(
            label = "auth",
            executable = executable,
            workingDirectory = workingDirectory ?: System.getProperty("user.home"),
            environment = environment,
            onNotification = { _, _ -> },
            onServerRequest = { id, method, _ -> server.respondError(id, AppServer.METHOD_NOT_FOUND, "Not handled: $method") },
            onDiagnostic = {},
            onExit = { _, _ -> answer.complete(Status(installed = true, loggedIn = false)) },
        )

        if (!server.start(CodexLaunch.CLIENT_NAME, CodexLaunch.CLIENT_TITLE, ProjectCatalog.pluginVersion ?: "dev")) {
            return Status(installed = true, loggedIn = false)
        }

        server.request(
            "account/read",
            buildJsonObject { put("refreshToken", false) },
            onResult = { result ->
                val home = AppServer.text(server.serverInfo["codexHome"])
                answer.complete(parse(result as? JsonObject, home))
            },
            onError = { error ->
                thisLogger().info("Codex would not say whether it is signed in: ${error.message}")
                answer.complete(Status(installed = true, loggedIn = false))
            },
        )

        return try {
            answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            Status(installed = true, loggedIn = false)
        } finally {
            server.stop()
        }
    }

    internal fun parse(result: JsonObject?, codexHome: String): Status {
        val account = result?.get("account") as? JsonObject
        val sessions = codexHome.takeIf { it.isNotEmpty() }?.let { home ->
            runCatching { File(home, "sessions").canonicalPath }.getOrDefault("$home/sessions")
        }.orEmpty()

        if (account == null) return Status(installed = true, loggedIn = false, projectsDirectory = sessions)

        val method = AppServer.text(account["type"])
        return Status(
            installed = true,
            loggedIn = true,
            email = AppServer.text(account["email"]),
            plan = AppServer.text(account["planType"]).ifEmpty { if (method == "apiKey") "API key" else "" },
            method = method,
            projectsDirectory = sessions,
        )
    }

    private const val TIMEOUT_SECONDS = 20L
}
