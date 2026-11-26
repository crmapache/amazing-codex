/**
 * How the ordinary layout gives way when the panel is dragged narrow - the arithmetic of it, with the
 * measuring left to the hooks (see useSelectorsFit and useToolsFit).
 *
 * Two rows give way, each in steps, and each step is spent only once the one before it is used up:
 *
 * - The status row under the field. First its far end (the usage, the bubble, the heart) steps down onto a
 *   line of its own and the three selectors take the whole first line, sharing it evenly - that part is
 *   plain CSS (see .status). Then the captions go (MODEL, EFFORT, MODE) and the values stay. Only past that
 *   do the values themselves start losing letters to an ellipsis. The far end, on its own line, has one step
 *   of its own: once even that line cannot hold the usage and the pair, the bubble and the heart go (see
 *   endPairFits).
 * - The buttons inside the field. The little squares leave one by one, least needed first - the slash, the
 *   search, the sparkle, the microphone - and only once all four are gone do Queue and Send start to narrow,
 *   down to a single letter each.
 *
 * Kept apart from the components because it breaks quietly: a row that sheds a button one pixel too early
 * looks deliberate, one that sheds it a pixel too late pushes Send past the panel's edge, and one that
 * answers differently to its own last answer flickers under the mouse while the panel is being dragged.
 */

/** Measurement is in fractions of a pixel, and the layout rounds its own boxes: this much is "exact". */
const TOLERANCE = 0.5

/**
 * How the three selectors in the status row stand.
 *
 * `full` - with their captions; `terse` - the captions gone and the values kept; `squeezed` - not even the
 * values fit whole, and each gives way to an ellipsis.
 */
export type SelectorsFit = 'full' | 'terse' | 'squeezed'

/**
 * Which of the three the row can afford.
 *
 * The widths it is compared with are the row's natural ones, measured off a ruler that is never itself
 * shortened (see Selectors' ruler in StatusBar.tsx) - measured off the row itself, dropping the captions
 * would widen the room it had just made, and the next measurement would bring them back.
 */
export const selectorsFit = (room: number, full: number, terse: number): SelectorsFit => {
  if (room + TOLERANCE >= full) return 'full'
  if (room + TOLERANCE >= terse) return 'terse'
  return 'squeezed'
}

/**
 * Whether the bubble and the heart keep their place at the far end of the status row.
 *
 * By the time this matters the far end already stands on a line of its own (it steps down before anything
 * else gives way - see .status), so `room` is that whole line, and the usage (`meters`, zero when every
 * ring and the counter are switched off) and the pair (`pair`, at its natural width) are what has to share
 * it, `gap` apart. When they cannot, the pair goes rather than the usage: what went past the edge was always
 * the pair - the heart cut in half by the panel's border - and the rings are the one place the limits are
 * read, while the feedback screen has its own row in the menu.
 *
 * Coming back asks for a pixel to spare, like a square in the field's row (see toolsDropped): the measurement
 * is rounded, and a pair back in a line it only just fits would be the next measurement's reason to go.
 */
export const endPairFits = (room: number, meters: number, pair: number, gap: number, shown: boolean): boolean => {
  const need = (meters > 0 ? meters + gap : 0) + pair

  return need <= room + (shown ? TOLERANCE : -1)
}

/** The square buttons at the leading edge of the field's bottom row (see Composer.writingTools). */
export type WritingTool = 'voice' | 'improve' | 'attach' | 'slash' | 'search' | 'scenarios'

/** The squares in their groups, in the order they stand. The gap between groups is wider than inside one. */
export const TOOL_GROUPS: readonly (readonly WritingTool[])[] = [
  ['voice', 'improve'],
  ['attach', 'slash'],
  ['search', 'scenarios'],
]

/**
 * The squares that leave when the row runs out of room, the first to go first.
 *
 * Cheapest loss first: the slash only types a "/", which the keyboard does just as well, and the search
 * has Cmd/Ctrl+F. The paperclip and the scenarios are not on the list and stay whatever happens - past
 * the fourth square it is Queue and Send that give way (see .send).
 */
const DROP_ORDER: readonly WritingTool[] = ['slash', 'search', 'improve', 'voice']

/**
 * A step the row gives way by: a square leaving it, the file name on the editor's chip going and leaving
 * the drawing alone, or - with sending the editor switched off - that chip going whole (see EditorChip).
 */
export type FitStep = WritingTool | 'editorName' | 'editorChip'

/**
 * How the editor's chip stands in the row, for what it can give up: `named` gives up its name and keeps the
 * drawing, `muted` - the drawing alone, sending the editor being switched off - gives up itself.
 */
export type EditorFit = 'named' | 'muted' | null

