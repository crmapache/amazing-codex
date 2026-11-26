import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { SERVICE_VERSION, type Config } from './config.js'

/**
 * What is left of the service that used to hand the panel's feedback to the author's Telegram: a polite
 * refusal, for the copies of the plugin that still have the form.
 *
 * The form is gone from the plugin. It was a box anybody could type into, it filled up faster than one
 * person could read it, and the plugin already costs that person a large share of a Claude subscription
 * and of their time - so the screen now points to the two ways a change still gets in, a tip on Ko-fi with
 * the wish written in it or a pull request. But a plugin is updated when its owner gets round to it, and
 * until then the old form keeps posting here. Switched off, this address would answer every one of those
 * people with a connection error, which reads as "the plugin is broken, try again". Answered, it tells
 * them the same thing the new screen says, in the form's own red line.
 *
 * Nothing is forwarded and nothing is kept. The body is read only so as to be thrown away - see [drain].
 */

/**
 * The words the old form shows. A 400 is the one answer whose body it prints as it is (FeedbackSender.kt,
 * in every version that has the form: `400 -> response.body().take(160)`), so this has to stay a single
 * line under 160 characters - past that the form cuts it mid-word.
 */
export const CLOSED_TEXT =
  "Messages here are closed. Want a change? Tip on ko-fi.com/mzpizote with what you'd like and I'll build it, or open a pull request on GitHub."

export interface Service {
  listen: (port: number) => Promise<number>
  close: () => void
}

export const createService = (config: Config, log: (line: string) => void): Service => {
  const http = createServer((request: IncomingMessage, response: ServerResponse) => {
    const path = (request.url ?? '/').split('?')[0] ?? '/'

    if (path === '/healthz') {
      reply(response, 200, 'ok')
      return
    }

    if (path === '/v1/info') {
      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ serviceVersion: SERVICE_VERSION, accepting: false }))
      return
    }

    if (path !== '/v1/feedback') {
      reply(response, 404, 'not found')
      return
    }

    if (request.method !== 'POST') {
      reply(response, 405, 'post it')
      return
    }

    // The secret is checked before the body is touched, as it always was: a scanner should cost this
    // service a header, not twenty megabytes of bandwidth.
    if (config.key && request.headers['x-acc-key'] !== config.key) {
      reply(response, 403, 'not for you')
      return
    }

    drain(request, config.maxBodyBytes)
      .then((bytes) => {
        if (bytes === null) return
        // A size, never a word: the whole point of a service that forgets is that its log has nothing to
        // forget either.
        log(`turned one away: ${bytes} B, nothing forwarded`)
        reply(response, 400, CLOSED_TEXT)
      })
      .catch((error: unknown) => {
        log(`a request failed: ${(error as Error).message}`)
        if (!response.headersSent) reply(response, 500, 'something went wrong here')
      })
  })

  return {
    listen: (port: number) =>
      new Promise((resolve) => {
        http.listen(port, () => {
          const taken = http.address()
          resolve(typeof taken === 'object' && taken ? taken.port : port)
        })
      }),
    close: () => void (http as Server).close(),
  }
}

/**
 * Read the body to its end and keep none of it; resolve with how many bytes came, or null when the sender
 * went away or sent more than [ceiling].
 *
 * Read rather than ignored, and that is the reason this exists at all. The old plugin writes its whole
 * request - attachments included, up to twenty megabytes - before it looks for an answer. Answer early
 * and close, and it is still writing into a socket that is gone: it gets a broken pipe instead of the
 * answer, and shows "no answer from the feedback service" - the very message this service is kept up to
 * replace. Past the ceiling the request is dropped: nothing the old form can send is that big, so whatever
 * is has not come from it.
 */
export const drain = (request: IncomingMessage, ceiling: number): Promise<number | null> =>
  new Promise((resolve) => {
    let held = 0
    let over = false

    request.on('data', (chunk: Buffer) => {
      if (over) return
      held += chunk.length
      if (held > ceiling) {
        over = true
        resolve(null)
        request.destroy()
      }
    })

    request.on('end', () => resolve(over ? null : held))
    request.on('error', () => resolve(null))
    request.on('aborted', () => resolve(null))
  })

const reply = (response: ServerResponse, status: number, text: string): void => {
  response.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' })
  response.end(text)
}
