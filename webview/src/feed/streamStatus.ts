import type { AgentStatus, AgentTab } from '../components/StreamSwitcher'
import type { CardState, PlanDecision } from '../hooks/useCardState'
import { pendingAgents, stintElapsed } from './build'
import type { PanelState } from './panelState'
import { formatDuration } from './tools'
import type { AskItem, FeedItem, PermItem, PlanItem, TaskItem } from './types'
import type { Dict } from '../i18n/en'

/**
 * Whether the turn stands on this feed item waiting for the person. A permission request, a question with
 * options and a shown plan hold it equally fast, so their rule is one: parting ways, it would lie now
 * through the status line, now through the tab's dot - depending on where which case was forgotten.
 */
export const awaitsYou = (item: FeedItem, cards: CardState): boolean =>
  (item.kind === 'perm' && item.decision === null) ||
  (item.kind === 'ask' && !item.historic && !cards.answeredAsks.includes(item.id)) ||
  (item.kind === 'plan' && !item.historic && cards.planDecisions[item.id] === undefined)

/**
 * The subagent that raised a decision, or nothing when the conversation's own turn did.
 *
 * The CLI names the asker by the agent's own id - `agent_id` of `can_use_tool` is the very string its
 * `task_started` calls `task_id` (checked by a live run on CLI 2.1.293). A card raised by a Task call goes
 * by the call's id, though, and learns the task's name only later (see TaskItem.taskId), so both are looked
 * at. Matched by the card's id alone, the question of every ordinary subagent belonged to no card: its chip
 * never turned to "needs input", and on the agent's own screen, where the person sat watching it work, the
 * question never showed - the agent read as running forever (report from macOS, 0.13.20).
 *
 * A name no card answers to is counted as the main stream's: a decision hidden for good would be worse
 * than one shown without its asker.
 */
export const askerOf = (item: FeedItem, items: FeedItem[]): TaskItem | undefined => {
  if (item.kind !== 'perm' && item.kind !== 'ask') return undefined
  const named = item.taskId
  if (named === undefined) return undefined
  return items.find((one): one is TaskItem => one.kind === 'task' && (one.id === named || one.taskId === named))
}

/** Which stream a decision belongs to: the card of the agent that raised it, or main. */
const ownerStream = (item: FeedItem, items: FeedItem[]): string => askerOf(item, items)?.id ?? 'main'

/** What the chip of an agent says - and the card of a decision it raised, so that the two can be matched by eye. */
export const agentLabel = (task: TaskItem): string =>
  // A workflow's chip counts its fleet instead: "agent:agent" said nothing, and how many agents are
  // running at once is the one thing worth having on a chip that small.
  task.workflow ? `workflow:${task.workflow.total}` : `agent:${task.target}`

/**
 * The one thing the conversation is standing on, or nothing at all.
 *
 * A permission before a plan before a question: a permission holds a call that is happening this second,
 * while a plan and a question hold a turn that is prepared to wait. Within a kind, the most recent - the
 * older ones are answered, replayed, or belong to a conversation that has since restarted.
 *
 * A subagent's decision counts as much as the main stream's: the work does not move until somebody answers
 * it, and the phone has nowhere else to offer it - the desk shows it over the input field on every screen
 * of the conversation (see pendingPermission).
 *
 * One function rather than the same three lines written wherever it is needed. A phone asks it twice -
 * once for the strip that says something is waiting, once for the screen that shows what - and when
 * those two were written separately the strip went by permissions alone: a question then held the
 * conversation with nothing on screen offering to answer it, since a question is not drawn in the feed
 * either (the panel pins it over the input field instead).
 */
export const awaiting = (items: FeedItem[], cards: CardState): PermItem | PlanItem | AskItem | undefined => {
  const pending = items.filter((item) => awaitsYou(item, cards))
  const latest = <T extends FeedItem>(kind: FeedItem['kind']) =>
    [...pending].reverse().find((item): item is T => item.kind === kind)

  return latest<PermItem>('perm') ?? latest<PlanItem>('plan') ?? latest<AskItem>('ask')
}

