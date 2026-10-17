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
| Scroll to bottom | menu ⋮ → Scroll to bottom; Back first scrolls to the bottom when you are in history |
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
