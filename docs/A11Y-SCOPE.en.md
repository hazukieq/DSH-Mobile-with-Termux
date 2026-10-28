# Accessibility (a11y) scope: which endpoints need it, which never do

> **One rule.** An endpoint needs the accessibility service only if it does one of:
> (a) read **another app's** UI node tree or screen pixels;
> (b) inject gestures / keys / text into **another app**;
> (c) consume accessibility events (nodes / windows / touch interference / notifications).
> Everything else — launching apps, hand-off, sharing, media playback, posting notifications, TTS,
> file access, engine/PTY/plugin control, screenshotting **this app's own** window — is ordinary
> Android API and must keep working with accessibility off.

Chinese version: [A11Y-SCOPE.md](A11Y-SCOPE.md).

## 1. Purpose of this file

The gate set is derived from a per-path reason table (`A11Y_DEPENDENT_REASONS`, see `AgentBridge.kt`):
to join the set, a path must also state **why** it needs accessibility — you cannot add a path without
an explanation. Classification always follows the handler's actual mechanism, never the endpoint
name, file name or category. This document is the human-readable version of that classification;
**code is the single source of truth** — when they disagree, fix this document to match the code.

## 2. Endpoints that need accessibility (hard gate, 28 total)

When the service is unavailable, the gate at the top of `route()` answers these with a structured 503
(see §4); every handler in the table really uses `DshAccessibilityService.instance` for its main job.

| Group | Endpoints | Why |
|---|---|---|
| Node-tree read | `GET /screen`, `GET /screen-changed`, `GET /scene`, `GET /diff`, `POST /wait-text`, `POST /wait`, `POST /idle` | node-tree / window-change events only come from the accessibility service; `/wait-*` polls the tree or waits for events to go quiet |
| Node-tree read + tap | `POST /pick` | ranks on-screen accessibility nodes and optionally taps one |
| Gesture / key / text / scroll injection | `POST /tap`, `POST /tap-id`, `POST /gesture`, `POST /key`, `POST /input`, `POST /scroll-find`, `POST /scroll-center`, `POST /scroll-edge` | gestures, global keys and text injection all go through the service; scrolling must read the tree while scrolling |
| Screen pixels | `GET /screenshot`, `GET /frame-diff`, `POST /frame-wait`, `POST /vision/crop`, `POST /vision/track`, `POST /vision/tap`, `POST /vision/drag` | screenshots come from `AccessibilityService.takeScreenshot` (not MediaProjection, and `/screenshot` has **no** privileged fallback); vision analysis / taps / drags capture an accessibility screenshot first |
| Composites / event cache | `POST /batch`, `POST /act`, `POST /loop/run`, `GET /interference`, `GET /notif` | they read the tree / tap / screenshot internally (see the `/batch` leftover in §9); touch-interference counts and notification events come from accessibility callbacks (`/notif` is **not** NotificationListenerService) |

## 3. Endpoints that never need accessibility (must not enter the gate)

They must keep returning 200 while the service is off. Do **not** send the user to enable
accessibility for them, and do not stall a task because of them.

| Group | Endpoints |
|---|---|
| Launch / hand-off / system pages | `POST /launch`, `POST /launch-intent`, `POST /open`, `POST /activity`, `POST /a11y/enable` (only opens the system accessibility settings page) |
| Waiting for this app | `POST /wait-app` (when the target package is this app) |
| Status / settings / engine | `GET /status`, `GET /settings`, `POST /settings`, `GET /apps`, `GET /process`, `GET /diag`, `GET /plugin/list`, `POST /plugin/install`, `POST /engine/restart`, `POST /notify`, `POST /say`, `GET /say`, `POST /overlay`, `GET /local/status`, `POST /local/ensure`, `GET /daemon/status`, `POST /daemon/stop` |
| Files / sharing / media | `POST /open/file`, `POST /open/folder`, `POST /open/share`, `POST /share/text`, `POST /media/play`, `POST /media/pause`, `POST /media/resume`, `POST /media/stop`, `GET /media/status`, `POST /export` |
| This app's own window | `GET /app-screenshot` (PixelCopy of this process's window, needs no permission) |
| PTY / runtime / vision list | `POST /pty/open`, `POST /pty/write`, `POST /pty/resize`, `POST /pty/kill`, `POST /pty/close`, `GET /pty/read`, `GET /runtime/status`, `GET /runtime/feed`, `POST /runtime/pull`, `POST /runtime/stage`, `POST /runtime/apply`, `POST /runtime/rollback`, `GET /vision/list` |
| Memory bookkeeping (no screen read) | `GET /memory/list`, `GET /memory/stats`, `POST /memory/confirm`, `POST /memory/clear` |

