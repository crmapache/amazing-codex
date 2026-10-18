import type { Dict } from '../i18n/en'
import { SwitchScreen } from './SwitchScreen'

/**
 * Whether a message sent from the panel carries what the editor shows - the open file, and the lines
 * selected in it (see EditorContext on the plugin's side).
 *
 * One switch and a note. The note answers the two questions this raises: whether a phone's message carries
 * it too (it does not - there is no editor beside a phone), and how to send a single message without it
 * while leaving the switch alone (the chip in the field).
 */
export const ShareEditor = ({ t, on, onToggle }: { t: Dict; on: boolean; onToggle: (on: boolean) => void }) => (
  <SwitchScreen words={t.shareEditor} on={on} onToggle={onToggle} />
)
