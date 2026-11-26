import { useEffect, useState, type ReactNode } from 'react'
import s from './sideMenu.module.css'
import shell from './shell.module.css'
import { useT } from '../i18n'
import { THANKS_LINKS } from './thanksLinks'

/**
 * Asking the author for a change: a speech bubble beside the heart, and behind it a screen in the side
 * menu that says how.
 *
 * It used to be a form - a message, an address, files and a debug report, posted to a service that put
 * it in the author's Telegram. That stopped: the plugin is one person's work, it takes a large share of
 * that person's Claude subscription and time, and a box anybody can type into filled up faster than it
 * could be read. So the screen no longer takes a message. It names the two ways a change still gets in -
 * a tip on Ko-fi with the wish written in it, or a pull request to the repository - and leaves the rest
 * to the browser.
 *
 * Kept where the form was, under the same bubble and the same row of the menu, on purpose: that is where
 * somebody with a request goes looking, and the answer has to be found exactly there rather than in a
 * place they would never open.
 *
 * The debug report outlived the form. It is still built here, in the IDE, from what the plugin itself saw
 * (see FeedbackReport on the plugin's side), and still shown whole before anything is done with it - but
 * now it is copied rather than sent: into a pull request about a bug, or into a reply when the author asks
 * a person who tipped for a fix what happened.
 */

/** The two ways in, as the cards show them. The addresses are the heart's own (see Thanks). */
export const FEEDBACK_WAYS = [
  { id: 'tip', url: THANKS_LINKS.tip },
  { id: 'code', url: THANKS_LINKS.github },
] as const

export type FeedbackWay = (typeof FEEDBACK_WAYS)[number]['id']

