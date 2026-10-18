import { useEffect, useState } from 'react'
import { projectRelative } from '../../feed/changes'
import type { ChangesItem, FileChange } from '../../feed/types'
import { useMissingFiles, useOpenFile } from '../../hooks/useOpenFile'
import { useT } from '../../i18n'
import s from '../feed.module.css'

/**
 * Putting back what the turn changed: one file, or every file the list holds (see ChangeRevert in the IDE) -
 * or, with `restore`, bringing the changes back after that. Nothing asks first: every revert has its way back
 * on the same row.
 */
export type RevertChanges = (id: string, files: FileChange[], restore?: boolean) => void

/** How long a pressed row waits for the IDE before it gives up waiting - the work itself takes a moment. */
const PRESS_PATIENCE_MS = 15_000

/**
 * How long a press goes unannounced. The IDE answers within a fifth of a second as a rule, and "Reverting…"
 * flashed for that long and replaced by "Reverted" was a jolt across every row of the list for nothing.
 */
const QUIET_PRESS_MS = 500

/**
 * A list longer than this stands folded to its first FOLDED_ROWS rows. Measured on a turn that created three
 * hundred files: open, the list was 6650 pixels - several screens of scrolling past the answer to reach the
 * input field.
 */
const FOLD_OVER = 12
const FOLDED_ROWS = 10

/**
 * The files a turn changed, at its end: a path, the lines added in green and the lines removed in red (see
 * ChangesItem). A file the turn created shows only what it added - there was nothing to remove from it.
 *
 * No heading and no sentence: the rows are the whole of what this says, and "Worked" right under them says
 * whose turn it was. A click on a row opens the file - by the full path the tool was given, so it is that
 * very file rather than a choice between files of the name.
 *
 * Each row has a button that puts the file back, and the list has one for all of them; a row put back has a
 * button that brings the changes back, and so does the list once more than one row is put back. A row is
 * put back only where it can be put back exactly: a file whose change came without its diff has no button
 * at all (see FileChange.edits). Where there is no editor (the phone) the rows are only read, and show what
 * a revert at the desk came to.
 */
