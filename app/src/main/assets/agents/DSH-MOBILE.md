<!-- dsh-mobile app doc v75 — managed by DSH Mobile; rewritten on app updates -->
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
- **Agent bridge** HTTP API on `127.0.0.1:3083` (token in app-private `$PREFIX/.bridge-token`, header `X-DSH-Token`; the app never logs it and never writes it to shared storage).
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
| `pgrep -x` / `pkill -x` | list / kill by process name (Termux bootstrap; `psx`/`killx` no longer exist) |
| `loop` | screen-action loop helper (`loop --help`) |
| `fast on/off` | per-step "no thinking" mode while driving the UI |
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
  `POST /wait` (`/wait-text` is an alias of it), `POST /idle`, `GET /interference` (`scr dump`,
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
These are deliberately **not** behind the accessibility gate (so "open WeChat" must not answer `503 a11y_off` with the
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
  `/scroll-edge`, `/wait` (`/wait-text` alias), `/idle`, `/interference`, `/launch`, `/launch-intent`,
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
1. **WebView debugging is ON by default** (no flag, no restart needed). To opt out
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

### Screenshots and recording

- **App-internal screenshot (native)**: `appshot [--max N] [--out /path.png]` or
  `dsh-mobile appshot [out.png] [max]`. Kotlin-side `PixelCopy` captures the DSH Mobile window
  surface (WebView pixels included) - no accessibility, no CDP, no screen-capture permission.
  Requires the DSH Mobile UI to be in the foreground; the JSON returns the saved PNG path and
  the file can be read with the `read_image` tool. Do **not** rely on CDP/WebView screenshots:
  they can come out blank or stale.
- **System screenshot** (whole screen, incl. status bar and other apps): `shot --out /path.png`.
  Uses the accessibility service; without it the gate explains what to enable.
- **Screen recording (privileged only)**: `record start 60 /sdcard/Movies/demo.mp4`, `record stop`,
  `record status`. The gate is only deployed in ROOT / SHIZUKU mode (`screenrecord` needs shell
  uid); in NORMAL mode the command does not exist at all — do not probe for it, use `shot` instead.
- **Shell tap (privileged only)**: `scr tap --shell <x> <y>` likewise only exists in
  ROOT / SHIZUKU mode; in NORMAL mode it exits 3 with an explanation.
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
- **Payment links are launch-only.** `weixin://wxpay/…`, `alipays://…` and `https://*.alipay.com`
  links may land on the pay page, on the app home, or nowhere useful - the OS and the target app
  decide. `/open` only guarantees "the app was started". Never try to verify, scroll or click inside
  WeChat/Alipay to reach a payment screen; hand off, then `wait-app` for the user's return.
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

Every hand-off receipt also carries `stop:true` and `next:"none"` so a pipeline can
key off one field instead of parsing prose. A **share** task ends here too: `native share` is a
hand-off by design (`startActivity`); what the chat app does afterwards is its own job. If the
user wants delivery confirmed, ask them - do not screenshot, poll `/status`, or press Home to get
a "readable" dump (that last one changes their device state for your own uncertainty).

Reading a tree is only meaningful for the app that produced it: `scr dump` starts with
`fg=<package>` and `/screen?format=json` carries `foreground`. If it is not your target package,
do not interpret the nodes and do not act on them.

The canonical rules (receipts beat observations, no side effects for evidence, when verification
is legitimate) live in `$DSH_HOME/AGENTS.md` § "Hand-off actions: opening a page FOR THE USER needs
no verification" - read it there instead of keeping a second copy.

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
2. Drawer entries are buttons with these aria-labels: `新建会话`, `插件`,
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
- **First launch**: onboarding flow (language, permission mode), then the
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
device). Use these forms instead of trying to install another Node:

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

---

## Always-on skills and prompt-file protection

- `$DSH_HOME/skills/evidence-discipline/SKILL.md` ships in the APK; its **body is also inlined**
  into `AGENTS.md` under `## Always-on skill: evidence-discipline`, so every session starts with
  the evidence rules without waiting for a `skill` call. (The engine has no
  `alwaysPreloadSkills` knob; inlining is the L3-equivalent we can do without forking it.)
- `$DSH_HOME/AGENTS.md` is watched while the engine runs: external writes (agent `write`/`edit`)
  are reverted, the tampered copy is kept as `AGENTS.md.tampered-<epoch>` and an audit line is
  appended to `$DSH_HOME/log/instructions-audit.log`. Edits through the app's settings screen are
  app-sourced and pass. The agent must never write that file — see the integrity section of
  `AGENTS.md`.

## Environment & automation reference (moved out of AGENTS.md)

These reference sections used to sit in `$DSH_HOME/AGENTS.md`, which the engine injects
into every session with a 64 KiB budget shared with workspace instructions. They live here
so the injected file stays small; read them on demand (`dsh-mobile doc`).

### Prefer bounds over guessing icons

Icon-only buttons (search/back/more) often have no text. Do not guess centers: read
`GET /screen?format=json` (each node has `bounds` and `center`) or `GET /scene` (merged a11y+OCR with
bounds), then tap the reported center and use `expect` to verify.


**You are the cloud predictor and takeover operator; the local loop is the executor.** You choose the route, predict successor states, and define success conditions. The local loop performs matching → action → verification → retry. Compile each interpretation into a plan rather than remote-controlling the local executor step by step.

**Speed switch for UI work: the `fast_mode` TOOL.** For a multi-step accessibility task, prefer calling `fast_mode` with `action: "on"` before running the plans. The engine then skips reasoning on every step — the mechanical part is already planned, and measured performance was 3 steps / 0 thinking events. When UI work is finished, call `fast_mode` with `action: "off"` to return to normal conversation mode. `action: "status"` reports the current state and carries a 30-minute auto-expiry as a safety net. Turn it off before coding, writing, or analysis work, which benefits from reasoning.

**Choose tools by task size and evidence.**

- For a simple UI task with 1-2 actions and the target already on the current screen, use a small `loop run` plan.
- For a larger task, an unfamiliar page, or a request to go do something and report back, use `fast_mode` and batch the work into short `loop run` plans, reading each report before deciding on the next plan. A 2-4 step plan is a useful default; use one step when only one is reliable, or a longer plan when the route is well supported by evidence.
- The persistent planner daemon and its `parallel` gate were removed; `parallel` is not part of the toolset.
- `loop` provides local picking (exact 1–2 ms / semantic ~93 ms), target-state verification, retry, OCR fallback, and scene memory at about 1.7 s per step without a cloud round trip. Individual `scr tap` / `tap-text` calls remain available for a one-off action or a special case, but for multi-step target-state automation they make the model do the picking and cost a reasoning round trip per action.
- `scr batch` is useful for gesture-only sequences. It has no local picking, no verification (`ok:true` means dispatch only), and no retry, so `loop run` is generally better when target-state verification matters.

1. **Track the execution frontier.** Read context in this order: user goal state → executed facts → active/queued plan → screen → failure evidence. A state verified as successful is a fact. Work currently executing and FIFO steps already queued are promises. Normally add new work after those promises, unless new evidence requires a correction. An empty pool does not mean idle: a dequeued plan may still be running. Missing history does not imply that the first step is pending.

2. **Treat `loop run` as synchronous.** After it returns, continue from its report; there is no background executor. An in-flight snapshot is normally the full plan, not a live step number; its prefix may already be complete. A queued `verify` is a prediction, not completion evidence. A cached screen or event summary is not the latest full screen.

3. **Make evidence-based predictions.** Prefer 2-4 steps when the route is supported, one when only one step is reliable, and a shorter plan when the next page or dialog is unknown. When idle or taking over, use an anchor visible on the current screen for the first step. A successor anchor may be based on a page already seen or otherwise supported by evidence. Avoid guessed localized labels and stale coordinates. If there is no new gap, wait for new evidence with `scr changed` or `scr idle`; avoid enqueueing an action or wait that the local executor is already performing.

4. **Continue from reports.** `loop` reports expose actual progress in `steps[].status/detail`; use new results rather than restating the environment. For `status=ok`, inspect `detail[].verifyHow`: no `verify` or `none` proves only dispatch; `anyText` or `checkedText` proves the corresponding condition; `frameChanged` proves only that the screen changed. `failed`, `escalated`, historical `[skipped]`, and steps not returned do not prove completion. `yielded` means that a wait time slice was yielded; it is neither success nor failure, and later unreturned steps did not execute. After a yield, inspect the new screen before deciding whether another wait is needed. Avoid a duplicate dump when the next anchor or terminal evidence is already clear; read a new screen when it is missing or ambiguous, preferring `scr changed`.

5. **Define verification precisely.** Use `anyText` for text that appears only after the action; use `checkedText` to confirm an enabled toggle. Verification conditions are OR (any one condition passes). Choose text that identifies the post-action state; text already present on the source page, the foreground package, or a frame change is not a substitute when it would create a false positive. If the local `verify` is already waiting, an additional identical `noop` is normally unnecessary. A "download in progress" indicator is not proof that a download completed. The executor also accepts `foregroundPkg`, `frameChanged`, and `timeoutMs`; `waitSliceMs` bounds a `noop` wait slice, `useScreenEvents` controls event-summary use, and `useMemory` enables reuse of a verified same-scene plan.

6. **Take over using evidence.** Inspect `failedAt`, the failed step request and attempts, `detail` fields such as `act`, `verify`, `error`, `tap`, and `note`, plus `foreground` and `candidates`. A candidate pool may exist only for some failures; its absence does not mean the screen is empty. With `act=true` and `verify=false`, the action may already have taken effect: first check whether the successor state is present, then adjust verification or handle a new dialog. For a submission, download, or toggle, confirm the effect before repeating it. With `act=false`, change the visible anchor or route. Keep preceding successful steps; predictions after the failed step are no longer promises. When evidence is insufficient, perform only the necessary new screen read.

7. **Stop when the goal is proven.** Accept completion only from the current screen or explicit verification for the target. Do not infer completion from a plan, a dispatched action, or an empty pool. If the user asks to "open and view" an APK, the target app's system installation-confirmation screen is the terminal state; do not cancel it or continue installing. If the user asks to install it, that screen is only an intermediate state. Avoid cleanup actions unless they serve a goal-based reason.

8. **Use memory only for a verified same scene.** For synchronous reuse, add `"useMemory":true`; after a failure, use `false` for the repair plan so stale memory cannot override the repair.

For duplicate detection, compare page/target + action + verification result, not just button text; Back and scrolling may legitimately repeat on different pages.

## Reaching the WebUI from another device (LAN)

Upstream refuses `0.0.0.0` on purpose, and a naive port-forward breaks in four ways: no secure context (`crypto.randomUUID` missing), loopback-gated UI features, and same-origin checks on `/api`. The easiest path is to install OpenSSH (`pkg install -y openssh`) and forward over SSH (`ssh -L`) — that sidesteps all four; hand the user the resulting `http://127.0.0.1:3080` URL.

The app also has a built-in LAN proxy (Settings → 局域网访问 WebUI): it listens on its own port, polyfills `crypto.randomUUID`, rewrites the loopback gate and `SameSite=Strict` cookies, and forwards WebSocket upgrades, with a 6-digit code shown once when the user enables it. Prefer telling the user about that switch instead of hand-writing a proxy.

Only if a custom LAN URL is required, write a small node reverse proxy that listens on a new port, not 3080/3083. It must polyfill `crypto.randomUUID`, rewrite `isLoopbackHostname(pageLocation)` to a truthy call, set upstream `Host`/`Origin` to `127.0.0.1:3080`, forward WebSocket upgrades, and append the latest `?token=` read from `$PREFIX/engine.log`. Warn that this exposes an agent with full file and command access: use trusted networks only and set a password.

## Android powers: notifications and screen control

The documented environment already puts `scr`, `loop`, `notify`, and `say` on your PATH; run them directly. They authenticate to the app bridge internally. There is normally no need to hunt for the bridge token, curl port 3083, inspect wrapper scripts, or list `$PREFIX/bin`. If a command really is missing, install it with `pkg install -y <name>` (see Using Termux).

- `notify <message>` — system notification. Call it when a long task finishes, or when you need the user's attention while they are away.
- `scr dump` — compact screen read: `序号 text @x,y [flags] [role]`, one line per node (~6KB per screen); flags `c`lickable, `s`crollable, `e`ditable, `v`=checked, `o`=unchecked. **Role** marks the control kind: `[switch]` (a toggle — tapping flips it), `[row]` (a navigable row — tapping opens a page), `[button]`, `[edit]`, `[slider]`, `[list]`, `[scroll]`. Check the role before tapping: a `[row]` whose label looks like a setting usually opens a subpage where the actual `[switch]` lives — tapping the row flips nothing. `d:` means the label came from contentDescription; strip this prefix from target text. Keep titles/dialog text as well as controls because they identify page state. Keep unlabeled v/o nodes and their coordinates to associate a switch with its row label. Coordinates are current node centers and are valid for this snapshot only. `dump-click` is clickable/editable only and may omit state evidence; `dump-full` is legacy JSON and is best reserved for cases where essential fields are missing (roughly 15k tokens).
- **Screenshots**: use `scr shot --save`; it returns a file path instead of base64. Read the file with the `read_image` tool only when pixels are needed; dump text and OCR lines cover common cases. Old shots are auto-cleaned (last 12 kept).
- **OCR fallback**: when the accessibility tree exposes almost no text (ColorOS/OPPO browser, WebView, canvas-rendered apps), `scr dump` automatically appends offline OCR lines recognized from the screenshot (ML Kit, no network). The header then shows `ocr=Nlines/<ms>`; OCR lines are prefixed `o` (for example `o3 搜索 @540,2200 c`) and are valid tap targets/matching text exactly like normal nodes. Bridge `GET /ocr?force=1` returns raw OCR blocks with bounding boxes. Results are cached ~1.5s; avoid polling faster because screenshot capture is system-throttled. If a screen looks empty, OCR is already applied; do not conclude "nothing is on screen" from an empty dump alone.
- **Local fast layer**: bridge `GET /local/status` reports `modelReady`/`serverRunning` for the on-device embedding model; `POST /local/ensure` starts the 15MB model download + `llama-server` in the background. Once running, `loop pick` resolves semantically similar labels locally in milliseconds before any cloud round-trip.
- Commands: `scr tap <x> <y>` · `tap-text <t>` / `tap-desc <d>` · `long-press <x> <y> [ms]` · `swipe <x1> <y1> <x2> <y2> [ms]` · `key <back|home|recents|notifications|quick_settings>` · `input <text> [--target <hint>] [--append]` · `find <text> [--tap] [--back] [--max N]` (scroll until found) · `idle [ms]` (until the UI settles; also reports the foreground package) · `launch <pkg|label>` · `open <uri|pkg/cls|pkg>` (jump to another app/app link; also takes `intent://…` and rewrites `https://*.alipay.com` payment links to `alipays://`) · pay flow is **launch-only**: `scr open "weixin://wxpay/bizpayurl?pr=…"` / `scr open "<https alipay link>"` only starts WeChat/Alipay — landing on the pay page is **not** guaranteed, so never try to verify, scroll or click inside the payment app; just `scr wait-app <com.tencent.mm|com.eg.android.AlipayGphone> --gone 60`, then re-check the order via MCP. Never poll screenshots, never open the pay link twice (fork repo: docs/PAYMENT-LINKS.md) · `wait-app <pkg> [--gone] [sec]` · `pkg` · `shot [--max N]` · `status "<text>" [--action <desc>]` (writes the overlay's **accessibility/app-action chain** — the
  section under the tool chain) · `status-hide`.
- **`scr changed`** checks "did the screen move?" in milliseconds. The event stream feeds a small what-changed log (window switches, appearing/updating texts); calling it returns and consumes the log, so prefer `scr changed` over a full `dump` whenever only a change signal is needed. `scr macro <back|home|recents|notifications|quick_settings|open-settings|sleep>` runs a deterministic common action in one command.
- **`scr batch <json-file|->`** runs a gesture-only sequence in one turn. For multi-step target-state automation, prefer `loop run`. Step types and their exact field names are: `tap` (`text` | `desc` | `x`+`y`), `long_press` (`x`,`y`, optional `durationMs`), `swipe` (`x1`,`y1`,`x2`,`y2`, optional `durationMs`), `input` (`text`, optional `append`, `target`), `key` (`action`), `scroll_find` (`text`, optional `tap`,`back`,`maxSwipes`), `wait` (`text`, optional `gone`,`timeoutMs`), `idle` (optional `timeoutMs`), `sleep` (`ms`). The endpoint validates field names; a failing step aborts the batch unless it carries `"optional":true`. The response reports every step plus the fields it actually received.
- For raw `scr` gestures/batches, `ok:true` means dispatch, not the expected UI state. Verify important changes with a batch `wait`, `scr changed`, or one necessary dump. For `loop`, read each step's `detail` verification instead.
- After an action errors or a wrong target is tapped, usually restore or confirm a recognizable state with BACK / `back_to` / re-opening the target screen, re-read the screen, and retry with a sharper anchor. If the same failing step has failed twice, change the approach (scroll first, dismiss the dialog, use a different alias, or choose another route). For side-effectful actions, confirm whether the action already took effect before repeating it.
- After accessibility or UI-automation work, normally bring DSH Mobile back to the foreground with `scr launch com.termux` (this app's package; `com.termux`) or the launcher/recents so the user sees this conversation. If the user's goal is to leave another app open or view its terminal screen, keep that app in the foreground instead.
- If the available evidence still cannot identify the target, say so plainly and report what the page actually shows ("I could not find X here; the page currently shows A / B / C") instead of guessing. Do not claim success from a generic page marker — verify the specific identity requested (the exact name / price / state). A confident-but-wrong answer is worse than an honest miss.
- Avoid hard-coding dialog labels: system dialogs are localized by the *system* locale (允许/确定 vs Allow/OK). Dump first and match what is actually there.
- `scr interference [since-ms]` — checks whether the user grabbed the phone mid-flow. Record `before=$(( $(date +%s) * 1000 ))` (portable arithmetic form: don't rely on GNU-only `%3N`, the Android `date` in /system/bin ignores it), run the flow, then check. `"interfered":true` means a real touch happened (your own injected gestures are excluded) — stop, re-dump, and ask the user. Window changes alone are not interference. Taps carry small randomized offsets so they do not look robotic; avoid rapid taps during an animation because they can land on the wrong target.
- **Speed**: suggest that the user turn OFF thinking mode for UI automation sessions, or use the `fast_mode` tool.

### Accessibility readiness: "enabled" is not "connected"

Android keeps two independent states for an accessibility service (official docs:
`AccessibilityManager.getEnabledAccessibilityServiceList`): the user has granted it in system
settings, and the service actually bound to the app process. Recent builds report both:

- `GET /status` → `permissions.accessibility` (system-enabled) and
  `permissions.accessibility_connected` (bound). Only both true means actions work.
- Screen/gesture/vision endpoints return a structured 503 instead of a bare error:
  `{ok:false,error,accessibility_enabled,accessibility_connected,accessibility_listed_but_disabled,retryable,action}`.
  The app also waits ~1.5s for a transient bind before answering.

How to react:

- `retryable:true` → the service is enabled but not bound yet: wait ~2s and retry once.
- `retryable:false` and `accessibility_listed_but_disabled:true` → it was granted before but Android
  disabled it (force-stop, app update, some ROMs). Do not loop. Tell the user, and offer to open the
  page: `POST /activity {"name":"accessibility-settings"}`. Apps cannot enable accessibility
  themselves - this is a platform rule, not an app limitation.
- `retryable:false` otherwise → never granted; same settings page.
- Probe once at the start of a UI task, not before every action; the endpoints already retry the
  transient case internally.

#### Which capabilities actually need it

Judge by the mechanism, not by "this feels like automation": does the capability have to **read
another app's UI tree/pixels, inject input into another app, or read accessibility events**?

Need it (with the service off they answer the structured 503):

- read the UI tree: `GET /screen`, `GET /screen-changed`, `GET /diff`, `GET /scene`,
  `POST /wait` (`/wait-text` is an alias of it), `POST /idle`, `GET /interference`;
- inject input: `POST /tap`, `POST /tap-id`, `POST /gesture`, `POST /key`, `POST /input`,
  `POST /scroll-find`, `POST /scroll-center`, `POST /scroll-edge`, `POST /pick`,
  `POST /vision/tap`, `POST /vision/drag` (and the `scr` gate wrappers behind them, e.g.
  `scr tap`, `scr find`, `scr key`, `scr input`, `scr idle`, `scr interference`);
- screen pixels through the a11y API: `GET /screenshot`, `GET /frame-diff`, `POST /frame-wait`,
  `POST /vision/crop`, `POST /vision/track` (`shot`/`scr shot` capture the whole screen this way);
- the a11y notification cache: `GET /notif`;
- composite drivers that run the above: `POST /batch`, `POST /act`, `POST /loop/run`, and
  `loop ...` on the CLI.

Never need it (keep working; never ask the user to enable accessibility for them):

- launch / hand-off: `POST /launch`, `POST /launch-intent`, `POST /open`, `POST /activity`,
  `POST /a11y/enable` (it only opens the settings page), `scr launch`, `native open`. `/open`,
  `/activity` and `/overlay` may *use* the service when it is on (to verify a switch / dismiss an
  a11y overlay window) but never block on it - the start still goes through `startActivity`;
- wait for **our own** app: `POST /wait-app com.termux` / `scr wait-app com.termux` (Activity
  lifecycle). Waiting for *another* package does need the service: `wait-app <other-pkg>` /
  `POST /wait-app` with a non-`com.termux` package answers `a11y_unavailable` when it is off;
- sharing / files: `POST /open/file`, `POST /open/folder`, `POST /open/share`, `POST /share/text`,
  `POST /export`, `native share`, `native share-text`, `native open-file|open-folder`;
- media, TTS, notifications: `POST /media/play`, `POST /media/pause`, `POST /media/resume`,
  `POST /media/stop`, `GET /media/status`, `say`, `notify`, `POST /notify`, `POST /say`,
  `POST /overlay` (progress overlay; it may fall back to an a11y window but does not require it);
- app/engine control: `GET /status`, `GET /settings`, `GET /apps`, `GET /process`, `GET /diag`,
  `GET /plugin/list`, `POST /plugin/install`, `POST /engine/restart`, `/pty/*`, `/runtime/*`,
  `native status|settings|set`, `dsh-update`;
- our own window: `GET /app-screenshot` (`appshot` / `dsh-mobile appshot`, PixelCopy - no a11y,
  no CDP);
- memory bookkeeping: `GET /memory/list`, `GET /memory/stats`, `POST /memory/confirm`,
  `POST /memory/clear`;
- conditional: `GET /ocr` (privileged `screencap` via Shizuku/root works without a11y; model
  list/install does too), `POST /memory/lookup` / `POST /memory/store` (skip the screen read when
  the caller passes `dump`).

#### When it is off: degrade per capability, never globally

1. **Do the ordinary part first.** Launching the app, opening a URL/file, sharing, playing media,
   notifying, TTS and reading our own UI all keep working - do not stall the whole task or report
   "blocked" because accessibility is off.
2. **For the a11y-dependent step, do one of two things**: (a) ask the user to enable
   `DSH Mobile Helper (optional)` / zh `DSH Mobile 辅助（可选）` (offer `POST /a11y/enable` or
   `native open accessibility-settings`; apps cannot enable it themselves), or (b) route around it - ask the user to do the tap/read
   themselves and report back, use `POST /launch` + `POST /wait-app com.termux` for the app
   hand-off, `GET /app-screenshot` for our own UI, `POST /notify`/`say` for output.
3. **Say which capability is missing.** Name the endpoint/command and what needs the service
   (e.g. "reading another app's screen needs accessibility; launching WeChat does not"). Never
   describe a plain capability as an Android limitation.
4. **Do not retry a non-retryable a11y error, and do not re-probe before every action.** One
   `GET /status` at the start of a UI task is enough; the classification above and the details in
   `$DSH_HOME/DSH-MOBILE.md` is on disk; the a11y classification table is `docs/A11Y-SCOPE.md` in the fork repo (github.com/hazukieq/DSH-Mobile-with-Termux).

**Anti-fraud-guarded apps: the default answer is NO.**
WeChat（微信）/ QQ / banking and financial apps run real-time device-integrity checks (accessibility detection, gesture histograms, root detection): automating them gets accounts **banned**, sometimes permanently, and a mistimed tap can move money. Douyin（抖音）/ 小红书 and heavy-DRM apps are nearly as hostile. Refuse by default; for financial apps it is a flat no. If the user insists after you state the ban risk in one plain sentence — and only then — they must be watching live (never unattended), and the ban, account loss and any ToS violation are **theirs**; say that out loud before starting. Never help make injected input undetectable — that crosses from automation into fraud.

## This app: internals and native capabilities

You are inside **DSH Mobile** (`com.termux`): the dsh engine plus a Termux userland in one
APK. The full reference ships at `$DSH_HOME/DSH-MOBILE.md` — read it (`dsh-mobile doc`)
before deep app work. Quick map:

- `$PREFIX` = `<files>/usr` (Termux bootstrap; adapted engine at
  `$PREFIX/lib/node_modules/@deepseek-ai/dsh`). `$DSH_HOME` = `<files>/dsh-home`
  (profiles, sessions, workspaces, AGENTS.md).
- Native gates already on PATH: `notify`, `scr` (dump/tap/tap-text), `say`,
  `loop`, `fast`, `dsh-update`, `trash-cli`, `shot` (system screenshot), `appshot`
  (app-window screenshot), `record` (screen recording; deployed only in ROOT or SHIZUKU
  mode only), `native` (app control/debug: status, settings read/write, open native pages),
  `media` (native audio playback: `media play <path|url> [title] [artist] [--loop]`, pause/resume/
  stop/status - system audio hardware + audio focus, no Termux audio stack needed).
- Gate scripts are real files (not symlinks) and are made executable (0755) on every app
  start; the app also replaces bootstrap toybox/busybox symlinks that would shadow them. If a
  gate reports "command not found" or "Permission denied", run `ls -l "$PREFIX/bin/<name>"`
  and include that output in your report - do not work around it with `/system/bin/*`.
- Sharing / forwarding is a first-class action: `native share <path>` (files) and
  `native share-text "<text|link>"` open the system share sheet so the user can forward to
  WeChat/QQ/mail; `POST /open/share` and `POST /share/text` are the bridge equivalents.
  When the user names the target ("分享给微信", "用网易云打开", "播放", "阅读"), list handlers with
  `native apps send|view <mime>` and pass `--to`/`--app <label|package>` to deliver directly,
  without the chooser; ambiguous or missing targets come back as candidates, so ask the user.
  Incoming
  shares from other apps (ACTION_SEND) are copied to `$DSH_HOME/inbox/` and prefilled into the
  open conversation's composer; no automatic send.
- Opening files with other apps: `native open-file <path>` / `native open-folder <path>` /
  `native share <path>` (or `POST /open/file|folder|share`). Reveal works for `$DSH_HOME` paths
  too: the app ships a SAF DocumentsProvider (root "DSH Mobile") that the system file manager
  can browse, so private folders are deep-linked in DocumentsUI instead of failing. The WebUI's own "open in app" and
  "reveal in folder" buttons use the same path now, so Android no longer reports "no desktop".
  Receipts are hand-offs (`opened` means the app actually left the foreground); no screenshot
  verification.
- Native control one-liners: `native status` (foreground + resumed activity, network, battery,
  memory, storage, permission state), `native settings` / `native set page_scale=100`,
  `native open settings|terminal|main`. Prefer these over `am start`, which is silently blocked
  for background activity starts; `native open` reports the resumed activity so you can tell
  whether the window really opened.
- Screenshots: `appshot --out <png>` (or `dsh-mobile appshot`) is the **native** capture of the
  app window via PixelCopy - use it for evidence about the DSH UI (no accessibility, no CDP,
  reliable WebView pixels). `shot --out <png>` captures the whole screen through the
  accessibility service. Do not use CDP screenshots: they can be blank/stale.
- The UI map (top toolbar, the capsule handle that restores it, native Settings, mobile drawer,
  right pane, native terminal tabs/keys) is documented in `$DSH_HOME/DSH-MOBILE.md`, section
  "App UI map". On tablets the mobile UI layer is portrait-only - in landscape the stock desktop
  WebUI is used, so the mobile app bar/drawer will be absent there. Read it before explaining the app to the user or automating it.
- The rest of the native API (60+ endpoints: screen, screenshot, gesture, key, input, launch,
  launch-intent, ocr, vision, memory, notif, plugin list/install, diag, pty, runtime) lives on
  `127.0.0.1:3083` and is reachable as
  `dsh-mobile bridge <GET|POST> <path> [json]` (it handles the bridge token). Never hand-roll
  an Android intent or a shell workaround when the bridge already exposes the capability.
  `POST /launch` and `/launch-intent` accept `{"component":"pkg/cls"}` (+ optional `action`,
  `data`, `extras`): an unknown component now returns `ok:false` instead of a fake success, and
  a real success carries `backgroundLaunchMayBeBlocked` because Android may refuse background
  activity starts.

## Debugging the Web UI over CDP - mandatory

Never guess WebUI DOM/CSS from screenshots; drive the real page. **WebView debugging is ON by
default** (`GET /process` → `webview_debug:true` + `devtools_socket`), so no
flag/restart is needed; `dsh-mobile cdp off` (opt-out file) + restart disables it.

0. `dsh-mobile cdp doctor` first when anything looks off: it prints OK/FAIL for the process,
   the bridge `/process` answer, socket discovery, devtools HTTP and a test `Runtime.evaluate`.
1. `dsh-mobile cdp on` only removes the opt-out and legacy flag; a restart is needed only if
   the opt-out had been active.
2. `nohup dsh-mobile cdp serve 9222 >/tmp/cdp.log 2>&1 &` - bridges the WebView's abstract
   devtools socket to `127.0.0.1:9222`; `dsh-mobile cdp status` shows the socket and targets.
   Socket discovery is automatic: bridge `/process` → `/proc/net/unix` → abstract-socket scan,
   with `DSH_WEBVIEW_SOCKET` as an override; `cdp status` shows which tier was used.
3. Inspect and drive it: `curl -s 127.0.0.1:9222/json/list`,
   `dsh-mobile cdp eval 'document.title'`, `dsh-mobile cdp reload`. From the host you can
   instead `adb forward tcp:9222 localabstract:webview_devtools_remote_<app pid>` and use
   Playwright `connect_over_cdp("http://127.0.0.1:9222")` for real clicks and screenshots.
4. `dsh-mobile cdp off` when finished (restart to close the socket).

Renderer console errors also appear in logcat under the `chromium` tag.

Background progress: an injected `mobile-progress` plugin posts the current thinking /
tool step through `DshNative.progress(json)`; native `ProgressHub` shows it as an ongoing
notification or floating window (settings → 后台进度提示). The layer works without accessibility -
overlay uses SYSTEM_ALERT_WINDOW first and only falls back to the accessibility window. Never use
`POST /overlay` as the only progress channel any more; it is merged into the same hub.
Settings → 后台保活 requests battery-optimisation exemption and explains vendor limits; while a
task runs in the background ProgressHub holds a PARTIAL_WAKE_LOCK (15 min, renewed) to reduce
Doze freezes - released on foreground/done. WebView renderer reclaim is recovered by rebuilding
the WebView only (the engine keeps running).
Normal exit is final: 退出 from the notification or swiping the app away sets a persisted
`user_exited` flag; `EngineService.start()` and the accessibility keepalive skip while it is set.
Only the user opening the app again clears it - never try to auto-start the engine after an exit.

Node/pnpm on device: `dsh` is not on PATH - run
`node "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" ...`; pnpm is not on PATH either,
so use Corepack's entry `node "$PREFIX/lib/node_modules/corepack/dist/pnpm.js"` (or a
`$HOME/bin/pnpm` wrapper) and prepend `$HOME/bin` to PATH for plugin installs. Never replace the
bundled `$PREFIX/bin/node`; experimental bundles go through the WebUI `pluginManager` RPC, not
pnpm.

## Driving the WebUI correctly (CDP)

The mobile UI replaces the engine app bar; the engine's "打开侧边栏" button is off-screen. Use:

1. `dsh-mobile cdp drawer` opens the drawer **idempotently** (the 菜单 button is a toggle — clicking it
   twice closes the drawer, and then `click-text` matches page text instead of a nav item).
2. Drawer buttons by aria-label: 新建会话 / 插件 / 自动化任务 / 记忆系统 / 搜索会话 /
   视图选项 / 添加工作区; the account menu is `button[aria-label="账号菜单"]` at the drawer bottom.
3. Account menu items are `[role=menuitem]`: 设置 / 意见反馈 / 退出登录 ->
   `dsh-mobile cdp click-text 设置 --exact`; then `dsh-mobile cdp wait '[role=dialog]'`.
4. Settings is the `[role=dialog]` overlay (账号与余额 / 通用设置 / 模型 / 内置插件 / Agent 预设 …).

Rules:
- **Tap channel**: `scr tap <x> <y>` 走无障碍 `dispatchGesture`。少数 App（GitHub、Play 商店账号抽屉）
  把界面标成 `accessibilityDataSensitive`，Android 14+ 会静默吞掉注入（回执 ok、页面不动）。
  自检：同一页面 `scr dump` 若只有 OCR、节点数为 0，基本就是这个原因；本 App
  XML 声明了 `isAccessibilityTool="true"`（改 XML 后必须去系统设置重开无障碍服务才生效）。
  仍被吞时用 `scr tap --shell <x> <y>`（Shizuku/Root 的 `input tap`，需提权模式；否则会明确报错）。
  OCR 坐标与无障碍坐标出现系统性偏移时（换屏/缩放/letterbox），跑一次 `scr calib` 看拟合与 MAE，
  满意再 `scr calib --apply` 落库（按截图像素尺寸分桶，横竖屏互不影响；`--clear` 清空）。
- **Click channel**: `cdp click` / `cdp click-text`
  默认走 **DOM click**（`el.click()`，交给 React 处理）。原因不是选择器问题，而是
  **Android WebView 不处理 CDP 合成输入**：Chrome 里 `Input.dispatchTouchEvent` 会交给
  SyntheticGestureController 做底层注入，WebView 没有这条路径，所以调用回执 ok、页面收不到
  事件（Chromium issuetracker 35819848 / 35824183 有同类讨论）。实测把
  `Emulation.setTouchEmulationEnabled` / `setFocusEmulationEnabled` / `Page.setWebLifecycleState(active)`
  全部打开，触摸仍然不生效——可以确定不是「页面没聚焦」。
  需要真手势（画布/地图/自定义 pointer）时：用无障碍手势 `scr tap <x> <y>`（真实系统输入），
  或加 `--touch` 试 CDP 通道（可能是空转）。
  回执里的 `input` 字段会写 `dom-click` / `emulated-touch` / `emulated-touch+dom-click`（兜底）。
- `cdp drawer` 会**自证**：点完轮询 `button[aria-label="收起侧边栏"]`，只有抽屉真出现才报
  `opened`；失败会先自动用 DOM click 兜底，再失败才 `ok:false, drawer:not_opened`（此时才需要
  怀疑 App 不在前台 / WebView 在重载）。
- `dsh-mobile cdp drawer` / `cdp close` are the coordinate-free way in/out of the drawer
  (`Escape` is not handled by this UI). `click-text` prefers a matching `aria-label` over a
  longer text blob, so a chat message quoting the label no longer steals the hit.
- Hashed class names change per build (`hHd-Xa_*`, `VOzbGW_panel`) - anchor on aria-label / role /
  text instead.
- `element.click()` **能**驱动这个 UI（开抽屉、点导航项都有效，所以 `click`/
  `click-text` 默认就用它）；synthetic KeyboardEvents 不行，打字仍用 helper 的 `type` / `key`。
- Verify after each step (dialog present, text changed, or `appshot`); an element with x<0 means
  its panel is not open yet - open it first. Two failures -> ask the user which entry they mean.
