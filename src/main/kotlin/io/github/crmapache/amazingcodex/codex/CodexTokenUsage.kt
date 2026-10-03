package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How many tokens went through Codex today, on this machine - the aquamarine figure beside the rings.
 *
 * Read off the conversations Codex writes (`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`): every model
 * request leaves a `token_count` event with the usage of that one request (`last_token_usage`). Counted
 * for every conversation, not only the panel's - the figure answers "how much today", and a turn run in
 * Codex's own terminal was spent today all the same.
 *
 * Only the day folders that can hold today's events are read: yesterday's (a conversation started before
 * midnight keeps writing into the file it began in) and today's.
 */
internal object CodexTokenUsage {

    fun today(workingDirectory: String?): String {
        val root = CodexHome.of(workingDirectory).sessionsDirectory
        if (!root.isDirectory) return "0.0M"

        val today = LocalDate.now()
        var total = 0L

        runCatching {
            for (day in listOf(today.minusDays(1), today)) {
                val folder = File(root, String.format(Locale.ROOT, "%04d/%02d/%02d", day.year, day.monthValue, day.dayOfMonth))
                folder.listFiles { file -> file.isFile && file.extension == "jsonl" }?.forEach { file ->
                    total += scanFile(file, today)
                }
            }
        }.onFailure { thisLogger().warn("Failed to scan Codex sessions for today's token usage", it) }

        return String.format(Locale.ROOT, "%.1fM", total / 1_000_000.0)
    }

    internal fun scanFile(file: File, today: LocalDate): Long {
        var subtotal = 0L
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    if (!line.contains(TOKEN_COUNT)) continue
                    subtotal += lineTokens(line, today)
                }
            }
        }.onFailure { thisLogger().warn("Failed to scan $file for token usage", it) }
        return subtotal
    }

    /** One `token_count` event's tokens, when it happened today. */
    internal fun lineTokens(line: String, today: LocalDate): Long {
        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return 0
        val timestamp = event["timestamp"]?.jsonPrimitive?.contentOrNull ?: return 0
        val date = runCatching { Instant.parse(timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: return 0
        if (date != today) return 0

        val info = (event["payload"] as? JsonObject)?.get("info") as? JsonObject ?: return 0
        val last = info["last_token_usage"] as? JsonObject ?: return 0
        return last["total_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
    }

    private const val TOKEN_COUNT = "\"token_count\""
}
