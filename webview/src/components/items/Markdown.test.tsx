import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { parseParagraphs } from '../../feed/markdown'
import { OpenFileContext } from '../../hooks/useOpenFile'
import { Markdown } from './Markdown'

vi.mock('../../bridge', () => ({ send: vi.fn() }))

describe('markdown link destinations on screen', () => {
  it('renders a local file label as an editor action rather than a browser address', () => {
    const html = renderToStaticMarkup(
      <OpenFileContext.Provider value={() => {}}>
        <Markdown paragraphs={parseParagraphs('[Демо-страница](/tmp/demo.html)')} onOpenLink={() => {}} />
      </OpenFileContext.Provider>,
    )

    expect(html).toContain('<button')
    expect(html).toContain('>Демо-страница</')
    expect(html).not.toContain('<a ')
    expect(html).not.toContain('[Демо-страница]')
  })

  it('keeps a local label as text when there is no IDE editor', () => {
    const html = renderToStaticMarkup(
      <Markdown paragraphs={parseParagraphs('[Демо-страница](/tmp/demo.html)')} onOpenLink={() => {}} />,
    )

    expect(html).toContain('Демо-страница')
    expect(html).not.toContain('<button')
    expect(html).not.toContain('<a ')
    expect(html).toContain('/tmp/demo.html')
  })

  it('still renders a website as a browser link', () => {
    const html = renderToStaticMarkup(
      <OpenFileContext.Provider value={() => {}}>
        <Markdown paragraphs={parseParagraphs('[Docs](https://example.com)')} onOpenLink={() => {}} />
      </OpenFileContext.Provider>,
    )

    expect(html).toContain('href="https://example.com"')
    expect(html).toContain('>Docs</')
    expect(html).not.toContain('<button')
  })
})
