# Accessibility (a11y) scope: which endpoints need the service, and which never do

> **One rule.** An endpoint needs the accessibility service only if it does at least one of:
> (a) read **another app's** UI node tree or screen pixels;
> (b) inject gestures/keys/text into **another app**;
> (c) consume accessibility events (nodes, windows, touch interference, notifications).
> Everything else — launching an app, hand-off, sharing, media playback, posting notifications,
> TTS, file access, engine/PTY/plugin control, screenshotting **our own** window — is ordinary
> Android API and must keep working with the service switched off.

Chinese version: [A11Y-SCOPE.md](A11Y-SCOPE.md).

## 1. Why this document exists (the v2.5.2 incident)

v2.5.2 shipped a gate-classification bug: `/launch`, `/launch-intent` and `/open` (and, in the old
set, `/app-screenshot`) were listed as "requires accessibility". With the service off, "open
WeChat / open Alipay / open the browser" all answered `503 a11y_off` — yet `startActivity`,
`Intent` and PixelCopy have nothing to do with accessibility. **It was our classification error,
not an Android limitation.**

How v2.5.3 fixes it:

- the gate set is **derived from a per-path reason table** (`A11Y_DEPENDENT_REASONS` in
  `AgentBridge.kt`): to gate a path you must also write why it needs the service — no silent
  additions;
- every handler was re-read in code and re-judged by mechanism, not by endpoint name, file name or
  category;
- this document is the human-readable copy of that classification; **the code is the single source
  of truth** — if they disagree, fix this page to match the code.

## 2. Endpoints that need the service (hard gate, 28 paths)

When the service is unavailable these return the structured 503 from §4, emitted by the gate at the
top of `route()`. Every handler in the table genuinely reads
`DshAccessibilityService.instance` to perform its main function.

| Group | Endpoints | Why it needs the service |
|---|---|---|
| Read the node tree | `GET /screen`, `GET /screen-changed`, `GET /scene`, `GET /diff` | node trees and window-content events only exist for an accessibility service |
| Read the node tree | `POST /wait-text`, `POST /wait`, `POST /idle` | poll the node tree or wait for accessibility events to settle |
| Read tree + tap | `POST /pick` | rank on-screen accessibility nodes, optionally tap one |
| Inject gestures/keys/text | `POST /tap`, `POST /tap-id`, `POST /gesture`, `POST /key`, `POST /input` | gestures, global keys and text injection all go through the service |
| Inject scrolling | `POST /scroll-find`, `POST /scroll-center`, `POST /scroll-edge` | scroll while reading the node tree |
| Screen pixels | `GET /screenshot`, `GET /frame-diff`, `POST /frame-wait` | the screenshot comes from `AccessibilityService.takeScreenshot`, not MediaProjection; `/screenshot` has **no** privileged fallback |
| Screen pixels | `POST /vision/crop`, `POST /vision/track`, `POST /vision/tap`, `POST /vision/drag` | screenshot through the service first, then visual analysis/tap/drag |
| Composite drivers | `POST /batch`, `POST /act`, `POST /loop/run` | they read the tree / tap / screenshot internally (see the `/batch` caveat in §10) |
| Accessibility event cache | `GET /interference`, `GET /notif` | touch-interference counters and notification events come from accessibility callbacks (`/notif` is **not** a NotificationListenerService) |

## 3. Endpoints that never need the service (must not be gated)

With the service off they must still answer 200. Do **not** ask the user to enable accessibility for
them, and do not stall a task because of them.

