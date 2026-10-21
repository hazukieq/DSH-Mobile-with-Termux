# Narrow-screen and tablet UI: client plugins

> Fixes for Android phones in portrait (~386 CSS px wide) and tablets in portrait. Everything is
> implemented as
> **client plugins**: no engine source edits, no DOM hijacking, no conflicts with other UI
> plugins, and engine upgrades won't overwrite them.

## 1. The problem: it's not "squeezed", the width becomes 0

The engine's `@deepseek-ai/dsh-client-ui-layout` lays out three columns with CSS Grid, and
`computeColumns()` decides the widths:

```js
const s = sidebar === 0 ? collapsedWidth : clampWidth(sidebar, 264, 420);
const available = viewport - s - 400;          // always reserves 400px for the chat column
const rightbar  = rightbar === 0 || available < 300
  ? 0
  : Math.min(available, clampWidth(rightbar, 300, viewport * 0.7));
return { sidebar: s, center: Math.max(0, viewport - s - rightbar), rightbar };
```

On a phone in portrait the viewport is only ~386 px:

| Case | Calculation | Result |
|---|---|---|
| Sidebar expanded + chat | `386 - 280 = 106` | chat column squeezed to 106 px; text wraps one character per line |
| Right panel opened | `available = 386 - 56 - 400 = -70 < 300` | **right column width = 0** (the column is never rendered) |

So "view changes / code diff" isn't truncated — the panel's column is computed as zero width.

The settings dialog has its own issue: the panel is `width: 800px; max-width: calc(100vw - 48px)`
with a fixed `width: 188px; flex: none` nav, leaving ~150 px for content and squashing labels to 47 px.

## 2. Why plugins

The DSH WebUI supports **client plugins** (`dsh.client`):

- a standard npm package — `package.json` declares `dsh.client` (`platform: "web"`, optional `inject`)
  and provides `exports["./client"]`;
- `dsh-client-modules` scans the host Loader entries and injects the browser bundle;
- the bundle loads as a classic script and registers via
  `window.__ModuleLoader__.load({ id, factory })`, where `id` must equal the package name.

Benefits: **zero source edits**, natural isolation (each plugin tags only the elements it
located and writes rules scoped to its own `<style>`), and full removability.

All three plugins live in the APK under `assets/android-plugins/`; the app deploys them next to
the engine and inserts their entries into `android-overlay.yml`.

## 3. Plugin 1 — `narrow-sidebar` (full-width sidebar)

**Behavior**: in portrait, when the sidebar is expanded, it spans the full row and the chat
column steps aside. Landscape/wide screens are untouched.

- **Locate** (no hashed class names): find the collapse button by accessibility label
  (`收起侧边栏` / `Collapse sidebar`), walk up to the first `display: grid` ancestor (the AppFrame);
  that column is the sidebar, `nextElementSibling` is the center column.
- **Condition**: `innerWidth <= 900 && innerHeight > innerWidth`.
- **Apply**: tag the sidebar column with `data-android-narrow-sidebar-col`
  (`grid-column: 1 / -1`) and every following **column** with `data-android-narrow-yield-col`
  (`display: none`).
- **Detail**: "column" detection skips `position: absolute/fixed` elements and the engine's
  `data-shell-overlay` layer so dialogs/toasts are never hidden.

## 4. Plugin 2 — `narrow-settings` (settings dialog reflow)

**Behavior**: at ≤ 720 px, the settings dialog stacks vertically — the nav becomes a
horizontally scrollable top bar and content takes the full width. Wide screens untouched.

- **Locate**: prefer the engine's own dialog semantics `[role="dialog"][aria-modal="true"]`,
  then find the `<nav>` element (nav column) and the largest sibling (content column).
- **Fallback**: if a future version drops the role, fall back to "inside an overlay + has a close
  button + has a nav".
- **Hard gates**: the element must be **inside an overlay** and **contain a close button** —
  if either is missing, do nothing. This is a scar from a real incident: an earlier version used
  only "many buttons + a big sibling", mis-identified the main AppFrame as the settings panel and
  broke the whole UI (black screen).
- **Apply**: panel `flex-direction: column` + `width: calc(100vw - 24px)`; nav `flex-direction: row`
  with `overflow-x: auto`; nav items `flex: none; white-space: nowrap`; content `width: 100%`
  with `overflow-x: hidden`.

## 5. Plugin 3 — `narrow-rightbar` (full-screen right panel)

**Behavior**: on narrow screens, the right panel (changes/diff, trajectory, …) becomes a
full-screen overlay **while it is open**. Closed state and wide screens are untouched.

- **Locate**: `[data-sidebar-right-panel]` **with** `[data-sidebar-right-open]` — the latter only
  exists while open, so a closed panel can never be forced visible.
- **Apply**: lift the panel out of the zero-width grid column:
  `position: fixed; inset: 0; width: 100vw; height: 100vh; z-index: 60`; zero out
  `--dsh-sidebar-width` and force `visibility: visible; transform: none` on
  `[data-dockkit-host=dock]` — this cancels the translate that pushes the dock off-screen in the
  engine's narrow full-screen mode.

## 6. Debugging & verification

Plugins log decisions with a `[dsh-android]` prefix; the app forwards them to the engine log:

```
[dsh-android] narrow-sidebar: 已接管（侧栏全宽，隐藏 2 个对话列；视口 386x777）
[dsh-android] narrow-settings: 已接管设置面板（视口 386x777）
[dsh-android] narrow-rightbar: 已接管右侧面板（视口 386x777）
```

Four test suites cover the decision logic and DOM-structure changes
(`node scripts/probes/_test_narrow_*.mjs`):

| Test | Coverage |
|---|---|
| `_test_narrow_sidebar.mjs` | 30 checks: portrait takeover, landscape no-op, rotation restore, dispose cleanup, node-rebuild migration |
| `_test_narrow_jsdom.mjs` | 26 checks: real DOM via jsdom, right panel present, overlay not harmed |
| `_test_narrow_settings.mjs` | 36 checks: panel location (role/nav), overlay not mis-identified, main UI never touched |
| `_test_narrow_rightbar.mjs` | 28 checks: only when open, closed state untouched, panel-rebuild migration |

Troubleshooting:

1. Look for `[dsh-android] narrow-*` in the engine log — absent means the plugin didn't load or the
   condition didn't match;
2. Verify `engine/android-plugins/<pkg>/` contains all three files
   (`package.json`, `index.js`, `client.js`);
3. Check the `android-overlay.yml` entry points at `index.js`;
4. Restart the engine (plugins load at engine start).

## 7. Boundaries

- Desktop mode (landscape, 1280 px) is **fully unaffected** — the engine's normal split layout
  and drag handles stay;
- Width thresholds: sidebar 900 px, settings 720 px, right panel 900 px;
- Only geometry is changed (width/height/direction/overflow) — colors, font sizes and spacing are
  left to the engine and theme plugins.
