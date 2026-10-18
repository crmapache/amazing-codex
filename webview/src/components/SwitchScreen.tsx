import s from './sideMenu.module.css'

/** The words of a one-switch screen, as each such screen's block in the dictionaries holds them. */
export interface SwitchWords {
  label: string
  hint: string
  note: string
}

/**
 * A settings screen that is one switch and a note under it - the tabs on start, the editor in messages,
 * the spell check. One markup for all of them: copies of it would part ways at the first change to any.
 */
export const SwitchScreen = ({ words, on, onToggle }: { words: SwitchWords; on: boolean; onToggle: (on: boolean) => void }) => (
  <div className={s.screen}>
    <button type="button" className={s.switchRow} onClick={() => onToggle(!on)} aria-pressed={on}>
      <span className={s.switchText}>
        <span className={s.switchLabel}>{words.label}</span>
        <span className={s.switchHint}>{words.hint}</span>
      </span>
      <span className={`${s.switchTrack} ${on ? s.switchTrackOn : ''}`}>
        <span className={`${s.switchKnob} ${on ? s.switchKnobOn : ''}`} />
      </span>
    </button>

    <span className={s.screenNote}>{words.note}</span>
  </div>
)
