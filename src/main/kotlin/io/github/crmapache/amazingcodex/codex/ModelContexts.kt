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
    fun window(workingDirectory: String?, model: String, mode: String): Int? =
        limitsOf(read(workingDirectory), resolve(workingDirectory, model))?.let { if (normalize(mode) == LONG) it.long else it.standard }

    /**
     * The window Codex reports for [model] in [mode] (`modelContextWindow` of `thread/tokenUsage/updated`): the raw
     * one less the cache's own margin, 258,400 of 272,000. What the context meter divides by when Codex's last count
     * was made under another window - another process, another mode (see CodexShapes.contextOf). Null where the
     * cache does not know the model: then no window was handed to Codex either, and its own count stays true.
     */
    fun effectiveWindow(workingDirectory: String?, model: String, mode: String): Int? =
        limitsOf(read(workingDirectory), resolve(workingDirectory, model))
            ?.let { if (normalize(mode) == LONG) it.longEffective else it.standardEffective }

    /** "default" as the config names it - the caller knows better when it can (see CodexSession.nextModel). */
    private fun resolve(workingDirectory: String?, model: String): String =
        model.takeUnless { it.isBlank() || it == ModelNames.DEFAULT }
            ?: CodexSettings.effective(workingDirectory, "model")

    /**
     * The limits of [model] in [contexts]. A model the cache does not name has none: another model's window handed
     * to it would be a guess sent to Codex as a setting. Only "default" nobody could resolve falls back to the
     * cache's first model, as before - Long has to mean something on a tab that never named its model.
     */
    internal fun limitsOf(contexts: Map<String, Limits>, model: String): Limits? =
        if (model.isBlank()) contexts.values.firstOrNull() else contexts[model]

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
