import type { MenuOption } from '../components/Menu'
import type { Dict } from '../i18n/en'
import type { rewindable } from './rewind'

/** What the menu behind the dots on a card can do (see messageMenu). */
export type MessageAction = 'reuse' | 'pin' | 'rewind'

/**
 * The menu behind the dots on a card at the desk - what used to be three more buttons in the head of a
 * message of one's own, beside the copy that stays there. Each part is null where the card is not handed
 * its action: an answer has the pin alone (a menu of one, asked for so that every card has the same
 * corner), a step log of a scenario cannot be cut or put back, and a card nobody can do anything more
 * with gets no dots at all.
 *
 * Putting back comes first: of everything here it is the one the feedback behind it asked for, the way
 * back to a message to fix it (see feed/reuse.ts). Rewinding comes last, because it is the one that takes
 * something away (the dialog it opens asks first, and says what else goes).
 *
 * What cannot be done right now stays in the menu, dead, with its reason on the second line - a pin with
 * three up already, a message written into a running turn. The rule the head's buttons kept (see
 * PinButton): gone, an entry takes with it the one line that says why and what to do about it. A message
 * the panel never named (sent before it named them) has nothing to rewind by, and no entry.
 *
 * The drawings are the card's to add (see UserCard): they are components, and this stays a plain function
 * a test can call.
 */
export const messageMenu = (
  t: Dict,
  can: {
    /** How many pasted pictures will not come back with it - see reusableMessage. */
    reuse: number | null
    pin: { pinned: boolean; full: boolean } | null
    rewind: ReturnType<typeof rewindable> | null
  },
): (MenuOption & { id: MessageAction })[] => {
  const options: (MenuOption & { id: MessageAction })[] = []

  if (can.reuse !== null) {
    options.push({
      id: 'reuse',
      label: t.feed.reuse.label,
      ...(can.reuse > 0 ? { sub: t.feed.reuse.lostImages(can.reuse) } : {}),
      iconTone: 'dim',
    })
  }

  if (can.pin) {
    const { pinned, full } = can.pin
    const crowded = full && !pinned
    options.push({
      id: 'pin',
      label: pinned ? t.feed.pin.remove : t.feed.pin.add,
      ...(crowded ? { sub: t.feed.pin.crowded, disabled: true } : {}),
      // Lit while pinned, the way the pin left in the head is (see .pinOn): the accent is the panel's
      // colour of "this one is on".
      ...(pinned ? {} : { iconTone: 'dim' as const }),
    })
  }

  if (can.rewind !== null && can.rewind !== 'unnamed') {
    const steering = can.rewind === 'steering'
    options.push({
      id: 'rewind',
      label: t.feed.rewind.label,
      sub: steering ? t.feed.rewind.steering : t.feed.rewind.hint,
      ...(steering ? { disabled: true } : {}),
      iconTone: 'dim',
    })
  }

  return options
}
