# Terminal

> DSH Mobile ships a real PTY terminal: multiple sessions, scrollback, CJK wide characters,
> true color — with exactly the same environment as the agent's own shell tool. The runtime is
> built from two layers: a Termux bootstrap and an adapted dsh engine. `pkg` / `apt` / `dpkg`
> are the real Termux implementations.

## 1. Terminal

### Entry points

- **Launcher icon**: `DSH Terminal` (a second LAUNCHER entry, separate from the main app icon)
- **Settings → Terminal**

### Architecture

```
soft keyboard / extra-keys bar / paste
        │  sendText / sendKey / sendControlChar
        ▼
   TerminalView  ──Canvas rendering (monospace, 256/truecolor, blinking cursor)──▶ screen
        │ attach(session)
        ▼
   TerminalSession ── reader thread ──▶ TerminalEmulator (VT100/xterm state machine + screen buffer)
        │ write()/resize()                  │ throttled onChanged
        ▼                                    ▼
   dsh_pty.c (JNI) ── fork/execve + PTY ──▶ child (zsh / bash)
```

- **PTY layer**: `dsh_pty.c` keeps a 16-slot session table. The engine's existing
  `Pty.nativeForkPty` symbols stay unchanged (they delegate to slot 0), so the engine side
  is unaffected.
- **VT emulator**: GROUND / ESC / CSI / OSC state machine, incremental UTF-8 decoding across
  chunks, alternate screen (DECSET 1049, required by vim/top), scroll regions (DECSTBM),
  insert/delete line and column, autowrap and delayed wrap, cursor save/restore, OSC 0/2
  title, BEL. Colors: 16 / 256 (`38;5;n`) / true color (`38;2;r;g;b`).
- **CJK wide characters**: two cells per wcwidth rule, the second cell marked as a
  continuation; a wide character in the last column wraps instead of overflowing.
- **Scrollback**: 5000 lines on the main screen; new output does **not** yank the view back
  to the bottom while you are scrolled back (it only follows when already at the bottom).
- **Sessions**: multiple concurrent sessions, switched from the top tab strip, closed by
  long-pressing a tab; rotation or font-size changes do not restart the processes. The app
  allows up to 7 terminals; the engine side (WebUI sidebar) has its own limit of 6.

### Interaction

| Action | Where |
|---|---|
| Esc / Tab / ^C / ^D / ^L / ^Z / Home / End / PgUp / PgDn / arrows | extra-keys bar at the bottom (horizontally scrollable) |
| Font size ± | menu ⋮ → Font size ± (8–26 sp, persisted) |
| Copy all output | menu ⋮ → Copy all output (scrollback + current screen, trailing blank lines collapsed) |
| Paste | menu ⋮ → Paste (system clipboard, bypasses the IME) |
| Scroll back | swipe **down** to see older output, swipe **up** to return to newer output; while scrolled back the top-right shows `↓ lines` |
| Jump to bottom | menu ⋮ → Scroll to bottom |
| New session | top-bar ＋ |

- **Back key / top-bar ←**: while scrolled back, it returns to the bottom first; a second
  press exits the terminal.
- **Soft keyboard**: while the keyboard is open the system consumes the back key once to
  dismiss it (same as system terminals); the second press is handled by the app.
- **Input**: the IME goes through `commitText`; hardware keyboards and
  `adb shell input text` arrive as `KeyEvent`s — printable characters are taken from
  `event.unicodeChar` and `ACTION_MULTIPLE` `characters` and written straight to the PTY.

### Environment: identical to the agent's

Sessions use `$PREFIX/bin/zsh` when it is installed (`pkg install zsh`), otherwise the engine's
own `$PREFIX/bin/bash -i`. The environment comes directly from `EngineConfig.buildEnv()`:
PATH / LD_LIBRARY_PATH / PREFIX / `GIT_EXEC_PATH` / `PYTHONHOME` and so on are the **same
source** as the agent's shell tool. There is no split where "git works for the agent but not
in the terminal"; conversely, replaying an agent command in the terminal is a verbatim replay.

If the runtime has not been installed yet, the session falls back to `/system/bin/sh` (toybox),
which still works, and the title is marked `Android (toybox)`.

## 2. Runtime: two layers

