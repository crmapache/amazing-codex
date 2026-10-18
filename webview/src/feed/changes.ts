import type { AgentUserEvent, ContentBlock, FileEdit, FilesSettledEvent, RevertOutcome, ToolUseResult } from '../protocol'
import type { PanelState } from './panelState'
import { isOpenablePath } from './paths'
import { countUnified, hunksFor, resultToText, shortenPath, unifiedPatch } from './tools'
import type { FeedItem, FileChange, ToolItem } from './types'

/**
 * The files a turn changed, counted as the results come and laid into the feed as one row at its end
 * (see ChangesItem).
 *
 * The figures come from the CLI rather than from the call. Every Edit and Write answers with the diff it
 * actually applied - `structuredPatch`, its lines marked `+` and `-` - and a Write that brought a file into
 * being says `type: 'create'`. The call alone cannot tell either: `replace_all` changes the text as many
 * times as it occurs, and a Write over an existing file removes lines nobody named.
 *
 * Codex says it differently, and truer: a patch's card carries the very diff Codex applied (`unified_diff`, see
 * CodexDialect.editInput in the plugin), and each file of the patch answers with a small structured half of its
 * own - the file, `create`, `update` or `delete`, and where a moved file came from (CodexDialect.fileHalf). The
 * hunks are read off the card's diff rather than sent a second time beside it.
 */

/** The tools that change a file. Anything else that names one - a Read - only looks at it. */
const CHANGING = new Set(['Edit', 'MultiEdit', 'Write', 'NotebookEdit'])

/** What a Write says in its text when it created the file - read only when the structured half is missing. */
const CREATED = /^File created successfully/

/** What one tool result changed, before it is told apart by where the file lies and which call it was. */
type Change = Omit<FileChange, 'shown' | 'firstCall' | 'revert'>

/**
 * The change one result made, or nothing when it made none.
 *
 * [toolName] and [input] are the call's, when the call is known. A subagent's calls are not - they are
 * lines in its own log rather than cards of this feed - and then the structured result alone decides: it
 * names the file and carries the diff, which is all an edit needs. Without it nothing is guessed for a
 * subagent.
 *
 * For a call of this feed the call itself stands in when the structured half is missing: a CLI old enough
 * not to send it, or a result cut down on the way to the phone. The counts are then the ones the card
 * shows over its diff.
 */
export const changeOf = (
  call: { toolName?: string; input?: unknown },
  structured: ToolUseResult | undefined,
  text: string,
  isError: boolean,
): Change | null => {
  if (isError) return null
  if (call.toolName !== undefined && !CHANGING.has(call.toolName)) return null

  const input = asObject(call.input)
  const result = asObject(structured)
  const path = str(result.filePath) || str(input.file_path) || str(input.notebook_path)
  if (!path || !isOpenablePath(path)) return null

  // Counted from the call's own text where there is one: the transcript and the phone shorten long strings
  // inside a result, and the call's arguments are what they spare (see JournalTrim).
  if (result.type === 'create') {
    const content = str(input.content) || str(result.content)
    return { path, added: countLines(content), removed: 0, created: true, edits: [{ kind: 'create', content }] }
  }

  // A file a Codex patch deleted: what went is the text it held - the card's own argument (an EDIT that empties it,
  // see CodexDialect.fileCalls), or for a subagent the structured half. A revert writes it back.
  if (result.type === 'delete' || (structured === undefined && input.deleted === true)) {
    const content = str(input.old_string) || str(result.content)
    return { path, added: 0, removed: countLines(content), created: false, deleted: true, edits: [{ kind: 'delete', content }] }
  }

  // A Codex patch of a file: the hunks are the diff the card already carries (or, for a subagent, the structured
  // half), read the way the card reads it. A move is a change of its own, made before the patch's hunks - a file
  // moved and left as it was is a row all the same.
  const unified = str(input.unified_diff) || str(result.unified_diff)
  const said = str(result.movedFrom) || str(input.moved_from)
  // A move onto its own path is no move.
  const movedFrom = said !== path ? said : ''
  if ((unified || movedFrom) && !Array.isArray(result.structuredPatch)) {
    const hunks = unifiedPatch(unified)
    // A diff cut short on the way (see unifiedPatch) is counted as far as it goes and has no exact undo.
    const edits: FileEdit[] | undefined =
      hunks === null
        ? undefined
        : [...(movedFrom ? [{ kind: 'move' as const, from: movedFrom }] : []), ...(hunks.length > 0 ? [{ kind: 'patch' as const, hunks }] : [])]
    return counted(path, {
      ...countUnified(unified),
      created: false,
      ...(movedFrom ? { movedFrom } : {}),
      ...(edits && edits.length > 0 ? { edits } : {}),
    })
  }

  // A big edit comes back from the CLI with an empty diff (measured on 2.1.293: a replace_all across an 8000-line
  // file). The IDE works one out from the CLI's copy of the file and writes it in (see UndiffedEdits); the phone
  // is sent only its counts. When neither is there - a transcript, an IDE that found no copy - the file is listed
  // all the same: it did change, and a row without figures is truer than no row.
  // An empty diff from a write of exactly the text the file already held - nothing changed. Anything else with an
  // empty diff is a big change the CLI did not diff: an edit always changes something, and the original it sends
  // beside a big one may be cut short (the live stream carries part of it, the transcript none).
  const written = str(input.content) || str(result.content)
  const unchanged = written !== '' && result.originalFile === written
  if (Array.isArray(result.structuredPatch) && result.structuredPatch.length === 0 && !unchanged) {
    const counts = asObject(result.accCounts)
    if (typeof counts.added === 'number' && typeof counts.removed === 'number') {
      return counted(path, { added: counts.added, removed: counts.removed, created: false })
    }
    if (call.toolName === undefined) return null
    return counted(path, undiffed(call.toolName, input, text))
  }

  if (Array.isArray(result.structuredPatch)) {
    const hunks = hunksOf(result.structuredPatch)
    return counted(path, { ...patchCounts(result.structuredPatch), ...(hunks ? { edits: [{ kind: 'patch', hunks }] } : {}) })
  }

  if (call.toolName === undefined) return null
  return counted(path, fromCall(call.toolName, input, text))
}

