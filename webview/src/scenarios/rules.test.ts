import { describe, expect, it } from 'vitest'
import type { Scenario, ScenarioCard, ScenarioCardSlot, ScenarioInput, ScenarioStage } from '../protocol'
import {
  cardRuns,
  declaredSlots,
  fillInputs,
  fillSlots,
  MAX_STAGE_REPEAT,
  mentionedInputs,
  mentionedSlots,
  missingInputs,
  loops,
  passesOf,
  plan,
  problemsOf,
  runnable,
  slotsToFill,
} from './rules'

/**
 * The same examples the IDE's ScenarioRulesTest holds.
 *
 * The rules live in two languages because the editor has to say what is wrong while somebody types and
 * the run has to be refused before a process is raised - and two copies that are never compared are two
 * copies that drift (see rules.ts).
 */

const card = (over: Partial<ScenarioCard> = {}): ScenarioCard => ({
  id: 'c1',
  title: 'Card',
  prompt: '',
  slots: [],
  dod: '',
  after: '',
  model: '',
  effort: '',
  permissionMode: '',
  ...over,
})

const slot = (name: string, description = ''): ScenarioCardSlot => ({ id: name, name, description })

const stage = (over: Partial<ScenarioStage> = {}): ScenarioStage => ({
  id: 's1',
  title: 'Stage',
  repeat: 1,
  untilDone: false,
  cards: [card({ prompt: 'do it' })],
  ...over,
})

const input = (name: string, required = false): ScenarioInput => ({
  id: name,
  name,
  label: name,
  placeholder: '',
  required,
})

const scenario = (over: Partial<Scenario> = {}): Scenario => ({
  version: 1,
  id: 'x',
  name: 'Round',
  createdAt: 0,
  updatedAt: 0,
  inputs: [],
  head: { briefing: '', model: '', effort: '', permissionMode: 'default', onQuestion: 'head', retries: 2 },
  stages: [stage()],
  scope: 'project',
  ...over,
})

describe('what a scenario says', () => {
  it('reads the two kinds of placeholder apart', () => {
    const text = 'Fix {{ticket}} using [[findings]] and {{ ticket }} again'

    expect(mentionedInputs(text)).toEqual(['ticket'])
    expect(mentionedSlots(text)).toEqual(['findings'])
  })

  // A name nobody answered stands rather than being emptied: a prompt that says {{ticket}} out loud is a
  // mistake somebody can see, while one that quietly lost the number does the wrong thing to the wrong
  // branch.
  it('leaves an unanswered name standing', () => {
    expect(fillInputs('a {{one}} b {{missing}}', { one: 'X' })).toBe('a X b {{missing}}')
    expect(fillSlots('a [[one]] b [[missing]]', { one: 'X' })).toBe('a X b [[missing]]')
  })

  it('counts a slot used but never declared as one the head must fill', () => {
    const one = card({ prompt: 'read [[findings]] and [[extra]]', slots: [slot('findings', 'the file')] })

    expect(declaredSlots(one).map((it) => it.name)).toEqual(['findings', 'extra'])
  })
})

describe('what is wrong with a scenario', () => {
  it('blocks a run on an undeclared slot and not on an unused one', () => {
    const undeclared = scenario({ stages: [stage({ cards: [card({ prompt: 'read [[x]]' })] })] })
    expect(runnable(undeclared)).toBe(false)

    const unused = scenario({ stages: [stage({ cards: [card({ prompt: 'do it', slots: [slot('x')] })] })] })
    expect(runnable(unused)).toBe(true)
    expect(problemsOf(unused).map((it) => it.kind)).toEqual(['unusedSlot'])
  })

  it('sees a name the scenario never asked for', () => {
    const one = scenario({ stages: [stage({ cards: [card({ prompt: 'fix {{ticket}}' })] })] })

    expect(problemsOf(one).map((it) => it.kind)).toEqual(['unknownInput'])
    expect(runnable(one)).toBe(false)
  })

  it('refuses an empty scenario and an empty stage', () => {
    expect(problemsOf(scenario({ stages: [] })).map((it) => it.kind)).toEqual(['noStages'])
    expect(problemsOf(scenario({ stages: [stage({ cards: [] })] })).map((it) => it.kind)).toEqual(['emptyStage'])
  })

  it('wants names to be names', () => {
    const one = scenario({ inputs: [input('a b'), input('ok'), input('ok')] })

    expect(problemsOf(one).map((it) => it.kind)).toEqual(['badInputName', 'duplicateInput'])
  })

  it('counts only what was asked for and left empty as missing', () => {
    const one = scenario({ inputs: [input('ticket', true), input('note')] })

    expect(missingInputs(one, { note: 'hello' })).toEqual(['ticket'])
    expect(missingInputs(one, { ticket: '   ' })).toEqual(['ticket'])
    expect(missingInputs(one, { ticket: 'ACC-1' })).toEqual([])
  })
})

