import type { MenuOption } from './components/Menu'
import type { Dict } from './i18n/en'
import type { ModelInfo } from './protocol'
import { ASIDE_COMMAND } from './feed/side'

/**
 * The panel's own knowledge of Codex's models, efforts, modes and commands. What exists is Codex's to
 * say (the `models` message carries its live catalogue); this is what the panel shows until it has said
 * it, and the words the panel puts on each choice.
 */

/** The menu's name for "whatever Codex runs by default" - never sent to Codex as a model. */
export const DEFAULT_MODEL = 'default'

/**
 * The model catalogue until the real one arrives from Codex (see ModelInfo and the `models` message).
 * Only "default": Codex's models are an account's, a plan's and a date's business, and a list written
 * into the panel would be wrong the week it shipped. The live catalogue arrives with the sign-in check,
 * seconds after the panel opens.
 */
export const modelCatalogue = (t: Dict): MenuOption[] => [
  { id: DEFAULT_MODEL, label: t.models.default.label, sub: t.models.default.sub },
]

/**
 * The CLI's catalogue in the shape the menu understands, with the hand-added models after it. An
 * unavailable line is shown - exactly as the terminal does - but marked: seeing that a model exists and
 * why it cannot be chosen is more useful than not seeing it at all.
 *
 * The CLI's own descriptions come in English and cannot be anything else - it does not know what
 * language this panel is in. So where its list names a model we have words for, ours are used: what the
 * catalogue decides is *which* models exist, which is the part only it can know. Anything unfamiliar
 * keeps the description the CLI gave it - an English line for one model reads better than no line.
 *
 * [custom] stands apart from all of that and is added to whichever of the two lists is in force,
 * including the built-in one: a model somebody typed in exists because they said so, and a panel still
 * waiting for its catalogue must not be a panel that has lost it. A name the catalogue already carries
 * is not repeated - it is the same model, and the CLI's own line about it says more.
 */
export const modelOptions = (t: Dict, models: ModelInfo[] | null, custom: string[] = []): MenuOption[] => {
  const ours = modelCatalogue(t)

  const listed: MenuOption[] =
    models === null || models.length === 0
      ? []
      : models.map((model) => {
          const known = ours.find((option) => option.id === model.value)

          return {
            id: model.value,
            label: known?.label ?? (model.label || model.value),
            sub: known?.sub ?? model.description,
            ...(model.disabled ? { tag: t.models.unavailable } : {}),
          }
        })

  // "Default" stays on top whatever the catalogue says. Codex's list names models only, and a tab that
  // never picked one runs on "default" - without the entry it would stand in this menu with nothing
  // ticked. Once the catalogue marks its default model, the line says which one that is now.
  const standard = models?.find((model) => model.isDefault)
  const byDefault: MenuOption = {
    id: DEFAULT_MODEL,
    label: t.models.default.label,
    sub: standard ? t.models.defaultNow(standard.label || modelLabel(standard.value)) : t.models.default.sub,
  }
  const base = listed.some((option) => option.id === DEFAULT_MODEL) ? listed : [byDefault, ...listed]

  const added = custom
    .filter((name) => !base.some((option) => option.id === name))
    .map((name) => ({ id: name, label: name, sub: t.models.custom }))

  return added.length === 0 ? base : [...base, ...added]
}

/**
 * One and the same model, whatever case it was written in. Codex names models by exact ids and signs its
 * answers with the same id the menu picked - there is no alias to see through.
 */
const modelKey = (model: string): string => model.trim().toLowerCase()

/** Two names for one and the same model - see modelKey. */
export const sameModel = (one: string, other: string): boolean => modelKey(one) === modelKey(other)

/**
 * What a pick has to come true as: the model itself, or nothing for "default", where there is no telling
 * in advance which model Codex will run (see noteStreamModel, which asks this).
 */
export const modelFamily = (model: string): string => (modelKey(model) === DEFAULT_MODEL ? '' : modelKey(model))

/** A choice turned into the identifier behind it, when the catalogue knows one; otherwise as it is. */
const expandModel = (models: ModelInfo[] | null, model: string): string =>
  models?.find((option) => option.value === model)?.resolved || model

