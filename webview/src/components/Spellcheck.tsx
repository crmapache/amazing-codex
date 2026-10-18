import type { Dict } from '../i18n/en'
import { SwitchScreen } from './SwitchScreen'

/**
 * Whether the panel's text fields underline misspelled words (see spellcheck in App.tsx).
 *
 * One switch and a note. Asked for by somebody who writes to Claude in several languages: the browser
 * checks every word against the dictionaries it has, and a message in any other language came up
 * underlined from end to end. The note says who the switch is for, and that a phone is not touched by
 * it - its keyboard checks by its own rules.
 */
export const Spellcheck = ({ t, on, onToggle }: { t: Dict; on: boolean; onToggle: (on: boolean) => void }) => (
  <SwitchScreen words={t.spellcheck} on={on} onToggle={onToggle} />
)
