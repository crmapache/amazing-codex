/**
 * The arithmetic of a tab dragged along the strip - along its rows and across them.
 *
 * The strip wraps onto as many rows as its tabs need, and the order is one line all the same: the rows
 * are only how that line is folded on screen. So the answer is one number - the place in the row the
 * hand is asking for - and the end of one row and the start of the next are the same place.
 *
 * Everything here works off a snapshot taken when the gesture began (see Header) and never off the
 * picture being moved: one and the same hand gives one and the same answer, however many times it is
 * asked. Answering from the live picture made the neighbours dart about - one stepped aside, the
 * geometry changed, the condition fired again.
 */

/** A spot in the strip's own layout pixels. */
export interface Point {
  x: number
  y: number
}

/** One tab as the strip measured it: where it stands, and which unit of the gesture it belongs to. */
export interface TabRect {
  /** The tab's own name in the strip: its conversation, or the panel tab it is. */
  key: string
  /**
   * What the gesture moves it with: its group, or the tab itself for a fork carried inside its group -
   * and nothing for a tab outside the gesture altogether (see unitIdOf in Header).
   */
  unit: string | undefined
  left: number
  right: number
  top: number
}

/**
 * A run of one unit's tabs on one row.
 *
 * A group with many forks can wrap, and then it has a run on every row it touches: one box spanning both
 * rows would have its middle somewhere in the air between them, and the neighbours would step aside for
 * a hand that is nowhere near.
 */
export interface Box {
  /** The unit's place in the row. */
  index: number
  /** The row, counted from the top. */
  line: number
  left: number
  right: number
}

export interface Shot {
  /** The units in the order the strip draws them: whole groups, or one group's tabs. */
  units: string[]
  /** Every tab of the strip, with its unit's place (-1 outside the gesture) and its row (-1 likewise). */
  tabs: (TabRect & { index: number; line: number })[]
  /** The tops of the rows the gesture's tabs stand on, from the top down. */
  lines: number[]
  boxes: Box[]
}

/**
 * The margin a change of place is checked against: a hand on a boundary trembles, and without it the
 * neighbours would tremble along with it.
 */
export const SWAP_GAP_PX = 8

/** The snapshot the whole gesture is answered from - see Shot. */
export const shotOf = (rects: TabRect[]): Shot => {
  const units: string[] = []
  for (const rect of rects) {
    // A unit's tabs always stand together (see groupOrder in tabs.ts), so a glance at the last one does.
    if (rect.unit !== undefined && units.at(-1) !== rect.unit) units.push(rect.unit)
  }

  const lines = [...new Set(rects.filter((rect) => rect.unit !== undefined).map((rect) => rect.top))].sort(
    (one, two) => one - two,
  )

  const tabs = rects.map((rect) => ({
    ...rect,
    index: rect.unit === undefined ? -1 : units.indexOf(rect.unit),
    line: rect.unit === undefined ? -1 : lines.indexOf(rect.top),
  }))

  const boxes: Box[] = []
  for (const tab of tabs) {
    if (tab.index < 0) continue

    const last = boxes.at(-1)
    if (last && last.index === tab.index && last.line === tab.line) {
      last.left = Math.min(last.left, tab.left)
      last.right = Math.max(last.right, tab.right)
      continue
    }

    boxes.push({ index: tab.index, line: tab.line, left: tab.left, right: tab.right })
  }

  return { units, tabs, lines, boxes }
}

/**
 * The row a carried tab whose top stands at `top` is over.
 *
 * The nearest one, and the hand keeps the row it is on until another is nearer by the margin: dragged
 * along a row with the hand a little low, the tab would otherwise flick into the row under it and back.
 */
export const rowFor = (lines: number[], top: number, current: number): number => {
  let nearest = current
  for (const [line, lineTop] of lines.entries()) {
    if (Math.abs(lineTop - top) < Math.abs((lines[nearest] ?? Infinity) - top)) nearest = line
  }

  if (nearest === current) return current
  const held = lines[current]
  if (held === undefined) return nearest
  return Math.abs(lines[nearest]! - top) + SWAP_GAP_PX <= Math.abs(held - top) ? nearest : current
}

/**
 * Where the carried unit is asking to go: its place in the row, with the hand over row `line` and moved
 * `dx` from where it pressed.
 *
 * `carried` is the run of the unit on the row it was taken by. Along that row a neighbour gives way when
 * the carried run has covered more than half of it - its leading edge has passed the neighbour's middle.
 * On another row it is the same rule over that row's units: whatever the run's leading edge has passed
 * steps aside. Having passed nothing there, the run stands at the row's near end - after the last unit of
 * a row above, before the first of a row below - and that is the very place the hand has reached: the
 * strip's order goes on from the end of one row into the start of the next.
 */
export const placeOn = (boxes: Box[], from: number, carried: Box, line: number, dx: number): number => {
  const left = carried.left + dx
  const right = carried.right + dx
  const others = boxes.filter((box) => box.line === line && box.index !== from)

  let place = from
  for (const box of others) {
    const middle = (box.left + box.right) / 2
    if (box.index < from && left < middle) place = Math.min(place, box.index)
    if (box.index > from && right > middle) place = Math.max(place, box.index)
  }

  if (place !== from || line === carried.line || others.length === 0) return place

  // Everything on a row above the carried one comes before it in the order, everything below after it.
  const indices = others.map((box) => box.index)
  return line < carried.line ? Math.max(...indices) + 1 : Math.min(...indices) - 1
}

/**
 * The unit the dropped one comes to stand before, or null for the end: having travelled forward, it
 * stands before the unit that came after the last one to step aside.
 */
export const beforeOf = (units: string[], from: number, place: number): string | null =>
  place > from ? (units[place + 1] ?? null) : (units[place] ?? null)
