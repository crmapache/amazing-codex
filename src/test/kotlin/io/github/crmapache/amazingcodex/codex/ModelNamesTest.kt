package io.github.crmapache.amazingcodex.codex

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The same examples the panel's own rule is held to (modelKey in catalog.ts). Codex names its models by
 * exact ids, so the rule is short: case and surrounding whitespace do not make two models different, and
 * nothing else is seen through.
 */
class ModelNamesTest {

    @Test
    fun `case and whitespace are not another model`() {
        assertTrue(ModelNames.same("gpt-5.6-sol", " GPT-5.6-Sol "))
        assertEquals("gpt-5.6-sol", ModelNames.key("  GPT-5.6-SOL\t"))
    }

    @Test
    fun `different models stay apart`() {
        assertFalse(ModelNames.same("gpt-5.6-sol", "gpt-5.6-codex"))
        assertFalse(ModelNames.same("gpt-5.6-sol", "gpt-5.5-sol"))
        // No alias is seen through: a family is not the model.
        assertFalse(ModelNames.same("sol", "gpt-5.6-sol"))
    }

    @Test
    fun `a catalogue holds exactly the models it names`() {
        val catalogue = setOf("gpt-5.6-sol", "gpt-5.1-codex-max")

        assertTrue(ModelNames.holds(catalogue, "gpt-5.6-sol"))
        assertTrue(ModelNames.holds(catalogue, "GPT-5.1-Codex-Max"))
        assertFalse(ModelNames.holds(catalogue, "gpt-5.6-codex"))
        assertFalse(ModelNames.holds(catalogue, "sol"))
    }

    @Test
    fun `an empty catalogue holds nothing`() {
        assertFalse(ModelNames.holds(emptySet(), "gpt-5.6-sol"))
    }

    // Codex has no window marks, so taking one off changes nothing.
    @Test
    fun `a model has no mark to come off`() {
        assertEquals("gpt-5.6-sol", ModelNames.unmarked("gpt-5.6-sol"))
        assertEquals("opus[1m]", ModelNames.unmarked("opus[1m]"))
    }

    @Test
    fun `a family is what follows the version`() {
        assertEquals("sol", ModelNames.familyOf("gpt-5.6-sol"))
        assertEquals("codex-max", ModelNames.familyOf("gpt-5.1-codex-max"))
        assertEquals("sol", ModelNames.familyOf("GPT-5.6-Sol"))
    }

    @Test
    fun `a name of an unknown shape is its own family`() {
        // No version, or nothing after it.
        assertEquals("o3", ModelNames.familyOf("o3"))
        assertEquals("gpt-5", ModelNames.familyOf("gpt-5"))
        assertEquals("my-local-model", ModelNames.familyOf("My-Local-Model"))
    }
}
