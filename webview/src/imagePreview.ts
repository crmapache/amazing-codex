import { isOpenablePath } from './feed/paths'
import { isPastedImage } from './feed/reference'
import type { Chip, UserToken } from './feed/types'
import type { PicturePlace, PreviewPlace } from './protocol'

/**
 * The picture behind an attachment chip, shown under the pointer (see components/ImagePreview.tsx).
 *
 * A chip says "Image #3" or `screen.png`, and that is not enough to tell two screenshots apart, or to
 * remember what the message a minute ago was about. The chips stand in three places drawn three different
 * ways - the input field's own DOM, the feed's React, the queue's rows - so the picture is not a part of any
 * of them: each marks its element with where the bytes are (see [markPreview]), and one layer over the
 * whole panel reads the mark, the way the hover hints do (see Tooltips). Getting the bytes is apart from
 * this, in imagePreviewLoad.ts: the field's DOM marks its chips here, and it is tested without a bridge.
 */

/**
 * Where the picture's bytes are: in the page already (a pasted picture carries them in its chip), or
 * somewhere the IDE reads them from (see PreviewPlace).
 *
 * `opens` - a click on the chip opens the picture in the editor, and the preview says so under it. The chip
 * decides that (see ChipView): whether a click opens anything depends on there being an editor at all.
 *
 * `name` - the picture's path, written whole under it: the chip shows the name cut in the middle, and the
 * hover that used to be the one place it stood whole is the picture now. A pasted picture has no name.
 */
export type PreviewSource = ({ data: string } | { place: PreviewPlace }) & { opens?: boolean; name?: string }

/** The mark on a chip with a picture behind it - also what puts the pointer on it (see base.css). */
const ATTRIBUTE = 'data-acc-preview'

export const PREVIEW_SELECTOR = `[${ATTRIBUTE}]`

/**
 * The sources behind the marks. Beside the element rather than in an attribute: a pasted picture is
 * megabytes of base64, and an attribute that size would be copied into the DOM, into every clone a copy
 * of the selection makes, and into anything that serialises the field.
 */
const sources = new WeakMap<Element, PreviewSource>()

/** Mark an element as a picture's chip, or take the mark off when there is no picture to show. */
export const markPreview = (element: HTMLElement, source: PreviewSource | null): void => {
  if (!source) {
    sources.delete(element)
    element.removeAttribute(ATTRIBUTE)
    return
  }

  sources.set(element, source)
  element.setAttribute(ATTRIBUTE, '')
}

export const previewOf = (element: Element): PreviewSource | undefined => sources.get(element)

/**
 * The chips whose last click opened nothing (see pictureUnopened in protocol.ts) - the preview says so under
 * the picture instead of "click to open", until the chip is clicked again and the IDE asked anew: the message
 * may be back in the transcript, the disk may have room again. Beside the element, as the sources are.
 */
const unopened = new WeakSet<Element>()

export const markUnopened = (element: Element, failed: boolean): void => {
  if (failed) unopened.add(element)
  else unopened.delete(element)
}

export const isUnopened = (element: Element): boolean => unopened.has(element)

/**
 * Where the picture behind a chip is - or null for a chip that is no picture.
 *
 * The bytes in the chip first: a pasted picture holds them, and nothing has to be asked. Then a file: a
 * picture picked in the IDE's dialog is a path, and a pasted one has usually been written out by the IDE
 * (see PastedFiles.kt) - a read off disk is the cheapest of the asks. Last, [elsewhere]: what the place
 * the chip is drawn in knows about the message - its bytes in the queue, its line in the transcript.
 */
export const chipPreview = (chip: Chip, elsewhere?: PreviewPlace | null): PreviewSource | null => {
  if (chip.kind !== 'img') return null

  if (chip.data) return { data: chip.data }
  const path = filePath(chip)
  if (path !== null) return path ? { place: { kind: 'file', path }, ...(isPastedImage(chip) ? {} : { name: path }) } : null

  return elsewhere ? { place: elsewhere } : null
}

/**
 * Where the picture a click on the chip opens is - or null for a chip that opens nothing.
 *
 * The file first, the other way round from [chipPreview]: a file is what the editor opens, and a pasted
 * picture usually has one (see PastedFiles.kt). Bytes the page holds come next - the IDE writes them out by
 * their content, so a second click opens the same file - and last what the place the chip is drawn in knows.
 */
export const chipOpening = (chip: Chip, elsewhere?: PreviewPlace | null): PicturePlace | null => {
  if (chip.kind !== 'img') return null

  const path = filePath(chip)
  if (path !== null) return path ? { kind: 'file', path } : null
  if (chip.data) return { kind: 'bytes', data: chip.data }

  return elsewhere ?? null
}

/**
 * The file a picture's chip names: the one the IDE wrote a pasted picture into, or the path a picked one is.
 * Null when it names none, and empty when the one it names is refused - a network path, which the IDE never
 * reaches for (see isOpenablePath), so neither a preview nor a click is promised over it.
 */
const filePath = (chip: Chip): string | null => {
  const path = chip.path || (isPastedImage(chip) ? null : chip.value)
  if (path === null) return null
  return isOpenablePath(path) ? path : ''
}

/**
 * Which of the message's pasted pictures each token is: its place among them, or -1 for anything else.
 *
 * That place is how the queue and the transcript name a picture - both keep the bytes in the order the
 * chips stood in, and neither keeps the chips' captions (the numbering goes on across the conversation,
 * see countSessionImages).
 */
export const pastedOrdinals = (tokens: UserToken[]): number[] => {
  let next = 0
  return tokens.map((token) => (token.kind === 'chip' && isPastedImage(token.chip) ? next++ : -1))
}
