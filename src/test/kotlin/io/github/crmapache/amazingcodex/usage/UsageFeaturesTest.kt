package io.github.crmapache.amazingcodex.usage

import io.github.crmapache.amazingcodex.remote.RemoteCommands
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UsageFeaturesTest {

    private fun ofMessage(type: String, vararg fields: Pair<String, Any>) =
        UsageFeatures.ofMessage(
            type,
            buildJsonObject {
                put("type", type)
                for ((name, value) in fields) {
                    when (value) {
                        is Boolean -> put(name, value)
                        else -> put(name, value.toString())
                    }
                }
            },
        )

    @Test
    fun `a press is named by the feature it stands for`() {
        assertEquals(listOf("fork"), ofMessage("newSession", "kind" to "branch"))
        assertEquals(listOf("new_tab"), ofMessage("newSession", "kind" to "main"))
        assertEquals(listOf("voice"), ofMessage("voiceStart"))
        assertEquals(listOf("improve_prompt"), ofMessage("improvePrompt", "draft" to "whatever was typed"))
        assertEquals(listOf("setting:theme"), ofMessage("setTheme", "theme" to "dark"))
        assertEquals(listOf("setting:new_chat"), ofMessage("setDefaultModel", "model" to "gpt-5.6-sol"))
        assertEquals(listOf("plugins_manage"), ofMessage("marketplaceAdd"))
        // Codex's own settings, written from the panel's screen of them.
        assertEquals(listOf("setting:codex_config"), ofMessage("setCodexConfig", "key" to "model_reasoning_summary", "value" to "auto"))
    }

    @Test
    fun `switching remote access off is not a use of it`() {
        assertEquals(listOf("remote_enable"), ofMessage("setRemoteEnabled", "enabled" to true))
        assertNull(ofMessage("setRemoteEnabled", "enabled" to false))
    }

    /** Trusting a project is a feature used and a setting changed at once; taking trust back is only the second. */
    @Test
    fun `trusting a project is counted as the feature and the setting, taking it back as the setting`() {
        assertEquals(listOf("project_trust", "setting:project_trust"), ofMessage("setProjectTrust", "trusted" to true))
        assertEquals(listOf("setting:project_trust"), ofMessage("setProjectTrust", "trusted" to false))
        assertEquals(listOf("setting:project_trust"), ofMessage("setProjectTrust"))
    }

    /**
     * What the original plugin counts and this one cannot do: the service keeps no such ids for this plugin,
     * and a message of that name - the panel still sends "stopTask" and "pluginEnable", the IDE refuses
     * both - must not count a use of something that did not happen.
     */
    @Test
    fun `what Codex cannot do counts as nothing`() {
        for (type in listOf("stopTask", "pluginEnable", "pluginDisable", "designLogin", "setClaudeConfig", "setSettingSources", "askCodexConfig")) {
            assertNull(ofMessage(type, "enabled" to true), "$type stands for no feature of this plugin")
        }
        for (id in listOf("stop_task", "design_login", "setting:claude_config", "setting:setting_sources", "screen:settingSources", "screen:claudeConfig")) {
            assertFalse(UsageFeatures.isKnown(id), "$id is the original plugin's, and the service drops it here")
        }
    }

    @Test
    fun `what the panel sends by itself counts as nothing`() {
        for (type in listOf("ready", "saveDraft", "tabShown", "history", "mcpList", "pluginList", "accountList", "scenarios", "trace", "cursor", "stat", "prompt")) {
            assertNull(ofMessage(type), "$type is sent without anybody pressing anything")
        }
    }

    @Test
    fun `every feature a message can name is one the book accepts`() {
        val types = listOf(
            "newSession", "nameSession", "reorderTabs", "queuePrompt", "takeQueued", "reorderQueue", "stop",
            "bash", "improvePrompt", "setModel", "setEffort", "setMode", "planDecision", "askAnswer",
            "agentTranscript", "openFile", "clipboardWrite", "pick", "savePastedFile", "resumeSession", "search",
            "searchAi", "voiceStart", "startPairing", "scenarioRun", "scenarioSave", "scenarioDraft",
            "scenarioSchedule", "scenarioQueue", "scenarioAnswer", "saveImage", "mcpAdd", "pluginInstall",
            "accountAdd", "accountUse", "feedbackSend", "setTheme", "setTextSize", "setLanguage",
            "setComposerLayout", "setSendKey", "setPasteCollapse", "setCalmColors", "setHiddenIndicators",
            "soundSettings", "setShareEditor", "setRestoreTabs", "setImproveInstructions", "setCustomModels",
            "setDefaultModel", "setCodexConfig", "setProjectTrust", "setExecutablePath", "voiceEnabled",
            "setRelayUrl",
        )
        for (type in types) {
            val ids = ofMessage(type, "enabled" to true, "trusted" to true)
            assertTrue(!ids.isNullOrEmpty(), "$type stands for nothing")
            for (id in ids) assertTrue(UsageFeatures.isKnown(id), "$type names \"$id\", which the book would drop")
        }
    }

    @Test
    fun `the panel may name only what the IDE cannot see arriving`() {
        assertTrue(UsageFeatures.isPanelFeature("pin"))
        assertTrue(UsageFeatures.isPanelFeature("screen:history"))
        // Counted at the IDE's door already - the panel naming it too would count it twice.
        assertFalse(UsageFeatures.isPanelFeature("voice"))
        assertFalse(UsageFeatures.isPanelFeature("screen:nonsense"))
        assertFalse(UsageFeatures.isPanelFeature("anything the panel likes"))
    }

    /** The screens are the side menu's own list; a screen added there and not here would not be counted. */
    @Test
    fun `the screens are the side menu's screens`() {
        val menu = File("webview/src/components/SideMenu.tsx")
        assertTrue(menu.exists(), "SideMenu.tsx not found at ${menu.absolutePath}")

        val union = menu.readText().substringAfter("export type MenuScreen =").substringBefore("\n\n")
        val screens = Regex("""'([a-zA-Z]+)'""").findAll(union).map { it.groupValues[1] }.toSet()

        assertTrue(screens.isNotEmpty(), "the MenuScreen union was not found")
        assertEquals(screens, UsageFeatures.SCREENS)
    }

    /**
     * Every message a phone may send is either somebody pressing something or the phone keeping itself up to
     * date, and which of the two is decided by name. A message allowed to phones later and placed nowhere
     * fails here, rather than being left out of "used from a phone" - or put into it by default.
     */
    @Test
    fun `every message a phone may send is decided about as an action or as background`() {
        val placed = UsageFeatures.PHONE_ACTIONS + UsageFeatures.PHONE_BACKGROUND

        assertEquals(emptySet(), UsageFeatures.PHONE_ACTIONS intersect UsageFeatures.PHONE_BACKGROUND)
        assertEquals(emptySet(), RemoteCommands.ALLOWED - placed, "allowed to phones and placed nowhere")
        assertEquals(emptySet(), placed - RemoteCommands.ALLOWED, "placed, but a phone may not send it at all")
    }

    @Test
    fun `a phone keeping itself up to date is not a person using it`() {
        assertTrue(UsageFeatures.isPhoneAction("permissionDecision"))
        assertTrue(UsageFeatures.isPhoneAction("prompt"))
        assertFalse(UsageFeatures.isPhoneAction("ready"))
        // The name a tab gets after its first message is guessed by the page, not typed by anybody.
        assertFalse(UsageFeatures.isPhoneAction("renameSession"))
    }
}
