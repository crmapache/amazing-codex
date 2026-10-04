package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.crmapache.amazingcodex.codex.accounts.AccountsState
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts

/**
 * A conversation's settings answer two different questions, and they are allowed to disagree: what THIS
 * conversation works at, and what a new tab starts at. Answering both with one control is expensive in a
 * way nobody notices at the time - the panel simply starts every next tab, in every project, in whatever
 * was chosen once for one tab.
 *
 * The permission mode answers only the first question, the effort answers both at once, and neither of
 * them ever reaches a conversation that is already open.
 *
 * No process comes up here: a conversation with no process applies both straight away, because both
 * travel as flags at launch.
 */
class CodexSessionsTest : BasePlatformTestCase() {

    private fun sessions(onBorn: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> }) = CodexSessions(
        workingDirectory = null,
        parentDisposable = testRootDisposable,
        onEvent = { _, _ -> },
        onError = { _, _ -> },
        onFinished = {},
        onBorn = onBorn,
    )

    // The MODE selector, Shift+Tab and an approved plan all come down this way, and none of them is an
    // answer to "how do I want to work from now on": spending one tab in bypass says nothing about the
    // next one. What new tabs start in is written only from the header's menu (see CodexPanel).
    fun testAConversationModeIsNotASavedDefault() {
        CodexPreferences.mode = PermissionModes.ASK

        val sessions = sessions()
        var applied = false
        sessions.setPermissionMode("main", PermissionModes.BYPASS) { applied = it.applied }

        assertTrue(applied)
        // The conversation itself did switch - that is what was asked for.
        assertEquals(PermissionModes.BYPASS, sessions.permissionMode("main"))
        // And nothing else did.
        assertEquals(PermissionModes.ASK, CodexPreferences.mode)
    }

    // Two tabs, two modes, and neither one is the other's business.
    fun testConversationsKeepTheirOwnModes() {
        val sessions = sessions()
        sessions.setPermissionMode("main", PermissionModes.BYPASS) {}
        sessions.setPermissionMode("branch-1", PermissionModes.PLAN) {}

        assertEquals(PermissionModes.BYPASS, sessions.permissionMode("main"))
        assertEquals(PermissionModes.PLAN, sessions.permissionMode("branch-1"))
    }

    // A tab nobody has touched has no mode of its own to report: whoever asks falls back to the setting.
    fun testAConversationThatDoesNotExistReportsNoModeOfItsOwn() {
        assertNull(sessions().permissionMode("never-opened"))
    }

    // The effort is the other half: it does become what new tabs start at - that is what was asked for -
    // and it still applies to the one conversation it was chosen in.
    fun testAnEffortIsAppliedHereAndRememberedForTheNextTab() {
        val sessions = sessions()
        sessions.setEffort("main", "low")

        assertEquals("low", sessions.effort("main"))
        assertEquals("low", CodexPreferences.effort)
    }

    // Two tabs, two efforts - and the choice in one reaches neither the other's conversation nor its
    // chip: that is the whole complaint this was built for. The Claude-era "ultracode" is read as
    // Codex's "ultra".
    fun testConversationsKeepTheirOwnEfforts() {
        val sessions = sessions()
        sessions.setEffort("main", "low")
        sessions.setEffort("branch-1", "ultracode")

        assertEquals("low", sessions.effort("main"))
        assertEquals("ultra", sessions.effort("branch-1"))
    }

    /**
     * And the whole point of it: a conversation is born at whatever was chosen by then, and a choice made
     * afterwards in another tab does not reach back to it.
     *
     * Its birth is announced, because nothing else could tell: the CLI says nothing about the effort ever
     * (see CodexSession.setEffort), so a panel left to guess would draw every untouched tab by the
     * current setting - that is, by a choice made somewhere else entirely.
     */
    /**
     * The model is announced at birth beside the effort, and for the same reason. A conversation opened
     * from the history then adopts the model its transcript names, before its process is up - what it
     * carries on at is its own model, not the setting's (see CodexSessionHub.resumeConversation).
     */
    fun testAResumedConversationAdoptsItsOwnModelBeforeItWakes() {
        CodexPreferences.model = "gpt-5.1-codex-mini"
        val born = mutableMapOf<String, String>()
        val sessions = sessions { sessionId, _, model, _, _ -> born[sessionId] = model }

        sessions.resume("old", "conversation-1")
        assertEquals("gpt-5.1-codex-mini", born["old"])
        assertEquals("gpt-5.1-codex-mini", sessions.model("old"))

        sessions.adoptModel("old", "gpt-5.5")
        assertEquals("gpt-5.5", sessions.model("old"))
        // Adopting is no choice: the next tab still starts on the setting.
        assertEquals("gpt-5.1-codex-mini", CodexPreferences.model)

        sessions.adoptModel("old", "")
        assertEquals("gpt-5.5", sessions.model("old"))
    }

    fun testEachConversationIsBornAtWhatWasChosenByThen() {
        val born = mutableMapOf<String, String>()
        val sessions = sessions { sessionId, effort, _, _, _ -> born[sessionId] = effort }

        CodexPreferences.effort = "high"
        sessions.setPermissionMode("first", PermissionModes.PLAN) {}

        sessions.setEffort("second", "low")

        assertEquals("high", born["first"])
        assertEquals("low", born["second"])
        // And the first one stayed where it was born, though the setting has moved on since.
        assertEquals("high", sessions.effort("first"))
    }

    /**
     * A fork carries on the same conversation, so it carries on it the same way: on the parent's model,
     * at the parent's effort and in the parent's permission mode.
     *
     * The saved setting is not the answer here, and the difference is not theoretical: every selector
     * writes the machine's default as well as changing its own tab, so a model chosen in a neighbouring
     * tab decided what a fork made from this one started on.
     */
    fun testAForkStartsOnWhatItsParentRunsOn() {
        CodexPreferences.model = "gpt-5.6-sol"
        CodexPreferences.effort = "high"
        CodexPreferences.mode = PermissionModes.ASK

        val sessions = sessions()
        sessions.setModel("parent", "gpt-5.6-codex") {}
        sessions.setEffort("parent", "low")
        sessions.setPermissionMode("parent", PermissionModes.PLAN) {}

        // The settings move on afterwards - in another tab, as they do.
        CodexPreferences.model = "gpt-5.1-codex-mini"
        CodexPreferences.effort = "ultra"

        sessions.branchFrom("parent", "branch-1")

        assertEquals("gpt-5.6-codex", sessions.model("branch-1"))
        assertEquals("low", sessions.effort("branch-1"))
        assertEquals(PermissionModes.PLAN, sessions.permissionMode("branch-1"))
    }

    /**
     * A parent nobody has touched hands down nothing: an empty field means "as the settings have it",
     * which is exactly what such a tab is itself running on.
     */
    fun testAForkOfAnUntouchedTabFollowsTheSettings() {
        CodexPreferences.model = "gpt-5.6-sol"
        CodexPreferences.effort = "high"

        val sessions = sessions()
        sessions.branchFrom("parent", "branch-1")

        assertEquals("gpt-5.6-sol", sessions.model("branch-1"))
        assertEquals("high", sessions.effort("branch-1"))
    }

    /**
     * A fork asked for with a model of its own is still a fork: the model comes from the request, and
     * everything the request said nothing about still comes from the parent.
     *
     * Nobody sends such a request today - the panel forks without a choice, and a phone cannot fork at all
     * - so this is the first thing that would break the moment one did, and it would break in silence.
     */
    fun testAForkKeepsItsParentWhereTheRequestSaidNothing() {
        CodexPreferences.model = "gpt-5.6-sol"
        CodexPreferences.effort = "high"

        val sessions = sessions()
        sessions.setModel("parent", "gpt-5.6-codex") {}
        sessions.setEffort("parent", "low")
        sessions.setPermissionMode("parent", PermissionModes.PLAN) {}

        sessions.rememberLaunch("branch-1", SessionLaunch(model = "gpt-5.1-codex-mini"))
        sessions.branchFrom("parent", "branch-1")

        assertEquals("gpt-5.1-codex-mini", sessions.model("branch-1"))
        assertEquals("low", sessions.effort("branch-1"))
        assertEquals(PermissionModes.PLAN, sessions.permissionMode("branch-1"))
    }

    /**
     * Choosing an account is not only about the next conversation: the ones already open move onto it.
     *
     * A conversation cannot be told to change account - the CLI reads its credentials once, at start - so
     * this is a new process over the same transcript, and everything the tab was set to has to be carried
     * across by hand. Missed, the moved tab falls back to the machine's defaults and the model changes
     * out from under the person, which is not what pressing Select says.
     */
    fun testChoosingAnAccountMovesTheTabsAlreadyOpen() {
        CodexPreferences.model = "gpt-5.1-codex-mini"
        CodexPreferences.effort = "high"

        val sessions = sessions()
        sessions.setModel("main", "gpt-5.6-codex") {}
        sessions.setEffort("main", "low")
        sessions.setPermissionMode("main", PermissionModes.PLAN) {}

        register("work")
        sessions.switchAllTo()

        assertEquals("work", sessions.accountOf("main"))
        assertEquals("gpt-5.6-codex", sessions.model("main"))
        assertEquals("low", sessions.effort("main"))
        assertEquals(PermissionModes.PLAN, sessions.permissionMode("main"))
    }

    /**
     * A tab whose process is replaced while it says nothing has to announce it all the same.
     *
     * A turn would be interrupted and every client told; a silent swap used to say nothing at all - and
     * a workflow's fleet, a background subagent and a background command all outlive the TURN that
     * started them and none of them outlives the process. Their cards were left ticking against a CLI
     * that no longer existed. A tab with no process is the other half of the same rule: nothing to lose,
     * so nothing to say.
     */
    fun testATabWithNoProcessAnnouncesNothingWhenTheAccountChanges() {
        var dropped = 0
        val sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, _ -> },
            onError = { _, _ -> },
            onFinished = {},
            onProcessDropping = { dropped += 1 },
        )
        sessions.setEffort("main", "low")

        register("work")
        sessions.switchAllTo()

        assertEquals(0, dropped)
    }

    /** A tab with no process has nothing to move, and no process is raised to move it. */
    fun testATabThatWasNeverOpenedIsLeftAlone() {
        val sessions = sessions()

        register("work")
        sessions.switchAllTo()

        // No conversation was made for it: a tab nobody has written into reads the register itself
        // whenever it does start, which by then says exactly this.
        assertNull(sessions.model("main"))
    }

    /**
     * The sweep reads a conversation on one thread and takes its process on another, so what it read is
     * asked again at the moment of taking (see CodexSessions.sleep). A conversation with no process is
     * settled before any of that: there is nothing to take, and asking would put a look at the state of
     * every long-closed tab on the sweep's path for nothing.
     */
    fun testATabWithNoProcessIsSettledWithoutAskingAnything() {
        var asked = false

        assertFalse(sessions().sleep("never-opened") { asked = true; true })
        assertFalse(asked)
    }

    /**
     * A restart throws the process away exactly as a swap does - an added MCP server is read at launch
     * and reaches a conversation no other way - so it owes the same word: the fleet, the background
     * command and the pinned question all die with that process, and nothing else would say so.
     *
     * A tab with no process owes nothing and must stay silent: a restart there raises nothing and drops
     * nothing, and a row in the feed about it would appear every time somebody adds a server while an
     * untouched tab sits next door. What it still owes is the answer to whoever asked - the MCP screen
     * questions the process next, and without that word it would go on showing the list from before.
     */
    fun testARestartWithNoProcessAnnouncesNothingAndStillAnswers() {
        var dropped = 0
        var answered = 0
        val sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, _ -> },
            onError = { _, _ -> },
            onFinished = {},
            onProcessDropping = { dropped += 1 },
        )

        assertFalse(sessions.restart("never-opened") { answered += 1 })
        assertEquals(0, dropped)
        assertEquals(1, answered)
    }

    /** Nothing was waiting, so the end of a turn has nothing to apply - and must not stumble over it. */
    fun testTheEndOfATurnAppliesNoRestartNobodyAskedFor() {
        sessions().applyPendingRestart("main")
    }

    /**
     * The rule that keeps a waiting restart from taking down the wrong process. A turn does not always
     * end by saying so - a crash, a Stop, an account chosen - and whatever came up after the process the
     * note was written about already read the config at launch. Fired blindly, the note would cost a
     * fleet or a dev server raised long afterwards.
     */
    fun testAWaitingRestartIsOwedOnlyByTheProcessItWaitedFor() {
        assertTrue(CodexSessions.stillOwed(running = true, startedAt = 1_000, waitedFor = 1_000))
        // Something else came up in its place: it read the config when it started.
        assertFalse(CodexSessions.stillOwed(running = true, startedAt = 2_000, waitedFor = 1_000))
        // Nothing is standing there at all.
        assertFalse(CodexSessions.stillOwed(running = false, startedAt = 1_000, waitedFor = 1_000))
    }

    /** The account register is machine-wide and outlives a test, so what a test adds it takes away. */
    private fun register(id: String) {
        AccountsState.getInstance().remember(AccountsState.Account().apply { this.id = id })
        CodexAccounts.getInstance().currentId = id
    }

    override fun tearDown() {
        CodexPreferences.mode = ""
        CodexPreferences.effort = ""
        CodexPreferences.model = ""
        runCatching {
            AccountsState.getInstance().accounts().forEach { AccountsState.getInstance().forget(it.id) }
            CodexAccounts.getInstance().currentId = ""
        }
        super.tearDown()
    }
}
