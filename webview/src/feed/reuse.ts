import { isPastedImage, kindOfPath } from './reference'
import type { UserItem, UserToken } from './types'

/**
 * A sent message, taken back into the input field to be corrected and sent again.
 *
 * This is what the first request for it asked for in different words: somebody drafts their prompts in a
 * text file, pastes them into the panel, and on noticing a slip has no way back to what they sent - the
 * card in the feed is text on a screen, and retyping the attachments by hand is the part that hurts.
 * Copying it out is only half an answer: a path pasted back into the field is a path, not a chip, and an
 * image has no text form at all.
 *
 * So the message goes back as the pieces it was made of. Almost all of them survive the trip untouched -
 * a file, a folder, a command, a quote and a paste each carry everything they need inside the chip. The
 * one exception is a pasted image, and it is the reason this file exists rather than a one-line map.
 */
export interface Reuse {
  tokens: UserToken[]
  /**
   * How many pasted images cannot come back. Their bytes live in the feed only while the panel that sent
   * them is the panel on screen: a conversation read off the disk is drawn from its text, where a pasted
   * picture is the caption "[Image #2]" and nothing else (see replayedMessage). The bytes do stand in the
   * message's line of the transcript, but the IDE hands them out only shrunk, for a preview under the
   * pointer (see imagePreview.ts) - not as the picture to send again.
   *
   * Counted rather than passed on silently, because silence here is a lie the person acts on: a message
   * whose picture is gone looks complete in the field, goes out with a caption pointing at nothing, and
   * gets an answer about an image the agent was never shown.
   */
  lostImages: number
}

/**
 * The message as the field can take it back.
 *
 * A picture picked in the IDE's dialog - or read back off the transcript as `@design/sheet.png` - goes back
 * as itself: the chip is its own path, and it travels as `@path` again. A pasted image with its bytes still
 * here goes back as itself too - it will travel as an attachment again. One that has only the file the shell
 * wrote under it (see PastedFiles.kt) goes back as a picture of that file: the agent reads it with a tool
 * instead of being handed the bytes, which is a step down but a step that genuinely carries the picture -
 * and it is exactly what copying the message into the clipboard has always given (see clipboardText). One
 * that has neither is left out and counted.
 */
export const reusableMessage = (item: Pick<UserItem, 'tokens'>): Reuse => {
  const tokens: UserToken[] = []
  let lostImages = 0

  for (const token of item.tokens) {
    if (token.kind !== 'chip' || token.chip.kind !== 'img') {
      tokens.push(token)
      continue
    }

    const { chip } = token
    if (chip.data || !isPastedImage(chip)) {
      tokens.push(token)
      continue
    }

    if (chip.path) {
      tokens.push({ kind: 'chip', chip: { kind: kindOfPath(chip.path), value: chip.path } })
      continue
    }

    lostImages += 1
  }

  return { tokens: merge(tokens), lostImages }
}

/**
 * Neighbouring pieces of text become one, and the empty ones go.
 *
 * Needed because of the images left out above: dropping a chip from the middle of a sentence leaves the
 * text on either side of it as two tokens with nothing between them, and the field would draw them as
 * two - with the gap the chip used to fill still there.
 */
const merge = (tokens: UserToken[]): UserToken[] => {
  const merged: UserToken[] = []

  for (const token of tokens) {
    if (token.kind !== 'text') {
      merged.push(token)
      continue
    }

    const last = merged.at(-1)
    if (last?.kind === 'text') merged[merged.length - 1] = { kind: 'text', value: last.value + token.value }
    else merged.push(token)
  }

  return merged.filter((token) => token.kind !== 'text' || token.value !== '')
}
