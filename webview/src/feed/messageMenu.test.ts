import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import { messageMenu } from './messageMenu'

const everything = { reuse: 0, pin: { pinned: false, full: false }, rewind: 'yes' as const }

describe('the menu behind the dots on a message of one\'s own', () => {
  it('puts the message back first and rewinds last', () => {
    expect(messageMenu(en, everything).map((option) => option.id)).toEqual(['reuse', 'pin', 'rewind'])
  })

  it('offers only what the card was handed', () => {
    expect(messageMenu(en, { reuse: null, pin: null, rewind: null })).toEqual([])
    expect(messageMenu(en, { ...everything, reuse: null, rewind: null }).map((option) => option.id)).toEqual(['pin'])
  })

  it('says before the press that a pasted picture will not come back', () => {
    expect(messageMenu(en, everything)[0]?.sub).toBeUndefined()
    expect(messageMenu(en, { ...everything, reuse: 2 })[0]?.sub).toBe(en.feed.reuse.lostImages(2))
  })

  it('keeps a pin it cannot place, dead, with the reason under it', () => {
    const pin = messageMenu(en, { ...everything, pin: { pinned: false, full: true } })[1]
    expect(pin?.disabled).toBe(true)
    expect(pin?.sub).toBe(en.feed.pin.crowded)
  })

  it('takes a pin off even with the strip full', () => {
    const pin = messageMenu(en, { ...everything, pin: { pinned: true, full: true } })[1]
    expect(pin?.label).toBe(en.feed.pin.remove)
    expect(pin?.disabled).toBeUndefined()
  })

  it('keeps a rewind into a running turn, dead, and says where to rewind instead', () => {
    const rewind = messageMenu(en, { ...everything, rewind: 'steering' })[2]
    expect(rewind?.disabled).toBe(true)
    expect(rewind?.sub).toBe(en.feed.rewind.steering)
  })

  it('has no rewind for a message there is no name to cut by', () => {
    expect(messageMenu(en, { ...everything, rewind: 'unnamed' }).map((option) => option.id)).toEqual(['reuse', 'pin'])
  })
})
