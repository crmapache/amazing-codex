package io.github.crmapache.amazingcodex.codex

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The choice of model and mode outlives an IDE restart, which is why it lives in the IDE's settings.
 * This test guards exactly that: storage gone silently looks like "the panel has forgotten my model
 * again".
 */
class CodexPreferencesTest : BasePlatformTestCase() {

    fun testContextPinIsSavedSeparatelyFromTheLastChoiceAndCanBeCleared() {
        try {
            CodexPreferences.contextMode = "standard"
            CodexPreferences.newTabContextMode = " LONG "

            assertEquals("long", CodexPreferences.newTabContextMode)
            assertEquals("long", CodexPreferences.snapshot().newTabContextMode)
            assertEquals("standard", CodexPreferences.contextMode)

            CodexPreferences.newTabContextMode = " "

            assertEquals("", CodexPreferences.newTabContextMode)
            assertEquals("", CodexPreferences.snapshot().newTabContextMode)
        } finally {
            CodexPreferences.newTabContextMode = ""
            CodexPreferences.contextMode = "standard"
        }
    }

    /**
     * The address the old feedback form remembered. The form is gone, so a person's address has no business
     * staying in the IDE's settings - and PRIVACY.md says it is deleted.
     */
    fun testTheOldFeedbackAddressIsWiped() {
        PropertiesComponent.getInstance().setValue("acx.feedback.email", "someone@example.com")

        CodexPreferences.forgetRetired()

        assertNull(PropertiesComponent.getInstance().getValue("acx.feedback.email"))
    }

    /**
     * The panel's own font is a name the page puts into CSS and nothing more: one line, without what a control
     * character would do to a stylesheet, and empty for "as the console's".
     */
    fun testTheFontIsAPlainName() {
        CodexPreferences.fontFamily = "  Menlo\n"
        assertEquals("Menlo", CodexPreferences.fontFamily)

        CodexPreferences.fontFamily = "Fira\u0000 Code"
        assertEquals("Fira Code", CodexPreferences.fontFamily)

        CodexPreferences.fontFamily = ""
        assertEquals("", CodexPreferences.fontFamily)
    }

    fun testSnapshotKeepsWhatWasWritten() {
        CodexPreferences.mode = "acceptEdits"
        CodexPreferences.composerLayout = "right"

        val snapshot = CodexPreferences.snapshot()

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

    /**
     * The indicators switched off around the field. What is stored is what is OFF - an empty setting means
     * "all shown" - and the value comes in by a message, so anything that is not a plain word is dropped
     * on the way in and on the way out.
     */
    fun testHiddenIndicatorsSurviveARoundTrip() {
        CodexPreferences.hiddenIndicators = setOf("tokens", "thanks")

        assertEquals(setOf("thanks", "tokens"), CodexPreferences.hiddenIndicators)
        assertEquals(setOf("thanks", "tokens"), CodexPreferences.snapshot().hiddenIndicators)
    }

    fun testHiddenIndicatorsKeepOnlyPlainWords() {
        CodexPreferences.hiddenIndicators = setOf(" week ", "with,comma", "two words", "", "a1")

        assertEquals(setOf("week"), CodexPreferences.hiddenIndicators)
    }

    fun testNothingHiddenIsTheDefault() {
        CodexPreferences.hiddenIndicators = setOf("week")
        CodexPreferences.hiddenIndicators = emptySet()

        assertEquals(emptySet<String>(), CodexPreferences.hiddenIndicators)
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
        CodexPreferences.hiddenIndicators = emptySet()
        super.tearDown()
    }
}