/**
 * Which model a given tab is genuinely working on - the same formula as the `model` variable in App.
 *
 * The choice, expanded through the catalogue, is the caption while the conversation runs on it: it is the
 * fullest of the names for one model - the signature under an answer loses the window mark, and by that
 * signature alone "Opus (1M context)" turned into a plain "Opus" a few seconds after being chosen, as if
 * the panel had reset the choice by itself. The stream's own name takes over only when it means another
 * model altogether - the CLI does swap them on its own (see ModelSwitchItem), and then the caption must
 * name what is genuinely at work.
 *
 * A function of its own rather than only inline in App: the subscription to the shell's messages is set
 * up once at mount (see App, the useEffect with subscribe) and has no render of its own - models and
 * prefs.model reach it through a ref rather than a closure, and the formula has to be exactly the one
 * used in the render, with no right to drift.
 */
export const resolvePanelModel = (
  panel: { pendingModel?: string; model?: string },
  models: ModelInfo[] | null,
  prefsModel: string,
): string => {
  const chosen = expandModel(models, prefsModel || DEFAULT_MODEL)
  const running = panel.pendingModel ?? panel.model
  if (!running) return chosen

  const actual = expandModel(models, running)
  return sameModel(chosen, actual) ? chosen : actual
}

/**
 * The model a conversation is genuinely working on, when it is not the one the setting names.
 *
 * The setting is one for every tab and project, while a model applies to the tab it was chosen in (see
 * ClaudeSessionHub.changeModel), and the agent may move a tab to another model itself mid-turn. Either
 * way the menu is obliged to tick what this conversation is actually busy with rather than what the
 * setting says (see modelMenu).
 *
 * It answers about the model alone and says nothing about whose doing the discrepancy is: the accent on
 * the MODEL button is drawn by the tab's own memory of a swap instead (see PanelState.switchedFrom) -
 * asking this question for it named a model chosen by hand in a neighbouring tab as the agent's doing.
 *
 * Empty when the model in force matches the chosen one or there is nothing to compare against: without
 * the catalogue it is unknown what the choice itself expands into ("default" - which one is that?), and
 * any discrepancy would be an invention.
 */
export const modelInForce = (
  models: ModelInfo[] | null,
  selected: string,
  actual: string | undefined,
): string | undefined => {
  if (!actual) return undefined

  const resolved = models?.find((option) => option.value === (selected || DEFAULT_MODEL))?.resolved
  if (!resolved) return undefined

  const running = expandModel(models, actual)
  return sameModel(resolved, running) ? undefined : running
}

/**
 * The list of models and the one ticked in it.
 *
 * While a conversation runs on the chosen model, what is ticked is the choice - "default" included,
 * which is a choice too. As soon as the agent moves to another model, the tick moves with it: the list
 * is obliged to show what the conversation is genuinely busy with. A model absent from the catalogue
 * (the CLI calls it something else, or does not show it at all) gets a line of its own - otherwise there
 * would be nothing to tick.
 *
 * That line does not settle into the catalogue: the catalogue is shared across tabs, while the switch
 * belongs to one conversation. A neighbouring tab should know nothing about it - neither by an extra
 * menu entry nor by a tick that has moved.
 */
export const modelMenu = (
  t: Dict,
  models: ModelInfo[] | null,
  /** The models added by hand - see modelOptions, which merges them into whichever list is in force. */
  custom: string[],
  selected: string,
  switched: string | undefined,
): { options: MenuOption[]; selected: string } => {
  const options = modelOptions(t, models, custom)

  // The way to the screen where a model is added, and the only way anybody finds it: the settings list
  // is where the entries are managed, but nobody goes looking there for a model that is missing from
  // this menu. Last, after everything the menu is actually for, and it is not a value - App answers this
  // id by opening the screen rather than by choosing anything (see the sentinel's use there).
  const add: MenuOption = { id: ADD_MODEL, label: t.models.add, icon: '+', sub: t.models.addSub }

  if (!switched) return { options: [...options, add], selected: selected || DEFAULT_MODEL }

  const known = models?.find((option) => option.resolved === switched || option.value === switched)
  if (known) return { options: [...options, add], selected: known.value }

  return {
    options: [...options, { id: switched, label: modelLabel(switched), sub: t.models.switchedItself }, add],
    selected: switched,
  }
}

