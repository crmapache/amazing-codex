import { useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import type { StatisticsData } from '../../protocol'
import { tokenBars, tokenSummary, type BarSpan, type BarTick, type Range, type TokenBar, type TokenSummary } from '../../stats/compute'
import { clock, monthYear, shortDate, tokenAmount, tokenScale } from '../../stats/format'
import s from './stats.module.css'

/**
 * The tokens of the range as bars: the hours of today, the 168 hours of the week, and for thirty days and
 * all time as fine a span as the width allows (see barSpan in stats/compute.ts) - four hours a bar in a
 * panel of ordinary width, a day or a week in a narrow one or over a long history.
 *
 * Bars rather than a line through the days, because a day of work is not one figure: it has mornings,
 * evenings, and nights the agents ran on their own, and a point a day averaged all of that into a hill.
 *
 * The bars share the width rather than keeping one of their own: each takes an equal part of the row, and
 * the gap beside it shrinks with it (see .barFill), down to bars a pixel and a half wide in the narrowest
 * panel - which is still a readable picture of a week. The width is measured, not assumed, because the
 * span of a bar for thirty days and all time depends on it; the measuring happens before the first paint,
 * so the card never flashes a month of one bar before settling.
 */

interface TokenCardProps {
  data: StatisticsData
  range: Range
}

/** The lines across the plot, as shares of the top - the whole, the half and the floor. */
const LINES = [1, 0.5, 0]

const MILLION = 1_000_000
const MAX_Y_TICKS = 4

/** The width one character of a name under the axis takes, near enough to keep the end ones inside the card. */
const TICK_CHAR_PX = 5.6

/**
 * The smallest of the customary steps - 1, 2 or 5 of some power of ten - that keeps the ticks to four:
 * 500M or 1B for tokens.
 */
const tickStep = (top: number): number => {
  const magnitude = 10 ** Math.floor(Math.log10(top / MAX_Y_TICKS))
  for (const step of [1, 2, 5, 10]) {
    if (top / (step * magnitude) <= MAX_Y_TICKS) return step * magnitude
  }
  return 10 * magnitude
}

/** The top of the scale: at least [floor], and a whole number of tick steps above the highest value. */
export const scaleTop = (values: number[], floor: number): number => {
  const highest = Math.max(floor, ...values)
  const step = tickStep(highest)
  return Math.ceil(highest / step) * step
}

/**
 * Where the tokens come from, on the card's label: they are not the panel's own turns, and a figure that
 * looks four times the size of what the panel showed before has to say why.
 */
const TOKENS_SOURCE =
  "Read from Claude Code's own transcripts: every session on this machine, subagents and scenario runs included"

/** Said instead of "nothing spent" while the IDE is still reading the transcripts. */
export const COUNTING_TOKENS = "counting the tokens in Claude Code's transcripts…"

export const TokenCard = ({ data, range }: TokenCardProps) => {
  const row = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)

  useLayoutEffect(() => {
    const element = row.current
    if (!element) return

    const measure = () => setWidth(element.clientWidth)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const bars = useMemo(() => tokenBars(data, range, width), [data, range, width])
  const summary = useMemo(() => tokenSummary(data, range), [data, range])
  const oneDay = range.days === 1
  const reading = data.tokensReading === true
  const peak = bars.peak === null ? null : bars.bars[bars.peak]!
  const top = scaleTop([peak?.tokens ?? 0], MILLION)
  const count = bars.bars.length

  return (
    <div className={s.chartCard}>
      <div className={s.labelRow}>
        <span className={s.label} data-tooltip={TOKENS_SOURCE}>
          {cardTitle(bars.span, oneDay)}
        </span>
        <span className={`${s.labelNote} ${s.labelNoteFills}`}>
          {oneDay ? todayNote(summary, peak, reading) : rangeNote(summary, reading)}
        </span>
        {!oneDay && summary.average ? (
          <span className={s.labelAside} data-tooltip={averageBasis(summary.average, range.from)}>
            average <span className={s.labelAsideFigure}>{tokenAmount(summary.average.tokens)}</span> a day
          </span>
        ) : null}
      </div>

      <div className={s.bars}>
        <div className={s.barsPlot}>
          {LINES.map((share) => (
            <span key={share} className={`${s.barsLine} ${share === 0 ? s.barsFloor : ''}`} style={{ bottom: `${share * 100}%` }}>
              <span className={s.barsScale}>{tokenScale(top * share)}</span>
            </span>
          ))}

          <div className={s.barsRow} ref={row} role="img" aria-label={cardTitle(bars.span, oneDay).toLowerCase()}>
            {bars.bars.map((bar, index) => (
              <span
                key={`${bar.from}-${bar.hour}`}
                className={`${s.barSlot} ${startsDay(bar, index, bars.span, oneDay) ? s.barSlotDay : ''} ${bar.weekend ? s.barSlotWeekend : ''}`}
                data-tooltip={bar.ahead ? undefined : barTip(bar, bars.span, oneDay, index === bars.peak)}
              >
                {bar.tokens > 0 && !bar.ahead ? (
                  <span
                    className={s.barFill}
                    data-peak={index === bars.peak ? '' : undefined}
                    style={{ height: `${Math.max(2, (bar.tokens / top) * 100)}%` }}
                  />
                ) : null}
              </span>
            ))}
          </div>
        </div>

        <div className={s.barsTicks}>
          {bars.ticks.map((tick) => (
            <span key={`${tick.start}-${tick.label}`} className={s.barsTick} style={tickPlace(tick, count, width)}>
              {tick.label}
            </span>
          ))}
        </div>
      </div>
    </div>
  )
}

/** "THE TOKENS OF TODAY", "TOKENS BY THE HOUR", "TOKENS BY 4 HOURS", "TOKENS BY 2 WEEKS" - what one bar is. */
const cardTitle = (span: BarSpan, oneDay: boolean): string => {
  if (oneDay) return 'THE TOKENS OF TODAY'
  if (span === 1) return 'TOKENS BY THE HOUR'
  if (typeof span === 'number') return `TOKENS BY ${span} HOURS`
  if (span === 'day' || span === 'week' || span === 'month') return `TOKENS BY THE ${span.toUpperCase()}`
  return `TOKENS BY ${span.toUpperCase()}`
}

/**
 * A bar of hours that opens a new day, in a range of several: a faint line before it keeps the days apart.
 * Only where a day is six bars or more - between every second bar the lines are a texture, not a calendar.
 */
const startsDay = (bar: TokenBar, index: number, span: BarSpan, oneDay: boolean): boolean =>
  !oneDay && typeof span === 'number' && span <= DAY_LINES_UP_TO && bar.hour === 0 && index > 0

/** The longest bar of hours that still gets a line between the days: six of them to a day. */
const DAY_LINES_UP_TO = 4

/**
 * Where a name under the axis stands: centred under the bars it names, unless that would push it past
 * either end of the row - then it keeps to the end, as the first and the last date of a line chart did.
 */
const tickPlace = (tick: BarTick, count: number, width: number): CSSProperties => {
  if (count === 0) return {}
  const center = (tick.start + tick.count / 2) / count
  const half = (tick.label.length * TICK_CHAR_PX) / 2

  if (width > 0 && center * width - half < 0) return { left: `${(tick.start / count) * 100}%` }
  if (width > 0 && center * width + half > width) return { right: `${(1 - (tick.start + tick.count) / count) * 100}%` }
  return { left: `${center * 100}%`, transform: 'translateX(-50%)' }
}

/** "18:00", and midnight at the end of a bar as "24:00" rather than as the start of the day. */
const clockEnd = (hour: number): string => (hour === 24 ? '24:00' : clock(hour))

/**
 * "Oct 19 · 14:00 · 412M tokens", "Oct 13 - Oct 19 · 9.84B tokens · the busiest".
 *
 * The last part is there because the lit bar is the one thing on the card nothing explains: hovering says
 * that it is the most there was, rather than a state of its own.
 */
const barTip = (bar: TokenBar, span: BarSpan, oneDay: boolean, busiest: boolean): string => {
  const when =
    typeof span === 'number'
      ? `${oneDay ? '' : `${shortDate(bar.from)} · `}${span === 1 ? clock(bar.hour) : `${clock(bar.hour)}-${clockEnd(bar.end)}`}`
      : span === 'month'
        ? monthYear(bar.from)
        : bar.from === bar.to
          ? shortDate(bar.from)
          : `${shortDate(bar.from)} - ${shortDate(bar.to)}`
  const spent = bar.tokens > 0 ? `${tokenAmount(bar.tokens)} tokens` : 'quiet'
  return `${when} · ${spent}${busiest ? ' · the busiest' : ''}`
}

/** "1.53B so far · busiest 03:00 with 278M". */
const todayNote = (summary: TokenSummary, peak: TokenBar | null, reading: boolean): string => {
  if (!peak) return reading ? COUNTING_TOKENS : 'no tokens spent today yet'
  return `${tokenAmount(summary.total)} so far · busiest ${clock(peak.hour)} with ${tokenAmount(peak.tokens)}`
}

/** "12.4B in all · busiest day 3.16B on Oct 15". */
const rangeNote = (summary: TokenSummary, reading: boolean): string => {
  if (!summary.busiest) return reading ? COUNTING_TOKENS : 'no tokens spent in this range yet'
  const busiest = `${tokenAmount(summary.busiest.tokens)} on ${shortDate(summary.busiest.date)}`
  return `${tokenAmount(summary.total)} in all · busiest day ${busiest}`
}

/**
 * Over which days the average is taken, when that is not the whole range - "Over the 32 days from Sep 16,
 * the first one Claude Code's transcripts still hold". Nothing when it is the whole range: then the figure
 * needs no footnote.
 */
const averageBasis = (average: { from: string; days: number }, rangeFrom: string): string | undefined =>
  average.from > rangeFrom
    ? `Over the ${average.days} days from ${shortDate(average.from)}, the first one Claude Code's transcripts still hold`
    : undefined
