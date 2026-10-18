import { Fragment, memo, useMemo } from 'react'
import { withoutShellText } from '../feed/bash'
import { tokensOf } from '../feed/tokens'
import { pastedOrdinals } from '../imagePreview'
import type { PreviewPlace, QueuedMessage } from '../protocol'
import { ChipView } from './items/UserCard'
import f from './feed.module.css'

/**
 * A queued message as its row draws it: the words, and the attachments as the chips they were typed as.
 *
 * The row used to draw the text the agent will read, where an attachment is `@/Users/…/pasted-123.png`
 * or `[Image #3]` in the middle of the sentence - a path ate the row's whole width, and a picture was a
 * caption nobody could see behind. Now it is the same chips as in the feed (see ChipView): a file is its
 * name and opens on a click, a picture shows itself under the pointer. Its bytes are not in the row - the
 * queue keeps them for sending, and the preview asks for them by the message (see PreviewPlace `queued`).
 *
 * Shared by the desk's queue (see Queue) and the phone's (see mobile/screens/Composer), each in a box of
 * its own - [className]. [sessionId] is what lets a picture be asked for; the phone has no pointer to
 * hover with and gives none, and its chips are names and nothing more.
 *
 * The quotes come first, as they stood first in the text: "fix this" says nothing without the paragraph it
 * is about, and two such messages are told apart by nothing else. Each is cut to a line of its own (see
 * .rowQuote), so the words after it still show.
 *
 * A message that came without its pieces - from an older IDE - is drawn as its text, as it always was.
 */
export const QueueLine = memo(
  ({ item, sessionId, className }: { item: QueuedMessage; sessionId?: string; className: string }) => {
    const tokens = useMemo(() => tokensOf(item.tokens), [item.tokens])
    // Once per message rather than per render: a new place each time would wake every memoised chip.
    const places = useMemo(
      () =>
        (tokens ? pastedOrdinals(tokens) : []).map((image): PreviewPlace | null =>
          image >= 0 && sessionId ? { kind: 'queued', sessionId, id: item.id, image } : null,
        ),
      [tokens, sessionId, item.id],
    )

    if (!tokens) return <span className={className}>{withoutShellText(item.text)}</span>

    return (
      <span className={className}>
        {item.quotes?.map((quote, index) => (
          <span key={`q${index}`} className={f.rowQuote}>
            {quote}
          </span>
        ))}
        {tokens.map((token, index) =>
          token.kind === 'text' ? (
            <Fragment key={index}>{token.value}</Fragment>
          ) : (
            <ChipView key={index} chip={token.chip} elsewhere={places[index]} />
          ),
        )}
      </span>
    )
  },
)
