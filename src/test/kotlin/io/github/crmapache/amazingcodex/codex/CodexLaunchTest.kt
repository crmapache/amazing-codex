package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a conversation's process comes up with. Almost nothing travels on the command line any more -
 * `codex app-server` takes the briefing, the model and the permissions as JSON over stdin - so what is
 * held here is that it stays that way, and what the agent is told about where it runs.
 */
class CodexLaunchTest {

    /**
     * npm on Windows installs Codex as a batch file, the platform runs it through cmd.exe, and the batch
     * hands on what it got with %*. A line feed or a quotation mark in an argument cuts the command short
     * there without a word, so nothing that could hold one goes on the command line at all.
     */
    @Test
    fun `nothing a conversation is launched with travels on the command line`() {
        assertEquals(emptyList(), CodexLaunch.serverArguments())
    }

    // The map that comes in is the account's: it says whose credential the process opens and therefore
    // whose subscription pays for the turn. A variable of theirs dropped here would be a conversation
    // that runs, answers and is billed to somebody else, with nothing looking wrong.
    @Test
    fun `nothing the account asked for is dropped on the way`() {
        val account = mapOf(
            "CODEX_HOME" to "/store/one",
            "CODEX_SQLITE_HOME" to "/Users/someone/.codex",
            "OPENAI_API_KEY" to "",
            "PATH" to "/usr/bin",
        )

        val environment = CodexLaunch.environment(account)

        for ((name, value) in account) assertEquals(value, environment[name], "the account's $name")
        assertEquals(account.keys, environment.keys)
    }

    // An app-server client is, to Codex, just another editor, and the agent would send a person to a
    // terminal for an MCP sign-in the panel does with a button. So it is told where it actually is.
    @Test
    fun `the agent is told it runs in the panel`() {
        val briefing = CodexLaunch.panelBriefing()

        assertSame(CodexLaunch.PANEL_BRIEFING, briefing)
        assertTrue("MCP" in briefing)
        assertTrue("JetBrains IDE" in briefing)
        assertTrue("Never send the person to a terminal" in briefing)
    }

    /**
     * A tab continuing a finished run's main thread is told the role is over. The transcript is pages of
     * "you are the main thread, you hand cards out, you never write to disk, answer in JSON", and an agent
     * resumed onto it goes on obeying that - while the button that opened the tab promised the opposite.
     */
    @Test
    fun `a tab continuing a run's main thread is told that part is over`() {
        val briefing = CodexLaunch.panelBriefing(afterScenarioHead = true)

        assertTrue(briefing.startsWith(CodexLaunch.PANEL_BRIEFING), "the ordinary briefing is still there")
        assertTrue("main thread" in briefing)
        assertTrue("run is over" in briefing)
        assertTrue("change it" in briefing)
    }

    // And nothing else hears a word of it: every other tab would be told about a role it never had.
    @Test
    fun `an ordinary tab hears nothing about scenario runs`() {
        assertFalse("main thread" in CodexLaunch.panelBriefing())
        assertFalse("scenario" in CodexLaunch.panelBriefing())
    }

    @Test
    fun `the client names itself the way the app-server expects`() {
        assertTrue(CodexLaunch.CLIENT_NAME.matches(Regex("[a-z_]+")), "an identifier, not a title")
        assertEquals("Amazing Codex GUI", CodexLaunch.CLIENT_TITLE)
    }

    // The feed, the phone, the scenario engine and the statistics all know a question with options by
    // this name, so Codex's request_user_input is given it at the border.
    @Test
    fun `a question with options goes under the name every reader knows`() {
        assertEquals("AskUserQuestion", CodexLaunch.ASK_TOOL)
    }

    // The app-server has no /init of its own, so the panel says it in words - and the words are about the
    // file Codex reads, not the one Claude read.
    @Test
    fun `init asks for Codex's own contributor guide`() {
        assertTrue("AGENTS.md" in CodexLaunch.INIT_PROMPT)
        assertFalse("CLAUDE.md" in CodexLaunch.INIT_PROMPT)
        assertEquals("Implement the plan.", CodexLaunch.IMPLEMENT_PLAN)
    }
}
