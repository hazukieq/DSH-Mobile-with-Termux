/**
 * dsh-mobile-ui — host (Node) half.
 *
 * The chrome itself is browser-side (`./client`), but the **rollback switch must
 * live on the host**, not in the browser: the App's WebView has no address bar,
 * so a flag kept in `localStorage` cannot rescue a page the skin has broken.
 * The host therefore owns one flag file and serves three tiny routes:
 *
 *   GET /__mobile-ui/state     → { disabled }   (consulted on boot)
 *   GET /__mobile-ui/rollback  → write the flag (stock UI afterwards)
 *   GET /__mobile-ui/restore   → remove the flag (skin back)
 *
 * Every client of this engine sees the same flag, so rolling back from the Edge
 * preview also rolls the App back. The engine binds loopback only and these
 * routes toggle nothing but a UI preference, so no further authorization is
 * needed — they must never grow into anything stateful.
 *
 * @module dsh-mobile-ui
 */

import { readFileSync, writeFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'

/** Cordis plugin name (matches the loader entry id in `cordis.patch.yml`). */
export const name = 'dsh-mobile-ui'

/** The web server service provides `register()` for the three routes. */
export const inject = ['webServer']

/** Flag file inside the Harness home; its presence means "stay stock". */
const FLAG_FILE = '.dsh-mobile-ui-off'

/** Route paths served by this plugin. */
const STATE_PATH = '/__mobile-ui/state'
const ROLLBACK_PATH = '/__mobile-ui/rollback'
const RESTORE_PATH = '/__mobile-ui/restore'

/** Absolute path of the rollback flag. */
function flagPath() {
  const home = process.env.DSH_HOME || process.env.HOME || '.'
  return join(home, FLAG_FILE)
}

/** Whether the flag file exists right now. */
function isDisabled() {
  try {
    readFileSync(flagPath())
    return true
  } catch {
    return false
  }
}

/** Write a tiny JSON response. */
function reply(res, body, status = 200) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  })
  res.end(JSON.stringify(body))
}

/** Accept only GET; everything else gets a plain 405. */
function requireGet(req, res) {
  if (req.method === 'GET') return true
  res.writeHead(405, { allow: 'GET', 'cache-control': 'no-store' })
  res.end()
  return false
}

/**
 * Host plugin body: register the three rollback routes.
 * @param {import('@deepseek-ai/cordis').Context} ctx - host context
 */
export function apply(ctx) {
  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'exact',
        path: STATE_PATH,
        handler: async (req, res) => {
          if (!requireGet(req, res)) return
          reply(res, { disabled: isDisabled() })
        }
      }),
    `dsh-mobile-ui: GET ${STATE_PATH}`
  )

  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'exact',
        path: ROLLBACK_PATH,
        handler: async (req, res) => {
          if (!requireGet(req, res)) return
          try {
            writeFileSync(flagPath(), `${new Date().toISOString()}\n`)
            reply(res, { ok: true, disabled: true })
          } catch (error) {
            reply(res, { ok: false, disabled: isDisabled(), error: String((error && error.message) || error) }, 500)
          }
        }
      }),
    `dsh-mobile-ui: GET ${ROLLBACK_PATH}`
  )

  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'exact',
        path: RESTORE_PATH,
        handler: async (req, res) => {
          if (!requireGet(req, res)) return
          try {
            rmSync(flagPath(), { force: true })
            reply(res, { ok: true, disabled: false })
          } catch (error) {
            reply(res, { ok: false, disabled: isDisabled(), error: String((error && error.message) || error) }, 500)
          }
        }
      }),
    `dsh-mobile-ui: GET ${RESTORE_PATH}`
  )

  ctx.logger?.debug?.('dsh-mobile-ui: rollback routes registered')
}

export default apply
