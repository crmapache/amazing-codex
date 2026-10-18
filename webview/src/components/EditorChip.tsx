import type { EditorRef } from '../protocol'
import { editorLabel, type EditorChoice } from '../feed/editorContext'
import { useT } from '../i18n'
import type { Dict } from '../i18n/en'
import { fitMark } from '../hooks/useToolsFit'
import type { MenuOption } from './Menu'
import { anchorFrom, type Anchor } from './StatusBar'
import s from './composer.module.css'

/**
 * A page with a band of lines picked out on it - the file in the editor, and a selection in it.
 *
 * A drawing rather than the terminal's "⧉": that sign is not in the panel's font and falls through to
 * whatever the system has, at a size and weight of its own beside a name set in ours (the same reason the
 * arrow on a message's card is drawn - see ReuseArrow).
 */
export const EditorIcon = ({ size = 12, struck = false }: { size?: number; struck?: boolean }) => (
  <svg
    viewBox="0 0 16 16"
    aria-hidden="true"
    width={size}
    height={size}
    fill="none"
    stroke="currentColor"
    strokeWidth="1.5"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M4.2 2.4h5.2l2.8 2.8v8a.8.8 0 01-.8.8H4.2a.8.8 0 01-.8-.8V3.2a.8.8 0 01.8-.8z" />
    <path d="M5.6 7.4h4.8M5.6 10.2h3.2" />
    {/* Not going, with no name beside it to strike through - the drawing is all there is to say so. */}
    {struck ? <path d="M2.2 13.8L13.8 2.2" /> : null}
  </svg>
)

/**
 * What the editor shows, in the field's bottom row - it goes with the message (see EditorContext.kt).
 *
 * Beside the paperclip on purpose: it is an attachment, one the panel makes by itself, and the hand that
 * attaches things is already there. A press opens its menu (see editorMenu) - this one message without it,
 * or every message, or none: the setting itself is one of the choices, so that switching it off for good
 * does not take a trip to the settings, and switching it back on does not either.
 *
 * With the setting off it stays (`muted`), as the drawing alone and struck: the way back has to be where
 * the switch was thrown. Without that the chip just vanished, and with it any sign that the editor had
 * ever gone along.
 *
 * It gives way first when the row runs short: the name steps out whole and the drawing stays, before any
 * of the squares beside it leave (see droppableTools); a muted chip leaves whole. The whole place stays in
 * the hover.
 */
export const EditorChip = ({
  editor,
  on,
  muted = false,
  bare = false,
  nameGone = false,
  open = false,
  onOpen,
}: {
  editor: EditorRef
  /** The next message carries it - the setting, or this tab's exception to it (see editorGoes). */
  on: boolean
  /** Sending the editor is switched off, and this message makes no exception: the drawing alone, struck. */
  muted?: boolean
  /** The drawing alone, beside a narrow field - see .editorChipBare. */
  bare?: boolean
  /**
   * The row ran short and the name stepped out of it (see droppableTools). Kept in the chip at no width
   * rather than taken out: its width is what tells the row when there is room for it again.
   */
  nameGone?: boolean
  /**
   * Its menu is open. The hover steps aside then: it unfolds upwards, into the very place the menu opens
   * into, and the pointer is still on the chip that was just pressed (see speaks in Tooltips).
   */
  open?: boolean
  /** Pressed: the menu opens right above it. */
  onOpen: (anchor: Anchor) => void
}) => {
  const t = useT()
  const place = editorLabel({ ...editor, name: editor.path })
  const hover = muted ? t.composer.editor.muted(place) : on ? t.composer.editor.on(place) : t.composer.editor.off(place)
  const named = !bare && !muted

  return (
    <button
      type="button"
      className={`${s.editorChip} ${on ? '' : s.editorChipOff} ${bare ? s.editorChipBare : ''}`}
      aria-haspopup="menu"
      aria-expanded={open}
      aria-label={hover}
      data-tooltip={open ? undefined : hover}
      data-tooltip-at="top"
      // Like the other buttons in the row: the press must not pull the caret out of the field.
      onMouseDown={(event) => event.preventDefault()}
      onClick={(event) => onOpen(anchorFrom(event.currentTarget))}
    >
      <EditorIcon struck={!on && (!named || nameGone)} />
      {named ? (
        <span className={`${s.editorChipText} ${nameGone ? s.editorChipNameGone : ''}`} {...fitMark('name')}>
          {editorLabel(editor)}
        </span>
      ) : null}
    </button>
  )
}

/** The chip menu's entries (see editorMenu); `once` is "not with this message" or "with this one only". */
export const EDITOR_ALWAYS = 'editor:always'
export const EDITOR_ONCE = 'editor:once'
export const EDITOR_NEVER = 'editor:never'

/**
 * The chip's menu: the setting either way, and between them this one message going the other way.
 *
 * Three entries rather than a press that flips: a press used to leave out one message, and that read as the
 * switch - it was off, and the next message, or the next tab, had it on again. What a press does is now
 * said before it is done, and the middle entry is the old press, so one message still goes without it in
 * two clicks. With the setting off the middle entry turns round - with this one only - for whoever keeps it
 * off and now and then asks about the lines in front of them.
 *
 * The file and lines in question stand as the menu's hint: the exception holds for them, and selecting
 * other lines starts over (see editorKey).
 */
export const editorMenu = (
  t: Dict,
  shared: boolean,
  choice: EditorChoice,
  editor: EditorRef,
): { title: string; hint: string; width: number; options: MenuOption[]; selected: string } => {
  const words = t.composer.editor.menu
  return {
    title: t.menu.titles.shareEditor.title,
    hint: editorLabel(editor),
    width: 300,
    options: [
      { id: EDITOR_ALWAYS, label: words.always, sub: words.alwaysSub },
      shared
        ? { id: EDITOR_ONCE, label: words.skip, sub: words.skipSub }
        : { id: EDITOR_ONCE, label: words.only, sub: words.onlySub },
      { id: EDITOR_NEVER, label: words.never, sub: words.neverSub },
    ],
    selected: choice === 'always' ? EDITOR_ALWAYS : choice === 'once' ? EDITOR_ONCE : EDITOR_NEVER,
  }
}
