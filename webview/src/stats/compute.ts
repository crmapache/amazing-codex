import type { StatisticsData, StatisticsDay } from '../protocol'
import { addDays, dayNumber, daysBetween, weekday } from './dates'
import { WEEKDAYS, clock, longDate, shortDate, shortMonth } from './format'

/**
 * The arithmetic behind the statistics tab: out of the day records the IDE keeps and the tokens it reads
 * out of the CLI's transcripts, everything the tiles, the bars, the calendar and the lists show - for
 * whichever range is chosen.
 *
 * The days are the machine's, not one project's: they arrive already folded together (see
 * StatsSnapshot.daysTogether on the plugin's side), so every figure here is about the person's work
 * wherever it was done. The one thing counted by project is where the tokens went, and it reads the
 * tokens the projects carry alongside the days.
 *
 * Nothing here is about the time a panel stood open. It used to be the tab's first figure, and it said
 * more about a window left open than about the work: the tokens a day of work took is the honest measure.
 *
 * Pure functions over the message, so the range switches without a trip to the IDE and the whole of it
 * can be checked in a test. The words go in the components; here are the numbers.
 */

export type RangeKey = '1d' | '7d' | '30d' | 'all'

export interface Range {
  key: RangeKey
  /** The first and the last day counted, both included. */
  from: string
  to: string
  /** How many calendar days that is. */
  days: number
  /** "today", "last 30 days", "since Aug 26, 2026". */
  label: string
  /** "TODAY", "30D", "7D", "ALL" - the tag on the first tile. */
  tag: string
}

const RANGE_DAYS: Record<RangeKey, number> = { '1d': 1, '7d': 7, '30d': 30, all: 0 }

/**
 * The first day anything at all was recorded - a day in the book, or a day of tokens - where "all time"
 * begins. The tokens often reach further back than the book: the first reading of the transcripts finds
 * the month of work before the plugin was installed.
 */
export const firstRecordedDay = (data: StatisticsData): string | null => {
  let first: string | null = null
  const consider = (day: string) => {
    if (first === null || day < first) first = day
  }
  for (const day of data.days) consider(day.date)
  for (const day of Object.keys(data.tokens ?? {})) if (tokensOn(data, day) > 0) consider(day)
  return first
}

export const rangeOf = (data: StatisticsData, key: RangeKey): Range => {
  const to = data.today
  if (key !== 'all') {
    const days = RANGE_DAYS[key]
    return {
      key,
      from: addDays(to, -(days - 1)),
      to,
      days,
      // A single day is today itself, and reads as such rather than as "last 1 days".
      label: days === 1 ? 'today' : `last ${days} days`,
      tag: days === 1 ? 'TODAY' : `${days}D`,
    }
  }

  const first = firstRecordedDay(data) ?? to
  const from = first < to ? first : to
  return {
    key,
    from,
    to,
    days: dayNumber(to) - dayNumber(from) + 1,
    label: `since ${longDate(from)}`,
    tag: 'ALL',
  }
}

const within = (day: string, from: string, to: string): boolean => day >= from && day <= to

const sum = (days: StatisticsDay[], pick: (day: StatisticsDay) => number | undefined): number =>
  days.reduce((total, day) => total + (pick(day) ?? 0), 0)

const max = (days: StatisticsDay[], pick: (day: StatisticsDay) => number | undefined): number =>
  days.reduce((best, day) => Math.max(best, pick(day) ?? 0), 0)

const isActive = (day: StatisticsDay): boolean => day.minutes > 0 || (day.turns ?? 0) > 0 || (day.prompts ?? 0) > 0

/** The days inside the range. */
export const daysIn = (data: StatisticsData, range: Range): StatisticsDay[] =>
  data.days.filter((day) => within(day.date, range.from, range.to))

// --- Tokens -----------------------------------------------------------------------------

/**
 * The tokens of one day, every hour of it added up.
 *
 * Out of `tokens` rather than the turns of `days`: the turns know only the main thread of a panel's own
 * conversations, and a busy day spends most of its tokens in subagents and scenario runs (see
 * StatisticsData.tokens).
 */
const tokensOn = (data: StatisticsData, date: string): number =>
  (data.tokens?.[date] ?? []).reduce((total, value) => total + value, 0)

const tokensOver = (data: StatisticsData, from: string, to: string): number =>
  daysBetween(from, to).reduce((total, date) => total + tokensOn(data, date), 0)

