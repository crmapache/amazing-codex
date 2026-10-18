import type { Dict } from '../i18n/en'
import type { ShellMessage } from '../protocol'
import type { PanelAction } from './panelState'
import type { SessionInfoItem } from './types'

/**
 * `/status` under Codex (see SessionInfoRow): what the row says about continuing the conversation in a terminal.
 *
 * Out of the row's markup on purpose. The row is the original's, and the original writes Claude Code's
 * `claude --resume <id>` there; kept here, the fork's difference is a call, and the next merge of the row is a merge
 * of the row.
 */

/**
 * `/status` alone, as a phone sends it - a message the IDE answers with the row rather than pass to the model (see
 * SessionCommands.statusAsked, the same test on that side). It names nothing, so it does not name a new tab either.
 */
export const isStatusCommand = (text: string): boolean => text.trim() === '/status'

/** The answer to `/status` as the feed takes it - the same on the desk and on a phone (see mobile/feed.ts). */
export const sessionInfoAction = (message: Extract<ShellMessage, { type: 'sessionInfo' }>): PanelAction => ({
  kind: 'sessionInfo',
  conversationId: message.conversationId,
  ...(message.resume ? { resume: message.resume } : {}),
  ...(message.account ? { account: message.account } : {}),
  ...(message.onDefault ? { onDefault: true } : {}),
})

/**
 * The line that continues the conversation in a terminal: the IDE's own when it named one - a tab on an added
 * account has the account's drawer in front of the command (see ResumeLine.kt) - and `codex resume <id>` otherwise.
 */
export const resumeCommand = (item: SessionInfoItem): string => item.resume ?? `codex resume ${item.conversationId ?? ''}`

/**
 * What the caption under the line says: where to run it, and on which account it runs. A tab on an added account is
 * either carried on as that account by the line (macOS and Linux, at the desk), or - on Windows, and on a phone, which
 * is not handed the machine's paths - by the default sign-in, and the caption says so rather than let the turns be
 * billed to the other subscription unannounced.
 */
export const resumeNote = (item: SessionInfoItem, words: Dict['feed']['session']): string => {
  const account = item.account ?? words.unnamedAccount
  if (item.onDefault) return words.terminalNoteDefault(account)
  return item.account ? words.terminalNoteOn(item.account) : words.terminalNote
}