"Not needed" also covers the plain Android behaviour behind those endpoints: `startActivity`,
`Intent`/App Link dispatch, the `ACTION_SEND` share sheet, `MediaPlayer`, `NotificationManager`,
`TextToSpeech`, SAF/DocumentsProvider file access, and reading/writing this app's settings.

## 4. What "service is off" should look like

`GET /status` reports two independent states:

- `permissions.accessibility` — granted in system settings;
- `permissions.accessibility_connected` — actually bound in the process.

Both must be true for actions. The 503 from a hard-gated endpoint is a structured object with an
actionable message (`error`/`code` are only two values: `a11y_connecting` = granted but the bind is
not done, `retryable:true`, retry after 1–2s; `a11y_off` = never granted or disabled by Android,
common after force-stop/update, with `accessibility_listed_but_disabled:true`):

```json
{"ok":false,"error":"a11y_off","code":"a11y_off","endpoint":"/screen","retryable":false,
 "because":"reads the on-screen node tree","reason":"...","message":"...","how_to_fix":"...",
 "accessibility_enabled":false,"accessibility_connected":false,"accessibility_listed_but_disabled":false,
 "action":"open_a11y_settings","actionHint":"POST /a11y/enable opens the system accessibility page"}
```

## 5. Conditionally dependent endpoints (deliberately outside the hard gate; the handler decides)

Seven endpoints (six behaviours — `/memory/lookup` and `/memory/store` share one) have a path that
**succeeds without accessibility**, so a static gate would kill usable cases; the handler must return
an error that **states the reason and how to fix it** when it really needs the service.

| Endpoint | When it needs a11y | Behaviour with the service off |
|---|---|---|
| `GET /ocr` | the default path OCRs an accessibility screenshot | `engine=ppocr6` can use privileged `screencap` (Shizuku/root); the `ensure`/`download`/`models` branches need no a11y at all |
| `POST /wait-app` | waiting for the foreground state of **another** app | for this app it uses the Activity lifecycle; only third-party targets answer 503 `a11y_unavailable` |
| `POST /memory/lookup`, `POST /memory/store` | computing a scene fingerprint from the current screen | when the body carries `dump` (the caller supplies the node tree) nothing is read; the package name in the fingerprint may be empty |
| `POST /overlay` | `hide=true` also dismisses the overlay window | showing/updating the progress bar does not depend on it; `StatusOverlay.hide(svc)` accepts null |
| `POST /activity` | only affects the `bal_exempt.accessibility` hint in the receipt | the start goes through `startActivity` and returns 200 as usual |
| `POST /open` | only reads the foreground package before/after the jump to verify "did it really switch" | returns 200 with the service off, and the receipt carries a `hint` that nothing was verified |

## 6. What to do when the service is off (agent behaviour rules)

1. **Keep doing the ordinary part.** It is unaffected; never stall a task because accessibility is
   off. Probe `GET /status` **once** per UI task, not before every action; on `retryable:true` retry
   once after ~2s, and if it is still false do not loop.
2. Otherwise give the user a way to open the page (`POST /a11y/enable` or
   `POST /activity {"name":"accessibility-settings"}`) and let them decide — an app cannot enable
   accessibility itself.
3. **Prefer routing around it**: `/launch` + `/open` for hand-off, `POST /wait-app <this package>` to
   wait for the return, `GET /app-screenshot` for our own window, `POST /share/text` to forward,
   `POST /notify` / `POST /say` for output.
4. Do not loop on non-retryable accessibility errors, and never describe an ordinary capability as
   "blocked by Android".

## 7. Code-side invariants and maintenance rules

1. `A11Y_DEPENDENT_REASONS` (`AgentBridge.kt`) **is** the gate set: the gate iterates its keys. Any
   added path must carry a human-readable reason in the same table — if you cannot write the reason,
   the classification is not finished.
2. `A11Y_INDEPENDENT_ACTIONS` is the counter-example set: launch/hand-off/share/media/own-window
   endpoints that clearly need no accessibility. If it overlaps the gate at startup, the app logs
   `Log.e "a11y path classification conflict"` — fix per this document.
