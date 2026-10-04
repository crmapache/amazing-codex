import { LOCALES } from './i18n'
import { DICTIONARIES } from './i18n/all'
import { en } from './i18n/en'
import { ru } from './i18n/ru'
import { describe, expect, it } from 'vitest'
import {
  columns,
  contextLimits,
  contextOptions,
  contextShortLabel,
  effortFits,
  effortOptions,
  levelsOf,
  effortShortLabel,
  EFFORT_SAMPLE,
  modeSample,
  MODEL_SAMPLE,
  modelSample,
  modelCatalogue,
  modeOptions,
  modeLabel,
  modeMenuOptions,
  modelLabel,
  ADD_MODEL,
  isModelName,
  modelMenu,
  modelOptions,
  resolvePanelModel,
  modelInForce,
  modeShortLabel,
  nextMode,
  normalizeMode,
  withRefusedMode,
} from './catalog'

describe('permission modes', () => {
  it('brings an old name to the one the mode is called by now', () => {
    // `default` lies in the saved settings of past versions and arrives from the agent.
    expect(normalizeMode('default')).toBe('manual')
  })

  it('reads the modes Codex has no counterpart for as its default preset', () => {
    expect(normalizeMode('auto')).toBe('acceptEdits')
    expect(normalizeMode('dontAsk')).toBe('acceptEdits')
  })

  it('leaves the other modes alone', () => {
    for (const mode of ['acceptEdits', 'readOnly', 'plan', 'bypassPermissions']) {
      expect(normalizeMode(mode)).toBe(mode)
    }
  })

  it('finds a caption by the old name too - otherwise a raw value ends up in the status row', () => {
    expect(modeLabel(en, 'default')).toBe('Ask every time')
    expect(modeShortLabel(en, 'default')).toBe('Ask')
    expect(modeShortLabel(en, 'manual')).toBe('Ask')
    expect(modeShortLabel(en, 'acceptEdits')).toBe('Auto')
  })

  it('offers the five presets and nothing Codex does not have', () => {
    expect(modeOptions(en).map((option) => option.id)).toEqual(['manual', 'acceptEdits', 'readOnly', 'plan', 'bypassPermissions'])
  })
})

describe('the Shift+Tab cycle of modes', () => {
  const nothingExtra = { bypass: false, auto: false }

  it('walks the presets from asking most to asking least', () => {
    expect(nextMode('manual', nothingExtra)).toBe('acceptEdits')
    expect(nextMode('acceptEdits', nothingExtra)).toBe('readOnly')
    expect(nextMode('readOnly', nothingExtra)).toBe('plan')
  })

  it('lets the first mode old name lead on round the circle rather than back into itself', () => {
    expect(nextMode('default', nothingExtra)).toBe('acceptEdits')
  })

  it('goes to full access after the plan when it is allowed', () => {
    expect(nextMode('plan', { bypass: true, auto: false })).toBe('bypassPermissions')
    expect(nextMode('bypassPermissions', { bypass: true, auto: false })).toBe('manual')
  })

  it('lets the circle step over a forbidden full access', () => {
    expect(nextMode('plan', nothingExtra)).toBe('manual')
  })
})

describe('the modes the agent refused', () => {
  it('remembers a refusal - the circle does not lead into that mode a second time', () => {
    expect(withRefusedMode([], 'bypassPermissions')).toEqual(['bypassPermissions'])
  })

  it('does not double the record on a repeated refusal', () => {
    expect(withRefusedMode(['bypassPermissions'], 'bypassPermissions')).toEqual(['bypassPermissions'])
  })

  it('brings a mode old name to the current one - otherwise the refusal is not recognised', () => {
    expect(withRefusedMode([], 'default')).toEqual(['manual'])
  })
})

describe('the availability of full access in the menu', () => {
  it('shows full access but marks it unavailable - like a model, rather than dropping it from the list', () => {
    const options = modeMenuOptions(en, { bypass: false, auto: false })

    expect(options.find((option) => option.id === 'bypassPermissions')?.disabled).toBe(true)
  })

  it('leaves an available mode an ordinary item, with no mark', () => {
    const options = modeMenuOptions(en, { bypass: true, auto: false })

    expect(options.find((option) => option.id === 'bypassPermissions')?.disabled).toBeUndefined()
  })

  it('does not let the unavailability of full access touch the other modes', () => {
    const options = modeMenuOptions(en, { bypass: false, auto: false })

    for (const id of ['manual', 'acceptEdits', 'readOnly', 'plan']) {
      expect(options.find((option) => option.id === id)?.disabled).toBeUndefined()
    }
  })
})

