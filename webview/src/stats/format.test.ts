import { describe, expect, it } from 'vitest'
import { addDays, dayNumber, daysBetween, weekday } from './dates'
import {
  clock,
  compactNumber,
  groupThousands,
  longDate,
  money,
  monthYear,
  shortDate,
  shortMonth,
  tokenAmount,
  tokenDelta,
  tokenScale,
} from './format'

describe('figures', () => {
  it('sets thousands off with a narrow space', () => {
    expect(groupThousands(1284)).toBe('1 284')
    expect(groupThousands(18430)).toBe('18 430')
    expect(groupThousands(999)).toBe('999')
    expect(groupThousands(-5962)).toBe('-5 962')
  })

  it('shortens what no longer needs its last digits', () => {
    expect(compactNumber(1284)).toBe('1 284')
    expect(compactNumber(18430)).toBe('18.4k')
    expect(compactNumber(100_000)).toBe('100k')
    expect(compactNumber(41_200_000)).toBe('41.2M')
  })

  it('says tokens in millions, and on a scale as short as it gets', () => {
    // Billions from a thousand millions up, three figures that matter: "57 673M" was digits to count.
    expect(tokenAmount(57_673_000_000)).toBe('57.7B')
    expect(tokenAmount(2_794_300_000)).toBe('2.79B')
    expect(tokenAmount(2_800_000_000)).toBe('2.8B')
    expect(tokenAmount(3_000_000_000)).toBe('3B')
    expect(tokenAmount(138_327_000_000)).toBe('138B')
    expect(tokenAmount(999_400_000)).toBe('999M')
    expect(tokenAmount(586_000_000)).toBe('586M')
    expect(tokenAmount(18_430)).toBe('18.4k')
    expect(tokenScale(0)).toBe('0')
    expect(tokenScale(500_000_000)).toBe('500M')
    expect(tokenScale(1_000_000_000)).toBe('1B')
    expect(tokenScale(1_500_000_000)).toBe('1.5B')
    expect(tokenScale(2_500_000)).toBe('2.5M')
    expect(tokenScale(500_000)).toBe('500k')
  })

  it('says a change of tokens with its sign', () => {
    expect(tokenDelta(2_100_000_000)).toBe('+2.1B')
    expect(tokenDelta(-480_000_000)).toBe('-480M')
    expect(tokenDelta(0)).toBe('±0')
  })

  it('prices in whole dollars once there are enough of them', () => {
    expect(money(974)).toBe('$974')
    expect(money(12.34)).toBe('$12.3')
    expect(money(0.4)).toBe('$0.40')
  })

  it('names dates the way the calendar does', () => {
    expect(shortDate('2026-08-14')).toBe('Aug 14')
    expect(longDate('2026-08-14')).toBe('Aug 14, 2026')
    expect(shortMonth('2026-10-14')).toBe('Oct')
    // January says its year: on a row of months it is where one year turns into the next.
    expect(shortMonth('2027-01-03')).toBe('Jan 2027')
    expect(monthYear('2026-10-14')).toBe('Oct 2026')
    expect(clock(10)).toBe('10:00')
    expect(clock(24)).toBe('00:00')
  })
})

describe('days', () => {
  it('counts days apart as a subtraction and walks the calendar both ways', () => {
    expect(dayNumber('2026-08-26') - dayNumber('2026-08-20')).toBe(6)
    expect(addDays('2026-08-26', -30)).toBe('2026-07-27')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(daysBetween('2026-08-24', '2026-08-26')).toEqual(['2026-08-24', '2026-08-25', '2026-08-26'])
    expect(daysBetween('2026-08-26', '2026-08-24')).toEqual([])
  })

  it('knows a Monday from a Sunday', () => {
    expect(weekday('2026-08-24')).toBe(0)
    expect(weekday('2026-08-26')).toBe(2)
    expect(weekday('2026-08-30')).toBe(6)
  })
})
