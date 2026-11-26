import { describe, expect, it } from 'vitest'
import type { StatisticsData, StatisticsDay } from '../protocol'
import {
  barCount,
  barSpan,
  bestStreak,
  currentStreak,
  daysTile,
  factsTile,
  filesTile,
  heatLevel,
  heatMap,
  outputTile,
  projectRows,
  projectsSpending,
  rangeOf,
  tokenBars,
  tokenSummary,
  tokenTile,
  toolRows,
  workSentence,
} from './compute'

const TODAY = '2026-08-26'

const day = (date: string, overrides: Partial<StatisticsDay> = {}): StatisticsDay => ({
  date,
  minutes: 0,
  ...overrides,
})

/** A day at work: some minutes in the panel and a reply, so it counts as worked. */
const busy = (date: string, minutes: number, overrides: Partial<StatisticsDay> = {}) =>
  day(date, { minutes, turns: 1, ...overrides })

/** A day's tokens with the named hours filled in. */
const spent = (tokens: Record<number, number>): number[] => Array.from({ length: 24 }, (_, hour) => tokens[hour] ?? 0)

type Project = { key: string; name: string; tokens: Record<string, number> }

const data = (
  days: StatisticsDay[],
  tokens: Record<string, number[]> = {},
  others: Project[] = [],
): StatisticsData => ({
  // Noon by the clock of whoever runs the test: the hours after it are still ahead.
  now: new Date(2026, 7, 26, 12, 0).getTime(),
  since: Date.parse('2026-06-01T00:00:00Z'),
  today: TODAY,
  ide: 'WebStorm',
  devicesPaired: 0,
  project: { key: 'p-this', name: 'amazing-claude-code' },
  projects: [
    {
      key: 'p-this',
      name: 'amazing-claude-code',
      tokens: Object.fromEntries(Object.entries(tokens).map(([date, hours]) => [date, hours.reduce((a, b) => a + b, 0)])),
    },
    ...others,
  ],
  days,
  tokens,
  achievements: [],
})

describe('the ranges', () => {
  it('counts a week and a month back from today, today included', () => {
    const empty = data([])
    expect(rangeOf(empty, '7d')).toMatchObject({ from: '2026-08-20', to: TODAY, days: 7, tag: '7D' })
    expect(rangeOf(empty, '30d')).toMatchObject({ from: '2026-07-28', to: TODAY, days: 30, tag: '30D' })
  })

  it('is today alone for a single day, and says so rather than "last 1 days"', () => {
    const range = rangeOf(data([]), '1d')
    expect(range).toMatchObject({ from: TODAY, to: TODAY, days: 1, tag: 'TODAY' })
    expect(range.label).toBe('today')
  })

  it('starts all time at the first recorded day - a day of the book or a day of tokens', () => {
    // The transcripts reach back past the day the plugin was installed.
    const set = data([busy('2026-08-01', 30)], { '2026-05-10': spent({ 9: 5 }) })
    expect(rangeOf(set, 'all')).toMatchObject({ from: '2026-05-10', to: TODAY, days: 109, tag: 'ALL' })
    expect(rangeOf(set, 'all').label).toBe('since May 10, 2026')
  })

  it('all time on an empty book is today alone', () => {
    expect(rangeOf(data([]), 'all')).toMatchObject({ from: TODAY, to: TODAY, days: 1 })
  })
})

describe('the tokens tile', () => {
  it('sums the range against the stretch before it, and names the projects that spent them', () => {
    const set = data(
      [],
      { '2026-08-10': spent({ 9: 40 }), '2026-08-18': spent({ 9: 30 }), '2026-08-25': spent({ 9: 60, 14: 40 }) },
      [
        { key: 'p-relay', name: 'relay', tokens: { '2026-08-25': 10 } },
        { key: 'p-old', name: 'old', tokens: { '2026-07-01': 500 } },
      ],
    )
    const tile = tokenTile(set, rangeOf(set, '7d'))

    expect(tile.tokens).toBe(100)
    // The week before - Aug 13 to 19 - held the 30 of Aug 18.
    expect(tile.delta).toBe(70)
    expect(tile.busiestHour).toBe(9)
    // This project and relay spent inside the week; the one that only ever worked in July does not count.
    expect(tile.projects).toBe(2)
    expect(projectsSpending(set, rangeOf(set, '7d'))).toBe(2)
  })

  it('has no delta when the stretch before reaches past the first day the transcripts know', () => {
    // A week before Aug 20 is a week the CLI has deleted, not a week of nothing.
    const set = data([], { '2026-08-20': spent({ 9: 30 }), '2026-08-25': spent({ 9: 60 }) })
    expect(tokenTile(set, rangeOf(set, '7d')).delta).toBeNull()
  })

  it('has no delta for all time, and nothing busiest on a quiet book', () => {
    const set = data([busy('2026-08-25', 60)])
    expect(tokenTile(set, rangeOf(set, 'all'))).toMatchObject({ tokens: 0, delta: null, busiestHour: null, projects: 0 })
  })
})

