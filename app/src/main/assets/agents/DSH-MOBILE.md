<!-- dsh-mobile app doc v68 — managed by DSH Mobile; rewritten on app updates -->
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
- **Accessibility service** (system label `DSH Mobile Helper (optional)`; zh `DSH Mobile 辅助（可选）`;
  settings section 屏幕点击（无障碍）) for reading other apps' UI and injecting input. The user must
  enable it in system settings. Launching/hand-off/sharing/media/files/engine do **not** need it
  (see "Accessibility scope" below).
- **Foreground service** keeping the engine alive; hot-update of the engine layer.

## Native gate commands (already on PATH, prefer these)

| Command | Purpose |
| --- | --- |
| `notify [msg]` | system notification (use when a long task finishes) |
| `scr dump` / `scr tap x y` / `scr tap-text text` | read UI hierarchy; tap by coordinates or text (needs the accessibility service) |
| `say text` | speak through Android TTS |
| `psx` / `killx` | list processes / kill by process name safely (does not match your own command line) |
| `loop` | screen-action loop helper (`loop --help`) |
| `fast on/off` | per-step "no thinking" mode while driving the UI |
| `linux ...` | run commands inside an installed proot distro (Alpine/Ubuntu) |
| `dsh-update check|pull|apply` | hot-update the dsh engine layer (not the APK) |
| `trash-cli list|restore <kw|idx>|empty` | recover files deleted from `/sdcard` |

Deleting under `/sdcard` or `/storage` goes through `rm`, which moves the file into
`/sdcard/dsh-trash` first; never bypass it (`/system/bin/rm`, `busybox rm`, `find -delete`).

## Accessibility scope: what needs the service, what never does

The app is not "an accessibility app". The service (system label `DSH Mobile Helper (optional)`,
zh `DSH Mobile 辅助（可选）`) is needed by exactly one family of capabilities — **observing or touching other apps' UI** — and the
rest of the app must keep working when it is off. Full classification: `docs/A11Y-SCOPE.md` in the fork repo (there is no docs/ directory on the device).

**Needs the service** (gated: with it off these answer a structured `503 a11y_off` that names the
reason; `GET /interference` is in the same gate set):

- read the UI tree: `GET /screen`, `GET /screen-changed`, `GET /diff`, `GET /scene`,
  `POST /wait`, `POST /wait-text`, `POST /idle`, `GET /interference` (`scr dump`,
  `scr changed`, `scr idle`, `scr find`, `scr interference`);
- inject input: `POST /tap`, `POST /tap-id`, `POST /gesture`, `POST /key`, `POST /input`,
  `POST /scroll-find`, `POST /scroll-center`, `POST /scroll-edge`, `POST /pick`,
  `POST /vision/tap`, `POST /vision/drag` (`scr tap`, `scr key`, `scr input`, `loop run`);
- screen pixels through the a11y API: `GET /screenshot`, `GET /frame-diff`, `POST /frame-wait`,
  `POST /vision/crop`, `POST /vision/track` (`shot`, `scr shot`);
- the a11y event caches: `GET /notif` (notification events), `GET /interference` (touch events);
- composite drivers: `POST /batch`, `POST /act`, `POST /loop/run`.

**Never needs the service** — do not ask the user to enable it for these, and do not treat a task
as blocked because it is off:

- launch / hand-off: `POST /launch`, `POST /launch-intent`, `POST /open`, `POST /activity`,
  `POST /a11y/enable`, `scr launch`, `native open` (`/open`, `/activity` and `/overlay` may use
  the service when it is on to verify a switch or dismiss an a11y overlay window, but never block
  on it);
- waiting for **this** app: `POST /wait-app com.termux` / `scr wait-app com.termux` (Activity
  lifecycle). Waiting for another package is the one exception: `wait-app <other>` /
  `POST /wait-app` with a foreign package returns `503 a11y_unavailable` without the service;