/**
 * The menu entry that leads to the screen instead of choosing a model.
 *
 * A sentinel rather than an empty id, and prefixed so that it can never be a model: it travels through
 * the same `onPick` every model does, and a name the CLI might one day use would be launched as one.
 */
export const ADD_MODEL = 'acc:add-model'

/**
 * Whether this is usable as a model name at all.
 *
 * Not a guess at what a provider will accept - nobody here can know that, and the whole point of a
 * hand-added model is that Claude Code does not know it either. It is the one thing that IS knowable:
 * the name travels as a launch argument, and an argument holding a line feed or a quotation mark is cut
 * short by a shell we never asked for, silently, taking the rest of the command line with it (see
 * ClaudeLaunch). A space would do the same by splitting one argument into two, and a comma is out for a
 * smaller reason: it separates the entries where the IDE keeps this list.
 *
 * The IDE drops the same names on the way in (see ClaudePreferences.customModels) - this half only keeps
 * the button from promising something that will be thrown away.
 */
export const isModelName = (name: string): boolean =>
  name.length > 0 && name.length <= 120 && !/[\s"'`\\,]/.test(name)

/** The reasoning levels one model has and the one it starts on - see levelsOf. */
export interface ModelLevels {
  efforts: string[]
  defaultEffort: string
  configured: boolean
}

/**
 * The levels of [model], from Codex's own catalogue: gpt-5.5 has no max and no ultra, and each model starts
 * on a level of its own. "default" is the catalogue's default model. Null when the catalogue has not
 * arrived or does not name the model - a model added by hand, an IDE too old to send levels - and then
 * every level is offered, as before.
 */
export const levelsOf = (models: ModelInfo[] | null, model: string): ModelLevels | null => {
  const known =
    modelKey(model || DEFAULT_MODEL) === DEFAULT_MODEL
      ? models?.find((option) => option.isDefault)
      : models?.find((option) => sameModel(option.value, model) || sameModel(option.resolved, model))
  if (!known?.efforts?.length) return null

  return {
    efforts: known.efforts,
    defaultEffort: known.defaultEffort ?? '',
    configured: known.defaultEffortConfigured === true,
  }
}

/**
 * Whether [effort] is a level the model has. Codex takes a level the model lacks without a word and runs
 * on some other one, so a chip left on it names a level nothing is running at. `auto` fits everything: it
 * is the model's own default by definition.
 */
export const effortFits = (levels: ModelLevels | null, effort: string): boolean =>
  !levels || effort === 'auto' || levels.efforts.includes(effort)

/**
 * Strongest first, and `auto` above them all - the way the list is read rather than the way the values
 * grow. Reaching for this menu means reaching for more thinking, and what is reached for should not sit
 * at the bottom of a list that opens at its top.
 *
 * With the model's [levels] known, only its own levels stand here and "default" marks the one it starts
 * on; without them every level does, and "default" stays on medium, which is what most models start on.
 */
export const effortOptions = (t: Dict, levels: ModelLevels | null = null): MenuOption[] => {
  const starts = levels?.defaultEffort || 'medium'
  const tagOf = (id: string, own?: string): string | undefined =>
    id === starts ? (levels?.configured ? t.effort.tags.configured : t.effort.tags.default) : own

  // The captions are Codex's own values and stay as they are in every language: what is being chosen here
  // is literally the word Codex is given.
  const all: MenuOption[] = [
    { id: 'ultra', label: 'ultra', tag: tagOf('ultra', t.effort.tags.ultra), sub: t.effort.ultracode.sub },
    { id: 'max', label: 'max', tag: tagOf('max', t.effort.tags.slow), sub: t.effort.max.sub },
    { id: 'xhigh', label: 'xhigh', tag: tagOf('xhigh'), sub: t.effort.xhigh.sub },
    { id: 'high', label: 'high', tag: tagOf('high'), sub: t.effort.high.sub },
    { id: 'medium', label: 'medium', tag: tagOf('medium'), sub: t.effort.medium.sub },
    { id: 'low', label: 'low', tag: tagOf('low'), sub: t.effort.low.sub },
  ]
  const own = levels ? all.filter((option) => levels.efforts.includes(option.id)) : all

  return [{ id: 'auto', label: 'auto', sub: t.effort.auto.sub }, ...own.map(({ tag, ...rest }) => (tag ? { ...rest, tag } : rest))]
}

export type ContextMode = 'standard' | 'long'

export interface ContextLimits {
  standard: number
  long: number
}

export const contextLimits = (models: ModelInfo[] | null, model: string): ContextLimits | null => {
  const known =
    modelKey(model || DEFAULT_MODEL) === DEFAULT_MODEL
      ? models?.find((option) => option.isDefault)
      : models?.find((option) => sameModel(option.value, model) || sameModel(option.resolved, model))
  if (!known?.standardContext || !known.longContext) return null
  return { standard: known.standardContext, long: known.longContext }
}

export const formatContextWindow = (tokens: number): string => {
  if (tokens >= 1_000_000) return `${Number((tokens / 1_000_000).toFixed(1))}M`
  return `${Math.round(tokens / 1_000)}K`
}

export const contextOptions = (t: Dict, limits: ContextLimits | null): MenuOption[] => {
  const standard = limits ? formatContextWindow(limits.standard) : '?'
  const long = limits ? formatContextWindow(limits.long) : '?'
  const unavailable = !limits || limits.long <= limits.standard

  return [
    { id: 'standard', label: t.selectors.context.standard, sub: t.selectors.context.standardSub(standard) },
    {
      id: 'long',
      label: t.selectors.context.long,
      sub: unavailable ? t.selectors.context.unavailable : t.selectors.context.longSub(long),
      ...(unavailable ? { disabled: true } : {}),
    },
  ]
}

export const contextShortLabel = (mode: ContextMode, limits: ContextLimits | null, actualMax?: number): string => {
  const size = actualMax || (mode === 'long' ? limits?.long : limits?.standard)
  const name = mode === 'long' ? 'Long' : 'Std'
  return size ? `${name} ${formatContextWindow(size)}` : name
}

/**
 * The first entry of both "new chats" lists: nothing pinned at all, so a new tab starts on whatever was
 * last chosen in one - which is what the panel did before the setting existed.
 *
 * A sentinel rather than an empty id, and prefixed the way ADD_MODEL is, for the same reason: it travels
 * through the same onPick every real value does, and an empty string would be indistinguishable from a
 * model or a level the panel simply has no name for. What the IDE is sent for it IS the empty string -
 * that is how the setting spells "nothing pinned" (see ClaudePreferences.newTabModel).
 */
export const LAST_USED = 'acc:last-used'

/**
 * The models a new tab may be pinned to, with "as last chosen" at the top.
 *
 * [lastPick] is not a choice on this list but a fact about it: the first entry has to say what it
 * currently amounts to, or it promises something unnamed - the same reason the language picker names the
 * IDE's language under "Automatic".
 */
export const newTabModelOptions = (
  t: Dict,
  models: ModelInfo[] | null,
  custom: string[],
  lastPick: string,
): MenuOption[] => {
  const options = modelOptions(t, models, custom)
  const pick = lastPick || DEFAULT_MODEL
  const named = options.find((option) => option.id === pick)?.label ?? modelLabel(pick)

  return [{ id: LAST_USED, label: t.newChat.lastUsed, sub: t.newChat.lastUsedNow(named) }, ...options]
}

/** The same list for the effort, and the level's own word is its caption - see effortOptions. */
export const newTabEffortOptions = (t: Dict, lastPick: string): MenuOption[] => [
  { id: LAST_USED, label: t.newChat.lastUsed, sub: t.newChat.lastUsedNow(lastPick) },
  ...effortOptions(t),
]

/**
 * The five modes the MODE chip offers - each one a preset of Codex's approval policy and sandbox (see
 * PermissionModes.kt on the IDE side, where the pairs are spelled out).
 */
export const modeOptions = (t: Dict): MenuOption[] => [
  {
    id: 'manual',
    label: t.modes.manual.label,
    key: '⇧⇥',
    sub: t.modes.manual.sub,
  },
  {
    // Codex's "Auto": its own default for a trusted folder, and this panel's too.
    id: 'acceptEdits',
    label: t.modes.acceptEdits.label,
    tag: t.modes.tags.default,
    key: '⇧⇥',
    sub: t.modes.acceptEdits.sub,
  },
  {
    id: 'readOnly',
    label: t.modes.readOnly.label,
    tag: t.modes.tags.readOnly,
    key: '⇧⇥',
    sub: t.modes.readOnly.sub,
  },
  {
    id: 'plan',
    label: t.modes.plan.label,
    tag: t.modes.tags.readOnly,
    key: '⇧⇥',
    sub: t.modes.plan.sub,
  },
  {
    id: 'bypassPermissions',
    label: t.modes.bypassPermissions.label,
    tag: t.modes.tags.danger,
    danger: true,
    sub: t.modes.bypassPermissions.sub,
  },
]

/**
 * A command from the hint. Some the panel runs itself, some travel to the agent.
 *
 * The built-in list was checked against a live agent: not all of them are available in streaming mode -
 * `/clear`, `/compact`, `/resume`, `/export`, `/permissions`, `/status` are interactive there and answer
 * with a refusal, so they are not here.
 */
export interface CommandOption {
  id: string
  hint: string
  /** The panel runs it itself and does not send it to the agent. */
  local?: boolean
  /**
   * The argument's syntax, as in the native terminal ("[low|medium|...] [--fix] [<target>]") - shown as
   * grey text right after the command's name, until the argument itself is being typed. Most commands
   * have none: it comes from the frontmatter of a command's or skill's file (see ClaudeCommandHints.kt)
   * rather than being invented by us.
   */
  argumentHint?: string
}

export const panelCommands = (t: Dict): CommandOption[] => [
  { id: 'resume', hint: t.commands.resume, local: true },
  { id: 'fork', hint: t.commands.fork, local: true },
  { id: 'login', hint: t.commands.login, local: true },
  { id: 'logout', hint: t.commands.logout, local: true },
  /*
   * The panel's, because app-server knows no slash commands at all. A name renames the tab the way a
   * double click does - and Codex's own record of the thread with it (thread/name/set), so the history
   * and `codex resume` in a terminal show it too - and no name opens the field on the tab (see
   * TabNameField), which is what a bare rename is asking for.
   */
  { id: 'rename', hint: t.commands.rename, local: true, argumentHint: t.commands.renameArgument },
  /*
   * The panel's only when nothing follows it: alone, it opens the panel's own screen of Codex's settings
   * (see CodexConfig.tsx). With `key=value` after it the IDE writes that one setting into Codex's config
   * as typed (see localCommand and CodexCommands).
   */
  { id: 'config', hint: t.commands.config, local: true },
  /*
   * A question beside the work - Codex's own `/side` in a terminal, which also answers to `/btw`. The IDE
   * asks it in an ephemeral fork of the conversation, the way Codex's terminal does (see SideQuestion.kt),
   * and the answer goes into a card over the field rather than into the conversation (see feed/side). It
   * is the one panel command the phone keeps (see phoneCommands).
   */
  { id: ASIDE_COMMAND, hint: t.commands.btw, local: true, argumentHint: t.commands.btwArgument },
]

/**
 * The commands of Codex's own terminal that the panel carries out through its conversation (see
 * CodexCommands on the IDE side) - the app-server has no slash commands of its own, so these are the ones
 * that mean something here.
 */
export const builtinCommands = (t: Dict): CommandOption[] => [
  { id: 'model', hint: t.commands.model },
  { id: 'effort', hint: t.commands.effort },
  { id: 'compact', hint: t.commands.compact },
  { id: 'new', hint: t.commands.newChat },
  { id: 'init', hint: t.commands.init },
  {
    id: 'review',
    hint: t.commands.codeReview,
    argumentHint: '[<base branch> | commit <sha> | <instructions>]',
  },
]

/**
 * Brings a mode's name to the one we use. `default` is what this mode used to be called: it sits in
 * saved settings and may arrive from the agent, and the panel must not show an unfamiliar mode because
 * of that.
 */
export const normalizeMode = (mode: string): string => {
  if (mode === 'default') return 'manual'
  // Claude's classifier modes, out of an old setting: the nearest Codex preset is its default.
  if (mode === 'auto' || mode === 'dontAsk') return 'acceptEdits'
  return mode
}

export const modeLabel = (t: Dict, mode: string): string =>
  modeOptions(t).find((option) => option.id === normalizeMode(mode))?.label ?? mode

/**
 * Which of the optional modes this conversation has available. Neither is switched on by the panel:
 * bypass is allowed by the session's launch (and forbidden by an organization's policy), auto by the
 * agent's own availability - so both have to be asked about anew every time.
 */
export interface ModeAvailability {
  bypass: boolean
  auto: boolean
}

/**
 * MODE_OPTIONS with the unavailable options marked (see ModeAvailability) - by the same trick already
 * used for unavailable models (see modelOptions): the entry is visible and understandable but not
 * clickable, instead of answering with an agent's error after the click.
 */
export const modeMenuOptions = (t: Dict, available: ModeAvailability): MenuOption[] =>
  modeOptions(t).map((option) =>
    option.id === 'bypassPermissions' && !available.bypass ? { ...option, disabled: true } : option,
  )

/**
 * Remembers a mode the agent refused (for now only bypass: it does not depend on the model, only on an
 * organization's policy, so one refusal genuinely holds for the whole panel). For auto there is a memory
 * of its own, per model - see autoRefusedModels in App.tsx.
 */
export const withRefusedMode = (refused: string[], mode: string): string[] => {
  const known = normalizeMode(mode)
  return refused.includes(known) ? refused : [...refused, known]
}

/**
 * The next mode on Shift+Tab. The order and every branch repeat the terminal Claude Code one to one: Ask
 * → Accept edits → Plan → Bypass → Auto → Ask, with an unavailable mode simply stepped over. Everything
 * outside the cycle (Don't ask, and an unfamiliar name out of an old conversation) returns to the start -
 * which is what it does there too.
 */
export const nextMode = (mode: string, available: ModeAvailability): string => {
  switch (normalizeMode(mode)) {
    case 'manual':
      return 'acceptEdits'
    case 'acceptEdits':
      return 'readOnly'
    case 'readOnly':
      return 'plan'
    case 'plan':
      return available.bypass ? 'bypassPermissions' : 'manual'
    default:
      return 'manual'
  }
}

/**
 * A mode's caption for the button in the bottom line. It is fixed width, and the full "Bypass
 * permissions" does not fit there - and should not: a button cannot change width when the mode changes,
 * that jerks the whole row.
 */
const modeShort = (t: Dict): Record<string, string> => ({
  manual: t.modes.manual.short,
  acceptEdits: t.modes.acceptEdits.short,
  readOnly: t.modes.readOnly.short,
  plan: t.modes.plan.short,
  bypassPermissions: t.modes.bypassPermissions.short,
})

export const modeShortLabel = (t: Dict, mode: string): string =>
  modeShort(t)[normalizeMode(mode)] ?? modeLabel(t, mode)

/**
 * A model's caption on the bottom line: `gpt-5.6-sol` reads "GPT-5.6 Sol", `gpt-5.1-codex-max` reads
 * "GPT-5.1 Codex Max". Anything that is not shaped like an OpenAI id is shown as it is.
 */
export const modelLabel = (model?: string): string => {
  if (!model) return DEFAULT_MODEL_LABEL

  const parts = model.trim().split('-').filter(Boolean)
  if (parts[0]?.toLowerCase() !== 'gpt' || parts.length < 2) return model

  const tail = parts
    .slice(2)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
  return tail ? `GPT-${parts[1]} ${tail}` : `GPT-${parts[1]}`
}

/** The caption until the model is named either by a choice or by the agent itself. */
const DEFAULT_MODEL_LABEL = 'default'

/**
 * How many columns a caption takes in the monospaced font the selectors are set in.
 *
 * Not its length: a Han character, a kana or a hangul syllable is drawn full width - two columns for one
 * character. Measured by length, the Chinese "不询问" (three characters, six columns) came out shorter
 * than "Don't ask" and the button was built too narrow for its own caption in half the languages the
 * panel now speaks.
 */
export const columns = (label: string): number =>
  [...label].reduce((total, character) => total + (isWide(character) ? 2 : 1), 0)

/** The ranges that are drawn full width: CJK and the kana, hangul and the fullwidth forms beside them. */
const isWide = (character: string): boolean => {
  const code = character.codePointAt(0) ?? 0
  return (
    (code >= 0x1100 && code <= 0x115f) || // Hangul Jamo
    (code >= 0x2e80 && code <= 0x303e) || // CJK radicals and punctuation
    (code >= 0x3041 && code <= 0x33ff) || // kana, hangul compatibility jamo, CJK compatibility
    (code >= 0x3400 && code <= 0x4dbf) || // CJK extension A
    (code >= 0x4e00 && code <= 0x9fff) || // CJK unified ideographs
    (code >= 0xa960 && code <= 0xa97f) ||
    (code >= 0xac00 && code <= 0xd7a3) || // hangul syllables
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0xfe30 && code <= 0xfe4f) ||
    (code >= 0xff00 && code <= 0xff60) || // fullwidth forms
    (code >= 0xffe0 && code <= 0xffe6)
  )
}

