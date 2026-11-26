import { describe, expect, it } from 'vitest'
import { controlPastMenu } from './Menu'

/**
 * Over what the scrim of an open menu steps aside - see controlPastMenu. The case it came from: with the
 * menu behind a card's dots open, the jump to the latest message took two clicks, the first one spent on
 * closing the menu, and did not light up under the pointer.
 */

/** A control marked to be reached past a menu: it is its own nearest marked ancestor. */
const marked = (): Element => {
  const self = { closest: (): unknown => self }
  return self as unknown as Element
}

/** Anything else on the page - inside a marked control, or inside nothing marked at all. */
const inside = (control: Element | null): Element => ({ closest: () => control }) as unknown as Element

const scrim = inside(null)
const page = inside(null)

describe('the pointer over an open menu scrim', () => {
  it('finds a marked control right under it - its drawing included', () => {
    const jump = marked()
    expect(controlPastMenu([scrim, jump, page], scrim)).toBe(jump)
    expect(controlPastMenu([scrim, inside(jump), jump, page], scrim)).toBe(jump)
  })

  it('keeps finding it while the scrim stands aside and is no longer under the pointer at all', () => {
    const jump = marked()
    expect(controlPastMenu([inside(jump), jump, page], scrim)).toBe(jump)
  })

  it('finds nothing over anything not marked - a click there only closes the menu', () => {
    expect(controlPastMenu([scrim, inside(null), page], scrim)).toBeNull()
  })

  it('does not reach a marked control that something else covers at that point', () => {
    expect(controlPastMenu([scrim, inside(null), marked(), page], scrim)).toBeNull()
  })
})
