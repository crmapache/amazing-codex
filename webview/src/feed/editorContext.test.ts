import { describe, expect, it } from 'vitest'
import type { AgentEvent } from '../protocol'
import { initialPanelState, reducePanel } from './build'
import { editorChoice, editorGoes, editorKey, editorLabel, editorOfBlock, editorOfBlocks } from './editorContext'
import type { UserItem } from './types'

/** The block EditorContext.kt writes beside the person's text - the same words, character for character. */
const selectedNote = (path: string, from: number, to: number, text: string) =>
  `<system-reminder>\nThe user selected the lines ${from} to ${to} from ${path}:\n${text}\n\nThis may or may not be related to the current task.\n</system-reminder>`

const openedNote = (path: string) =>
  `<system-reminder>\nThe user opened the file ${path} in the IDE. This may or may not be related to the current task.\n</system-reminder>`

const tooLongNote = (path: string, from: number, to: number) =>
  `<system-reminder>\nThe user selected the lines ${from} to ${to} from ${path} in the IDE - too long to include here, read the file for them. This may or may not be related to the current task.\n</system-reminder>`

describe('editorOfBlock', () => {
  it('reads the selected lines and the file back out of the note', () => {
    expect(editorOfBlock(selectedNote('src/app/Foo.kt', 12, 18, 'val x = 1\nval y = 2'))).toEqual({
      path: 'src/app/Foo.kt',
      name: 'Foo.kt',
      from: 12,
      to: 18,
    })
  })

  it('reads a file that stood open with nothing selected', () => {
    expect(editorOfBlock(openedNote('README.md'))).toEqual({ path: 'README.md', name: 'README.md' })
  })

  it('reads a selection too long to have been sent', () => {
    expect(editorOfBlock(tooLongNote('src/big.ts', 1, 4000))).toEqual({
      path: 'src/big.ts',
      name: 'big.ts',
      from: 1,
      to: 4000,
    })
  })

  // A file outside the project keeps its whole path, and on Windows that path has a colon of its own.
  it('names a file by its last part whichever slash the path uses', () => {
    expect(editorOfBlock(selectedNote('C:\\work\\notes\\todo.txt', 3, 3, 'x'))).toEqual({
      path: 'C:\\work\\notes\\todo.txt',
      name: 'todo.txt',
      from: 3,
      to: 3,
    })
  })

  // The same words inside something a person typed are that person talking, not the IDE's note.
  it('leaves a block alone that is not the note itself', () => {
    expect(editorOfBlock('I wrote: The user opened the file x.ts in the IDE.')).toBeUndefined()
    expect(editorOfBlock('<system-reminder>\nSomething else entirely.\n</system-reminder>')).toBeUndefined()
  })
})

describe('editorOfBlocks', () => {
  it('finds the note among the text and the images', () => {
    expect(
      editorOfBlocks([
        { type: 'text', text: 'why is this slow?' },
        { type: 'text', text: openedNote('src/a.ts') },
      ]),
    ).toEqual({ path: 'src/a.ts', name: 'a.ts' })
  })
})

describe('editorLabel', () => {
  it('writes the lines after the name, a single line as itself', () => {
    expect(editorLabel({ path: 'src/Foo.kt', name: 'Foo.kt', from: 12, to: 18 })).toBe('Foo.kt:12-18')
    expect(editorLabel({ path: 'src/Foo.kt', name: 'Foo.kt', from: 7, to: 7 })).toBe('Foo.kt:7')
    expect(editorLabel({ path: 'src/Foo.kt', name: 'Foo.kt' })).toBe('Foo.kt')
  })
})

describe('editorKey', () => {
  // What a one-message exception is remembered by: other lines are a new thing on screen.
  it('tells the same file with other lines apart', () => {
    const file = { path: 'src/Foo.kt', name: 'Foo.kt' }
    expect(editorKey({ ...file, from: 1, to: 2 })).not.toBe(editorKey({ ...file, from: 1, to: 3 }))
    expect(editorKey(file)).not.toBe(editorKey({ ...file, from: 1, to: 2 }))
    expect(editorKey({ ...file, from: 1, to: 2 })).toBe(editorKey({ ...file, from: 1, to: 2 }))
  })
})

describe('whether the next message carries the editor', () => {
  const lines = { path: 'src/Foo.kt', name: 'Foo.kt', from: 4, to: 9 }
  const skip = { key: editorKey(lines), on: false }
  const only = { key: editorKey(lines), on: true }

  it('follows the setting with nothing said for this message', () => {
    expect(editorGoes(true, undefined, lines)).toBe(true)
    expect(editorGoes(false, undefined, lines)).toBe(false)
    expect(editorChoice(true, undefined, lines)).toBe('always')
    expect(editorChoice(false, undefined, lines)).toBe('never')
  })

  // The two ways of one message: left out while the setting sends, sent while the setting does not.
  it('goes the other way for one message, either way', () => {
    expect(editorGoes(true, skip, lines)).toBe(false)
    expect(editorChoice(true, skip, lines)).toBe('once')
    expect(editorGoes(false, only, lines)).toBe(true)
    expect(editorChoice(false, only, lines)).toBe('once')
  })

  it('forgets the exception once other lines are on screen', () => {
    const other = { ...lines, to: 12 }
    expect(editorGoes(true, skip, other)).toBe(true)
    expect(editorChoice(true, skip, other)).toBe('always')
    expect(editorGoes(false, only, other)).toBe(false)
  })

  // Left behind by a setting that has since been switched to the same answer: nothing to except.
  it('does not show an exception that agrees with the setting', () => {
    expect(editorChoice(false, skip, lines)).toBe('never')
    expect(editorChoice(true, only, lines)).toBe('always')
  })
})

describe('a message in the feed', () => {
  const userOf = (state: typeof initialPanelState) =>
    state.items.filter((item): item is UserItem => item.kind === 'user')

  it('keeps what the editor showed when it was sent', () => {
    const editor = { path: 'src/Foo.kt', name: 'Foo.kt', from: 4, to: 9 }
    const state = reducePanel(
      initialPanelState,
      { kind: 'prompt', tokens: [{ kind: 'text', value: 'why?' }], quotes: [], editor },
      1_700_000_000_000,
    )

    expect(userOf(state)[0]?.editor).toEqual(editor)
  })

  // A past conversation has only the transcript: the note is stripped from the words and becomes the line.
  it('reads it back out of a past conversation, and not into the words', () => {
    const event = {
      type: 'user',
      message: {
        content: [
          { type: 'text', text: 'why is this slow?' },
          { type: 'text', text: selectedNote('src/Foo.kt', 4, 9, 'for (x of xs) await x') },
        ],
      },
      timestamp: '2026-09-29T10:00:00.000Z',
    } as AgentEvent

    const [message] = userOf(reducePanel(initialPanelState, { kind: 'agent', event, replay: true }, 1_700_000_000_000))

    expect(message?.editor).toEqual({ path: 'src/Foo.kt', name: 'Foo.kt', from: 4, to: 9 })
    expect(message?.tokens).toEqual([{ kind: 'text', value: 'why is this slow?' }])
  })
})
