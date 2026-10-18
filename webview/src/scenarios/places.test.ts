import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import type { ScenarioRunStep } from '../protocol'
import { itemLabel, stageSize, stepPlace } from './places'

const step = (over: Partial<ScenarioRunStep> = {}): ScenarioRunStep => ({
  key: 'p:a:1',
  cardId: 'a',
  stageId: 'p',
  pass: 1,
  title: 'Plan it',
  state: 'waiting',
  conversationId: '',
  startedAt: 0,
  finishedAt: 0,
  slots: {},
  prompt: '',
  said: '',
  summary: '',
  nudges: [],
  verdict: '',
  verdictReason: '',
  handoff: '',
  failure: '',
  error: '',
  cost: 0,
  tokens: 0,
  ...over,
})

const item = (over: Partial<NonNullable<ScenarioRunStep['item']>> = {}) => ({
  label: 'Titles for the test',
  value: 'https://cards/6',
  index: 3,
  count: 7,
  again: '',
  ...over,
})

describe('where a row stands in its stage', () => {
  // A list is read by its items: how many there are is what the person wants to see, not a ceiling of passes.
  it('places a pass of a list by its item', () => {
    const perItem = { passes: 1, untilDone: false, perItem: true }

    expect(stepPlace(en, step({ item: item() }), perItem)).toBe('item 3 of 7')
    expect(stepPlace(en, step({ item: item({ again: 'Notion asked to sign in' }) }), perItem)).toBe('second go')
    expect(stepPlace(en, step(), perItem)).toBe('waiting for the list')
    expect(stepPlace(en, step({ state: 'skipped', item: item({ index: 0, count: 0, label: '', value: '' }) }), perItem)).toBe(
      'nothing on the list',
    )
  })

  it('places a pass of a loop by its pass, and a stage that runs once not at all', () => {
    expect(stepPlace(en, step({ pass: 2 }), { passes: 3, untilDone: true, perItem: false })).toBe('pass 2 of up to 3')
    expect(stepPlace(en, step({ pass: 2 }), { passes: 3, untilDone: false, perItem: false })).toBe('pass 2 of 3')
    expect(stepPlace(en, step(), { passes: 1, untilDone: false, perItem: false })).toBe('')
  })

  it('heads a stage over a list with the length of its list', () => {
    expect(stageSize(en, { passes: 1, untilDone: false, items: 7 })).toBe('7 items')
    expect(stageSize(en, { passes: 1, untilDone: false, items: 1 })).toBe('1 item')
    expect(stageSize(en, { passes: 1, untilDone: false, items: 0 })).toBe('empty list')
    expect(stageSize(en, { passes: 1, untilDone: false, items: null })).toBe('list not given yet')
    expect(stageSize(en, { passes: 3, untilDone: true })).toBe('×3, until done')
    expect(stageSize(en, { passes: 1, untilDone: false })).toBe('')
  })

  it('names the item a row is for, and nothing for a row that is not for one', () => {
    expect(itemLabel(step({ item: item() }))).toBe('Titles for the test')
    expect(itemLabel(step({ item: item({ index: 0, count: 0, label: '' }) }))).toBe('')
    expect(itemLabel(step())).toBe('')
  })
})
