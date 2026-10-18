import { describe, expect, it } from 'vitest'
import type { AgentEvent, ToolUseResult } from '../protocol'
import { initialPanelState, reducePanel, type PanelState } from './build'
import { changeOf, projectRelative, withChange } from './changes'
import type { PanelAction } from './panelState'
import type { ChangesItem, FeedItem } from './types'

const AT = 1_700_000_000_000
const ROOT = '/Users/you/app'

const reduce = (actions: PanelAction[], state: PanelState = initialPanelState): PanelState =>
  actions.reduce((acc, action) => reducePanel(acc, action, AT), state)

const live = (event: AgentEvent): PanelAction => ({ kind: 'agent', event })
const replayed = (event: AgentEvent): PanelAction => ({ kind: 'agent', event, replay: true })

const init: AgentEvent = { type: 'system', subtype: 'init', cwd: ROOT, session_id: 's1' }

const call = (id: string, name: string, input: unknown, parent?: string): AgentEvent => ({
  type: 'assistant',
  message: { content: [{ type: 'tool_use', id, name, input }] },
  ...(parent ? { parent_tool_use_id: parent } : {}),
})

const answer = (
  id: string,
  text: string,
  structured?: ToolUseResult,
  options: { saved?: boolean; parent?: string; isError?: boolean } = {},
): AgentEvent => ({
  type: 'user',
  message: { content: [{ type: 'tool_result', tool_use_id: id, content: text, is_error: options.isError === true }] },
  ...(structured ? (options.saved ? { toolUseResult: structured } : { tool_use_result: structured }) : {}),
  ...(options.parent ? { parent_tool_use_id: options.parent } : {}),
})

const said = (text: string): AgentEvent => ({ type: 'user', message: { content: text }, timestamp: '2026-10-12T10:00:00Z' })

const result: AgentEvent = { type: 'result', subtype: 'success', duration_ms: 4000 }

const patch = (...lines: string[]) => [{ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines }]

const lists = (items: FeedItem[]): ChangesItem['files'][] =>
  items.filter((item): item is ChangesItem => item.kind === 'changes').map((item) => item.files)

