import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Menu, type MenuOption } from '../Menu'
import { anchorFrom, type Anchor } from '../StatusBar'
import { MoreButton } from './MoreButton'

interface CardMenuProps {
  /** The dots' hover, and the name a screen reader gives them. */
  label: string
  /** The menu's own heading, the way every menu of the panel has one. */
  title: string
  width: number
  options: MenuOption[]
  className: string
  onPick: (id: string) => void
}

/**
 * Three dots in the corner of a card, and the panel's own menu under them (see Menu) - the same box the
 * model and the mode are chosen in, so a card's menu reads as the rest of the panel's do.
 *
 * Two things set it apart from the menus by the input field, and both come from standing in the feed:
 *
 * - It is drawn into the body rather than in place. The menu counts its coordinates from the window, and a
 *   fixed box counts them from the window only while no ancestor has a transform - and the feed stands in
 *   one that does (.content's translateZ(0), there so that JCEF repaints; see SelectionMenu, which met the
 *   same thing first).
 * - It follows its card. The feed scrolls by itself while an answer is printing, and a menu left where it
 *   opened would hang over some other message - one whose rewind or pin it does not offer. Re-measured on
 *   every scroll and resize, once a frame; once the dots leave the visible part of the feed there is
 *   nothing left to point at, and the menu closes.
 */
export const CardMenu = ({ label, title, width, options, className, onPick }: CardMenuProps) => {
  const button = useRef<HTMLButtonElement | null>(null)
  const [anchor, setAnchor] = useState<Anchor | null>(null)
  const open = anchor !== null
  const close = useCallback(() => setAnchor(null), [])

  useEffect(() => {
    const dots = button.current
    if (!open || !dots) return

    const scroller = scrollerOf(dots)
    let frame = 0

    const follow = () => {
      if (frame) return
      frame = requestAnimationFrame(() => {
        frame = 0
        const rect = dots.getBoundingClientRect()
        const bounds = scroller?.getBoundingClientRect() ?? { top: 0, bottom: window.innerHeight }
        if (rect.bottom < bounds.top || rect.top > bounds.bottom || !dots.isConnected) {
          setAnchor(null)
          return
        }

        const next = anchorFrom(dots)
        setAnchor((current) =>
          current && current.right === next.right && current.top === next.top && current.bottom === next.bottom
            ? current
            : next,
        )
      })
    }

    // Capture: the scroll happens inside the feed, and such an event does not travel up to the document.
    document.addEventListener('scroll', follow, true)
    window.addEventListener('resize', follow)

    return () => {
      if (frame) cancelAnimationFrame(frame)
      document.removeEventListener('scroll', follow, true)
      window.removeEventListener('resize', follow)
    }
  }, [open])

  return (
    <>
      <MoreButton
        ref={button}
        className={className}
        label={label}
        expanded={open}
        onClick={() => {
          const dots = button.current
          setAnchor((current) => (current || !dots ? null : anchorFrom(dots)))
        }}
      />

      {anchor
        ? createPortal(
            <Menu
              title={title}
              width={width}
              anchor={anchor}
              options={options}
              // Nothing here is a choice of one out of several: the entries are errands, and the column
              // the tick would stand in carries their drawings.
              selected=""
              onClose={close}
              onPick={(id) => {
                setAnchor(null)
                onPick(id)
              }}
            />,
            document.body,
          )
        : null}
    </>
  )
}

/** The nearest ancestor that scrolls - the feed, wherever this card happens to be drawn. */
const scrollerOf = (node: HTMLElement): HTMLElement | null => {
  for (let parent = node.parentElement; parent; parent = parent.parentElement) {
    const { overflowY } = getComputedStyle(parent)
    if (overflowY === 'auto' || overflowY === 'scroll') return parent
  }

  return null
}
