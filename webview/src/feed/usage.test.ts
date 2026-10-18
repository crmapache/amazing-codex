import { en } from '../i18n/en'
import { describe, expect, it } from 'vitest'
import {
  contextColor,
  contextGlow,
  emptyUsageBook,
  FIVE_HOUR_MS,
  limitWindowName,
  limitWindowRing,
  mergeUsage,
  mergeUsageBook,
  paceColor,
  RING_LENGTH,
  ringDash,
  spendingWindow,
  timeLeft,
  usableAgainIn,
  usageOf,
  type UsageFacts,
} from './usage'

describe('mergeUsage', () => {
  const known: UsageFacts = {
    session: { percent: 10, resets: '2026-08-26T22:00:00Z' },
    week: { percent: 18, resets: '2026-08-30T10:00:00Z' },
    extra: { active: false, enabled: true, percent: 23 },
    contextWindow: 1_000_000,
    todayTokens: '657.8M',
  }

  it('keeps what a partial message says nothing about', () => {
    expect(mergeUsage(known, { type: 'usage', todayTokens: '700M' })).toEqual({ ...known, todayTokens: '700M' })
  })

  it('does not let a zero context window stick in the state', () => {
    expect(mergeUsage(known, { type: 'usage', contextWindow: 0 }).contextWindow).toBe(1_000_000)
  })

  /**
   * The account has changed, and the previous one's shares are nobody's (see ProjectUsage.forget). The
   * ordinary merging cannot arrive at this by itself: a weekly window the new account has not opened yet
   * is not mentioned in the answer at all, so silence about it would keep the old percentage.
   */
  it('throws the subscription away on a reset and keeps what does not belong to it', () => {
    expect(mergeUsage(known, { type: 'usage', reset: true })).toEqual({
      contextWindow: 1_000_000,
      todayTokens: '657.8M',
    })
  })

  it('fills up again from the answers that follow the reset', () => {
    const empty = mergeUsage(known, { type: 'usage', reset: true })
    const filled = mergeUsage(empty, { type: 'usage', session: { percent: 3, resets: '2026-08-26T23:00:00Z' } })

    expect(filled.session).toEqual({ percent: 3, resets: '2026-08-26T23:00:00Z' })
    expect(filled.week).toBeUndefined()
  })
})

describe('the per-model weeks', () => {
  const fable = { label: 'Fable', percent: 58, resets: '2026-09-21T00:00:00Z' }

  // The plugin sends the whole list every time, so a list is news and replaces, and silence keeps.
  it('takes a list whole and keeps it through a message that says nothing about it', () => {
    const known = mergeUsage({}, { type: 'usage', models: [fable] })

    expect(known.models).toEqual([fable])
    expect(mergeUsage(known, { type: 'usage', todayTokens: '1M' }).models).toEqual([fable])
    expect(mergeUsage(known, { type: 'usage', models: [] }).models).toEqual([])
  })

  it('goes with the account on a reset', () => {
    expect(mergeUsage({ models: [fable] }, { type: 'usage', reset: true }).models).toBeUndefined()
  })

  // Copied field by field - the one place a new figure is easy to lose on its way to the screen.
  it('reaches the account screen through usageOf', () => {
    const book = mergeUsageBook(emptyUsageBook(), { type: 'usage', account: 'work', models: [fable] })

    expect(usageOf(book, 'work').models).toEqual([fable])
    expect(usageOf(book, 'home').models).toBeUndefined()
  })
})

describe('contextColor', () => {
  it('paints by the same thresholds as the context bar in the composer', () => {
    expect(contextColor(0)).toBe('var(--acc-gauge-1)')
    expect(contextColor(49)).toBe('var(--acc-gauge-1)')
    expect(contextColor(50)).toBe('var(--acc-gauge-2)')
    expect(contextColor(69)).toBe('var(--acc-gauge-2)')
    expect(contextColor(70)).toBe('var(--acc-gauge-3)')
    expect(contextColor(84)).toBe('var(--acc-gauge-3)')
    expect(contextColor(85)).toBe('var(--acc-gauge-4)')
    expect(contextColor(100)).toBe('var(--acc-gauge-4)')
  })

  /**
   * The whole of the no-stress mode rests on this: the gauges name a step of a ladder, never a paint.
   * A colour written back in here would go on shouting through the switch, and only on some readings -
   * the ones nobody looks at while the setting is being tried out.
   */
  it('names a step of the ladder rather than a paint of its own', () => {
    for (const percent of [0, 49, 50, 69, 70, 84, 85, 100]) {
      expect(contextColor(percent), `${percent}%`).toMatch(/^var\(--acc-gauge-[1-4]\)$/)
    }
  })
})

