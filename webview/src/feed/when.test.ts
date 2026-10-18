import { describe, expect, it } from 'vitest'
import { clockOrDate } from './when'

/**
 * A limit's moment: the clock alone within a day, its date beside it further off - a business seat's spending cap
 * starts over with its month, and "at 0:00" named no day.
 */
describe('clockOrDate', () => {
  const now = new Date(2026, 9, 17, 12, 0).getTime()
  const time = (at: number) => new Date(at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })

  it('is the clock alone within a day', () => {
    const at = now + 5 * 3_600_000
    expect(clockOrDate(at, now)).toBe(time(at))
  })

  it('carries the date a day or more away, either way', () => {
    const ahead = new Date(2026, 10, 1, 0, 0).getTime()
    const behind = now - 3 * 86_400_000

    for (const at of [ahead, behind]) {
      const said = clockOrDate(at, now)
      expect(said).not.toBe(time(at))
      expect(said).toContain(new Date(at).toLocaleDateString(undefined, { day: 'numeric' }))
    }
  })
})
