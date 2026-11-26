import { describe, expect, it } from 'vitest'
import type { CardState } from '../hooks/useCardState'
import { en } from '../i18n/en'
import {
  agentsWaiting,
  askerOf,
  awaiting,
  buildAgentTabs,
  mainStatusOf,
  pendingAsk,
  pendingPermission,
  streamStatus,
} from './streamStatus'
import type { AskItem, FeedItem, PermItem, PlanItem, TaskItem } from './types'
import { initialPanelState } from './build'
import type { PanelState } from './panelState'

const cards = (answeredAsks: string[] = [], planDecisions: CardState['planDecisions'] = {}): CardState => ({
  isOpen: () => false,
  toggle: () => {},
  planDecisions,
  decidePlan: () => {},
  answeredAsks,
  answerAsk: () => {},
  reset: () => {},
})

const perm = (id: string, extra: Partial<PermItem> = {}): PermItem => ({
  id,
  kind: 'perm',
  target: 'src/app.ts',
  mode: 'manual',
  command: '',
  decision: null,
  rememberable: true,
  ...extra,
})

const plan = (id: string, extra: Partial<PlanItem> = {}): PlanItem => ({
  id,
  kind: 'plan',
  steps: 0,
  duration: '',
  paragraphs: [],
  ...extra,
})

const ask = (id: string, extra: Partial<AskItem> = {}): AskItem => ({
  id,
  kind: 'ask',
  questions: [{ id: 'q1', title: 'Which way?', hint: '', multiSelect: false, options: [] }],
  ...extra,
})

const text: FeedItem = { id: 't1', kind: 'text', source: 'Looking at the relay.', paragraphs: [] } as FeedItem

/**
 * Which of the three things holds a turn, and which of them a screen must offer to answer.
 *
 * Written down because two screens on a phone ask this question - the strip above the conversation and
 * the decision screen behind it - and when each answered it for itself the strip went by permissions
 * alone. A question then stopped the conversation with nothing anywhere offering to answer it: a
 * question is not drawn in the feed either, so from a phone it was invisible and the work simply stood.
 */
describe('what the turn is standing on', () => {
  it('is nothing at all in an ordinary conversation', () => {
    expect(awaiting([text], cards())).toBeUndefined()
  })

  it('is a question with options, exactly as much as a permission is', () => {
    expect(awaiting([text, ask('a1')], cards())?.id).toBe('a1')
    expect(awaiting([text, perm('p1')], cards())?.id).toBe('p1')
  })

  it('is a plan that has been shown and not answered', () => {
    expect(awaiting([plan('pl1'), text], cards())?.id).toBe('pl1')
  })

  it('is not a question already answered, nor a plan already decided', () => {
    expect(awaiting([ask('a1')], cards(['a1']))).toBeUndefined()
    expect(awaiting([plan('pl1')], cards([], { pl1: 'approve' }))).toBeUndefined()
  })

  /**
   * The agent took the question back itself - Stop pressed over a waiting card cancels it along with the
   * turn (see PermissionChannel.Incoming.Withdrawn on the IDE's side). Nobody decided anything, and the
   * conversation must stop saying it is waiting for a person all the same: otherwise the strip, the tab's
   * dot and the phone's list all go on promising a decision that no longer exists.
   */
  it('is not a card the agent has taken back', () => {
    expect(awaiting([perm('p1', { decision: 'withdrawn' })], cards())).toBeUndefined()
    expect(awaiting([plan('pl1')], cards([], { pl1: 'withdrawn' }))).toBeUndefined()
    expect(awaiting([ask('a1')], cards(['a1']))).toBeUndefined()
  })

  /** A conversation opened from the history: the turn that asked ended some time in the past. */
  it('is not a question or a plan replayed out of a past conversation', () => {
    expect(awaiting([ask('a1', { historic: true })], cards())).toBeUndefined()
    expect(awaiting([plan('pl1', { historic: true })], cards())).toBeUndefined()
  })

  it('is not a permission already answered', () => {
    expect(awaiting([perm('p1', { decision: 'once' })], cards())).toBeUndefined()
  })

  /**
   * A permission holds a call happening this second, while a plan and a question hold a turn prepared to
   * wait - so it is offered first, and the two screens agree about which one that is.
   */
  it('is the permission when a plan and a question are waiting beside it', () => {
    expect(awaiting([plan('pl1'), ask('a1'), perm('p1')], cards())?.id).toBe('p1')
  })

  it('is the plan rather than the question when there is no permission', () => {
    expect(awaiting([ask('a1'), plan('pl1')], cards())?.id).toBe('pl1')
  })

  it('is the most recent of its kind', () => {
    expect(awaiting([ask('a1'), ask('a2')], cards())?.id).toBe('a2')
  })

  /**
   * The work does not move until somebody answers it, wherever it came from - and the phone has no other
   * screen to offer a subagent's permission on.
   */
  it('is a subagent permission as much as the conversation own', () => {
    expect(awaiting([task(), perm('p1', { taskId: 'task-1' })], cards())?.id).toBe('p1')
  })
})

const task = (over: Partial<TaskItem> = {}): TaskItem => ({
  id: 'task-1',
  kind: 'task',
  target: 'Explore',
  meta: 'Find the callers',
  duration: '',
  percent: 0,
  log: [],
  pending: true,
  ...over,
})

