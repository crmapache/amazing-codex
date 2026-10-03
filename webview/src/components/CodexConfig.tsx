import { useEffect, useState } from 'react'
import { useFieldHistory } from '../hooks/useFieldHistory'
import { useT } from '../i18n'
import type { Dict } from '../i18n/en'
import type { CodexConfigSetting, CodexProjectLayer } from '../protocol'
import s from './sideMenu.module.css'

/** What the screen knows at the moment - the IDE's last word about Codex's settings. */
export interface CodexConfigState {
  settings: CodexConfigSetting[]
  loading: boolean
  /** One of the screen's own words - `noCli` - or empty. */
  error: string
  /** The project's own Codex settings and whether Codex reads them - absent until the IDE has said. */
  project?: CodexProjectLayer
}

export const EMPTY_CODEX_CONFIG: CodexConfigState = { settings: [], loading: true, error: '' }

/** The outcome key a trust change is answered under - see CodexConfigDesk.TRUST_KEY. */
export const TRUST_KEY = 'projects.trust_level'

/** The groups in the order they stand on the screen - what shapes the work first, then the terminal's. */
const GROUPS: CodexConfigSetting['group'][] = ['work', 'terminal', 'other']

/** A setting that takes exactly on and off - drawn as a switch rather than as two words to pick from. */
const isSwitch = (setting: CodexConfigSetting): boolean =>
  setting.options.length === 2 && setting.options.includes('true') && setting.options.includes('false')

/**
 * The settings the panel's own chips decide per tab - the model, the effort and the mode - see the `chips`
 * words. In the panel they are what a tab left on "Default" or "auto" goes by, and what a terminal starts on.
 */
const DECIDED_BY_CHIPS = new Set(['model', 'model_reasoning_effort', 'approval_policy', 'sandbox_mode'])

const labelOf = (t: Dict, key: string): string =>
  (t.codexConfig.labels as Record<string, string | undefined>)[key] ?? key

const lockedWords = (t: Dict, layer: CodexConfigSetting['lockedBy']): string =>
  layer === 'policy' ? t.codexConfig.lockedPolicy : t.codexConfig.lockedProject

/**
 * Codex's own settings - its `config.toml`, the same file a terminal reads - opened by `/config` in the
 * panel and from the settings list (see CodexConfig.kt and CodexConfigDesk.kt on the IDE's side for where
 * every value comes from and how it is written).
 *
 * The values, where each comes from and what an organisation allows are all Codex's own answers: the
 * screen only draws them. A change goes out one at a time and the rows hold still until Codex answers, and
 * a change that went into the file but is overruled - by the project's own settings, by a policy - says so
 * under its row rather than ticking a value nothing runs on.
 *
 * Above the settings stands the project's own block: whether it has Codex settings of its own and whether
 * Codex reads them. Codex reads a project's `.codex/config.toml`, hooks and exec policies only once the
 * project is trusted - the question its terminal asks at the first start in a folder, and one the panel
 * never asked, so a project opened only here kept its settings unread with no word about it.
 *
 * A value that is not known is drawn as none picked, with a line saying Codex decides, rather than as a
 * guess: a switch showing "on" for a setting that is off is worse than no switch.
 */
export const CodexConfig = ({
  state,
  pending,
  failures,
  onSet,
  onTrust,
}: {
  state: CodexConfigState
  /** The key a change is on its way for, if any - [TRUST_KEY] for the project's trust. */
  pending: string | null
  /** Codex's own words about a change it did not take, by key - its sentence, `overridden`, or empty. */
  failures: Record<string, string>
  onSet: (key: string, value: string) => void
  onTrust: (trusted: boolean) => void
}) => {
  const t = useT()
  const busy = pending !== null

  const note = (setting: CodexConfigSetting) => {
    if (pending === setting.key) return <span className={s.switchHint}>{t.codexConfig.saving}</span>
    if (setting.lockedBy) return <span className={s.switchHint}>{lockedWords(t, setting.lockedBy)}</span>

    const lines = [
      setting.value === undefined ? t.codexConfig.notSet : '',
      DECIDED_BY_CHIPS.has(setting.key) ? t.codexConfig.chips : '',
    ].filter(Boolean)

    return lines.length > 0 ? <span className={s.switchHint}>{lines.join(' ')}</span> : null
  }

  const failure = (setting: CodexConfigSetting) =>
    setting.key in failures ? (
      <div className={`${s.message} ${s.messageBad}`}>
        {failures[setting.key] === 'overridden' ? t.codexConfig.overridden : failures[setting.key] || t.codexConfig.failed}
      </div>
    ) : null

  const row = (setting: CodexConfigSetting) => {
    const locked = Boolean(setting.lockedBy)
    const disabled = busy || locked

    // A switch only when its state is known: an unknown one is two words with neither picked (below).
    if (isSwitch(setting) && setting.value !== undefined) {
      const on = setting.value === 'true'
      return (
        <div key={setting.key} className={s.configItem}>
          <button
            type="button"
            className={`${s.switchRow} ${disabled ? s.switchRowMoot : ''}`}
            aria-pressed={on}
            disabled={disabled}
            onClick={() => onSet(setting.key, on ? 'false' : 'true')}
          >
            <span className={s.switchText}>
              <span className={s.switchLabel}>{labelOf(t, setting.key)}</span>
              {note(setting)}
            </span>
            <span className={`${s.switchTrack} ${on ? s.switchTrackOn : ''}`}>
              <span className={`${s.switchKnob} ${on ? s.switchKnobOn : ''}`} />
            </span>
          </button>
          {failure(setting)}
        </div>
      )
    }

    return (
      <div key={setting.key} className={s.configItem}>
        <div className={`${s.configCard} ${disabled ? s.switchRowMoot : ''}`}>
          <span className={s.switchText}>
            <span className={s.switchLabel}>{labelOf(t, setting.key)}</span>
            {note(setting)}
          </span>
          {setting.free ? (
            <FreeValue
              value={setting.value ?? ''}
              disabled={disabled}
              label={labelOf(t, setting.key)}
              onSave={(value) => onSet(setting.key, value)}
            />
          ) : (
            <div className={s.configChoices} role="radiogroup" aria-label={labelOf(t, setting.key)}>
              {setting.options.map((option) => {
                const picked = option === setting.value
                return (
                  <button
                    key={option}
                    type="button"
                    role="radio"
                    aria-checked={picked}
                    className={`${s.configChoice} ${picked ? s.configChoiceOn : ''}`}
                    disabled={disabled}
                    onClick={() => {
                      if (!picked) onSet(setting.key, option)
                    }}
                  >
                    {optionWords(t, option)}
                  </button>
                )
              })}
            </div>
          )}
        </div>
        {failure(setting)}
      </div>
    )
  }

  return (
    <div className={s.screen}>
      <div className={s.screenNote}>{state.error ? '' : t.codexConfig.intro}</div>

      {state.error ? (
        <div className={`${s.message} ${s.messageBad}`}>
          {state.error === 'noCli' ? t.codexConfig.noCli : t.codexConfig.unreadable}
        </div>
      ) : null}

      {state.project ? (
        <ProjectBlock
          layer={state.project}
          busy={busy}
          saving={pending === TRUST_KEY}
          failure={TRUST_KEY in failures ? failures[TRUST_KEY] || t.codexConfig.failed : ''}
          onTrust={onTrust}
        />
      ) : null}

      {state.loading && state.settings.length === 0 && !state.error ? (
        <div className={s.screenEmpty}>{t.codexConfig.loading}</div>
      ) : null}

      {GROUPS.map((group) => {
        const settings = state.settings.filter((setting) => setting.group === group)
        if (settings.length === 0) return null

        return (
          <section key={group} className={s.configGroup}>
            <div className={s.screenLabel}>{t.codexConfig.groups[group]}</div>
            <div className={s.indicatorList}>{settings.map(row)}</div>
          </section>
        )
      })}
    </div>
  )
}