/** The first day the transcripts know of - before it, a day is not a day without tokens but an unknown one. */
const firstTokenDay = (data: StatisticsData): string | null =>
  Object.keys(data.tokens ?? {})
    .filter((date) => tokensOn(data, date) > 0)
    .sort()[0] ?? null

export interface TokenTile {
  tokens: number
  /**
   * The change against the stretch of equal length before the range. Null for all time, and when that
   * stretch reaches back past the first day the transcripts know: a day before them is not a day without
   * tokens - the CLI deleted its transcript - and a delta against it would be a rise that never happened.
   */
  delta: number | null
  /** The hour of the day that held the most of them, added up over the range - absent when nothing was spent. */
  busiestHour: number | null
  /** How many projects spent tokens in the range - "across four projects" under the figure. */
  projects: number
}

export const tokenTile = (data: StatisticsData, range: Range): TokenTile => {
  const tokens = tokensOver(data, range.from, range.to)

  let delta: number | null = null
  const first = firstTokenDay(data)
  if (range.key !== 'all' && first !== null) {
    const previousTo = addDays(range.from, -1)
    const previousFrom = addDays(previousTo, -(range.days - 1))
    if (previousFrom >= first) delta = tokens - tokensOver(data, previousFrom, previousTo)
  }

  const byHour = Array.from({ length: 24 }, () => 0)
  for (const date of daysBetween(range.from, range.to)) {
    ;(data.tokens?.[date] ?? []).forEach((value, hour) => {
      if (hour < 24) byHour[hour] = (byHour[hour] ?? 0) + value
    })
  }
  let busiestHour: number | null = null
  byHour.forEach((value, hour) => {
    if (value > 0 && (busiestHour === null || value > byHour[busiestHour]!)) busiestHour = hour
  })

  return { tokens, delta, busiestHour, projects: projectsSpending(data, range) }
}

/** How many projects hold a token inside the range. */
export const projectsSpending = (data: StatisticsData, range: Range): number =>
  data.projects.filter((project) =>
    Object.entries(project.tokens ?? {}).some(([day, tokens]) => tokens > 0 && within(day, range.from, range.to)),
  ).length

// --- Days at work -----------------------------------------------------------------------

export interface DaysTile {
  active: number
  total: number
  /** The run of days reaching today or yesterday, and the longest run ever. */
  streak: number
  best: number
  /** The last fortnight, oldest first: whether each day was worked. */
  dots: { date: string; active: boolean }[]
}

const DOT_DAYS = 14

/** The longest run of consecutive days in a sorted list. */
export const bestStreak = (dates: string[]): number => {
  let best = 0
  let run = 0
  let previous: number | null = null

  for (const date of [...dates].sort()) {
    const number = dayNumber(date)
    run = previous !== null && number === previous + 1 ? run + 1 : 1
    best = Math.max(best, run)
    previous = number
  }

  return best
}

/** The run that reaches today, or yesterday since today may not have started. Zero otherwise. */
export const currentStreak = (dates: string[], today: string): number => {
  const set = new Set(dates)
  let cursor = set.has(today) ? today : addDays(today, -1)
  if (!set.has(cursor)) return 0

  let run = 0
  while (set.has(cursor)) {
    run++
    cursor = addDays(cursor, -1)
  }
  return run
}

export const daysTile = (data: StatisticsData, range: Range): DaysTile => {
  const activeDates = data.days.filter(isActive).map((day) => day.date)
  const inRange = activeDates.filter((date) => within(date, range.from, range.to))
  const activeSet = new Set(activeDates)

  return {
    active: inRange.length,
    total: range.days,
    streak: currentStreak(activeDates, data.today),
    best: bestStreak(activeDates),
    dots: daysBetween(addDays(data.today, -(DOT_DAYS - 1)), data.today).map((date) => ({
      date,
      active: activeSet.has(date),
    })),
  }
}

// --- What came out of it ----------------------------------------------------------------

export interface OutputTile {
  turns: number
  sessions: number
  filesTouched: number
  forks: number
}

export const outputTile = (data: StatisticsData, range: Range): OutputTile => {
  const days = daysIn(data, range)
  return {
    turns: sum(days, (day) => day.turns),
    sessions: sum(days, (day) => day.sessions),
    filesTouched: sum(days, (day) => day.filesTouched),
    forks: sum(days, (day) => day.forks),
  }
}