describe('the model catalogue', () => {
  const models = [
    { value: 'gpt-5.6-sol', label: 'GPT-5.6-Sol', description: 'Reliable agentic workhorse', resolved: 'gpt-5.6-sol' },
    { value: 'gpt-5.6-luna', label: 'GPT-5.6-Luna', description: 'Fast and affordable', resolved: 'gpt-5.6-luna' },
    { value: 'gpt-4-legacy', label: 'GPT-4 legacy', description: 'legacy', resolved: 'gpt-4-legacy', disabled: true },
  ]

  it('puts the live catalogue under the default entry', () => {
    expect(modelOptions(en, models).map((option) => option.id)).toEqual([
      'default',
      'gpt-5.6-sol',
      'gpt-5.6-luna',
      'gpt-4-legacy',
    ])
  })

  /** Codex's list names models only; a tab that never picked one would otherwise have nothing ticked. */
  it('says which model the default is once the catalogue marks it', () => {
    const marked = [{ ...models[0]!, isDefault: true }, ...models.slice(1)]
    expect(modelOptions(en, marked)[0]?.sub).toBe(en.models.defaultNow('GPT-5.6-Sol'))
    expect(modelOptions(en, models)[0]?.sub).toBe(en.models.default.sub)
  })

  it('shows the built-in list while there is no catalogue', () => {
    expect(modelOptions(en, null)).toEqual(modelCatalogue(en))
    expect(modelOptions(en, [])).toEqual(modelCatalogue(en))
  })

  /** The caption is as much the panel's own as the line under it - "Default (recommended)" is a sentence. */
  it('names the default in the panel’s language', () => {
    const models = [{ value: 'default', label: 'Default', resolved: 'gpt-5.6-sol', description: 'Recommended' }]

    expect(modelOptions(ru, models)[0]?.label).toBe(ru.models.default.label)
  })

  it('keeps Codex’s own words for its models', () => {
    const sol = modelOptions(ru, models).find((option) => option.id === 'gpt-5.6-sol')
    expect(sol?.sub).toBe('Reliable agentic workhorse')
    expect(sol?.label).toBe('GPT-5.6-Sol')
  })

  it('shows an unavailable model but marks it', () => {
    expect(modelOptions(en, models).find((option) => option.id === 'gpt-4-legacy')?.tag).toBe('unavailable')
  })
})

describe('the model the agent moved to itself', () => {
  const models = [
    { value: 'gpt-5.6-sol', label: 'GPT-5.6-Sol', description: '', resolved: 'gpt-5.6-sol' },
    { value: 'gpt-5.6-luna', label: 'GPT-5.6-Luna', description: '', resolved: 'gpt-5.6-luna' },
  ]

  it('does not count a conversation on the chosen model as a switch', () => {
    expect(modelInForce(models, 'gpt-5.6-sol', 'gpt-5.6-sol')).toBeUndefined()
    expect(modelInForce(models, 'gpt-5.6-luna', 'GPT-5.6-Luna')).toBeUndefined()
  })

  it('makes a move to another model visible', () => {
    expect(modelInForce(models, 'gpt-5.6-sol', 'gpt-5.6-terra')).toBe('gpt-5.6-terra')
  })

  it('does not invent a discrepancy without a catalogue', () => {
    expect(modelInForce(null, 'default', 'gpt-5.6-terra')).toBeUndefined()
    expect(modelInForce(models, 'unknown-choice', 'gpt-5.6-terra')).toBeUndefined()
  })

  it('keeps the tick on the choice while the conversation is on the chosen model', () => {
    expect(modelMenu(en, models, [], 'gpt-5.6-luna', undefined)).toMatchObject({ selected: 'gpt-5.6-luna' })
    expect(modelMenu(en, models, [], '', undefined)).toMatchObject({ selected: 'default' })
  })

  it('moves the tick onto the catalogue model after a move', () => {
    expect(modelMenu(en, models, [], 'gpt-5.6-sol', 'gpt-5.6-luna')).toMatchObject({ selected: 'gpt-5.6-luna' })
  })

  it('starts a row of its own for a model missing from the catalogue - otherwise there is nothing to mark', () => {
    const menu = modelMenu(en, models, [], 'gpt-5.6-sol', 'gpt-5.6-terra')

    expect(menu.selected).toBe('gpt-5.6-terra')
    expect(menu.options.map((option) => option.id)).toContain('gpt-5.6-terra')
    expect(menu.options.find((option) => option.id === 'gpt-5.6-terra')?.label).toBe('GPT-5.6 Terra')
    expect(models).toHaveLength(2)
  })

  it('ends the menu with the way to add a model, always', () => {
    expect(modelMenu(en, models, [], 'gpt-5.6-luna', undefined).options.at(-1)?.id).toBe(ADD_MODEL)
    expect(modelMenu(en, models, ['my-model'], 'gpt-5.6-luna', undefined).options.at(-1)?.id).toBe(ADD_MODEL)
    expect(modelMenu(en, models, [], 'gpt-5.6-sol', 'gpt-5.6-terra').options.at(-1)?.id).toBe(ADD_MODEL)
  })
})