describe('contextGlow', () => {
  it('keeps the glow at the same level as the colour - they do not part ways at the boundaries', () => {
    expect(contextGlow(49)).toEqual({ strong: 'var(--acc-gauge-1-80)', soft: 'var(--acc-gauge-1-35)' })
    expect(contextGlow(50)).toEqual({ strong: 'var(--acc-gauge-2-80)', soft: 'var(--acc-gauge-2-35)' })
    expect(contextGlow(70)).toEqual({ strong: 'var(--acc-gauge-3-80)', soft: 'var(--acc-gauge-3-35)' })
    expect(contextGlow(85)).toEqual({ strong: 'var(--acc-gauge-4-80)', soft: 'var(--acc-gauge-4-35)' })
  })
})

describe('timeLeft', () => {
  it('counts a long window in days - "97h 12m" is a figure one has to divide before it means anything', () => {
    const inHours = (hours: number) => new Date(Date.now() + hours * 3_600_000).toISOString()

    expect(timeLeft(inHours(2.5))).toMatch(/^2h 3\dm$/)
    expect(timeLeft(inHours(0.5))).toMatch(/^\d\dm$/)
    expect(timeLeft(inHours(50))).toMatch(/^2d \dh$/)
  })

  it('says nothing rather than something wrong when the reset time is unknown or past', () => {
    expect(timeLeft('')).toBeNull()
    expect(timeLeft('not a date')).toBeNull()
    expect(timeLeft(new Date(Date.now() - 60_000).toISOString())).toBeNull()
  })
})

describe('paceColor', () => {
  const inHours = (hours: number) => new Date(Date.now() + hours * 3_600_000).toISOString()

  it('reads the same percentage differently by how much of the window is left', () => {
    // Half the five-hour window gone: half the limit spent is exactly on plan, and nearly all of it
    // is not - the figure is the same in both cases.
    expect(paceColor(50, inHours(2.5), FIVE_HOUR_MS)).toBe('var(--acc-gauge-1)')
    expect(paceColor(80, inHours(2.5), FIVE_HOUR_MS)).toBe('var(--acc-gauge-3)')
  })

  it('keeps an absolute ceiling: at the limit, time no longer saves anyone', () => {
    // The window is nearly over, so spending nearly all of it is exactly on plan - the pace alone
    // would paint both of these green. The ceiling overrules it: at 90% orange, at 96% red.
    expect(paceColor(91, inHours(0.4), FIVE_HOUR_MS)).toBe('var(--acc-gauge-3)')
    expect(paceColor(97, inHours(0.1), FIVE_HOUR_MS)).toBe('var(--acc-gauge-4)')
  })
})

describe('ringDash', () => {
  it('leaves the whole arc unturned at nothing spent and closes it at the limit', () => {
    expect(ringDash(0)).toBeCloseTo(RING_LENGTH)
    expect(ringDash(100)).toBeCloseTo(0)
    expect(ringDash(50)).toBeCloseTo(RING_LENGTH / 2)
  })

  it('clamps rather than drawing an arc that runs backwards', () => {
    expect(ringDash(-10)).toBeCloseTo(RING_LENGTH)
    expect(ringDash(140)).toBeCloseTo(0)
  })
})