/**
 * The widest the name on the editor's chip is drawn, and the gap before it - the same numbers as
 * .editorChipText's max-width and margin in composer.module.css. Needed here because a name that has
 * stepped out of the row has no width left to measure, and coming back has to know what it will cost.
 */
export const EDITOR_NAME_MAX = 200
export const EDITOR_NAME_GAP = 5

/**
 * The muted chip, the gap before it included: the drawing and the padding either side of it, and the margin
 * - .editorChip's numbers in composer.module.css. Fixed rather than measured, since a chip that has left the
 * row is not there to be measured, and it is the same square whatever file is open.
 */
export const EDITOR_CHIP_MUTED = 32

/** What the row is measured to be right now (see useToolsFit). */
export interface ToolsMeasure {
  /**
   * How much wider than the row its contents want to be at their natural widths: positive when it is
   * short of room by that much, negative when that much is to spare. Natural means Queue and Send
   * spelled out in full, whatever they are shrunk to at this moment.
   */
  short: number
  /** A square button's width. */
  square: number
  /** The gap between two squares of one group, and between two groups. */
  inner: number
  outer: number
  /**
   * The file name on the editor's chip, gap included, at its natural width - whether or not it is drawn
   * right now. Zero or absent when there is no chip.
   */
  name?: number
}

/** The squares' block, as wide as it stands with the given ones shown. */
export const toolsWidth = (
  shown: (tool: WritingTool) => boolean,
  measure: Pick<ToolsMeasure, 'square' | 'inner' | 'outer'>,
): number => {
  let width = 0
  let groups = 0

  for (const group of TOOL_GROUPS) {
    const count = group.filter(shown).length
    if (!count) continue

    width += count * measure.square + (count - 1) * measure.inner
    groups += 1
  }

  return groups ? width + (groups - 1) * measure.outer : 0
}

/**
 * The squares that may leave right now, in the order they go: those of DROP_ORDER this panel has at all
 * (no microphone while dictation is off, no search where the shell offers none - a missing one never costs
 * a step), except the microphone while it is listening. That one is the dictation's only stop button on
 * the screen, and a panel dragged narrow mid-sentence must not take it out from under the hand.
 *
 * Ahead of all of them, the file name on the editor's chip when there is one (`named`): it is the cheapest
 * loss in the row - the drawing stays, still a button, and the name is in its hover - and it goes whole,
 * as a square does. Shrunk by the layout instead, it gave way together with Queue and Send and ended as a
 * stub of a letter beside a Send that had already lost its word. A muted chip (`muted`) goes whole in that
 * same first place: it carries nothing, the person switched it off, and the setting is the way back.
 */
export const droppableTools = (
  present: ReadonlySet<WritingTool>,
  listening: boolean,
  editor: EditorFit = null,
): FitStep[] => [
  ...(editor === 'named' ? (['editorName'] as const) : editor === 'muted' ? (['editorChip'] as const) : []),
  ...DROP_ORDER.filter((tool) => present.has(tool) && !(tool === 'voice' && listening)),
]

/**
 * How many of the `droppable` squares to leave out, counted from the first.
 *
 * `present` - the squares this panel has at all; `dropped` - how many are left out right now, which is what
 * the measurement was taken with.
 *
 * The fewest that make the row fit - so widening the panel brings the squares back in the reverse order.
 * Bringing one back asks for a pixel to spare rather than for an exact fit: the measurement is rounded, and
 * a square that came back into a row it only just fits would be the next measurement's reason to go again.
 */
export const toolsDropped = (
  present: ReadonlySet<WritingTool>,
  droppable: readonly FitStep[],
  dropped: number,
  measure: ToolsMeasure,
): number => {
  const current = Math.min(dropped, droppable.length)
  const named = droppable.includes('editorName')
  const muted = droppable.includes('editorChip')
  const widthAt = (level: number) => {
    const gone = new Set<FitStep>(droppable.slice(0, level))
    const name = named && !gone.has('editorName') ? (measure.name ?? 0) : 0
    const chip = muted && !gone.has('editorChip') ? EDITOR_CHIP_MUTED : 0
    return toolsWidth((tool) => present.has(tool) && !gone.has(tool), measure) + name + chip
  }
  const now = widthAt(current)

  for (let level = 0; level < droppable.length; level += 1) {
    // How far past the room the row would reach with this many left out.
    const over = measure.short - now + widthAt(level)
    if (over <= (level < current ? -1 : TOLERANCE)) return level
  }

  return droppable.length
}

/**
 * A caption split into its first letter and the rest (see Queue and Send in Composer).
 *
 * The button narrows down to the first letter and no further, and the layout can hold that floor only if
 * the letter is a box of its own. By code point rather than by UTF-16 unit, so that a letter outside the
 * basic plane is not cut in half.
 */
export const firstLetter = (text: string): [string, string] => {
  const [head = '', ...rest] = Array.from(text)
  return [head, rest.join('')]
}
