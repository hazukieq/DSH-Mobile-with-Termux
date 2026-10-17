# Terminal + Linux environment

> Added 2026-10-18. Gives DSH Mobile the two things it did not have: a **real terminal**
> and an **optional Linux userland** (proot distros). The point is to stop bionic-snapshot
> boundaries from deciding what the agent can do — and to let the user see and take over
> what the agent is running.

## Why

The original design ships the Node.js runtime and toolchain as bionic binaries inside the
APK (install and go), at three costs:

1. **No terminal UI** — the only shell is the agent's `bash -c` tool: invisible, non-interactive;
2. **Hard-coded Termux prefixes** — `ExtensionManager.rewriteTermuxPaths` deliberately skips
   ELF binaries, so sshd / git-core style paths stay broken (the code comment literally says
   "long-term solved by a PRoot live environment");
3. **No glibc / apt world** — only the bionic closure plus 19 extensions.

So this release does **not** replace that route; it adds a side branch: step into Linux when
glibc/apt is the blocker, keep the zero-download bionic toolchain for everyday work. Both
share the same `$HOME`.

## 1. Terminal

### Entry points

- **Launcher icon** `DSH Terminal` (a second LAUNCHER entry, independent of the main app icon)
- **Settings → Extensions group → Terminal**
- **Linux environment page → Open terminal** (straight into a distro)

### Architecture

```
soft keyboard / extra-keys row / paste
        │  sendText / sendKey / sendControlChar
        ▼
   TerminalView  ──Canvas rendering (monospace, 256/true colour, blinking cursor)──▶ screen
        │ attach(session)
        ▼
   TerminalSession ── reader thread ──▶ TerminalEmulator (VT100/xterm state machine + screen buffer)
        │ write()/resize()                  │ throttled onChanged
        ▼                                    ▼
   dsh_pty.c (JNI) ── fork/execve + PTY ──▶ child (bash / proot)
```

- **PTY layer**: `dsh_pty.c` was refactored from a single engine child to a **session table**;
  the engine's original `Pty.nativeForkPty` symbols are unchanged (delegating to slot 0), so
  engine behaviour is bit-for-bit identical.
- **VT emulator**: GROUND / ESC / CSI / OSC state machine, incremental UTF-8 decoding across
  chunk boundaries, alternate screen (DECSET 1049 — required by vim/top), scroll regions
  (DECSTBM), insert/delete line & char, autowrap with deferred wrap, cursor save/restore,
  OSC 0/2 title, BEL. Colour: 16 / 256 (`38;5;n`) / truecolour (`38;2;r;g;b`).
- **CJK wide characters**: wcwidth-style, 2 cells; the second cell is a continuation; a wide
  glyph in the last column wraps instead of overflowing.
- **Scrollback**: 5000 rows for the main screen; while scrolled back, new output does **not**
  yank the view down (it only follows when already at the bottom).
- **Sessions**: multiple concurrent sessions, tab strip on top, long-press a tab to close;
  rotation / font change does not restart processes.

### Interaction

| Action | Where |
|---|---|
| Esc / Tab / ^C / ^D / ^L / ^Z / Home / End / PgUp / PgDn / arrows | extra-keys row at the bottom (horizontally scrollable) |
| Font size ± | menu ⋮ → Font size ± (8–26 sp, persisted) |
| Copy whole output | menu ⋮ → Copy all output (scrollback + screen, trailing blank lines collapsed) |
| Paste | menu ⋮ → Paste (system clipboard, bypasses the IME) |
| Scroll back | drag **down** = older output, drag **up** = newer output (same as every other Android scroller); a `↓ rows` badge appears while scrolled back |
| Scroll to bottom | menu ⋮ → Scroll to bottom; **back / top-left ←** first returns to the bottom when scrolled back, then exits |

#### Terminal interaction fixes (2026-10-18)

Two interaction bugs were fixed. The back key used `viewportTopRow < scrollbackRows - 1`,
which is always true (the viewport top can only reach `scrollbackRows - screenRows`), so it
always ran `scrollToBottom()` and never `finish()` — the back key appeared dead. It now uses
the emulator's `scrolledBack` (`viewTop < maxTop`). Touch scrolling was also inverted relative
to Android convention (swipe up showed history, swipe down jumped to the newest); it now
follows the standard direction. With the soft keyboard open the system still uses the first
back press to dismiss it (same as other terminal apps) — the second one is handled by the app.
| New session | top-bar ＋; pick Android or an installed distro |

### Environment: identical to the agent's

Android-side sessions run `engine/bin/bash -i` with the environment straight from
`EngineConfig.buildEnv()` — the same PATH / LD_LIBRARY_PATH / PREFIX / extension-injected
`GIT_EXEC_PATH` / `PYTHONHOME` the agent's shell tool gets. You never see "git works for the
AI but not in my terminal"; replaying the agent's command in the terminal is a literal replay.

If the engine runtime is not installed yet, it falls back to `/system/bin/sh` (toybox) and
says so in the title.

## 2. Linux environment (proot)

### Distros

| id | distro | package manager | default shell | download |
|---|---|---|---|---|
| `alpine` | Alpine Linux 3.20.3 (musl) | `apk` | `/bin/sh` | ≈ 3.5 MB |
| `ubuntu` | Ubuntu Base 24.04.3 (glibc) | `apt` | `/bin/bash` | ≈ 30 MB |

The ABI follows the built APK: `arm64-v8a → aarch64/arm64`, `x86_64 → x86_64/amd64`.

### What install does

