/**
 * Everything that can be turned without touching the code - which, now that the service only turns old
 * forms away (see server.ts), is very little.
 */

const number = (name: string, fallback: number): number => {
  const raw = process.env[name]
  if (!raw) return fallback

  const parsed = Number(raw)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

export interface Config {
  port: number
  /**
   * The most that is read and thrown away before the request is dropped. A little over what the old form
   * could send (ten files, twenty megabytes, plus its report and multipart's own overhead): anything
   * bigger has not come from it.
   */
  maxBodyBytes: number
  /**
   * The shared secret the plugin sends. Empty means "do not check" - which is right for someone running
   * this locally and wrong for anything reachable from the internet.
   */
  key: string
  logLevel: 'silent' | 'info'
}

export const readConfig = (): Config => ({
  port: number('PORT', 4451),
  maxBodyBytes: number('FEEDBACK_MAX_BODY_BYTES', 24 * 1024 * 1024),
  key: process.env.FEEDBACK_KEY ?? '',
  logLevel: process.env.FEEDBACK_LOG_LEVEL === 'silent' ? 'silent' : 'info',
})

export const SERVICE_VERSION = '0.2.0'
