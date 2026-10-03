package io.github.crmapache.amazingcodex.usage

import io.github.crmapache.amazingcodex.codex.CodexCommands
import io.github.crmapache.amazingcodex.stats.DayRecord
import io.github.crmapache.amazingcodex.stats.MinuteSet
import io.github.crmapache.amazingcodex.stats.StatsCollector
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The promise of the usage report, held by a test rather than by care: only counts leave, and every name
 * that leaves has been through a list.
 *
 * The most important check here is the first one. A day's report is compared with the exact set of keys it
 * may carry, so a field added to the statistics book does not start travelling because somebody wrote it
 * into the report without thinking - this test turns red, and PRIVACY.md is the other file to change.
 */
class UsageReportTest {

    private fun busyDay(): DayRecord = DayRecord().apply {
        minutes.mark(9 * 60)
        minutes.mark(9 * 60 + 20)
        minutes.mark(14 * 60)
        prompts = 12
        turns = 11
        sessions = 3
        turnMillis = 95_500
        files.add("hash-of-secret-path")
        files.add("hash-of-another-one")
        tools["Read"] = 20
        tools["MCP"] = 4
        tools["some weird name"] = 1
        // As the book keeps them: dressed for the statistics card (see StatsCollector.familyOf).
        models[StatsCollector.familyOf("gpt-5.6-sol")] = 9
        models[StatsCollector.familyOf("my-company-model")] = 2
        models[StatsCollector.familyOf("gpt-acme-support")] = 3
        slash.add("compact")
        slash.add("deploy-to-acme-prod")
        slash.add("prompts:acme-release")
        features["voice"] = 2
        features["screen:history"] = 1
        features["not-a-feature"] = 5
        cost = 12.5
        tokensIn = 1_000_000
        earlyPrompts = 4
        thanksWays.add("github")
        ranOutWindows.add("account-7:five_hour:2026-09-30T12:00")
    }

    /** A model of one's own, added by hand in the panel - shaped like OpenAI's, which is the point. */
    private val own = listOf("gpt-acme-support")

    @Test
    fun `a day carries exactly the keys it may carry and nothing more`() {
        val json = UsageReport.dayJson("2026-09-29", busyDay(), own)

        assertEquals(
            setOf(
                "day", "minutes", "conversations", "prompts", "turns", "turnSeconds", "phonePrompts", "phoneActions", "forks",
                "edits", "linesAdded", "linesRemoved", "filesEdited", "permissionsAsked", "permissionsDenied",
                "plansApproved", "todosDone", "attachments", "quotes", "ranOutFiveHour", "watched", "mcpConnected",
                "plugins", "longestConversation", "sittings", "tools", "models", "slash", "features",
            ),
            json.keys,
        )
    }

    @Test
    fun `nothing a person could be recognised by travels`() {
        val text = UsageReport.dayJson("2026-09-29", busyDay(), own).toString()

        // The file hashes, a custom model's name, a command and a prompt of one's own, an account's window.
        for (secret in listOf("hash-of-secret-path", "my-company-model", "acme", "account-7", "some weird name")) {
            assertFalse(secret in text, "\"$secret\" leaked into the report: $text")
        }
        // Nor the figures that say what the work cost or when in the day it happened.
        for (field in listOf("cost", "tokens", "early", "late", "thanks", "hours")) {
            assertFalse("\"$field" in text, "\"$field\" should not be in the report: $text")
        }
    }

