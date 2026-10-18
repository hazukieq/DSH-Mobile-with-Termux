/**
 * dsh-mobile-ui — host (Node) half.
 *
 * The Android-app-style chrome itself is browser-side (`./client`). Upstream's
 * host half also registered three rollback routes (/__mobile-ui/{state,rollback,
 * restore}) through ctx.webServer; on this engine the loader creates the entry
 * without resolving `inject: ['webServer']`, so apply() threw
 * "cannot get property webServer without inject" at boot.
 *
 * The chrome has its own CSS gate (html[data-dsh-mobile]) and can be disabled by
 * removing the loader entry, so the rollback routes are not required here.
 * Keeping the host half a no-op plugin is enough for the client bundle to load.
 *
 * @module dsh-mobile-ui
 */

/** Cordis plugin name (matches the loader entry id in the overlay). */
const name = 'mobile-ui'

/** No host services are used. */
const inject = []

/** Host-side plugin body: intentionally empty. */
function apply() {}

export { apply, inject, name }
