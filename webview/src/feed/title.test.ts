import { describe, expect, it } from 'vitest'
import { deriveSessionTitle, resumedTitle, searchHitTitleSource, splitTabTitle } from './title'

describe('deriveSessionTitle', () => {
  it('glues a short first line to its continuation', () => {
    expect(deriveSessionTitle('Let us\nmake a pretty dialog with buttons')).toBe(
      'Let us make a pretty dialog with buttons',
    )
  })

  it('cuts an inline image tag out of the middle of a phrase', () => {
    expect(deriveSessionTitle('look [Image #1] here, what is wrong')).toBe('look here, what is wrong')
  })

  it('skips the lines with a quote and a file mention', () => {
    expect(deriveSessionTitle('> old text\n@src/App.tsx\nfix this here')).toBe('fix this here')
  })

  it('takes the last original line if nothing is left after the clean-up', () => {
    expect(deriveSessionTitle('@src/App.tsx\n[Image #1]')).toBe('[Image #1]')
  })

  it('does not let bash-mode command output become a tab title', () => {
    const text =
      '<bash-input>git pull</bash-input>\n<bash-stdout>Already up to date.\nvia origin/main</bash-stdout>\n\nLet us move on to this task'

    expect(deriveSessionTitle(text)).toBe('Let us move on to this task')
  })

  it('clips long text at a word boundary with an ellipsis', () => {
    const text = 'please work through this very long and detailed statement of the task in full'
    const title = deriveSessionTitle(text, 40)

    expect(title.length).toBeLessThanOrEqual(41)
    expect(title.endsWith('…')).toBe(true)
    expect(text.startsWith(title.slice(0, -1))).toBe(true)
  })

  it('leaves short text alone', () => {
    expect(deriveSessionTitle('go on')).toBe('go on')
  })
})

describe('resumedTitle', () => {
  // The tab hands its name back to the transcript, so a person's name worked over like a guess would be
  // cut in the transcript itself.
  it('carries a name the person gave exactly as it was typed', () => {
    const name = '@backend: the long refactoring of the parser and the tokenizer, part two'

    expect(resumedTitle(name, 'user')).toEqual({ title: name, titleSource: 'user' })
  })

  it('cleans a guess and a model name as before', () => {
    expect(resumedTitle('> quoted\nfix the parser', 'heuristic')).toEqual({
      title: 'fix the parser',
      titleSource: 'heuristic',
    })
    // A name with no rank said is the model's, as it always was.
    expect(resumedTitle('Parser fixes', undefined)).toEqual({ title: 'Parser fixes', titleSource: 'llm' })
  })

  // Live, the tab wore the model's name whole; from the history it came back cut at forty characters.
  it('keeps the model name whole and cuts a guess as a live tab does', () => {
    const named = 'Транскрипция русского с английскими терминами'
    expect(resumedTitle(named, 'llm').title).toBe(named)

    const guess = 'please work through this very long and detailed statement of the task in full'
    expect(resumedTitle(guess, 'heuristic').title).toBe(deriveSessionTitle(guess))
  })
})

describe('splitTabTitle', () => {
  it('keeps the last word for the end of a narrow tab', () => {
    expect(splitTabTitle('Refactor auth module tests')).toEqual({ head: 'Refactor auth module', tail: ' tests' })
  })

  it('puts the two parts back into the name exactly', () => {
    for (const title of ['Refactor auth module tests', 'Транскрипция русского с английскими терминами', '修复登录页面的样式问题']) {
      const { head, tail } = splitTabTitle(title)
      expect(head + tail).toBe(title)
    }
  })

  it('leaves a short name, a long last word and a name of one word whole', () => {
    expect(splitTabTitle('Fix the parser')).toEqual({ head: 'Fix the parser', tail: '' })
    expect(splitTabTitle('Rename useSocketReconnectHandler')).toEqual({ head: 'Rename useSocketReconnectHandler', tail: '' })
    expect(splitTabTitle('Supercalifragilisticexpialidocious')).toEqual({
      head: 'Supercalifragilisticexpialidocious',
      tail: '',
    })
  })

  // Chinese and Japanese have no spaces to find a word by.
  it('keeps the last few characters of a name written without spaces', () => {
    expect(splitTabTitle('修复登录页面的样式问题')).toEqual({ head: '修复登录页面的', tail: '样式问题' })
  })
})

describe('searchHitTitleSource', () => {
  it('reads the rank the IDE says', () => {
    expect(searchHitTitleSource({ named: true, titleSource: 'user' })).toBe('user')
  })

  // A phone may well be talking to an IDE built before titleSource.
  it('falls back to whether the title is a guess', () => {
    expect(searchHitTitleSource({ named: true })).toBe('llm')
    expect(searchHitTitleSource({ named: false })).toBe('heuristic')
  })
})
