<!-- dsh-mobile app doc v48 — managed by DSH Mobile; rewritten on app updates -->
# DSH Mobile — the app you are running inside

> This build is **DSH Mobile with Termux**, a fork of
> [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)
> (MIT) on top of [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness).
> Fork source: <https://github.com/hazukieq/DSH-Mobile-with-Termux>.

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
  Restart policy: prefer `dsh-update restart` (it schedules the restart via the app supervisor
  ~8s later so the command output and your reply survive; the turn is still interrupted) or
  `POST /engine/restart` (202, same as Settings → 重启引擎). Never kill the managed engine or
  use the plugin market's "立即重启" button - that spawns a detached `dsh web` that races the
  managed engine for port 3080. A restart ends the current turn, so warn the user first.
- App self-info/screenshots: `GET /process` (app pid, uid, version, `webview_debug`,
  `devtools_socket`), `GET /app-screenshot?max=N` (native PixelCopy capture of the app window).
- Runtime: `GET /runtime/status`, `/runtime/feed`, `POST /runtime/stage`, `/runtime/pull`,
  `/runtime/apply`, `/runtime/rollback`, `GET /local/status`, `POST /local/ensure`.
- PTY: `POST /pty/open`, `/pty/write`, `/pty/resize`, `/pty/close`, `/pty/kill`, `GET /pty/read`.

## Debugging the Web UI — CDP is mandatory

The WebView can be debugged over the Chrome DevTools Protocol. Never guess the DOM from
screenshots; drive the real page.

0. Diagnose first (recommended): `dsh-mobile cdp doctor` runs the whole chain — process info,
   bridge `/process`, socket discovery, devtools HTTP, a test `Runtime.evaluate` — and prints
   OK/FAIL per layer.
1. **WebView debugging is ON by default since v1.3.61** (no flag, no restart needed). To opt out
   on a device: `dsh-mobile cdp off` (creates `webview-debug-off` next to the app's external
   files) and restart the app; `dsh-mobile cdp on` removes the opt-out.
2. On-device bridge (no adb needed): `dsh-mobile cdp serve 9222` — connects the WebView's
   abstract devtools socket to `127.0.0.1:9222` and prints the socket it picked. Run it in the
   background (`nohup dsh-mobile cdp serve 9222 >/tmp/cdp.log 2>&1 &`).
3. Inspect: `curl -s 127.0.0.1:9222/json/list`; evaluate JS with
   `dsh-mobile cdp eval 'document.title'`; reload with `dsh-mobile cdp reload`.
4. From a host with adb: `adb forward tcp:9222 localabstract:webview_devtools_remote_<app pid>`
   and point Playwright/Chrome at `http://127.0.0.1:9222` (`connect_over_cdp`).
5. `GET /process` reports `webview_debug: true` and the exact `devtools_socket` name.

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

## Native control / debug API (app internals for agents)

Besides UI automation, the app exposes its own state and controls. All of it is token-gated
bridge HTTP on `127.0.0.1:3083`, reachable as `dsh-mobile bridge ...` or the `native` gate:

| command | endpoint | what |
| --- | --- | --- |
| `native status` / `dsh-mobile app status` | `GET /status` | app+engine, foreground + **resumed activity**, network (type/validated/metered/bandwidth), battery, memory, storage, screen on/locked, permissions (accessibility, all-files, overlay, notifications, battery optimisation) |
| `native settings` | `GET /settings` | current native settings: page_scale, orientation, lan_web_access, a11y_max_provider, priv_mode + writable keys |
| `native set page_scale=100 orientation=auto` | `POST /settings` | write native settings; returns `applied` / `restartRequired` / `errors` |
| `native open settings` | `POST /activity` | open our own Activity (`settings` / `terminal` / `main` / Class); after 350 ms it reports `resumed_before`/`resumed_after` so a BAL-blocked start is visible |
| `native net` | `GET /status` subset | network + battery + screen |
| `dsh-mobile app open terminal` | same | convenience from the helper |
| `dsh-mobile app settings set priv_mode=root` | `POST /settings` | switches privilege mode (engine restart required) |

Other useful native endpoints: `GET /process` (pid/uid/version/debug flag/devtools socket),
`GET /app-screenshot?max=N` (PixelCopy of the app window), `GET /screen` + `/tap*` (accessibility),
`POST /launch-intent` (`component` + `extras`), `GET /notif` (list notifications), `POST /notify`, `GET /say`/`POST /say`,
`GET /plugin/list`, `POST /plugin/install`, `POST /engine/restart`, `/pty/*`, `/runtime/*`.

### Instant actions (tap / scroll / type / key)

