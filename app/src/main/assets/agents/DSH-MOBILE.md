<!-- dsh-mobile app doc v1 — managed by DSH Mobile; rewritten on app updates -->
# DSH Mobile — the app you are running inside

You are not on a generic Linux box: this is **DSH Mobile**, a single APK that bundles the
official DeepSeek Harness engine plus a Termux userland. Package name `com.termux`, app data
in `/data/user/0/com.termux` (usually `/data/data/com.termux`).

## Layout

| Path | Meaning |
| --- | --- |
| `$PREFIX` = `<files>/usr` | Termux bootstrap (bash, apt/dpkg, coreutils, git, python…), plus the adapted dsh engine at `$PREFIX/lib/node_modules/@deepseek-ai/dsh` |
| `$DSH_HOME` = `<files>/dsh-home` | Harness home: `profiles/`, `sessions/`, `workspaces/`, `logs/engine.log`, `AGENTS.md`, `.zshrc`, dotfiles |
| `$DSH_HOME/workspaces` | default workspace root (often symlinked to shared storage) |
| `/sdcard` | shared storage; `rm` there goes to the trash (see below) |
| `<files>/engine` | legacy runtime path on old installs; current runtime is `$PREFIX` |

Engine WebUI listens on `127.0.0.1:3080` (token-gated). Android native side adds:

- **Native terminal** (own activity, zsh by default) with extra keys, TUI mouse-wheel scrolling.
- **Agent bridge** HTTP API on `127.0.0.1:3083` (token in `$PREFIX/.bridge-token`, header `X-DSH-Token`).
- **Accessibility service** `DSH 屏幕点击` for screen dumps/taps (must be enabled by the user).
- **Foreground service** keeping the engine alive; hot-update of the engine layer.

## Native gate commands (already on PATH, prefer these)

| Command | Purpose |
| --- | --- |
| `notify [msg]` | system notification (use when a long task finishes) |
| `scr dump` / `scr tap x y` / `scr tap-text text` | read UI hierarchy; tap by coordinates or text (needs accessibility) |
| `say text` | speak through Android TTS |
| `psx` / `killx` | list processes / kill by process name safely (does not match your own command line) |
| `loop` | screen-action loop helper (`loop --help`) |
| `fast on/off` | per-step "no thinking" mode while driving the UI |
| `linux ...` | run commands inside an installed proot distro (Alpine/Ubuntu) |
| `dsh-update check|pull|apply` | hot-update the dsh engine layer (not the APK) |
| `trash-cli list|restore <kw|idx>|empty` | recover files deleted from `/sdcard` |

Deleting under `/sdcard` or `/storage` goes through `rm`, which moves the file into
`/sdcard/dsh-trash` first; never bypass it (`/system/bin/rm`, `busybox rm`, `find -delete`).

## Full native API: AgentBridge (61 endpoints)

Everything the app can do natively is exposed over `127.0.0.1:3083`; use
`dsh-mobile bridge <GET|POST> <path> [json]` so the token is handled for you, e.g.
`dsh-mobile bridge GET /screen`, `dsh-mobile bridge POST /launch '{"package":"com.android.settings"}'`.

Groups (see the actual handlers for params):

- Screen/UI: `GET /screen`, `/screen-changed`, `/frame-diff`, `/diff`, `/screenshot`, `/ocr`,
  `POST /tap`, `/tap-id`, `/gesture`, `/key`, `/input`, `/scroll-find`, `/scroll-center`,
  `/scroll-edge`, `/wait`, `/wait-text`, `/idle`, `/interference`, `/launch`, `/launch-intent`,
  `/batch`, `/loop/run`, `/pick`. `POST /launch` and `/launch-intent` accept
  `{"component":"pkg/cls"}` (plus optional `action`, `data`, `extras`); a missing component returns
  `{"ok":false,"error":"component not found: ..."}`, and a successful component start is flagged
  `backgroundLaunchMayBeBlocked` because Android may silently refuse background activity starts.
- Vision: `POST /vision/crop`, `/vision/track`, `/vision/tap`, `/vision/drag`, `GET /vision/list`.
- Memory: `GET /memory/list`, `/memory/stats`, `POST /memory/lookup`, `/memory/store`,
  `/memory/confirm`, `/memory/clear`.
- App/plugins: `GET /plugin/list`, `POST /plugin/install`, `GET /notif`, `GET /diag`,
  `POST /notify`, `GET|POST /say`, `POST /engine/restart`.
- App self-info/screenshots: `GET /process` (app pid, uid, version, `webview_debug`,
  `devtools_socket`), `GET /app-screenshot?max=N` (native PixelCopy capture of the app window).