describe('days at work', () => {
  it('counts the streak up to yesterday and the best run ever', () => {
    const dates = ['2026-08-01', '2026-08-02', '2026-08-03', '2026-08-04', '2026-08-24', '2026-08-25']
    expect(bestStreak(dates)).toBe(4)
    expect(currentStreak(dates, TODAY)).toBe(2)
    expect(currentStreak(['2026-08-20'], TODAY)).toBe(0)
  })

  it('lays out a fortnight of dots ending today', () => {
    const set = data([busy('2026-08-25', 10), busy('2026-08-13', 10), busy('2026-08-12', 10)])
    const tile = daysTile(set, rangeOf(set, '30d'))

    expect(tile.dots).toHaveLength(14)
    expect(tile.dots[0]).toEqual({ date: '2026-08-13', active: true })
    expect(tile.dots[13]).toEqual({ date: TODAY, active: false })
    expect(tile.active).toBe(3)
    expect(tile.total).toBe(30)
    expect(tile.streak).toBe(1)
    expect(tile.best).toBe(2)
  })
})

describe('what came out of it', () => {
  it('adds the turns, sessions, files and forks of the range', () => {
    const set = data([
      busy('2026-08-25', 10, { turns: 12, sessions: 2, filesTouched: 5, forks: 1 }),
      busy('2026-07-01', 10, { turns: 100, sessions: 9, filesTouched: 50, forks: 9 }),
    ])
    expect(outputTile(set, rangeOf(set, '30d'))).toEqual({ turns: 12, sessions: 2, filesTouched: 5, forks: 1 })
  })
})

describe('the tokens over a range', () => {
  it('adds the days up and names the busiest', () => {
    const set = data([], { '2026-08-25': spent({ 9: 400_000_000, 14: 1_200_000_000 }), [TODAY]: spent({ 10: 50_000_000 }) })
    const summary = tokenSummary(set, rangeOf(set, '7d'))

    expect(summary.total).toBe(1_650_000_000)
    expect(summary.busiest).toEqual({ date: '2026-08-25', tokens: 1_600_000_000 })
    // The transcripts begin on the 25th: the five days before them are not five days of nothing.
    expect(summary.average).toEqual({ tokens: 825_000_000, from: '2026-08-25', days: 2 })
  })

  it('counts a quiet day inside the known stretch as a day', () => {
    const set = data([], { '2026-08-20': spent({ 9: 300 }), '2026-08-26': spent({ 9: 400 }) })
    expect(tokenSummary(set, rangeOf(set, '7d')).average).toEqual({ tokens: 100, from: '2026-08-20', days: 7 })
  })

  it('has nothing to name for an IDE that sends no tokens', () => {
    const set = data([busy('2026-08-25', 90)])
    expect(tokenSummary(set, rangeOf(set, '7d'))).toEqual({ total: 0, busiest: null, average: null })
  })
})

