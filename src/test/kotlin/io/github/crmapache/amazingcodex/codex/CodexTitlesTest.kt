package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A model's answer to "name this conversation", made into a tab's title (see CodexTitles.clean). */
class CodexTitlesTest {

    @Test
    fun `the title is one line without the dressing a model puts around it`() {
        assertEquals("Fix the login button", CodexTitles.clean("Fix the login button"))
        assertEquals("Fix the login button", CodexTitles.clean("\"Fix the login button.\""))
        assertEquals("Починить кнопку входа", CodexTitles.clean("«Починить кнопку входа»"))
        assertEquals("Search index", CodexTitles.clean("# **Search index**"))
        assertEquals("Search index", CodexTitles.clean("`Search index`"))
        assertEquals("Search index", CodexTitles.clean("\n\n  Search index  \nbecause the index was slow"))
    }

    @Test
    fun `nothing, or a paragraph, is not a title`() {
        assertNull(CodexTitles.clean(""))
        assertNull(CodexTitles.clean("   \n  "))
        assertNull(CodexTitles.clean("\"\""))
        assertNull(CodexTitles.clean("word ".repeat(20)))
    }

    @Test
    fun `a title may be exactly as long as a tab can hold`() {
        assertEquals("x".repeat(80), CodexTitles.clean("x".repeat(80)))
        assertNull(CodexTitles.clean("x".repeat(81)))
    }
}
