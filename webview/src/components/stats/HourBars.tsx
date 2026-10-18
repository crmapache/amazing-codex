import { clock } from '../../stats/format'
import type { ChartTone } from './LineChart'
import s from './stats.module.css'

/**
 * The hours of a single day: a bar an hour, midnight on the left, the height what the hour held - the
 * minutes in the panel, the tokens spent.
 *
 * This is what a chart card shows when the range is one day - the line of days has nothing to draw with a
 * single point, whereas the hours say when the work actually happened.
 */

interface HourBarsProps {
  /** All 24 hours, midnight first - the quiet ones included, so the day keeps its shape. */
  values: number[]
  /** The figure at the top of the plot. */
  top: number
  /** The hours lit as the busiest of the day. */
  peaks: number[]
  /** A figure on the scale: "30m", "250M". */
  scale: (value: number) => string
  /** What hovering over an hour says: "09:00 · 1h 00m · busiest hour of the day". */
  tip: (hour: number, value: number) => string
  tone?: ChartTone
}

/** The lines across the plot, as shares of the top - the whole, the half and the floor. */
const LINES = [1, 0.5, 0]
const TICK_EVERY = 6

export const HourBars = ({ values, top, peaks, scale, tip, tone = 'moon' }: HourBarsProps) => (
  <div className={`${s.day} ${tone === 'iris' ? s.dayIris : ''}`}>
    <div className={s.dayPlot}>
      {LINES.map((share) => (
        <span key={share} className={`${s.dayLine} ${share === 0 ? s.dayFloor : ''}`} style={{ bottom: `${share * 100}%` }}>
          <span className={s.dayScale}>{scale(top * share)}</span>
        </span>
      ))}

      <div className={s.dayBars}>
        {values.map((value, hour) => (
          <span key={hour} className={s.dayBar} data-tooltip={tip(hour, value)}>
            <span
              className={`${s.dayBarFill} ${value === 0 ? s.dayBarEmpty : ''}`}
              data-peak={peaks.includes(hour) ? '' : undefined}
              style={{ height: value > 0 ? `${Math.max(3, (value / top) * 100)}%` : undefined }}
            />
          </span>
        ))}
      </div>
    </div>

    <div className={s.dayTicks}>
      {values.map((_, hour) =>
        hour % TICK_EVERY === 0 ? (
          <span
            key={hour}
            className={s.dayTick}
            style={{ gridColumn: hour + 1, justifySelf: hour === 0 ? 'start' : 'center' }}
          >
            {clock(hour)}
          </span>
        ) : null,
      )}
    </div>
  </div>
)