All simulated-input endpoints (`/tap`, `/tap-id`, `/gesture`, `/key`, `/input`, `/scroll-*`,
`/vision/tap|drag`, `/pick`, `/overlay`, `/loop/run`, and the helper's `cdp click|click-text|tap|
type|key|scroll`) return `instant:true`, `verify:"none"` and
`do_not:["screenshot_immediately","retry_immediately"]`. The event has been dispatched; the UI
may be mid-animation or the user may have moved on. To verify a result: settle (`scr idle` or
`cdp wait`) then read once. Screenshots are for evidence/diagnosis, not tap confirmation.

### Opening files with system apps (no desktop needed)

App-private files (`$DSH_HOME/...`) are exposed to the system file manager through the app's own
SAF **DocumentsProvider** (root "DSH Mobile"): `native open-folder <dir>` / the UI's
"显示文件位置" deep-links DocumentsUI to the exact folder (breadcrumb shows
`DSH Mobile > …`), so private paths are browsable/locatable too. The provider is read-only for
other apps and only exposes `$DSH_HOME` (never all of /data).


The WebUI "open in app" / "reveal in folder" works on Android now: the engine patch makes
`dsh-native-command` hand the path to the app instead of failing with "this host has no
desktop". From the shell/bridge:

```bash
native open-file /sdcard/Download/a.pdf     # POST /open/file  {path, mime?}
native open-folder /sdcard/Download         # POST /open/folder
native share /sdcard/Download/a.pdf         # POST /open/share
```

Files are exposed through the app's own read-only content provider
(`content://<pkg>.dshfiles/...`) with a one-shot read grant; folders under `/sdcard` use the
system Files app document URI. The receipt reports `opened` (whether the app really left the
foreground) and `handoff:true`; if no installed app can handle the type you get `ok:false`
with a readable error. When the start is blocked while the app is in the background, use the
same BAL workarounds as `native open` (foreground the app / notification fallback).

### Opening with a specific app / sharing to a specific app

When the user says "用 XX 打开 / 分享给 XX / 播放 / 阅读", the app can target that app directly
(no chooser):

```bash
native apps view audio/mpeg        # list apps that handle VIEW audio/mpeg (label + package)
native apps send audio/mpeg        # list share targets
native play /sdcard/x.mp3 --app 网易云音乐      # open with a named app (playback/video/reader)
native share /sdcard/x.mp3 --to 微信            # ACTION_SEND directly to WeChat
native share-text "https://..." --to QQ        # share a link/text directly to QQ
```

Bridge: `GET /apps?action=view|send&mime=...` returns `{apps:[{package,label,default}]}`;
`POST /open/file|share` and `POST /share/text` accept `{"app":"<label or package fragment>"}`.
Matching is by app label or package substring; zero matches returns the available list, multiple
matches returns the candidates - so the agent asks the user instead of guessing. Without
`app`, the normal chooser opens. Receipts stay hand-offs (`opened` = left the foreground).

### Sharing (outgoing and incoming)

**Outgoing** (agent → other apps): the share sheet is opened through the app, no permissions needed:

```bash
native share /sdcard/Download/a.pdf          # POST /open/share  (ACTION_SEND + EXTRA_STREAM)
native share-text "看这个：https://..."       # POST /share/text   (ACTION_SEND text/plain)
native open-file <path>                      # open with another app instead of sharing
```

Receipts are hand-offs (`opened` = the app really left the foreground); a chooser with no
handler stays in front and reports `opened:false`.

**Incoming** (other apps → DSH Mobile): DSH Mobile is registered for `ACTION_SEND` /
`ACTION_SEND_MULTIPLE` (text and files). When the user shares something to it:

- files are copied into `$DSH_HOME/inbox/` (stable path the engine/terminal can read);
- the current conversation's composer is prefilled with the file paths plus the shared text
  (draft is merged, not overwritten); nothing is sent automatically;
- a toast confirms: "已把分享内容放入输入框"; if no conversation/composer is available the
  toast explains the inbox path instead ("已接收分享，但当前没有可写入的会话输入框…");
- shared text-only links land directly in the composer as text.

### Audio playback (native MediaPlayer)

Music/TTS-length audio does **not** need any Termux audio stack (no ALSA/PulseAudio/mpv):
the app decodes and outputs through Android's own audio path with a media audio focus.

```bash
media play /sdcard/Music/song.mp3 "Song" "Artist" --loop
media pause | resume | stop | status
# bridge equivalents: POST /media/play {"path"|"url","title","artist","loop"},
#                     POST /media/pause, /media/resume, /media/stop; GET /media/status
```

- Local paths: same uid, so files under `$PREFIX`/`$DSH_HOME` work; `/sdcard` needs the
  all-files grant. `http(s)` URLs work too.
- Supported formats are whatever the platform MediaPlayer decodes (MP3, AAC/M4A, FLAC, OGG,
  WAV…). Not supported: opus-in-webm, APE, DSD - transcode first (e.g. `ffmpeg` in Termux).
- Playback continues in the background because the engine runs in a foreground service.
- This is an instant action: `ok:true` means playback was started; no screenshot verification.

### Background launches (BAL) and the `opened` field

`POST /activity` waits up to 1.2 s for the target Activity to actually resume and reports
`opened:true|false`, `background_blocked`, the current BAL exemptions
(`bal_exempt.foreground|overlay|accessibility`) and `notification_posted`. If the OS refused a
background start, `opened:false` (not a fake success). Pass `{"notifyIfBlocked":true}` (or
`native open <page> --notify`) to post a tappable notification fallback. Do not retry blindly
and do not screenshot to confirm. Overlay / accessibility are **optional suggestions** (both are
BAL exemptions, neither is required): `native open overlay-settings` or
`native open accessibility-settings` opens the system page for the user; if they decline, just
fall back to `--notify` or asking them to foreground the app, and do not ask again.

### Hand-off semantics (opening pages for the user)

`POST /activity` is a **hand-off**: it opens the page and returns immediately with
`handoff:true`, `verify:"none"`, `do_not:["screenshot","poll","retry"]` and a note. The user may
edit and come back before the call returns, so `observed_resumed` may already be the previous
page again - `ok:true` still means the hand-off was accepted. Agents must not screenshot, poll
or re-run to "confirm", and must not wonder why the page is no longer visible. Verify state
only when the task is about state ("is X enabled?") or the user asks for evidence.

Honesty rules baked into the API: `/activity` compares the resumed activity before/after and
reports `hint` when the window did not change; `/launch-intent` returns 404 for unknown
components instead of a fake `ok`; `/settings` masks secrets and reports `restartRequired`.

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
- **Onboarding asks for file access**: on Android 11+ the first-run flow requests "All files
  access" (`MANAGE_EXTERNAL_STORAGE`) before entering the app (skippable; later in Settings →
  权限中心). Android 10 and below gets the legacy READ/WRITE_EXTERNAL_STORAGE runtime prompt.
