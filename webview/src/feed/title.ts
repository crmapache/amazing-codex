/**
 * An instant tab name out of the first message - a heuristic stand-in until the real title from the LLM
 * arrives (see sessionTitle in protocol.ts), and the fallback if that call did not work out. The same
 * logic as in ClaudeHistory.kt on the plugin's side (for the titles in the history panel) - keeping them
 * word for word identical is not required, but the result has to be recognisably the same for one and
 * the same message.
 */

import { withoutShellText } from './bash'
import { columns } from '../catalog'
import type { SearchHit, TitleSource } from '../protocol'

const IMAGE_PLACEHOLDER = /\[Image #\d+]/g
const MULTIPLE_SPACES = / {2,}/g

const stripImageTags = (line: string): string => line.replace(IMAGE_PLACEHOLDER, ' ').replace(MULTIPLE_SPACES, ' ').trim()

const isAttachmentLine = (line: string): boolean => line.startsWith('@') || line.startsWith('> ')

const truncateAtWord = (text: string, max: number): string => {
  if (text.length <= max) return text
  const cut = text.slice(0, max)
  const lastSpace = cut.lastIndexOf(' ')
  return `${lastSpace > 0 ? cut.slice(0, lastSpace) : cut}…`
}

/**
 * Joins the meaningful lines of the first message into one - a short first line ("Right") must not
 * become the whole tab name when the substance of the question is written a line below. Attachments
 * (`@path`, a quote, an `[Image #N]` even mid-sentence) and bash-mode output are cut out of the name as
 * noise.
 */
export const deriveSessionTitle = (text: string, max = 60): string => {
  const rawLines = withoutShellText(text)
    .split('\n')
    .filter((line) => line.trim().length > 0)
  const meaningful = rawLines.map(stripImageTags).filter((line) => line.length > 0 && !isAttachmentLine(line))

  const joined = (meaningful.length > 0 ? meaningful : rawLines.slice(-1)).join(' ').trim()

  return truncateAtWord(joined, max)
}

/**
 * The longest model's name a tab opened from the history keeps - the ceiling the person's own names have
 * (SessionTitle.OWN_MAX_LENGTH). The CLI names a conversation in a few words; this is only there for an
 * old transcript whose "name" was a sentence.
 */
const MODEL_TITLE_MAX = 100

/**
 * The name, and its rank, a past conversation opens in a tab with - picked from the history list or
 * reached from a search hit.
 *
 * A name the person gave travels exactly as they typed it. Worked over like a guess it came out cut and
 * short of a line starting with "@", and the tab hands its name back to the conversation's transcript with
 * the next message (see CodexSession.nameAfterPerson) - the cut would have been written over the name
 * itself. A name with no rank said is the model's, as it always was.
 *
 * The model's name is not cut at a width: the same conversation open live wore it whole, and from the
 * history it came back as "Транскрипция русского с английскими…" - cut at forty characters for a tab that
 * now grows, cuts in the middle and shows the whole name on hover (see TabTitle in Header). A guess gets
 * the length a live tab's guess gets.
 */
export const resumedTitle = (
  title: string,
  source: TitleSource | undefined,
): { title: string; titleSource: TitleSource } => {
  if (source === 'user') return { title, titleSource: 'user' }
  if (source === 'heuristic') return { title: deriveSessionTitle(title), titleSource: 'heuristic' }

  return { title: deriveSessionTitle(title, MODEL_TITLE_MAX), titleSource: 'llm' }
}

/** Kana and the CJK ideographs: text written without spaces, where a word is not found by looking for one. */
const UNSPACED_SCRIPT = /[぀-ヿ㐀-䶿一-鿿豈-﫿]/

/**
 * A last word wider than this is a name or a path, and kept whole it would take the tab over. In columns
 * rather than characters, as everything measured here: an ideograph is drawn two wide (see columns).
 */
const TAIL_MAX = 14

/** How many characters of a title written without spaces stay visible at its end - about two words. */
const UNSPACED_TAIL = 4

/** Below this a title is short enough that cutting it in two has nothing to win. */
const SPLIT_FROM = 16

/**
 * A tab's name in two parts, so that a narrow tab cuts it in the middle rather than at the end: the head
 * gives way to an ellipsis first and the tail stays (see TabTitle in Header). "Refactor auth module" and
 * "Refactor auth tests" cut at the end read the same; cut in the middle they read "Refactor a…module" and
 * "Refactor a…tests" - the end is what tells conversations with the same beginning apart.
 *
 * The tail is the last word with the space before it, so the two parts side by side are the name exactly
 * and a cut one reads "Refactor auth mod… tests": the ellipsis on the word it cut, the last word whole. A last word too long to keep (a path, a function's name) gives no tail, and the
 * name is cut at the end as before. Chinese and Japanese are written without spaces, and there the tail is
 * the last few characters.
 */
export const splitTabTitle = (title: string): { head: string; tail: string } => {
  const whole = { head: title, tail: '' }
  if (columns(title) < SPLIT_FROM) return whole

  const chars = Array.from(title)
  const space = title.lastIndexOf(' ')
  if (space > 0) {
    const tail = title.slice(space + 1)
    const width = columns(tail)
    if (width === 0 || width > TAIL_MAX || title.slice(0, space).trim() === '') return whole
    return { head: title.slice(0, space), tail: title.slice(space) }
  }

  if (!UNSPACED_SCRIPT.test(title)) return whole
  return { head: chars.slice(0, -UNSPACED_TAIL).join(''), tail: chars.slice(-UNSPACED_TAIL).join('') }
}

/**
 * Where a search hit's title came from - see SearchHit.titleSource. An IDE built before that field says
 * only whether the title is a guess, and a phone may well be talking to one.
 */
export const searchHitTitleSource = (hit: Pick<SearchHit, 'named' | 'titleSource'>): TitleSource =>
  hit.titleSource ?? (hit.named ? 'llm' : 'heuristic')
