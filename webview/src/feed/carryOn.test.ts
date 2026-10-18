import { describe, expect, it } from 'vitest'
import { carryOnShown } from './carryOn'

/**
 * The row that says when the IDE carries the conversation on by itself after a limit (see CarryOnRow). It must
 * never promise over work already under way, nor a moment that has gone by: a background agent reporting in
 * starts a turn the feed never sent, and the row stood over it saying "carries on at 03:00" at ten past.
 */
describe('the plan to carry on', () => {
  const plan = { at: 1_000_000, guess: false }

  it('stands while the tab waits and the moment is ahead', () => {
    expect(carryOnShown(plan, false, 999_999)).toBe(true)
  })

  it('stands down while a turn is running, however it began', () => {
    expect(carryOnShown(plan, true, 999_999)).toBe(false)
  })

  it('stands down once its moment has gone by', () => {
    expect(carryOnShown(plan, false, 1_000_000)).toBe(false)
  })

  it('is nothing when nothing is planned', () => {
    expect(carryOnShown(undefined, false, 0)).toBe(false)
  })
})