- **Web UI mobile layer** (`dsh-mobile-ui`): on tablets it is enabled **only in portrait**;
  rotating to landscape reloads the page into the stock desktop WebUI (and back). Phones keep
  the mobile layer in both orientations. The layer has an app bar with ☰ drawer, conversation title,
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

## Node, pnpm and plugin tooling on the device

The bundled runtime has Node, but `dsh` and `pnpm` are **not on PATH** (verified on a real
device, 2026-10-22). Use these forms instead of trying to install another Node:

```bash
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
# dsh CLI (not on PATH):
node "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" plugin --profile web list

# pnpm: the app ships Corepack, but its shims assume a sibling `node` that does not exist.
# Run Corepack's pnpm entry directly, or create a thin wrapper once:
mkdir -p "$HOME/bin"
printf '#!/data/data/com.termux/files/usr/bin/sh\nexec node "$PREFIX/lib/node_modules/corepack/dist/pnpm.js" "$@"\n' > "$HOME/bin/pnpm"
chmod 755 "$HOME/bin/pnpm"

# then any pnpm-based flow works (plugin manager spawns `pnpm`):
PATH="$HOME/bin:$PATH" node "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" \
  plugin --profile web add <package>
```

Notes:

- Installed plugins land in `$DSH_HOME/profiles/<profile>/package.json` (+ `node_modules`), and
  the profile's `dsh.profile.bundles` is updated; with HMR on, no engine restart is needed.
- Never overwrite `$PREFIX/bin/node` and do not install a second Node — Corepack's
  `dist/pnpm.js` downloads an `android-arm64` pnpm on first use.
- Experimental bundles are toggled through the WebUI's `pluginManager` Remote, not pnpm. The
  WebUI token can be read from the last `dsh web: ... token=` line in `$PREFIX/engine.log`;
  exchange it for a cookie (`curl -c cj.txt "http://127.0.0.1:3080/?token=$T"`), then POST
  `pluginManager/listBundles` / `pluginManager/setBundleEnabled` to
  `http://127.0.0.1:3080/api/...` with `content-type: application/json` and
  `origin: http://127.0.0.1:3080`. An `application:"applied"` result means HMR took it;
  `restart-required` means the engine must be restarted.

