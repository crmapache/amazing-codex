import { send } from './bridge'
import { markUnopened, type PreviewSource } from './imagePreview'
import type { PicturePlace, PreviewPlace, ShellMessage } from './protocol'

/**
 * The bytes behind a preview mark (see imagePreview.ts): at once when the page holds them, after a round
 * trip to the IDE otherwise (see previewImage in protocol.ts).
 */

/**
 * What a request for a picture came to: the picture, or the word that there is nothing to show. `size` is the
 * picture's own when the URL holds a smaller copy (see ImagePreviews.fitted); without it the URL is the picture.
 */
export type Loaded = { state: 'ready'; url: string; size?: { width: number; height: number } } | { state: 'missing' }

/**
 * Pictures asked of the IDE and answered, the most recent last.
 *
 * Few of them: a preview is moved over again and again while one message is being looked at, and it is
 * that one message's pictures that are worth keeping - not every picture ever hovered, each up to a
 * megabyte and a half (see ImagePreviews.kt). A miss on disk is not kept at all: the file may be written a
 * moment later, and asking again costs a read of one file. A miss in a transcript is (see missedLines).
 */
const kept = new Map<string, Loaded>()
const KEEP = 8

/**
 * Lines the transcript did not have, and until when that answer stands. A line of a conversation that is
 * not there is not there a moment later either - and finding that out is a pass over a file of tens of
 * megabytes, so a pointer going back over the chip must not ask again. Kept a while rather than for good: a
 * message sent from the phone carries its picture beside the chip, and its line lands a moment after it.
 */
const missedLines = new Map<string, number>()
const MISSED_LINE_KEPT_MS = 30_000

/** Those on their way, by the place - a second hover over the same chip waits for the first answer. */
const waiting = new Map<string, ((result: Loaded) => void)[]>()
/** And the place each request id stands for. */
const asked = new Map<string, string>()
let lastRequest = 0

/**
 * How long an answer is waited for. Generous: a picture out of a working day's transcript is a pass over
 * tens of megabytes. Past it the preview says there is nothing to show, rather than hanging empty.
 */
const ANSWER_TIMEOUT_MS = 15_000

const keyOf = (place: PreviewPlace): string => {
  if (place.kind === 'file') return `file:${place.path}`
  if (place.kind === 'queued') return `queued:${place.sessionId}:${place.id}:${place.image}`
  return `said:${place.sessionId}:${place.uuid}:${place.image}`
}

/** A miss on a transcript's line that still stands (see missedLines). */
const missedLine = (key: string): boolean => {
  const until = missedLines.get(key)
  if (until === undefined) return false
  if (Date.now() < until) return true
  missedLines.delete(key)
  return false
}

/**
 * Whether the picture behind a mark is to hand without asking the IDE: bytes the page holds, a picture kept,
 * a line the transcript was found not to have. The preview asks for anything else only once the pointer has
 * stayed on the chip (see ImagePreview): a pointer swept across a feed of pictures read back off the disk
 * would otherwise send a pass over the transcript for every one of them.
 */
export const isKnown = (source: PreviewSource): boolean => {
  if ('data' in source) return true
  const key = keyOf(source.place)
  return kept.has(key) || missedLine(key)
}

/** The picture behind a mark - at once when the page holds it, after a round trip to the IDE otherwise. */
export const loadPreview = (source: PreviewSource): Promise<Loaded> => {
  if ('data' in source) return Promise.resolve({ state: 'ready', url: source.data })

  const key = keyOf(source.place)
  if (missedLine(key)) return Promise.resolve({ state: 'missing' })
  const hit = kept.get(key)
  if (hit) {
    // To the end of the line: the most recently looked at stays longest.
    kept.delete(key)
    kept.set(key, hit)
    return Promise.resolve(hit)
  }

  return new Promise((resolve) => {
    const list = waiting.get(key)
    if (list) {
      list.push(resolve)
      return
    }

    waiting.set(key, [resolve])
    lastRequest += 1
    const id = `preview-${lastRequest}`
    asked.set(id, key)
    setTimeout(() => answer(id, ''), ANSWER_TIMEOUT_MS)
    send({ type: 'previewImage', id, place: source.place })
  })
}

const answer = (id: string, data: string, size?: { width: number; height: number }): void => {
  const key = asked.get(id)
  if (key === undefined) return
  asked.delete(id)

  const result: Loaded = data ? { state: 'ready', url: data, ...(size ? { size } : {}) } : { state: 'missing' }
  if (result.state === 'missing' && key.startsWith('said:')) missedLines.set(key, Date.now() + MISSED_LINE_KEPT_MS)
  if (result.state === 'ready') {
    kept.set(key, result)
    while (kept.size > KEEP) {
      const oldest = kept.keys().next().value
      if (oldest === undefined) break
      kept.delete(oldest)
    }
  }

  const list = waiting.get(key) ?? []
  waiting.delete(key)
  for (const resolve of list) resolve(result)
}

/** The IDE's answer - called from the shared message handling, as the pasted file's is (see pasted.ts). */
export const resolveImagePreview = (message: Extract<ShellMessage, { type: 'imagePreview' }>): void => {
  const { width, height } = message
  answer(message.id, message.data ?? '', width && height ? { width, height } : undefined)
}

/**
 * The clicks on pictures still to be answered, by request id - the chip each came from, so a picture that did
 * not open says so over the chip that was pressed (see pictureUnopened). A click that opened something is not
 * answered at all, so only the last few are kept: an answer comes back within a moment or not at all.
 */
const clicked = new Map<string, HTMLElement>()
const CLICKS_KEPT = 16
let lastClick = 0

/** A click on a picture's chip: the IDE opens the picture in the editor (see openPicture in protocol.ts). */
export const openPicture = (place: PicturePlace, chip: HTMLElement): void => {
  // Pressed again, the IDE is asked again: the message may be back, the disk may have room.
  markUnopened(chip, false)

  lastClick += 1
  const id = `picture-${lastClick}`
  clicked.set(id, chip)
  while (clicked.size > CLICKS_KEPT) {
    const oldest = clicked.keys().next().value
    if (oldest === undefined) break
    clicked.delete(oldest)
  }

  send({ type: 'openPicture', id, place })
}

/**
 * The IDE's word that a click opened nothing. The chip is marked (see markUnopened), and if the pointer is
 * still on it the preview is shown again at once - it hid on the press - so the click has an answer to see.
 */
export const resolvePictureUnopened = (message: Extract<ShellMessage, { type: 'pictureUnopened' }>): void => {
  const chip = clicked.get(message.id)
  clicked.delete(message.id)
  if (!chip?.isConnected) return

  markUnopened(chip, true)
  if (chip.matches(':hover')) chip.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }))
}
