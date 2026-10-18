import { limitWindowName } from '../feed/usage'
import { clockOrDate } from '../feed/when'
import type { Dict } from '../i18n/en'
import type { ScenarioRunLimit, ScenarioRunMove } from '../protocol'

/**
 * What the panel itself did to keep a run going, in words: it moved the run to another account, or put it to
 * wait out a limit no account had room past.
 *
 * The IDE sends names and figures rather than a sentence (see ScenarioRunMove), so the sentence is chosen here,
 * in the reader's language - once for both screens that draw a run, the desk and the phone. Written in each,
 * the two would name one wait in two different ways on the first change.
 */

/** An account as a run's timeline names it: the name the person gave it or its address, or the CLI's own sign-in. */
const accountName = (t: Dict, name: string): string => name || t.accounts.defaultName

/**
 * A moment as the reader's own clock shows it - what one compares against one's own evening - and with its date
 * when it is a day or more off: a run waiting out a weekly limit or a business seat's monthly spending cap goes
 * on days or weeks later, and the clock alone named no day (see clockOrDate).
 */
export const clockOf = (at: number): string => clockOrDate(at)

/** The sentence for a note of the panel's own (`who: 'panel'`). */
export const moveText = (t: Dict, move: ScenarioRunMove): string => {
  const to = accountName(t, move.to)
  if (move.reason === 'choice') return t.scenarios.run.movedByChoice(to)

  const from = accountName(t, move.from)
  // No account to go to: the run waits, and says until when when it knows.
  const clock = move.until > 0 ? clockOf(move.until) : ''

  if (move.reason === 'unfit') {
    return move.waits ? t.scenarios.run.unfitWait(from, clock) : t.scenarios.run.unfitMoved(from, to)
  }

  const window = limitWindowName(t, move.window)
  return move.waits ? t.scenarios.run.limitWait(from, window, clock) : t.scenarios.run.limitMoved(from, window, to)
}

/** The line on a run's card and screen while it waits out a limit (see ScenarioRun.limit). */
export const limitText = (t: Dict, limit: ScenarioRunLimit): string =>
  limit.reason === 'unfit'
    ? t.scenarios.run.unfitResting(accountName(t, limit.account), clockOf(limit.until))
    : t.scenarios.run.limitResting(accountName(t, limit.account), limitWindowName(t, limit.window), clockOf(limit.until))