// --- Tokens over the range -----------------------------------------------------------------

export interface TokenSummary {
  /** All of them, over the range. */
  total: number
  /** The busiest day in the range, if there was one. */
  busiest: { date: string; tokens: number } | null
  /**
   * Tokens a day, and over which days: from the first day the transcripts know of, or the range's own
   * first day if that is later. Null when the range lies wholly before them.
   *
   * Not over the whole range, because a day before the transcripts is not a day without tokens: the
   * CLI deletes its transcripts after a month, and the work of those days is simply not on the disk any
   * more. Counted as zeros, they would put "all time" at a fraction of what a day really costs. Within
   * the known stretch a zero is a real day off and counts like any other.
   */
  average: { tokens: number; from: string; days: number } | null
}

export const tokenSummary = (data: StatisticsData, range: Range): TokenSummary => {
  const dates = daysBetween(range.from, range.to)

  let busiest: { date: string; tokens: number } | null = null
  let total = 0
  for (const date of dates) {
    const spent = tokensOn(data, date)
    total += spent
    if (spent > 0 && (busiest === null || spent > busiest.tokens)) busiest = { date, tokens: spent }
  }

  const first = firstTokenDay(data)
  const known = first === null ? [] : dates.filter((date) => date >= first)
  const average =
    known.length === 0
      ? null
      : {
          tokens: known.reduce((sum, date) => sum + tokensOn(data, date), 0) / known.length,
          from: known[0]!,
          days: known.length,
        }

  return { total, busiest, average }
}

// --- Tokens as bars ---------------------------------------------------------------------------

/**
 * How much time one bar holds: a number of hours that divides the day, one to three days, one or two weeks
 * (Monday to Sunday) or a calendar month. The hours divide the day so a bar never straddles midnight; the
 * days and weeks are counted from the epoch rather than from the range's first day, so a bar keeps the same
 * days from one day to the next instead of every bar shifting when a day goes by; and the weeks and months
 * follow the calendar so a bar reads as "that week" rather than as seven days from nowhere.
 *
 * The steps between a day and a month are there so the picture coarsens gently as the panel narrows or
 * the history grows: from days straight to weeks, a hundred and thirty days in an ordinary panel went from
 * too many bars to nineteen.
 */
export type BarSpan = 1 | 2 | 3 | 4 | 6 | 8 | 12 | 'day' | '2 days' | '3 days' | 'week' | '2 weeks' | 'month'

const SPANS: BarSpan[] = [1, 2, 3, 4, 6, 8, 12, 'day', '2 days', '3 days', 'week', '2 weeks', 'month']

/**
 * The narrowest a bar may be before the next span up is taken - the thirty days and all time only. About
 * as wide as the week's hourly bars come out in a panel of ordinary width, so the three ranges read as
 * one family of pictures.
 */
export const BAR_MIN_PX = 5

/** The room one name under the axis needs: "Sep 30" in the tick font, and a gap to the next. */
export const BAR_LABEL_PX = 52

export interface TokenBar {
  /** The first and the last day the bar holds - one and the same for a bar of hours or of a day. */
  from: string
  to: string
  /** For a bar of hours, the hour it begins at and the hour it ends at (exclusive); 0 and 24 otherwise. */
  hour: number
  end: number
  tokens: number
  /** Whether the bar lies wholly after the present hour - the rest of today, drawn as nothing at all. */
  ahead: boolean
  /**
   * Whether every day the bar holds is a Saturday or a Sunday - a faint band behind it, so a week of bars
   * shows where the weekend fell. A bar that mixes a Friday in, or the single day of today, is not marked.
   */
  weekend: boolean
}

/** A name under the axis, and the bars it stands under: [start, start + count). */
export interface BarTick {
  start: number
  count: number
  label: string
}

export interface TokenBars {
  span: BarSpan
  bars: TokenBar[]
  /** The bar that held the most, or null when the range spent nothing. */
  peak: number | null
  ticks: BarTick[]
}

/** Which bar of a span longer than a day a date falls in - the same number for every day of one bar. */
const barOf = (span: Exclude<BarSpan, number>, date: string): number => {
  const day = dayNumber(date)
  // Weeks from the Monday the epoch's week began with: day 0 was a Thursday.
  const week = Math.floor((day + 3) / 7)
  switch (span) {
    case 'day':
      return day
    case '2 days':
      return Math.floor(day / 2)
    case '3 days':
      return Math.floor(day / 3)
    case 'week':
      return week
    case '2 weeks':
      return Math.floor(week / 2)
    case 'month':
      return Number(date.slice(0, 4)) * 12 + Number(date.slice(5, 7)) - 1
  }
}