export const ChangesRow = ({
  item,
  root,
  onRevert,
}: {
  item: ChangesItem
  /**
   * The project's folder as the IDE names it - a file inside it is shown from there, any other in full. Absent
   * on the phone, which is never told the path: there the path stands as the feed noted it (see FileChange.shown).
   */
  root?: string
  onRevert?: RevertChanges
}) => {
  const t = useT()
  // Pressed and not answered yet, by how many answers the file had at the press (see FileChange.answers).
  // Counted rather than judged by what the row says: a second press after a refusal is answered with the same
  // refusal, and a row that waited for its words to change waited for ever. Held here rather than in the feed -
  // a panel reloaded meanwhile simply shows the rows as the answer leaves them.
  const [pressed, setPressed] = useState<ReadonlyMap<string, { answers: number; restore: boolean; at: number }>>(new Map())
  const pendingOf = (file: FileChange) => {
    const press = pressed.get(file.path)
    return press && press.answers === (file.answers ?? 0) ? press : undefined
  }

  // An answered press is forgotten at once; one the IDE never answers - a window closed under it, a message
  // lost - after PRESS_PATIENCE_MS, so the row is pressable again instead of saying "Reverting…" for good.
  useEffect(() => {
    const prune = () =>
      setPressed((current) => {
        const waiting = [...current].filter(([path, press]) => {
          const file = item.files.find((candidate) => candidate.path === path)
          return file !== undefined && press.answers === (file.answers ?? 0) && Date.now() - press.at < PRESS_PATIENCE_MS
        })
        return waiting.length === current.size ? current : new Map(waiting)
      })
    prune()
    if (pressed.size === 0) return
    const timer = window.setTimeout(prune, PRESS_PATIENCE_MS)
    return () => window.clearTimeout(timer)
  }, [item.files, pressed])

  // A press still waiting past QUIET_PRESS_MS says so on its row; this re-draws the list at that moment.
  const [, setQuietOver] = useState(0)
  useEffect(() => {
    if (pressed.size === 0) return
    const timer = window.setTimeout(() => setQuietOver((count) => count + 1), QUIET_PRESS_MS)
    return () => window.clearTimeout(timer)
  }, [pressed])

  const revertable = (file: FileChange) => file.edits !== undefined && file.revert !== 'reverted'
  const restorable = (file: FileChange) => file.edits !== undefined && file.revert === 'reverted'
  // Counted with the rows still waiting, so the list's own buttons stay put while the IDE answers - they are
  // only switched off. Counted without them, the whole foot went away under "Revert all" and came back a moment
  // later as "Bring all back": the list jumped by a row each time.
  const revertCount = item.files.filter(revertable).length
  const restoreCount = item.files.filter(restorable).length
  const toRevert = item.files.filter((file) => revertable(file) && !pendingOf(file))
  const toRestore = item.files.filter((file) => restorable(file) && !pendingOf(file))
  const foldable = item.files.length > FOLD_OVER
  const [unfolded, setUnfolded] = useState(false)
  const shownFiles = foldable && !unfolded ? item.files.slice(0, FOLDED_ROWS) : item.files
  const footed = foldable || (onRevert !== undefined && item.files.filter((file) => file.edits !== undefined).length > 1)

  const act = (files: FileChange[], restore: boolean) => {
    if (!onRevert || files.length === 0) return
    onRevert(item.id, files, restore)
    const at = Date.now()
    setPressed((current) => new Map([...current, ...files.map((file) => [file.path, { answers: file.answers ?? 0, restore, at }] as const)]))
  }

  return (
    <div className={s.changes}>
      {shownFiles.map((file) => (
        <ChangedFile
          key={file.path}
          file={file}
          shown={root ? (projectRelative(file.path, root) ?? file.path) : file.shown}
          pending={(() => {
            const press = pendingOf(file)
            return press && Date.now() - press.at >= QUIET_PRESS_MS ? press.restore : undefined
          })()}
          waiting={pendingOf(file) !== undefined}
          slot={onRevert !== undefined}
          onRevert={onRevert && revertable(file) ? () => act([file], false) : undefined}
          onRestore={onRevert && restorable(file) ? () => act([file], true) : undefined}
        />
      ))}
      {/* The foot stands whenever the list has more than one file to act on, whatever is pressable in it right
          now - a foot that came and went moved the whole list by its height. */}
      {footed ? (
        <div className={s.changesFoot}>
          {foldable ? (
            <button type="button" className={`${s.changesRevertAll} ${s.changesFold}`} onClick={() => setUnfolded((open) => !open)}>
              {unfolded ? t.feed.changes.fewer : t.feed.changes.all(item.files.length)}
            </button>
          ) : null}
          {onRevert && restoreCount > 1 ? (
            <button
              type="button"
              className={s.changesRevertAll}
              disabled={toRestore.length === 0}
              onClick={() => act(toRestore, true)}
            >
              {t.feed.changes.restoreAll}
            </button>
          ) : null}
          {onRevert && revertCount > 1 ? (
            <button
              type="button"
              className={s.changesRevertAll}
              disabled={toRevert.length === 0}
              onClick={() => act(toRevert, false)}
            >
              {t.feed.changes.revertAll}
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

const ChangedFile = ({
  file,
  shown,
  pending,
  waiting,
  slot,
  onRevert,
  onRestore,
}: {
  file: FileChange
  /** The path as this row says it - from the project's root, or in full. */
  shown: string
  /**
   * Pressed and not answered for longer than a moment: true while the changes are being brought back, false while
   * being put back. Said on the row; a quicker answer goes unannounced.
   */
  pending: boolean | undefined
  /** Pressed and not answered at all - the button is off however short the wait. */
  waiting: boolean
  /** The list has revert buttons: a row without one keeps its place, so the figures stand in one column. */
  slot: boolean
  onRevert?: () => void
  onRestore?: () => void
}) => {
  const t = useT()
  const openFile = useOpenFile()
  // Pressed, and no file there any more - said on the row rather than a press that does nothing.
  const gone = useMissingFiles().has(file.path)

  // The name is what the eye looks for, so a long path gives up its folders first and never the name.
  const cut = Math.max(shown.lastIndexOf('/'), shown.lastIndexOf('\\'))
  const folder = cut < 0 ? '' : shown.slice(0, cut + 1)
  const name = shown.slice(cut + 1)
  const reverted = file.revert === 'reverted'

  // What came of a revert or of bringing it back, said on the row it happened to. Nothing while nobody has
  // asked for either.
  const refusal = reverted ? file.restoreRefused : file.revert
  const note =
    pending !== undefined
      ? pending
        ? t.feed.changes.restoring
        : t.feed.changes.reverting
      : refusal === 'changed'
        ? t.feed.changes.changed
        : refusal === 'missing'
          ? t.feed.changes.missing
          : refusal === 'failed'
            ? t.feed.changes.failed
            : gone
              ? t.feed.changes.missing
              : reverted
                ? t.feed.changes.reverted
                : file.deleted
                  ? t.feed.changes.deleted
                  : file.movedFrom
                    ? t.feed.changes.movedFrom(baseName(file.movedFrom))
                    : ''
  const refused = pending === undefined && (refusal === 'changed' || refusal === 'missing' || refusal === 'failed' || gone)

  const content = (
    <>
      {/* Cut from its start rather than its end: the folder next to the file says more than the root. */}
      <span className={s.changeFolder}>
        <span className={s.changeFolderText}>{folder}</span>
      </span>
      <span className={s.changeName}>{name}</span>
      {note ? <span className={refused ? `${s.changeNote} ${s.changeNoteBad}` : s.changeNote}>{note}</span> : null}
      {file.uncounted ? (
        <span className={s.changeUncounted}>±</span>
      ) : file.deleted ? (
        // What went: the file as it stood before the turn, whatever the turn did to it on the way.
        <span className={`${s.changeRemoved} ${s.changeRemovedAlone}`}>−{Math.max(file.removed - file.added, 0)}</span>
      ) : (
        <>
          <span className={s.changeAdded}>+{file.created ? Math.max(file.added - file.removed, 0) : file.added}</span>
          {file.created ? null : <span className={s.changeRemoved}>−{file.removed}</span>}
        </>
      )}
    </>
  )

  const rowClass = reverted ? `${s.changeRow} ${s.changeRowReverted}` : s.changeRow
  const button = onRevert ?? onRestore
  const label = onRevert ? t.feed.changes.revert : t.feed.changes.restore

  // A file the turn deleted has nothing to open until a revert writes it back.
  const openable = openFile && !(file.deleted && !reverted)

  return (
    <div className={s.changeLine}>
      {openable ? (
        <button
          type="button"
          className={`${rowClass} ${s.changeRowLink}`}
          aria-label={`${t.feed.copy.openFile}: ${shown}`}
          data-tooltip={t.feed.copy.openFile}
          onClick={() => openFile?.({ path: file.path })}
        >
          {content}
        </button>
      ) : (
        <div className={rowClass}>{content}</div>
      )}
      {button ? (
        <button
          type="button"
          className={s.changeRevert}
          aria-label={`${label}: ${shown}`}
          data-tooltip={label}
          disabled={waiting}
          onClick={button}
        >
          {/* The same arrow both ways: back for a revert, mirrored forward for bringing the changes back. */}
          <svg width="13" height="13" viewBox="0 0 16 16" aria-hidden="true" className={onRevert ? undefined : s.changeRestoreIcon}>
            <path
              d="M5.5 3.5 2.5 6.5l3 3M3 6.5h6.5a4 4 0 0 1 0 8H7"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      ) : slot ? (
        <span className={s.changeRevertSlot} />
      ) : null}
    </div>
  )
}

/** The last part of a path, by either separator - what a moved file's row says it came from. */
const baseName = (path: string): string => path.slice(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