/**
 * While an unanswered permission request or a question hangs there, the turn is not genuinely thinking -
 * it stands and waits for the person's decision. A "Claude is thinking" at that moment would be untrue.
 * A subagent's decision counts too: the card stands over the input field right under this line on every
 * screen of the conversation (see pendingPermission), and the main turn waits for its agent, which waits
 * for the person. Left to the agent's chip alone, it read as "Claude is thinking - 4m" above a card asking
 * for an answer, and as an agent that never ends to whoever was not looking at that chip.
 *
 * The elapsed time is written right here rather than waiting for the turn's outcome: the "Worked Ns" under a
 * finished answer arrives only with its end, and until then how much had already passed was not visible at
 * all. It is counted from the person's request less pausedMs - the total time of every such wait over this
 * work (see attentionStarted/attentionEnded in feed/build.ts and the effect in App that sends them):
 * otherwise after a decision the idle seconds would be charged to the agent retroactively, as though it had
 * been "thinking" all that time. From the request rather than from the turn because one request is many
 * turns whenever background agents are in it - see PanelState.stintStartedAt. It is updated once a second by
 * the same tick that moves the tool calls' durations (see tickDurations in feed/build.ts).
 *
 * `now` is a parameter because the two clients do not share a clock. The panel counts against its own
 * Date.now() and everything in the state was written by that same clock, so it passes nothing. The
 * phone's state, though, was built out of moments stamped by the machine with the IDE, and answering
 * against the phone's own clock subtracts one machine's time from another's - see mobile/clock.ts.
 */
export const streamStatus = (
  t: Dict,
  panel: PanelState,
  cards: CardState,
  now: number = Date.now(),
): string => {
  /**
   * The request to the model failed and waits for a retry: at that moment the turn is not running at all -
   * no text, no calls, no question - and a "Claude is thinking" with a running counter would be an outright
   * lie. It is precisely because of it that the panel looked hung: the only thing happening was shown
   * nowhere.
   *
   * Before compacting: the request that compacts the context can fail too, and then what has to be told
   * about is the failure rather than the compacting standing still because of it.
   */
  if (panel.retry) {
    // The attempts and the countdown are told about by the card in the feed right above this line (see
    // RetryRow) - here goes only what is not in it: how long all of this has already dragged on. The line's
    // familiar shape is kept - "what is happening - how long it has run" - and exactly what was untrue
    // changes.
    return t.stream.retryWaiting(t.feed.retry.reason[panel.retry.reason], formatDuration(now - panel.retry.startedAt))
  }

  // The compacting is spoken about by its own card in the feed (a CONTEXT with a growing percentage) -
  // there must be no second caption about the same thing right under it.
  if (panel.compacting) return ''

  const awaitingDecision = panel.items.some((item) => awaitsYou(item, cards))
  if (awaitingDecision) return t.stream.waitingForYou

  /**
   * The main stream's own turn may have ended already (the agent started a background subagent and fell
   * silent at that - which is what the Task tool does outside a skill) while the subagent has not. Without
   * this branch the only trace of anything still happening would be a dot on the subagent's chip, which one
   * first has to notice and then work out what it means.
   */
  if (panel.status !== 'running') {
    const pending = pendingAgents(panel.items).length
    if (pending === 0) return ''
    return pending === 1 ? t.stream.waitingForSubagent : t.stream.waitingForSubagents(pending)
  }

  /**
   * What exactly is being done right now has already been named by a card in the feed itself (the tool call,
   * its command, its description). Repeating the same thing here a second time in different words is not an
   * account of what is happening but a duplicate of what is visible a line above anyway. While the turn runs
   * and no decision is awaited from the person, there is exactly one honest caption here - the turn is
   * thinking.
   */
  const label = t.stream.thinking
  if (!panel.turnStartedAt) return label

  // Counted from the person's request rather than from this turn's start (see PanelState.stintStartedAt):
  // a request that started background agents ends in a dozen turns, and a counter restarted by each of
  // them says "12s" half an hour into the work. The pauses come out of it inside stintElapsed - the
  // current one included, because the effect carrying waitStartedAt into pausedMs fires after this
  // render and the number would otherwise jump on the next tick.
  const elapsed = formatDuration(stintElapsed(panel, now))
  return t.stream.withElapsed(label, elapsed)
}

/** A permission or a question nobody has answered yet - the two a chip turns to "needs input" for. */
const undecided = (item: FeedItem, answeredAsks: string[]): boolean =>
  (item.kind === 'perm' && item.decision === null) ||
  (item.kind === 'ask' && !item.historic && !answeredAsks.includes(item.id))

/**
 * The cards of the agents standing on a decision of theirs right now: their chip turns to "needs input",
 * and their screen and their card in the feed say "waiting for you" rather than "working" (an agent on a
 * permission was drawn as working for as long as nobody answered, which read as an agent that never ends).
 */