### Background progress (notification / floating window)

v1.3.68 adds a background progress layer that does **not** require accessibility:

* a small injected client plugin (`mobile-progress`) watches the current session DOM (latest
  thinking text / tool step / running state) and posts it through `DshNative.progress(json)`;
* native `ProgressHub` shows it as a low-importance ongoing notification (default) or a floating
  window; it only appears while the app is in the background (or for done/error), lockscreen
  content is `VISIBILITY_PRIVATE` (status only, details hidden);
* settings → **后台进度提示**: mode 关闭 / 通知 / 悬浮窗, plus permission rows
  (notifications / overlay / accessibility) and a "预览一次" button. Floating window prefers
  `TYPE_APPLICATION_OVERLAY` (SYSTEM_ALERT_WINDOW); it falls back to the accessibility window when
  that permission is missing, and to the notification when neither is available;
* `DshNative.openSettings()` opens the native settings page from the WebUI.
* Background survival: settings → 后台保活 asks for battery-optimisation exemption, and the
  后台进度提示 section also links to the vendor guidance (vivo/OPPO/Xiaomi background limits).
  While a task runs in the background, ProgressHub holds a 15-minute PARTIAL_WAKE_LOCK (renewed
  per update) so Doze is less likely to freeze the engine; it is released on foreground/done.
  If the WebView sandbox is reclaimed by the system, onRenderProcessGone rebuilds the WebView and
  reloads the engine URL without restarting the engine.
* Never auto-start after a normal exit (v1.3.73, hard requirement): a persisted `user_exited`
  flag is set by the notification's 退出 action and by removing the app from recents
  (`onTaskRemoved`); while it is set, `EngineService.start()` and the accessibility keepalive
  watchdog skip starting the engine. Only a fresh user launch of MainActivity (onCreate without
  saved state) clears the flag and starts the engine again. If you (the agent) need to start it,
  ask the user to open the app instead of calling the service.
* Floating-window details (v1.3.75): the overlay starts as a small ball; tapping it expands a
  rounded card whose body is a scrollable TextView (long thinking text wraps, and each update
  auto-scrolls to the newest line; the card is capped at 232dp wide / 176dp tall). The title
  line uses a marquee when it overflows. Automatic hide (foreground return) does not set the
  user-hidden flag, so the next background update can show the window again.
* Overlay refinements (v1.3.77): once the user expands the floating window it stays expanded
  (remembered across hide/show and progress updates) and the body keeps auto-scrolling as new
  thinking text arrives. Leaving the app pokes the WebUI (`window.__dshMobileProgressPoke`) so the
  first snapshot appears immediately instead of waiting for the next poll. The overlay microphone
  is driven by the accessibility service; without it the tap shows an explicit hint instead of
  doing nothing.
* The floating window is display-only now (v1.3.81): the microphone was removed from it. Voice
  input still lives in the app: the accessibility service drives a voice panel (SpeechRecognizer,
  or cloud ASR when configured), and MainActivity injects the recognised text into the WebUI
  composer. The DSH engine also ships an optional experimental voice-input bundle for the WebUI;
  it is not enabled by default.
  The accessibility service entry is labelled "optional" and its description now states it is
  only used for voice input and reading the current screen on request.
* Live reasoning chain (v1.3.83): the mobile-progress plugin now posts the whole current-turn
  reasoning chain (all 思考 blocks plus tool steps from the last user message onward) every
  250ms while running, so the floating window updates in near real time instead of stepping.
  A session is pinned: when several sessions are active, the first one shown stays pinned until
  it reports done/error; other sessions' updates are ignored until then.
* Overlay follow/liveness (v1.3.89): the overlay now follows whichever session the WebUI is
  currently showing (the earlier "first active session" pinning was removed after it froze on a
  stale background session). While a turn runs, the payload carries elapsed milliseconds and the
  title line shows `· Ns`, so the card visibly ticks even when the reasoning text is momentarily
  unchanged. Body height stays capped (132dp, internal scroll).
* Structured chain (v1.3.93): the overlay body is no longer a raw text dump. The plugin walks the
  current turn in DOM order and emits labelled lines - [工具] process title, [思考] reasoning,
  [命令] tool call, [网页] fetch, [步骤] other steps, [提问] question cards, [回复] answer tail -
  deduped and capped to the last 4000 chars (first partial line dropped).
* Overlay window sizing (v1.3.96): on vivo, changing only child visibility left the window at the
  previous (expanded) frame, so a collapsed ball kept a large transparent touch-blocking area.
  The window LayoutParams width/height are now set explicitly (56dp square collapsed, wrap
  expanded) and the view is re-attached on level changes; measured collapsed frame is 140x140.
