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
  `/batch`, `/loop/run`, `/pick`.
- Vision: `POST /vision/crop`, `/vision/track`, `/vision/tap`, `/vision/drag`, `GET /vision/list`.
- Memory: `GET /memory/list`, `/memory/stats`, `POST /memory/lookup`, `/memory/store`,
  `/memory/confirm`, `/memory/clear`.
- App/plugins: `GET /plugin/list`, `POST /plugin/install`, `GET /notif`, `GET /diag`,
  `POST /notify`, `GET|POST /say`, `POST /engine/restart`.
- Runtime: `GET /runtime/status`, `/runtime/feed`, `POST /runtime/stage`, `/runtime/pull`,
  `/runtime/apply`, `/runtime/rollback`, `GET /local/status`, `POST /local/ensure`.
- PTY: `POST /pty/open`, `/pty/write`, `/pty/resize`, `/pty/close`, `/pty/kill`, `GET /pty/read`.

## Debugging the Web UI — CDP is mandatory

The WebView can be debugged over the Chrome DevTools Protocol. Never guess the DOM from
screenshots; drive the real page.

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

`dsh-mobile cdp status` prints the flag state, the app pid and every devtools socket.
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
