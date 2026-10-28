# Accessibility control: letting the AI actually use your phone

> Not a "simulated-tap script" — a phone-operation capability that can **see, decide, verify, and
> recover**: it reads the screen, finds targets, taps accurately, knows whether the tap landed, and
> changes route when it fails.

## 0. Authorization state: enabled ≠ connected

Android tracks two independent states for an accessibility service (see
[AccessibilityManager](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager)
and `getEnabledAccessibilityServiceList`):

| State | Test | Meaning |
|---|---|---|
| Enabled | our package appears in `getEnabledAccessibilityServiceList(...)` | granted in system settings |
| Connected | `DshAccessibilityService.instance != null` | the service is bound to the process; actions work |

Both must be true before actions work; checking only "connected" misreads "granted but not yet bound"
(fresh start, rebind after reclaim) as not granted.

- `/health` reports `permissions.accessibility` (**enabled**) plus `accessibility_connected`; an
  a11y-dependent endpoint waits up to **1.5s** for the bind before answering: still disconnected →
  503 `retryable: true` (retry shortly; if it persists, toggle the service off/on); not enabled →
  503 `retryable: false` (go enable it in system settings).
- The "Accessibility (optional)" settings page distinguishes "connected / enabled (not connected) /
  granted but disabled by the system / not granted"; the first successful connection persists
  `granted_once`, so after force-stop/update strips the secure setting it still reads "granted once,
  disabled".
- **Connected but dead**: `/status` says connected while `rootInActiveWindow` is null and `/screen`
  returns `0nodes`; toggling the service off/on recovers it. Check order: `/status` → is `/screen`
  0 nodes → toggle the service.
- Android **disables an app's accessibility service on force-stop**; the user must re-enable it in
  system settings and the app cannot work around that.

## Scope: only "reading / writing another app's UI" needs accessibility

Accessibility is not a global switch for the app but a precondition for a set of capabilities. The
only test:

> Does this capability have to **read another app's UI tree / screen pixels**, **inject gestures,
> keys or text into another app**, or **consume accessibility events**? Yes → needed; no → not needed.

- **Needed**: screen reads (`/screen`, `/scene`, `/diff`, `/interference`), waits (`/wait`,
  `/wait-text`, `/idle`), tap/key/text/scroll injection (`/tap`, `/gesture`, `/key`, `/input`,
  `/scroll-*`, `/pick`, `/vision/tap|drag`), accessibility screenshots and frame diffs (`/screenshot`,
  `/frame-*`, `/vision/crop|track`), the notification-event cache (`/notif`), and the composites that
  drive them (`/batch`, `/act`, `/loop/run`); watching whether **another** app is in the foreground
  (`wait-app <other-pkg>`) belongs here too.
- **Not needed**: launching/jumping to an app (`/launch`, `/launch-intent`, `/open`, `/activity`),
  waiting for **our own** app to come back (`wait-app com.termux`, which watches our Activity
  lifecycle), our own window screenshot (`appshot`, PixelCopy), sharing and files (`native share`,
  `/open/file|folder|share`), media playback, notifications, TTS, engine/plugin/PTY/runtime, and
  metadata operations on the memory system. With accessibility off they **keep working**.

So ordinary chat, opening apps, sharing files, playing audio and the background engine do not depend
on accessibility; only "the AI operating another app" does. When the service is off, do the ordinary
part first, then either ask the user to enable it (`POST /a11y/enable` opens system settings) or
route around it (user taps themselves, `/launch` hand-off, `appshot` for our own window,
`notify`/`say` for output). The full classification and maintenance rules are in
[A11Y-SCOPE.en.md](A11Y-SCOPE.en.md) (Chinese: [A11Y-SCOPE.md](A11Y-SCOPE.md)).

## 1. What it can do

With the accessibility service on, the agent gains (all without Root or ADB):

