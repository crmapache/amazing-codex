import type { Ref } from 'react'
import s from '../feed.module.css'

/**
 * The three dots that open everything else a message can do.
 *
 * On the phone they open a sheet (see MessageSheet): there is no selection menu under a thumb and no field
 * to type a command into without losing the draft, so the dots are the one affordance a phone has for
 * "what else can I do with this". At the desk they open the card's menu (see CardMenu) - the rarer things
 * a message of one's own can do, kept out of its head so that copying stands there alone.
 *
 * [expanded] is whether the menu it opened is up: the dots stay lit for as long as it is, so it is plain
 * which card the menu belongs to.
 */
export const MoreButton = ({
  className,
  label,
  expanded,
  ref,
  onClick,
}: {
  className?: string
  label: string
  expanded?: boolean
  ref?: Ref<HTMLButtonElement>
  onClick: () => void
}) => (
  <button
    ref={ref}
    type="button"
    className={`${className ?? s.textAction} ${expanded ? s.moreOpen : ''}`}
    aria-label={label}
    aria-haspopup={expanded === undefined ? undefined : 'menu'}
    aria-expanded={expanded}
    data-tooltip={label}
    onClick={onClick}
  >
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <circle cx="3" cy="8" r="1.4" fill="currentColor" />
      <circle cx="8" cy="8" r="1.4" fill="currentColor" />
      <circle cx="13" cy="8" r="1.4" fill="currentColor" />
    </svg>
  </button>
)
