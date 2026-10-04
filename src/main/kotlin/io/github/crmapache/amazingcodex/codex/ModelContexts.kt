package io.github.crmapache.amazingcodex.codex

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The context windows Codex advertises for the models installed on this machine. */
internal object ModelContexts {

    const val STANDARD = "standard"
    const val LONG = "long"

    data class Limits(
        val standard: Int,
        val long: Int,
        val standardEffective: Int,
        val longEffective: Int,
    )

    fun normalize(mode: String): String = if (mode.trim().lowercase() == LONG) LONG else STANDARD

    /** Raw window value handed to Codex. Null means the cache does not know this model. */
    fun window(workingDirectory: String?, model: String, mode: String): Int? {
        val contexts = read(workingDirectory)
        val resolved = model.takeUnless { it.isBlank() || it == "default" }
            ?: CodexSettings.effective(workingDirectory, "model").takeIf { it.isNotBlank() }
        val limits = resolved?.let(contexts::get) ?: contexts.values.firstOrNull() ?: return null
        return if (normalize(mode) == LONG) limits.long else limits.standard
    }

    fun read(workingDirectory: String?): Map<String, Limits> {
        val file = File(CodexHome.of(workingDirectory).configDirectory, "models_cache.json")
        return runCatching { if (file.isFile) parse(file.readText()) else emptyMap() }.getOrDefault(emptyMap())
    }

    internal fun parse(text: String): Map<String, Limits> {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val models = root["models"] as? JsonArray ?: return emptyMap()

        return models.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val id = model["slug"]?.jsonPrimitive?.content.orEmpty()
            val standard = model["context_window"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val long = model["max_context_window"]?.jsonPrimitive?.intOrNull ?: standard
            val percent = model["effective_context_window_percent"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, 100) ?: 100
            if (id.isEmpty() || standard <= 0 || long < standard) return@mapNotNull null

            id to Limits(
                standard = standard,
                long = long,
                standardEffective = standard * percent / 100,
                longEffective = long * percent / 100,
            )
        }.toMap()
    }
}