- Runtime: `GET /runtime/status`, `/runtime/feed`, `POST /runtime/stage`, `/runtime/pull`,
  `/runtime/apply`, `/runtime/rollback`, `GET /local/status`, `POST /local/ensure`.
- PTY: `POST /pty/open`, `/pty/write`, `/pty/resize`, `/pty/close`, `/pty/kill`, `GET /pty/read`.

## Debugging the Web UI — CDP is mandatory

The WebView can be debugged over the Chrome DevTools Protocol. Never guess the DOM from
screenshots; drive the real page.

0. Diagnose first (recommended): `dsh-mobile cdp doctor` runs the whole chain — flag file,
   bridge `/process`, socket discovery, devtools HTTP, a test `Runtime.evaluate` — and prints
   OK/FAIL per layer.
1. Enable (flag file + app restart):
   `dsh-mobile cdp on` → asks you to reopen the app (the flag is read at process start).
2. On-device bridge (no adb needed): `dsh-mobile cdp serve 9222` — connects the WebView's
   abstract devtools socket to `127.0.0.1:9222` and prints the socket it picked. Run it in the
   background (`nohup dsh-mobile cdp serve 9222 >/tmp/cdp.log 2>&1 &`).
3. Inspect: `curl -s 127.0.0.1:9222/json/list`; evaluate JS with
   `dsh-mobile cdp eval 'document.title'`; reload with `dsh-mobile cdp reload`.
4. From a host with adb: `adb forward tcp:9222 localabstract:webview_devtools_remote_<app pid>`
   and point Playwright/Chrome at `http://127.0.0.1:9222` (`connect_over_cdp`).
5. Turn it off when finished: `dsh-mobile cdp off` (flag removed; restart the app to apply).

Socket discovery is automatic and does not need `/proc`: the helper asks the app over the
bridge (`GET /process` → `app_pid` + `devtools_socket`), then falls back to `/proc/net/unix`
(where SELinux permits) and finally to an abstract-socket probe scan. `DSH_WEBVIEW_SOCKET`
overrides everything. `dsh-mobile cdp status` prints the flag state, the app process info, the
discovery source (`via bridge|proc|scan|env`) and the devtools targets.
Renderer console errors also land in logcat under the `chromium` tag (`[INFO:CONSOLE]`).

### Screenshots, recording and alarms

- **App-internal screenshot (native)**: `appshot [--max N] [--out /path.png]` or
  `dsh-mobile appshot [out.png] [max]`. Kotlin-side `PixelCopy` captures the DSH Mobile window
  surface (WebView pixels included) - no accessibility, no CDP, no screen-capture permission.
  Requires the DSH Mobile UI to be in the foreground; the JSON returns the saved PNG path and
  the file can be read with the `read_image` tool. Do **not** rely on CDP/WebView screenshots:
  they can come out blank or stale.
- **System screenshot** (whole screen, incl. status bar and other apps): `shot --out /path.png`.
  Uses the accessibility service; without it the gate explains what to enable.
- **Screen recording**: `record start 60 /sdcard/Movies/demo.mp4`, `record stop`, `record status`.
  `screenrecord` runs as shell/root, so this needs ROOT or SHIZUKU privilege mode; NORMAL mode
  exits 3 with an explanation.
- **Alarms/timers**: `alarm 07:30 "standup"`, `alarm timer 600 "tea"`, `alarm show`; standard
  AlarmClock intents, retried through `shz` when background activity start is blocked.

## Three UI layers — decide the layer before touching "settings/plugins/terminal"

| layer | what it is | typical entry | CDP? | shell launch? |
| --- | --- | --- | --- | --- |
| native | Android activities + the top-bar overlay | top-bar gear → native Settings; capsule handle | no | **no** — `am start` of the app's own activity is silently blocked by Android (exit 0, nothing opens) |
| web | the React WebUI on `127.0.0.1:3080` | drawer; **account menu → 设置 (Settings)** | yes | n/a |
| terminal | Termux shell / native terminal activity | terminal activity or engine sidebar terminal | no | yes (run commands) |

- **"Settings" is ambiguous**:
  - **WebUI settings** = `账号菜单 → 设置`: a full-screen dialog overlay (`role=dialog`), the URL
    stays `/`; it is **not** a `/settings` route, so opening that URL never worked (now the engine
    serves index.html for extensionless client routes as a fallback, but the entry is still the menu).
  - **Native settings** = the top-bar gear → Android `SettingsActivity` (permission centre, plugin
    centre, engine restart, terminal font, mobile-UI rollback).