describe('the tokens as bars', () => {
  it('keeps today and the week by the hour, however narrow the panel', () => {
    const set = data([])
    expect(barSpan(rangeOf(set, '1d'), 100)).toBe(1)
    expect(barSpan(rangeOf(set, '7d'), 120)).toBe(1)
    expect(tokenBars(set, rangeOf(set, '7d'), 120).bars).toHaveLength(168)
  })

  it('takes the finest span whose bars stay wide enough for thirty days', () => {
    const range = rangeOf(data([]), '30d')
    // 720 hours: 1 000 px holds 200 bars of five pixels - four hours a bar is 180 of them.
    expect(barSpan(range, 1000)).toBe(4)
    expect(barSpan(range, 600)).toBe(6)
    expect(barSpan(range, 300)).toBe(12)
    expect(barSpan(range, 160)).toBe('day')
    expect(barSpan(range, 80)).toBe('2 days')
    expect(barSpan(range, 30)).toBe('week')
    expect(barSpan(range, 15)).toBe('2 weeks')
  })

  it('coarsens a long history step by step, out to months', () => {
    const set = data([], { '2025-08-27': spent({ 9: 1 }) })
    const range = rangeOf(set, 'all')
    expect(range.days).toBe(365)
    expect(barCount(range, '3 days')).toBe(123)
    expect(barCount(range, 'week')).toBe(53)
    expect(barCount(range, '2 weeks')).toBe(27)
    expect(barCount(range, 'month')).toBe(13)
    expect(barSpan(range, 2000)).toBe('day')
    expect(barSpan(range, 700)).toBe('3 days')
    expect(barSpan(range, 300)).toBe('week')
    expect(barSpan(range, 150)).toBe('2 weeks')
    expect(barSpan(range, 100)).toBe('month')
  })

  it('keeps the same days in a bar from one day to the next', () => {
    // Two days a bar, counted from the epoch rather than from the range: Aug 25 and 26 share a bar today,
    // and would tomorrow too.
    const set = data([], { '2026-08-25': spent({ 9: 1 }), [TODAY]: spent({ 9: 2 }) })
    const bars = tokenBars(set, rangeOf(set, '30d'), 80)
    expect(bars.span).toBe('2 days')
    expect(bars.bars[bars.bars.length - 1]).toMatchObject({ from: '2026-08-25', to: TODAY, tokens: 3 })
  })

  it('adds the hours of a bar up and lights the busiest', () => {
    const set = data([], { '2026-08-25': spent({ 9: 10, 10: 30, 22: 5 }), [TODAY]: spent({ 1: 7 }) })
    const bars = tokenBars(set, rangeOf(set, '30d'), 1000)

    expect(bars.span).toBe(4)
    // Aug 25 is the 29th day of the thirty: its 08:00-12:00 bar holds 9 and 10 o'clock.
    const busiest = bars.bars[bars.peak!]!
    expect(busiest).toMatchObject({ from: '2026-08-25', to: '2026-08-25', hour: 8, end: 12, tokens: 40, ahead: false })
    expect(bars.bars.reduce((total, bar) => total + bar.tokens, 0)).toBe(52)
  })

  it('puts a week of days into one bar, Monday to Sunday, cut at the ends of the range', () => {
    const set = data([], { '2026-08-17': spent({ 9: 1 }), '2026-08-23': spent({ 9: 2 }), '2026-08-24': spent({ 9: 4 }) })
    const bars = tokenBars(set, rangeOf(set, '30d'), 30)

    expect(bars.span).toBe('week')
    const last = bars.bars[bars.bars.length - 1]!
    expect(last).toMatchObject({ from: '2026-08-24', to: TODAY, tokens: 4 })
    expect(bars.bars[bars.bars.length - 2]).toMatchObject({ from: '2026-08-17', to: '2026-08-23', tokens: 3 })
    // The range begins on Tuesday Jul 28: the first week is cut short, as the last one is.
    expect(bars.bars[0]!.from).toBe('2026-07-28')
  })

  it('leaves the hours after now ahead, and the hour under way among the past', () => {
    const set = data([], { [TODAY]: spent({ 9: 5 }) })
    const bars = tokenBars(set, rangeOf(set, '1d'), 500)

    expect(bars.bars).toHaveLength(24)
    expect(bars.bars[12]!.ahead).toBe(false)
    expect(bars.bars[13]!.ahead).toBe(true)
    expect(bars.peak).toBe(9)
    expect(tokenBars(data([]), rangeOf(data([]), '1d'), 500).peak).toBeNull()
  })

  it('marks the bars that lie wholly on a weekend, and none on a single day', () => {
    const set = data([])
    // Aug 20 to 26, 2026: Thursday to Wednesday, so Saturday the 22nd and Sunday the 23rd.
    const week = tokenBars(set, rangeOf(set, '7d'), 700).bars
    expect(week.filter((bar) => bar.weekend).map((bar) => bar.from)).toEqual([
      ...Array.from({ length: 24 }, () => '2026-08-22'),
      ...Array.from({ length: 24 }, () => '2026-08-23'),
    ])

    // A week to a bar holds Monday to Friday too: not a weekend.
    expect(tokenBars(set, rangeOf(set, '30d'), 30).bars.some((bar) => bar.weekend)).toBe(false)
    // Today alone, whatever day it is, has nothing to stand out from.
    const saturday = { ...data([]), today: '2026-08-22', now: new Date(2026, 7, 22, 12).getTime() }
    expect(tokenBars(saturday, rangeOf(saturday, '1d'), 500).bars.some((bar) => bar.weekend)).toBe(false)
  })

  it('names the hours of a day, the days of a week, and fewer days when they do not fit', () => {
    const set = data([])
    expect(tokenBars(set, rangeOf(set, '1d'), 500).ticks.map((tick) => tick.label)).toEqual(['00:00', '06:00', '12:00', '18:00'])

    const week = tokenBars(set, rangeOf(set, '7d'), 700).ticks
    expect(week.map((tick) => tick.label)).toEqual(['Aug 20', 'Aug 21', 'Aug 22', 'Aug 23', 'Aug 24', 'Aug 25', 'Aug 26'])
    expect(week[1]).toMatchObject({ start: 24, count: 24 })

    // Seven names of 52 px want 364: in 200 px every second day is named.
    expect(tokenBars(set, rangeOf(set, '7d'), 200).ticks.map((tick) => tick.label)).toEqual(['Aug 20', 'Aug 22', 'Aug 24', 'Aug 26'])
  })
})

