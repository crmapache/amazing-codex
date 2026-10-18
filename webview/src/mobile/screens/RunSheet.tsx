import { useState } from 'react'
import { effortFits, effortOptions, levelsOf, modelOptions, modeLabel, sameModel } from '../../catalog'
import type { ModelInfo } from '../../protocol'
import { fastModeSub, fastTierNote, modelHasFastMode, type FastModeShown } from '../../feed/fastMode'
import { Sheet } from './Sheet'
import m from '../mobile.module.css'
import { useT } from '../../i18n'

interface RunSheetProps {
  /** The catalogue as the CLI on that machine reports it, or null until it has said (see catalog.ts). */
  models: ModelInfo[] | null
  /** And the models added by hand at that desk - they stand in this list too (see modelOptions). */
  customModels: string[]
  /** What this conversation runs on right now. */
  model: string
  effort: string
  mode: string
  /** Fast mode for this conversation - what is asked and what Codex said of it, read together (see feed/fastMode.ts). */
  fast: FastModeShown
  onApply: (change: { model?: string; effort?: string; fast?: boolean }) => void
  onClose: () => void
}

/**
 * How the turn on screen runs: the model, the effort, and the mode it cannot change.
 *
 * The first two are here because they are about what a turn costs and how good it is, and that is a
 * decision somebody away from the desk genuinely has: finding a refactor grinding along on Haiku, or a
 * one-line fix burning a subscription on `max`, is finding the one thing about the run that could be
 * fixed from here in a tap. Neither widens what the agent may touch, which is what every other refusal
 * on this channel guards (see RemoteCommands).
 *
 * The mode is shown and locked, and the sentence under it says why rather than leaving a dead control:
 * it decides whether the agent asks before it writes, and this conversation may have somebody sitting in
 * front of it. A conversation started FROM the phone begins in any mode - that choice is on the screen
 * that starts one.
 *
 * Nothing here writes the machine's settings, unlike the same choice made at the desk: what the next tab
 * opened at that keyboard starts on is not a decision to take from a sofa (see SessionCommands, which
 * passes `remember = local`).
 *
 * Fast mode sits with them for the same reason - it is about what a turn costs and how soon it answers -
 * and only on a model that has it, or while it is not off (from Codex's own config too), so that it can be
 * turned off again. The switch starts from what is SHOWN: turned off over Codex's config, it asks off for this
 * conversation.
 */
export const RunSheet = ({ models, customModels, model, effort, mode, fast, onApply, onClose }: RunSheetProps) => {
  const t = useT()
  const [pickedModel, setPickedModel] = useState(model)
  const [pickedEffort, setPickedEffort] = useState(effort)
  const shownOn = fast.kind !== 'off'
  const [pickedFast, setPickedFast] = useState(shownOn)
  const fastOffered = shownOn || modelHasFastMode(models, pickedModel)
  // What there is to say beyond the price: an ask still waiting, a refusal, or that Codex's config put it there.
  const fastTold = pickedFast && fast.kind !== 'off' && (fast.kind !== 'on' || fast.fromSettings === true)
  const tier = fastTierNote(models, pickedModel)

  const changed = !sameModel(pickedModel, model) || pickedEffort !== effort || pickedFast !== shownOn

  // The levels are the picked model's own (see levelsOf); a level it does not have goes back to `auto`,
  // since Codex would run such a turn on some other level without a word.
  const levels = levelsOf(models, pickedModel)
  const pickModel = (next: string) => {
    setPickedModel(next)
    if (!effortFits(levelsOf(models, next), pickedEffort)) setPickedEffort('auto')
  }

  return (
    <Sheet
      title={t.mobile.run.title}
      meta={t.mobile.run.subtitle}
      height="84%"
      onClose={onClose}
      footer={
        <>
          <button type="button" className={m.buttonSecondary} onClick={onClose}>
            {t.common.cancel}
          </button>
          <button
            type="button"
            className={m.buttonPrimary}
            disabled={!changed}
            onClick={() =>
              onApply({
                model: sameModel(pickedModel, model) ? undefined : pickedModel,
                effort: pickedEffort === effort ? undefined : pickedEffort,
                fast: pickedFast === shownOn ? undefined : pickedFast,
              })
            }
          >
            {t.mobile.run.apply}
          </button>
        </>
      }
    >
      <p className={m.sheetLabel}>{t.selectors.model}</p>
      <div className={m.chipWrap}>
        {modelOptions(t, models, customModels).map((option) => (
          <button
            key={option.id}
            type="button"
            className={`${m.pickChip} ${sameModel(option.id, pickedModel) ? m.pickChipOn : ''}`}
            disabled={option.disabled}
            onClick={() => pickModel(option.id)}
          >
            {option.label}
          </button>
        ))}
      </div>

      <p className={m.sheetLabel}>{t.selectors.effort}</p>
      <div className={m.chipWrap}>
        {effortOptions(t, levels).map((option) => (
          <button
            key={option.id}
            type="button"
            className={`${m.pickChip} ${option.id === pickedEffort ? m.pickChipOn : ''}`}
            onClick={() => setPickedEffort(option.id)}
          >
            {option.label}
          </button>
        ))}
      </div>

      {fastOffered ? (
        <>
          <p className={m.sheetLabel}>{t.fast.label}</p>
          <div className={m.chipWrap}>
            {[false, true].map((on) => (
              <button
                key={String(on)}
                type="button"
                className={`${m.pickChip} ${on === pickedFast ? m.pickChipOn : ''}`}
                onClick={() => setPickedFast(on)}
              >
                {on ? t.fast.phone.on : t.fast.phone.off}
              </button>
            ))}
          </div>
          {/* What Codex said of it, while that is more than "on"; otherwise what it is and costs, in Codex's words when it has any. */}
          <p className={m.sheetNote}>
            {fastTold ? fastModeSub(t, fast) : tier ? t.fast.phone.noteTier(tier) : t.fast.phone.note}
          </p>
        </>
      ) : null}

      <div className={m.sheetLabelRow}>
        <span className={m.sheetLabelInline}>{t.selectors.mode}</span>
        <span className={m.lockedTag}>
          <Lock />
          {t.mobile.run.locked}
        </span>
      </div>
      <div className={m.lockedRow}>
        <span className={m.lockedName}>{modeLabel(t, mode)}</span>
        <span className={m.lockedNow}>{t.mobile.run.inForce}</span>
      </div>
      <p className={m.sheetNote}>{t.mobile.run.modeNote}</p>
    </Sheet>
  )
}

const Lock = () => (
  <svg viewBox="0 0 16 16" width="11" height="11" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
    <rect x="3" y="7" width="10" height="7" rx="1.6" />
    <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
  </svg>
)