/**
 * The patch as the IDE needs it to undo it, or nothing when any hunk of it is not what a hunk should be -
 * half a patch undone would be a file nobody wrote.
 */
const hunksOf = (patch: unknown[]): Extract<FileEdit, { kind: 'patch' }>['hunks'] | undefined => {
  const hunks: Extract<FileEdit, { kind: 'patch' }>['hunks'] = []
  for (const hunk of patch) {
    const fields = asObject(hunk)
    const lines = fields.lines
    if (typeof fields.newStart !== 'number' || !Array.isArray(lines) || !lines.every((line) => typeof line === 'string')) {
      return undefined
    }
    // The old start is where bringing the change back looks first (see ChangeRevert.redo).
    const oldStart = typeof fields.oldStart === 'number' ? fields.oldStart : fields.newStart
    hunks.push({ oldStart, newStart: fields.newStart, lines: lines as string[] })
  }
  return hunks.length > 0 ? hunks : undefined
}

/** A change that changed nothing - a Write of the same text, an edit undone by the next - is no row. A move is one. */
const counted = (path: string, change: Omit<Change, 'path'>): Change | null =>
  change.created || change.deleted || change.movedFrom || change.uncounted || change.added > 0 || change.removed > 0
    ? { path, ...change }
    : null

/**
 * An edit the CLI reported with no diff and nobody filled one in: a single replacement is still counted from the
 * call, exactly as its card counts it; a replacement of every occurrence, or a file written over, cannot be.
 */
const undiffed = (toolName: string, input: Record<string, unknown>, text: string): Omit<Change, 'path'> => {
  if ((toolName === 'Edit' && input.replace_all === true) || toolName === 'Write' || toolName === 'MultiEdit') {
    return { added: 0, removed: 0, created: false, uncounted: true }
  }
  return fromCall(toolName, input, text)
}

/** The lines a patch added and removed. `\ No newline at end of file` is a note about a line, not a line. */
const patchCounts = (patch: unknown[]): Omit<Change, 'path' | 'edits'> => {
  let added = 0
  let removed = 0
  for (const hunk of patch) {
    const lines = asObject(hunk).lines
    if (!Array.isArray(lines)) continue
    for (const line of lines) {
      if (typeof line !== 'string') continue
      if (line.startsWith('+')) added += 1
      else if (line.startsWith('-')) removed += 1
    }
  }
  return { added, removed, created: false }
}

/** The counts out of the call itself, for a result that came without its diff. */
const fromCall = (toolName: string, input: Record<string, unknown>, text: string): Omit<Change, 'path'> => {
  if (toolName === 'Write') {
    return { added: countLines(str(input.content)), removed: 0, created: CREATED.test(text.trim()) }
  }

  if (toolName === 'NotebookEdit') {
    const added = input.edit_mode === 'delete' ? 0 : countLines(str(input.new_source))
    return { added, removed: 0, created: false }
  }

  // MultiEdit keeps its pairs in a list; each is drawn the way a single Edit's card draws its own.
  const pairs = toolName === 'MultiEdit' && Array.isArray(input.edits) ? input.edits : [input]
  let added = 0
  let removed = 0
  for (const pair of pairs) {
    for (const hunk of hunksFor('change', 'Edit', pair, '')) {
      for (const line of hunk.lines) {
        if (line.kind === 'add') added += 1
        else if (line.kind === 'del') removed += 1
      }
    }
  }
  return { added, removed, created: false }
}

