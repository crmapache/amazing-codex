import { memo, useCallback, useMemo, type ReactNode } from 'react'
import { LinkedText } from './LinkedText'
import { Caret } from './Caret'
import { CopyButton } from './CopyButton'
import { PinButton, PinIcon } from './PinButton'
import { MoreButton } from './MoreButton'
import { CardMenu } from './CardMenu'
import { chipFile, chipLabel, chipTitle, pasteBlockPreview, pasteBody, pasteLineCount } from '../../feed/reference'
import { COPY_ATTRIBUTE } from '../../feed/copy'
import { clipboardMessage, clipboardText } from '../../feed/tokens'
import { reusableMessage } from '../../feed/reuse'
import { rewindable } from '../../feed/rewind'
import { messageMenu, type MessageAction } from '../../feed/messageMenu'
import type { Chip, ChipKind, UserItem } from '../../feed/types'
import type { CardState } from '../../hooks/useCardState'
import { useOpenFile, useOpenPicture } from '../../hooks/useOpenFile'
import { useSaidImages } from '../../hooks/useSaidImages'
import { chipOpening, chipPreview, markPreview, pastedOrdinals } from '../../imagePreview'
import { editorLabel } from '../../feed/editorContext'
import type { EditorRef, PreviewPlace } from '../../protocol'
import { EditorIcon } from '../EditorChip'
import { pastMenu } from '../Menu'
import s from '../feed.module.css'
import { useT } from '../../i18n'

const CHIP_CLASS: Record<ChipKind, string> = {
  file: s.chipFile ?? '',
  img: s.chipImg ?? '',
  dir: s.chipDir ?? '',
  cmd: s.chipCmd ?? '',
  ref: s.chipRef ?? '',
  quote: s.chipQuote ?? '',
  paste: s.chipPaste ?? '',
}

interface UserCardProps {
  item: UserItem
  /** What is unfolded in the feed - here it is the pastes inside the message (see PasteView). */
  cards: CardState
  /**
   * What stands over the message instead of "YOU" - handed in by a feed whose "you" side is not a person.
   *
   * The log of a scenario's step is one such feed: everything sent there was sent by the main thread of
   * the run (see ScenarioEngine), and a person reading it at three in the morning has to be able to tell
   * that at a glance rather than by remembering what a scenario is. Absent everywhere else, and that is
   * the point: the same transcript opened as an ordinary chat is a place where a person really does type,
   * so there it says "YOU" as it always did.
   */
  userLabel?: string
  /** Open a link from one's own message in the system browser. */
  onOpenLink: (url: string) => void
  /**
   * Put this message back into the input field, chips and quotes and all (see feed/reuse.ts).
   *
   * Absent where there is no field to put it into - the phone has one, but its own, holding plain text
   * and its own pictures rather than the panel's tokens (see mobile/screens/Composer). There the copy
   * button beside this one is the whole answer.
   */
  onReuse?: (item: UserItem) => void
  /**
   * Open the rewind dialog over this message - cut the conversation back to before it, put the code back,
   * or both (see RewindDialog and feed/rewind.ts).
   *
   * Absent where the conversation is not one's own to cut: a step log of a scenario, a phone (its sheet
   * behind the three dots carries the same action, see MessageSheet).
   */
  onRewind?: (item: UserItem) => void
  /**
   * Pin this message over the conversation, or unpin it (see feed/pins.ts). Absent where there is no
   * strip to pin it to - the phone, the same way putting the message back is absent there.
   */
  onPin?: () => void
  pinned: boolean
  /** Whether the strip is already full - the hint says so before the press (see PinButton). */
  pinsFull: boolean
  /**
   * Everything else this message can do - quoting it, forking from it - in the phone's sheet behind the
   * three dots (see MessageSheet). Handed in by the phone alone: at the desk a quote is a selection and a
   * fork is a command, and neither of those exists under a thumb. At the desk the same dots open the
   * card's own menu instead (see feed/messageMenu.ts).
   */
  onActions?: () => void
}

/**
 * The drawings the menu's entries wear - the very ones these actions wore as buttons in the head. The pin is
 * filled while the message is pinned: the one drawing says both what the entry does and how things stand.
 * Shared with an answer's menu (see TextCard), which holds the pin alone.
 */
