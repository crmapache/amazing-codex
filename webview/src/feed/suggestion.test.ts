import { describe, expect, it } from 'vitest'
import { initialPanelState, type PanelState } from './panelState'
import { editDeclines, shownGuess } from './suggestion'

describe('whether the gray guess stands in the field', () => {
  const quiet = { empty: true, conversation: true, dictating: false }

  it('stands in an empty field of a conversation', () => {
    expect(shownGuess('run the tests', quiet)).toBe('run the tests')
  })

  // The words being dictated are drawn where the gray text stood (see .field[data-voice] in the styles): a guess
  // nobody can see must not be what Tab puts into the message.
  it('is not there while words are being dictated, nor over a draft, nor outside a conversation', () => {
    expect(shownGuess('run the tests', { ...quiet, dictating: true })).toBe('')
    expect(shownGuess('run the tests', { ...quiet, empty: false })).toBe('')
    expect(shownGuess('run the tests', { ...quiet, conversation: false })).toBe('')
    expect(shownGuess(undefined, quiet)).toBe('')
  })
})

describe('whether a keystroke in the field is news to the guess', () => {
  const idle: PanelState = { ...initialPanelState, status: 'idle' }

  it('is, once per turn, whether the guess has come or not', () => {
    expect(editDeclines(idle)).toBe(true)
    expect(editDeclines({ ...idle, suggestion: 'run the tests' })).toBe(true)
    expect(editDeclines({ ...idle, suggestionDeclined: true })).toBe(false)
  })

  it('is not while the turn runs', () => {
    expect(editDeclines({ ...idle, status: 'running' })).toBe(false)
  })
})
