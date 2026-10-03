package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The choice of model and mode outlives an IDE restart, which is why it lives in the IDE's settings.
 * This test guards exactly that: storage gone silently looks like "the panel has forgotten my model
 * again".
 */
class CodexPreferencesTest : BasePlatformTestCase() {

    fun testSnapshotKeepsWhatWasWritten() {
        CodexPreferences.model = "haiku"
        CodexPreferences.effort = "low"
        CodexPreferences.mode = "acceptEdits"
        CodexPreferences.composerLayout = "right"

        val snapshot = CodexPreferences.snapshot()

        assertEquals("haiku", snapshot.model)
        assertEquals("low", snapshot.effort)
        assertEquals("acceptEdits", snapshot.mode)
        assertEquals("right", snapshot.composerLayout)
    }

    /**
     * The models somebody names by hand. What is stored is what will one day be a launch argument, so
     * the filter is the point of the setting rather than a nicety: an argument holding a line feed or a
     * quotation mark is cut short by a shell nobody asked for, taking the rest of the command line with
     * it (see CodexLaunch).
     */
    fun testCustomModelsSurviveARoundTrip() {
        CodexPreferences.customModels = listOf("glm-4.6", "claude-fable-5-1[1m]")

        assertEquals(listOf("glm-4.6", "claude-fable-5-1[1m]"), CodexPreferences.customModels)
    }

    fun testCustomModelsDropWhatCannotBeALaunchArgument() {
        CodexPreferences.customModels = listOf(
            "  glm-4.6  ",
            "two words",
            "with\"quote",
            "with\nfeed",
            "with,comma",
            "",
            "glm-4.6",
        )

        // Trimmed, deduplicated, and everything a shell could cut short left out - a comma among them,
        // because a comma is what separates the entries in this setting.
        assertEquals(listOf("glm-4.6"), CodexPreferences.customModels)
    }

    /** What an older version of this list left behind is filtered on the way out as well as in. */
    fun testUnusableNamesAreFilteredOnReadingToo() {
        assertEquals(listOf("glm-4.6"), CodexPreferences.usableModelNames(listOf("glm-4.6", "two words")))
    }

    fun testEmptyValueMeansDefault() {
        CodexPreferences.model = "opus"
        CodexPreferences.model = ""

        // An empty string means "as Claude Code has it by default": then the flag is not passed at
        // process launch at all.
        assertEquals("", CodexPreferences.model)
    }

    override fun tearDown() {
        CodexPreferences.model = ""
        CodexPreferences.effort = ""
        CodexPreferences.mode = ""
        CodexPreferences.composerLayout = ""
        CodexPreferences.customModels = emptyList()
        super.tearDown()
    }
}