3. `ACTING_PATHS` only decides the "AI is operating the device" notification counter; membership
   **does not** mean accessibility is needed, and vice versa.
4. Conditional endpoints (§5) stay outside the gate; their handlers must still provide
   `because`/`how_to_fix` on failure.
5. Adding/changing an endpoint: read the handler (and helpers such as `DshAccessibilityService.kt`,
   `ScreenOcr.kt`, `Framer.kt`, `VisionTracker.kt`, `ShotFallback.kt`) to confirm whether it really
   uses the service → classify per this document → write the handler → add a reason to
   `A11Y_DEPENDENT_REASONS` → update §2/§3/§5 here. Only handlers that **unconditionally** need
   `DshAccessibilityService.instance` may join the gate; `Intent`/`startActivity`-only, own-window and
   file/settings paths must never join.
6. Never push an endpoint into the gate because "it looks like automation". Launching, sharing, media,
   notifications, files, PTY and engine control are automation too — none of them needs accessibility.
7. When an endpoint moves from "unconditionally dependent" to "has a fallback" (e.g. `/ocr`'s
   privileged capture), it must be **removed from the reason table and this document updated at the
   same time** — otherwise the reverse problem appears ("it works but gets a 503").
8. Self-check: `python3 scripts/check-i18n.py` (Kotlin string literals must not contain Chinese) and
   `python3 scripts/check-doc-endpoints.py` (every endpoint in the docs must exist in `route()`), plus
   a grep for `A11Y_DEPENDENT_REASONS` to confirm the set is still derived from the table with no
   second hard-coded list.

## 8. On-device verification checklist (run once with a11y off, once with it on)

**Accessibility off** (Settings → Accessibility, turn off `DSH Mobile Helper (optional)`; `/status`
should report `accessibility=false, accessibility_connected=false`):

- should be 200: `POST /launch`, `POST /launch-intent`, `POST /open`, `POST /activity`,
  `GET /app-screenshot`, `POST /notify`, `POST /say`, `GET /status` (and `POST /settings`),
  `POST /open/file`, `POST /share/text`, `GET /process`, `GET /diag`, `POST /overlay`;
- should be 200 (conditional): `POST /wait-app {"package":"com.termux"}`,
  `GET /ocr?engine=ppocr6&ensure=1`, `POST /memory/lookup` (body with `dump`);
- should be 503 with `because`/`how_to_fix` present and a correct `endpoint`: `GET /screen`,
  `GET /scene`, `GET /screenshot`, `GET /notif`, `GET /interference`, `POST /tap`, `POST /act`,
  `POST /batch`, `POST /key`, `POST /gesture`, `POST /wait-text`, `GET /diff`, `POST /loop/run`,
  `POST /vision/tap`; the same batch of 503s must **not** contain any endpoint from §3.

**Then verify with it on**: all the dependent endpoints return 200 and `/status` reports both
permissions true. **Then test one race**: with accessibility on, turn it off in system settings or
force-stop the app, then immediately hit a hard-gated endpoint — it must return `a11yGone()`'s
actionable 503, not a bare `{"error":"..."}`.

## 9. Known leftovers / to re-check

1. `POST /batch` **unconditionally** requires accessibility: even when steps contain only ordinary
   actions such as `launch`/`open`, the handler starts with `svc ?: 503`, so it is honestly classified
   as a hard dependency; if it ever becomes "scan steps first, allow when all are ordinary", the
   reason table entry and this document must change together.
2. `GET /notif` depends on the accessibility notification-event cache
   (`DshAccessibilityService.recentNotifications()`), **not** `NotificationListenerService`; if it
   switches to NLS, move it to §3 and delete the reason entry.
3. `GET /screenshot` has no privileged fallback (`ShotFallback` only serves `/ocr`) so it stays a hard
   dependency; if a Shizuku/root fallback is added it must be re-classified. `GET /interference` is in
   the reason table and its 503 comes from the gate with `because`/`how_to_fix`.
4. The service's display name comes from `res/values/strings.xml` `accessibility_label`
   (`DSH Mobile Helper (optional)`; Chinese `DSH Mobile 辅助（可选）`).

## 10. Related documents

Full route table: [android-agent-routes.md](android-agent-routes.md); capability and UI details:
[ACCESSIBILITY.en.md](ACCESSIBILITY.en.md) / [ACCESSIBILITY.md](ACCESSIBILITY.md).