const tabsOf = (item: TaskItem) => buildAgentTabs({ ...initialPanelState, items: [item] }, [], new Set())

describe('buildAgentTabs', () => {
  it('names a subagent by the kind of agent it is', () => {
    expect(tabsOf(task())[0]?.label).toBe('agent:Explore')
  })

  /**
   * A workflow has no subagent_type at all, so the chip used to read "agent:agent" over a fleet of nine -
   * both halves of it saying nothing. The count is what a chip that small can usefully carry.
   */
  it('counts the fleet on a workflow chip instead', () => {
    const workflow = { phases: [], log: [], running: 2, done: 7, failed: 0, total: 9 }
    expect(tabsOf(task({ target: 'workflow', workflow }))[0]?.label).toBe('workflow:9')
  })
})

/**
 * A subagent's permission, in the shape the CLI sends it (recorded on 2.1.293): the card is raised by the
 * Task call and goes by the call's id, `task_started` gives it the agent's own name later, and
 * `can_use_tool` names the asker by that name - not by the call.
 */
describe('a decision a subagent raised', () => {
  const agentCard = task({ id: 'toolu_01Agent', taskId: 'a5a1f47f4475fd51c', target: 'general-purpose' })
  const agentPerm = perm('p-agent', { taskId: 'a5a1f47f4475fd51c', command: 'npm test' })
  const mainPerm = perm('p-main', { command: 'rm -rf build' })
  const panelOf = (items: FeedItem[], status: PanelState['status'] = 'running'): PanelState => ({
    ...initialPanelState,
    status,
    items,
  })

  it('belongs to the card by the agent name the CLI gives it, as well as by the card id', () => {
    expect(askerOf(agentPerm, [agentCard, agentPerm])?.id).toBe('toolu_01Agent')
    // An agent a skill raised has no Task call: its card goes by the task's name from the start.
    const skillCard = task({ id: 'a77', taskId: 'a77' })
    expect(askerOf(perm('p2', { taskId: 'a77' }), [skillCard])?.id).toBe('a77')
  })

  it('has no asker when it is the conversation own, or names a card nobody has', () => {
    expect(askerOf(mainPerm, [agentCard, mainPerm])).toBeUndefined()
    expect(askerOf(perm('p3', { taskId: 'gone' }), [agentCard])).toBeUndefined()
  })

  /** Matched by the card's id alone, the chip of every ordinary subagent stayed "running" over its question. */
  it('turns the agent chip to needs-input, and leaves the main chip alone', () => {
    const panel = panelOf([agentCard, agentPerm])
    expect(buildAgentTabs(panel, [], new Set())[0]?.status).toBe('needs-input')
    expect(mainStatusOf(panel, [])).toBe('running')
    expect(mainStatusOf(panelOf([agentCard, mainPerm]), [])).toBe('needs-input')
  })

  /**
   * Shown only on the screen of the stream that raised it, the question of an agent never reached main,
   * and the main stream's never reached a person sitting on the agent's screen: the work stood with
   * nothing on screen saying why (report from macOS, 0.13.20).
   */
  it('is offered over the field on main and on the agent screen alike', () => {
    const items = [agentCard, agentPerm]
    expect(pendingPermission(items, 'main')?.id).toBe('p-agent')
    expect(pendingPermission(items, 'toolu_01Agent')?.id).toBe('p-agent')
    expect(pendingPermission([agentCard, mainPerm], 'toolu_01Agent')?.id).toBe('p-main')
  })

  it('gives way on each screen to that screen own stream', () => {
    const items = [agentCard, mainPerm, agentPerm]
    expect(pendingPermission(items, 'main')?.id).toBe('p-main')
    expect(pendingPermission(items, 'toolu_01Agent')?.id).toBe('p-agent')
    // Neither stream's own: the most recent of all.
    expect(pendingPermission(items, 'another')?.id).toBe('p-agent')
  })

  it('does the same for a question with options', () => {
    const items = [agentCard, ask('a-main'), ask('a-agent', { taskId: 'a5a1f47f4475fd51c' })]
    expect(pendingAsk(items, [], 'main')?.id).toBe('a-main')
    expect(pendingAsk(items, [], 'toolu_01Agent')?.id).toBe('a-agent')
    expect(pendingAsk(items, ['a-main'], 'main')?.id).toBe('a-agent')
  })

  /** Its screen and its card in the feed said "Working" for as long as nobody answered. */
  it('tells which agents stand on a decision, and lets them go once it is answered', () => {
    expect([...agentsWaiting([agentCard, agentPerm], [])]).toEqual(['toolu_01Agent'])
    expect([...agentsWaiting([agentCard, { ...agentPerm, decision: 'once' }], [])]).toEqual([])
    expect([...agentsWaiting([agentCard, mainPerm], [])]).toEqual([])
  })

  /** "Claude is thinking - 4m" stood right above the card asking for an answer. */
  it('makes the status line say the work waits for the person', () => {
    expect(streamStatus(en, panelOf([agentCard, agentPerm]), cards())).toBe(en.stream.waitingForYou)
    // A background agent asking after the main turn has ended: it waits for the person, not for itself.
    expect(streamStatus(en, panelOf([agentCard, agentPerm], 'idle'), cards())).toBe(en.stream.waitingForYou)
    expect(streamStatus(en, panelOf([agentCard], 'idle'), cards())).toBe(en.stream.waitingForSubagent)
  })
})
