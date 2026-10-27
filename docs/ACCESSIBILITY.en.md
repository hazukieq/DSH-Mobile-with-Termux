# Accessibility control: the AI actually knows how to use your phone

> Not a "simulated-tap script" — a capability that **sees, decides, verifies and self-corrects**:
> it reads the screen, finds the target, taps precisely, knows whether the tap worked, and
> switches route on failure.

## Scope: only reading/injecting into another app's UI needs it

Accessibility is a precondition for **one family of capabilities**, not a switch for the app. One
test decides it:

> Does the capability have to **read another app's UI tree or screen pixels**, **inject
> gestures/keys/text into another app**, or **consume accessibility events**? Yes → it needs the
> service. No → it does not.

- **Needs it**: reading the screen (`/screen`, `/scene`, `/diff`, `/interference`), waiting
  (`/wait`, `/wait-text`, `/idle`), tapping/keys/typing/scrolling (`/tap`, `/gesture`, `/key`,
  `/input`, `/scroll-*`, `/pick`, `/vision/tap|drag`), accessibility screenshots and frame diffs
  (`/screenshot`, `/frame-*`, `/vision/crop|track`), the notification event cache (`/notif`),
  the composite drivers that run them (`/batch`, `/act`, `/loop/run`), and observing *another*
  app's foreground state (`wait-app <other-package>`).
- **Never needs it**: launching/jumping to an app (`/launch`, `/launch-intent`, `/open`,
  `/activity`), waiting for *this* app to return (`wait-app com.termux` — our own Activity
  lifecycle), the app's own window screenshot (`appshot`, PixelCopy), sharing and files
  (`native share`, `/open/file|folder|share`), media playback, notifications, TTS,
  engine/plugin/PTY/runtime control, and memory metadata operations. They keep working with the
  service off.

So ordinary chat, opening apps, sharing files, playing audio and the background engine do not
depend on accessibility; only "the AI operating another app" does. When it is off, do the ordinary
part first, then either ask the user to enable it (`POST /a11y/enable` opens the system page) or
route around the a11y step (let the user tap/read, hand off with `/launch`, capture our own window
with `appshot`, report with `notify`/`say`).

The v2.5.2 incident was exactly this misclassification: `/launch`, `/launch-intent`, `/open` and
`/app-screenshot` were treated as requiring accessibility, so "open WeChat" answered 503 with the
service off. It was a gate-classification bug, **not** an Android limitation; fixed in 6fb665c.
Full classification and maintenance rules: [A11Y-SCOPE.en.md](A11Y-SCOPE.en.md).

## 1. What it can do

With the accessibility service enabled, the agent gains all of this — no root, no ADB:

| Capability | Details |
|---|---|
| **Read the screen** | text, widget type, coordinates and hierarchy; falls back to **OCR** for purely graphical UIs |
| **Tap** | by text, by widget id, or by coordinates, with before/after assertions |
| **Type** | into a specific field (located by hint or nearby label), without clobbering other text |
| **Swipe / scroll** | direction, distance and duration control; scroll-to-find and scroll-and-collect |
| **Long-press / double-tap / pinch / drag** | long-press menus, double-tap to enter, map zoom, drag-to-reorder |
| **Keys** | Back, Home, Enter and other system keys |
| **Wait** | wait for the UI to settle, for text to appear, for the page to finish loading — never a fixed sleep |
| **Composition** | repeat N times, repeat until a condition, conditional branches (`when`), back-stack return, data collection |

Launch / Intent, sharing, media, notifications and TTS are **not** accessibility capabilities (see
the scope section above) — they use plain `startActivity` / Intent / system-service APIs. This section lists what the
service *adds*, not a precondition you must satisfy before using the app.

All of it is expressed as **21 action primitives**, dispatched once to a local closed-loop executor —
not "think one step, call the cloud, think the next".

## 2. Why it's fast (measured)

| Stage | Measured | Notes |
|---|---|---|
| **Single-step loop** | **~1.45 s/step** (decide + act + verify) | real device `loop/run`: `picked=设置中心 actMs=1132 verify=True verifyMs=69` |
| **Verify** | **~5 ms** | compact fast path, ~1/7 the size of a full JSON dump |
| **OCR chain** | **326–393 ms** (was 1370–1588 ms, **~4×**) | screenshot throttling + content-area crop + single-frame reuse |
| **Settle** | **355–1566 ms** (page-dependent) | strong/weak loading signals; no more fixed 900 ms waits |
| **Frame diff** | **p50 ~55 ms** (incl. screenshot IPC) | 64×64 downsample, comparison <5 ms |
| **Scene-memory hit** | **0 ms, zero cloud calls** | a familiar screen reuses the previous decision |

## 3. Why it's robust (it sees, and it verifies)

**Layered vision, each layer a fallback for the others**:

1. **Accessibility tree** — standard widgets give text and coordinates directly (fastest);
2. **OCR** — fills in when the tree has no text (some system browsers, canvas-heavy pages);
3. **Icon matching** — text-free icon buttons (settings gear, back arrow, …) are recognized too;
4. **Frame diff** — answers only "did the screen change", used to confirm a tap landed.

**Every step is verified**: tap → verify the target state → on failure switch route
(different anchor, different action, different path) and hand the failure evidence
(screenshots, candidate list, reason) back to the decision layer instead of retrying blindly.

**It doesn't fight you**: injected gestures are recognized and excluded, so "user took over" is
never mis-detected; the floating overlay is hidden during screenshots so the AI sees the same
screen you do.