/** How many bars the range comes to at a span. */
export const barCount = (range: Range, span: BarSpan): number => {
  if (typeof span === 'number') return (range.days * 24) / span
  return barOf(span, range.to) - barOf(span, range.from) + 1
}

/**
 * The span of a bar for the range, given the width the bars have to share.
 *
 * Today and the week are by the hour whatever the width: the day's rhythm - the mornings, the nights the
 * agents ran on their own - is what they are looked at for, and a week's 168 bars still fit a narrow
 * panel once the bars give up their gaps (see .barFill). Thirty days and all time take the finest span
 * whose bars stay at least [BAR_MIN_PX] wide, so a wider panel shows more of the rhythm rather than
 * fatter bars.
 */
export const barSpan = (range: Range, plotWidth: number): BarSpan => {
  if (range.key === '1d' || range.key === '7d') return 1
  const fits = Math.floor(plotWidth / BAR_MIN_PX)
  return SPANS.find((span) => barCount(range, span) <= fits) ?? 'month'
}

export const tokenBars = (data: StatisticsData, range: Range, plotWidth: number): TokenBars => {
  const span = barSpan(range, plotWidth)
  const hourNow = new Date(data.now).getHours()
  const dates = daysBetween(range.from, range.to)
  const bars: TokenBar[] = []

  const sumHours = (date: string, from: number, to: number): number => {
    const hours = data.tokens?.[date] ?? []
    let total = 0
    for (let hour = from; hour < to; hour++) total += hours[hour] ?? 0
    return total
  }

  if (typeof span === 'number') {
    for (const date of dates) {
      for (let hour = 0; hour < 24; hour += span) {
        bars.push({
          from: date,
          to: date,
          hour,
          end: hour + span,
          tokens: sumHours(date, hour, hour + span),
          ahead: date > data.today || (date === data.today && hour > hourNow),
          weekend: false,
        })
      }
    }
  } else {
    let key: number | null = null
    for (const date of dates) {
      const tokens = sumHours(date, 0, 24)
      const last = bars[bars.length - 1]
      if (last && key === barOf(span, date)) {
        last.to = date
        last.tokens += tokens
      } else {
        key = barOf(span, date)
        bars.push({ from: date, to: date, hour: 0, end: 24, tokens, ahead: false, weekend: false })
      }
    }
  }

  if (range.days > 1) {
    for (const bar of bars) bar.weekend = daysBetween(bar.from, bar.to).every((date) => weekday(date) >= 5)
  }

  let peak: number | null = null
  bars.forEach((bar, index) => {
    if (bar.tokens > 0 && (peak === null || bar.tokens > bars[peak]!.tokens)) peak = index
  })

  return { span, bars, peak, ticks: barTicks(bars, span, range, plotWidth) }
}

/**
 * The names under the bars: the hours of a single day; the days, for the bars of hours across several; a
 * bar's first day; the month. As many as fit at [BAR_LABEL_PX] apiece - every second or
 * third one when they do not - and each stands centred under what it names.
 */
const barTicks = (bars: TokenBar[], span: BarSpan, range: Range, plotWidth: number): BarTick[] => {
  if (typeof span === 'number' && range.days === 1) {
    return bars.flatMap((bar, index) => (bar.hour % 6 === 0 ? [{ start: index, count: 1, label: clock(bar.hour) }] : []))
  }

  const groups: BarTick[] = []
  if (typeof span === 'number') {
    const perDay = 24 / span
    for (let start = 0; start < bars.length; start += perDay) {
      groups.push({ start, count: perDay, label: shortDate(bars[start]!.from) })
    }
  } else {
    bars.forEach((bar, index) => {
      groups.push({ start: index, count: 1, label: span === 'month' ? shortMonth(bar.from) : shortDate(bar.from) })
    })
  }

  const every = Math.max(1, Math.ceil((groups.length * BAR_LABEL_PX) / Math.max(1, plotWidth)))
  return groups.filter((_, index) => index % every === 0)
}

// --- When you work ----------------------------------------------------------------------

