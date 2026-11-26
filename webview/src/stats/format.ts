/**
 * How the statistics say their figures: thousands with a space, big numbers and tokens shortened, dates
 * the way the calendar names them.
 *
 * One place rather than a helper per tile, for the same reason as everywhere in this panel: two tiles
 * saying "2.79B" and "2 794M" about one figure read as two different figures.
 */

/**
 * An ordinary space between the groups of three, as the design writes them - not a narrow no-break one:
 * a figure that cannot be pasted into a search box as digits is a figure that lies a little.
 */
const NARROW_SPACE = ' '

/** "1 284" - thousands set off by a space. */
export const groupThousands = (value: number): string => {
  const rounded = Math.round(value)
  const sign = rounded < 0 ? '-' : ''
  const digits = String(Math.abs(rounded))
  const groups: string[] = []
  for (let end = digits.length; end > 0; end -= 3) groups.unshift(digits.slice(Math.max(0, end - 3), end))
  return sign + groups.join(NARROW_SPACE)
}

/** "18.4k", "41.2M" - a figure that no longer needs its last digits. Below ten thousand it stays whole. */
export const compactNumber = (value: number): string => {
  const abs = Math.abs(value)
  if (abs >= 1_000_000) return `${trimZero((value / 1_000_000).toFixed(1))}M`
  if (abs >= 10_000) return `${trimZero((value / 1_000).toFixed(1))}k`
  return groupThousands(value)
}

const trimZero = (text: string): string => text.replace(/\.0$/, '')

/**
 * "57.7B", "2.79B", "412M", "18.4k" - tokens as a person reads them.
 *
 * Billions from the first thousand millions up, with three figures that matter: "57 673M" was a number to
 * count the digits of, and the tab adds up tens of billions in a month. Below a billion it stays in whole
 * millions, the unit of the "tok" figure by the composer; below a million it says "18.4k".
 */
export const tokenAmount = (value: number): string => {
  const abs = Math.abs(value)
  if (abs >= 1_000_000_000) {
    const billions = value / 1_000_000_000
    const places = abs >= 100_000_000_000 ? 0 : abs >= 10_000_000_000 ? 1 : 2
    return `${trimZeros(billions.toFixed(places))}B`
  }
  return abs >= 1_000_000 ? `${groupThousands(value / 1_000_000)}M` : compactNumber(value)
}

/** "2.80" -> "2.8", "3.00" -> "3": the places of a figure that say nothing. */
const trimZeros = (text: string): string => (text.includes('.') ? text.replace(/\.?0+$/, '') : text)

/** "+2.1B", "-480M", "±0" - a change of tokens against the previous stretch of the same length. */
export const tokenDelta = (value: number): string => {
  if (Math.round(value) === 0) return '±0'
  return `${value > 0 ? '+' : '-'}${tokenAmount(Math.abs(value))}`
}

/**
 * "500M", "1.5B" - tokens on a chart's scale, where there is room for four characters and no more. The
 * notes and tips say three figures (see tokenAmount).
 */
export const tokenScale = (value: number): string => {
  const abs = Math.abs(value)
  if (abs >= 1_000_000_000) return `${trimZero((value / 1_000_000_000).toFixed(1))}B`
  if (abs >= 1_000_000) return `${trimZero((value / 1_000_000).toFixed(abs >= 100_000_000 ? 0 : 1))}M`
  if (abs >= 1_000) return `${Math.round(value / 1_000)}k`
  return String(Math.round(value))
}

/** "$974", "$0.42" - the API price of what the plan already paid for. */
export const money = (usd: number): string => {
  if (usd >= 100) return `$${groupThousands(usd)}`
  if (usd >= 10) return `$${usd.toFixed(1)}`
  return `$${usd.toFixed(2)}`
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "Aug 14" out of "2026-08-14". */
export const shortDate = (date: string): string => {
  const month = Number(date.slice(5, 7))
  const day = Number(date.slice(8, 10))
  return `${MONTHS[month - 1] ?? ''} ${day}`
}

/** "Oct" out of "2026-10-14" - a month's bar; January says its year, so a year turning is seen to. */
export const shortMonth = (date: string): string => {
  const month = Number(date.slice(5, 7))
  const name = MONTHS[month - 1] ?? ''
  return month === 1 ? `${name} ${date.slice(0, 4)}` : name
}

/** "Oct 2026" - a month's bar under the pointer. */
export const monthYear = (date: string): string => `${MONTHS[Number(date.slice(5, 7)) - 1] ?? ''} ${date.slice(0, 4)}`

/** "Aug 14, 2026" - when the year matters, as in "counting since". */
export const longDate = (date: string): string => `${shortDate(date)}, ${date.slice(0, 4)}`

export const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** "10:00" out of an hour of the day. */
export const clock = (hour: number): string => `${String(((hour % 24) + 24) % 24).padStart(2, '0')}:00`
