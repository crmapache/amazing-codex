import type { AccountInfo } from '../protocol'

/**
 * A row whose drawer holds no credential, and that is no sign-in in flight. Its one useful act is to be
 * signed in again: Select would only move the work onto an account that cannot run a turn.
 *
 * The CLI's ordinary sign-in is one of these when the CLI wiped it by itself - a refresh the server
 * turned down - and then it keeps its row instead of vanishing from the screen (see
 * CodexAccounts.keepsSignedOutDefault on the plugin's side). One rule for the panel and the phone, so
 * the two screens cannot disagree about which rows offer Select.
 */
export const lostSignIn = (account: AccountInfo): boolean => account.health === 'absent' && account.pending !== true