describe('when you work', () => {
  it('paints the busiest day darkest and the quiet ones by their share of it', () => {
    expect(heatLevel(0, 100)).toBe(0)
    expect(heatLevel(20, 100)).toBe(1)
    expect(heatLevel(50, 100)).toBe(2)
    expect(heatLevel(70, 100)).toBe(3)
    expect(heatLevel(100, 100)).toBe(4)
  })

  it('ends the calendar on the coming Sunday, with the days ahead marked as such', () => {
    const map = heatMap(data([], { '2026-08-25': spent({ 9: 40 }) }))
    const newest = map.weeks[0]!
    // Aug 26, 2026 is a Wednesday: the newest week runs Mon Aug 24 to Sun Aug 30.
    expect(newest[0]!.date).toBe('2026-08-24')
    expect(newest[6]!.date).toBe('2026-08-30')
    expect(newest[6]!.ahead).toBe(true)
    expect(newest[1]).toMatchObject({ date: '2026-08-25', tokens: 40, level: 4, ahead: false })
    expect(map.weeks).toHaveLength(53)
  })

  it('names the days and the hours the tokens land on', () => {
    const work = spent({ 10: 40, 11: 40, 12: 40 })
    const tokens = {
      '2026-08-18': work, // Tuesday
      '2026-08-19': work, // Wednesday
      '2026-08-20': work, // Thursday
      '2026-08-22': spent({ 22: 5 }), // Saturday, barely
    }
    expect(workSentence(tokens)).toBe('Most of your work lands Tue–Thu, between 10:00 and 13:00.')
  })

  it('has a sentence for an empty calendar too', () => {
    expect(workSentence({})).toMatch(/Nothing here yet/)
  })
})

describe('what the agent did', () => {
  it('lists the five busiest tools against the busiest', () => {
    const set = data([
      busy('2026-08-25', 10, { tools: { Read: 10, Edit: 5, Bash: 4, Grep: 3, Write: 1, Glob: 1 } }),
      busy('2026-08-24', 10, { tools: { Read: 10 } }),
    ])
    const rows = toolRows(set, rangeOf(set, '30d'))

    expect(rows.map((row) => row.name)).toEqual(['Read', 'Edit', 'Bash', 'Grep', 'Glob'])
    expect(rows[0]).toMatchObject({ count: 20, share: 1, tone: 'neutral' })
    expect(rows[1]).toMatchObject({ count: 5, share: 0.25, tone: 'edit' })
    expect(rows[2]?.tone).toBe('command')
  })
})

describe('where the tokens went', () => {
  it('ranks the projects by their tokens in the range and marks this one', () => {
    const set = data(
      [],
      { '2026-08-25': spent({ 9: 60 }) },
      [
        { key: 'p-relay', name: 'relay', tokens: { '2026-08-25': 120, '2026-01-01': 999 } },
        { key: 'p-idle', name: 'idle', tokens: { '2026-01-01': 5 } },
      ],
    )
    const rows = projectRows(set, rangeOf(set, '30d'))

    expect(rows.map((row) => row.name)).toEqual(['relay', 'amazing-claude-code'])
    expect(rows[0]).toMatchObject({ tokens: 120, share: 1, current: false })
    expect(rows[1]).toMatchObject({ tokens: 60, share: 0.5, current: true })
  })
})

describe('files', () => {
  it('adds the lines up and keeps the biggest edit of the range', () => {
    const set = data([
      busy('2026-08-25', 10, {
        linesAdded: 100,
        linesRemoved: 40,
        filesTouched: 3,
        editsRefused: 1,
        biggestEdit: 80,
      }),
      busy('2026-08-24', 10, { linesAdded: 20, biggestEdit: 30 }),
    ])
    expect(filesTile(set, rangeOf(set, '30d'))).toEqual({
      added: 120,
      removed: 40,
      touched: 3,
      refused: 1,
      biggest: 80,
    })
  })
})

describe('sessions, models, forks', () => {
  it('adds the turns up and shares out the models', () => {
    const set = data([
      busy('2026-08-25', 10, {
        turns: 4,
        sessions: 2,
        forks: 1,
        maxDepth: 2,
        models: { Sonnet: 3, Opus: 1 },
        cost: 1.5,
      }),
    ])
    const tile = factsTile(set, rangeOf(set, '30d'))

    expect(tile).toMatchObject({ sessions: 2, turns: 4, forks: 1 })
    expect(tile.models).toEqual([
      { name: 'Sonnet', turns: 3, share: 0.75 },
      { name: 'Opus', turns: 1, share: 0.25 },
    ])
    expect(tile.cost).toBe(1.5)
    expect(tile.deepestChain).toBe(2)
  })
})
