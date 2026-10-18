/**
 * Which paste events the clipboard bridge may serve, and which typed letters belong to a paste.
 *
 * Split out of clipboard.ts and held by a test because it breaks silently and on one system only: the
 * bridge exists for Linux alone (see the header of clipboard.ts), so anything wrong here is invisible on
 * a Mac and is reported as "the panel pastes text by itself".
 *
 * That is exactly what happened. Inside the embedded browser on Linux an ordinary Shift+V arrived as a
 * paste command - the modifiers a key press carries across that boundary are not always the ones that
 * were pressed, a Ctrl nobody held among them - and the bridge filled that paste with the IDE's clipboard:
 * a person typing a capital V got yesterday's copied text instead.
 *
 * The same boundary loses a Ctrl that was held (Ubuntu, Wayland, GNOME): Ctrl+V reached the page as a bare
 * "v". So on Linux the paste shortcuts are taken by the IDE, which sees them whole (see PasteKeys.kt), and
 * the page never pastes because of a key press it saw: whatever modifiers a press claims on this side,
 * the claim is the part that cannot be trusted. 0.13.29 let the page take Ctrl+Shift+V as a paste as well,
 * and the capital V came back the moment a phantom Ctrl rode on a Shift+V.
 */

/**
 * How long after a press the character it typed is still considered to belong to it.
 *
 * Generous on purpose: the browser types the character in the same task as the press, and the only thing
 * a wider window costs is that a paste raised within a quarter of a second of typing a letter is refused.
 * Keyboard pastes do not come this way at all (see servesPaste); a refused one is asked for again from the
 * context menu, while a paste nobody asked for is text appearing in a message under somebody's hands. The
 * same window bounds the leaked letter of a paste shortcut (see isLeak): a person does not type that
 * letter again within it.
 */
export const TYPING_WINDOW_MS = 250

/** The last key press, as far as a paste is concerned. */
export interface LastKey {
  at: number
  /** The character the press writes, if it writes one - what has to be put back when it is swallowed. */
  text: string
}

/**
 * Whether a paste the browser raised should be filled in from the IDE's clipboard.
 *
 * A paste with no key press just before it is served: that is the context menu, or a middle click, and it
 * is a real paste. A paste on the heels of a key press is refused whatever the press claimed to be: a real
 * paste shortcut never reaches the browser on the bridge, the IDE takes it first (see PasteKeys.kt), so a
 * paste here is the browser reading a press through a modifier nobody held.
 */
export const servesPaste = (last: LastKey | null, now: number): boolean =>
  last === null || now - last.at > TYPING_WINDOW_MS

/**
 * Whether a press put no character into the field.
 *
 * A character the browser swallowed in favour of a paste it invented is put back only when nothing typed
 * it after all, so that a letter never arrives twice.
 *
 * A character typed later than the window is somebody carrying on writing, and says nothing about the
 * press.
 */
export const typedNothing = (keyAt: number, typedAt: number | null): boolean =>
  typedAt === null || typedAt < keyAt || typedAt - keyAt > TYPING_WINDOW_MS

/** A paste shortcut the IDE has taken, and the letter that press may still leak into the page. */
export interface OwnedPress {
  at: number
  /** What the key writes on its own - empty for Insert, which writes nothing. */
  text: string
}

/**
 * Whether text going into a field is the letter leaked by a paste shortcut the IDE already took.
 *
 * The IDE swallows the typed key after a press it handled, but the letter may come down another road (an
 * input method's commit, say), so it is checked for here as well. Only the press's own letter, either
 * case, and only within the window - anything else is somebody writing.
 */
export const isLeak = (owned: OwnedPress | null, data: string | null, now: number): boolean => {
  if (!owned?.text || !data) return false
  if (now < owned.at || now - owned.at > TYPING_WINDOW_MS) return false

  return data.toLowerCase() === owned.text.toLowerCase()
}

/**
 * Whether an input event counts as typing.
 *
 * Everything that inserts is typing except the paste itself - including composition, which is how an
 * input method types. The bridge's own paste arrives as insertFromPaste and must not be read as somebody
 * at the keyboard.
 */
export const isTyping = (inputType: string): boolean =>
  inputType.startsWith('insert') && !inputType.startsWith('insertFromPaste')
