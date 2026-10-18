import { createContext, useContext } from 'react'

/**
 * The tab whose transcript the feed's messages can be read back out of - for a picture whose bytes the
 * feed no longer holds (see PreviewPlace `said` in protocol.ts).
 *
 * A conversation opened from the history, or brought back after a restart, keeps `[Image #2]` and not the
 * picture: the bytes stay in the transcript and nowhere else. The IDE reads them from there, and needs to
 * know whose transcript - which only the screen that draws a tab's own feed can say.
 *
 * A context rather than a prop for the same reason as the editor (see useOpenFile): the chip is a leaf
 * deep inside a card. Absent everywhere but the panel's own feed of a tab: a scenario's step log is a
 * different conversation's lines, and a phone has no pointer to hover with.
 */
export const SaidImagesContext = createContext<string | null>(null)

export const useSaidImages = (): string | null => useContext(SaidImagesContext)
