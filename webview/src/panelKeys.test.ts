import { describe, expect, it } from 'vitest'
import { reachesPastMenu } from './panelKeys'

describe('the panel keys over the side menu', () => {
  // The history screen shows "Recently closed", and its first row names the key that brings that tab back.
  it('lets the key that reopens a tab through on the history screen, where its row is', () => {
    expect(reachesPastMenu('reopenTab', 'history', 2)).toBe(true)
  })

  it('keeps every other key, every other screen and an empty list off what lies under the menu', () => {
    expect(reachesPastMenu('reopenTab', 'settings', 2)).toBe(false)
    expect(reachesPastMenu('reopenTab', 'history', 0)).toBe(false)
    expect(reachesPastMenu('closeTab', 'history', 2)).toBe(false)
    expect(reachesPastMenu('newTab', 'history', 2)).toBe(false)
  })
})