export const messageActionIcon: Record<MessageAction, (pinned: boolean) => ReactNode> = {
  reuse: () => <ReuseArrow />,
  pin: (pinned) => <PinIcon filled={pinned} />,
  rewind: () => <RewindArrow />,
}


export const UserCard = ({
  item,
  cards,
  userLabel,
  onOpenLink,
  onReuse,
  onRewind,
  onPin,
  pinned,
  pinsFull,
  onActions,
}: UserCardProps) => {
  const t = useT()
  /*
   * What copying and putting back do, worked out here rather than on the press: the menu has to say
   * beforehand that a picture cannot come back (its bytes are not in a conversation read off the disk),
   * and a person who learns that after pressing has already lost the field they were typing in.
   */
  const reuse = useMemo(() => reusableMessage(item), [item])
  const copied = useMemo(() => clipboardMessage(item), [item])
  const rewind = rewindable(item)
  const menu = messageMenu(t, {
    reuse: onReuse ? reuse.lostImages : null,
    pin: onPin ? { pinned, full: pinsFull } : null,
    rewind: onRewind ? rewind : null,
  }).map((option) => ({ ...option, icon: messageActionIcon[option.id](pinned) }))

  return (
  <div className={s.user}>
    <div className={s.userHead}>
      {/* A message the panel said itself wears its own word, and the hint says why (see UserItem.auto): YOU
          over words nobody typed is the one thing this head may not say. */}
      <span className={s.label} data-tooltip={!userLabel && item.auto && item.auto !== 'unknown' ? t.feed.auto.why[item.auto] : undefined}>
        {userLabel ?? (item.auto ? t.feed.auto.label : t.feed.you)}
      </span>
      <span className={s.time}>{item.time}</span>
      <div className={s.spacer} />

      {/* A box of their own rather than more children of the head: the head spaces its children out for a
          label and a time standing apart, and little squares set that far from each other read as unrelated
          buttons. The gap is the group's own, the same one the bubble and the heart stand at down by the
          field (see .endPair). */}
      <div className={s.userActions}>
        {/* The pin stays out here while the message is pinned, lit, and a press takes it off: the strip is at
            the top of the feed and this card may be a screen away from it, so the lit pin is the one thing
            that answers "is this the one up there?" without opening anything (see .pinOn). Unpinned, it
            lives in the menu with the rest. */}
        {onPin && pinned ? <PinButton pinned full={pinsFull} className={s.userAction} onPin={onPin} /> : null}

        {/* The message itself, and the paths of what was attached to it rather than the captions the
            chips wear: a caption means something inside this panel and nothing in a terminal or a task
            (see clipboardText). This is the button the feedback asked for by name, and the one action left
            standing in the head - everything else is behind the dots beside it. */}
        <CopyButton text={copied} className={s.userAction} title={t.feed.copyMessage} />

        {onActions ? (
          <MoreButton className={s.userAction} label={t.feed.moreActions} onClick={onActions} />
        ) : menu.length > 0 ? (
          <CardMenu
            className={s.userAction}
            label={t.feed.moreActions}
            title={t.feed.messageMenu}
            width={272}
            options={menu}
            onPick={(id) => {
              if (id === 'reuse') onReuse?.(item)
              if (id === 'pin') onPin?.()
              if (id === 'rewind') onRewind?.(item)
            }}
          />
        ) : null}
      </div>
    </div>

    {/* The quote is shown whole right here: without it a question like "but why?" hangs in the air -
        there is no telling what it is about. */}
    {item.quotes.map((quote, index) => (
      <blockquote key={index} className={s.userQuote}>
        {quote}
      </blockquote>
    ))}

    <SentTokens
      className={s.userBody}
      tokens={item.tokens}
      uuid={item.uuid}
      onOpenLink={onOpenLink}
      isOpen={(index) => cards.isOpen(`${item.id}:paste:${index}`)}
      onToggle={(index) => cards.toggle(`${item.id}:paste:${index}`)}
    />

    {item.editor ? <EditorLine editor={item.editor} /> : null}
  </div>
  )
}

/**
 * The words and the attachment chips of a sent message, as the feed draws them.
 *
 * Shared with the run's timeline (see ScenarioRunTab): what the person writes to a run's main thread comes
 * out of the same field as a chat message, files, pictures and pasted text included, and reads the same way
 * there. Whether a folded paste is open is the caller's to keep - the feed keeps it with every other card.
 *
 * [uuid] is the message's line in the transcript: a pasted picture whose bytes the feed no longer holds is
 * read back from there for the preview under the pointer (see useSaidImages).
 */
