import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isKnown, loadPreview, resolveImagePreview } from './imagePreviewLoad'
import type { WebviewMessage } from './protocol'

const { sent } = vi.hoisted(() => ({ sent: [] as WebviewMessage[] }))
vi.mock('./bridge', () => ({ send: (message: WebviewMessage) => sent.push(message) }))

/** The IDE's answer to the last request for a picture. */
const answer = (data: string) => {
  const last = sent.at(-1)
  if (last?.type !== 'previewImage') throw new Error('nothing was asked')
  resolveImagePreview({ type: 'imagePreview', id: last.id, data })
}

describe('loadPreview', () => {
  beforeEach(() => {
    sent.length = 0
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  /*
   * A line not in the transcript is not there a moment later either - and finding that out is a pass over a
   * file of tens of megabytes. The answer is kept a while, so a pointer going back over the chip asks nothing.
   */
  it('remembers a picture the transcript did not have, and asks again only after a while', async () => {
    const source = { place: { kind: 'said', sessionId: 'main', uuid: 'u-gone', image: 0 } } as const

    const first = loadPreview(source)
    answer('')
    expect(await first).toEqual({ state: 'missing' })
    expect(isKnown(source)).toBe(true)

    expect(await loadPreview(source)).toEqual({ state: 'missing' })
    expect(sent).toHaveLength(1)

    vi.advanceTimersByTime(60_000)
    expect(isKnown(source)).toBe(false)
    void loadPreview(source)
    expect(sent).toHaveLength(2)
  })

  /* A file may be written a second later: a miss on disk is asked again every time. */
  it('asks the disk again about a file that was not there', async () => {
    const source = { place: { kind: 'file', path: 'shots/later.png' } } as const

    const first = loadPreview(source)
    answer('')
    expect(await first).toEqual({ state: 'missing' })
    expect(isKnown(source)).toBe(false)

    void loadPreview(source)
    expect(sent).toHaveLength(2)
  })

  it('knows a picture it holds, and one the page holds itself', async () => {
    const source = { place: { kind: 'file', path: 'shots/here.png' } } as const

    const first = loadPreview(source)
    answer('data:image/png;base64,AA==')
    expect(await first).toEqual({ state: 'ready', url: 'data:image/png;base64,AA==' })

    expect(isKnown(source)).toBe(true)
    expect(isKnown({ data: 'data:image/png;base64,AA==' })).toBe(true)
  })
})