1. **proot payload**: three `.deb`s from the official Termux repository
   (`proot 5.1.107.96` + `libtalloc 2.5.0` + `libandroid-shmem 0.7`), SHA-256 verified,
   `data.tar.xz` extracted, `data/data/com.termux/files/usr/**` flattened into
   `<filesDir>/linux/proot/`. postinst scripts are **not** executed (same policy as the
   Extension Center).
2. **rootfs**: download `.tar.gz` from a mirror, extract into a temp dir, then **atomically
   rename** into `distros/<id>/rootfs` (a failure never leaves half a rootfs behind).
3. **post-config** (plain file writes, no chroot):
   - `/etc/resolv.conf`: `223.5.5.5` / `8.8.8.8` / `1.1.1.1`
   - `/etc/hosts`: dual-stack localhost
   - Alpine: `/etc/apk/repositories` → Aliyun mirror (official CDN kept as a comment)
   - Ubuntu: `/etc/apt/sources.list` → Aliyun `noble` (four components) + updates/security
   - `/etc/profile.d/00-dsh.sh`: PS1, `LANG=C.UTF-8`
   - `/tmp` mode 1777, `/root` present

### Launch command (verified on the emulator)

```sh
PROOT_LOADER=<home>/proot/libexec/proot/loader \
PROOT_TMP_DIR=<home>/tmp \
LD_LIBRARY_PATH=<home>/proot/lib \
PROOT_NO_SECCOMP=1 \
<home>/proot/bin/proot \
  --link2symlink --kill-on-exit -0 \
  -r <rootfs> -b /dev -b /proc -b /sys -b <appFilesDir> [-b /storage/emulated/0:/sdcard] \
  -w /root \
  /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin USER=root \
      /bin/sh -l
```

Note the loader: Termux's proot has `/data/data/com.termux/files/usr/libexec/proot/loader`
**compiled in**, which does not exist here. proot 5.1.107 honours the `PROOT_LOADER`
override (`src/execve/enter.c:571`), so an env var points it into the app's private dir —
**no ELF patching required**.

### Layout

```
filesDir/linux/
  proot/                 proot + libtalloc + libandroid-shmem + libexec/proot/loader
  tmp/                   PROOT_TMP_DIR
  distros/<id>/rootfs/   distro root filesystem
  distros/<id>/.installed
```

### Mirrors & licensing

- China-first: Aliyun for rootfs, official Termux for debs; on failure the list falls back to
  the official CDNs. TUNA / USTC returned 403 to this host, so they are not used by default.
- **Neither rootfs nor proot ships inside the APK**: the APK contains no third-party GPL
  binary. proot is GPL-2.0 and each Termux package carries its own licence; the device fetches
  them from upstream at install time.

## 3. Agent integration

Engine startup (`EngineConfig.buildEnv`) injects:

| variable | value |
|---|---|
| `DSH_LINUX_HOME` | `<filesDir>/linux` |
| `PROOT_LOADER` | `<home>/proot/libexec/proot/loader` |
| `PROOT_TMP_DIR` | `<home>/tmp` |
| `PROOT_NO_SECCOMP` | `1` |
| `DSH_LINUX_DEFAULT` | first installed distro id |

`<home>/proot/bin` is prepended to PATH and `<home>/proot/lib` appended to
LD_LIBRARY_PATH. The `linux` gate is deployed from `assets/gates/linux` into `engine/bin/`:

```
linux list                  # installed distros, one per line
linux status [id]           # rootfs path / proot readiness
linux run <id> <cmd...>     # run inside the distro (streams stdout/stderr, returns guest exit code)
linux shell <id> [cmd]      # login shell (interactive use)
linux exec <cmd...>         # run on the default distro
linux path <id>            # print the rootfs path
```

Examples:

```sh
linux exec "uname -a && cat /etc/os-release"
linux run ubuntu "apt-get update && apt-get install -y jq"
```

`AgentContextSeed.kt` gained a "Linux environment" section: when to use it, that installing
a distro is a **user** action, and that the bionic toolchain stays the default. The agent is
explicitly told not to fetch rootfs images itself.

## 6. Measured evidence (emulator, 2026-10-18)

Android 14 (API 34) x86_64 AVD `dsh36`, KVM; debug APK with the bundled x86_64 runtime.zip (160 MB).

