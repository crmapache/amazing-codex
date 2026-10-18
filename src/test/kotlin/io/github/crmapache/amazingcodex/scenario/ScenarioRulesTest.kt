package io.github.crmapache.amazingcodex.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a scenario means, checked here and again in webview/src/scenarios/rules.test.ts.
 *
 * The examples are deliberately the same on both sides. The rules live in two languages because the
 * editor has to say what is wrong while somebody types and the run has to be refused before a process is
 * raised - and two copies that are never compared are two copies that drift (see ScenarioRules).
 */
class ScenarioRulesTest {

    private fun card(
        id: String = "c1",
        prompt: String = "",
        slots: List<CardSlot> = emptyList(),
        title: String = "Card",
    ) = Card(id = id, title = title, prompt = prompt, slots = slots)

    private fun scenario(
        inputs: List<ScenarioInput> = emptyList(),
        stages: List<Stage> = listOf(Stage(id = "s1", title = "Stage", cards = listOf(card(prompt = "do it")))),
    ) = Scenario(id = "x", name = "Round", inputs = inputs, stages = stages)

    private fun input(name: String, required: Boolean = false) =
        ScenarioInput(id = name, name = name, label = name, required = required)

    @Test
    fun `the two kinds of placeholder are read apart`() {
        val text = "Fix {{ticket}} using [[findings]] and {{ ticket }} again"

        assertEquals(listOf("ticket"), ScenarioRules.mentionedInputs(text))
        assertEquals(listOf("findings"), ScenarioRules.mentionedSlots(text))
    }

    // A name nobody answered stands rather than being emptied: a prompt that says {{ticket}} out loud is
    // a mistake somebody can see, while one that quietly lost the number goes off and does the wrong
    // thing to the wrong branch.
    @Test
    fun `an unanswered name is left standing`() {
        assertEquals("a X b {{missing}}", ScenarioRules.fillInputs("a {{one}} b {{missing}}", mapOf("one" to "X")))
        assertEquals("a X b [[missing]]", ScenarioRules.fillSlots("a [[one]] b [[missing]]", mapOf("one" to "X")))
    }

    // A name used in a prompt that nobody declared is the failure this exists for: it would reach the
    // agent as the literal text [[findings]], because nobody was ever asked to fill it.
    @Test
    fun `a slot used but never declared is still a slot the head must fill`() {
        val one = card(prompt = "read [[findings]] and [[extra]]", slots = listOf(CardSlot("s", "findings", "the file")))

        assertEquals(listOf("findings", "extra"), ScenarioRules.declaredSlots(one).map { it.name })
    }

    @Test
    fun `an undeclared slot blocks a run and an unused one does not`() {
        val undeclared = scenario(stages = listOf(Stage(id = "s1", cards = listOf(card(prompt = "read [[x]]")))))
        assertFalse(ScenarioRules.runnable(undeclared))

        val unused = scenario(
            stages = listOf(
                Stage(id = "s1", cards = listOf(card(prompt = "do it", slots = listOf(CardSlot("s", "x", ""))))),
            ),
        )
        assertTrue(ScenarioRules.runnable(unused))
        assertEquals(listOf(ScenarioRules.Problems.UNUSED_SLOT), ScenarioRules.problemsOf(unused).map { it.kind })
    }

    @Test
    fun `a name the scenario never asked for is a problem`() {
        val one = scenario(stages = listOf(Stage(id = "s1", cards = listOf(card(prompt = "fix {{ticket}}")))))

        assertEquals(listOf(ScenarioRules.Problems.UNKNOWN_INPUT), ScenarioRules.problemsOf(one).map { it.kind })
        assertFalse(ScenarioRules.runnable(one))
    }

    @Test
    fun `an empty scenario and an empty stage are both refused`() {
        assertEquals(listOf(ScenarioRules.Problems.NO_STAGES), ScenarioRules.problemsOf(scenario(stages = emptyList())).map { it.kind })

        val empty = scenario(stages = listOf(Stage(id = "s1", cards = emptyList())))
        assertEquals(listOf(ScenarioRules.Problems.EMPTY_STAGE), ScenarioRules.problemsOf(empty).map { it.kind })
    }