| Layer | Contents | Source |
|---|---|---|
| Termux base | bash / apt / dpkg / pkg / coreutils / sed / tar … | `assets/bootstrap-aarch64.zip` extracted into `<files>/usr`; `SYMLINKS.txt` recreates symlinks |
| dsh engine | node + the adapted `lib/node_modules/@deepseek-ai/dsh` closure + ripgrep + bash | `assets/engine-dsh-aarch64.zip` (bundled in the APK) |

- `$PREFIX` is `/data/data/com.termux/files/usr` (applicationId = `com.termux`).
- The engine's `bin/node`, `bin/rg` and `bin/bash` are app-managed wrappers; the real binaries
  and their libraries live in `$PREFIX/lib/dsh-engine/`. If something overwrites them, the
  self-healing logic restores the wrappers.
- **Never install `@deepseek-ai/dsh` with npm**: the upstream package cannot be installed on
  Android/Termux; this adapted runtime is the whole point of the project. npm would also pull
  newer nested dependencies that overwrite the adapted closure.
- **Do not install/upgrade `nodejs` or `ripgrep` with `pkg`**: that replaces the wrappers above.

### Startup order

- **Synchronous**: extract the engine layer (one-off, roughly 30 s), then start the engine
  immediately so the UI is usable.
- **Background**: extract the Termux bootstrap (apt/dpkg/pkg/coreutils), then write the apt
  sources to a China mirror.

Between the first launch and the bootstrap finishing, `pkg` / `apt` are not available yet
(check whether `$PREFIX/bin/apt` exists); the engine itself does not depend on them.

### apt sources

`BootstrapInstaller` writes the Tsinghua TUNA mirror for the Termux main repository into
`$PREFIX/etc/apt/sources.list` (only when the file is missing or still points at the old
default). Users can edit it afterwards.

## 3. Package management: apt / dpkg / pkg

The Termux base is the official bootstrap, so the package manager can really install,
remove and resolve dependencies without root:

```
pkg update
pkg install -y python git clang make openssh vim ffmpeg
pkg search <keyword>
pkg list-installed
apt install -y <package>      # apt / dpkg work directly as well
```

- Ordinary packages are fine, and `pkg upgrade` works for them. Do not remove `bash`,
  `termux-tools` or `libandroid-support` (the shell and Termux scripts depend on them).
- **`pkg`/`apt` refuse to run in root mode**, and root-owned files break later app use: switch
  to Normal/Shizuku mode, install, then switch back.
- **Cannot coexist with official Termux**: same package name, different signature. Uninstall
  Termux before installing this APK. When upgrading from the old package name
  (`app.dsh.mobile`), `LegacyDataMigrator` rewrites the old paths inside dsh-home text data to
  the current package name and rebuilds workspace directories from `workspace.json`
  (idempotent).

## 4. Engine hot-update: `dsh-update`

The runtime has two layers, and **hot-update replaces only the engine layer**:

```
<files>/usr/lib/node_modules           <- replaced
<files>/usr/bin/node, bin/rg            <- replaced
<files>/usr/.dsh-engine-version         <- replaced
(apt/dpkg/bash/coreutils and the rest of the bootstrap are untouched)
```

### Commands

```
dsh-update status                        # local dsh/node/engine versions, staging and rollback state
dsh-update check [--manifest URL]        # local vs upstream and adapted engine on GitHub (never npm)
dsh-update pull [version] [--apply]      # download an engine tarball, verify sha256, stage it
dsh-update stage <url|path> [sha256]     # stage a specific bundle (offline/internal testing)
dsh-update apply                         # back up -> swap in -> restart -> health check (auto-rollback on failure)
dsh-update rollback                      # restore the previous engine
dsh-update restart                       # restart the engine only
```

- The version is judged by `@deepseek-ai/dsh`, not by the APK version.
- `apply` moves the old layer to `$PREFIX/.engine-prev`, swaps in the staged one, restarts the
  engine and runs a health check against `http://127.0.0.1:3080/` (120 s); an unhealthy tree
  rolls back automatically.
- Only the engine layer changes, so updating the engine never breaks `pkg`/`apt`.

### Feed and CI

- Default feed: this repository's release
  `dsh-engine-latest/engine-manifest.json`
  (`{dsh_version, engine_version, assets.<arch>.{url,sha256,size}}`).