- sharing and files: `POST /open/file`, `POST /open/folder`, `POST /open/share`,
  `POST /share/text`, `POST /export`, `native share`, `native share-text`,
  `native open-file|open-folder`, SAF/DocumentsProvider;
- media, TTS, notifications: `POST /media/play`, `POST /media/pause`, `POST /media/resume`,
  `POST /media/stop`, `GET /media/status`, `say`, `notify`, `POST /notify`, `POST /say`,
  `POST /overlay` (progress overlay; SYSTEM_ALERT_WINDOW first, a11y window only as fallback);
- app/engine control: `GET /status`, `GET /settings`, `GET /apps`, `GET /process`, `GET /diag`,
  `GET /plugin/list`, `POST /plugin/install`, `POST /engine/restart`, `/pty/*`, `/runtime/*`,
  `native status|settings|set`, `dsh-update`;
- our own window: `GET /app-screenshot` (`appshot` — PixelCopy; no accessibility, no CDP);
- memory bookkeeping: `GET /memory/list`, `GET /memory/stats`, `POST /memory/confirm`,
  `POST /memory/clear`;
- conditional: `GET /ocr` (privileged `screencap` via Shizuku/root, and the model
  list/install/download branches, work without the service), `POST /memory/lookup` /
  `POST /memory/store` (no screen read when the caller passes `dump`).

**Classification rule** (also the maintenance rule): a capability needs accessibility if and only
if it must read another app's UI tree/pixels, inject input into another app, or consume
accessibility events. Launching (`startActivity`), intents, share sheets, `MediaPlayer`,
`NotificationManager`, `TextToSpeech`, files/SAF and our own window are ordinary Android APIs —
in v2.5.2 they were wrongly put behind the gate (so "open WeChat" answered `503 a11y_off` with the
service off); that was a classification bug, **not** an Android restriction, fixed in 6fb665c and
now enforced by `A11Y_DEPENDENT_REASONS` / `A11Y_INDEPENDENT_ACTIONS` in `AgentBridge.kt`.

**Service off, per capability:** do the ordinary part first; for an a11y step either ask the user
to enable the service (`POST /a11y/enable` opens the page; apps cannot enable it themselves) or
route around it — user taps/reads, `/launch` + `wait-app com.termux` hand-off, `appshot` for our
own window, `notify`/`say` for output. Never retry a non-retryable a11y error and never re-probe
before each action: one `GET /status` at the start of a UI task is enough.

## Full native API: AgentBridge (80 paths, 82 method+path pairs)

Everything the app can do natively is exposed over `127.0.0.1:3083`; use
`dsh-mobile bridge <GET|POST> <path> [json]` so the token is handled for you, e.g.
`dsh-mobile bridge GET /screen`, `dsh-mobile bridge POST /launch '{"package":"com.android.settings"}'`.

Groups (see the actual handlers for params):

- Screen/UI: `GET /screen`, `/screen-changed`, `/frame-diff`, `/diff`, `/screenshot`, `/ocr`,
  `POST /tap`, `/tap-id`, `/gesture`, `/key`, `/input`, `/scroll-find`, `/scroll-center`,
  `/scroll-edge`, `/wait`, `/wait-text`, `/idle`, `/interference`, `/launch`, `/launch-intent`,
  `/open`, `/wait-app`, `/batch`, `/loop/run`, `/pick`. `POST /launch` and `/launch-intent` accept
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

### Ground rules every agent must follow (tap/act/share)

- **Always read the receipt** of any bridge call (`/act`, `/gesture`, `/tap`, `/share`, `/launch`, `/open`):
  `ok:false` / `verified:false` / `changed:false` / `opened:false` mean failure. Never discard it with
  `>/dev/null`; wrap it (e.g. `actq`) if it must be short. `handoff:true` actions must never be retried.