/**
 * A model somebody named themselves - the whole of what a person on a proxy router or a gateway can do
 * about a catalogue that does not name what their provider serves.
 */
describe('the models added by hand', () => {
  const models = [
    { value: 'default', label: 'Default', description: '', resolved: 'claude-opus-5[1m]' },
    { value: 'sonnet', label: 'Sonnet', description: '', resolved: 'claude-sonnet-5' },
  ]

  it('puts them after the CLI catalogue', () => {
    const options = modelOptions(en, models, ['glm-4.6'])

    expect(options.map((option) => option.id)).toEqual(['default', 'sonnet', 'glm-4.6'])
    expect(options.at(-1)).toMatchObject({ label: 'glm-4.6', sub: en.models.custom })
  })

  /* The catalogue is what a panel is still waiting for in its first seconds, and the machine somebody
     adds a model on is exactly the machine whose catalogue may never come back at all. */
  it('puts them after the built-in list too, while no catalogue has arrived', () => {
    const ids = modelOptions(en, null, ['glm-4.6']).map((option) => option.id)

    expect(ids).toContain('glm-4.6')
    expect(ids.slice(0, -1)).toEqual(modelCatalogue(en).map((option) => option.id))
  })

  it('does not repeat a model the catalogue already names', () => {
    expect(modelOptions(en, models, ['sonnet']).map((option) => option.id)).toEqual(['default', 'sonnet'])
  })

  it('lets the tick stand on one of them', () => {
    expect(modelMenu(en, models, ['glm-4.6'], 'glm-4.6', undefined)).toMatchObject({ selected: 'glm-4.6' })
  })

  /*
   * Not an opinion about what a provider accepts - nobody here knows that. It is the one thing that is
   * knowable: the name goes out as a launch argument, and a shell we never asked for cuts an argument
   * short at a line feed or a quotation mark, taking the rest of the command line with it.
   */
  it('refuses a name that could not survive being a launch argument', () => {
    expect(isModelName('glm-4.6')).toBe(true)
    expect(isModelName('claude-fable-5-1[1m]')).toBe(true)
    expect(isModelName('')).toBe(false)
    expect(isModelName('two words')).toBe(false)
    expect(isModelName('with"quote')).toBe(false)
    expect(isModelName('with\nfeed')).toBe(false)
    // And a comma, for a smaller reason: it separates the entries where the IDE keeps this list.
    expect(isModelName('with,comma')).toBe(false)
    expect(isModelName('x'.repeat(200))).toBe(false)
  })
})

describe('the caption of the model a tab works on', () => {
  const models = [
    { value: 'default', label: 'Default', description: '', resolved: 'gpt-5.6-sol' },
    { value: 'gpt-5.6-luna', label: 'GPT-5.6-Luna', description: '', resolved: 'gpt-5.6-luna' },
  ]

  it('expands the default through the catalogue before the agent has said a word', () => {
    expect(resolvePanelModel({}, models, '')).toBe('gpt-5.6-sol')
    expect(resolvePanelModel({}, models, 'gpt-5.6-luna')).toBe('gpt-5.6-luna')
  })

  it('names what is genuinely at work once Codex has moved the model itself', () => {
    expect(resolvePanelModel({ model: 'gpt-5.6-terra' }, models, 'gpt-5.6-luna')).toBe('gpt-5.6-terra')
    expect(modelInForce(models, 'gpt-5.6-luna', 'gpt-5.6-terra')).toBe('gpt-5.6-terra')
  })

  it('shows a choice not yet confirmed by the agent', () => {
    expect(resolvePanelModel({ pendingModel: 'gpt-5.6-luna', model: 'gpt-5.6-sol' }, models, 'default')).toBe('gpt-5.6-luna')
  })
})

