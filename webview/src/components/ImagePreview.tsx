import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { PREVIEW_SELECTOR, isUnopened, previewOf } from '../imagePreview'
import { isKnown, loadPreview, type Loaded } from '../imagePreviewLoad'
import { useT } from '../i18n'
import { placeTooltip } from '../tooltipPlacement'
import s from './imagePreview.module.css'

/**
 * The picture behind a chip, shown while the pointer is on it - in the input field, in a sent message, in
 * a queued one (see imagePreview.ts for how a chip says where its picture is).
 *
 * Built the way the hover hints are (see Tooltips): one element in the body for the whole panel, driven
 * directly rather than through React state - `mouseover` fires on every pass of the pointer across the
 * panel, and a render per pass is a price with nothing to show for it. In the body, because the field and
 * the queue stand inside boxes that clip and transform, and a picture a few hundred pixels tall has to be
 * able to stand over the feed.
 *
 * Shown once the picture is decoded rather than at once: an empty frame that fills a moment later is a
 * flicker on every chip the pointer crosses. Only a picture that keeps the pointer waiting - one the IDE
 * reads off a transcript - gets the frame first, so the wait is seen as a wait. A picture there is nothing
 * to show of says so in words: an empty frame under the pointer reads as something that failed to load.
 */
export const ImagePreview = () => {
  const t = useT()
  const frame = useRef<HTMLDivElement | null>(null)
  const image = useRef<HTMLImageElement | null>(null)
  const caption = useRef<HTMLDivElement | null>(null)
  const title = useRef<HTMLDivElement | null>(null)
  // The words are read when a preview opens, not when the effect was set up: the language arrives after
  // the first render (see i18n).
  const words = useRef(t.feed.imagePreview)
  words.current = t.feed.imagePreview

  useEffect(() => {
    const element = frame.current
    const picture = image.current
    const line = caption.current
    const name = title.current
    if (!element || !picture || !line || !name) return

    // A screen without hover has nothing to show a preview on - and a tap must not conjure one either.
    if (!window.matchMedia('(hover: hover)').matches) return

    let anchor: HTMLElement | null = null
    /** Which showing the answers in flight belong to - one that arrives for a chip already left is dropped. */
    let showing = 0
    let follow = 0
    let wait = 0
    let spot = ''

    const place = () => {
      if (!anchor) return

      const { width, height } = element.getBoundingClientRect()
      const placed = placeTooltip({
        anchor: anchor.getBoundingClientRect(),
        tip: { width, height },
        viewport: { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight },
        // Above by preference: the field and the queue stand at the bottom of the panel, and in the feed
        // the line under a chip is the one being read next.
        side: 'top',
        align: 'left',
      })

      const next = `${placed.x},${placed.y},${placed.side}`
      if (next === spot) return
      element.style.left = `${placed.x}px`
      element.style.top = `${placed.y}px`
      element.dataset.side = placed.side
      spot = next
    }

    /** Keeps up with the chip while it shows: the field grows, the feed scrolls under the pointer, the chip is removed. */
    const keepUp = () => {
      if (!anchor || !anchor.isConnected) {
        hide()
        return
      }
      place()
      follow = requestAnimationFrame(keepUp)
    }

    const open = (state: 'ready' | 'loading' | 'missing', text: string, called = '') => {
      element.dataset.state = state
      line.textContent = text
      name.textContent = called
      name.hidden = !called
      spot = ''
      place()
      element.dataset.open = ''
      cancelAnimationFrame(follow)
      follow = requestAnimationFrame(keepUp)
    }

    const show = (next: HTMLElement) => {
      const source = previewOf(next)
      if (!source) {
        hide()
        return
      }

      hide()
      anchor = next
      showing += 1
      const mine = showing

      const ask = () => {
        // A picture the page holds is decoded in a frame or two; one the IDE has to read may take a while.
        wait = window.setTimeout(() => {
          if (mine === showing) open('loading', '')
        }, LOADING_AFTER_MS)
        void loadPreview(source).then(reveal)
      }

      const reveal = async (result: Loaded) => {
        if (mine !== showing) return

        if (result.state === 'missing') {
          clearTimeout(wait)
          open('missing', words.current.missing, source.name)
          return
        }

        picture.src = result.url
        try {
          await picture.decode()
        } catch {
          if (mine !== showing) return
          clearTimeout(wait)
          open('missing', words.current.missing, source.name)
          return
        }
        if (mine !== showing) return

        clearTimeout(wait)
        // The picture's own size: what came may be a smaller copy of a large one (see ImagePreviews.fitted).
        const { width, height } = result.size ?? { width: picture.naturalWidth, height: picture.naturalHeight }
        const size = `${width} × ${height}`
        // A chip whose last click opened nothing says so here: the picture is the chip's hover, and the
        // one place a click's answer can be read.
        const does = isUnopened(next) ? words.current.unopened : words.current.opens
        open('ready', source.opens ? `${size} · ${does}` : size, source.name)
      }

      // The IDE is asked only once the pointer stays: a pointer swept down a conversation read back off the
      // disk crosses a chip in a few milliseconds, and each would be a pass over a transcript of tens of
      // megabytes whose answer nobody waits for any more. What is to hand is shown at once.
      if (isKnown(source)) ask()
      else wait = window.setTimeout(() => mine === showing && ask(), ASK_AFTER_MS)
    }

    const hide = () => {
      showing += 1
      clearTimeout(wait)
      cancelAnimationFrame(follow)
      if (!anchor) return
      anchor = null
      delete element.dataset.open
    }

    const onOver = (event: MouseEvent) => {
      const target = event.target
      const next = target instanceof Element ? target.closest<HTMLElement>(PREVIEW_SELECTOR) : null

      if (!next) {
        hide()
        return
      }
      // A mouseover the page dispatched itself is a chip asking to be shown again - its click opened nothing
      // (see resolvePictureUnopened) - even when the pointer came back to it while the IDE was answering.
      if (next !== anchor || !event.isTrusted) show(next)
    }

    // The pointer left the panel or the window. And a press: a chip that opens hands the picture to the
    // editor, and a preview left standing over it would cover the very thing that just opened.
    const onGone = () => hide()

    document.addEventListener('mouseover', onOver)
    document.addEventListener('mouseleave', onGone)
    document.addEventListener('mousedown', onGone, true)
    window.addEventListener('blur', onGone)

    return () => {
      document.removeEventListener('mouseover', onOver)
      document.removeEventListener('mouseleave', onGone)
      document.removeEventListener('mousedown', onGone, true)
      window.removeEventListener('blur', onGone)
      hide()
    }
  }, [])

  // Presentation only: the chip under the pointer carries its own caption for a screen reader.
  return createPortal(
    <div ref={frame} className={s.frame} aria-hidden="true">
      <img ref={image} className={s.image} alt="" />
      <div ref={title} className={s.name} hidden />
      <div ref={caption} className={s.caption} />
    </div>,
    document.body,
  )
}

/**
 * How long the pointer stays on a chip before the IDE is asked for its picture - a sweep across the feed is
 * well under it, a look at one chip well over.
 */
const ASK_AFTER_MS = 140

/**
 * How long a picture may keep the pointer waiting, once asked for, before the frame shows that it is coming. Below this the
 * wait is not seen at all - one in the page is decoded well inside it, one off a file nearly so.
 */
const LOADING_AFTER_MS = 160