/**
 * The turn's list with one more change in it - added to the file's row when the turn already touched it.
 *
 * A file the turn created stays created whatever was done to it afterwards: its row says how long it came
 * out, and that is what was added minus what the later edits took out again.
 *
 * A file moved goes on as the row of where it came from, under its new path: the turn changed one file, and its
 * edits in the order they were made are what a revert undoes - the patches at the new place, the move, the patches
 * before it. A file the turn created and then deleted leaves no row: the turn left nothing there. One deleted and
 * written again is a file the turn changed, no longer deleted.
 */
export const withChange = (list: FileChange[], change: FileChange): FileChange[] => {
  let at = list.findIndex((file) => file.path === change.path)
  if (at < 0 && change.movedFrom) at = list.findIndex((file) => file.path === change.movedFrom)
  if (at < 0) return [...list, change]

  const file = list[at]
  if (!file) return [...list, change]
  if (file.created && change.deleted) return list.filter((_, index) => index !== at)

  // One change without its diff leaves the whole file without an exact undo, and one without figures leaves it
  // without figures.
  const edits = file.edits && change.edits ? [...file.edits, ...change.edits] : undefined
  const created = file.created || (change.created && !file.deleted)
  const deleted = change.deleted ?? (change.created ? undefined : file.deleted)
  // Where it stood before the turn: the first move's source, and nowhere for a file the turn created.
  const movedFrom = created ? undefined : (file.movedFrom ?? change.movedFrom)
  const { deleted: _deleted, movedFrom: _movedFrom, ...rest } = file
  const merged: FileChange = {
    ...rest,
    path: change.path,
    shown: change.shown,
    added: file.added + change.added,
    removed: file.removed + change.removed,
    created,
    ...(deleted ? { deleted: true as const } : {}),
    ...(movedFrom && movedFrom !== change.path ? { movedFrom } : {}),
    ...(edits ? { edits } : { edits: undefined }),
    ...(file.uncounted || change.uncounted ? { uncounted: true as const } : {}),
  }
  return list.map((entry, index) => (index === at ? merged : entry))
}

/**
 * The changes the tool results in this event made, added to the turn's list.
 *
 * The structured half belongs to the event rather than to a block, so it is trusted only when the event
 * holds one result - which is how the CLI sends them, one message per result. An event with several is
 * counted from the calls instead.
 */
export const noteChanges = (state: PanelState, event: AgentUserEvent, blocks: ContentBlock[]): PanelState => {
  const results = blocks.filter((block) => block.type === 'tool_result')
  if (results.length === 0) return state

  const structured = results.length === 1 ? (event.tool_use_result ?? event.toolUseResult) : undefined
  const workingDirectory = state.project?.workingDirectory ?? ''
  let list = state.turnChanges
  // The figures on an edit card's head, where the CLI's diff (or the one the IDE filled in) gives true ones.
  const heads = new Map<string, { added: number; removed: number }>()

  for (const result of results) {
    if (result.type !== 'tool_result') continue
    const call = event.parent_tool_use_id ? undefined : callOf(state, result.tool_use_id)
    // A result of this feed whose call is not in it is a page's seam (see historyPage) - and a subagent's
    // result is never one of this feed's calls.
    if (!event.parent_tool_use_id && !call) continue

    const change = changeOf(call ?? {}, structured, resultToText(result.content), result.is_error === true)
    if (change) {
      list = withChange(list, { ...change, shown: shortenPath(change.path, workingDirectory), firstCall: result.tool_use_id })
      // The card counts its head from the call, and a replace_all names one line while changing thousands: the
      // head said "+1 -1" right above the list's "+8000 -8000".
      if (call && (call.toolName === 'Edit' || call.toolName === 'MultiEdit') && structured && !change.uncounted) {
        heads.set(result.tool_use_id, { added: change.added, removed: change.removed })
      }
    }
  }

  const withHeads = heads.size === 0 ? state : { ...state, items: withHeadCounts(state.items, heads) }
  return list === state.turnChanges ? withHeads : { ...withHeads, turnChanges: list }
}