    @Test
    fun `names have to be names`() {
        val one = scenario(inputs = listOf(input("a b"), input("ok"), input("ok")))

        assertEquals(
            listOf(ScenarioRules.Problems.BAD_INPUT_NAME, ScenarioRules.Problems.DUPLICATE_INPUT),
            ScenarioRules.problemsOf(one).map { it.kind }.filterNot { it == ScenarioRules.Problems.NO_PROMPT },
        )
    }

    // The whole walk written out before it starts: two cards looped three times are six rows, in the
    // order they will run, and that is the picture the timeline draws before any of them has happened.
    @Test
    fun `a loop is written out flat`() {
        val stage = Stage(id = "s1", repeat = 3, cards = listOf(card(id = "a", prompt = "x"), card(id = "b", prompt = "y")))
        val plan = ScenarioRules.plan(scenario(stages = listOf(stage)))

        assertEquals(6, plan.size)
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), plan.map { it.cardId })
        assertEquals(listOf(1, 1, 2, 2, 3, 3), plan.map { it.pass })
        assertEquals(6, ScenarioRules.cardRuns(scenario(stages = listOf(stage))))
    }

    // One card of one pass, which is not the same thing as one card: three passes are three different
    // things that happened, and they must not answer to one name.
    @Test
    fun `every go at a card has a name of its own`() {
        val stage = Stage(id = "s1", repeat = 2, cards = listOf(card(id = "a", prompt = "x")))

        assertEquals(listOf("s1:a:1", "s1:a:2"), ScenarioRules.plan(scenario(stages = listOf(stage))).map { it.key })
    }

    // The file on disk is not to be trusted: it is edited by hand and written by older builds.
    @Test
    fun `a pass count out of range is clamped rather than believed`() {
        assertEquals(1, ScenarioRules.passesOf(Stage(id = "s", repeat = 0)))
        assertEquals(1, ScenarioRules.passesOf(Stage(id = "s", repeat = -4)))
        assertEquals(MAX_STAGE_REPEAT, ScenarioRules.passesOf(Stage(id = "s", repeat = 99)))
    }

    @Test
    fun `only what was asked for and left empty counts as missing`() {
        val one = scenario(inputs = listOf(input("ticket", required = true), input("note")))

        assertEquals(listOf("ticket"), ScenarioRules.missingInputs(one, mapOf("note" to "hello")))
        assertEquals(listOf("ticket"), ScenarioRules.missingInputs(one, mapOf("ticket" to "   ")))
        assertEquals(emptyList(), ScenarioRules.missingInputs(one, mapOf("ticket" to "ACC-1")))
    }

    // A field nobody touched and a field typed in and cleared again are the same answer. They were not:
    // the form sends only what was typed in, and an absent name is left standing by fillInputs - so an
    // optional question left alone reached the agent as the literal {{note}}.
    @Test
    fun `a declared name left alone is answered empty rather than left standing`() {
        val one = scenario(inputs = listOf(input("ticket", required = true), input("note")))
        val answers = ScenarioRules.answers(one, mapOf("ticket" to "ACC-1"))

        assertEquals(mapOf("ticket" to "ACC-1", "note" to ""), answers)
        assertEquals("ACC-1 says ", ScenarioRules.fillInputs("{{ticket}} says {{note}}", answers))
        assertEquals(
            ScenarioRules.fillInputs("{{ticket}} says {{note}}", ScenarioRules.answers(one, mapOf("ticket" to "ACC-1", "note" to ""))),
            ScenarioRules.fillInputs("{{ticket}} says {{note}}", answers),
        )
    }

    // What the person did type stands, and a name the scenario never declared is still left standing:
    // that one is a typo in a prompt, and it has to be visible (see fillInputs).
    @Test
    fun `answers keep what was typed and do not invent undeclared names`() {
        val one = scenario(inputs = listOf(input("ticket")))

        assertEquals(mapOf("ticket" to "ACC-1"), ScenarioRules.answers(one, mapOf("ticket" to "ACC-1")))
        assertEquals("{{stray}}", ScenarioRules.fillInputs("{{stray}}", ScenarioRules.answers(one, emptyMap())))
    }
    // A stage over a list: the item is written in the prompts as a slot is, and nobody declares it - the
    // panel fills it, one value per pass (see Stage.each).
    @Test
    fun `the item of a stage over a list is not a slot the head is asked for`() {
        val one = card(prompt = "/plan [[card]] with [[note]]", slots = listOf(CardSlot("n", "note", "a note")))
        val stage = Stage(id = "s1", each = StageEach(name = "card", description = "the cards"), cards = listOf(one))

        assertEquals(listOf("note"), ScenarioRules.slotsToFill(stage, one).map { it.name })
        assertTrue(ScenarioRules.runnable(scenario(stages = listOf(stage))))
        assertEquals(emptyList(), ScenarioRules.problemsOf(scenario(stages = listOf(stage))))
    }

    @Test
    fun `a stage over a list says what the list is and calls its item a name`() {
        val unnamed = Stage(id = "s1", each = StageEach(name = "the card", description = "x"), cards = listOf(card(prompt = "do it")))
        val unsaid = Stage(id = "s1", each = StageEach(name = "card", description = " "), cards = listOf(card(prompt = "/plan [[card]]")))

        assertEquals(listOf(ScenarioRules.Problems.BAD_ITEM_NAME), ScenarioRules.problemsOf(scenario(stages = listOf(unnamed))).map { it.kind })
        assertEquals(listOf(ScenarioRules.Problems.NO_ITEM_LIST), ScenarioRules.problemsOf(scenario(stages = listOf(unsaid))).map { it.kind })
        assertFalse(ScenarioRules.runnable(scenario(stages = listOf(unnamed))))
        assertFalse(ScenarioRules.runnable(scenario(stages = listOf(unsaid))))
    }

    // One name with two fillers - the panel and the head - is a prompt nobody can predict.
    @Test
    fun `a slot under the item's own name is refused`() {
        val one = card(prompt = "/plan [[card]]", slots = listOf(CardSlot("c", "card", "the next card")))
        val stage = Stage(id = "s1", each = StageEach(name = "card", description = "the cards"), cards = listOf(one))

        assertEquals(listOf(ScenarioRules.Problems.ITEM_SLOT_CLASH), ScenarioRules.problemsOf(scenario(stages = listOf(stage))).map { it.kind })
        assertFalse(ScenarioRules.runnable(scenario(stages = listOf(stage))))
    }

    // Every pass then runs the same words. Worth saying, not worth refusing: the item may reach the cards
    // through the head's slots instead.
    @Test
    fun `an item no prompt writes is said but does not stop a run`() {
        val stage = Stage(id = "s1", each = StageEach(name = "card", description = "the cards"), cards = listOf(card(prompt = "do it")))

        assertEquals(listOf(ScenarioRules.Problems.UNUSED_ITEM), ScenarioRules.problemsOf(scenario(stages = listOf(stage))).map { it.kind })
        assertTrue(ScenarioRules.runnable(scenario(stages = listOf(stage))))
    }

    // How many items there are is the head's answer, given as the stage begins: until then the stage is one
    // pass, the place the list is asked for - whatever count the file carries beside it.
    @Test
    fun `a stage over a list is planned as one pass and never loops until done`() {
        val stage = Stage(
            id = "s1",
            repeat = 10,
            untilDone = true,
            each = StageEach(name = "card", description = "the cards"),
            cards = listOf(card(id = "a", prompt = "/plan [[card]]"), card(id = "b", prompt = "/check [[card]]")),
        )

        assertEquals(listOf("s1:a:1", "s1:b:1"), ScenarioRules.plan(scenario(stages = listOf(stage))).map { it.key })
        assertEquals(2, ScenarioRules.cardRuns(scenario(stages = listOf(stage))))
        assertFalse(ScenarioRules.loopsUntilDone(stage))
        assertTrue(ScenarioRules.loopsUntilDone(stage.copy(each = null)))
    }
}