export const SentTokens = ({
  tokens,
  uuid,
  className,
  onOpenLink,
  isOpen,
  onToggle,
}: {
  tokens: UserItem['tokens']
  uuid?: string
  className?: string
  onOpenLink: (url: string) => void
  isOpen: (index: number) => boolean
  onToggle: (index: number) => void
}) => {
  const sessionId = useSaidImages()
  // Worked out once per message rather than per chip and per render: the feed repaints on every chunk of a
  // printing answer, and a new place object each time would wake every memoised chip in it.
  const places = useMemo(
    () =>
      pastedOrdinals(tokens).map((image): PreviewPlace | null =>
        image >= 0 && sessionId && uuid ? { kind: 'said', sessionId, uuid, image } : null,
      ),
    [tokens, sessionId, uuid],
  )

  return (
  <div className={className}>
    {tokens.map((token, index) =>
      token.kind === 'text' ? (
        <TextToken key={index} value={token.value} echo={token.echo === true} onOpenLink={onOpenLink} />
      ) : token.chip.kind === 'paste' ? (
        // A paste with nothing after it in the message takes a whole line: the room is free anyway,
        // and seven words in a narrow chip are not enough to recall what exactly was sent.
        <PasteView
          key={index}
          chip={token.chip}
          block={index === tokens.length - 1}
          open={isOpen(index)}
          onToggle={() => onToggle(index)}
        />
      ) : (
        <ChipView key={index} chip={token.chip} elsewhere={places[index]} />
      ),
    )}
  </div>
  )
}

/**
 * What the editor showed when the message went - the file, and how many lines of it were selected. The
 * agent was shown it beside the message (see EditorContext.kt), and a question like "why is this slow?" is
 * only readable later with the thing it was about in sight.
 *
 * Opens those lines in the editor, the way a file's chip opens its file; where there is no editor (the
 * phone) it is the same words without the click.
 */
const EditorLine = memo(({ editor }: { editor: EditorRef }) => {
  const t = useT()
  const openFile = useOpenFile()
  const count = editor.from !== undefined && editor.to !== undefined ? editor.to - editor.from + 1 : 0
  const words = count > 0 ? t.feed.editor.selected(count, editor.name) : t.feed.editor.opened(editor.name)
  const place = editorLabel({ ...editor, name: editor.path })

  if (!openFile) {
    return (
      <div className={s.userEditor} data-tooltip={place}>
        <EditorIcon size={11} />
        <span className={s.userEditorText}>{words}</span>
      </div>
    )
  }

  return (
    <button
      type="button"
      className={`${s.userEditor} ${s.userEditorOpens}`}
      data-tooltip={t.feed.editor.open(place)}
      {...pastMenu}
      onClick={() =>
        openFile({
          path: editor.path,
          ...(editor.from !== undefined ? { line: editor.from, endLine: editor.to } : {}),
        })
      }
    >
      <EditorIcon size={11} />
      <span className={s.userEditorText}>{words}</span>
    </button>
  )
})

/**
 * An arrow curving back into a field - "take this back to where it was written".
 *
 * A drawing rather than a character: ↩ and ⤺ are not in the panel's font and fall through to whatever
 * the system has, at a size and weight of their own beside a letter set in ours (the same reason a
 * hotkey's caps are drawn - see HotkeyCaps). Shared with a queued message's row (see Queue), which takes
 * its message back into the field just as this card does.
 */
export const ReuseArrow = () => (
  <svg viewBox="0 0 16 16" aria-hidden="true" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3.4 6.6h6.4a3 3 0 0 1 0 6H6.2" />
    <path d="M5.8 4.2 3.4 6.6l2.4 2.4" />
  </svg>
)

/**
 * An arrow turning back round a circle - "go back to how it was here". The circle is what tells it from the
 * reuse arrow above it in the card's menu, which takes a message somewhere rather than taking time back.
 */
const RewindArrow = () => (
  <svg viewBox="0 0 16 16" aria-hidden="true" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3.2 8a4.8 4.8 0 1 0 1.4-3.4" />
    <path d="M3.4 2.6v2.6h2.6" />
    <path d="M8 5.6V8l1.7 1.2" />
  </svg>
)