| Capability | What it does |
|---|---|
| **Screen read** | texts, control types, bounds and hierarchy; falls back to **OCR** on pure-graphic screens |
| **Tap / input** | tap by text, control id or coordinates (with pre/post assertions); write into the input box located by hint/nearby label without overwriting other text |
| **Swipe / scroll** | controlled direction, distance and duration; scroll to find a target, scroll to collect list content |
| **Long-press / double-tap / pinch / drag** | long-press menus, double-tap entry, map zoom, drag-to-reorder and similar interactions |
| **Keys / waits** | Back, Home, Enter and other system keys; wait for the UI to settle, for text to appear or for a page to load (not a fixed sleep) |
| **Composition** | repeat N times, repeat-until, conditionals (`when`), back-stack retreat, data collection |

Device actions such as launch / Intent, sharing, media, notifications and TTS **do not** need
accessibility (see the previous section) — they use plain `startActivity` / Intent / system-service
paths. This section lists what accessibility **adds**; it is not a precondition for those actions.
The capabilities are expressed as **21 action primitives** sent once to the local closed-loop executor.

## 2. Fast, stable, cheap

**Fast**: one closed-loop step is about **1.45s** (decide + act + verify; `loop/run` single step
`actMs=1132 verifyMs=69`); verify takes about **5ms** (compact fast path, about 1/7 of a full JSON
dump); the OCR chain takes **326–393ms** (capture throttle + content-crop + single-frame reuse);
settle waits take **355–1566ms**; frame diff p50 is **~55ms**; a scene-memory hit costs **0ms** and
zero cloud calls. **fast mode** thinks only on the **first** step, and long sequences are dispatched
once and executed locally — no fixed sleeps.

**Stable**: layered vision backs itself up — accessibility node tree (fastest) → OCR (when the tree
has no text, e.g. system browsers and Canvas-heavy pages) → icon recognition (text-less buttons) →
frame diff (answers only "did the screen change", to verify a tap landed). Every step verifies: tap →
verify target state → on failure change route (new anchor/action/path) with the failure evidence
(screenshot, candidates, error cause) handed back to the decision layer. Injected gestures are
recognised and excluded, so "user took over" is not misread; the overlay is hidden during captures;
swipe paths are curved with slight jitter and tap coordinates carry small random offsets.

**Cheap**: one dispatch, local closed-loop execution, and the next step only after the page has
really loaded — no round trips, no empty waiting.

## 3. Compared with alternatives

Versus classic Appium/UIAutomator scripts and cloud-phone remote agents:

- **Deployment**: the former needs a computer + adb + a test framework, a cloud phone needs an
  instance + network; this runs from an app on the phone, with no Root/ADB (optional Shizuku/Root
  enhancement).
- **Adaptation and verification**: scripts hard-code selectors and break when the UI changes, while
  a cloud phone has the model look at screenshots with 1–3s per-step round trips; this uses layered
  screen read + OCR + icons + frame diff, ~1.45s per local step, with per-step structured
  verification and route changes on failure. On **data**, scripts run locally/CI and a cloud phone
  **uploads screens and actions**; everything here stays on the device and only text summaries go
  to the model.

## 4. Security boundaries

- **Local bridge token gate**: the bridge listens only on `127.0.0.1:3083`; the token is generated
  randomly on every start and injected only into the engine's environment, so third-party apps on the
  same device cannot call it (no token → `unauthorised`).
- **Three permission tiers**: normal (sandbox) / Shizuku (adb-level) / Root (full disk), with
  unavailable options greyed out; **su gate**: in normal mode `engine/bin` blocks privilege
  escalation of AI child processes. **Capture avoidance**: the overlay hides itself during AI captures.

## 5. Some apps refuse accessibility injection

The two typical ones: the **GitHub app** and the **Google Play Store account drawer**.

- Android 14+ lets an app mark its UI as `accessibilityDataSensitive` (accounts, payments, tokens);
  the framework then hands that node tree only to services declaring
  `android:isAccessibilityTool="true"`. Without the declaration `dump` returns OCR only and
  `dispatchGesture` **returns true while nothing happens** on screen (silently swallowed).
- This service declares it: `res/xml/dsh_accessibility_service.xml` sets
  `android:isAccessibilityTool="true"` plus `flagRetrieveInteractiveWindows` (the account drawer is a
  separate Dialog/PopupWindow) and `flagIncludeNotImportantViews`. ⚠️ The flag **can only be declared
  in XML** (`isAccessibilityTool` is read-only at runtime) and takes effect only after toggling the
  service **off and on again**.