describe('the limit windows', () => {
  it('names them the way the CLI does', () => {
    expect(limitWindowName(en, 'five_hour')).toBe('5-hour')
    expect(limitWindowName(en, 'seven_day')).toBe('weekly')
    expect(limitWindowName(en, 'seven_day_opus')).toBe('weekly Opus')
  })

  // A bucket that appears in a later CLI must not turn into "your seven_day_whatever limit" in the panel.
  it('says nothing about a window it does not know', () => {
    expect(limitWindowName(en, 'seven_day_whatever')).toBe('')
    expect(limitWindowName(en, undefined)).toBe('')
  })

  // The CLI's own name for it is "Fable limit" - the model's week, not the shared one.
  it('names the overage-included window by its model', () => {
    expect(limitWindowName(en, 'seven_day_overage_included')).toBe('weekly Fable')
  })

  // With the model's ring on the screen it is that ring that runs out: burning the shared week beside it
  // would point at a window that is fine.
  it('sends the model week to its own ring when there is one', () => {
    expect(limitWindowRing('seven_day_overage_included', true)).toBe('model')
    expect(limitWindowRing('seven_day', true)).toBe('week')
    expect(limitWindowRing('five_hour', true)).toBe('session')
  })

  it('sends every weekly window to the weekly ring, and everything else to the five-hour one', () => {
    expect(limitWindowRing('seven_day')).toBe('week')
    expect(limitWindowRing('seven_day_opus')).toBe('week')
    // No model ring on the screen: the week is the closest thing there is, as before the ring existed.
    expect(limitWindowRing('seven_day_overage_included')).toBe('week')
    expect(limitWindowRing('five_hour')).toBe('session')
    // The five-hour window runs out several times a day and is nearly always the one meant.
    expect(limitWindowRing(undefined)).toBe('session')
    expect(limitWindowRing('brand_new_bucket')).toBe('session')
  })
})

describe('the spending cap of a Codex business seat', () => {
  it('is a window with its share and its reset when the budget says how much has gone', () => {
    expect(spendingWindow({ active: false, enabled: true, percent: 85, resets: '2026-10-01T00:00:00Z' })).toEqual({
      percent: 85,
      resets: '2026-10-01T00:00:00Z',
    })
    expect(spendingWindow({ active: false, percent: 10 })).toEqual({ percent: 10, resets: '' })
  })

  it('is not drawn while money is burning, when it is off, or when its share is unknown', () => {
    expect(spendingWindow({ active: true, enabled: true, percent: 40 })).toBeNull()
    expect(spendingWindow({ active: false, enabled: false, percent: 40 })).toBeNull()
    expect(spendingWindow({ active: false, enabled: true })).toBeNull()
    expect(spendingWindow(undefined)).toBeNull()
  })
})

/**
 * The accounts screen answering "when can I use account A again" without switching to it - asked for in a
 * report from 0.13.36 (account A at 100%, the work moved to B).
 */
describe('usableAgainIn', () => {
  const now = Date.parse('2026-10-17T12:00:00Z')
  const inHours = (hours: number) => new Date(now + hours * 3_600_000).toISOString()

  it('says nothing about an account with room', () => {
    expect(usableAgainIn({ session: { percent: 40, resets: inHours(2) }, week: { percent: 99, resets: inHours(48) } }, now)).toBeNull()
    expect(usableAgainIn({}, now)).toBeNull()
  })

  it('counts to the reset of the window that is full', () => {
    expect(usableAgainIn({ session: { percent: 100, resets: inHours(2.25) }, week: { percent: 30, resets: inHours(48) } }, now)).toBe('2h 15m')
  })

  // The earlier reset opens nothing while the other window still refuses.
  it('counts to the later reset when both windows are full', () => {
    expect(usableAgainIn({ session: { percent: 100, resets: inHours(2) }, week: { percent: 100, resets: inHours(50) } }, now)).toBe('2d 2h')
  })

  // Extra usage carries the work on, billed on top: nothing is waited for.
  it('says nothing while extra usage carries the account past its limit', () => {
    expect(usableAgainIn({ session: { percent: 100, resets: inHours(2) }, extra: { active: true } }, now)).toBeNull()
  })

  // A model's own week (Fable) refuses that model alone, and a full window without a named reset has no answer.
  it('counts only the windows the whole plan is measured by, and only to a reset it was told', () => {
    expect(
      usableAgainIn({ session: { percent: 10, resets: inHours(1) }, models: [{ label: 'Fable', percent: 100, resets: inHours(30) }] }, now),
    ).toBeNull()
    expect(usableAgainIn({ session: { percent: 100, resets: '' } }, now)).toBeNull()
  })

  it('measures against the clock it is given', () => {
    expect(timeLeft(inHours(1), now)).toBe('1h 0m')
    expect(timeLeft(inHours(-1), now)).toBeNull()
  })
})

/**
 * A Codex business seat has no window of the plan, only the workspace's spending cap, which starts over with its
 * month - and the accounts screen said nothing about a seat at 100% of it, as if it had room.
 */
