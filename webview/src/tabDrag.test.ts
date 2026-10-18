import { describe, expect, it } from 'vitest'
import { beforeOf, placeOn, rowFor, shotOf, SWAP_GAP_PX, type TabRect } from './tabDrag'

/** A tab 100px wide on row `line` (34px each), `column` places from the left, in a group of its own. */
const at = (key: string, line: number, column: number, unit = key): TabRect => ({
  key,
  unit,
  left: column * 100,
  right: column * 100 + 100,
  top: line * 34,
})

/** The same, standing outside the gesture. */
const outside = (key: string, line: number, column: number): TabRect => ({ ...at(key, line, column), unit: undefined })

/**
 * A strip wrapped onto three rows:
 *
 *   a b c d
 *   e f g
 *   h i
 */
const strip = shotOf([
  at('a', 0, 0),
  at('b', 0, 1),
  at('c', 0, 2),
  at('d', 0, 3),
  at('e', 1, 0),
  at('f', 1, 1),
  at('g', 1, 2),
  at('h', 2, 0),
  at('i', 2, 1),
])

/** Where `key` is asking to go with the hand over row `line`, moved `dx` along it. */
const place = (key: string, line: number, dx: number, shot = strip) => {
  const from = shot.units.indexOf(key)
  const tab = shot.tabs.find((each) => each.key === key)!
  const carried = shot.boxes.find((box) => box.index === from && box.line === tab.line)!
  const index = placeOn(shot.boxes, from, carried, line, dx)
  return { index, before: index === from ? undefined : beforeOf(shot.units, from, index) }
}

describe('shotOf', () => {
  it('numbers the rows from the top and the units in the order they are drawn', () => {
    expect(strip.lines).toEqual([0, 34, 68])
    expect(strip.units).toEqual(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'])
    expect(strip.tabs.find((tab) => tab.key === 'f')).toMatchObject({ index: 5, line: 1 })
  })

  it('gives a group wrapped onto two rows a box on each, rather than one spanning the air between', () => {
    const shot = shotOf([
      at('a', 0, 0),
      at('g1', 0, 1, 'g'),
      at('g2', 0, 2, 'g'),
      at('g3', 1, 0, 'g'),
      at('b', 1, 1),
    ])
    expect(shot.units).toEqual(['a', 'g', 'b'])
    expect(shot.boxes).toEqual([
      { index: 0, line: 0, left: 0, right: 100 },
      { index: 1, line: 0, left: 100, right: 300 },
      { index: 1, line: 1, left: 0, right: 100 },
      { index: 2, line: 1, left: 100, right: 200 },
    ])
  })

  it('leaves the tabs outside the gesture out of its rows and units', () => {
    // A fork carried inside its group: the other groups stand where they are and are nobody's unit.
    const shot = shotOf([outside('a', 0, 0), at('m', 1, 0), at('f1', 1, 1), at('f2', 1, 2)])
    expect(shot.units).toEqual(['m', 'f1', 'f2'])
    expect(shot.lines).toEqual([34])
    expect(shot.tabs[0]).toMatchObject({ index: -1, line: -1 })
  })
})

describe('placeOn along its own row', () => {
  it('takes a neighbour place once the leading edge has passed its middle', () => {
    expect(place('b', 0, 49)).toEqual({ index: 1, before: undefined })
    expect(place('b', 0, 51)).toEqual({ index: 2, before: 'd' })
    expect(place('c', 0, -51)).toEqual({ index: 1, before: 'b' })
  })

  it('does not step past the end of its row into the next one', () => {
    // A row's neighbours are the only ones a sideways hand passes: e and f are on the row below.
    expect(place('c', 0, 900)).toEqual({ index: 3, before: 'e' })
  })
})

describe('placeOn across the rows', () => {
  it('goes up into the middle of a row above', () => {
    // f carried up with its left edge between b's middle and c's.
    expect(place('f', 0, 60)).toEqual({ index: 2, before: 'c' })
    expect(place('f', 0, -60)).toEqual({ index: 0, before: 'a' })
  })

  it('goes down into the middle of a row below', () => {
    // b carried down with its right edge between h's middle and i's, then past i's too.
    expect(place('b', 2, -100)).toEqual({ index: 7, before: 'i' })
    expect(place('b', 2, -40)).toEqual({ index: 8, before: null })
  })

  it('stands at the near end of a row having passed nothing there', () => {
    // Past every middle of the row above: after its last tab, which is the start of the row below.
    expect(place('h', 0, 400)).toEqual({ index: 4, before: 'e' })
    // Before every middle of the row below: before its first tab.
    expect(place('c', 1, -400)).toEqual({ index: 3, before: 'e' })
    expect(place('a', 2, -100)).toEqual({ index: 6, before: 'h' })
  })

  it('stays put when the end of one row is the start of the next', () => {
    // e is the first of its row, and the end of the row above is exactly where it already stands.
    expect(place('e', 0, 400)).toEqual({ index: 4, before: undefined })
    // d is the last of its row, and the start of the row below is exactly where it already stands.
    expect(place('d', 1, -400)).toEqual({ index: 3, before: undefined })
  })

  it('carries a group wrapped onto two rows by the part under the hand', () => {
    const shot = shotOf([
      at('a', 0, 0),
      at('b', 0, 1),
      at('g1', 0, 2, 'g'),
      at('g2', 0, 3, 'g'),
      at('g3', 1, 0, 'g'),
      at('c', 1, 1),
      at('d', 1, 2),
    ])
    const from = shot.units.indexOf('g')
    const tail = shot.boxes.find((box) => box.index === from && box.line === 1)!

    // Taken by its tail on the second row and moved right past c's middle: after c.
    expect(placeOn(shot.boxes, from, tail, 1, 60)).toBe(3)
    expect(beforeOf(shot.units, from, 3)).toBe('d')
    // Taken by the same tail and lifted onto the first row, left of a's middle: first in the strip.
    expect(placeOn(shot.boxes, from, tail, 0, -60)).toBe(0)
  })
})

describe('rowFor', () => {
  const lines = [0, 34, 68]

  it('keeps the row the hand is on until another is nearer by the margin', () => {
    expect(rowFor(lines, 17 + SWAP_GAP_PX / 2 - 1, 0)).toBe(0)
    expect(rowFor(lines, 17 + SWAP_GAP_PX / 2 + 1, 0)).toBe(1)
    expect(rowFor(lines, 17 - SWAP_GAP_PX / 2 + 1, 1)).toBe(1)
    expect(rowFor(lines, 17 - SWAP_GAP_PX / 2 - 1, 1)).toBe(0)
  })

  it('goes straight to a far row when the hand jumps there', () => {
    expect(rowFor(lines, 70, 0)).toBe(2)
  })

  it('has nowhere to go on a strip of one row', () => {
    expect(rowFor([0], 40, 0)).toBe(0)
  })
})