| Check | Result |
|---|---|
| Build | `assembleDebug -Pabi=x86_64` and `-Pabi=arm64-v8a` both BUILD SUCCESSFUL; `check-i18n.py` green |
| APK size | 185 MB with runtime.zip (24 MB without) |
| Engine | runtime extraction → `engine healthy on :3080` (~21 s, 374 MB unpacked) |
| Terminal (Android) | title `Android`, prompt `u0_a192@dsh:~$`; `node -v` → `v24.18.0` (the agent's own runtime) |
| Rendering | 16 / 256 / truecolour, bold/underline/inverse, CJK wide chars, `✓` glyph |
| Alternate screen | `CSI ?1049h` shows only the ALT content; the main screen is restored afterwards |
| Input | IME works; `adb shell input text` (hardware-key path via unicodeChar) works; extra-keys row scrolls to KBD |
| KBD key | `dumpsys input_method` `mInputShown`: true → false after tapping |
| Sessions | tab strip `Android \| Alpine Linux \| Ubuntu Base`, switch + create |
| Distro install | Alpine (3.5 MB) and Ubuntu (30 MB) both finished in <10 s (Aliyun mirror) |
| `linux` gate | `linux list` → `alpine`/`ubuntu`; `linux status` prints distro/installed/rootfs/proot/default |
| Agent path | `linux exec uname -a` → `Linux localhost 6.1.23-android14-… x86_64 Linux` |
| Ubuntu session | `/bin/ls /` lists the guest rootfs; `echo $PATH` is the guest PATH; `apt --version` → `apt 2.8.3 (amd64)` |

### arm64 device re-test (Redmi K40 / Android 16 / LineageOS, 2026-10-18)

The same code on a real arm64 phone:

| Check | Result |
|---|---|
| Build | `assembleDebug -Pabi=arm64-v8a` (light APK without runtime.zip, 23 MB) |
| Install | Alpine installed in 16 s |
| Alpine session | `uname -a` → `Linux localhost 4.19.325-cip131-… aarch64 Linux`; `ls /` works (no fork error) |
| Package manager | `apk update` hits `mirrors.aliyun.com` (the mirror we configure) → `OK: 24059 distinct packages available` |
| ABI handling | the x86_64 Alpine warning is absent on the phone; distro order keeps Alpine first |

This confirms the hypothesis that the x86_64 fork limitation does not affect phones:
arm64 has no `fork` syscall (musl always uses `clone`), so Android's seccomp policy
does not block it.

### Two issues found and fixed

1. **Duplicate guest env keys**: interactive sessions previously concatenated `guestEnv` + `hostEnv`, both carrying `PATH=`; the guest ended up with the host `/system/bin:/system/xbin` (`ls: command not found`). Now the guest env is rebuilt with `env -i` inside the guest, so host variables no longer leak.
2. **Printable input**: `adb shell input text` / hardware keyboards deliver `KeyEvent`s, not IME `commitText`; those characters were dropped. Now taken from `event.unicodeChar` and `ACTION_MULTIPLE`'s `characters`.

### Alpine on x86_64 emulators (important)

Alpine installs and its shell starts, but **every external command** fails with
`can't fork: Function not implemented`. The proot log pinpoints it:

```
proot info: vpid 1: seccomp SIGSYS: fork(...) = 0x39
```

On x86_64, musl implements `fork()` with the `fork(2)` syscall, which Android's app seccomp
policy kills with SIGSYS (proot turns it into ENOSYS). **arm64 has no fork syscall** (musl
always uses `clone`), so real phones are unaffected; glibc's Ubuntu Base uses `clone` and
works on x86_64. The app compensates: on x86_64 the distro list puts Ubuntu first and the
Alpine card shows this note.

## 4. Limits

- **No systemd / D-Bus / GUI** inside the container: processes and files only; run services
  as foreground processes.
- **Fake root is not root**: `-0` makes `id` print 0, but the underlying uid is still the
  app's; device-level privileges, mounts and raw sockets remain unavailable (switch the
  privilege mode for that).
- **Overhead**: proot translates paths via ptrace; IO-heavy builds are noticeably slower than
  the native bionic toolchain. Interactive commands are imperceptible.
- **Disk**: Alpine ≈ 40 MB, Ubuntu ≈ 130 MB unpacked; the UI shows the size before installing.
- **seccomp**: some syscalls are filtered on Android (hence `PROOT_NO_SECCOMP=1`); a rare
  program may still abort.
- **Uninstalling the app removes the rootfs** (it lives in private `filesDir`). Back it up if
  you need it long-term.
- **Resize does not reflow**: content is preserved and cropped/padded (same as most mobile
  terminals).

## 5. Troubleshooting

| Symptom | Fix |
|---|---|
| `bash: not found` in the terminal | engine runtime not installed; the title shows `Android (toybox)` fallback |
| `linux: proot not installed` | install any distro from the Linux environment page (it also installs the proot payload) |
| no network inside the distro | check that `/etc/resolv.conf` was not overwritten; the app already holds INTERNET |
| `apt` says `Release file ... not valid yet` | device clock is wrong; proot shares the host clock |
| install stuck at 0% | mirror unreachable; retry on another network — install is idempotent (temp dir + atomic publish) |
| garbled output | `TERM=xterm-256color` is injected automatically; check the remote program's terminfo |

## 7. Rebuilding the runtime under the app's own package name (added 2026-10-18)

### Why

Termux binaries hard-code `/data/data/com.termux/files/usr` at compile time:
bash's `SYS_BASHRC`/`SYS_PROFILE`, openssl's default config dir, git's
`git-core`/templates, python's stdlib path.

- text files can be rewritten at install time (`ExtensionManager.rewriteTermuxPaths`);
- **strings inside ELF cannot**: our path
  `/data/data/app.dsh.mobile/files/engine` (37 chars) is longer than Termux's (31),
  so an equal-length in-place patch is impossible;
- the rest can be pinned with env vars (`OPENSSL_CONF` / `GIT_EXEC_PATH` /
  `PYTHONHOME`), but **bash's system bashrc has no env override** — on a device that
  also has Termux installed every interactive shell printed
  `bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied`
  (seen on a real device; the emulator hides it because that path does not exist).

### Approach

Build the path-sensitive packages from source with termux-packages, with the package
name and prefix pointed at this app, then overlay them onto the runtime collected from
Termux:

```
TERMUX_APP__PACKAGE_NAME=app.dsh.mobile
TERMUX__PREFIX_SUBDIR=engine
→ PREFIX = /data/data/app.dsh.mobile/files/engine
```

`scripts/build-runtime-app-prefix.sh` automates the whole pipeline (it uses `bwrap` to
provide a writable `/data`, so no root is required):