/** The edit cards named in [heads] with their head's figures set to the diff's. */
const withHeadCounts = (items: FeedItem[], heads: Map<string, { added: number; removed: number }>): FeedItem[] =>
  items.map((item) =>
    item.kind === 'toolGroup' && item.tools.some((tool) => heads.has(tool.id))
      ? {
          ...item,
          tools: item.tools.map((tool) => {
            const counts = heads.get(tool.id)
            return counts ? { ...tool, meta: { kind: 'diff' as const, ...counts } } : tool
          }),
        }
      : item,
  )

/**
 * The turn's list with each file the IDE settled taking its figures and edits from it (see FilesSettledEvent): the
 * file's whole change in the turn, measured on the disk, in place of the sum of its diffs - which did not take the
 * file to what stands there, so a revert by them could only refuse. A file the list does not hold stays out of it:
 * the IDE settles every file the turn's edits named, and the list leaves out the ones that changed nothing.
 */
export const settleChanges = (state: PanelState, files: FilesSettledEvent['files']): PanelState => {
  if (!state.turnChanges.some((file) => files.some((settled) => settled.path === file.path))) return state

  return {
    ...state,
    turnChanges: state.turnChanges.map((file) => {
      const settled = files.find((candidate) => candidate.path === file.path)
      if (!settled) return file
      const { uncounted: _uncounted, ...counted } = file
      return { ...counted, added: settled.added, removed: settled.removed, edits: settled.edits }
    }),
  }
}

/**
 * [path] from the project's root, when the file lies inside the project - and nothing when it does not, so the
 * caller says it in full.
 *
 * Separators are compared as one, and a drive letter regardless of case: the IDE names the project with
 * forward slashes, while the CLI on Windows names files with backslashes and the drive as it was typed.
 */
export const projectRelative = (path: string, root: string | undefined): string | null => {
  const tidy = (value: string) => value.replace(/\\/g, '/').replace(/\/+$/, '')
  const full = tidy(path)
  const base = root ? tidy(root) : ''
  if (!base || full.length <= base.length + 1) return null

  const head = full.slice(0, base.length)
  const same = head === base || (/^[A-Za-z]:/.test(base) && head.toLowerCase() === base.toLowerCase())
  return same && full.charAt(base.length) === '/' ? full.slice(base.length + 1) : null
}

/** The call this result answers, among the feed's own cards. */
const callOf = (state: PanelState, id: string): ToolItem | undefined => {
  for (const item of state.items) {
    if (item.kind !== 'toolGroup') continue
    const tool = item.tools.find((candidate) => candidate.id === id)
    if (tool) return tool
  }
  return undefined
}

/**
 * The turn is over: what it changed goes into the feed as one row, and the next turn starts its list
 * afresh. Nothing at all when it changed nothing - most turns are talk.
 */
export const closeChanges = (state: PanelState): PanelState => {
  if (state.turnChanges.length === 0) return state

  // Named by the turn's first change rather than by a counter: the IDE's answer to a revert finds the list by
  // this name, and a feed rebuilt from a journal whose head has gone would count differently.
  const id = `changes-${state.turnChanges[0]?.firstCall ?? state.seq}`

  return {
    ...state,
    seq: state.seq + 1,
    turnChanges: [],
    items: [...state.items, { id, kind: 'changes', files: state.turnChanges }],
  }
}

/**
 * The IDE's answer to a revert: each file it names marked with what came of it.
 *
 * An answer to bringing the changes back ([restore]) clears the mark when it worked, and otherwise leaves the
 * file reverted - which is what it still is - with the reason beside it.
 */
export const applyChangesReverted = (
  state: PanelState,
  id: string,
  outcomes: { path: string; outcome: RevertOutcome }[],
  restore = false,
): PanelState => ({
  ...state,
  items: state.items.map((item) =>
    item.kind === 'changes' && item.id === id
      ? {
          ...item,
          files: item.files.map((file) => {
            const found = outcomes.find((outcome) => outcome.path === file.path)
            if (!found) return file
            const answered = { ...file, answers: (file.answers ?? 0) + 1 }
            if (!restore) return { ...answered, revert: found.outcome, restoreRefused: undefined }
            return found.outcome === 'restored'
              ? { ...answered, revert: undefined, restoreRefused: undefined }
              : { ...answered, restoreRefused: found.outcome }
          }),
        }
      : item,
  ),
})

const asObject = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {}

const str = (value: unknown): string => (typeof value === 'string' ? value : '')

const countLines = (value: string): number => {
  const trimmed = value.replace(/\n+$/, '')
  return trimmed.length === 0 ? 0 : trimmed.split('\n').length
}