describe('what one result changed', () => {
  it('counts the lines the CLI applied, not the ones the call named', () => {
    // A replace_all over three occurrences: the call names one line, the patch holds all three.
    expect(
      changeOf(
        { toolName: 'Edit', input: { file_path: `${ROOT}/a.ts`, old_string: 'x', new_string: 'y', replace_all: true } },
        { filePath: `${ROOT}/a.ts`, structuredPatch: patch('-x', '+y', ' z', '-x', '+y', '-x', '+y') },
        'The file has been updated successfully.',
        false,
      ),
    ).toMatchObject({ path: `${ROOT}/a.ts`, added: 3, removed: 3, created: false })
  })

  it('says a Write created its file, and counts the file from the call', () => {
    expect(
      changeOf(
        { toolName: 'Write', input: { file_path: `${ROOT}/Dockerfile`, content: 'FROM node:22\nCOPY . .\n' } },
        { type: 'create', filePath: `${ROOT}/Dockerfile`, content: 'FROM node:22\n… 9 more characters', structuredPatch: [] },
        'File created successfully at: /Users/you/app/Dockerfile',
        false,
      ),
    ).toEqual({
      path: `${ROOT}/Dockerfile`,
      added: 2,
      removed: 0,
      created: true,
      // The content the IDE compares before deleting is the call's, which no trimming touches.
      edits: [{ kind: 'create', content: 'FROM node:22\nCOPY . .\n' }],
    })
  })

  it('counts a Write over an existing file by what it replaced', () => {
    expect(
      changeOf(
        { toolName: 'Write', input: { file_path: `${ROOT}/a.txt`, content: 'z' } },
        { type: 'update', filePath: `${ROOT}/a.txt`, structuredPatch: patch('-one', '-two', '+z', '\\ No newline at end of file') },
        'The file has been updated successfully.',
        false,
      ),
    ).toMatchObject({ path: `${ROOT}/a.txt`, added: 1, removed: 2, created: false })
  })

  it('falls back on the call when the result came without its diff', () => {
    expect(
      changeOf(
        { toolName: 'Edit', input: { file_path: `${ROOT}/a.ts`, old_string: 'a\nb\nc', new_string: 'a\nB\nB2\nc' } },
        undefined,
        'The file has been updated successfully.',
        false,
      ),
    ).toEqual({ path: `${ROOT}/a.ts`, added: 2, removed: 1, created: false })
    expect(
      changeOf({ toolName: 'Write', input: { file_path: `${ROOT}/new.md`, content: '# Hi\n' } }, undefined, 'File created successfully at: x', false),
    ).toEqual({ path: `${ROOT}/new.md`, added: 1, removed: 0, created: true })
  })

  // Without the diff there is nothing exact to undo, so the row gets no revert button (see FileChange.edits).
  it('keeps the hunks the CLI applied, and none for a change counted from the call', () => {
    const hunks = [{ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['-x', '+y'] }]
    expect(changeOf({ toolName: 'Edit', input: {} }, { filePath: `${ROOT}/a.ts`, structuredPatch: hunks }, '', false)?.edits).toEqual([
      { kind: 'patch', hunks: [{ oldStart: 1, newStart: 1, lines: ['-x', '+y'] }] },
    ])
    expect(
      changeOf({ toolName: 'Edit', input: { file_path: `${ROOT}/a.ts`, old_string: 'x', new_string: 'y' } }, undefined, 'ok', false)?.edits,
    ).toBeUndefined()
  })

  it('is nothing for a failed call, a reading call and a change that changed nothing', () => {
    const edit = { toolName: 'Edit', input: { file_path: `${ROOT}/a.ts`, old_string: 'x', new_string: 'y' } }
    expect(changeOf(edit, undefined, 'String to replace not found', true)).toBeNull()
    expect(changeOf({ toolName: 'Read', input: { file_path: `${ROOT}/a.ts` } }, undefined, '1\tx', false)).toBeNull()
    expect(
      changeOf(
        { toolName: 'Write', input: { file_path: `${ROOT}/a.ts`, content: 'x' } },
        { type: 'update', structuredPatch: [], originalFile: 'x' },
        '',
        false,
      ),
    ).toBeNull()
  })

  // The CLI reports a big edit with an empty diff; the IDE fills one in, and a phone gets only its counts.
  it('lists a big edit the CLI reported without a diff - with the counts the IDE worked out, or none', () => {
    const replaceAll = { toolName: 'Edit', input: { file_path: `${ROOT}/big.js`, old_string: 'foo(', new_string: 'bar(', replace_all: true } }
    expect(changeOf(replaceAll, { filePath: `${ROOT}/big.js`, structuredPatch: [], accCounts: { added: 8000, removed: 8000 } }, 'ok', false)).toEqual({
      path: `${ROOT}/big.js`,
      added: 8000,
      removed: 8000,
      created: false,
    })
    // The live stream sends part of the original beside it - that is not "the same text written".
    expect(changeOf(replaceAll, { filePath: `${ROOT}/big.js`, structuredPatch: [], originalFile: 'x = foo(1)\nx = fo' }, 'ok', false)).toMatchObject({
      uncounted: true,
    })
    expect(changeOf(replaceAll, { filePath: `${ROOT}/big.js`, structuredPatch: [] }, 'ok', false)).toEqual({
      path: `${ROOT}/big.js`,
      added: 0,
      removed: 0,
      created: false,
      uncounted: true,
    })
    // A single replacement is still counted from the call, as its card counts it.
    const single = { toolName: 'Edit', input: { file_path: `${ROOT}/big.js`, old_string: 'a\nb', new_string: 'a\nB' } }
    expect(changeOf(single, { filePath: `${ROOT}/big.js`, structuredPatch: [] }, 'ok', false)).toEqual({
      path: `${ROOT}/big.js`,
      added: 1,
      removed: 1,
      created: false,
    })
  })

  // A subagent's call is not one of this feed's cards, so its result has to say everything by itself.
  it('takes a result without its call only when the result names the file and the diff', () => {
    expect(changeOf({}, { filePath: `${ROOT}/a.ts`, structuredPatch: patch('+x') }, '', false)).toMatchObject({
      path: `${ROOT}/a.ts`,
      added: 1,
      removed: 0,
      created: false,
    })
    expect(changeOf({}, { type: 'text' }, '1\tx', false)).toBeNull()
    expect(changeOf({}, undefined, 'The file has been updated successfully.', false)).toBeNull()
  })
})