```bash
./scripts/build-runtime-app-prefix.sh --abi x86_64 \
    --base-runtime runtime-collected-x86_64.zip --out runtime-app-prefix-x86_64.zip
./scripts/build-runtime-app-prefix.sh --abi arm64-v8a \
    --base-runtime runtime-collected-arm64.zip --out runtime-app-prefix-arm64.zip
```

By default only `bash` is rebuilt (which pulls `libandroid-support`, `libiconv`,
`readline`, `ncurses`); pass `--pkgs "bash openssl git"` for more.

### Host patches applied to termux-packages (all under `[dsh-patch]`, applied idempotently)

| File | Patch | Reason |
|---|---|---|
| `scripts/build/termux_step_setup_variables.sh` | build markers under `$TERMUX_TOPDIR` | host has no `/data/data` |
| `scripts/build/termux_step_handle_buildarch.sh` | arch marker likewise + writable-`/data` guard | same |
| `scripts/properties.sh` | package name / prefix dir overridable; usr-merge validation off by default | our layout is `<rootfs>/engine` |
| `scripts/build/toolchain/termux_setup_toolchain_30.sh` | copy toolchain when `fuse-overlayfs` is absent | installing it needs root |
| `scripts/build/termux_download.sh` | GitHub archive → `codeload.github.com` | `github.com/.../archive` returns 429 |
| `packages/ncurses/build.sh` | drop the foot terminfo source | codeberg returns 429 from this network |
| `packages/bash/build.sh` | no `termux-tools` dependency; `CC_FOR_BUILD=gcc` | that dependency drags in pkg/coreutils; host has only gcc |

Requirements: Linux x86_64 host, `bwrap`, NDK r30 (termux supports r30/r23c only),
`gcc/patch/make/zip/unzip/dpkg-deb/python3`. No Android SDK needed.

### Packaging contract: **no symlinks inside runtime.zip**

`RuntimeInstaller` extracts zip entries as regular files, so links must be
**materialised** (that is what `scripts/collect-termux-runtime.sh` does). Using
`zip -y` keeps symlinks and the installed `lib/libreadline.so.8` ends up 18 bytes long
(the link target string), failing at exec:

```
CANNOT LINK EXECUTABLE ".../lib/libreadline.so.8" is too small to be an ELF executable: only found 18 bytes
```

The script deliberately packs **without** `-y` (zip dereferences links by default).

### Measured (2026-10-18)

| | x86_64 emulator | arm64 phone (Redmi K40 / Android 16) |
|---|---|---|
| runtime version | `v1.3.1-app-prefix-x86_64` | `v1.3.1-app-prefix-arm64` |
| `strings bin/bash \| grep com.termux` | **0** | **0** |
| `bash --version` | 5.3.20 (x86_64-pc-linux-android) | 5.3.20 (aarch64-unknown-linux-android) |
| interactive shell | normal prompt, no bashrc error | same (the phone has Termux; before the rebuild it printed `Permission denied` every time) |
| rc | read from `$PREFIX/etc/bash.bashrc`, caller PS1 preserved | same |

The temporary `bash --norc` workaround in `ShellProfiles` has therefore been removed in
favour of plain `bash -i`.

### Limits

- **node is still collected from Termux**: rebuilding it means cross-compiling ICU and
  OpenSSL (tens of minutes) while its path-sensitive bits are already pinned by env vars;
- **extensions (python/git/…) still use the runtime prefix rewrite**: they install into
  per-extension directories (`files/extensions/<id>`), where a compile-time prefix is
  meaningless;