describe('the walk written out before it starts', () => {
  // Two cards looped three times are six rows, in the order they will run - the picture the timeline
  // draws before any of them has happened.
  it('writes a loop out flat', () => {
    const looped = stage({ repeat: 3, cards: [card({ id: 'a', prompt: 'x' }), card({ id: 'b', prompt: 'y' })] })
    const rows = plan(scenario({ stages: [looped] }))

    expect(rows).toHaveLength(6)
    expect(rows.map((it) => it.cardId)).toEqual(['a', 'b', 'a', 'b', 'a', 'b'])
    expect(rows.map((it) => it.pass)).toEqual([1, 1, 2, 2, 3, 3])
    expect(cardRuns(scenario({ stages: [looped] }))).toBe(6)
  })

  it('gives every go at a card a name of its own', () => {
    const looped = stage({ repeat: 2, cards: [card({ id: 'a', prompt: 'x' })] })

    expect(plan(scenario({ stages: [looped] })).map((it) => it.key)).toEqual(['s1:a:1', 's1:a:2'])
  })

  it('clamps a pass count out of range rather than believing it', () => {
    expect(passesOf(stage({ repeat: 0 }))).toBe(1)
    expect(passesOf(stage({ repeat: -4 }))).toBe(1)
    expect(passesOf(stage({ repeat: 99 }))).toBe(MAX_STAGE_REPEAT)
  })
})

describe('a stage that runs once per item of a list', () => {
  const each = { name: 'card', description: 'the cards' }

  // The item is written in the prompts as a slot is, and nobody declares it - the panel fills it, one value
  // per pass.
  it('does not ask the main thread for the item', () => {
    const one = card({ prompt: '/plan [[card]] with [[note]]', slots: [slot('note', 'a note')] })
    const listed = stage({ each, cards: [one] })

    expect(slotsToFill(listed, one).map((it) => it.name)).toEqual(['note'])
    expect(problemsOf(scenario({ stages: [listed] }))).toEqual([])
    expect(runnable(scenario({ stages: [listed] }))).toBe(true)
  })

  it('says what the list is and calls its item a name', () => {
    const unnamed = stage({ each: { name: 'the card', description: 'x' } })
    const unsaid = stage({ each: { name: 'card', description: ' ' }, cards: [card({ prompt: '/plan [[card]]' })] })

    expect(problemsOf(scenario({ stages: [unnamed] })).map((it) => it.kind)).toEqual(['badItemName'])
    expect(problemsOf(scenario({ stages: [unsaid] })).map((it) => it.kind)).toEqual(['noItemList'])
    expect(runnable(scenario({ stages: [unnamed] }))).toBe(false)
    expect(runnable(scenario({ stages: [unsaid] }))).toBe(false)
  })

  // One name with two fillers - the panel and the main thread - is a prompt nobody can predict.
  it('refuses a slot under the item\'s own name', () => {
    const listed = stage({ each, cards: [card({ prompt: '/plan [[card]]', slots: [slot('card', 'the next card')] })] })

    expect(problemsOf(scenario({ stages: [listed] })).map((it) => it.kind)).toEqual(['itemSlotClash'])
    expect(runnable(scenario({ stages: [listed] }))).toBe(false)
  })

  it('says an item no prompt writes, and runs all the same', () => {
    const listed = stage({ each })

    expect(problemsOf(scenario({ stages: [listed] })).map((it) => it.kind)).toEqual(['unusedItem'])
    expect(runnable(scenario({ stages: [listed] }))).toBe(true)
  })

  // How many items there are is the main thread's answer: until then the stage is one pass, the place the
  // list is asked for - whatever count the file carries beside it.
  it('is planned as one pass and counts as a loop', () => {
    const listed = stage({
      repeat: 10,
      untilDone: true,
      each,
      cards: [card({ id: 'a', prompt: '/plan [[card]]' }), card({ id: 'b', prompt: '/check [[card]]' })],
    })

    expect(plan(scenario({ stages: [listed] })).map((it) => it.key)).toEqual(['s1:a:1', 's1:b:1'])
    expect(cardRuns(scenario({ stages: [listed] }))).toBe(2)
    expect(loops(listed)).toBe(true)
    expect(loops(stage())).toBe(false)
  })
})