export interface HeatCell {
  date: string
  tokens: number
  /** 0 is quiet, 4 is the busiest a day gets. */
  level: number
  /** Whether the day lies in the future - the rest of this week, drawn empty. */
  ahead: boolean
}

export interface HeatMap {
  /** Weeks, newest first; each week Monday to Sunday. */
  weeks: HeatCell[][]
  /** "Most of your work lands Tue–Thu, between 10:00 and 13:00." - by the tokens it took. */
  sentence: string
}

/**
 * How many weeks the calendar shows at the narrowest: a year of them. Past that the panel asks for as many
 * as fit its width (see Heatmap), so the columns reach the right-hand edge however wide it is pulled - a
 * calendar that stops halfway across the card reads as "there is nothing further back", which is not what
 * a fixed column count means. The ceiling only keeps an absurd width from asking for a century.
 */
export const HEAT_WEEKS = 53
export const HEAT_WEEKS_MAX = 53 * 10

/** The busiest quarter of days is the top level; the rest are spread evenly below it. */
export const heatLevel = (tokens: number, busiest: number): number => {
  if (tokens <= 0 || busiest <= 0) return 0
  const share = tokens / busiest
  if (share <= 0.25) return 1
  if (share <= 0.5) return 2
  if (share <= 0.75) return 3
  return 4
}

export const heatMap = (data: StatisticsData, weeks: number = HEAT_WEEKS): HeatMap => {
  const columns = Math.min(HEAT_WEEKS_MAX, Math.max(1, Math.round(weeks)))
  const today = data.today
  // The newest week ends on the coming Sunday, so today always sits in the last column.
  const end = addDays(today, 6 - weekday(today))
  const start = addDays(end, -(columns * 7 - 1))
  const dates = daysBetween(start, end)

  const busiest = dates.reduce((best, date) => (date > today ? best : Math.max(best, tokensOn(data, date))), 0)

  const cells = dates.map((date) => {
    const tokens = tokensOn(data, date)
    return { date, tokens, level: heatLevel(tokens, busiest), ahead: date > today }
  })

  const grid: HeatCell[][] = []
  for (let index = 0; index < cells.length; index += 7) grid.push(cells.slice(index, index + 7))

  return { weeks: grid.reverse(), sentence: workSentence(data.tokens ?? {}) }
}

/**
 * The days of the week and the hours of the day the work lands in - the run of three weekdays and the
 * run of three hours that hold the most tokens.
 */
export const workSentence = (tokens: Record<string, number[]>): string => {
  const byWeekday = Array.from({ length: 7 }, () => 0)
  const byHour = Array.from({ length: 24 }, () => 0)
  let total = 0

  for (const [date, hours] of Object.entries(tokens)) {
    hours.forEach((spent, hour) => {
      if (spent <= 0 || hour >= 24) return
      total += spent
      byWeekday[weekday(date)] = (byWeekday[weekday(date)] ?? 0) + spent
      byHour[hour] = (byHour[hour] ?? 0) + spent
    })
  }

  if (total === 0) return 'Nothing here yet - the calendar fills in as you work.'

  const dayRun = bestRun(byWeekday, 3, false)
  const hourRun = bestRun(byHour, 3, true)

  const dayWords =
    dayRun.length === 1
      ? `${WEEKDAYS[dayRun.start]}`
      : `${WEEKDAYS[dayRun.start]}–${WEEKDAYS[(dayRun.start + dayRun.length - 1) % 7]}`
  const hourWords = `between ${clock(hourRun.start)} and ${clock(hourRun.start + hourRun.length)}`

  return `Most of your work lands ${dayWords}, ${hourWords}.`
}

/**
 * The run of up to `width` neighbouring slots holding the most, shortened from either end while the
 * end slot adds too little to be worth naming - "Tue–Thu" rather than "Mon–Thu" when Monday is quiet.
 */
const bestRun = (values: number[], width: number, wrap: boolean): { start: number; length: number } => {
  const size = values.length
  let best = { start: 0, length: 1, total: -1 }

  for (let start = 0; start < size; start++) {
    let total = 0
    for (let offset = 0; offset < width; offset++) {
      const index = start + offset
      if (index >= size && !wrap) break
      total += values[index % size] ?? 0
    }
    if (total > best.total) best = { start, length: Math.min(width, wrap ? width : size - start), total }
  }

  // Trim the quiet ends: a slot holding under a fifth of the run's busiest slot is not where the work is.
  let start = best.start
  let length = best.length
  const peak = Math.max(...Array.from({ length }, (_, offset) => values[(start + offset) % size] ?? 0))
  while (length > 1 && (values[start % size] ?? 0) < peak * 0.2) {
    start++
    length--
  }
  while (length > 1 && (values[(start + length - 1) % size] ?? 0) < peak * 0.2) length--

  return { start: start % size, length }
}

