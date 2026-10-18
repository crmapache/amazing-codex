import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import type { AgentEvent, ModelInfo } from '../protocol'
import { initialPanelState, reducePanel, type PanelState } from './build'
import {
  fastAsked,
  fastCommand,
  fastModeShown,
  fastModeSub,
  fastReasonText,
  fastTierNote,
  modelHasFastMode,
  PENDING,
} from './fastMode'
import { localCommand } from './slash'

/**
 * Fast mode takes two halves from two places - the ask from the IDE, whether it is on from what Codex says - and
 * every screen reads them through the same few functions. What is pinned here is how they read together, and that a
 * fast tier from Codex's own config is shown as plainly as one asked for here.
 */
describe('what fast mode shows', () => {
  it('is off while nobody asks and Codex runs no fast tier', () => {
    expect(fastModeShown(undefined, undefined)).toEqual({ kind: 'off' })
    expect(fastModeShown(undefined, { state: 'off' })).toEqual({ kind: 'off' })
    expect(fastModeShown(false, { state: 'off' })).toEqual({ kind: 'off' })
  })

  // Nobody asked here, and the conversation runs fast all the same: Codex's config put it there, and it spends the
  // same limits. Never invisible.
  it('is on when Codex runs the fast tier from its own config', () => {
    expect(fastModeShown(undefined, { state: 'on' })).toEqual({ kind: 'on', fromSettings: true })
  })

  // An ask Codex has not heard yet - nothing since it, or an answer about the turn that was already running.
  it('waits for the next turn to carry the ask', () => {
    expect(fastModeShown(true, undefined)).toEqual({ kind: 'waiting' })
    expect(fastModeShown(true, { state: 'off', reason: PENDING })).toEqual({ kind: 'waiting' })
    expect(fastModeShown(false, { state: 'on', reason: PENDING })).toEqual({ kind: 'off' })
  })

  it('serves, or says why it did not', () => {
    expect(fastModeShown(true, { state: 'on' })).toEqual({ kind: 'on' })
    expect(fastModeShown(true, { state: 'off', reason: 'not_served' })).toEqual({ kind: 'unavailable', reason: 'not_served' })
    expect(fastModeShown(true, { state: 'off', reason: 'model_not_allowed' })).toEqual({
      kind: 'unavailable',
      reason: 'model_not_allowed',
    })
  })

  it('words a reason it knows and falls back on one it does not', () => {
    expect(fastReasonText(en, 'not_served')).toBe(en.fast.reasons.notServed)
    expect(fastReasonText(en, 'model_not_allowed')).toBe(en.fast.reasons.modelNotAllowed)
    expect(fastReasonText(en, 'a_reason_from_a_later_ide')).toBe(en.fast.reasons.unknown)
  })

  it('says under the switch where it comes from, and what Codex says of the tier while it is off', () => {
    expect(fastModeSub(en, { kind: 'on', fromSettings: true })).toBe(en.fast.fromSettingsSub)
    expect(fastModeSub(en, { kind: 'on' })).toBe(en.fast.onSub)
    expect(fastModeSub(en, { kind: 'off' })).toBe(en.fast.offSub)
    expect(fastModeSub(en, { kind: 'off' }, '1.5x speed, increased usage')).toBe(en.fast.offSubTier('1.5x speed, increased usage'))
  })
})

describe('which models have fast mode', () => {
  const catalogue: ModelInfo[] = [
    {
      value: 'gpt-5.6-terra',
      label: 'GPT-5.6-Terra',
      description: '',
      resolved: 'gpt-5.6-terra',
      isDefault: true,
      fast: true,
      fastDescription: '1.5x speed, increased usage',
    },
    { value: 'gpt-6.1-sol', label: 'GPT-6.1 Sol', description: '', resolved: 'gpt-6.1-sol', fast: true },
    { value: 'gpt-5.5', label: 'GPT-5.5', description: '', resolved: 'gpt-5.5' },
  ]

  it('takes the catalogue at its word, "default" as the model it comes to', () => {
    expect(modelHasFastMode(catalogue, 'gpt-5.6-terra')).toBe(true)
    expect(modelHasFastMode(catalogue, 'GPT-6.1-Sol')).toBe(true)
    expect(modelHasFastMode(catalogue, 'gpt-5.5')).toBe(false)
    expect(modelHasFastMode(catalogue, 'default')).toBe(true)
    expect(modelHasFastMode([{ ...catalogue[2], isDefault: true }], 'default')).toBe(false)
  })

  // Codex has no rule by name to fall back on: without its word there is no switch to offer.
  it('says no without a catalogue, or for a model it does not name', () => {
    expect(modelHasFastMode(null, 'gpt-5.6-terra')).toBe(false)
    expect(modelHasFastMode([], 'gpt-5.6-terra')).toBe(false)
    expect(modelHasFastMode(catalogue, 'my-own-model')).toBe(false)
    expect(modelHasFastMode(catalogue, undefined)).toBe(false)
  })

  it("quotes Codex's own line about the tier where it gives one", () => {
    expect(fastTierNote(catalogue, 'default')).toBe('1.5x speed, increased usage')
    expect(fastTierNote(catalogue, 'gpt-6.1-sol')).toBeUndefined()
    expect(fastTierNote(catalogue, 'gpt-5.5')).toBeUndefined()
  })
})

