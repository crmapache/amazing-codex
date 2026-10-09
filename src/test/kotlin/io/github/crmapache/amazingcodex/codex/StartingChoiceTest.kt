package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.crmapache.amazingcodex.codex.accounts.AccountsState
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts

/**
 * What a new tab starts on, and the one promise that matters about it: the answer SHOWN is the answer the
 * launch uses.
 *
 * The two used to be worked out apart - the launch read the paying account's memory, the chip over an
 * empty tab, the phone and the scenarios did not - and nothing caught it, because every half was right on
 * its own. Caught live instead: a sandbox chip saying Sonnet over a tab that came up on Opus. So every case
 * here asks both, and a conversation born in a fresh tab is the launch's side of the comparison.
 */
class StartingChoiceTest : BasePlatformTestCase() {

    private val accounts: CodexAccounts get() = CodexAccounts.getInstance()
    private var bornContextMode = "unborn"

    private fun account(id: String, model: String = "", effort: String = "") = AccountsState.Account().apply {
        this.id = id
        storeDir = "/tmp/acc/$id"
        email = "$id@example.com"
        this.model = model
        this.effort = effort
    }

    private fun working(id: String, model: String = "", effort: String = "") {
        AccountsState.getInstance().remember(account(id, model, effort))
        accounts.currentId = id
    }

