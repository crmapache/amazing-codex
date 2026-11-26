import { LOCALES } from '../i18n'
import { DICTIONARIES } from '../i18n/all'
import { describe, expect, it } from 'vitest'
import { FEEDBACK_WAYS, bareAddress } from './Feedback'
import { THANKS_LINKS } from './thanksLinks'

describe('the two ways a change gets in', () => {
  it('leads the tip to the same page as the heart, and the code to the repository', () => {
    const urls = Object.fromEntries(FEEDBACK_WAYS.map((way) => [way.id, way.url]))

    expect(urls.tip).toBe(THANKS_LINKS.tip)
    expect(urls.code).toBe(THANKS_LINKS.github)
  })

  it('puts the tip first - it is the one the plugin lives on', () => {
    expect(FEEDBACK_WAYS[0]?.id).toBe('tip')
  })

  /** In every language: a translation that reused one line for both cards would leave two equal cards. */
  it('says something different on each card in every language', () => {
    for (const { id } of LOCALES) {
      const ways = DICTIONARIES[id].feedback.ways
      expect(ways.tip.title, id).not.toBe(ways.code.title)
      expect(ways.tip.body, id).not.toBe(ways.code.body)
    }
  })
})

describe('the address under a card', () => {
  it('drops the scheme, as a person would read it', () => {
    expect(bareAddress('https://ko-fi.com/mzpizote')).toBe('ko-fi.com/mzpizote')
    expect(bareAddress('http://example.com/a')).toBe('example.com/a')
  })

  it('drops a trailing slash and leaves the rest alone', () => {
    expect(bareAddress('https://github.com/crmapache/amazing-claude-code/')).toBe(
      'github.com/crmapache/amazing-claude-code',
    )
  })
})