/**
 * The longest caption that could end up on the button.
 *
 * The selector's width is measured by it rather than by whatever stands there right now. Otherwise every
 * model or mode change would change the button's width and with it the neighbours' positions: the whole
 * row would jerk over nothing.
 *
 * The value is set in the same monospaced font as the rest of the feed, so more columns is wider.
 */
const widestLabel = (labels: string[]): string =>
  labels.reduce((longest, label) => (columns(label) > columns(longest) ? label : longest), '')

/**
 * These three samples hold the width - the button draws them as an invisible spacer (see Selector). They
 * are assembled from the same lists the real captions come from, so that a new mode or model family
 * widens the button by itself, without an edit here.
 *
 * The rule they all obey: the reserve is the widest caption that can genuinely stand here, and not a
 * column more. A column no choice can ever fill is dead space on a button that already carries a label -
 * and it is dead space on all three of them at once, which is what used to push the row onto a second
 * line. The width itself does not move when the value does: that is the whole reason for the reserve.
 *
 * A model absent from the families (the CLI calls it its own way) may turn out longer - such a caption
 * is cut with an ellipsis but leaves the row alone. The full name is always in the hover tooltip.
 */

/**
 * The fallback reserve for MODEL, used until Codex's own catalogue arrives - see [modelSample], which is
 * what the button actually measures itself by. Shaped like the captions Codex's ids make.
 */