    /** The model and the effort a conversation is born with in a tab nobody chose anything for. */
    private fun born(launch: SessionLaunch = SessionLaunch()): Pair<String, String> {
        var model = "unborn"
        var effort = "unborn"
        val sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, _ -> },
            onError = { _, _ -> },
            onFinished = {},
            onBorn = { _, bornEffort, bornModel, bornContext, _ ->
                effort = bornEffort
                model = bornModel
                bornContextMode = bornContext
            },
        )
        sessions.rememberLaunch("main", launch)
        // Brings the conversation into being without raising a process: a tab with no process only
        // records an effort (see CodexSession.setEffort). Not remembered, so nothing it does is an input.
        sessions.setEffort("main", "max", remember = false)
        return model to effort
    }

    private fun reset() {
        AccountsState.getInstance().accounts().forEach { AccountsState.getInstance().forget(it.id) }
        accounts.currentId = ""
        CodexPreferences.model = ""
        CodexPreferences.effort = ""
        CodexPreferences.newTabModel = ""
        CodexPreferences.newTabEffort = ""
        CodexPreferences.contextMode = ModelContexts.STANDARD
        CodexPreferences.newTabContextMode = ""
        CodexPreferences.customModels = emptyList()
    }

    override fun setUp() {
        super.setUp()
        reset()
    }

    override fun tearDown() {
        runCatching { reset() }
        super.tearDown()
    }

    /**
     * The case the sandbox caught. The machine was last left on Sonnet at low, the account in use on Opus
     * at xhigh - a pick made in another IDE on the shared register, or on this account before a switch
     * away and back. The launch takes the account's; so must everything that shows it.
     */
    fun testAnAccountsOwnMemoryIsWhatIsShownAndWhatIsLaunched() {
        CodexPreferences.model = "sonnet"
        CodexPreferences.effort = "low"
        working("work", model = "opus[1m]", effort = "xhigh")

        assertEquals("opus[1m]", StartingChoice.model())
        assertEquals("xhigh", StartingChoice.effort())
        assertEquals("opus[1m]" to "xhigh", born())
    }

    /** "As last chosen" on the "New chats" screen names the same thing an unpinned tab gets. */
    fun testAsLastChosenMeansTheAccountsLastPick() {
        CodexPreferences.model = "sonnet"
        CodexPreferences.effort = "low"
        working("work", model = "opus", effort = "high")

        assertEquals("opus", StartingChoice.unpinnedModel())
        assertEquals("high", StartingChoice.unpinnedEffort())
    }

    fun testUnpinnedContextFollowsTheLastChoiceAndIsLaunchedAsShown() {
        assertEquals(ModelContexts.STANDARD, StartingChoice.contextMode())
        CodexPreferences.contextMode = ModelContexts.LONG

        born()

        assertEquals(ModelContexts.LONG, StartingChoice.contextMode())
        assertEquals(StartingChoice.contextMode(), bornContextMode)
    }

    fun testPinnedContextBeatsTheLastChoiceAndClearingItFollowsTheLastChoiceAgain() {
        CodexPreferences.contextMode = ModelContexts.LONG
        CodexPreferences.newTabContextMode = ModelContexts.STANDARD

        born()

        assertEquals(ModelContexts.STANDARD, StartingChoice.contextMode())
        assertEquals(StartingChoice.contextMode(), bornContextMode)
        assertEquals(ModelContexts.LONG, CodexPreferences.contextMode)

        CodexPreferences.newTabContextMode = ""
        born()

        assertEquals(ModelContexts.LONG, bornContextMode)
    }

    fun testAContextRequestOrRestoredTabOutranksThePin() {
        CodexPreferences.newTabContextMode = ModelContexts.LONG

        born(SessionLaunch(contextMode = ModelContexts.STANDARD))

        assertEquals(ModelContexts.STANDARD, bornContextMode)
        assertEquals(ModelContexts.LONG, StartingChoice.contextMode())
    }

    fun testChangingAnOpenChatsContextDoesNotOverwriteThePin() {
        CodexPreferences.newTabContextMode = ModelContexts.LONG
        val born = mutableMapOf<String, String>()
        val sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, _ -> },
            onError = { _, _ -> },
            onFinished = {},
            onBorn = { id, _, _, context, _ -> born[id] = context },
        )

        sessions.setContextMode("main", ModelContexts.STANDARD)
        sessions.setEffort("next", "max", remember = false)

        assertEquals(ModelContexts.STANDARD, sessions.contextMode("main"))
        assertEquals(ModelContexts.STANDARD, CodexPreferences.contextMode)
        assertEquals(ModelContexts.LONG, CodexPreferences.newTabContextMode)
        assertEquals(ModelContexts.LONG, born["next"])

        sessions.branchFrom("main", "fork")
        assertEquals(ModelContexts.STANDARD, sessions.contextMode("fork"))
    }

    /** The pin was said in words, and it is stronger than any memory - shown and launched alike. */
    fun testAPinBeatsTheAccountsMemory() {
        working("work", model = "opus", effort = "xhigh")
        CodexPreferences.newTabModel = "haiku"
        CodexPreferences.newTabEffort = "medium"

        assertEquals("haiku", StartingChoice.model())
        assertEquals("medium", StartingChoice.effort())
        assertEquals("haiku" to "medium", born())
        // And the entry beside the pin still names what an unpinned tab would get.
        assertEquals("opus", StartingChoice.unpinnedModel())
    }

    /** An account nobody has picked anything on - the ordinary sign-in among them - falls to the machine. */
    fun testAnAccountWithNoMemoryFallsToTheMachinesLastPick() {
        CodexPreferences.model = "sonnet"
        CodexPreferences.effort = "low"
        working("fresh")

        assertEquals("sonnet", StartingChoice.model())
        assertEquals("low", StartingChoice.effort())
        assertEquals("sonnet" to "low", born())
    }

    /** Switching accounts changes the answer, since the memory belongs to the account. */
    fun testSwitchingAccountsBringsTheOthersMemory() {
        AccountsState.getInstance().remember(account("home", model = "sonnet", effort = "low"))
        working("work", model = "opus", effort = "xhigh")
        assertEquals("opus", StartingChoice.model())

        accounts.currentId = "home"

        assertEquals("sonnet", StartingChoice.model())
        assertEquals("low", StartingChoice.effort())
        assertEquals("sonnet" to "low", born())
    }

    /**
     * A pin the account cannot run is held to what it can, and the chip says the model the launch will
     * actually use rather than the one it will be refused.
     */
    fun testAPinTheAccountCannotRunIsShownAsTheLaunchWillRunIt() {
        working("pro")
        accounts.noteModels("pro", setOf("default", "gpt-5.5"))
        CodexPreferences.newTabModel = "gpt-9-unknown"

        val shown = StartingChoice.model()
        assertFalse("the refused pin is not what the chip shows", shown == "gpt-9-unknown")
        assertEquals(shown, born().first)
    }

    /** A request that names a model outranks everything, and is clamped just the same. */
    fun testARequestOutranksThePinAndIsClampedToo() {
        working("pro", model = "sonnet")
        accounts.noteModels("pro", setOf("default", "sonnet"))
        CodexPreferences.newTabModel = "haiku"

        assertEquals("sonnet", StartingChoice.model(requested = "sonnet"))
        assertEquals("sonnet", StartingChoice.model(requested = "opus"))
        assertEquals("low", StartingChoice.effort(requested = "low"))
    }

    /**
     * A tab opened with a choice of its own - a phone's new chat - is drawn by that choice before its first
     * message, and what it is drawn by is what it is born with (see CodexSessions.planned). Reported from
     * Windows: a model added by hand, chosen for a new chat on a phone, appeared nowhere until the first
     * answer - "default" on the phone, the setting at the desk - and read as a model that cannot be started.
     */
    fun testALaunchedTabIsShownWhatItIsBornWith() {
        working("pro")
        accounts.noteModels("pro", setOf("default", "sonnet"))
        CodexPreferences.customModels = listOf("glm-4.6")
        CodexPreferences.newTabModel = "sonnet"

        var model = "unborn"
        var effort = "unborn"
        val sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, _ -> },
            onError = { _, _ -> },
            onFinished = {},
            onBorn = { _, bornEffort, bornModel, _, _ ->
                effort = bornEffort
                model = bornModel
            },
        )
        sessions.rememberLaunch("phone", SessionLaunch(model = "glm-4.6", effort = "low", mode = "acceptEdits"))

        val planned = sessions.planned("phone")
        assertEquals("glm-4.6", planned?.model)
        assertEquals("low", planned?.effort)
        assertEquals("acceptEdits", planned?.mode)

        // Brought into being the way born() does it - no process, the effort it already had.
        sessions.setEffort("phone", "low", remember = false)

        assertEquals(planned?.model, model)
        assertEquals(planned?.effort, effort)
        // Born, the choice is spent: from here the conversation itself answers.
        assertNull(sessions.planned("phone"))
    }

    /** A tab with no choice of its own has nothing to announce: the setting already draws it, and rightly. */
    fun testATabWithoutAChoiceHasNothingPlanned() {
        val sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, _ -> },
            onError = { _, _ -> },
            onFinished = {},
            onBorn = { _, _, _, _, _ -> },
        )

        assertNull(sessions.planned("plain"))
    }
}