/**
 * The text is shown exactly as it was typed - without markup and without rewrapping of ours: the lines
 * are preserved by .userBody itself (white-space: pre-wrap). An address in the text stays a live link: it
 * is clicked rather than retyped by hand. The log of a scenario, whose "you" side is written by the
 * engine in markdown, is drawn the same way on purpose: read as markup it was a larger, different-looking
 * text than the same conversation opened as a chat, and the two are one feed.
 *
 * We dim only what the panel put in itself - the agent's question repeated beside the chosen answer (see
 * UserToken.echo). What a person typed never fades, however it happens to end: the repeat used to be
 * guessed by a question mark at the end of the line, and an ordinary question to the agent ("did you
 * merge it yourself?") went pale - that is, exactly what was the whole message looked unimportant.
 */
const TextToken = ({
  value,
  echo,
  onOpenLink,
}: {
  value: string
  echo: boolean
  onOpenLink: (url: string) => void
}) => {
  return (
    <span className={echo ? s.userEcho : undefined}>
      <LinkedText text={value} onOpenLink={onOpenLink} />
    </span>
  )
}

/**
 * An attachment chip inside a sent message - and inside a queued one (see Queue).
 *
 * A memo component of its own for the sake of a collapsed paste: its caption and tooltip are computed
 * from the text itself, while the feed repaints on every chunk of a printing answer. The chip meanwhile
 * does not change at all - and recomputing it a hundred times a second serves nothing.
 *
 * A picture's chip shows the picture under the pointer (see imagePreview.ts). [elsewhere] is where the
 * message keeps it when the chip itself does not - its line in the transcript, its bytes in the queue.
 */
export const ChipView = memo(({ chip, elsewhere }: { chip: Chip; elsewhere?: PreviewPlace | null }) => {
  const t = useT()
  const openFile = useOpenFile()
  const openPicture = useOpenPicture()
  const behind = useMemo(() => chipPreview(chip, elsewhere), [chip, elsewhere])
  // A picture opens by a road of its own, whatever holds it - a file, the page's bytes, the queue, the
  // transcript (see useOpenPicture): a conversation brought back after a restart is all pictures without
  // a file, and the click's failure has to be told over the chip that was pressed.
  const opening = useMemo(() => chipOpening(chip, elsewhere), [chip, elsewhere])
  const picture = openPicture ? opening : null
  const file = openFile ? chipFile(chip) : null
  const opens = file !== null || picture !== null
  const preview = useMemo(() => (behind && opens ? { ...behind, opens } : behind), [behind, opens])
  const mark = useCallback((node: HTMLElement | null) => {
    if (node) markPreview(node, preview)
  }, [preview])
  // No attribute at all when there is nothing to add (see chipTitle): an empty one is still a hover, and
  // an empty box under the pointer reads as something that failed to load. And none on a picture's chip:
  // the picture is its hover, and says what a click does under itself.
  const title = preview ? '' : chipTitle(chip)
  // What the chip means in the clipboard travels beside it: copied as it looks, `App.tsx` is a name with
  // no path in it, while what the agent was handed is the path itself (see copiedText).
  const copy = { [COPY_ATTRIBUTE]: clipboardText({ kind: 'chip', chip }) }

  /*
   * A chip that stands for a file opens it - a button rather than a span with a click on it, so that a
   * keyboard reaches it as well as a pointer. It was the one file name in the feed that did nothing when
   * clicked, and a person who has just attached a file is the surest to click it. Where there is no
   * editor (the phone), or nothing to open (a folder, a command), it stays the plain chip below.
   *
   * The hover says what a click does, the way it does on every path that opens (see PathLink) - unless
   * the name had to be cut, and then the hover is the only place the whole of it exists (see chipTitle).
   */
  if ((file && openFile) || (picture && openPicture)) {
    return (
      <button
        ref={mark}
        type="button"
        className={`${s.chip} ${CHIP_CLASS[chip.kind]} ${s.chipOpens}`}
        aria-label={`${t.feed.copy.openFile}: ${chip.value}`}
        data-tooltip={preview ? undefined : title || t.feed.copy.openFile}
        {...copy}
        {...pastMenu}
        onClick={(event) => {
          if (file && openFile) openFile(file)
          else if (picture && openPicture) openPicture(picture, event.currentTarget)
        }}
      >
        {chipLabel(chip)}
      </button>
    )
  }

  return (
    <span ref={mark} className={`${s.chip} ${CHIP_CLASS[chip.kind]}`} data-tooltip={title || undefined} {...copy}>
      {/* There is deliberately no attachment type icon - see renderChipNode in Composer: the chip here is
          a reminder of what was sent, and the colour of the kind is reminder enough. */}
      {chipLabel(chip)}
    </span>
  )
})