- rebuilt packages target API 26 (matching `minSdk 26`; bash's `mblen` needs 26+);
- the script overlays rebuilt packages onto a collected base runtime; it does not build
  all 19 packages from scratch.

## 8. Runtime hot-update: pulled by **dsh version** (added 2026-10-18, semantics fixed same day)

### Three different "versions" (the first cut conflated them)

| Name | Example | Decided by | How to upgrade |
|---|---|---|---|
| **APK version** | `v1.3.1` | this repo's releases | reinstall the APK |
| **runtime asset label** | `v1.3.1-app-prefix-arm64` | `assets/runtime.zip` inside the APK | follows the APK |
| **dsh version** | `0.2.0-rc.2` | upstream `@deepseek-ai/dsh` | **`dsh-update check/pull`** |

`dsh-update` manages the last column: **the latest dsh runtime, not the latest APK**.
The first implementation only staged a zip you handed it and its status only showed the
runtime label, which made it look like an app update — it now keys on the dsh version.

### Why not `npm install @deepseek-ai/dsh@latest` on the device

The Android compatibility patches are applied to the dependency tree **at build time**:
inline `openPath/openTextFile` reader, old-WebView polyfills, `koffi`/`node-pty` stubs,
and the `@deepseek-ai/node-addon-system` flock fallback (without which the engine ends up
in a restart loop). The device has no python3 and should not re-run that patch set in the
field; installing the upstream package as-is gives you an engine that will not start.
So the implementation **pulls a runtime package pre-built and pre-patched for that dsh
version**.

### Feed (`runtime-manifest.json`), keyed by dsh version

```json
{
  "schema": 1,
  "dsh_version": "0.2.1",
  "built_at": "2026-10-18T02:00:00Z",
  "assets": {
    "arm64-v8a": {"url": "https://…/runtime-arm64-v8a.zip", "sha256": "…", "size": 172961543},
    "x86_64":    {"url": "…", "sha256": "…", "size": 176352302}
  }
}
```

- Default location: this repo's rolling release tag `dsh-runtime-latest`;
- Override with `DSH_RUNTIME_MANIFEST`, or `dsh-update check|pull --manifest <url>`
  (mirrors, self-hosted feeds, local testing);
- Produced by `.github/workflows/dsh-runtime.yml`: daily npm check, rebuild the runtime for
  the new dsh version with `scripts/collect-termux-runtime.sh` (which applies the Android
  patches), publish both ABIs as an immutable `dsh-runtime-<ver>` release and move
  `dsh-runtime-latest`.

### Mechanism

```
filesDir/
  engine/            live tree (what EngineSupervisor starts)
  engine.staging/    extraction in progress
  engine.new/        complete staged tree (+ .runtime-version)
  engine.old/        previous tree (rollback; reclaimed on a later healthy boot)
  .runtime-swap      write-ahead note for crash recovery
  .runtime-reclaim   "engine.old may be reclaimed" marker (2-minute age gate)
```

- `pull`: fetch the manifest → pick the current ABI → download (verifying SHA-256 and size
  while streaming) → reuse the `stage` validation/extraction/publish path (zip layout, ELF
  ABI, free space, integrity probe) → land in `engine.new`. **Same version is not
  re-pulled** (saves 170 MB and avoids swapping in a semantically identical tree).
- `apply`: write-ahead note → stop engine → `engine→engine.old`, `engine.new→engine` →
  start → wait for `:3080` health (90 s) → success; on failure **swap back automatically**
  and restart the previous runtime.
- `engine/.runtime-source=external` makes `RuntimeInstaller` skip the assets version check
  on later launches (otherwise the bundled `runtime.zip` would silently undo the update).
- `rollback` swaps `engine.old` back in and restarts; the rollback point survives one boot.

### Agent entry point: the `dsh-update` gate

```
dsh-update check [--manifest <url>]      # local dsh / npm latest / what the feed offers (read-only)
dsh-update pull [dsh-version] [--apply]  # stage the latest dsh runtime; --apply also swaps
dsh-update status                        # includes version (runtime label) + dsh (engine version)
dsh-update stage <zip|url> [sha256]      # manual package (offline / canary)
dsh-update apply | rollback | restart
```

Underneath: new routes on the in-app bridge (`127.0.0.1:3083`, same token auth as
 notify/scr): `/runtime/feed`, `/runtime/pull`, `/runtime/stage|apply|rollback|status`,
`/engine/restart`. Actions are **async 202 + status polling**: `apply` kills the engine
process the agent runs in, so the response is sent first and the restart happens in the app
process — the calling shell dying does not affect the outcome.

Settings' "Runtime update" row shows the runtime label and the dsh version; tapping it offers
"Check dsh update", and when a newer dsh runtime exists, "Pull and apply" (the UI equivalent
of `pull --apply`).

### Measured (2026-10-18)

| Item | Environment | Result |
|---|---|---|
| `check` | arm64 phone + local feed | live `0.2.0-rc.2` / npm latest / feed `0.2.1` / `update_available: true` |
| `pull` | arm64 phone (173 MB, local source via adb reverse) | **19.6 s** for download + verify + extract into `engine.new` |
| `pull --apply` | arm64 phone | 26 s end-to-end, `apply: ok`, engine healthy, rollback point kept |
| same-version guard | arm64 phone | `already on latest dsh 0.2.0-rc.2 (feed …)`, no re-download |
| emulator (first cut) | x86_64 | stage 14 s / apply 4.3 s / rollback 6.3 s; broken runtime auto-rolled back after 90 s; kill mid-swap recovered |

### Limits

- The feed is **pre-built per dsh version**: after npm publishes, the matching runtime
  bundle must be produced by CI first (`check` also prints the npm latest so the gap is
  visible);
- a runtime bundle is ~170 MB per ABI; peak disk during pull/apply is roughly
  "downloaded zip + new tree + old tree", and free space is checked before pulling;
- the rollback window lasts one boot; afterwards `engine.old` is reclaimed and `rollback`
  returns 409;
- it is still a runtime-only update: the APK, `dsh-home`, `workspaces` and sessions are
  untouched.

## 9. WebUI sidebar terminal (node-pty bridge, added 2026-10-18)

### Symptom

The WebUI sidebar terminal (in the app's WebView, `127.0.0.1:3080`) did not open:

```
terminal error: subprocess-local: terminal inspection is unsupported on platform android
```

or, earlier, `node-pty unavailable in this Android build`. Those are two independent
blockers:

1. **node-pty has no Android build**: upstream ships glibc/x64 `.node` prebuilds, which
   bionic cannot load; the runtime carried a build-time stub whose `spawn()` throws.
2. **The process inspector rejects android**: `dsh-subprocess-local` only accepts
   linux/darwin/win32 and throws for anything else — so even a working PTY fails here.

### Fix

**① Keep the real PTY in the app process; expose it over the bridge**

```
engine (node)                  app process
node-pty shim  ──HTTP──▶ AgentBridge /pty/{open,write,resize,kill,close,read}
                                └─▶ libdshpty.so (fork/exec + /dev/ptmx)
```

- `PtyBridge.kt`: session table, one reader thread per session, 512 KB ring buffer with an
  absolute `seq`; `GET /pty/read?id&seq&timeout` long-polls for new bytes (base64) and
  reports `exit_code`/`signal` once the child is gone.
- `assets/runtime-overlay/lib/node_modules/node-pty/`: a **real** CommonJS node-pty
  implementation, deployed into the engine tree by
  `EngineConfig.applyRuntimeOverlays()` on every engine start (idempotent, and only when
  the target is not a native node-pty). `spawn()` keeps the **synchronous** contract
  (a child node process performs the HTTP open; the parent blocks in `execFileSync`)
  because the engine reads `terminal.pid` immediately after spawn returns.
- The PTY table grew from 8 to 16 slots (1 engine + 7 app tabs + 8 engine-side); the app
  terminal still caps at 7.

**② Teach the process inspector about android**

`EngineConfig.patchEngineForAndroid()` scans `@deepseek-ai/dsh-subprocess-local/lib/*.js`
by **content anchor** at engine start (file names carry a content hash), turning

```js
if (platform === "linux") return new LinuxProcessInspector(arch, internals);
```

into `linux || android`. Android is a Linux variant: the inspector only uses
`/proc/<pid>/` (stat/task/fd/syscall), all present; `/bin/ps` is used only in the darwin
branch and in `foregroundPgid` (already wrapped in try/catch). Idempotent: when the anchor
is gone it is skipped, and a runtime update re-patches the new file automatically.

**③ Point the default shell at the bundled bash**

The engine resolves the default shell from `process.env.SHELL || userInfo().shell`; Android
has no passwd entry, so without it you get `/bin/sh` (toybox). `EngineConfig.buildEnv()` now
injects `SHELL=<engineRoot>/bin/bash`, so the sidebar gets the **real bash** (our PS1, rc,
completion).

### Four pitfalls hit along the way (mostly C / lifetime)

| Pitfall | Symptom | Fix |
|---|---|---|
| `PR_SET_PDEATHSIG` is bound to the **thread**, not the process | open succeeds but reads immediately report exited / signal=15, no output | fork on a dedicated long-lived `pty-spawner` thread |
| `opendir`/`malloc` after fork in a multi-threaded ART process | child crashes in libsigchain before exec | `close_inherited_fds` became an allocation-free `close(3..1023)` loop |
| `sigaction` on SIGSEGV/SIGBUS in the forked child | same crash, child stuck before exec | reset only an allowlist of ordinary signals; crash signals are reset by execve |
| engine passes a `cwd` that does not exist on the device | child `_exit(125)`, terminal opens then dies | fall back to `dsh-home` and log |

### Measured (arm64 phone)

- sidebar "New terminal" shows the `~ $` prompt (the app's PS1);
- typing `bash --version` produced GNU bash usage output — proof it is the bundled bash,
  not toybox sh;
- calling the shim directly from `node -e`: `spawn(bash)` printed `PTY_OK_2`, resize took
  effect, `kill('SIGKILL')` produced `exit {exitCode:0, signal:'SIGKILL'}`;
- the app's own TerminalActivity still works; both share the PTY table without interference.

## 10. The native runtime is not Termux: why `pkg` / `apt` do not work (added 2026-10-18)

### In one line

The app's native shell runs a **minimal closure collected from the Termux repository**
(node, bash, ripgrep, curl wrapper, pnpm, …), **not a Termux rootfs**. So `apt` and
`dpkg` simply are not there, and the old `pkg` was only a Termux shell script that cannot
work here.

### Three reasons (all of them matter)

1. **No package manager in the runtime.** `collect-termux-runtime.sh` only picks the
   command closure dsh needs; it never includes `apt`/`dpkg` (which would drag in ~100 MB
   of dependencies).
2. **`pkg` is a Termux script with hard-coded paths.** It starts with
   `source /data/data/com.termux/files/usr/bin/termux-setup-package-manager`, while our
   prefix is `/data/data/app.dsh.mobile/files/engine` — it fails immediately.
3. **Dropping apt/dpkg in would not help.** Termux's apt/dpkg are compiled with
   `/data/data/com.termux/files/usr` as the prefix; the dpkg status database,
   `sources.list` and signing keys live under it, and the `.deb` files in the Termux
   repository contain `data/data/com.termux/...` paths as well. Installing them into our
   tree would put everything in the wrong place — the same issue that forced us to
   rebuild bash under the app's package name.

### The two working ways to install software

**① Native extensions (the `pkg install` equivalent)**

`engine/bin/pkg` is now an app gate (no longer the broken Termux shell):

```
pkg list                 # 21 extensions: python / openjdk-17 / golang / rust / ruby / php /
                         #   lua / perl / clang / git / archivers / android-buildtools /
                         #   cmake / sqlite / ffmpeg / imagemagick / openssh / android-tools /
                         #   vim / llama-cpp / asr-service
pkg search <keyword>
pkg install <id|name>    # goes through the app extension centre: fetch the Termux .deb,
                         #   rewrite the prefix, unpack into <engine>/extensions/<id>/ and
                         #   add it to PATH/LD_LIBRARY_PATH
pkg state <id> / pkg where <id>
```

Measured: `pkg install archivers` finished in **8.7 s** (download + unpack + activate);
`pkg list/search/state` all work. A new shell (or an engine restart) is needed before the
new bin/ shows up in PATH.

**② A real distro package manager (apk / apt)**

For dependency resolution and system-level tooling, use the proot Linux environment:

```
linux shell alpine        # inside: apk update && apk add python3
linux run alpine apk --version
linux shell ubuntu        # inside: apt update && apt install ...
```

Measured: `apk-tools 2.14.4 (aarch64)` runs fine inside Alpine.

### A real regression fixed along the way: rebuilt bash `test -x` was always false

While investigating the `linux` gate's `proot: missing` report we found that our rebuilt
bash 5.3.20 returns **false for `[ -x <any file> ]`** (even `/system/bin/sh`), while
`[ -e ]` works.

- Cause: bionic's `faccessat()` does not support `AT_EACCESS` (returns EINVAL), and bash's
  `sh_eaccess()` uses **only** `faccessat(AT_EACCESS)` when configure detects
  `HAVE_FACCESSAT && AT_EACCESS` → every check fails.
- Impact: every script using `[ -x ]` (gates, user build scripts) silently takes the wrong
  branch; the `linux` gate therefore mis-reported a missing proot.
- Fix: build with `ac_cv_func_faccessat=no` so bash falls back to `access(2)`; documented
  in `scripts/build-runtime-app-prefix.sh`. On the rebuilt runtime
  `v1.3.2-app-prefix-arm64`, `[ -x ]` and `[ -x /system/bin/sh ]` behave correctly and
  `linux status` reports `proot: ok` again.

### Cheat sheet

| Need | Use |
|---|---|
| python / git / ffmpeg etc. | `pkg install python` (app extension, prefix rewritten) |
| real apt/apk dependency resolution and distro tooling | `linux shell alpine` / `linux shell ubuntu` |
| copy Termux's `pkg`/`apt` over | ❌ prefix and dpkg database do not match; not feasible |

## 11. A real package manager on the native runtime: dpkg + the Termux repository (added 2026-10-18)

> The previous section explained why `apt`/`pkg` cannot work on the native runtime.
> This section is the fix: cross-compile Termux's `dpkg` and `proot` under this app's
> package name, then let proot bind-map the Termux prefix onto the app's runtime tree,
> so `.deb` files from the Termux repository install, uninstall and resolve deps for real.

### Why proot

Termux `.deb` payloads contain paths under `/data/data/com.termux/files/usr/...`, while
this app's prefix is `/data/data/app.dsh.mobile/files/engine` (Android forbids creating
another app's data directory). Running `dpkg -i` directly would scatter files. Instead:

```
proot -r <pkgroot> -b <engine>:/data/data/com.termux/files/usr \
      -b <engine>:<engine> -b <dsh-home>:/data/data/com.termux/files/home \
      <engine>/bin/dpkg --unpack ...
```

`pkgroot` is a fake rootfs skeleton (`<files>/pkgroot`) whose only job is to make proot
bind targets exist:
- guest `/data/data/com.termux/files/usr/bin/vim` is written to `<engine>/bin/vim`
- maintainer-script shebang `#!/data/data/com.termux/files/usr/bin/sh` becomes `<engine>/bin/sh`
- dpkg itself is built for the app prefix, so its admindir is `<engine>/var/lib/dpkg`

### Components (all rebuilt for our prefix, not copied binaries)

| Component | Purpose | Patch (all tagged `[dsh-patch]` in `scripts/build-runtime-app-prefix.sh`) |
|---|---|---|
| `dpkg` 1.22.6 | real status DB, file lists, removal, `--configure` | deps trimmed to libraries, `dpkg-perl`/`dpkg-scanpackages` subpackages disabled (they pull perl/clang), source moved to the GitHub mirror (salsa 429s this network) |
| `proot` 5.1.107.96 | prefix mapping (bind) | `#include <string.h>` in `src/extension` (NDK r30 is strict) |
| `libandroid-shmem` | proot dependency | provide `_PATH_TMP` (missing in NDK r30 `paths.h`) |
| `attr` / `libacl` | tar deps (optional) | provide glibc's `IFTODT` macro |
| `zstd` | `dpkg-deb` needs `libzstd.so.1` | none |

### Three real gotchas (fixed)

1. **No `bin/sh`**: Termux's bash package does not ship `bin/sh`, yet every maintainer
   script uses `#!/data/data/com.termux/files/usr/bin/sh`. A runtime overlay provides
   `bin/sh` (execs the sibling bash).
2. **`dpkg-deb` wants GNU tar options**: it runs `tar --warning=no-timestamp`, while
   Android only has toybox tar, which rejects GNU-only flags. The overlay `bin/tar`
   filters those flags and delegates to toybox (only for dpkg).
3. **`update-alternatives` creates absolute links**: inside proot it writes
   `/data/data/com.termux/files/usr/bin/vim -> ...`, which dangles outside proot
   (`vim: inaccessible or not found`). `pkg` rewrites such links to relative ones after
   install; `pkg fixup` reruns it. Also, symlinks inside the runtime zip are written as
   plain files by `RuntimeInstaller` (`bin/sh` used to become a 0-byte file), so the
   build script now **dereferences every symlink into a real file** before zipping
   (270 of them today).

### Usage

```
pkg update                # fetch the official Termux index (Packages.gz, 1.8 MB, ~3000 pkgs)
pkg install vim           # resolve deps + download/verify + dpkg install (76 s, 4 packages)
pkg list                  # read the real dpkg database
pkg remove vim
pkg search python
pkg fixup                 # rewrite alternatives links (runs automatically after install)
```

Host build (reproducible):

```
scripts/build-runtime-app-prefix.sh --abi arm64-v8a \
  --base-runtime <collected.zip> --out /tmp/runtime-arm64.zip
```

Default `PKGS="bash proot dpkg zstd"` (dependencies are built recursively).

### Relation to apt

`apt` itself (the backend of the official `pkg` script) depends on gnutls/gcrypt/gpgv/
termux-keyring and ten-odd other packages and has not been cross-compiled yet. Today's
`pkg` is a front-end over the Termux repository plus real `dpkg` with its own solver;
`install/remove/list/search/info` already work. The apt layer (full version solving,
`apt upgrade`, release signature verification) is the next step.

## 12. Package name switched to `com.termux`: a full Termux environment plus the bundled adapted engine (2026-10-18)

### Why change the package name

Termux packages and binaries hard-code `/data/data/com.termux/files/usr` inside ELF files and
deb payload paths. As long as the applicationId is anything else, proot has to map the prefix
(previous section). Setting applicationId to `com.termux` makes `<filesDir>/usr` the real
`$PREFIX`: debs install verbatim, maintainer-script shebangs work as-is, `apt`/`dpkg`/`pkg`
come straight from the official Termux bootstrap, and none of our rebuilt dpkg/proot/tar shims
are needed.

The cost: **it cannot coexist with the official Termux** (same package id, different signing
key); uninstalling Termux is required, and Termux's data cannot be migrated because the
signing key changes. The app data directory changes too, so data needs migration.

### Two runtime layers (they never overwrite each other)

| Layer | Contents | Source |
|---|---|---|
| Termux base | bash / apt / dpkg / coreutils / sed / tar … | `assets/bootstrap-aarch64.zip` extracted into `<files>/usr`, 1213 links rebuilt from `SYMLINKS.txt` |
| dsh engine | node + `lib/node_modules/@deepseek-ai/dsh` (adapted closure) + ripgrep | `assets/engine-dsh-aarch64.zip` (**bundled in the APK**) |

node itself comes from the Termux repository (`pkg install nodejs-lts`) so it follows repo
upgrades; **dsh must never be installed with npm** — the upstream package cannot be installed
on Android/Termux, which is exactly why this project ships an adapted runtime.

### Incident postmortem: dsh would not start after the rename (fixed)

Symptom: blank home page; `engine.log` reported
`No usable native binding found for node-addon-require-builtin-android-arm64 (auto)`.

Root causes:
1. On first boot `BootstrapInstaller` installed `@deepseek-ai/dsh` with **npm**. npm pulled in a
   **newer nested dependency** (`dsh/node_modules/node-addon-require-builtin`) that shadowed the
   working one from the adapted runtime, so `dsh-app-boot` failed to find an Android native
   binding and exited fatally.
2. After restoring data, `dsh-home/storages/workspace.json` and `session_projcache/*.json`
   contained **absolute paths under the old package name**, so session restoration tried to
   mkdir the old package directory → `EACCES`, which looked like "the home page is broken".

Fixes:
- the engine is only ever extracted from the APK assets (delete any npm-tainted
  `lib/node_modules` first); npm is not used at all;
- new `LegacyDataMigrator` rewrites old package paths in dsh-home text data on first start and
  recreates workspace directories from `workspace.json` (idempotent, marker file);
- `restoreExecBits` after extraction (ZipInputStream drops permission bits, otherwise `bin/pkg`
  is not executable);
- the runtime builder dereferences every symlink before zipping (zip contract).

### What works now

```
pkg install vim          # native Termux apt (no proot, no rewriting)
apt update && apt upgrade
dsh-update status        # bundled engine version (engine upgrades with the APK)
linux shell alpine       # distros still run under proot, independent of the Termux prefix
```

## 13. dsh-update: engine-layer hot updates (restored 2026-10-18, verified on device)

After the rename to `com.termux` the runtime has two layers (previous section).
**Hot update replaces only the engine layer**:

```
<files>/usr/lib/node_modules      ← replaced
<files>/usr/bin/node, bin/rg      ← replaced
<files>/usr/.dsh-engine-version   ← replaced
(apt/dpkg/bash/coreutils from the bootstrap are never touched)
```

### Commands

```
dsh-update status                        # local dsh/node/engine versions, staged/rollback state
dsh-update check [--manifest URL]         # local dsh version vs feed (falls back to npm registry)
dsh-update pull [version] [--apply]       # download engine tar.gz, verify sha256, stage it
dsh-update stage <url|path> [sha256]      # stage an explicit artifact (offline/internal builds)
dsh-update apply                          # backup -> swap -> restart -> health check (auto rollback on failure)
dsh-update rollback                       # back to the previous engine
dsh-update restart                        # restart the engine only
```

- version semantics are unchanged: keyed on the **`@deepseek-ai/dsh` version**, not the APK;
- `apply` moves the old layer to `<prefix>/.engine-prev` (node_modules, bin/node, bin/rg, stamp),
  swaps the new layer in, kills the engine so the guardian restarts it, then health-checks
  `http://127.0.0.1:3080/`; an unhealthy new tree is rolled back with its stamp restored;
- the artifact contains the engine layer only, so package management keeps working after updates.

### CI publishing

`.github/workflows/dsh-runtime.yml` now additionally produces:

```
engine-dsh-aarch64.tar.gz / engine-dsh-x86_64.tar.gz
engine-manifest.json   { dsh_version, engine_version, assets.<arch>.{url,sha256,size} }
```

published to the rolling `dsh-engine-latest` release (the default feed) and archived in the
immutable `dsh-runtime-<dsh version>` release. Until a release exists, `check` falls back to
comparing the dsh version from the npmmirror registry.

### Verified on device (arm64)

```
stage: ok (dsh-engine-0.2.0-rc.2-20261018)
apply: swapped in ...   health: ok (http 401)   apply: ok
rollback: restored previous engine (dsh-engine-2026.10.18)   health: ok
```
