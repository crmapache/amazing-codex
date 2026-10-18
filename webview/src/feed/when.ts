/**
 * When something last happened, in words short enough for a list.
 *
 * Recent things are labelled by time and older ones by date: that is what makes a conversation from
 * this morning easy to pick out from one three weeks old. Shared by the panel's history and the
 * phone's, because two lists of the same conversations that word their dates differently is the sort
 * of small inconsistency that makes an application feel assembled rather than made.
 */
export const describeWhen = (at: number): string => {
  const date = new Date(at)
  const sameDay = new Date().toDateString() === date.toDateString()

  return sameDay
    ? `today ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
    : date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

const DAY_MS = 24 * 60 * 60 * 1000

/**
 * A moment a limit is about - when it resets, when a run waiting it out goes on - as the reader's clock shows it,
 * and with its date once it is a day or more away from [now].
 *
 * The clock alone is enough for a five-hour window: its reset is always within the day. A weekly one is days off,
 * and a business seat's spending cap starts over with its month, and there "at 0:00" names no day at all - read
 * as tonight, it promised the work back weeks too early.
 */
export const clockOrDate = (at: number, now: number = Date.now()): string =>
  Math.abs(at - now) < DAY_MS
    ? new Date(at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
    : new Date(at).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