- **Coordinates only from the current screen**: `/scene` or `/screen?format=json` `bounds`/`center`, or the
  receipt of a previous successful tap. No eyeballing, no proportional math on downscaled screenshots.
- **`ownOverlay:true`** only means the point is inside our own accessibility overlay - our own controls
  (pill, toolbar) still respond to coordinate taps; its transparent area swallows them (use node actions
  or `/overlay {"show":false}`). Details: repo `docs/ACCESSIBILITY.md` §8.7.

### Instant actions (tap / scroll / type / key)

All simulated-input endpoints (`/tap`, `/tap-id`, `/gesture`, `/key`, `/input`, `/scroll-*`,
`/vision/tap|drag`, `/pick`, `/overlay`, `/loop/run`, and the helper's `cdp click|click-text|tap|
type|key|scroll`) return `instant:true`, `verify:"none"` and
`do_not:["screenshot_immediately","retry_immediately"]`. The event has been dispatched; the UI
may be mid-animation or the user may have moved on. To verify a result: settle (`scr idle` or
`cdp wait`) then read once. Screenshots are for evidence/diagnosis, not tap confirmation.

Details that do not fit in this built-in doc ship **with the app** at `$DSH_HOME/docs` (refreshed from the
APK on every update). Read them with the `docs` gate:

```bash
docs                      # list what is available
docs ACCESSIBILITY        # tap semantics, capability matrix, ownOverlay; §10 receipt/coordinate rules
docs CLICK_CHAIN_UPGRADE  # multi-step chains
docs README               # index of everything
docs testing/2026-10-27-tap-xy-regression   # field evidence for the v2.5.30 fix
```

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

### Jumping to another app (payments, OAuth, app links) — `/open` + `/wait-app`

MCP flows often end where the app cannot continue alone: an order is created and the user has to
pay inside WeChat / Alipay / a browser. Use `POST /open` (or `scr open <uri|pkg/cls|pkg>`) to hand
the user over, then `POST /wait-app` (or `scr wait-app <pkg> [--gone] [sec]`) to wait for them to
come back:

- `{"uri":"https://…"}` — App Link / H5 payment page (default action `VIEW`; omit `package` so the
  system picks WeChat or the browser). A URI with no handler returns 404 instead of a fake `ok`.
- `{"uri":"weixin://dl/business/?t=…"}` / `{"uri":"alipays://platformapi/startapp?…"}` — deep links
  (required for H5 pages that only render inside WeChat/Alipay).
- `{"package":"com.tencent.mm"}` — plain app launch; `{"pkg":"wechat","label":…}` style label
  matching stays on `/launch`.
- `{"package":"com.tencent.mm","uri":"…"}` — set `setPackage`, i.e. "let WeChat handle this link".
- extras: `component` (explicit activity), `action`, `extras`, `flags` (`CLEAR_TOP`…), `chooser`,
  `waitMs` (0-5000, default 900).
- The reply carries real system foreground values: `before`, `after`, `changed`, `waitedMs` when the
  accessibility service is on; with it off the launch still happens (`ok:true`) and the fields are
  `null` with an explanatory `hint`. **Launching another app never required accessibility** -
  `startActivity` is an ordinary API; the a11y service is only an *observer* of other apps'
  foreground state (Android exposes no other way to read that for third-party packages).
- `wait-app` bodies: `{"package":"com.termux","until":"==","timeoutMs":60000}` (this app's package is
  `com.termux`, not `app.dsh.mobile` - that is only the Kotlin namespace); `until:"!="`
  waits for the app to leave (e.g. the user closed WeChat). Timeout is a 408 with the last known
  foreground and a "do not screenshot in a loop" hint.
- `wait-app com.termux` works **without** accessibility (it reads our own Activity lifecycle);
  waiting for another app (`com.tencent.mm`, browsers...) returns `503 a11y_unavailable` when the
  service is off, because that observation genuinely needs it.

Rules: do not screenshot/poll to confirm a payment page appeared — the foreground package is the
evidence. Never type passwords/payment details for the user; the payment step is theirs. After
`wait-app` says DSH is back in front, read the screen once (`scr dump`/`scr shot --save`) and
report whatever the app shows (paid / failed / still waiting).

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
- Accessibility is required only for reading/observing another app's UI and injecting input
  (`/screen`, `/tap*`, `/screenshot`, `/batch`, `/loop/run`, ...); opening apps, sharing, media,
  files and engine control work without it. Ask the user to enable `DSH Mobile Helper (optional)` in system
  settings (and allow restricted settings on Android 13+) only when an a11y capability is wanted.
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

No accessibility needed. Settings → **后台进度提示** picks the display mode: 关闭 / 通知 / 悬浮窗
(overlay is the default for installs that never chose). While a task runs in the background
ProgressHub holds a 15-minute PARTIAL_WAKE_LOCK, released on foreground/done; if the WebView sandbox
is reclaimed, onRenderProcessGone rebuilds it without restarting the engine.

* **Window**: collapsed = a ball (56 dp, measured 140x140 frame); expanded = a rounded card, 216 dp
  wide, body capped at 132 dp with internal scroll. Created with the calling Activity token (app
  context makes WMS mark it hidden when the app is not top); docks to the nearer **left/right** edge
  and keeps the user's vertical position; re-docks after expand/collapse; Android hides it over
  protected screens (system Settings, permission dialogs) by design. It is never auto-hidden while
  the app is in the foreground; returning to the foreground does not set the user-hidden flag.
* **Gestures**: tap the ball → expand (idle placeholder is `空闲 / 悬浮窗已开启`); tap the dot →
  collapse; long press the dot → hide; drag the dot → move. The body only scrolls — no tap or drag
  gesture on the text.
* **Expanded card is three sections**: ① the reasoning/tool chain — `[思考] …`,
  `[工具] 调用`, `[工具] 调用->完成` (or `->失败`), `[回复] …`, auto-scrolled to the newest line;
  ② the **accessibility / app-action chain** — what the service actually did (`读屏点击/滑动/按键`,
  `打开/跳转 <app>`), up to 4 lines, written by `POST /overlay {status,action}`, `POST /launch`,
  `POST /open` → `StatusOverlay.setA11yAction()`, or directly `window.DshNative.a11y(line)`;
  ③ session title + elapsed seconds (ticks every second while running, stops after done).
* **Text budgets**: 16000 chars end-to-end (plugin / ProgressHub / overlay), single reasoning block
  1400, tool line 420, reply keeps its last 4000 chars.
* **Source of truth is engine events, not DOM**. The host plugin
  (`assets/android-plugins/mobile-progress/index.js`) subscribes to the public DSH plugin API —
  `agent/assistant-stream` (`block-start/text-delta/reasoning-delta/tool-call-delta/block-end`,
  merged across attempts), `session/event` (`tool/call`, `tool/result`) and `agent/status`
  (`idle` forces a final `done` flush) — and POSTs a labelled chain with `X-DSH-Token` to the
  Android bridge `/overlay` (`kind/session/detail/elapsed`). 250 ms throttle, 2.5 s heartbeat while
  running; done/error snapshots are never deduplicated, so the card always ends on 已完成 · Ns.
  The browser half of the plugin is a placeholder only (`window.__dshMobileProgressPoke`).
* **Never auto-start after a normal exit**: a persisted `user_exited` flag (set by the
  notification's 退出 action and by `onTaskRemoved`) makes `EngineService.start()` and the
  accessibility keep-alive watchdog skip starting the engine; only a fresh MainActivity launch
  clears it. To start the engine, ask the user to open the app.
* `DshNative.progress(json)`, `DshNative.a11y(line)`, `DshNative.openSettings()` are the WebUI-side
  entry points; `POST /overlay {hide:true}` hides the window.
