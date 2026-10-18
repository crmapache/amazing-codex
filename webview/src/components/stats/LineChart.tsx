import { useEffect, useId, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react'
import { shortDate } from '../../stats/format'
import s from './stats.module.css'

/**
 * A figure a day, as one soft line with a wash of colour under it: the hours in the panel, the tokens
 * spent. One drawing for both, so the two cards stacked on the tab read as a pair - the same days under
 * the same grid, a different colour and a different scale.
 *
 * One line rather than two: the tab counts every project together, and a second line for the project in
 * front was the same figure told two ways - which is exactly what the tiles above used to disagree
 * about. Where the hours went by project is a list of its own, further down the tab.
 *
 * Drawn by hand into an SVG rather than by a charting library: the panel loads nothing off the network,
 * and a library for one line chart would be the heaviest thing in it. The width comes from the box the
 * chart sits in - it is measured rather than assumed, because the tool window is as wide as the person
 * made it.
 */

export type ChartTone = 'moon' | 'iris'

/** A tone's two paints: the channels for the line and its wash, and the light shade for the dots. */
const TONES: Record<ChartTone, { channels: string; light: string }> = {
  moon: { channels: 'var(--acc-ch-moon)', light: 'var(--acc-c-moon-4)' },
  iris: { channels: 'var(--acc-ch-iris)', light: 'var(--acc-c-iris-3)' },
}

interface LineChartProps {
  dates: string[]
  /** One figure a day, in step with [dates]. */
  values: number[]
  /** What the chart is of, for a screen reader: "Hours a day in the panel". */
  label: string
  /** The least the scale reaches to: an hour, a million tokens - so a quiet range is not drawn as a busy one. */
  floor: number
  /** A figure on the scale: "5h", "500M". */
  tick: (value: number) => string
  /** A day's figure under the pointer: "13h 32m", "2 794M tokens". */
  tip: (value: number) => string
  tone?: ChartTone
}

const HEIGHT = 150
const PAD_LEFT = 28
const PAD_RIGHT = 8
const PAD_TOP = 6
const PAD_BOTTOM = 18
const MAX_X_LABELS = 8
/**
 * The room one date under the axis needs: "Sep 30" in the tick font, and a gap to the next. Fewer dates
 * on a narrow panel - eight of them at a fixed count ran into each other ("Sep 18Sep 22").
 */
const X_LABEL_ROOM = 52
const MAX_Y_TICKS = 4

/**
 * The smallest of the customary steps - 1, 2 or 5 of some power of ten - that keeps the ticks to four:
 * 0.5h, 1h, 2h, 5h for hours, 500M or 1B for tokens.
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
 * A curve through the points that never overshoots them - a day of zero stays on the floor rather than
 * dipping below it as a plain spline would. The tangents are the monotone cubic ones (Fritsch-Carlson),
 * the segments are drawn as cubic Béziers.
 */
export const monotonePath = (points: { x: number; y: number }[]): string => {
  const count = points.length
  if (count === 0) return ''
  if (count === 1) return `M${points[0]!.x} ${points[0]!.y}`

  const slopes: number[] = []
  const deltas: number[] = []
  for (let index = 0; index < count - 1; index++) {
    const a = points[index]!
    const b = points[index + 1]!
    const dx = b.x - a.x
    deltas.push(dx === 0 ? 0 : (b.y - a.y) / dx)
  }

  slopes.push(deltas[0]!)
  for (let index = 1; index < count - 1; index++) {
    const before = deltas[index - 1]!
    const after = deltas[index]!
    slopes.push(before * after <= 0 ? 0 : (before + after) / 2)
  }
  slopes.push(deltas[count - 2]!)

  // Keep the tangents inside the band that guarantees monotonicity between two points.
  for (let index = 0; index < count - 1; index++) {
    const delta = deltas[index]!
    if (delta === 0) {
      slopes[index] = 0
      slopes[index + 1] = 0
      continue
    }
    const alpha = slopes[index]! / delta
    const beta = slopes[index + 1]! / delta
    const norm = alpha * alpha + beta * beta
    if (norm > 9) {
      const scale = 3 / Math.sqrt(norm)
      slopes[index] = scale * alpha * delta
      slopes[index + 1] = scale * beta * delta
    }
  }

  let path = `M${points[0]!.x} ${points[0]!.y}`
  for (let index = 0; index < count - 1; index++) {
    const a = points[index]!
    const b = points[index + 1]!
    const dx = (b.x - a.x) / 3
    path += ` C${a.x + dx} ${a.y + slopes[index]! * dx} ${b.x - dx} ${b.y - slopes[index + 1]! * dx} ${b.x} ${b.y}`
  }
  return path
}

const round = (value: number): number => Math.round(value * 100) / 100

export const LineChart = ({ dates, values, label, floor, tick, tip, tone = 'moon' }: LineChartProps) => {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [hover, setHover] = useState<number | null>(null)
  // Two charts on one tab: a gradient by a fixed id would paint the second with the first one's colour.
  const wash = `acc-stats-wash-${useId().replace(/:/g, '')}`
  const paint = TONES[tone]

  useEffect(() => {
    const element = box.current
    if (!element) return

    const measure = () => setWidth(element.clientWidth)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const count = dates.length
  const top = scaleTop(values, floor)
  const step = tickStep(top)
  const plotWidth = Math.max(0, width - PAD_LEFT - PAD_RIGHT)
  const plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM
  const xOf = (index: number): number => PAD_LEFT + (count <= 1 ? plotWidth / 2 : (index / (count - 1)) * plotWidth)
  const yOf = (value: number): number => PAD_TOP + plotHeight - (Math.min(top, Math.max(0, value)) / top) * plotHeight
  const floorY = PAD_TOP + plotHeight

  const points = values.map((value, index) => ({ x: round(xOf(index)), y: round(yOf(value)) }))

  const area = (points: { x: number; y: number }[]): string => {
    if (points.length === 0) return ''
    const line = monotonePath(points)
    const first = points[0]!
    const last = points[points.length - 1]!
    return `${line} L${last.x} ${floorY} L${first.x} ${floorY} Z`
  }

  const ticks: number[] = []
  // Counted in steps rather than added up: a tenth added to itself drifts off the round figures.
  for (let index = 0; index * step <= top * (1 + 1e-9); index++) ticks.push(round(index * step))

  const xLabels = Math.max(2, Math.min(MAX_X_LABELS, Math.floor(plotWidth / X_LABEL_ROOM)))
  const labelEvery = Math.max(1, Math.ceil(count / xLabels))

  const onMove = (event: ReactMouseEvent<SVGSVGElement>) => {
    if (count === 0 || plotWidth <= 0) return
    const rect = event.currentTarget.getBoundingClientRect()
    const x = event.clientX - rect.left - PAD_LEFT
    const index = count <= 1 ? 0 : Math.round((x / plotWidth) * (count - 1))
    setHover(Math.max(0, Math.min(count - 1, index)))
  }

  const hovered = hover === null ? null : dates[hover]
  const tipLeft = hover === null ? 0 : xOf(hover)
  const tipOnLeft = width > 0 && tipLeft > width * 0.6

  return (
    <div className={s.chart} ref={box}>
      {width > 0 ? (
        <svg
          className={s.chartSvg}
          viewBox={`0 0 ${width} ${HEIGHT}`}
          width={width}
          height={HEIGHT}
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
          role="img"
          aria-label={label}
        >
          <defs>
            <linearGradient id={wash} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor={`rgb(${paint.channels} / 22%)`} />
              <stop offset="1" stopColor={`rgb(${paint.channels} / 0%)`} />
            </linearGradient>
          </defs>

          {ticks.map((value) => (
            <g key={value}>
              <line className={s.chartGrid} x1={PAD_LEFT} x2={width - PAD_RIGHT} y1={yOf(value)} y2={yOf(value)} />
              <text className={s.chartTick} x={PAD_LEFT - 6} y={yOf(value) + 3} textAnchor="end">
                {tick(value)}
              </text>
            </g>
          ))}

          <line className={s.chartAxis} x1={PAD_LEFT} x2={width - PAD_RIGHT} y1={floorY} y2={floorY} />

          {dates.map((date, index) =>
            index % labelEvery === 0 && (count - 1 - index >= labelEvery / 2 || index === count - 1) ? (
              <text
                key={date}
                className={s.chartTick}
                x={xOf(index)}
                y={HEIGHT - 4}
                textAnchor={index === 0 ? 'start' : index === count - 1 ? 'end' : 'middle'}
              >
                {shortDate(date)}
              </text>
            ) : null,
          )}

          <path d={area(points)} fill={`url(#${wash})`} />
          <path d={monotonePath(points)} fill="none" stroke={`rgb(${paint.channels} / 90%)`} strokeWidth="1.4" />

          {points.length > 0 ? (
            <circle cx={points[points.length - 1]!.x} cy={points[points.length - 1]!.y} r="2.6" fill={paint.light} />
          ) : null}

          {hover !== null ? (
            <g>
              <line className={s.chartGuide} x1={xOf(hover)} x2={xOf(hover)} y1={PAD_TOP} y2={floorY} />
              <circle cx={xOf(hover)} cy={yOf(values[hover] ?? 0)} r="3.4" fill={paint.light} />
            </g>
          ) : null}
        </svg>
      ) : null}

      {hover !== null && hovered ? (
        <div
          className={s.chartTip}
          style={tipOnLeft ? { right: width - tipLeft + 10 } : { left: tipLeft + 10 }}
        >
          <span className={s.chartTipTitle}>{shortDate(hovered)}</span>
          <span className={s.chartTipLine}>{tip(values[hover] ?? 0)}</span>
        </div>
      ) : null}
    </div>
  )
}
