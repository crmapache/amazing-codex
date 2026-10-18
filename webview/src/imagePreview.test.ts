import { describe, expect, it } from 'vitest'
import { chipOpening, chipPreview, pastedOrdinals } from './imagePreview'
import type { UserToken } from './feed/types'

const said = { kind: 'said', sessionId: 'main', uuid: 'u-1', image: 0 } as const

describe('chipPreview', () => {
  it('shows a pasted picture out of its own bytes, asking nobody', () => {
    expect(chipPreview({ kind: 'img', value: 'Image #3', data: 'data:image/png;base64,AA==', path: '/p/a.png' }, said)).toEqual({
      data: 'data:image/png;base64,AA==',
    })
  })

  /* A file is the cheapest thing the IDE can be asked for - cheaper than a pass over a transcript. */
  it('reads a pasted picture without bytes off the file the IDE wrote it into', () => {
    expect(chipPreview({ kind: 'img', value: 'Image #3', path: '/p/a.png' }, said)).toEqual({
      place: { kind: 'file', path: '/p/a.png' },
    })
  })

  /* The name on the chip is cut in the middle, and the hover that used to show it whole is the picture now. */
  it('reads a picture picked in the dialog off its own path, and names it whole', () => {
    expect(chipPreview({ kind: 'img', value: 'design/sheet.png' }, said)).toEqual({
      place: { kind: 'file', path: 'design/sheet.png' },
      name: 'design/sheet.png',
    })
  })

  it('asks where the message keeps it for a caption and nothing else', () => {
    expect(chipPreview({ kind: 'img', value: 'Image #3' }, said)).toEqual({ place: said })
    // Nowhere to ask - a step log, a phone: no mark, and so no pointer promising a picture.
    expect(chipPreview({ kind: 'img', value: 'Image #3' })).toBeNull()
  })

  it('marks nothing that is not a picture', () => {
    expect(chipPreview({ kind: 'file', value: 'src/a.png' }, said)).toBeNull()
  })

  /* The IDE refuses a network path (see OpenInEditor), and a pointer must not promise a picture it will not read. */
  it('marks no picture behind a network path', () => {
    expect(chipPreview({ kind: 'img', value: '//host/share/shot.png' }, said)).toBeNull()
    expect(chipPreview({ kind: 'img', value: '\\\\host\\share\\shot.png' }, said)).toBeNull()
  })
})

describe('chipOpening', () => {
  /* The opposite order to the preview's: a file is what the editor opens, and bytes are written out only when there is none. */
  it('opens the file a pasted picture was written into, not its bytes', () => {
    expect(chipOpening({ kind: 'img', value: 'Image #3', data: 'data:image/png;base64,AA==', path: '/p/a.png' }, said)).toEqual({
      kind: 'file',
      path: '/p/a.png',
    })
  })

  it('hands the IDE the bytes of a pasted picture that has no file', () => {
    expect(chipOpening({ kind: 'img', value: 'Image #3', data: 'data:image/png;base64,AA==' }, said)).toEqual({
      kind: 'bytes',
      data: 'data:image/png;base64,AA==',
    })
  })

  it('opens a picked picture by its own path, and a caption where the message keeps it', () => {
    expect(chipOpening({ kind: 'img', value: 'design/sheet.png' }, said)).toEqual({ kind: 'file', path: 'design/sheet.png' })
    expect(chipOpening({ kind: 'img', value: 'Image #3' }, said)).toEqual(said)
    expect(chipOpening({ kind: 'img', value: 'Image #3' })).toBeNull()
  })

  it('opens nothing over a network path, and nothing that is not a picture', () => {
    expect(chipOpening({ kind: 'img', value: '//host/share/shot.png' }, said)).toBeNull()
    expect(chipOpening({ kind: 'img', value: 'Image #3', path: '//host/share/shot.png' }, said)).toBeNull()
    expect(chipOpening({ kind: 'file', value: 'src/a.png' }, said)).toBeNull()
  })
})

describe('pastedOrdinals', () => {
  /*
   * The queue and the transcript keep a message's pasted pictures in the order the chips stood in, and
   * neither keeps the captions - the numbering goes on across the conversation, so "Image #4" can be the
   * first picture of its message.
   */
  it('counts the pasted pictures only, in order', () => {
    const tokens: UserToken[] = [
      { kind: 'text', value: 'see ' },
      { kind: 'chip', chip: { kind: 'img', value: 'Image #4' } },
      { kind: 'chip', chip: { kind: 'img', value: 'design/sheet.png' } },
      { kind: 'chip', chip: { kind: 'file', value: 'src/a.ts' } },
      { kind: 'chip', chip: { kind: 'img', value: 'Image #5', data: 'data:image/png;base64,AA==' } },
    ]

    expect(pastedOrdinals(tokens)).toEqual([-1, 0, -1, -1, 1])
  })
})
