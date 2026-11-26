import { afterEach, describe, expect, it } from 'vitest'
import { readConfig, type Config } from './config.js'
import { CLOSED_TEXT, createService, type Service } from './server.js'

/**
 * A real service on a real port, answering the requests an old copy of the plugin still makes.
 *
 * What matters is that such a person sees the words rather than an error: the old form prints the body of
 * a 400 and nothing else, and only if the answer arrives after it has finished writing its request.
 */

let service: Service | undefined
const lines: string[] = []

const raise = async (overrides: Partial<Config> = {}): Promise<number> => {
  const config = { ...readConfig(), logLevel: 'silent' as const, key: '', ...overrides }
  service = createService(config, (line) => lines.push(line))
  return service.listen(0)
}

afterEach(() => {
  service?.close()
  service = undefined
  lines.length = 0
})

/** What the old form posts: multipart, its fields, and whatever files were picked. */
const oldForm = (file: Buffer = Buffer.from('a screenshot')): Buffer => {
  const boundary = 'testboundary'
  const field = (name: string, value: string) =>
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`)

  return Buffer.concat([
    field('kind', 'idea'),
    field('text', 'please add a dark mode'),
    field('email', 'someone@example.com'),
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="shot.png"\r\n\r\n`),
    file,
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ])
}

const post = (port: number, body: Buffer, headers: Record<string, string> = {}) =>
  fetch(`http://127.0.0.1:${port}/v1/feedback`, {
    method: 'POST',
    headers: { 'content-type': 'multipart/form-data; boundary=testboundary', ...headers },
    body: new Uint8Array(body),
  })

describe('an old feedback form posting here', () => {
  it('is answered with the words the new screen says, in the one status the form prints', async () => {
    const port = await raise()

    const response = await post(port, oldForm())

    expect(response.status).toBe(400)
    expect(await response.text()).toBe(CLOSED_TEXT)
  })

  it('logs a size and nothing of what was written', async () => {
    const port = await raise()

    await post(port, oldForm())

    expect(lines).toHaveLength(1)
    expect(lines[0]).toMatch(/^turned one away: \d+ B/)
    expect(lines.join('\n')).not.toContain('dark mode')
    expect(lines.join('\n')).not.toContain('someone@example.com')
  })

  /** The old form writes everything before it reads; answering mid-body would break its pipe instead. */
  it('reads a whole twenty-megabyte request through before answering', async () => {
    const port = await raise()

    const response = await post(port, oldForm(Buffer.alloc(20 * 1024 * 1024, 7)))

    expect(response.status).toBe(400)
    expect(await response.text()).toBe(CLOSED_TEXT)
  })

  it('drops what is bigger than anything the form could send', async () => {
    const port = await raise({ maxBodyBytes: 1024 })

    await expect(post(port, oldForm(Buffer.alloc(64 * 1024, 7))).then((response) => response.text())).rejects.toThrow()
  })

  it('still asks for the shared secret first', async () => {
    const port = await raise({ key: 'secret' })

    expect((await post(port, oldForm(), { 'x-acc-key': 'wrong' })).status).toBe(403)
    expect((await post(port, oldForm(), { 'x-acc-key': 'secret' })).status).toBe(400)
  })
})

describe('the words themselves', () => {
  /** FeedbackSender cuts the body at 160 characters, and the line it sits in is a single line. */
  it('fit the form whole', () => {
    expect(CLOSED_TEXT.length).toBeLessThanOrEqual(160)
    expect(CLOSED_TEXT).not.toMatch(/[\r\n]/)
  })

  it('name both ways in', () => {
    expect(CLOSED_TEXT).toContain('ko-fi.com/mzpizote')
    expect(CLOSED_TEXT).toContain('pull request')
  })
})

describe('the rest of the address', () => {
  it('says it is alive, and that it takes nothing', async () => {
    const port = await raise()

    expect(await (await fetch(`http://127.0.0.1:${port}/healthz`)).text()).toBe('ok')
    expect(await (await fetch(`http://127.0.0.1:${port}/v1/info`)).json()).toMatchObject({ accepting: false })
  })

  it('answers anything else as it always did', async () => {
    const port = await raise()

    expect((await fetch(`http://127.0.0.1:${port}/v1/feedback`)).status).toBe(405)
    expect((await fetch(`http://127.0.0.1:${port}/elsewhere`)).status).toBe(404)
  })
})
