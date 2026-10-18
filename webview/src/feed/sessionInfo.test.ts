import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import { initialPanelState, reducePanel } from './build'
import { isStatusCommand, resumeCommand, resumeNote, sessionInfoAction } from './sessionInfo'
import type { SessionInfoItem } from './types'

/**
 * `/status` under Codex: the terminal line and what the caption says about it (see feed/sessionInfo.ts). The IDE
 * decides the line for a tab on an added account (see ResumeLine.kt); this side draws what it said, and says on which
 * account a terminal would carry the conversation on.
 */
describe('the terminal line of /status', () => {
  const item = (more: Partial<SessionInfoItem> = {}): SessionInfoItem => ({
    id: 'i-1',
    kind: 'session',
    conversationId: '019a2b3c',
    ...more,
  })

  it('is codex resume on the default sign-in', () => {
    expect(resumeCommand(item())).toBe('codex resume 019a2b3c')
    expect(resumeNote(item(), en.feed.session)).toBe(en.feed.session.terminalNote)
  })

  // The account's drawer in front of the command, as the IDE wrote it - and the caption names the account.
  it('is the IDE’s own line for a tab on an added account', () => {
    const line = 'CODEX_HOME=/u/.amazing-codex/accounts/s-1 CODEX_SQLITE_HOME=/u/.codex codex resume 019a2b3c'
    const shown = item({ resume: line, account: 'Work' })

    expect(resumeCommand(shown)).toBe(line)
    expect(resumeNote(shown, en.feed.session)).toBe(en.feed.session.terminalNoteOn('Work'))
  })

  // Windows, a phone, a drawer that would not resolve: the line is plain, and the caption says whose sign-in it runs on.
  it('says when a terminal would carry it on the default sign-in', () => {
    const shown = item({ account: 'Work', onDefault: true })

    expect(resumeCommand(shown)).toBe('codex resume 019a2b3c')
    expect(resumeNote(shown, en.feed.session)).toBe(en.feed.session.terminalNoteDefault('Work'))
    expect(resumeNote(item({ onDefault: true }), en.feed.session)).toBe(
      en.feed.session.terminalNoteDefault(en.feed.session.unnamedAccount),
    )
  })

  it('takes the IDE’s answer whole into the feed', () => {
    const state = reducePanel(
      initialPanelState,
      sessionInfoAction({ type: 'sessionInfo', sessionId: 'main', conversationId: '019a2b3c', account: 'Work', onDefault: true }),
    )

    expect(state.items.at(-1)).toEqual(
      expect.objectContaining({ kind: 'session', conversationId: '019a2b3c', account: 'Work', onDefault: true }),
    )
    expect(state.items.at(-1)).not.toHaveProperty('resume')
  })

  // A line is about the id it names: one the feed falls back to (see the reducer) has none of its own.
  it('keeps no line for an id the IDE did not name', () => {
    const known = { ...initialPanelState, sessionId: 'known-id' }
    const state = reducePanel(known, { kind: 'sessionInfo', resume: 'CODEX_HOME=/x codex resume other' })

    expect(state.items.at(-1)).toEqual(expect.objectContaining({ conversationId: 'known-id' }))
    expect(state.items.at(-1)).not.toHaveProperty('resume')
  })

  it('knows /status when it is all there is', () => {
    expect(isStatusCommand('/status')).toBe(true)
    expect(isStatusCommand('  /status\n')).toBe(true)
    expect(isStatusCommand('/status of the migration')).toBe(false)
    expect(isStatusCommand('/statusline')).toBe(false)
  })
})