/**
 * A paste in a sent message, and the one attachment that opens.
 *
 * It opens because there is nowhere else to read it. A file chip stands for a file that is still on
 * disk; a paste stands for text that exists nowhere but in this message, and the collapsed chip shows
 * seven words of it. That used to be what the hover hint was for - it carried the whole text - and a
 * hundred lines in a hint the width of a hint is a strip of twenty characters down the window, cut off
 * at its edge (see chipTitle). Here the text is in the feed instead: it is selected, copied and found
 * by the browser's own search, and the phone - which has no hover at all and so had no way to see a
 * paste whatsoever - opens it with the same tap.
 *
 * And precisely because it opens, the collapsed chip carries no hint at all - it is the one chip in the
 * panel without one. A hint that repeats the first lines of the text stands over the feed at the very
 * moment the click puts those same lines into it, so it covers the answer it was previewing; and the
 * click itself needs no announcing, since the caret says the chip folds the same way a folded group of
 * calls does.
 *
 * [block] is a paste with nothing after it in the message: the room is free anyway, so the collapsed
 * form takes the whole width and shows the first lines rather than seven words. Standing in the middle
 * of a message it stays an ordinary chip, and the text it opens goes below it on a line of its own.
 *
 * memo for the same reason as the ordinary chip: the feed repaints on every chunk of a printing answer,
 * while a sent paste never changes.
 */
const PasteView = memo(({
  chip,
  block,
  open,
  onToggle,
}: {
  chip: Chip
  block: boolean
  open: boolean
  onToggle: () => void
}) => {
  const t = useT()
  const text = chip.text ?? ''

  // A hundred kilobytes is an ordinary paste, and both of these walk the text whole. Neither is wanted
  // on a repaint that changed a word in the answer below.
  const lines = useMemo(() => pasteLineCount(text), [text])
  const body = useMemo(() => (open ? pasteBody(text) : null), [open, text])

  // A button rather than a span with a click on it: this is the one chip that does something, and a
  // keyboard has to be able to reach it as well as a pointer.
  const collapsed = block ? (
    <button
      type="button"
      className={`${s.chip} ${s.chipPaste} ${s.chipPasteBlock} ${s.chipPasteOpens}`}
      {...pastMenu}
      onClick={onToggle}
    >
      <span className={s.chipPasteHead}>
        {/* The same arrow as on a folded group of calls: what folds in this panel says so with it. */}
        <Caret open={false} />
        <span className={s.chipPasteCount}>{t.feed.pastedLines(lines)}</span>
      </span>
      <span className={s.chipPasteText}>{pasteBlockPreview(text)}</span>
    </button>
  ) : (
    <button
      type="button"
      className={`${s.chip} ${s.chipPaste} ${s.chipPasteOpens}`}
      {...pastMenu}
      onClick={onToggle}
    >
      {chipLabel(chip)}
    </button>
  )

  if (!open || !body) return collapsed

  return (
    <>
      {/* The chip stays where it was when it is not the whole line: the message keeps reading the way it
          was written, with the text hanging below rather than in place of the attachment. */}
      {!block && collapsed}
      <span className={`${s.chip} ${s.chipPaste} ${s.chipPasteBlock} ${s.chipPasteFull}`}>
        <span className={s.chipPasteHead}>
          {/* Only this closes it back: inside the open text a click is a person selecting a line, and a
              paste that collapses under a selection cannot be copied by hand at all. */}
          <button
            type="button"
            className={s.chipPasteToggle}
            data-tooltip={t.feed.pasteClose}
            {...pastMenu}
            onClick={onToggle}
          >
            <Caret open />
            {t.feed.pastedLines(lines)}
          </button>
          {/* The whole paste travels, however much of it is drawn - see pasteBody. */}
          <CopyButton text={text} className={s.chipPasteCopy} title={t.feed.copyPaste} />
        </span>
        <span className={s.chipPasteBody}>{body.text}</span>
        {body.shownLines < body.lines && (
          <span className={s.chipPasteCut}>{t.feed.pasteShown(body.shownLines, body.lines)}</span>
        )}
      </span>
    </>
  )
})