    @Test
    fun `names are held to their lists`() {
        val json = UsageReport.dayJson("2026-09-29", busyDay(), own)

        assertEquals(mapOf("Read" to 20, "MCP" to 4, "other" to 1), counts(json, "tools"))
        assertEquals(mapOf("gpt-5.6-sol" to 9, "Other" to 5), counts(json, "models"))
        assertEquals(mapOf("compact" to 1, "custom" to 2), counts(json, "slash"))
        assertEquals(mapOf("voice" to 2, "screen:history" to 1), counts(json, "features"))
        assertEquals(2, json["filesEdited"]!!.jsonPrimitive.content.toInt())
        assertEquals(95, json["turnSeconds"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a sitting ends at a gap longer than half an hour`() {
        val minutes = MinuteSet()
        // 9:00-9:20 with a ten-minute pause inside, then 14:00 on its own, then 14:40-14:41.
        (540..545).forEach(minutes::mark)
        (555..560).forEach(minutes::mark)
        minutes.mark(840)
        minutes.mark(880)
        minutes.mark(881)

        assertEquals(listOf(21, 1, 2), UsageReport.sittings(minutes))
        assertEquals(emptyList(), UsageReport.sittings(MinuteSet()))
    }

    @Test
    fun `only days from the first allowed one on, and only days that saw anything`() {
        val together = TreeMap<String, DayRecord>().apply {
            put("2026-09-27", busyDay())
            put("2026-09-28", DayRecord())
            put("2026-09-29", busyDay())
            put("2026-09-30", DayRecord().apply { features["voice"] = 1 })
        }

        assertEquals(listOf("2026-09-29", "2026-09-30"), UsageReport.days(together, "2026-09-28", own).map { it.day })
    }

    @Test
    fun `a day that changed has a different digest`() {
        val day = busyDay()
        val before = UsageReport.days(TreeMap(mapOf("2026-09-29" to day)), "2026-09-01", own).single().digest
        day.prompts++
        val after = UsageReport.days(TreeMap(mapOf("2026-09-29" to day)), "2026-09-01", own).single().digest

        assertNotEquals(before, after)
    }

    @Test
    fun `the report wraps the days with the environment and the settings`() {
        val days = UsageReport.days(TreeMap(mapOf("2026-09-29" to busyDay())), "2026-09-01", own)
        val report = UsageReport.report(
            "Rk3pD9xQ2mV7tL1aZ8bN4c",
            UsageReport.Environment("0.14.0", "WS", "2026.2", "mac", "arm64", "0.152.0", "ru"),
            mapOf("remote" to true, "layout" to "bottom", "accounts" to 2),
            days,
        )

        assertEquals(setOf("schema", "product", "install", "env", "settings", "days"), report.keys)
        // The service files the figures under the plugin the report names - and under the original's when it names none.
        assertEquals("acx", report["product"]!!.jsonPrimitive.content)
        assertEquals("Rk3pD9xQ2mV7tL1aZ8bN4c", report["install"]!!.jsonPrimitive.content)
        assertEquals("WS", report["env"]!!.jsonObject["ide"]!!.jsonPrimitive.content)
        assertEquals("true", report["settings"]!!.jsonObject["remote"]!!.jsonPrimitive.content)
        assertEquals(1, report["days"]!!.jsonArray.size)
        assertTrue(report["days"]!!.jsonArray[0].jsonObject["sittings"]!!.jsonArray.isNotEmpty())
    }

    /**
     * The book dresses a model's id for the statistics card, and every day already in it is written that
     * way; the report has to get the id back exactly, or the service would fold a real model into "Other".
     */
    @Test
    fun `a model of Codex's catalogue travels by its id, out of the name the book keeps it under`() {
        for (id in listOf("gpt-5.6-sol", "gpt-5.5", "gpt-5", "gpt-5.1-codex-max", "gpt-4.1-mini", "gpt-oss-120b", "o3", "o4-mini", "codex-mini-latest")) {
            assertEquals(id, UsageReport.modelName(StatsCollector.familyOf(id), emptyList()), "the book keeps $id as ${StatsCollector.familyOf(id)}")
        }
    }

    @Test
    fun `a model added by hand or not shaped like OpenAI's travels as Other`() {
        // Added by hand: "Other" whatever it looks like, and whatever case it was typed in.
        assertEquals("Other", UsageReport.modelName(StatsCollector.familyOf("gpt-acme-support"), listOf("GPT-Acme-Support")))
        assertEquals("Other", UsageReport.modelName("o3", listOf("o3")))

        for (name in listOf("Opus", "claude-opus-5", "llama3:70b", "acme/gpt-5", "ft:gpt-4o:acme::x1", "gpt-", "gpt-5..5", "gpt-" + "a".repeat(40), "")) {
            assertEquals("Other", UsageReport.modelName(name, emptyList()), name)
        }
    }

    /** The commands the IDE itself turns into requests are built-in by definition - none of them is somebody's own. */
    @Test
    fun `every command the panel runs for Codex is a built-in one, and a prompt of one's own is custom`() {
        for (name in CodexCommands.BUILT_IN) assertEquals(name, UsageReport.commandName(name))
        assertEquals("btw", UsageReport.commandName("btw"))
        assertEquals("custom", UsageReport.commandName(StatsCollector.slashCommandOf("/prompts:acme-release now")!!))
        assertEquals("custom", UsageReport.commandName("acme-skill"))
    }

    private fun counts(json: JsonObject, name: String): Map<String, Int> =
        json[name]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
}
