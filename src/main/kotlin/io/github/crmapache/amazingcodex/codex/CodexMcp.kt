package io.github.crmapache.amazingcodex.codex

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Codex's MCP configuration: listing, adding and removing servers through `codex mcp`.
 *
 * The live state of a server - connected, failed, wanting a sign-in - is the conversation's process's to
 * tell (see CodexSession.requestMcpStatus); what servers exist and how each is started lives in the
 * config, and `codex mcp` is the one writer of it that Codex itself honours.
 */
internal object CodexMcp {

    /**
     * Adding a server. A command is split into words the way a shell would split what a person typed into
     * the form - `npx -y @scope/server` is three words - and goes after `--`; an address goes as `--url`.
     */
    fun add(
        workingDirectory: String?,
        name: String,
        commandOrUrl: String,
        transport: String?,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val isUrl = transport == "http" || transport == "sse" || commandOrUrl.startsWith("http://") || commandOrUrl.startsWith("https://")
        val args = buildList {
            add("mcp")
            add("add")
            add(name)
            if (isUrl) {
                add("--url")
                add(commandOrUrl.trim())
            } else {
                add("--")
                addAll(words(commandOrUrl))
            }
        }

        CodexCli.run(workingDirectory, args, onError = onError) { output ->
            forget()
            onResult(output.trim().ifEmpty { "Added $name." })
        }
    }

    fun remove(workingDirectory: String?, name: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        CodexCli.run(workingDirectory, listOf("mcp", "remove", name), onError = onError) { output ->
            forget()
            onResult(output.trim().ifEmpty { "Removed $name." })
        }
    }

    /**
     * The configured servers, as `codex mcp list --json` prints them. Blocking - called off the reader
     * thread - and remembered for a few seconds, because the MCP screen asks again after every action.
     */
    fun listConfigured(workingDirectory: String?, accountId: String = ""): JsonArray? {
        val key = "${workingDirectory.orEmpty()}|$accountId"
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second }

        val output = CodexCli.capture(workingDirectory, listOf("mcp", "list", "--json"), accountId = accountId) ?: return null
        val list = runCatching { Json.parseToJsonElement(output.substring(output.indexOf('[').coerceAtLeast(0))) as? JsonArray }
            .getOrNull() ?: return null

        cache[key] = System.currentTimeMillis() to list
        return list
    }

    private fun forget() = cache.clear()

    private val cache = ConcurrentHashMap<String, Pair<Long, JsonArray>>()

    private const val CACHE_MS = 5_000L

    /** A command line split into words by the shell's quoting rules - enough for what a form is given. */
    internal fun words(command: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in command.trim()) {
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> {
                    quote = c
                    any = true
                }
                quote == null && c.isWhitespace() -> {
                    if (current.isNotEmpty() || any) words += current.toString()
                    current.setLength(0)
                    any = false
                }
                else -> {
                    current.append(c)
                    any = true
                }
            }
        }
        if (current.isNotEmpty() || any) words += current.toString()
        return words
    }
}