describe('the path a row shows', () => {
  it('is from the project root for a file inside the project, and nothing for any other', () => {
    expect(projectRelative('/Users/you/app/src/a.ts', '/Users/you/app')).toBe('src/a.ts')
    expect(projectRelative('/Users/you/app/src/a.ts', '/Users/you/app/')).toBe('src/a.ts')
    expect(projectRelative('/Users/you/.claude/settings.json', '/Users/you/app')).toBeNull()
    // A sibling whose name starts the same is not inside.
    expect(projectRelative('/Users/you/app-two/a.ts', '/Users/you/app')).toBeNull()
    expect(projectRelative('/Users/you/app/a.ts', undefined)).toBeNull()
  })

  // The IDE names the project with forward slashes; the CLI on Windows names files with backslashes.
  it('reads a Windows path against the IDE\'s spelling of the project', () => {
    expect(projectRelative('C:\\work\\api\\src\\Program.cs', 'c:/work/api')).toBe('src/Program.cs')
  })
})

describe('the turn list', () => {
  const create = { kind: 'create' as const, content: 'a' }
  const edit = { kind: 'patch' as const, hunks: [{ oldStart: 1, newStart: 1, lines: ['+b'] }] }

  it('adds a second change of a file to its row, and keeps a created file created', () => {
    const one = withChange([], { path: 'a', shown: 'a', added: 5, removed: 0, created: true, firstCall: 'c1', edits: [create] })
    const two = withChange(one, { path: 'b', shown: 'b', added: 1, removed: 1, created: false, firstCall: 'c2', edits: [edit] })
    expect(withChange(two, { path: 'a', shown: 'a', added: 2, removed: 1, created: false, firstCall: 'c3', edits: [edit] })).toEqual([
      { path: 'a', shown: 'a', added: 7, removed: 1, created: true, firstCall: 'c1', edits: [create, edit] },
      { path: 'b', shown: 'b', added: 1, removed: 1, created: false, firstCall: 'c2', edits: [edit] },
    ])
  })

  it('loses its undo when one change of the file came without its diff', () => {
    const one = withChange([], { path: 'a', shown: 'a', added: 1, removed: 0, created: false, firstCall: 'c1', edits: [edit] })
    expect(withChange(one, { path: 'a', shown: 'a', added: 1, removed: 0, created: false, firstCall: 'c2' })[0]?.edits).toBeUndefined()
  })
})