export const MODEL_SAMPLE = widestLabel([DEFAULT_MODEL_LABEL, 'GPT-5.6 Terra'])

/**
 * The room MODEL reserves for its value, measured against the models this installation actually offers.
 *
 * The catalogue is the only thing that knows which captions are reachable here: an organization's list,
 * a CLI newer than the panel, a family the panel has never heard of. Guessing at it in advance is how
 * the button came to reserve room for "Sonnet 4.5 1M" while the longest caption anyone could choose was
 * two columns shorter - and those two columns stood empty on the button whatever was picked in it.
 *
 * Until the catalogue arrives, the built-in shape stands in for it (see MODEL_SAMPLE). The reserve can
 * therefore settle once, in the first moment after the panel starts; it never moves on a choice, which
 * is the promise that matters.
 */
export const modelSample = (models: ModelInfo[] | null): string =>
  models === null || models.length === 0
    ? MODEL_SAMPLE
    : widestLabel([DEFAULT_MODEL_LABEL, ...models.map((model) => modelLabel(model.resolved || model.value))])

/** What EFFORT says on the button: Codex's own word, which is short enough as it is. */
export const effortShortLabel = (effort: string): string => (effort === 'ultracode' ? 'ultra' : effort)

/** Unchanged by the language: Codex's own values are what stands on this button. */
export const EFFORT_SAMPLE = widestLabel(['auto', 'ultra', 'max', 'xhigh', 'high', 'medium', 'low'])

export const modeSample = (t: Dict): string => widestLabel(Object.values(modeShort(t)))
