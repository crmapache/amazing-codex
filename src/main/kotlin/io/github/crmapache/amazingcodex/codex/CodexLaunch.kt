package io.github.crmapache.amazingcodex.codex

/**
 * What a conversation's process comes up with, and what it is told about the place it runs in.
 *
 * Kept apart from the session because some of these decisions are made once for a conversation's whole
 * life, and a test reads them more reliably than an eye over the logs.
 *
 * Very little of it travels on the command line any more. The Claude side of this plugin had to squeeze a
 * briefing through `cmd.exe` on Windows and learned the hard way that a line break there cuts the command
 * in half. Codex's app-server takes everything - the briefing, the model, the permissions - as JSON over
 * stdin, where nothing is cut: the command line is `codex app-server` and the few overrides below, and
 * those are held to one line without quotes all the same (see CodexLaunchTest), because the same batch
 * wrapper still stands between us and the executable when npm installed it.
 */
internal object CodexLaunch {

    /** What the app-server is told this client is: shows up in its logs and in the threads' `originator`. */
    const val CLIENT_NAME = "amazing_codex"
    const val CLIENT_TITLE = "Amazing Codex GUI"

    /**
     * The tool name the panel's question card answers to - Codex's `request_user_input` is shown under it.
     *
     * The feed, the phone, the scenario engine and the statistics all know a question with options by
     * this name, so a Codex question is given it at the border (see CodexSession) rather than taught to
     * each of them separately.
     */
    const val ASK_TOOL = "AskUserQuestion"

    /**
     * Arguments after `app-server`. Empty on purpose: everything a conversation is launched with goes as
     * thread parameters, where Codex reads it the same way whatever its config says - an override here
     * would be a second place deciding the same thing.
     */
    fun serverArguments(): List<String> = emptyList()

    /**
     * The environment a conversation's process runs in, over the one its account handed us.
     *
     * Only ever adds: what came in decides which account pays for the turn (see AccountStore), and a
     * variable of theirs dropped here would be a conversation billed to somebody else with nothing looking
     * wrong.
     */
    fun environment(account: Map<String, String>): Map<String, String> = account

    /**
     * What the agent is told about the place it has been launched into, as developer instructions.
     *
     * An app-server client is, to Codex, just another editor, and the agent assumes the conventions of
     * whichever one it guesses at - including sending a person to a terminal for things the panel does
     * with a button. So it is told plainly: a person is watching, the panel has its own MCP screen with a
     * sign-in button, and questions with options are answered on screen.
     */
    val PANEL_BRIEFING = """
        This conversation runs inside the Amazing Codex GUI panel of a JetBrains IDE. A person is sitting
        in front of it, watching the answer as it is written, and can press a button on the spot.

        The panel manages MCP servers itself. Its header menu has an MCP screen that lists every server
        with its status and its error, offers a sign-in button for the servers that need authentication,
        and can reconnect, add and remove them. The button opens the browser from the IDE, the sign-in
        finishes there, and the list updates itself afterwards. So when asked whether an MCP server can be
        signed in to right now, the answer is yes: open the panel's menu, go to the MCP screen, find the
        server and press its sign-in button. Never send the person to a terminal for it.

        Paths you mention are clickable in the panel and open the file in the editor, at the line when you
        give one as path:line.
    """.trimIndent()

    /**
     * What is said on top of it to a tab continuing a scenario run's main thread.
     *
     * That conversation told the agent, message after message, that it was the main thread of a run - that
     * it hands cards out rather than doing the work, that it never writes to disk, that it answers in JSON
     * (see scenario/HeadTalk). All of that is still in the thread the tab resumes, so the role has to be
     * lifted for "carry on from here" to mean anything.
     */
    val AFTER_SCENARIO_HEAD = """
        The conversation above is a scenario run that has finished, and in it you were the main thread:
        you handed cards out to other sessions, judged what came back, and answered in JSON because
        every message asked you to.

        That run is over, and your part in it is over with it. There are no more cards to hand out and
        nothing here is answered in JSON any more. From now on you are an ordinary assistant in this
        project, talking to the person who opened this tab and reading everything above as your own
        memory of what was done.

        Use the tools as you would anywhere else. The rule that the main thread never writes to disk
        belonged to the run, not to you: if the person asks you to change a file, change it.
    """.trimIndent()

    /**
     * What a tab of the panel tells the agent about where it is running - one door rather than two, so a
     * caller never decides for itself whether to add the second half.
     */
    fun panelBriefing(afterScenarioHead: Boolean = false): String =
        if (afterScenarioHead) "$PANEL_BRIEFING\n\n$AFTER_SCENARIO_HEAD" else PANEL_BRIEFING

    /**
     * What Codex is asked to start a plan-mode turn's approved plan with - the words Codex's own terminal
     * sends when a person accepts a plan.
     */
    const val IMPLEMENT_PLAN = "Implement the plan."

    /**
     * What `/init` sends: Codex's terminal has the command, the app-server does not, so the panel says the
     * same thing in words.
     */
    val INIT_PROMPT = """
        Generate a file named AGENTS.md that serves as a contributor guide for this repository. Your goal
        is to produce a clear, concise, and well-structured document with descriptive headings and
        actionable explanations for each section. Look at the repository first: its layout, how it is
        built and tested, its coding style and conventions, and how commits and pull requests are written
        here. Keep it short and specific to this project - a newcomer should be able to read it in a few
        minutes and start contributing.
    """.trimIndent()
}