describe('the files a turn changed, in the feed', () => {
  it('stands at the end of a live turn, just above "Worked", with the paths from the project root', () => {
    const state = reduce([
      live(init),
      live(call('e1', 'Edit', { file_path: `${ROOT}/src/a.ts`, old_string: 'x', new_string: 'y' })),
      live(answer('e1', 'The file has been updated successfully.', { filePath: `${ROOT}/src/a.ts`, structuredPatch: patch('-x', '+y', '+z') })),
      live(call('w1', 'Write', { file_path: `${ROOT}/Dockerfile`, content: 'FROM node:22' })),
      live(answer('w1', 'File created successfully at: /Users/you/app/Dockerfile', { type: 'create', filePath: `${ROOT}/Dockerfile`, structuredPatch: [] })),
      live({ type: 'assistant', message: { content: [{ type: 'text', text: 'Done.' }] } }),
      live(result),
    ])

    const kinds = state.items.map((item) => item.kind)
    expect(kinds.slice(-3)).toEqual(['text', 'changes', 'meta'])
    expect(lists(state.items)).toMatchObject([
      [
        { path: `${ROOT}/src/a.ts`, shown: 'src/a.ts', added: 2, removed: 1, created: false, firstCall: 'e1' },
        { path: `${ROOT}/Dockerfile`, shown: 'Dockerfile', added: 1, removed: 0, created: true, firstCall: 'w1' },
      ],
    ])
    // Named by the turn's first change: the IDE's answer to a revert finds the list by it.
    expect(state.items.find((item) => item.kind === 'changes')?.id).toBe('changes-e1')
    expect(state.turnChanges).toEqual([])
  })

  // A replace_all names one line in its call and changes thousands: the card's head takes the diff's figures.
  it('gives an edit card the figures of its diff', () => {
    const state = reduce([
      live(init),
      live(call('e1', 'Edit', { file_path: `${ROOT}/big.js`, old_string: 'foo(', new_string: 'bar(', replace_all: true })),
      live(answer('e1', 'ok', { filePath: `${ROOT}/big.js`, structuredPatch: patch('-foo(1)', '+bar(1)', '-foo(2)', '+bar(2)') })),
    ])
    const card = state.items.flatMap((item) => (item.kind === 'toolGroup' ? item.tools : [])).find((tool) => tool.id === 'e1')
    expect(card?.meta).toEqual({ kind: 'diff', added: 2, removed: 2 })
  })

  it('draws nothing for a turn that changed nothing', () => {
    const state = reduce([
      live(init),
      live(call('r1', 'Read', { file_path: `${ROOT}/src/a.ts` })),
      live(answer('r1', '1\tx', { type: 'text' })),
      live(result),
    ])
    expect(lists(state.items)).toEqual([])
  })

  it('lists what a subagent changed with the rest of the turn', () => {
    const state = reduce([
      live(init),
      live(call('t1', 'Task', { description: 'fix it', prompt: 'fix it' })),
      live(call('s1', 'Edit', { file_path: `${ROOT}/b.ts`, old_string: 'a', new_string: 'b' }, 't1')),
      live(answer('s1', 'The file has been updated successfully.', { filePath: `${ROOT}/b.ts`, structuredPatch: patch('-a', '+b') }, { parent: 't1' })),
      live(answer('t1', 'Fixed.')),
      live(result),
    ])
    expect(lists(state.items)).toMatchObject([[{ path: `${ROOT}/b.ts`, shown: 'b.ts', added: 1, removed: 1, created: false }]])
  })

  it('keeps each turn to its own list', () => {
    const turn = (id: string, file: string): PanelAction[] => [
      live(call(id, 'Edit', { file_path: `${ROOT}/${file}`, old_string: 'a', new_string: 'b' })),
      live(answer(id, 'ok', { filePath: `${ROOT}/${file}`, structuredPatch: patch('-a', '+b') })),
      live(result),
    ]
    const state = reduce([live(init), ...turn('e1', 'a.ts'), { kind: 'prompt', tokens: [], quotes: [] }, ...turn('e2', 'b.ts')])
    expect(lists(state.items).map((files) => files.map((file) => file.shown))).toEqual([['a.ts'], ['b.ts']])
  })

  /** A transcript keeps no result of a turn: the next message is where the turn before it ended. */
  it('closes a replayed turn at the next message and at the end of the replay', () => {
    const state = reduce([
      replayed(init),
      replayed(said('Fix a')),
      replayed(call('e1', 'Edit', { file_path: `${ROOT}/a.ts`, old_string: 'a', new_string: 'b' })),
      replayed(answer('e1', 'ok', { filePath: `${ROOT}/a.ts`, structuredPatch: patch('-a', '+b') }, { saved: true })),
      replayed(said('Now b')),
      replayed(call('e2', 'Edit', { file_path: `${ROOT}/b.ts`, old_string: 'a', new_string: 'b' })),
      replayed(answer('e2', 'ok', { filePath: `${ROOT}/b.ts`, structuredPatch: patch('+c') }, { saved: true })),
      { kind: 'replayFinished', cursor: null },
    ])

    const kinds = state.items.map((item) => item.kind)
    expect(kinds.indexOf('changes')).toBeLessThan(kinds.lastIndexOf('user'))
    expect(kinds.at(-1)).toBe('changes')
    expect(lists(state.items).map((files) => files.map((file) => `${file.shown} +${file.added} -${file.removed}`))).toEqual([
      ['a.ts +1 -1'],
      ['b.ts +1 -0'],
    ])
  })

  /**
   * A formatter after the Write, the agent's own script, the CLI adding to a memory file: the file is not what its
   * diffs say, and the IDE says what it is before the result closes the list (see UndiffedEdits.settle).
   */
  it('takes the figures and edits of a file the IDE settled at the end of the turn', () => {
    const settled = { kind: 'patch' as const, hunks: [{ oldStart: 0, newStart: 1, lines: ['+a', '+b', '+c'] }] }
    const state = reduce([
      live(init),
      live(call('w1', 'Write', { file_path: `${ROOT}/a.css`, content: 'a {}' })),
      live(answer('w1', 'File created successfully', { type: 'create', filePath: `${ROOT}/a.css`, structuredPatch: [] })),
      live(call('e1', 'Edit', { file_path: `${ROOT}/b.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e1', 'ok', { filePath: `${ROOT}/b.ts`, structuredPatch: patch('-a', '+b') })),
      live({
        type: 'files_settled',
        files: [
          { path: `${ROOT}/a.css`, added: 3, removed: 0, edits: [{ kind: 'create', content: '' }, settled] },
          // A file the list does not hold stays out of it.
          { path: `${ROOT}/elsewhere.ts`, added: 1, removed: 0, edits: [settled] },
        ],
      }),
      live(result),
    ])

    expect(lists(state.items)).toMatchObject([
      [
        { path: `${ROOT}/a.css`, added: 3, removed: 0, created: true, edits: [{ kind: 'create', content: '' }, settled] },
        { path: `${ROOT}/b.ts`, added: 1, removed: 1, edits: [{ kind: 'patch' }] },
      ],
    ])
  })

  /** The phone is sent the figures without the edits when they would not fit its frame. */
  it('takes the figures alone when the edits did not come, and offers no revert by the old ones', () => {
    const state = reduce([
      live(init),
      live(call('e1', 'Edit', { file_path: `${ROOT}/b.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e1', 'ok', { filePath: `${ROOT}/b.ts`, structuredPatch: patch('-a', '+b') })),
      live({ type: 'files_settled', files: [{ path: `${ROOT}/b.ts`, added: 40, removed: 12 }] }),
      live(result),
    ])
    const [file] = lists(state.items)[0] ?? []
    expect(file).toMatchObject({ added: 40, removed: 12 })
    expect(file?.edits).toBeUndefined()
  })

  it('marks the rows the IDE says it put back, and leaves the rest', () => {
    const changed = reduce([
      live(init),
      live(call('e1', 'Edit', { file_path: `${ROOT}/a.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e1', 'ok', { filePath: `${ROOT}/a.ts`, structuredPatch: patch('-a', '+b') })),
      live(call('e2', 'Edit', { file_path: `${ROOT}/b.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e2', 'ok', { filePath: `${ROOT}/b.ts`, structuredPatch: patch('-a', '+b') })),
      live(result),
    ])
    const state = reducePanel(
      changed,
      { kind: 'changesReverted', id: 'changes-e1', files: [{ path: `${ROOT}/b.ts`, outcome: 'changed' }] },
      AT,
    )
    expect(lists(state.items)[0]?.map((file) => file.revert)).toEqual([undefined, 'changed'])
  })

  /** Bringing back clears the mark when it worked; a refusal leaves the file reverted, with the reason beside it. */
  it('takes the mark off a row brought back, and keeps it on one that could not be', () => {
    const changed = reduce([
      live(init),
      live(call('e1', 'Edit', { file_path: `${ROOT}/a.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e1', 'ok', { filePath: `${ROOT}/a.ts`, structuredPatch: patch('-a', '+b') })),
      live(call('e2', 'Edit', { file_path: `${ROOT}/b.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e2', 'ok', { filePath: `${ROOT}/b.ts`, structuredPatch: patch('-a', '+b') })),
      live(result),
      {
        kind: 'changesReverted',
        id: 'changes-e1',
        files: [
          { path: `${ROOT}/a.ts`, outcome: 'reverted' },
          { path: `${ROOT}/b.ts`, outcome: 'reverted' },
        ],
      },
    ])
    const state = reducePanel(
      changed,
      {
        kind: 'changesReverted',
        id: 'changes-e1',
        restore: true,
        files: [
          { path: `${ROOT}/a.ts`, outcome: 'restored' },
          { path: `${ROOT}/b.ts`, outcome: 'changed' },
        ],
      },
      AT,
    )
    expect(lists(state.items)[0]?.map((file) => [file.revert, file.restoreRefused])).toEqual([
      [undefined, undefined],
      ['reverted', 'changed'],
    ])
  })

  // A second press after a refusal is answered with the same refusal; the row must still see it as an answer.
  it('counts every answer about a file, the same refusal twice included', () => {
    const changed = reduce([
      live(init),
      live(call('e1', 'Edit', { file_path: `${ROOT}/a.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e1', 'ok', { filePath: `${ROOT}/a.ts`, structuredPatch: patch('-a', '+b') })),
      live(result),
    ])
    const refused: PanelAction = { kind: 'changesReverted', id: 'changes-e1', files: [{ path: `${ROOT}/a.ts`, outcome: 'changed' }] }
    const twice = reduce([refused, refused], changed)
    expect(lists(twice.items)[0]?.map((file) => [file.revert, file.answers])).toEqual([['changed', 2]])
  })

  it('lists what a turn changed before the line saying its process died', () => {
    const state = reduce([
      live(init),
      live(call('e1', 'Edit', { file_path: `${ROOT}/a.ts`, old_string: 'a', new_string: 'b' })),
      live(answer('e1', 'ok', { filePath: `${ROOT}/a.ts`, structuredPatch: patch('-a', '+b') })),
      { kind: 'processExited', exitCode: 1 },
    ])
    expect(state.items.map((item) => item.kind).slice(-2)).toEqual(['changes', 'crash'])
  })
})

/**
 * Codex's patches, as the plugin's dialect says them (see CodexDialect.fileCalls and fileHalf): one card per file
 * carrying the diff Codex applied, and one result per file with a small structured half - the file, what became of
 * it, where a moved one came from. The hunks are read off the card.
 */
describe('a Codex patch', () => {
  const half = (path: string, type: 'create' | 'update' | 'delete', extra: Record<string, unknown> = {}): ToolUseResult => ({
    filePath: path,
    type,
    ...extra,
  })

  it('puts a file back by the hunks of the card, the note on its last line kept', () => {
    const diff = '@@ -1,2 +1,2 @@\n alpha\n-beta\n\\ No newline at end of file\n+gamma\n'
    expect(
      changeOf(
        { toolName: 'Edit', input: { file_path: `${ROOT}/a.txt`, unified_diff: diff } },
        half(`${ROOT}/a.txt`, 'update'),
        '',
        false,
      ),
    ).toEqual({
      path: `${ROOT}/a.txt`,
      added: 1,
      removed: 1,
      created: false,
      edits: [{ kind: 'patch', hunks: [{ oldStart: 1, newStart: 1, lines: [' alpha', '-beta', '\\ No newline at end of file', '+gamma'] }] }],
    })
  })

  it('reads the start of an empty side the way the CLI says it', () => {
    expect(
      changeOf(
        { toolName: 'Edit', input: { file_path: `${ROOT}/a.txt`, unified_diff: '@@ -0,0 +1,2 @@\n+one\n+two\n' } },
        half(`${ROOT}/a.txt`, 'update'),
        '',
        false,
      )?.edits,
    ).toEqual([{ kind: 'patch', hunks: [{ oldStart: 1, newStart: 1, lines: ['+one', '+two'] }] }])
  })

  it('has no undo for a diff cut short on the way, and counts it as far as it goes', () => {
    const cut = '@@ -1,3 +1,3 @@\n a\n-b\n+c\n… 4000 more characters are not kept'
    const change = changeOf(
      { toolName: 'Edit', input: { file_path: `${ROOT}/a.txt`, unified_diff: cut } },
      half(`${ROOT}/a.txt`, 'update'),
      '',
      false,
    )
    expect(change).toMatchObject({ added: 1, removed: 1 })
    expect(change?.edits).toBeUndefined()
  })

  it('creates a file by the text of its card, and deletes one by the text it held', () => {
    expect(
      changeOf({ toolName: 'Write', input: { file_path: `${ROOT}/new.ts`, content: 'one\ntwo\n' } }, half(`${ROOT}/new.ts`, 'create'), '', false),
    ).toEqual({ path: `${ROOT}/new.ts`, added: 2, removed: 0, created: true, edits: [{ kind: 'create', content: 'one\ntwo\n' }] })
    expect(
      changeOf(
        { toolName: 'Edit', input: { file_path: `${ROOT}/old.ts`, old_string: 'gone\nfor good\n', new_string: '', deleted: true } },
        half(`${ROOT}/old.ts`, 'delete'),
        '',
        false,
      ),
    ).toEqual({
      path: `${ROOT}/old.ts`,
      added: 0,
      removed: 2,
      created: false,
      deleted: true,
      edits: [{ kind: 'delete', content: 'gone\nfor good\n' }],
    })
  })

  it('moves a file before its hunks, and lists a move that changed no line', () => {
    expect(
      changeOf(
        { toolName: 'Edit', input: { file_path: `${ROOT}/b.ts`, moved_from: `${ROOT}/a.ts`, unified_diff: '@@ -1 +1 @@\n-x\n+y\nMoved to: b.ts' } },
        half(`${ROOT}/b.ts`, 'update', { movedFrom: `${ROOT}/a.ts` }),
        '',
        false,
      ),
    ).toEqual({
      path: `${ROOT}/b.ts`,
      added: 1,
      removed: 1,
      created: false,
      movedFrom: `${ROOT}/a.ts`,
      edits: [
        { kind: 'move', from: `${ROOT}/a.ts` },
        { kind: 'patch', hunks: [{ oldStart: 1, newStart: 1, lines: ['-x', '+y'] }] },
      ],
    })
    expect(
      changeOf(
        { toolName: 'Edit', input: { file_path: `${ROOT}/b.ts`, moved_from: `${ROOT}/a.ts`, unified_diff: '' } },
        half(`${ROOT}/b.ts`, 'update', { movedFrom: `${ROOT}/a.ts` }),
        '',
        false,
      ),
    ).toMatchObject({ added: 0, removed: 0, movedFrom: `${ROOT}/a.ts`, edits: [{ kind: 'move', from: `${ROOT}/a.ts` }] })
  })

  it('lists every file of a patch of three, each with its undo', () => {
    const state = reduce([
      live(init),
      live({
        type: 'assistant',
        message: {
          content: [
            { type: 'tool_use', id: 'p:0', name: 'Write', input: { file_path: `${ROOT}/new.ts`, content: 'one\n' } },
            { type: 'tool_use', id: 'p:1', name: 'Edit', input: { file_path: `${ROOT}/a.ts`, unified_diff: '@@ -1 +1 @@\n-a\n+b\n' } },
            { type: 'tool_use', id: 'p:2', name: 'Edit', input: { file_path: `${ROOT}/old.ts`, old_string: 'x\n', new_string: '', deleted: true } },
          ],
        },
      }),
      live(answer('p:0', '', half(`${ROOT}/new.ts`, 'create'), { saved: true })),
      live(answer('p:1', '', half(`${ROOT}/a.ts`, 'update'), { saved: true })),
      live(answer('p:2', '', half(`${ROOT}/old.ts`, 'delete'), { saved: true })),
      live(result),
    ])
    const files = lists(state.items)[0]
    expect(files?.map((file) => [file.shown, file.added, file.removed, file.created, file.deleted === true, file.edits?.[0]?.kind])).toEqual([
      ['new.ts', 1, 0, true, false, 'create'],
      ['a.ts', 1, 1, false, false, 'patch'],
      ['old.ts', 0, 1, false, true, 'delete'],
    ])
  })

  // A conversation opened from the history, or brought back after the IDE restarted, is the same replay: Codex keeps
  // every patch whole in the thread, and each replayed turn ends with its result.
  it('comes back from the history with its figures and its undo', () => {
    const state = reduce([
      replayed(init),
      replayed(said('Fix a')),
      replayed(call('f1', 'Edit', { file_path: `${ROOT}/a.ts`, unified_diff: '@@ -1,2 +1,3 @@\n a\n+b\n c\n' })),
      replayed(answer('f1', '', half(`${ROOT}/a.ts`, 'update'), { saved: true })),
      replayed(call('f2:0', 'Write', { file_path: `${ROOT}/n.ts`, content: 'n\n' })),
      replayed(answer('f2:0', '', half(`${ROOT}/n.ts`, 'create'), { saved: true })),
      replayed(result),
      { kind: 'replayFinished', cursor: null },
    ])
    const files = lists(state.items)[0]
    expect(files?.map((file) => `${file.shown} +${file.added} -${file.removed} ${file.edits?.map((edit) => edit.kind).join(',')}`)).toEqual([
      'a.ts +1 -0 patch',
      'n.ts +1 -0 create',
    ])
  })

  it("lists a subagent's patch by its result alone, which carries the diff", () => {
    const state = reduce([
      live(init),
      live(call('agent-1', 'Task', { description: 'Start an agent', prompt: 'fix it' })),
      live(answer('sub-f1', '', half(`${ROOT}/a.ts`, 'update', { unified_diff: '@@ -1 +1 @@\n-a\n+b\n' }), { saved: true, parent: 'agent-1' })),
      live(answer('sub-f2', '', half(`${ROOT}/n.ts`, 'create', { content: 'x\ny\n' }), { saved: true, parent: 'agent-1' })),
      live(result),
    ])
    expect(lists(state.items)[0]?.map((file) => [file.shown, file.added, file.removed, file.edits?.[0]?.kind])).toEqual([
      ['a.ts', 1, 1, 'patch'],
      ['n.ts', 2, 0, 'create'],
    ])
  })
})

describe('a file a turn moved or deleted', () => {
  const change = (path: string, extra: Partial<Parameters<typeof withChange>[1]> = {}) => ({
    path,
    shown: path,
    added: 1,
    removed: 1,
    created: false,
    firstCall: `c-${path}`,
    edits: [{ kind: 'patch' as const, hunks: [{ oldStart: 1, newStart: 1, lines: ['-a', '+b'] }] }],
    ...extra,
  })

  it('goes on as the row of where it came from, its edits in the order they were made', () => {
    const before = withChange([], change('/p/a.ts'))
    const moved = withChange(
      before,
      change('/p/b.ts', { movedFrom: '/p/a.ts', edits: [{ kind: 'move', from: '/p/a.ts' }, { kind: 'patch', hunks: [] }] }),
    )
    expect(moved).toHaveLength(1)
    expect(moved[0]).toMatchObject({ path: '/p/b.ts', shown: '/p/b.ts', added: 2, removed: 2, movedFrom: '/p/a.ts', firstCall: 'c-/p/a.ts' })
    expect(moved[0]?.edits?.map((edit) => edit.kind)).toEqual(['patch', 'move', 'patch'])
  })

  it('is no row when the turn created it and then deleted it, and no longer deleted once written again', () => {
    const created = withChange([], change('/p/n.ts', { created: true, removed: 0, edits: [{ kind: 'create', content: 'x\n' }] }))
    expect(withChange(created, change('/p/n.ts', { deleted: true, added: 0, edits: [{ kind: 'delete', content: 'x\n' }] }))).toEqual([])

    const deleted = withChange([], change('/p/o.ts', { deleted: true, added: 0, edits: [{ kind: 'delete', content: 'x\n' }] }))
    const again = withChange(deleted, change('/p/o.ts', { created: true, removed: 0, edits: [{ kind: 'create', content: 'y\n' }] }))
    expect(again[0]).toMatchObject({ created: false, added: 1, removed: 1 })
    expect(again[0]?.deleted).toBeUndefined()
  })
})