- When the layer is unclear, **ask the user**: "do you mean the WebUI settings (account menu) or the
  gear/native settings?" — then act. Blindly trying layers costs many steps.
- `dsh-mobile ui` prints this layer map (add `--json` for machine use).

## Driving the WebUI over CDP — the correct way

The mobile UI replaces the engine app bar: the engine's own "打开侧边栏" button sits off-screen
(x ≈ -308) and must not be clicked. Use the mobile-ui app bar and real pointer events.

1. Open the drawer: `dsh-mobile cdp click 'button[aria-label="菜单"]'` (top-left, aria-label 菜单).
2. Drawer entries are buttons with these aria-labels: `新建会话`, `插件`, `插件市场`,
   `自动化任务`, `记忆系统`, `搜索会话`, `视图选项`, `添加工作区`; the account menu is
   `button[aria-label="账号菜单"]` near the bottom of the drawer.
3. Click the account menu, then a menu item (they are `[role=menuitem]`):
   `dsh-mobile cdp click-text 设置 --exact` → 设置 / 意见反馈 / 退出登录.
4. Wait for the overlay: `dsh-mobile cdp wait '[role=dialog]'`; the settings panel is
   `[role=dialog]` (`class VOzbGW_panel`) with 账号与余额 / 通用设置 / 模型 / 内置插件 /
   Agent 预设 / 刷B站 tabs and a close (关闭) button.
5. Read state with `dsh-mobile cdp eval '...'`, verify visually with `dsh-mobile appshot`.

Rules for reliable WebUI automation:

- **Never anchor on hashed CSS class names** (`hHd-Xa_iconButton`, `VOzbGW_panel`, `wSkVaW_tab`):
  they change between builds. Use `aria-label`, `role` and visible text.
- **Synthetic `element.click()` / dispatched KeyboardEvent do not work**: the components listen
  to real pointer events. Use `dsh-mobile cdp click | click-text | tap | type | key | scroll`,
  which dispatch `Input.dispatchMouseEvent` / `dispatchTouchEvent` / `insertText`, or Playwright.
- **Check state after every step** (`[role=dialog]` present, drawer closed, text changed) or take
  an `appshot`. A click whose element rect is x<0 / outside the viewport means the drawer or
  panel was not open — open it first instead of retrying the same click.
- After two failed attempts, ask the user which layer/entry they mean (see AGENTS.md).

## App UI map (what the user sees)

- **Top overlay toolbar** (native, 40 dp, semi-transparent): engine status text plus
  `重启` (restart engine), `隐藏` (hide toolbar) and the gear (open native Settings). The text
  area itself hides the toolbar when tapped.
- **Capsule handle**: after hiding the toolbar a small rounded capsule (64x16 dp) stays at the
  top centre; drag it anywhere to avoid covering content, tap it to bring the toolbar back.
  When the user says "顶部胶囊/把手" they mean this handle and the toolbar it restores.
- **Native Settings page** (gear): display (orientation, page zoom), permission centre (privilege
  mode, root, Shizuku, accessibility, all-files access, TTS), plugin centre, terminal font size,
  engine restart, and the mobile-UI rollback switch.
- **Web UI mobile layer** (`dsh-mobile-ui` v0.1.17): app bar with ☰ drawer, conversation title,
  right-pane toggle and ⓘ info button; drawer lists new session, plugins, automations, memory and
  workspace sessions; the right pane opens full-screen with its own tab strip.
- **Right pane** (engine dock): tabs for document preview / terminal / guide, `+` to add a tab,
  split and collapse buttons. Its terminal is the engine PTY (bash), separate from the native
  terminal.
- **Native terminal activity**: back arrow + title, `+` for a new session, menu; a tab strip when
  several sessions exist; the extra-key row (`ESC TAB CTRL ALT SHIFT ^C ^D ...`, sticky modifiers
  apply to the next input); `exit` closes its tab and the last tab asks whether to exit the app.
- **First launch**: onboarding flow (language, permission mode, optional Linux userland), then the
  engine boots and the Web UI appears behind the toolbar.

## Privilege modes and limits

- Modes: `normal` (app sandbox), `shizuku` (adb identity via the `shz` gate), `root` (real su).
- Without root you cannot modify `/system`, other apps' data, or mount anything.
- Accessibility must be enabled for `/screen`, `/tap*`; ask the user to enable
  `DSH 屏幕点击` in system settings (and allow restricted settings on Android 13+).
- The engine survives in a foreground service; the WebView is an activity that may be
  recycled. For UI work, treat the WebView as the target and the engine as the backend.
