import m from '../mobile.module.css'

interface EmptyPlateProps {
  /** What is not here, as one line. */
  title: string
  /** What would be here, or how it gets here - the second line the desk's empty shelf has too. */
  note?: string
  /**
   * For a screen whose list runs to the glass (`.list`, the agent's screen), where the cards stand inset of
   * their own (see .project) - the plate stands in with them rather than touching the edges.
   */
  inset?: boolean
}

/**
 * An empty section drawn as the card that is missing from it, rather than as a line of prose.
 *
 * Under a section's heading a bare sentence, indented, read as the heading's stray caption - "IN EVERY PROJECT"
 * and a grey line floating under it - while every full section on the same screen is a card. A dashed plate the
 * size of a card says what belongs there and that there is none, the way the desk draws an empty shelf (see
 * .emptyShelf in scenarios.module.css). "Loading…" stays plain text: it is a moment, not a state.
 */
export const EmptyPlate = ({ title, note, inset = false }: EmptyPlateProps) => (
  <div className={`${m.emptyPlate} ${inset ? m.emptyPlateInset : ''}`}>
    <p className={m.emptyPlateTitle}>{title}</p>
    {note ? <p className={m.emptyPlateNote}>{note}</p> : null}
  </div>
)
