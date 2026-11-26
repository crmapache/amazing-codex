import type { PanelState } from './panelState'

/**
 * The CLI's guess at the next message (see PanelState.suggestion) - when it is taken, when it goes, and when
 * it stands in the field. Apart from the reducer because two of these are asked outside it as well: the field
 * asks whether the gray text is there, App whether a keystroke is news.
 */

/**
 * A turn starts or the conversation moves on: no guess, and the next turn's guess is welcome again. The same
 * object when there was nothing to clear, so that a state nothing happened to is not rebuilt.
 */
export const withoutSuggestion = (state: PanelState): PanelState =>
  state.suggestion === undefined && state.suggestionDeclined === undefined
    ? state
    : { ...state, suggestion: undefined, suggestionDeclined: undefined }

/**
 * Whether a keystroke in the field changes anything about the guess - asked by App before it says so, because
 * every word said to the panels rebuilds them all, and this would be every keystroke.
 *
 * Once per turn: the first keystroke after the turn has ended ends that turn's guess, whether the guess has
 * arrived by then or is still on its way - typed and wiped before it came, the field is as touched as one typed
 * over the gray text. While the turn runs it is not news: what is typed then is the next message, and the guess
 * that comes after the turn is still worth showing.
 */
export const editDeclines = (state: PanelState): boolean =>
  state.status !== 'running' && (state.suggestion !== undefined || state.suggestionDeclined !== true)

/** The person touched the field - see editDeclines. */
export const declineSuggestion = (state: PanelState): PanelState =>
  editDeclines(state) ? { ...state, suggestion: undefined, suggestionDeclined: true } : state

/**
 * The guess the CLI has just sent, if it is still wanted. A replay never holds one - the CLI does not write it
 * down. One arriving while a turn runs is late: a queued message has already started the next turn, and the
 * guess was about the one before. And one arriving after the person touched the field is not wanted either.
 */
export const takeSuggestion = (state: PanelState, said: unknown, replay: boolean): PanelState => {
  const suggestion = typeof said === 'string' ? said.trim() : ''
  if (replay || suggestion === '' || state.status === 'running' || state.suggestionDeclined === true) return state

  return { ...state, suggestion }
}

/**
 * The guess as the field shows it - and so as Tab takes it: only what can be seen can be put into a message.
 * An empty field of a conversation, and not while words are being dictated: the dictated phrase is drawn where
 * the gray text stood (see .field[data-voice] in the styles), and a guess nobody can see would be mixed into
 * the dictated message by a Tab.
 */
export const shownGuess = (
  suggestion: string | undefined,
  field: { empty: boolean; conversation: boolean; dictating: boolean },
): string => (field.empty && field.conversation && !field.dictating ? (suggestion ?? '') : '')