/** An address as a person reads it under a card - no scheme, no trailing slash. */
export const bareAddress = (url: string): string => url.replace(/^https?:\/\//, '').replace(/\/$/, '')

interface FeedbackButtonProps {
  /** The side rail's variant: no frame of its own, like the heart's (see Thanks.tsx). */
  rail?: boolean
  /**
   * Compact's variant: level with the selectors it shares a row with, exactly as the heart beside it is
   * (see ThanksButton.withSelectors).
   *
   * It used to stand a row below, among the paperclip and the slash, because compact's row of selectors
   * could not give up the width for it. It can now: the MODE button stopped spelling out "PERMISSION
   * MODE" and gave back more room than this button takes.
   */
  withSelectors?: boolean
  onOpen: () => void
}

export const FeedbackButton = ({ rail = false, withSelectors = false, onOpen }: FeedbackButtonProps) => {
  const t = useT()

  return (
  <button
    type="button"
    className={`${shell.feedback} ${rail ? shell.feedbackRail : ''} ${withSelectors ? shell.feedbackLevel : ''}`}
    /* The panel's own tooltip rather than a title, unlike the heart beside it: this button opens the side
       menu rather than a popup above itself, so there is nothing for the hint to cover. */
    data-tooltip={t.feedback.button}
    data-tooltip-at="top"
    aria-label={t.feedback.button}
    onClick={onOpen}
  >
    <Bubble />
  </button>
  )
}

/** An outlined bubble rather than a solid one: beside a solid heart, a second filled shape would read as a
 *  pair of indicators rather than as two different errands. */
const Bubble = () => (
  <svg className={shell.feedbackIcon} viewBox="0 0 16 16" aria-hidden="true">
    <path
      d="M3.2 3.2h9.6a1.6 1.6 0 0 1 1.6 1.6v4.8a1.6 1.6 0 0 1-1.6 1.6H6.6l-2.7 2.2v-2.2h-.7A1.6 1.6 0 0 1 1.6 9.6V4.8a1.6 1.6 0 0 1 1.6-1.6z"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.3"
      strokeLinejoin="round"
    />
  </svg>
)

/** The heart's own drawing: the tip card leads to the same page the heart's first entry does. */
const HEART_PATH =
  'M8 14.3l-.9-.85C3.4 10.1 1 7.95 1 5.4A3.55 3.55 0 0 1 4.6 1.8c1.3 0 2.55.6 3.4 1.57A4.4 4.4 0 0 1 11.4 1.8A3.55 3.55 0 0 1 15 5.4c0 2.55-2.4 4.7-6.1 8.06z'

const WAY_ICONS: Record<FeedbackWay, ReactNode> = {
  tip: (
    <svg viewBox="0 0 16 16" aria-hidden="true" width="15" height="15">
      <path d={HEART_PATH} fill="currentColor" />
    </svg>
  ),
  /* A pull request as GitHub draws one: a branch leaving a line and an arrow bringing it back. A code
     bracket would have said "the source" - the card is about handing a change over, not about reading. */
  code: (
    <svg
      viewBox="0 0 16 16"
      aria-hidden="true"
      width="16"
      height="16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="4.2" cy="3.6" r="1.6" />
      <circle cx="4.2" cy="12.4" r="1.6" />
      <circle cx="11.8" cy="12.4" r="1.6" />
      <path d="M4.2 5.2v5.6" />
      <path d="M11.8 10.8V6.4a1.8 1.8 0 0 0-1.8-1.8H7.4" />
      <path d="M8.9 3.1L7.4 4.6l1.5 1.5" />
    </svg>
  ),
}

interface FeedbackProps {
  onOpenLink: (url: string) => void
  onReport: () => void
}

export const Feedback = ({ onOpenLink, onReport }: FeedbackProps) => {
  const t = useT()

  return (
    <div className={s.screen}>
      <p className={s.wayLead}>{t.feedback.lead}</p>

      <div className={s.ways}>
        {FEEDBACK_WAYS.map((way) => (
          /* The whole card is the button, the way the author's card is (see AuthorCard): one errand each,
             and the arrow in the corner is the notice that the press leaves the IDE. The address goes to
             the shell - this page has no browser of its own. */
          <button
            key={way.id}
            type="button"
            className={`${s.way} ${way.id === 'tip' ? s.wayTip : s.wayCode}`}
            onClick={() => onOpenLink(way.url)}
          >
            <span className={`${s.wayIcon} ${way.id === 'tip' ? s.wayIconTip : s.wayIconCode}`}>
              {WAY_ICONS[way.id]}
            </span>
            <span className={s.wayText}>
              <span className={s.wayTitle}>{t.feedback.ways[way.id].title}</span>
              <span className={s.wayBody}>{t.feedback.ways[way.id].body}</span>
              <span className={s.wayAddress}>{bareAddress(way.url)}</span>
            </span>
            <svg className={s.wayArrow} viewBox="0 0 16 16" aria-hidden="true">
              <path
                d="M5.4 10.6L10.6 5.4M6.2 5.2h4.6v4.6"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
        ))}
      </div>

      <p className={s.wayThanks}>
        {t.feedback.thanks}
        <svg className={s.authorHeart} viewBox="0 0 16 16" aria-hidden="true">
          <path d={HEART_PATH} fill="currentColor" />
        </svg>
      </p>

      {/* Last and quietest, under a hair of its own: most people who open this screen want one of the two
          cards, and the report is a tool for the few who are writing about a bug - a line, not a third card,
          and kept apart from the message so it does not read as part of it. */}
      <div className={s.wayFoot}>
        <button type="button" className={s.linkRow} onClick={onReport}>
          {t.feedback.report}
        </button>
      </div>
    </div>
  )
}

/** How long the copy button says it copied before it goes back to offering to. */
const COPIED_MS = 1600

/**
 * The report, in full, before it goes anywhere.
 *
 * Plain text in the mono font and nothing else: any prettier a rendering would invite the question of what
 * it left out, and the whole worth of this screen is that it left out nothing.
 */
export const FeedbackLog = ({
  text,
  conversation,
  onCopy,
}: {
  text: string | null
  conversation: string
  onCopy: () => void
}) => {
  const t = useT()
  /*
   * The clipboard cannot be looked into, and a button that simply did nothing visible on a press leaves
   * "did that work?" hanging - so it answers for a moment, the way the heart's "share" entry does.
   */
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) return
    const timer = window.setTimeout(() => setCopied(false), COPIED_MS)
    return () => window.clearTimeout(timer)
  }, [copied])

  return (
  <div className={s.screen}>
    <span className={s.screenNote}>{t.feedback.reportNote(conversation)}</span>

    {text === null ? (
      <div className={s.screenEmpty}>{t.feedback.building}</div>
    ) : (
      <>
        <pre className={s.logText}>{text}</pre>
        <div className={s.formActions}>
          <button
            type="button"
            className={s.button}
            onClick={() => {
              onCopy()
              setCopied(true)
            }}
          >
            {copied ? t.feedback.copied : t.feedback.copy}
          </button>
        </div>
      </>
    )}
  </div>  )
}