describe('/fast', () => {
  it('is the panel own command, with or without a word after it', () => {
    expect(localCommand(en, '/fast')).toEqual({ name: 'fast', argument: '' })
    expect(localCommand(en, '/fast on')).toEqual({ name: 'fast', argument: 'on' })
  })

  it('asks for what it says, and a bare one turns over what is shown', () => {
    expect(fastAsked('on', { kind: 'off' })).toBe(true)
    expect(fastAsked('OFF', { kind: 'on' })).toBe(false)
    expect(fastAsked('', { kind: 'on' })).toBe(false)
    expect(fastAsked('', { kind: 'off' })).toBe(true)
    expect(fastAsked('', { kind: 'waiting' })).toBe(false)
    expect(fastAsked('', { kind: 'unavailable', reason: 'not_served' })).toBe(false)
  })

  // On from Codex's config is on: a bare `/fast` over it is "off for this conversation", as the switch is.
  it('turns fast mode from Codex settings off', () => {
    expect(fastAsked('', fastModeShown(undefined, { state: 'on' }))).toBe(false)
  })

  // The phone answers it itself; anything more than the command is a message after all.
  it('is recognised on the phone only as a whole message', () => {
    expect(fastCommand(' /fast ')).toBe('')
    expect(fastCommand('/fast off')).toBe('off')
    expect(fastCommand('/fast and tell me why')).toBeNull()
    expect(fastCommand('/faster')).toBeNull()
  })
})

describe('the feed and fast mode', () => {
  const at = 1_700_000_000_000

  const init = (state: 'on' | 'off', reason?: string): AgentEvent => ({
    type: 'system',
    subtype: 'init',
    fast_mode_state: state,
    ...(reason ? { fast_mode_disabled_reason: reason } : {}),
  })
  const result = (state: 'on' | 'off', reason?: string): AgentEvent => ({
    type: 'result',
    subtype: 'success',
    num_turns: 1,
    result: 'done',
    fast_mode_state: state,
    ...(reason ? { fast_mode_disabled_reason: reason } : {}),
  })
  const play = (state: PanelState, ...events: AgentEvent[]): PanelState =>
    events.reduce((panel, event, index) => reducePanel(panel, { kind: 'agent', event }, at + index), state)
  const rows = (state: PanelState) => state.items.filter((item) => item.kind === 'fast')
  const shown = (state: PanelState) => fastModeShown(state.fastMode, state.fastReport)
  const asked = reducePanel(initialPanelState, { kind: 'fastModeApplied', on: true }, at)

  it('reads what the stream says and puts nothing in the feed while it serves', () => {
    const state = play(asked, init('on'), result('on'))

    expect(state.fastReport).toEqual({ state: 'on' })
    expect(shown(state)).toEqual({ kind: 'on' })
    expect(rows(state)).toEqual([])
  })

  // On from Codex's config, and then turned off here: the switch goes off at once - the next turn carries the ask,
  // and the answer about the old tier must not keep it lit until then.
  it('turns fast mode from Codex settings off at once, and keeps it off', () => {
    let state = play(initialPanelState, init('on'))
    expect(shown(state)).toEqual({ kind: 'on', fromSettings: true })

    state = reducePanel(state, { kind: 'fastModeApplied', on: false }, at)
    expect(shown(state)).toEqual({ kind: 'off' })

    state = play(state, result('off'))
    expect(shown(state)).toEqual({ kind: 'off' })
    expect(rows(state)).toEqual([])
  })

  // The turn that was running when it was asked for ran on what it had: that is no refusal.
  it('waits through the answer about the turn the ask did not ride', () => {
    const state = play(asked, result('off', PENDING))

    expect(shown(state)).toEqual({ kind: 'waiting' })
    expect(rows(state)).toEqual([])
  })

  // The stream repeats the reason at every turn's end; the feed says it once since the ask.
  it('says once that it did not turn on, and again only after it is asked anew', () => {
    let state = play(asked, init('off', 'not_served'), result('off', 'not_served'))
    state = play(state, result('off', 'not_served'))
    expect(rows(state)).toEqual([expect.objectContaining({ reason: 'not_served' })])

    state = reducePanel(state, { kind: 'fastModeApplied', on: false }, at)
    state = reducePanel(state, { kind: 'fastModeApplied', on: true }, at)
    state = play(state, result('off', 'not_served'))
    expect(rows(state)).toHaveLength(2)
  })

  it('says nothing of a conversation that never asked', () => {
    const state = play(initialPanelState, init('off'), result('off'))

    expect(rows(state)).toEqual([])
  })

  // A past conversation's lines are no news about now.
  it('ignores a replay', () => {
    const state = reducePanel(asked, { kind: 'agent', event: init('off', 'not_served'), replay: true }, at)

    expect(state.fastReport).toBeUndefined()
    expect(rows(state)).toEqual([])
  })

  // A subagent's start-up is about its own stream, not this conversation's.
  it('ignores a subagent start-up', () => {
    const state = play(asked, { ...init('off', 'not_served'), task_id: 'agent-1' } as AgentEvent)

    expect(state.fastReport).toBeUndefined()
  })
})
