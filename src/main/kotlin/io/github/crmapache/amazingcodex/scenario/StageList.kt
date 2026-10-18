package io.github.crmapache.amazingcodex.scenario

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A stage that runs once per item of a list (see Stage.each): the list the head gives read, written out as
 * passes, and the one second go an item may be given.
 *
 * Pure, and kept out of the engine for the reason CarryOn and TakeOver are: these are the decisions that either
 * do an item twice or lose one, and both look perfectly healthy on the screen. The engine is a state machine
 * over processes a test cannot raise; this is arithmetic over the steps, and a test can hold every case of it.
 *
 * The shape it replaces is worth saying, because it failed in a way nobody saw coming. Such work used to be a
 * stage set to go round up to ten times, the head picking "the next item" before every pass. An umbrella of
 * seven cards took nine passes: two went on publishing plans a sign-in had kept from being written, and an
 * umbrella of nine would have lost its last card without a word, while the screen showed ten passes of one
 * card and never how many cards there were.
 */
internal object StageList {

    /** One item as the head gave it. */
    data class Listed(val label: String, val value: String)

    /** What the head's answer came to. */
    sealed interface Reading {
        /** No list at all - the field is missing or is not a list. */
        data object Missing : Reading

        /** Items with nothing to write into a prompt, by their places in the list, counting from one. */
        data class Unusable(val places: List<Int>) : Reading

        /** More items than a stage takes (see MAX_STAGE_ITEMS). */
        data class TooLong(val count: Int) : Reading

        data class Items(val items: List<Listed>) : Reading
    }

    /** How much of an item's label is kept - a few words for a row and a hint, not the item itself. */
    const val LABEL_CHARS = 120

    /**
     * The list out of the head's answer: `items`, each an object with a `value` and a `label`, or a bare string
     * that is both.
     *
     * An item that cannot be read spoils the whole answer rather than being dropped. Dropped, it is an item of
     * the list that never gets its pass and is never mentioned again - the one outcome this kind of stage
     * exists to rule out. Refused, the head is asked once more and says what it meant.
     */
    fun read(body: JsonObject): Reading {
        val array = body["items"] as? JsonArray ?: return Reading.Missing
        val listed = array.map(::itemOf)
        val unusable = listed.withIndex().filter { it.value == null }.map { it.index + 1 }

        return when {
            unusable.isNotEmpty() -> Reading.Unusable(unusable)
            listed.size > MAX_STAGE_ITEMS -> Reading.TooLong(listed.size)
            else -> Reading.Items(listed.filterNotNull())
        }
    }

    private fun itemOf(element: JsonElement): Listed? = when (element) {
        is JsonObject -> HeadAnswer.text(element, "value").trim().takeIf { it.isNotEmpty() }?.let { value ->
            Listed(label = labelOf(HeadAnswer.text(element, "label").ifBlank { value }), value = value)
        }

        // JsonNull is a primitive too, and has no content.
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { Listed(label = labelOf(it), value = it) }
        else -> null
    }

    private fun labelOf(text: String): String {
        val line = text.trim().split(WHITESPACE).joinToString(" ")
        return if (line.length <= LABEL_CHARS) line else line.take(LABEL_CHARS) + "..."
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * The steps with the stage's list written in: the pass that stood in for the list (see ScenarioRules.plan)
     * replaced by one pass per item, every card of the stage in each, in the order the head gave them.
     *
     * The keys run on from the stand-in's, so the first item's first card has the key the stand-in had - and a
     * run looked at from a page older than this kind of stage still reads one step per card per pass.
     *
     * An empty list leaves the stand-in in place, skipped, with an item that says the list was empty (see
     * RunItem.count): a stage with nothing to do is told apart from one a stopped run never reached.
     */
    fun expand(steps: List<RunStep>, stage: Stage, items: List<Listed>, now: Long): List<RunStep> {
        val first = steps.indexOfFirst { it.stageId == stage.id }
        if (first < 0) return steps
        val standIn = steps.drop(first).takeWhile { it.stageId == stage.id }

        val written = if (items.isEmpty()) {
            standIn.map { it.copy(state = StepState.SKIPPED, startedAt = 0, finishedAt = now, item = RunItem()) }
        } else {
            items.flatMapIndexed { index, listed ->
                val item = RunItem(label = listed.label, value = listed.value, index = index + 1, count = items.size)
                stage.cards.map { card -> passStep(stage.id, card.id, index + 1, card.title.ifBlank { "Untitled" }, item) }
            }
        }

        return steps.take(first) + written + steps.drop(first + standIn.size)
    }

    /**
     * Whether the item of this step may still be put off to the end of the list: it is an item, and this is
     * its first go. A second go that runs into the same thing is the head's to call done or not.
     */
    fun canPutOff(step: RunStep): Boolean {
        val item = step.item ?: return false
        return item.count > 0 && item.again.isEmpty()
    }

    /**
     * The item of the step at [at] put off: the cards after it in the same pass skipped, and a second go at it
     * written at the end of the stage - from the card that was put off to the last card of the pass, because
     * the cards before it did finish, and the ones after it were written in the belief that it would.
     *
     * At the end rather than straight away, because what stops an item now is usually something that passes:
     * a service that would not let the card in, a card the item waits on that comes later in the list. And at
     * the end of the stage rather than the end of the run, because the stages after this one were written in
     * the belief that it had finished.
     *
     * [reason] is what the second go is told about the first (see HeadTalk.cardBriefing). The step at [at]
     * itself is the engine's to mark: the judgement on it is the head's.
     */
    fun putOff(steps: List<RunStep>, at: Int, reason: String, now: Long): List<RunStep> {
        val step = steps.getOrNull(at) ?: return steps
        val item = step.item ?: return steps
        val samePass = { one: RunStep -> one.stageId == step.stageId && one.pass == step.pass }

        val marked = steps.mapIndexed { index, one ->
            if (index > at && samePass(one) && !StepState.over(one.state)) {
                one.copy(state = StepState.SKIPPED, finishedAt = now)
            } else {
                one
            }
        }

        val pass = marked.filter { it.stageId == step.stageId }.maxOf { it.pass } + 1
        val again = marked.filter(samePass).dropWhile { it.key != step.key }.map { one ->
            passStep(one.stageId, one.cardId, pass, one.title, item.copy(again = reason))
        }
        val end = marked.indexOfLast { it.stageId == step.stageId } + 1

        return marked.take(end) + again + marked.drop(end)
    }

    /** Where the run goes once the pass of the step at [at] is behind it: the first step of another pass. */
    fun afterPass(steps: List<RunStep>, at: Int): Int {
        val step = steps.getOrNull(at) ?: return steps.size
        val next = steps.withIndex().firstOrNull { (index, one) ->
            index > at && (one.stageId != step.stageId || one.pass != step.pass)
        }
        return next?.index ?: steps.size
    }

    private fun passStep(stageId: String, cardId: String, pass: Int, title: String, item: RunItem): RunStep =
        RunStep(
            key = ScenarioRules.keyOf(stageId, cardId, pass),
            cardId = cardId,
            stageId = stageId,
            pass = pass,
            title = title,
            item = item,
        )
}