- `check` only talks to GitHub, along two paths: upstream `deepseek-ai/deepseek-harness`
  releases tagged `dsh-v*`, and whether this repository has published an adapted engine bundle
  with `engine-manifest.json`. When it has not, it reports `feed: unavailable` and states
  plainly that upstream has a newer version while the adapted bundle is not published — it
  never pretends npm is the feed.
- `.github/workflows/dsh-runtime.yml` produces `engine-dsh-*.tar.gz` plus the manifest,
  publishing to the rolling release `dsh-engine-latest` and to the immutable
  `dsh-runtime-<dsh version>` archive.

## 5. WebUI sidebar terminal (node-pty bridge)

The terminal in the dsh WebUI sidebar (in-app WebView, `127.0.0.1:3080`) needs two things: an
Android implementation of node-pty and a process inspector that accepts android. The real PTY
stays in the app process:

```
engine (node)                  app process
node-pty shim  ──HTTP──▶ AgentBridge /pty/{open,write,resize,kill,close,read}
                                └─▶ libdshpty.so (fork/exec + /dev/ptmx)
```

- **Shim**: `assets/runtime-overlay/lib/node_modules/node-pty/`, copied into the engine tree by
  `EngineConfig.applyRuntimeOverlays()` on every engine start (idempotent; only when the target
  is not a native node-pty). `spawn()` keeps **synchronous** semantics: a child node process
  performs one HTTP open and the parent waits with `execFileSync` (the engine reads
  `terminal.pid` immediately after spawn returns).
- **Bridge**: one reader thread and one 512 KB ring buffer per session (absolute `seq`);
  `GET /pty/read?id&seq&timeout` long-polls for incremental bytes (base64); once the process
  exits the response carries `exit_code/signal`.
- **Process inspector**: `EngineConfig.patchEngineForAndroid()` scans
  `@deepseek-ai/dsh-subprocess-local/lib/*.js` by **content anchor** on engine start (the
  filename carries a content hash, so it cannot be hard-coded) and rewrites
  `if (platform === "linux")` to `linux || android`. Android has `/proc`, so the inspector
  works. The patch is idempotent and re-applied to the new files after a runtime upgrade.
- **Default shell**: `buildEnv()` injects `SHELL=$PREFIX/bin/bash`, so the sidebar gets the
  engine's bash (with the app's PS1, rc and completions).

### Implementation constraints (C layer)

| Constraint | Why |
|---|---|
| All forks happen on the resident `pty-spawner` thread | `PR_SET_PDEATHSIG` is tied to the **thread**, not the process |
| The child must not `malloc` / `opendir` before exec | multi-threaded ART would crash it in libsigchain; `close_inherited_fds` uses a `close(3..1023)` loop |
| The child only resets the "ordinary signal" allow-list | crash signals are reset automatically by execve |
| Fall back to dsh-home and log when `cwd` does not exist | otherwise the child `_exit(125)`s and the terminal closes immediately |

## 6. Limits and known constraints

- Scrollback does not reflow: `resize` keeps content and crops/pads; no reflow (same as most
  mobile terminals).
- Only bionic binaries run in the terminal; glibc programs are not usable directly.
- `pkg` / `apt` depend on the bootstrap finishing; it runs in the background after first
  launch — the shell works during that window, package management does not.
- Engine hot-update only swaps the engine layer; it does not touch the APK, `dsh-home`,
  `workspaces` or existing sessions. A rollback point survives one `apply`; afterwards
  `.engine-prev` is cleared.
- The WebUI sidebar terminal depends on a healthy engine (`:3080`) and on the overlay being
  applied.
- Same package name as official Termux but a different signature: they cannot coexist.

## 7. Troubleshooting

| Symptom | Action |
|---|---|
| `bash: not found` in the terminal | runtime not installed yet; the title shows the `Android (toybox)` fallback |
| Garbled output | make sure `TERM=xterm-256color` (injected by the app) |
| `pkg` / `apt`: not found | the bootstrap has not finished extracting; retry later and check `$PREFIX/bin/apt` |
| WebUI sidebar: `terminal inspection is unsupported on platform android` | the engine patch did not apply: restart the engine and confirm the runtime version |
| WebUI sidebar: `node-pty unavailable in this Android build` | the overlay was not applied: restart the engine so `applyRuntimeOverlays()` runs again |
| `dsh-update check` shows `feed: unavailable` | this repository has no adapted engine bundle for that dsh version yet; wait for CI or upgrade with an APK that contains the new engine |