**It acts like a human**: swipe paths are curved with slight jitter, not mechanical straight lines;
tap coordinates carry a small random offset instead of a fixed signature.

## 4. Why it's economical

- **fast mode**: accessibility tasks only think on the **first** step; later steps pay no reasoning cost;
- **dispatch once, execute locally**: long sequences run locally instead of one cloud round-trip per step;
- **page-state detection**: the next step starts only when the page is genuinely ready — no idle sleeping;

## 5. Compared with alternatives

| | Classic Appium / UIAutomator scripts | Cloud phone + remote agent | **DSH Mobile** |
|---|---|---|---|
| Deployment | PC + adb + test framework | cloud instance + network | **install an app on the phone** |
| Root/ADB required | yes | yes | **no** (optional Shizuku/Root enhances it) |
| Adapting to new UIs | hard-coded selectors break on any change | screenshot uploaded to the cloud each time | **tree + OCR + icons + frame diff, layered** |
| Per-step latency | script-dependent | 1–3 s cloud round-trip | **local loop, measured ~1.45 s/step** |
| Verification | script assertions | model "looks at the picture" | **structured per-step verification with route switching** |
| Data | local/CI | **screen and actions leave the device** | **all on-device; only text summaries go to the model** |

## 6. Security boundaries

- **Token-gated local bridge**: it listens on `127.0.0.1:3083` only; the token is random per start and
  injected into the engine env only. Third-party apps on the same device can't call it (no token →
  `unauthorised`, verified);
- **Three privilege tiers**: Normal (sandbox) / Shizuku (adb-level) / Root (full disk); options whose
  capability isn't ready are greyed out;
- **su gate**: in Normal mode `engine/bin` blocks the agent's child processes from escalating;
- **Screenshot avoidance**: the overlay hides itself while the agent captures the screen, so it never
  mistakes its own UI for page content.

## 7. Field results

- **Open system settings and locate an item**: single-step loop 1.45 s (tap 1.13 s, verify 69 ms);
- **Multi-feature page task**: 7 calls / 64.7 s (including scroll-to-find, bottom detection, confirmations);
- **Seven-page noise test site** (scrolling lists, delayed loading, text-free icons, modal occlusion,
  long lists): all passed;
- **Cross-app task** (browser → settings → return to the original app): the "always return to the app"
  constraint is executed reliably.

## 8.6 OCR coordinate calibration: `scr calib` (v2.5.28)

OCR boxes live in **screenshot pixel space**; accessibility bounds live in **display coordinates**.
On this device they match (portrait `1080x2376`, landscape `2376x1080` - measured, not assumed), but any
scaling, cropping, letterboxing or vendor screenshot rescaling shifts every tap at once, and that kind of
error is invisible in code review.

The tool takes a11y text-node centres as ground truth, pairs them with OCR boxes of the same text, and
fits `truth = s * obs + d` per axis by least squares. Parameters are written only when `n >= 3` and the
fit really improves (MAE < 0.9x before and < 24px), keyed by **screenshot pixel size**, so portrait and
landscape are bucketed separately. Without a matching bucket the mapping is identity - old behaviour is
untouched.

```bash
scr calib              # measure only: pairs, sx/sy/dx/dy, MAE before/after
scr calib --apply      # measure and persist (undo with --clear)
scr calib --list       # show stored buckets
```

`/ocr` and `/scene` apply the mapping to OCR coordinates automatically.

## 8. Limits (be aware)

- **Pure canvas / game frames**: with no readable text, only OCR and visual localization remain;
  complex frames can still fail;
- **Controls with neither text nor a standard icon**: would need a vision model (off by default);
- **System permission dialogs**: still require a manual "Allow";
- **Anti-automation detection in some apps**: not guaranteed to pass everywhere — and we do not
  implement detection evasion.

## 9. Coordinate taps: semantics and the v2.5.30 regression fix

`/tap {x,y}` means **pixels**: it dispatches a synthetic gesture and does NOT silently resolve the point to an accessibility node. v2.5.29 did the opposite and made coordinate taps unreliable (wrong ancestors, stale full-screen nodes, swallowed taps on the notification shade). Measured on 2026-10-27 (vivo V2520A, Android 17): gesture taps work on third-party apps and on the SystemUI shade; they are swallowed by DSH's own accessibility overlay (use `/overlay` or `scr status --action` there).

Node semantics are still available explicitly with `{"via":"auto"}`, now guarded so that nodes covering >=60% of the screen (stale full-screen layers left by closed sidebars) are never used as tap targets - the call degrades to a gesture instead.

Responses now carry `sent`, `dispatchedAt`, `via`, `staleFullScreenNode`, `spaceHint` and `ownOverlay`, which is enough for a caller to prove where the tap actually landed.

## 9.1 Unified dispatch and window diagnostics (v2.5.31 / 2.5.35)

`POST /act` with `{"tapXY":[x,y]}` now goes through the same `tapDispatch` as `POST /tap {x,y}`:
synthetic gesture by default (jitter ≤ 4 px), node semantics only with `{"smart":true}` or
`/tap {"via":"auto"}`. Both reply with the same diagnostics — `via`, `sent`, `dispatchedAt`,
`window.target` / `window.all`, `node`, `staleFullScreenNode`, `ownOverlay`.

`window.target` prefers the **foreground app's** window, because system-UI windows can also be
full-screen; `window.all` lists every window containing the point, ascending by area.