export const agentsWaiting = (items: FeedItem[], answeredAsks: string[]): ReadonlySet<string> => {
  const waiting = new Set<string>()
  for (const item of items) {
    if (!undecided(item, answeredAsks)) continue
    const asker = askerOf(item, items)
    if (asker) waiting.add(asker.id)
  }
  return waiting
}

const statusOf = (task: TaskItem, waiting: ReadonlySet<string>): AgentStatus => {
  // An agent cut short is not the same as one that ran its course: a killed and a crashed one used to get
  // the same green dot as one that made it to the end.
  if (!task.pending) return task.outcome === 'failed' ? 'failed' : task.outcome === 'stopped' ? 'stopped' : 'done'
  return waiting.has(task.id) ? 'needs-input' : 'running'
}

/**
 * The main chip goes by its own stream's decisions only: the chips answer "whose", and an agent's question
 * already turns that agent's chip.
 */
export const mainStatusOf = (panel: PanelState, answeredAsks: string[]): AgentStatus => {
  const blocked = panel.items.some(
    (item) => undecided(item, answeredAsks) && ownerStream(item, panel.items) === 'main',
  )
  if (blocked) return 'needs-input'
  return panel.status === 'running' ? 'running' : 'idle'
}

/** A batch hidden by clearFinishedAgents disappears from the dropdown - the history itself went nowhere. */
export const buildAgentTabs = (
  panel: PanelState,
  answeredAsks: string[],
  hiddenTaskIds: ReadonlySet<string>,
): AgentTab[] => {
  const waiting = agentsWaiting(panel.items, answeredAsks)
  return panel.items
    .filter((item): item is TaskItem => item.kind === 'task' && !hiddenTaskIds.has(item.id))
    .map((task) => ({
      id: task.id,
      label: agentLabel(task),
      meta: task.meta,
      status: statusOf(task, waiting),
      percent: task.percent,
      duration: task.duration,
      // There is nothing to kill in one that has already finished, and nothing to kill it with until the
      // CLI has named the task (see TaskItem.taskId).
      stopId: task.pending ? task.taskId : undefined,
    }))
}

/**
 * The open stream's own decision first, and failing that the most recent one of any other stream.
 *
 * Any other, because a decision holds the work wherever the person happens to be looking. Shown only on
 * the screen of the stream that raised it, a subagent's question never reached the main screen, where a
 * person spends the turn, and the main stream's never reached an agent's screen, where a person sits
 * watching that agent: either way the work stood still with nothing on screen saying why (report from
 * macOS, 0.13.20). The card names its asker when that is an agent (see askerOf), and the terminal does
 * the same - it has one screen, and a subagent's question takes it like any other.
 */
const firstFor = <T extends FeedItem>(open: T[], items: FeedItem[], stream: string): T | undefined =>
  open.find((item) => ownerStream(item, items) === stream) ?? open[0]

/** The last question nobody has answered yet - see firstFor for whose. */
export const pendingAsk = (items: FeedItem[], answered: string[], stream: string): AskItem | undefined =>
  firstFor(
    [...items]
      .reverse()
      .filter(
        (item): item is AskItem =>
          item.kind === 'ask' &&
          // A question from a past conversation's replay is not shown as a card - see AskItem.historic.
          !item.historic &&
          !answered.includes(item.id),
      ),
    items,
    stream,
  )

/** The last call still waiting for a permission decision - see firstFor for whose. */
export const pendingPermission = (items: FeedItem[], stream: string): PermItem | undefined =>
  firstFor(
    [...items].reverse().filter((item): item is PermItem => item.kind === 'perm' && item.decision === null),
    items,
    stream,
  )

/**
 * A shown plan with no decision on it yet: while it is there, the turn stands on it.
 *
 * Only for a running turn: a plan card stays in the feed forever, including in a conversation raised from
 * the history - and there is nothing left to decide there, the turn ended somewhere in the past. Without
 * this check the very first message in a restored tab would travel not as a prompt but as a remark on an
 * ancient plan.
 */
export const pendingPlan = (
  panel: PanelState,
  decisions: Record<string, PlanDecision>,
): PlanItem | undefined =>
  panel.status === 'running'
    ? [...panel.items].reverse().find((item): item is PlanItem => item.kind === 'plan' && decisions[item.id] === undefined)
    : undefined