describe('the model caption in the bottom row', () => {
  it('names the generation and the model out of an OpenAI id', () => {
    expect(modelLabel('gpt-5.6-sol')).toBe('GPT-5.6 Sol')
    expect(modelLabel('gpt-5.1-codex-max')).toBe('GPT-5.1 Codex Max')
    expect(modelLabel('gpt-5')).toBe('GPT-5')
  })

  it('leaves the default and anything unfamiliar as it is', () => {
    expect(modelLabel('')).toBe('default')
    expect(modelLabel('default')).toBe('default')
    expect(modelLabel('llama3.1:70b')).toBe('llama3.1:70b')
  })
})

/**
 * The button measures its width off these samples rather than off what is chosen right now (see Selector
 * in StatusBar.tsx). Let a sample turn out shorter than the genuine caption and it gets clipped with an
 * ellipsis for no reason at all; let it turn out longer than any caption that can occur and the button
 * carries columns nothing will ever fill - which is what put the three of them onto two lines.
 */
describe('the width samples for the selectors', () => {
  it('keeps the model sample no shorter than any caption of the known families', () => {
    const labels = ['default', ...modelCatalogue(en).map((option) => modelLabel(option.id))]

    for (const label of labels) expect(label.length).toBeLessThanOrEqual(MODEL_SAMPLE.length)
  })

  /**
   * Once the CLI's catalogue is there, it and not the built-in shape says how wide the button has to be:
   * these are the only captions anyone in this installation can choose.
   */
  it('measures the model button by the catalogue Codex sent', () => {
    const models = [
      { value: 'default', label: 'Default', description: '', resolved: 'gpt-5.6-sol' },
      { value: 'gpt-5.6-terra', label: 'GPT-5.6-Terra', description: '', resolved: 'gpt-5.6-terra' },
    ]

    expect(modelSample(models)).toBe('GPT-5.6 Terra')
  })

  it('falls back to the built-in shape while the catalogue is unknown', () => {
    expect(modelSample(null)).toBe(MODEL_SAMPLE)
    expect(modelSample([])).toBe(MODEL_SAMPLE)
  })

  /**
   * The menu's own words, not the button's: what the button says is the short caption (see
   * effortShortLabel), and that is what has to fit into the room the sample holds.
   */
  it('keeps the effort sample no shorter than any caption the button can show', () => {
    for (const option of effortOptions(en)) {
      expect(columns(effortShortLabel(option.label)), option.id).toBeLessThanOrEqual(columns(EFFORT_SAMPLE))
    }
  })

  /** Codex's own words are short enough: the button says them as they are, and an old `ultracode` as `ultra`. */
  it('says every effort as Codex names it', () => {
    expect(effortShortLabel('ultracode')).toBe('ultra')

    for (const option of effortOptions(en)) expect(effortShortLabel(option.label)).toBe(option.label)
  })

  /**
   * No column stands empty whatever is chosen: the widest caption is exactly as wide as the room. A
   * sample wider than every caption is room nothing can fill - on three buttons at once, in a row that has
   * to fit into a panel somebody dragged narrow.
   */
  it('holds no more room than the widest caption needs', () => {
    expect(columns(EFFORT_SAMPLE)).toBe(
      Math.max(...effortOptions(en).map((option) => columns(effortShortLabel(option.label)))),
    )

    for (const { id } of LOCALES) {
      const dictionary = DICTIONARIES[id]

      expect(columns(modeSample(dictionary)), id).toBe(
        Math.max(...modeOptions(dictionary).map((option) => columns(modeShortLabel(dictionary, option.id)))),
      )
    }
  })

  /**
   * In every language, not only in English: the captions are translated while the button is not redrawn
   * per language, and a Han character takes two columns for one character - measured by length, the
   * Chinese sample came out narrower than the caption it is supposed to hold (see columns).
   */
  it('keeps the mode sample no shorter than any short caption, in every language', () => {
    for (const { id } of LOCALES) {
      const dictionary = DICTIONARIES[id]
      const sample = columns(modeSample(dictionary))

      for (const option of modeOptions(dictionary)) {
        expect(columns(modeShortLabel(dictionary, option.id)), `${id}: ${option.id}`).toBeLessThanOrEqual(sample)
      }
    }
  })

  it('counts a full-width character as the two columns it is drawn in', () => {
    expect(columns('Ask')).toBe(3)
    expect(columns('不问')).toBe(4)
    expect(columns('確認')).toBe(4)
    expect(columns('안 물음')).toBe(7)
  })
})