- Fallback: `scr tap --shell <x> <y>` (`POST /tap {"via":"shell"}`) uses Shizuku/Root `input tap`
  (uid 2000 system-synthesised input), unaffected by app-level `filterTouchesWhenObscured`; without
  Shizuku/Root it returns `via=shell requires Shizuku or root` explicitly instead of failing silently.

## 6. Notification shade and system UI

`dispatchGesture` is a synthetic gesture and the SystemUI shade is a separate window that drops
synthetic events during animations — correct coordinates still do nothing. Available means:

| Goal | How |
|---|---|
| Expand / collapse | `scr key expand-notifications` / `scr key dismiss-shade` |
| Tap a notification row or button | `scr tap-desc "<text>"`, `scr tap-id` (`ACTION_CLICK` first, gesture only on failure) |
| Detect shade / fallback | `/scene` returns a `hint` when the target is `com.android.systemui`; `scr tap --shell x y` (Shizuku/root `input tap`, trusted injection) |

`tapNode()` tries `AccessibilityNodeInfo.ACTION_CLICK` first and injects a gesture only on failure;
the channel is written to logs and to the `/tap` response's `via` field
(`action-click` / `node` / `gesture`).

## 7. OCR coordinate self-calibration: `scr calib`

OCR coordinates live in **screenshot pixel space** while accessibility bounds live in **display
coordinate space**. They agree on most devices (portrait `1080x2376`, landscape `2376x1080`), but any
scaling, cropping, letterboxing or vendor screenshot downscale shifts the whole screen's tap
coordinates systematically — you cannot see that in code, only measure it.

Method: treat "labelled nodes in the accessibility tree" as truth and "same-name OCR text blocks" as
observations, then fit `truth = s * obs + d` per axis by least squares; persist only when `n >= 3`
and the residual really improves (MAE < 0.9× before and < 24px). Parameters are bucketed by
**screenshot pixel size**, apply only within their bucket, and the mapping is identity when no
parameters exist.

```bash
scr calib          # measure only: pairs, sx/sy/dx/dy, MAE before/after
scr calib --apply  # measure and persist (scr calib --clear wipes it)
scr calib --list   # show saved bucket parameters
```

OCR coordinates returned by `/ocr` and `/scene` go through that mapping automatically.

## 8. `ownOverlay`: hitting our own overlay, and how to route around it

`ownOverlay:true` means the hit landed on DSH's own accessibility overlay. **Whether it gets
swallowed depends on what the hit lands on**:

| Hit target | Who handles the touch | Behaviour | What to do |
| --- | --- | --- | --- |
| A **control** on our overlay (capsule, toolbar buttons) | our app's View | responds normally | use coordinate gestures directly |
| A **transparent area** of our overlay with another app underneath | the overlay eats it and does not respond | gesture swallowed, page unchanged | use node actions, or hide the overlay first |
| A normal area of another app | that app | normal | use coordinate gestures directly |

Three steps: ① right after the tap run `scr changed` — `{"changed":false}` means it did not land;
② read `ownOverlay` / `window.all` / `staleFullScreenNode` from the receipt — if `window.all` lists
both `com.termux/TYPE_ACCESSIBILITY_OVERLAY` and the foreground app, suspect our own overlay first;
③ route around it, cheapest first: node semantics
`POST /act {"steps":[{"tap":"<on-screen text>"}]}`, hide the overlay
`POST /overlay {"show":false}` / `scr status-hide`, and for buttons on our own status capsule/ball use
`scr status --action` or `POST /overlay`.

Note: visible text on screen is usually a TextView (a text node), so `tap-desc` may answer
`target not found`; match by text or tap the container's centre instead.

## 9. Coordinate tap semantics

**Conclusion: `/tap {x,y}` means "screen pixels" and dispatches a synthetic gesture
(dispatchGesture) by default, with no node-semantics substitution.** Ask for node semantics explicitly
with `{"via":"auto"}` (`tapSmart`); it carries two guards: nodes covering ≥60% of the screen (the
`[0,0,W,H]` full-screen layer left behind after closing a drawer) cannot be tap targets, and hitting
such a node falls back to a synthetic gesture — the `via` receipt shows which one was used. The
coordinate receipt of `/tap` is self-describing:

