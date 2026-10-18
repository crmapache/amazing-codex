import { describe, expect, it } from 'vitest'
import { TYPING_WINDOW_MS, isLeak, isTyping, servesPaste, typedNothing } from './clipboardKeys'

describe('servesPaste', () => {
  it('serves a paste nobody pressed a key for - that is the context menu', () => {
    expect(servesPaste(null, 1_000)).toBe(true)
  })

  it('refuses a paste that follows a key press', () => {
    // Shift+V on Linux: the press crosses into the browser carrying a Ctrl nobody held, and comes back as
    // a paste. Filling that in is how the clipboard landed in a message by itself.
    expect(servesPaste({ at: 1_000, text: 'V' }, 1_010)).toBe(false)
  })

  it('refuses it whatever the press was: a real paste shortcut is taken by the IDE before the browser', () => {
    // 0.13.29 served pastes after a press that looked like Ctrl+Shift+V, and a phantom Ctrl on a Shift+V
    // looks exactly like that.
    expect(servesPaste({ at: 1_000, text: 'v' }, 1_010)).toBe(false)
    expect(servesPaste({ at: 1_000, text: '' }, 1_010)).toBe(false)
  })

  it('stops blaming a press once it is long past', () => {
    expect(servesPaste({ at: 1_000, text: 'V' }, 1_000 + TYPING_WINDOW_MS + 1)).toBe(true)
  })
})

describe('typedNothing', () => {
  it('is true for a press that put no character in', () => {
    expect(typedNothing(1_000, null)).toBe(true)
    expect(typedNothing(1_000, 900)).toBe(true)
  })

  it('is false for a press that turned out to type a character', () => {
    expect(typedNothing(1_000, 1_005)).toBe(false)
  })

  it('ignores typing that comes long after the press', () => {
    expect(typedNothing(1_000, 1_000 + TYPING_WINDOW_MS + 1)).toBe(true)
  })
})

describe('isLeak', () => {
  const owned = { at: 1_000, text: 'v' }

  it("swallows the paste shortcut's own letter right after the press", () => {
    // Ubuntu, Wayland, German layout: Ctrl+V also typed a "v", and the "v" cost the paste.
    expect(isLeak(owned, 'v', 1_005)).toBe(true)
    expect(isLeak({ at: 1_000, text: 'V' }, 'V', 1_005)).toBe(true)
    expect(isLeak(owned, 'V', 1_005)).toBe(true)
  })

  it('lets any other text through', () => {
    expect(isLeak(owned, 'x', 1_005)).toBe(false)
    expect(isLeak(owned, 'vv', 1_005)).toBe(false)
    expect(isLeak(owned, null, 1_005)).toBe(false)
  })

  it('lets the letter through once the press is long past', () => {
    expect(isLeak(owned, 'v', 1_000 + TYPING_WINDOW_MS + 1)).toBe(false)
  })

  it('has nothing to swallow without a press, or for a key that writes nothing', () => {
    expect(isLeak(null, 'v', 1_005)).toBe(false)
    expect(isLeak({ at: 1_000, text: '' }, 'v', 1_005)).toBe(false)
  })
})

describe('isTyping', () => {
  it('counts what a keyboard and an input method put in', () => {
    expect(isTyping('insertText')).toBe(true)
    expect(isTyping('insertCompositionText')).toBe(true)
    expect(isTyping('insertLineBreak')).toBe(true)
  })

  it('does not count the paste itself', () => {
    expect(isTyping('insertFromPaste')).toBe(false)
    expect(isTyping('insertFromPasteAsQuotation')).toBe(false)
  })

  it('does not count deleting', () => {
    expect(isTyping('deleteContentBackward')).toBe(false)
  })
})
