import { readConfig, SERVICE_VERSION } from './config.js'
import { createService } from './server.js'

/**
 * Starting the service. Everything it does lives in server.ts; this file only decides that it should
 * begin, so that a test can raise one of its own without one starting on import.
 */

const config = readConfig()

const log = (line: string): void => {
  if (config.logLevel === 'silent') return
  // Only ever this shape: what happened, a size, a time. Never a body.
  process.stdout.write(`${new Date().toISOString()} ${line}\n`)
}

const port = await createService(config, log).listen(config.port)

if (!config.key) log('no FEEDBACK_KEY set: every caller will be answered')

log(`feedback service ${SERVICE_VERSION} listening on ${port} - turning old feedback forms away`)