* Progress by engine events, not DOM (v1.3.100): the host half of mobile-progress subscribes to
  the public plugin API from the open-source DSH repo - `ctx.on('agent/assistant-stream', ({agent,
  frame}) => ...)` (StreamChunk: block-start/text-delta/reasoning-delta/tool-call-delta/block-end)
  plus `ctx.on('session/event', (session, event) => ...)` for tool/call and tool/result - and POSTs
  a labelled chain ([思考]/[工具]/[结果]/[回复]) to the Android AgentBridge `/overlay` endpoint
  (X-DSH-Token from DSH_BRIDGE_TOKEN). Multiple attempts in one turn are merged; the chain is kept
  after done; session titles are resolved natively from the engine projection cache. DOM scraping
  in the browser half is now a placeholder only.
* Completion reliability (v1.4.2): the overlay used to stall mid-chain when a turn ended,
  because only the assistant-stream `end` frame triggered a final flush and an identical payload
  could be deduplicated. The host plugin now also listens to `agent/status` and flushes a final
  `done` state on `idle`, sends a 2.5s heartbeat while running (so long tool calls do not look
  frozen), and the Android side never deduplicates done/error snapshots - the card keeps the full
  chain and switches to 已完成 with elapsed time.
* Overlay always on + edge docked (v1.4.4): the floating window is no longer hidden while the app
  is in the foreground (notification still stays quiet); it keeps updating inside the app. It is
  docked to the nearest screen edge by default (right edge on first show) and re-docks to the same
  side after expanding/collapsing, so its width change never leaves it floating mid-screen.
* Overlay default (v1.4.6): the default display mode is now 悬浮窗 instead of 通知 (only for installs
  that never picked a mode; an explicit choice is kept). Selecting 悬浮窗 without the
  SYSTEM_ALERT_WINDOW permission prompts the system grant page, and if the permission is still
  missing the notification title says "悬浮窗未授权 · 点此去授权" instead of silently falling back.
* Overlay appears immediately (v1.4.8): switching 显示方式 to 悬浮窗 (or opening the app while that
  mode is active) now creates the window right away with an idle placeholder instead of waiting
  for the next progress event; 预览一次 in overlay mode really shows the overlay. Choosing 通知
  removes it, choosing 关闭 clears everything.
* Overlay across apps (v1.5.3): the window must be created with the calling Activity token, not
  applicationContext - with app context WMS marks the TYPE_APPLICATION_OVERLAY window hidden
  (mLastHidden=true, isVisible=false) as soon as the app is not top, so it vanished over other
  apps. The overlay also drops a stale root and re-creates the window when the Activity is gone.
  Note: Android itself hides overlays over protected screens such as system Settings/permission
  dialogs; that is platform behaviour, not a crash.
* Separate notice area (v1.5.9): accessibility / automation status POSTed to the AgentBridge
  (/overlay status+action) is no longer written into the reasoning chain. The overlay layout has a
  dedicated bottom notice pill (@id/overlayNoticeBox) that shows those statuses for 8 seconds and
  only while the card is expanded; the chain text keeps its own scroll area untouched.
* The overlay window docks to a **left/right side edge** (right by default; after dragging it snaps
  to the nearer side) while its vertical position stays where the user put it. When the
  accessibility service is connected the window is hosted in the accessibility window.
* Restart race with dshmarket (v1.6.4): dshmarket's "立即重启" kills the host and spawns its own
  detached `dsh web`, while EngineSupervisor also restarts the managed engine - both fight for
  port 3080 and the WebView shows ERR_CONNECTION_REFUSED. The supervisor now parses
  /proc/net/tcp{,6} for LISTEN sockets on the engine port, kills processes holding it that are not
  the managed child, and waits for the port to be free before spawning (also on unhealthy polls).
  plugin-health.mjs is disposal-safe: the loader lookup is fully guarded (no bare ctx.loader),
  every probe body runs in try/catch, and the poll timer is stopped from both the disposer and a
  Cordis effect, so a disposed context can no longer crash the engine.
* Overlay content completeness (v1.6.8): the chain text was silently capped at 1200 chars in
  StatusOverlay and the expanded card had a drag gesture on the whole card, so finger swipes moved
  the window instead of scrolling the body - long replies looked cut after a few lines. Caps are
  now: 16000 chars end-to-end (plugin/hub/overlay), reasoning 1400, tool line 420, reply keeps its
  last 4000 chars (the conclusion). In the expanded state only the action line and the status dot
  are draggable/tappable; the body scrolls freely.