// --- What the agent did -------------------------------------------------------------------

export interface ToolRow {
  name: string
  count: number
  /** Against the busiest tool: 0 to 1. */
  share: number
  /** The feed's own paint for it: reads on the neutral backing, edits on the primary, a command on sand. */
  tone: 'neutral' | 'edit' | 'command' | 'agent' | 'mcp'
}

const TOOL_TONE: Record<string, ToolRow['tone']> = {
  Read: 'neutral',
  Grep: 'neutral',
  Glob: 'neutral',
  Edit: 'edit',
  MultiEdit: 'edit',
  Write: 'edit',
  NotebookEdit: 'edit',
  Bash: 'command',
  Task: 'agent',
  MCP: 'mcp',
}

export const TOOL_ROWS = 5

export const toolRows = (data: StatisticsData, range: Range, limit = TOOL_ROWS): ToolRow[] => {
  const counts = new Map<string, number>()
  for (const day of daysIn(data, range)) {
    for (const [name, count] of Object.entries(day.tools ?? {})) counts.set(name, (counts.get(name) ?? 0) + count)
  }

  const rows = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, limit)
  const top = rows[0]?.[1] ?? 0

  return rows.map(([name, count]) => ({
    name,
    count,
    share: top > 0 ? count / top : 0,
    tone: TOOL_TONE[name] ?? 'neutral',
  }))
}

// --- Where the tokens went ------------------------------------------------------------------

export interface ProjectRow {
  key: string
  name: string
  tokens: number
  share: number
  current: boolean
}

export const PROJECT_ROWS = 5

export const projectRows = (data: StatisticsData, range: Range, limit = PROJECT_ROWS): ProjectRow[] => {
  const rows = data.projects
    .map((project) => {
      let tokens = 0
      for (const [day, value] of Object.entries(project.tokens ?? {})) {
        if (within(day, range.from, range.to)) tokens += value
      }
      return { key: project.key, name: project.name || 'unnamed', tokens, share: 0, current: project.key === data.project.key }
    })
    .filter((row) => row.tokens > 0)
    .sort((a, b) => b.tokens - a.tokens || a.name.localeCompare(b.name))
    .slice(0, limit)

  const top = rows[0]?.tokens ?? 0
  return rows.map((row) => ({ ...row, share: top > 0 ? row.tokens / top : 0 }))
}

// --- Files -----------------------------------------------------------------------------------

export interface FilesTile {
  added: number
  removed: number
  touched: number
  refused: number
  biggest: number
}

export const filesTile = (data: StatisticsData, range: Range): FilesTile => {
  const days = daysIn(data, range)

  return {
    added: sum(days, (day) => day.linesAdded),
    removed: sum(days, (day) => day.linesRemoved),
    touched: sum(days, (day) => day.filesTouched),
    refused: sum(days, (day) => day.editsRefused),
    biggest: max(days, (day) => day.biggestEdit),
  }
}

// --- Sessions · models · forks ----------------------------------------------------------------

export interface FactsTile {
  sessions: number
  turns: number
  forks: number
  deepestChain: number
  /** Turns by model family, the busiest first, as shares of all turns. */
  models: { name: string; turns: number; share: number }[]
  cost: number
}

export const factsTile = (data: StatisticsData, range: Range): FactsTile => {
  const days = daysIn(data, range)

  const modelTurns = new Map<string, number>()
  for (const day of days) {
    for (const [name, count] of Object.entries(day.models ?? {})) modelTurns.set(name, (modelTurns.get(name) ?? 0) + count)
  }
  const modelTotal = [...modelTurns.values()].reduce((total, value) => total + value, 0)
  const models = [...modelTurns.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([name, count]) => ({ name, turns: count, share: modelTotal > 0 ? count / modelTotal : 0 }))

  return {
    sessions: sum(days, (day) => day.sessions),
    turns: sum(days, (day) => day.turns),
    forks: sum(days, (day) => day.forks),
    deepestChain: max(days, (day) => day.maxDepth),
    models,
    cost: sum(days, (day) => day.cost),
  }
}