/**
 * On and off in the panel's words wherever Codex offers them - a switch whose state is not known. Anything
 * else as Codex spells it: those are its own names for things.
 */
const optionWords = (t: Dict, option: string): string => {
  if (option === 'true') return t.codexConfig.on
  if (option === 'false') return t.codexConfig.off
  return option
}

/**
 * The project's own block: whether it has Codex settings of its own, whether Codex reads them, and the
 * button that changes that. Names only, never values - what a project sets may include a workspace id.
 */
const ProjectBlock = ({
  layer,
  busy,
  saving,
  failure,
  onTrust,
}: {
  layer: CodexProjectLayer
  busy: boolean
  saving: boolean
  failure: string
  onTrust: (trusted: boolean) => void
}) => {
  const t = useT()
  const words = t.codexConfig.project

  return (
    <section className={s.configGroup}>
      <div className={s.screenLabel}>{words.title}</div>
      <div className={s.configItem}>
        <div className={s.configCard}>
          <span className={s.switchText}>
            <span className={s.switchLabel}>
              {!layer.present ? words.none : layer.trusted ? words.trusted : words.untrusted}
            </span>
            <span className={s.switchHint}>
              {saving
                ? t.codexConfig.saving
                : layer.present && layer.sets.length > 0
                  ? words.sets(layer.sets.join(', '))
                  : layer.present
                    ? ''
                    : words.noneHint}
            </span>
          </span>
          {layer.present || layer.trusted ? (
            <button
              type="button"
              className={`${s.button} ${layer.trusted ? '' : s.buttonPrimary}`}
              disabled={busy}
              onClick={() => onTrust(!layer.trusted)}
            >
              {layer.trusted ? words.untrust : words.trust}
            </button>
          ) : null}
        </div>
        {failure ? <div className={`${s.message} ${s.messageBad}`}>{failure}</div> : null}
      </div>
    </section>
  )
}

/**
 * A setting that takes any value - a model's name, a service tier, a number of tokens. Its own component for the reason
 * OwnAnswer is one: the keys the embedded browser does not give a plain field come from a hook (see
 * useFieldHistory), and a hook cannot be called inside the list's loop.
 */
const FreeValue = ({
  value,
  disabled,
  label,
  onSave,
}: {
  value: string
  disabled: boolean
  label: string
  onSave: (value: string) => void
}) => {
  const t = useT()
  const [draft, setDraft] = useState(value)
  const field = useFieldHistory(draft, setDraft)

  // What Codex stored is the new ground: a field still holding what was typed would offer to save it again.
  useEffect(() => setDraft(value), [value])
  const changed = draft.trim() !== '' && draft.trim() !== value

  return (
    <div className={s.inputRow}>
      <input
        className={s.input}
        value={draft}
        aria-label={label}
        disabled={disabled}
        spellCheck={false}
        onChange={field.onChange}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) return
          if (event.key === 'Enter' && changed) {
            event.preventDefault()
            onSave(draft.trim())
            return
          }
          field.onKeyDown(event)
        }}
      />
      <button
        type="button"
        className={`${s.button} ${s.buttonPrimary}`}
        disabled={disabled || !changed}
        onClick={() => onSave(draft.trim())}
      >
        {t.codexConfig.save}
      </button>
    </div>
  )
}