```json
{"ok":true,"dispatched":true,"via":"gesture","sent":{"x":540,"y":804},
 "dispatchedAt":{"x":541,"y":803},"shot":"…/step-3.png",
 "staleFullScreenNode":"关闭侧边栏","spaceHint":"…"}
```

- `sent` is the requested coordinate; `dispatchedAt` is the point actually dispatched after jitter
  and clamping (a large difference means the coordinate was rewritten). When `staleFullScreenNode`
  is present, that window's a11y geometry is untrustworthy and coordinates should be measured from a
  screenshot. `ownOverlay:true` only says the hit landed on DSH's own overlay — **not** that it will
  be swallowed (see §8).

### 9.1 Capability matrix

| Window type | Coordinate gesture | Node action (tap-desc/tap-id) |
| --- | --- | --- |
| Normal third-party app (GitHub / KuGou / Settings / Bilibili), SystemUI shade | ✅ | ✅ |
| **Controls** on the DSH overlay (capsule, toolbar buttons) | ✅ | ✅ |
| **Transparent area** of the DSH overlay (another app underneath) | ❌ (overlay eats it and we do not respond) | ✅ (node tap, or hide the overlay first) |
| Windows with a stale full-screen node | ✅ (gestures unaffected) | ⚠️ centre may be off; use screenshot coordinates |

### 9.2 Unified dispatch and window diagnostics

`POST /act`'s `{"tapXY":[x,y]}` and `POST /tap {x,y}` share the same `tapDispatch`: synthetic gesture
by default (jitter ≤4px); only `{"smart":true}` or `/tap {"via":"auto"}` take the node path. Both
return the same fields:

| Field | Meaning |
| --- | --- |
| `via` | `gesture` / `action-click` / `shell` / `list` / `failed`; on the node path also `node` with the matched label |
| `sent` / `dispatchedAt` | requested coordinate / **actual** landing point (after jitter, clamped on screen) |
| `window.target` / `window.all` | window the point belongs to (foreground app preferred, `window/type/top/left/area`) / all windows containing the point, ascending by area (SystemUI windows can be full-screen too — do not judge by area alone) |
| `staleFullScreenNode` | label of the clickable stale node covering ≥ the full screen on the current page, when present |
| `ownOverlay` | the hit is on DSH's own accessibility overlay; whether it gets swallowed depends on the hit target (§8) |

## 10. Limits

- **Pure Canvas / game screens**: with no readable text only OCR and visual localisation remain, and
  complex scenes can still fail; **text-less controls with no standard icon** need a vision model,
  which is off by default.
- **System permission dialogs**: the user still has to tap "Allow"; **anti-automation checks** in some
  apps are not guaranteed to pass (and this project does not build evasion against them).

## 11. Two execution disciplines: read receipts · coordinate source

### 11.1 Read the receipt

- Every bridge call (`/act`, `/gesture`, `/tap`, `/share`, `/launch`, `/open`, …) must have its
  receipt read immediately: `ok` / `error` / `verified` / `changed` / `opened` / `handoff` /
  `ownOverlay` / `window`.
- `ok:false`, `verified:false`, `changed:false`, `opened:false` are always failures, never successes.
- `command >/dev/null` must not discard receipts; wrap them with a summariser (e.g. `actq`) when
  output is too verbose.
- `handoff:true` actions (share / open / launch) must never be retried — a retry repeats the side
  effect.

### 11.2 Coordinate source

- Coordinates may come only from the current screen's `/scene` or `/screen?format=json` `bounds` /
  `center`, or from the receipt of a previously successful tap; **never eyeball, scale-estimate or
  reuse stale page coordinates**.
- A screenshot preview (e.g. 581×1280) is not the same scale as the real screen (1080×2376) — never
  compute coordinates on the preview.
- Use `scr tap --shell` (Shizuku/Root) only when system-level injection is needed; normal taps prefer
  node semantics or `/act` transactions.