describe('usableAgainIn on a business seat', () => {
  const now = Date.parse('2026-10-17T12:00:00Z')
  const inDays = (days: number) => new Date(now + days * 86_400_000).toISOString()

  it('counts to the start of the next budget when the cap is spent', () => {
    expect(usableAgainIn({ extra: { active: false, enabled: true, percent: 100, resets: inDays(14.5) } }, now)).toBe('14d 12h')
  })

  // The share is `100 - remainingPercent` in whole percent; Codex's own word on the cap goes over it both ways.
  it('takes Codex’s word on the cap over its share', () => {
    expect(usableAgainIn({ extra: { active: false, enabled: true, percent: 99, resets: inDays(3), reached: true } }, now)).toBe('3d 0h')
    expect(usableAgainIn({ extra: { active: false, enabled: true, percent: 100, resets: inDays(3), reached: false } }, now)).toBeNull()
  })

  it('says nothing about a cap with room, a cap switched off, or a spent cap with no start-over', () => {
    expect(usableAgainIn({ extra: { active: false, enabled: true, percent: 85, resets: inDays(3) } }, now)).toBeNull()
    expect(usableAgainIn({ extra: { active: false, enabled: false, percent: 100, resets: inDays(3) } }, now)).toBeNull()
    expect(usableAgainIn({ extra: { active: false, enabled: true, percent: 100 } }, now)).toBeNull()
  })

  // Beside the plan's windows a cap only bounds the spending past them: the windows decide.
  it('leaves a cap beside the plan’s windows to them', () => {
    const facts: UsageFacts = {
      session: { percent: 30, resets: inDays(0.1) },
      week: { percent: 40, resets: inDays(4) },
      extra: { active: false, enabled: true, percent: 100, resets: inDays(14) },
    }
    expect(usableAgainIn(facts, now)).toBeNull()
  })
})

/**
 * `ordinaryUsageAllowed` - the backend saying the account may work - is the one recovery signal Codex documents,
 * and it beats a share in whole percent.
 */
describe('the backend’s word on the plan’s usage', () => {
  const now = Date.parse('2026-10-17T12:00:00Z')
  const inHours = (hours: number) => new Date(now + hours * 3_600_000).toISOString()
  const spent: UsageFacts = { session: { percent: 100, resets: inHours(2) }, week: { percent: 40, resets: inHours(48) } }

  it('takes the "at the limit" line away when the account may work', () => {
    expect(usableAgainIn({ ...spent, ordinaryUsage: true }, now)).toBeNull()
    expect(usableAgainIn({ ...spent, ordinaryUsage: false }, now)).toBe('2h 0m')
    expect(usableAgainIn(spent, now)).toBe('2h 0m')
  })

  // The plugin says null on every picture when it has no word, or took a "yes" back: absent is another message.
  it('is merged as said: null clears it, absence keeps it', () => {
    const allowed = mergeUsage({}, { type: 'usage', account: 'work', ordinaryUsage: true })
    expect(allowed.ordinaryUsage).toBe(true)
    expect(mergeUsage(allowed, { type: 'usage', account: 'work', todayTokens: '1M' }).ordinaryUsage).toBe(true)
    expect(mergeUsage(allowed, { type: 'usage', account: 'work', ordinaryUsage: null }).ordinaryUsage).toBeUndefined()
    expect(mergeUsage(allowed, { type: 'usage', reset: true }).ordinaryUsage).toBeUndefined()
  })

  // usageOf copies its fields by hand - the very place a new figure is easy to lose on the way to the screen.
  it('reaches the account’s screen', () => {
    const book = mergeUsageBook(emptyUsageBook(), { type: 'usage', account: 'work', ordinaryUsage: false })
    expect(usageOf(book, 'work').ordinaryUsage).toBe(false)
  })
})

describe('the spending cap in the feed', () => {
  it('is named as such when Codex says the cap stopped the work', () => {
    expect(limitWindowName(en, 'workspace_member_usage_limit_reached')).toBe('spending')
    expect(limitWindowName(en, 'workspace_owner_usage_limit_reached')).toBe('spending')
  })

  // Out of credits is over when somebody adds credits - "the credits limit, waiting for it to reset" would lie.
  it('leaves the workspace out of credits unnamed', () => {
    expect(limitWindowName(en, 'workspace_member_credits_depleted')).toBe('')
  })
})