describe('the reasoning levels of one model', () => {
  // The shape Codex 0.152 gave for two of its models: the newest has ultra, gpt-5.5 stops at xhigh and
  // starts there too.
  const models = [
    {
      value: 'gpt-5.6-sol',
      label: 'GPT-5.6 Sol',
      description: '',
      resolved: 'gpt-5.6-sol',
      isDefault: true,
      efforts: ['low', 'medium', 'high', 'xhigh', 'max', 'ultra'],
      defaultEffort: 'medium',
    },
    {
      value: 'gpt-5.5',
      label: 'GPT-5.5',
      description: '',
      resolved: 'gpt-5.5',
      efforts: ['low', 'medium', 'high', 'xhigh'],
      defaultEffort: 'xhigh',
    },
  ]

  it('offers only the levels the model has, with auto on top', () => {
    const ids = effortOptions(en, levelsOf(models, 'gpt-5.5')).map((option) => option.id)
    expect(ids).toEqual(['auto', 'xhigh', 'high', 'medium', 'low'])
  })

  it('marks the level the model starts on as its default', () => {
    const options = effortOptions(en, levelsOf(models, 'gpt-5.5'))
    expect(options.find((option) => option.id === 'xhigh')?.tag).toBe(en.effort.tags.default)
    expect(options.find((option) => option.id === 'medium')?.tag).toBeUndefined()
  })

  it('distinguishes a configured effort from the model default', () => {
    const configured = [{ ...models[0], defaultEffort: 'max', defaultEffortConfigured: true }]
    const options = effortOptions(en, levelsOf(configured, 'gpt-5.6-sol'))
    expect(options.find((option) => option.id === 'max')?.tag).toBe(en.effort.tags.configured)
  })

  it('reads "default" as the model Codex runs when none is named', () => {
    expect(levelsOf(models, 'default')?.efforts).toContain('ultra')
    expect(levelsOf(models, '')?.defaultEffort).toBe('medium')
  })

  it('offers every level when the model is not in the catalogue', () => {
    expect(levelsOf(models, 'my-own-model')).toBeNull()
    expect(levelsOf(null, 'gpt-5.5')).toBeNull()
    expect(effortOptions(en, null).map((option) => option.id)).toEqual([
      'auto',
      'ultra',
      'max',
      'xhigh',
      'high',
      'medium',
      'low',
    ])
  })

  it('says a level the model lacks does not fit, and auto always does', () => {
    const levels = levelsOf(models, 'gpt-5.5')
    expect(effortFits(levels, 'ultra')).toBe(false)
    expect(effortFits(levels, 'high')).toBe(true)
    expect(effortFits(levels, 'auto')).toBe(true)
    expect(effortFits(null, 'ultra')).toBe(true)
  })
})

describe('context windows', () => {
  const models = [
    {
      value: 'gpt-6-astra',
      label: 'GPT-6 Astra',
      description: '',
      resolved: 'gpt-6-astra',
      isDefault: true,
      standardContext: 258_400,
      longContext: 828_400,
    },
    {
      value: 'gpt-5.5',
      label: 'GPT-5.5',
      description: '',
      resolved: 'gpt-5.5',
      standardContext: 258_400,
      longContext: 258_400,
    },
  ]

  it('resolves the default model and shows the effective sizes', () => {
    const limits = contextLimits(models, 'default')
    expect(limits).toEqual({ standard: 258_400, long: 828_400 })
    expect(contextShortLabel('standard', limits)).toBe('Std 258K')
    expect(contextShortLabel('long', limits)).toBe('Long 828K')
  })

  it('disables long context when the model has no larger window', () => {
    const options = contextOptions(en, contextLimits(models, 'gpt-5.5'))
    expect(options.find((option) => option.id === 'long')?.disabled).toBe(true)
  })
})
