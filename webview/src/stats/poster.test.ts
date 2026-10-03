import { describe, expect, it } from 'vitest'
import { posterName } from './poster'

describe('the shared picture', () => {
  it('is named after the screen it shows and the day it was taken', () => {
    expect(posterName('statistics', '2026-08-26')).toBe('amazing-codex-statistics-2026-08-26.png')
    expect(posterName('achievements', '2026-08-26')).toBe('amazing-codex-achievements-2026-08-26.png')
  })

  it('names a single achievement by its id, so two of them are two files', () => {
    expect(posterName('achievement-big-diff', '2026-08-26')).toBe('amazing-codex-achievement-big-diff-2026-08-26.png')
  })
})
