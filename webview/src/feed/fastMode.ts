import type { Dict } from '../i18n'
import type { ModelInfo } from '../protocol'
import { DEFAULT_MODEL, sameModel } from '../catalog'

/**
 * Fast mode: Codex's fast service tier (`priority`, "Fast" in its catalogue, `/fast` in its terminal) - quicker
 * answers that use the plan's limits faster.
 *
 * Two halves that come from two places, and the screen needs both. Whether the conversation ASKS for it is the
 * IDE's to say - nothing in the stream says it (see CodexSession.fastMode and the `fastMode` message), and a
 * conversation that asks nothing leaves it to Codex's own config. Whether it is ON Codex says, and the IDE puts
 * that into the stream when the thread opens and at the end of every turn (`fast_mode_state` and
 * `fast_mode_disabled_reason` on `system/init` and `result`, see CodexSession.fastReport). One function reads the
 * two together, so the desk's menu and chip and the phone's sheet cannot tell different stories - and a tier set
 * in Codex's config shows as plainly as one asked for here: it spends the same limits.
 */

/**
 * The model menu's entry for fast mode - a switch rather than a model. A sentinel prefixed so that it can never be
 * a model's name, the way the menu's way out to "Add a model" is (see ADD_MODEL in catalog.ts).
 */
export const FAST_MODE = 'acc:fast-mode'

/**
 * The mark fast mode wears - on the model chip, in the menu, on the phone's chip. A text glyph rather than the
 * emoji: the emoji paints itself yellow, and the mark has to take the colour of what it says (lit, dim, warning).
 */
export const FAST_MARK = '↯'

/**
 * `/fast`, `/fast on` and `/fast off` - what each asks for, given what is SHOWN now (see runLocal in App). The
 * shown state rather than the ask: on from Codex's config is on, and a bare `/fast` over it turns it off for this
 * conversation, as the switch does.
 */
export const fastAsked = (argument: string, shown: FastModeShown): boolean => {
  const word = argument.trim().toLowerCase()
  if (word === 'on') return true
  if (word === 'off') return false
  return shown.kind === 'off'
}

/**
 * The argument of a `/fast` typed as a whole message - '' for a bare one - or null for anything else. The phone
 * answers it itself, as the panel does (see localCommand): the app-server knows no slash commands.
 */
export const fastCommand = (text: string): string | null => {
  const match = /^\/fast(?:\s+(on|off))?\s*$/i.exec(text.trim())
  return match ? (match[1] ?? '') : null
}

/** What the stream said at the thread's open or the last turn's end. */
export interface FastModeReport {
  state: 'on' | 'off'
  /** Why it does not serve an ask of this conversation - the IDE's word, absent when nothing blocks it. */
  reason?: string
}

/**
 * Not a "why" but "nothing to judge yet": the ask is newer than the last request that carried one to Codex - it was
 * changed mid-turn, say - so the turn this answer is about ran on what it was given (see CodexSession.fastReport).
 */
export const PENDING = 'pending'

/**
 * What the screen shows for fast mode:
 * - `off` - not on: nobody asked and Codex's config has no fast tier, or it was asked off;
 * - `waiting` - asked for, and Codex has not answered since (it does when the next message opens a turn);
 * - `on` - running on the fast tier; `fromSettings` when the panel did not ask for it - Codex's own config did;
 * - `unavailable` - asked for, and Codex ran without it, with the reason.
 */
export type FastModeShown =
  | { kind: 'off' }
  | { kind: 'waiting' }
  | { kind: 'on'; fromSettings?: boolean }
  | { kind: 'unavailable'; reason: string }

export const fastModeShown = (asked: boolean | undefined, report: FastModeReport | undefined): FastModeShown => {
  // An answer about an older ask is no answer: until the next one, the ask is what the next turn goes by.
  const answered = report !== undefined && report.reason !== PENDING

  if (!answered) return asked ? { kind: 'waiting' } : { kind: 'off' }
  if (report.state === 'on') return asked ? { kind: 'on' } : { kind: 'on', fromSettings: true }
  if (!asked) return { kind: 'off' }
  return report.reason ? { kind: 'unavailable', reason: report.reason } : { kind: 'waiting' }
}

/**
 * The catalogue's entry for a model: by value, "default" as the model it comes to (the one marked `isDefault`, as
 * levelsOf in catalog.ts reads it), else by the full name. Undefined without a catalogue or for a model it does not
 * name.
 */
const catalogueEntry = (models: ModelInfo[] | null, model: string): ModelInfo | undefined => {
  if (!models || models.length === 0) return undefined
  if (sameModel(model, DEFAULT_MODEL)) return models.find((option) => option.isDefault)
  return (
    models.find((option) => sameModel(option.value, model)) ??
    models.find((option) => option.resolved !== undefined && option.resolved !== '' && sameModel(option.resolved, model))
  )
}

/**
 * Whether a model has fast mode - a fast tier among its `serviceTiers` in Codex's catalogue (sent as `fast`). No
 * catalogue, or a model it does not name, is "no": Codex has no rule by name to fall back on, and a switch offered
 * where it means nothing would be a switch that does nothing (a model without the tier has it dropped from every
 * request - see CodexSessions.setModel).
 */
export const modelHasFastMode = (models: ModelInfo[] | null, model: string | undefined): boolean =>
  model ? catalogueEntry(models, model)?.fast === true : false

/**
 * Codex's own line about the model's fast tier ("1.5x speed, increased usage") - the one place that says how much
 * faster and whether it costs more. English, as Codex writes it; undefined when the catalogue says nothing.
 */
export const fastTierNote = (models: ModelInfo[] | null, model: string | undefined): string | undefined => {
  if (!model) return undefined
  const entry = catalogueEntry(models, model)
  return entry?.fast ? entry.fastDescription || undefined : undefined
}

/** Why fast mode cannot serve, in the panel's words - a general one for a word the panel has none for. */
export const fastReasonText = (t: Dict, reason: string): string => {
  const words = t.fast.reasons
  switch (reason) {
    case 'not_served':
      return words.notServed
    case 'model_not_allowed':
      return words.modelNotAllowed
    default:
      return words.unknown
  }
}

/** The line under the switch in the model menu - with Codex's own line about the tier while it is off. */
export const fastModeSub = (t: Dict, shown: FastModeShown, tier?: string): string => {
  switch (shown.kind) {
    case 'off':
      return tier ? t.fast.offSubTier(tier) : t.fast.offSub
    case 'waiting':
      return t.fast.waitingSub
    case 'on':
      return shown.fromSettings ? t.fast.fromSettingsSub : t.fast.onSub
    case 'unavailable':
      return t.fast.unavailableSub(fastReasonText(t, shown.reason))
  }
}
