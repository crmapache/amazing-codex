import type { Dict } from '../i18n/en'
import { SwitchScreen } from './SwitchScreen'

/**
 * Whether the tabs open when a project closed come back when it opens again - drafts and all (see
 * TabMemory on the plugin's side).
 *
 * One switch and two sentences. The first says what comes back, the second what it costs, and the second
 * is the one people ask about: ten tabs coming back does not mean ten agents starting, and a draft kept
 * on disk is kept on this machine only.
 */
export const RestoreTabs = ({ t, on, onToggle }: { t: Dict; on: boolean; onToggle: (on: boolean) => void }) => (
  <SwitchScreen words={t.restoreTabs} on={on} onToggle={onToggle} />
)
