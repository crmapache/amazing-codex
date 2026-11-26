import { describe, expect, it } from 'vitest'
import type { AccountInfo } from '../protocol'
import { lostSignIn } from './accounts'

const row = (over: Partial<AccountInfo>): AccountInfo => ({ id: 'a1', alias: '', email: 'a@b.c', plan: 'pro', ...over })

describe('lostSignIn', () => {
  // The case it exists for: the CLI wiped its own sign-in, and the row stays on the screen with a way back
  // in rather than vanishing.
  it('is the ordinary sign-in the CLI lost by itself', () => {
    expect(lostSignIn(row({ id: '', isDefault: true, health: 'absent' }))).toBe(true)
  })

  it('is an added account whose drawer reads empty', () => {
    expect(lostSignIn(row({ health: 'absent' }))).toBe(true)
  })

  // A draft's drawer is empty because its sign-in is still under way - there is nothing to sign in again.
  it('is not a sign-in still in flight', () => {
    expect(lostSignIn(row({ health: 'absent', pending: true }))).toBe(false)
  })

  // Unknown is the first of the two broadcasts, before the round has asked: not a verdict.
  it('is not a row that is signed in, or not asked about yet', () => {
    expect(lostSignIn(row({ health: 'present' }))).toBe(false)
    expect(lostSignIn(row({ health: 'unknown' }))).toBe(false)
    expect(lostSignIn(row({}))).toBe(false)
  })

  // The wait for a sign-in begun on the screen does not change what the row is: it is still lost until
  // the credential lands, so the screen keeps the way to stop waiting.
  it('stays lost while it is being signed in again', () => {
    expect(lostSignIn(row({ health: 'absent', signingIn: true }))).toBe(true)
  })
})