| Group | Endpoints |
|---|---|
| Launch / hand-off / system pages | `POST /launch`, `POST /launch-intent`, `POST /open`, `POST /activity`, `POST /a11y/enable` (only opens the system accessibility settings page) |
| Wait for our own app | `POST /wait-app` when the target package is this app |
| Status / settings / engine | `GET /status`, `GET /settings`, `POST /settings`, `GET /apps`, `GET /process`, `GET /diag`, `GET /plugin/list`, `POST /plugin/install`, `POST /engine/restart`, `POST /notify`, `POST /say`, `GET /say`, `POST /overlay`, `GET /local/status`, `POST /local/ensure`, `GET /daemon/status`, `POST /daemon/stop` |
| Files / share / media | `POST /open/file`, `POST /open/folder`, `POST /open/share`, `POST /share/text`, `POST /media/play`, `POST /media/pause`, `POST /media/resume`, `POST /media/stop`, `GET /media/status`, `POST /export` |
| Our own window | `GET /app-screenshot` (PixelCopy of this process's window; needs no permission) |
| PTY / runtime / WebView / vision registry | `POST /pty/open`, `POST /pty/write`, `POST /pty/resize`, `POST /pty/kill`, `POST /pty/close`, `GET /pty/read`, `GET /runtime/status`, `GET /runtime/feed`, `POST /runtime/pull`, `POST /runtime/stage`, `POST /runtime/apply`, `POST /runtime/rollback`, `POST /webview/open`, `POST /webview/eval`, `POST /webview/action`, `GET /webview/status`, `GET /vision/list` |
| Memory bookkeeping (no screen read) | `GET /memory/list`, `GET /memory/stats`, `POST /memory/confirm`, `POST /memory/clear` |

"Never needs it" also covers the plain-Android behaviour behind these endpoints: `startActivity`,
`Intent`/App-Link dispatch, `ACTION_SEND` share sheets, `MediaPlayer`, `NotificationManager`,
`TextToSpeech`, SAF/DocumentsProvider file access, and reading/writing the app's own preferences.

## 4. What "service off" looks like

`GET /status` reports two independent states:

- `permissions.accessibility` — granted in system settings;
- `permissions.accessibility_connected` — actually bound to the app process.

Only both true means actions work. A hard-gated endpoint answers with an actionable 503:

```json
{"ok":false,"error":"a11y_off","code":"a11y_off","endpoint":"/screen",
 "because":"reads the on-screen node tree",
 "reason":"...","message":"...","how_to_fix":"...",
 "accessibility_enabled":false,"accessibility_connected":false,
 "accessibility_listed_but_disabled":false,"retryable":false,
 "action":"open_a11y_settings","actionHint":"POST /a11y/enable opens the system accessibility page"}
```

`error`/`code` has exactly two values:

- `a11y_connecting` — granted but the bind has not finished (`retryable:true`; wait 1–2 s, retry
  once);
- `a11y_off` — never granted, or disabled by Android (common after force-stop/update;
  `accessibility_listed_but_disabled:true`).

## 5. Conditionally dependent endpoints (deliberately outside the hard gate)

Seven endpoints below (six behaviours - `/memory/lookup` and `/memory/store` share one) each have a
path that succeeds without the service, so a static gate would kill legitimate use. The handler
decides and, when it really needs the service and cannot get it, returns an error that states the
cause and how to fix it.

| Endpoint | When it needs the service | Behaviour with the service off |
|---|---|---|
| `GET /ocr` | the default path OCRs an accessibility screenshot | `engine=ppocr6` can use privileged `screencap` (Shizuku/root); the `ensure`/`download`/`models` branches need no a11y at all |
| `POST /wait-app` | waiting for **another** app to come to the foreground | waiting for this app uses the Activity lifecycle and needs no a11y; a third-party package gets `503 a11y_unavailable` |
| `POST /memory/lookup`, `POST /memory/store` | computing a scene fingerprint from the current screen | when the body carries `dump` (caller supplies the node tree) nothing is read from the screen; the package name in the fingerprint may be empty |
| `POST /overlay` | `hide=true` also dismisses the a11y overlay window | updating/showing progress does not depend on it; `StatusOverlay.hide(svc)` accepts null |
| `POST /activity` | only affects the `bal_exempt.accessibility` hint in the receipt | the start still goes through `startActivity` and answers 200 |
| `POST /open` | only used to read the foreground package before/after the jump, to verify the switch | still 200 with the service off; the receipt carries a `hint` that it was not verified |

## 6. What to do when the service is off (agent behaviour)

1. **Keep doing the plain part.** Plain capabilities are unaffected; never let a task stall because
   accessibility is off.
2. Probe `GET /status` **once** per UI task; do not probe before every action.
3. `retryable:true` → wait ~2 s, retry once; if it is still false, stop retrying.
4. Otherwise give the user the entry point (`POST /a11y/enable` or `POST /activity
   {"name":"accessibility-settings"}`) and let them decide — apps cannot enable accessibility
   themselves.
5. **Prefer routing around it**: `/launch` + `/open` for hand-off, `POST /wait-app <own package>`
   to come back, `GET /app-screenshot` for our own window, `POST /share/text` for forwarding,
   `POST /notify` / `POST /say` for output.
6. Never loop on a non-retryable a11y error, and never describe a plain capability as "blocked by
   Android".

## 7. Code-side invariants

1. `A11Y_DEPENDENT_REASONS` (`AgentBridge.kt`) **is** the gate set: the gate iterates its keys.
   Adding a path requires a human-readable reason in the same table — if you cannot write the
   reason, the classification is not thought through.
2. `A11Y_INDEPENDENT_ACTIONS` is the counter-example set (the v2.5.2 incident endpoints and their
   kind). At startup, an overlap with the gate table logs
   `a11y path classification conflict`; fix the classification per this document.
3. `ACTING_PATHS` only drives the "the AI is operating the device" notification counter. Presence
   there says **nothing** about needing accessibility, and vice versa.
4. Conditionally dependent endpoints (§5) stay outside the gate; on failure their handler must
   still provide `because`/`how_to_fix`.
5. New route: classify per this document first, then write the handler. Add it to the gate table
   only if the handler **unconditionally** needs `DshAccessibilityService.instance`; anything that
   only dispatches an `Intent`/`startActivity`, reads our own window, or touches files/settings must
   not be gated.
6. Never gate an endpoint because "it looks like automation". Launching, sharing, media,
   notifications, files, PTY and engine control are all automation — and none of them needs
   accessibility.

## 8. Maintenance flow (required for any endpoint add/change)

1. Read the handler (and the helpers it calls — they may live in `DshAccessibilityService.kt`,
   `ScreenOcr.kt`, `Framer.kt`, `VisionTracker.kt`, `WebAutomation.kt`, `ShotFallback.kt`) and
   confirm whether it really uses `svc`.
2. Dependent → add a human-readable reason to `A11Y_DEPENDENT_REASONS`; independent/conditional →
   keep it out of the table.
3. Update §2/§3/§5 of this document in the same change.
4. Self-check:
   - `python3 scripts/check-i18n.py` (no Chinese string literals in Kotlin);
   - `python3 scripts/check-doc-endpoints.py` (every endpoint named in docs exists in `route()`);
   - `grep -n "A11Y_DEPENDENT_REASONS" app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt`
     to confirm the set is still derived from the reason table and there is no second hard-coded
     list.
5. If an endpoint moves from "unconditionally dependent" to "has a fallback" (e.g. `/ocr`'s
   privileged screenshot), you must **both** remove it from the reason table and update this page —
   otherwise the reverse incident comes back: a working capability answering 503.

## 9. Device verification checklist (run once with a11y on, once off)

**Accessibility OFF** (Settings → Accessibility, turn off `DSH Mobile Helper (optional)`; `/status`
should report `accessibility=false, accessibility_connected=false`):

- must be 200 (key regressions): `POST /launch`, `POST /launch-intent`, `POST /open`,
  `POST /activity`, `GET /app-screenshot`, `POST /notify`, `POST /say`, `GET /status` (plus
  `POST /settings`), `POST /open/file`, `POST /share/text`, `GET /process`, `GET /diag`,
  `POST /overlay`;
- must be 200 (conditional): `POST /wait-app {"package":"com.termux"}`,
  `GET /ocr?engine=ppocr6&ensure=1`, `POST /memory/lookup` (body with `dump`);
- must be 503 with complete `because`/`how_to_fix` and the correct `endpoint`: `GET /screen`,
  `GET /scene`, `GET /screenshot`, `GET /notif`, `GET /interference`, `POST /tap`, `POST /act`,
  `POST /batch`, `POST /key`, `POST /gesture`, `POST /wait-text`, `GET /diff`, `POST /loop/run`,
  `POST /vision/tap`;
- **none** of the §3 endpoints may appear in that 503 batch.

**Then turn it ON**: all dependent endpoints above answer 200; both `/status` permission fields are
true.
**Then the race**: while a11y is on, disable it in system settings or force-stop the app and
immediately hit a hard-gated endpoint — you must get `a11yGone()`'s actionable 503, not a bare
`{"error":"..."}`.

## 10. Known leftovers / to re-check

1. `POST /batch` currently requires accessibility **unconditionally**: even when the steps are only
   plain actions such as `launch`/`open`, the handler starts with `svc ?: 503`. It is therefore
   honestly a hard dependency today; if it ever becomes "scan the steps, allow when all are plain",
   remove it from the reason table and update this document in the same change.
2. `GET /notif` reads the accessibility notification cache
   (`DshAccessibilityService.recentNotifications()`), **not** a `NotificationListenerService`; if it
   ever switches to NLS, move it to §3 and delete the reason-table entry.
3. `GET /screenshot` has no privileged fallback (`ShotFallback` serves only `/ocr`), so it stays
   hard-gated; adding a Shizuku/root fallback requires re-classifying it.
4. `GET /interference` was missing from the gate before v2.5.3 (the handler answered 503 with vague
   text); it is now in the reason table.
5. The service's real display name is `accessibility_label` in
   `app/src/main/res/values/strings.xml` (`DSH Mobile Helper (optional)`, Chinese
   `DSH Mobile 辅助（可选）`). `DSH Screen Helper` / `DSH Screen Control` in older code and docs are
   historical names, unified to the real label in this version.

## 11. See also

- [android-agent-routes.md](android-agent-routes.md) — the full route table;
- [ACCESSIBILITY.md](ACCESSIBILITY.md) / [ACCESSIBILITY.en.md](ACCESSIBILITY.en.md) — what the
  accessibility capability can do, and which capabilities do not need it.
