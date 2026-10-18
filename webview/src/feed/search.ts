import type { SearchHit, SearchScope } from '../protocol'
import type { FeedItem } from './types'
import { tokensText } from './tokens'

/**
 * The rules of the search that break without showing - apart from the screens, so a test can hold
 * them (see Search.tsx for the window, SearchCapsule.tsx for the capsule, App.tsx for the wiring).
 */

/** The window's three tabs: the two scopes of a typed search, and the model's. */
export type SearchTab = SearchScope | 'ai'

/** The tabs in the order they stand - the order the keys number them by and step through. */
export const SEARCH_TABS: readonly SearchTab[] = ['chat', 'project', 'ai']

/**
 * The tab [step] places along from [tab], round the end - where the panel's next and previous tab keys take the
 * window while it is open: the tabs the mouse can reach there are its own (see pressPanelKey in App).
 */
export const searchTabBeside = (tab: SearchTab, step: 1 | -1): SearchTab =>
  SEARCH_TABS[(SEARCH_TABS.indexOf(tab) + step + SEARCH_TABS.length) % SEARCH_TABS.length] ?? tab

/**
 * The tab a digit key stands for (with Alt and Cmd held, see searchKeys in Search.tsx) - by the key's place
 * rather than its character, so a keyboard printing "&" or "é" on it means the same, and so does the keypad.
 */
export const searchTabOfKey = (code: string): SearchTab | undefined => {
  const digit = /^(?:Digit|Numpad)([1-9])$/.exec(code)?.[1]
  return digit ? SEARCH_TABS[Number(digit) - 1] : undefined
}

/**
 * A press of the window's chord off a Mac, held until it is known whether it typed (see the keys in Search.tsx).
 *
 * Off a Mac the chord is Ctrl+Alt, and on Windows that is the right Alt: on a Spanish keyboard with 2 it is "@", on a
 * French one with 3 "#", on a German one with 2 "²", on a Polish one with C "ć" - taken for the chord, the character
 * never reached the field. Nothing in the press says which it is: the IDE's browser carries no AltGraph (its key
 * events know Ctrl, Alt, Shift and Meta alone), and the page, served from no secure origin, is not handed the
 * keyboard's layout to compare the key with - and the key alone lies both ways: a Russian keyboard gives "с" for C,
 * a French one "&" for 1, and neither types anything with Ctrl+Alt. So the browser is let do what it does: a
 * character it puts into the field is the field's ([typed]), and a press it lets go of with nothing typed was the
 * chord, done as its key comes up ([release]).
 */
export const chordWait = () => {
  let held: { code: string; act: () => void } | null = null

  return {
    press: (code: string, act: () => void) => {
      held = { code, act }
    },
    typed: () => {
      held = null
    },
    /** Whether the key coming up was the chord's, and the chord was done. */
    release: (code: string): boolean => {
      const press = held
      if (!press || press.code !== code) return false
      held = null
      press.act()
      return true
    },
  }
}

/** A piece of a snippet: painted or not. */
export interface SnippetPiece {
  text: string
  hit: boolean
}

/**
 * The snippet cut into what is painted and what is not - by the spans the IDE measured on it, which
 * are [start, end) pairs in the snippet's own characters.
 */
export const snippetPieces = (snippet: string, spans: readonly [number, number][]): SnippetPiece[] => {
  const pieces: SnippetPiece[] = []
  let at = 0

  for (const [start, end] of spans) {
    if (start < at || end <= start || end > snippet.length) continue
    if (start > at) pieces.push({ text: snippet.slice(at, start), hit: false })
    pieces.push({ text: snippet.slice(start, end), hit: true })
    at = end
  }

  if (at < snippet.length) pieces.push({ text: snippet.slice(at), hit: false })
  return pieces
}

/** The results of one conversation, under its own heading - see [groupByChat]. */
export interface SearchGroup {
  conversationId: string
  title: string
  /** When that conversation was last spoken in, out of the hits themselves. */
  at: number
  messages: number
  hits: SearchHit[]
}

/**
 * The results grouped by the conversation they are in, best group first.
 *
 * A list that spans conversations reads as one heap without this: three rows about refunds, one from
 * this morning and two from a week ago, with nothing saying they are three different talks. The order
 * is the order the results arrived in - they come sorted by how well they match - so the group holding
 * the best result stands first, and inside it the results keep that same order.
 */
export const groupByChat = (hits: readonly SearchHit[]): SearchGroup[] => {
  const groups: SearchGroup[] = []

  for (const hit of hits) {
    const known = groups.find((group) => group.conversationId === hit.conversationId)
    if (known) {
      known.hits.push(hit)
      known.at = Math.max(known.at, hit.at)
      continue
    }

    groups.push({
      conversationId: hit.conversationId,
      title: hit.title,
      at: hit.at,
      messages: hit.messages,
      hits: [hit],
    })
  }

  return groups
}

/**
 * The hits of one conversation in the order they stand in it - what the capsule's arrows walk.
 *
 * By the time of the message rather than by how well it matched: the list arrives best first, and
 * "next" inside a conversation means further down it. Only this conversation's, whatever the search
 * was over - a search across the project finds hits in a dozen talks, and the arrows on one of them
 * must not leap into another. Messages the transcript kept no time for (see SearchHit.at) keep the
 * order they were found in.
 */
export const chatHits = (hits: readonly SearchHit[], conversationId: string): SearchHit[] =>
  hits.filter((hit) => hit.conversationId === conversationId).sort((a, b) => a.at - b.at)

/**
 * The row of the feed a hit stands in, if it is on screen: by the transcript's name of the message
 * when the feed knows it, and by the words themselves when it does not.
 *
 * A message read off the disk carries its uuid (see UserItem.uuid); the person's own live message
 * does not - it went into the feed at the press of Send, before the transcript had a line for it. Its
 * text is what it was, though, and a hit on it carries that text whole, so the words are compared. Not
 * for the answers: an answer's uuid comes with the event that brought it, live or replayed.
 */
export const rowOf = (items: readonly FeedItem[], hit: SearchHit): string | undefined => {
  const named = items.find((item) => (item.kind === 'user' || item.kind === 'text') && item.uuid === hit.uuid)
  if (named) return named.id
  if (hit.speaker !== 'you') return undefined

  const wanted = hit.text.trim()
  if (!wanted) return undefined

  return items.find((item) => item.kind === 'user' && sameWords(tokensText(item.tokens), wanted, hit.truncated))?.id
}

/** Whether the text of a row is the text of the hit - the hit's whole, or its beginning when it was cut. */
const sameWords = (shown: string, wanted: string, truncated: boolean): boolean => {
  const a = shown.trim()
  if (a === wanted) return true
  return truncated && a.startsWith(wanted)
}
