# DEV: Terminal app + Linux environment (2026-10-18)

> Internal design/coordination doc for the feature branch that adds a **native Android
> terminal emulator** and a **proot-based Linux userland** to DSH Mobile, so both the
> user and the dsh agent can drive real Linux toolchains on the phone.
> Written before implementation; kept as the contract for the code that follows.

## 1. Why

DSH Mobile today ships a *frozen* bionic toolchain snapshot (runtime.zip) plus 19
downloadable extensions. That covers node/python/git, but:

- there is **no terminal UI** — the only shell is the agent's `bash -c` tool, invisible
  and non-interactive;
- the Termux `.deb` extensions carry **hard-coded `/data/data/com.termux/files/usr`
  paths** (`ExtensionManager.rewriteTermuxPaths` skips ELF binaries, so sshd/git-core
  style paths stay broken) — the code already notes "长期由 PRoot 活环境系统性解决";
- anything needing a real distro (apt, glibc, systemd-less daemons, build toolchains
  outside the catalogue) is out of reach.

This feature adds:

1. **Terminal** — a real VT100/xterm terminal with PTY sessions, soft-keyboard
   handling, extra-keys row, scrollback, multiple sessions, UTF-8 + CJK wide chars.
2. **Linux environment** — on-demand install of a distro rootfs (Alpine / Ubuntu) and
   the Termux `proot` payload, launched inside a PTY → a full login shell in the
   rootfs; and a `linux` agent gate so dsh can run commands there non-interactively.

## 2. Hard constraints (must not regress)

- `minSdk 26`, `targetSdk 28` (W^X exemption — exec from `filesDir` is the whole
  premise), `compileSdk 36`, Kotlin 1.9.24, Java 17, **no new Gradle dependencies**
  (`org.tukaani:xz` already present for `.xz`; gzip/tar are implemented in-tree).
- The engine's existing PTY path (`Pty.nativeForkPty` & friends) and its single-child
  semantics must keep working byte-for-byte.
- **i18n gate**: `python3 scripts/check-i18n.py` must stay green. Consequences for all
  contributors:
  - every user-visible string lives in `res/values/strings.xml` **and** the same key
    must be added to `values-zh-rCN/strings.xml` and `values-de/strings.xml`;
  - **no CJK inside Kotlin string literals** (comments are fine);
  - layout files must not contain literal CJK either.
- Do not touch `app/build.gradle.kts`, `AndroidManifest.xml`, `EngineConfig.kt`,
  `AgentBridge.kt`, `SettingsActivity.kt`, `strings.xml`, `themes.xml` unless it is
  explicitly your scope — the Lead owns integration files to avoid write conflicts.
- Do not run `git` commands. Do not reformat unrelated code.

## 3. Shared contract — `engine/ShellSpec.kt` (already written, do not edit)

```kotlin
data class ShellSpec(
    val cmd: String,                      // absolute path, execve'd directly (no PATH lookup)
    val args: List<String> = emptyList(), // argv[1..]
    val cwd: String,                      // must exist
    val env: List<String> = emptyList(),  // complete "KEY=VALUE" list (not inherited)
    val title: String = "",
    val kind: Kind = Kind.ANDROID,        // ANDROID | LINUX
    val distroId: String? = null,
)
```

## 4. Work split & file ownership

### A. `terminal-core` (teammate) — owns ONLY these files

| File | Content |
|---|---|
| `app/src/main/cpp/dsh_pty.c` | refactor to a **PTY session table**; keep legacy `Pty.native*` JNI symbols delegating to session 0 |
| `app/src/main/java/app/dsh/mobile/term/PtyNative.kt` | JNI bindings for the session API |
| `app/src/main/java/app/dsh/mobile/term/TerminalSession.kt` | one session: reader thread, write, resize, signal, exit callback |
| `app/src/main/java/app/dsh/mobile/term/TerminalEmulator.kt` | VT100/xterm parser + screen buffer + scrollback |
| `app/src/main/java/app/dsh/mobile/term/TerminalView.kt` | custom `View`: Canvas rendering, IME input, touch scroll |

Interface the Lead's `TerminalActivity` / `ShellProfiles` will consume (keep these
names/signatures):

```kotlin
// PtyNative.kt
object PtyNative {
    /** @return session id (>=0) or -errno */
    fun open(cmd: String, args: Array<String>, cwd: String, env: Array<String>, rows: Int, cols: Int): Int
    fun masterFd(session: Int): Int
    fun resize(session: Int, rows: Int, cols: Int): Int
    fun signal(session: Int, sig: Int): Int
    /** blocking waitpid; call from the reader thread after EOF */
    fun waitExit(session: Int): Int
    fun childPid(session: Int): Int
    /** close master fd + kill child tree + free slot */
    fun close(session: Int): Int
}

// TerminalEmulator.kt
class TerminalEmulator(rows: Int, cols: Int) {
    fun append(data: ByteArray, off: Int, count: Int)
    fun resize(rows: Int, cols: Int)
    fun reset()
    val rows: Int; val cols: Int
    val scrollbackRows: Int                    // total absolute rows (scrollback + screen)
    val viewportTopRow: Int                    // absolute row shown at top
    fun cellAt(absRow: Int, col: Int): TerminalCell
    val cursorAbsRow: Int; val cursorCol: Int; val cursorVisible: Boolean
    var title: String                          // OSC 0/2
    var onChanged: (() -> Unit)?
    var onTitle: ((String) -> Unit)?
    var onBell: (() -> Unit)?
}
data class TerminalCell(
    val text: String,       // one code point (may be 2 columns wide); "" = continuation of a wide glyph
    val fg: Int,            // 0xRRGGBB, or TerminalCell.DEFAULT
    val bg: Int,
    val style: Int,         // bitmask: BOLD/ITALIC/UNDERLINE/INVERSE/DIM/STRIKE
    val wide: Boolean,
)

// TerminalSession.kt
class TerminalSession internal constructor(
    val sessionId: Int, val spec: ShellSpec, val emulator: TerminalEmulator,
    val masterFd: Int, val pid: Int,
) {
    val title: String
    val isRunning: Boolean
    val exitStatus: Int
    var onUpdate: ((TerminalSession) -> Unit)?
    var onExited: ((TerminalSession) -> Unit)?
    fun write(bytes: ByteArray)
    fun resize(rows: Int, cols: Int)
    fun signal(sig: Int)
    fun close()
}
object TerminalSessions {
    fun create(spec: ShellSpec, rows: Int, cols: Int): TerminalSession
    val all: List<TerminalSession>
    fun defaultEnv(): List<String>
    fun closeAll()
}
```

`TerminalView` requirements (Lead will instantiate it):

```kotlin
class TerminalView(context: Context, attrs: AttributeSet?) : View {
    fun attach(session: TerminalSession?)   // null detaches
    var fontSizePx: Float                   // default 13sp-ish, persisted by Lead
    var colorScheme: TerminalColorScheme    // dark default
    /** 额外按键条调用：Ctrl/Alt/Esc/Tab/方向键/字符 */
    fun sendKey(keyCode: Int)               // KeyEvent.KEYCODE_*
    fun sendText(text: String)
    fun sendControlChar(c: Char)            // e.g. 'c' -> ^C
    fun scrollBy(deltaRows: Int)
    fun scrollToBottom()
    fun requestTerminalFocus()              // show IME, soft keyboard
}
```

Behavioural must-haves: DECSET 1049 alt screen (vim/top), CSI SGR 16/256/truecolor,
scroll regions (CSI r), insert/delete lines & chars, autowrap, cursor save/restore,
OSC title, BEL, bracketed paste is optional; **UTF-8 decoding across chunk
boundaries**; **CJK/emoji wide characters take 2 cells** (wcwidth-style ranges);
scrollback ≥ 5000 rows; resize preserves content (reflow is optional, cropping is fine).

### B. `linux-env` (teammate) — owns ONLY these files

| File | Content |
|---|---|
| `app/src/main/java/app/dsh/mobile/linux/LinuxDistro.kt` | distro catalogue: id/name/version/shell/pkg-manager/rootfs URL per ABI + SHA-256 + size |
| `app/src/main/java/app/dsh/mobile/linux/TarGzExtractor.kt` | streaming `.tar.gz` extractor (file/dir/symlink/hardlink, mode bits, path-escape guard) |
| `app/src/main/java/app/dsh/mobile/linux/DebInstaller.kt` | fetch Termux `.deb`s for proot/libtalloc/libandroid-shmem, extract `data.tar.xz` (via `org.tukaani:xz`), flatten `data/data/com.termux/files/usr/*` into `<linuxHome>/proot`, restore exec bits |
| `app/src/main/java/app/dsh/mobile/linux/LinuxEnv.kt` | public manager (see interface below) |
| `app/src/main/assets/gates/linux` | agent gate script (bash, `@DSH_BASH@`/`@DSH_LIBDIR@` placeholders like the other gates) |

Interface the Lead will call:

```kotlin
object LinuxEnv {
    fun home(ctx: Context): File                       // <filesDir>/linux
    fun distros(): List<LinuxDistro>
    fun isInstalled(ctx: Context, id: String): Boolean
    fun installedIds(ctx: Context): List<String>
    fun rootfs(ctx: Context, id: String): File         // <home>/distros/<id>/rootfs
    fun prootDir(ctx: Context): File                   // <home>/proot
    /** download+install proot payload; idempotent; blocking (call off main thread) */
    fun ensureProot(ctx: Context, report: (Float, String) -> Unit): Boolean
    /** download+extract distro rootfs; idempotent; blocking */
    fun install(ctx: Context, id: String, report: (Float, String) -> Unit): Boolean
    fun remove(ctx: Context, id: String)
    /** interactive login shell (kind = LINUX) or one-shot command */
    fun shellSpec(ctx: Context, id: String, interactive: Boolean,
                  command: List<String>? = null, cwd: String? = null): ShellSpec?
    /** extra env("KEY=VALUE") the engine needs to run the gate (PROOT_LOADER etc.) */
    fun gateEnv(ctx: Context): List<String>
}
```

Technical facts verified on the emulator (do not re-derive):

```
PROOT_LOADER=<home>/proot/libexec/proot/loader
PROOT_TMP_DIR=<home>/tmp
LD_LIBRARY_PATH=<home>/proot/lib
proot --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root \
      /usr/bin/env -i HOME=/root PATH=... TERM=xterm-256color /bin/sh -l
```

- proot version 5.1.107.96, deps libtalloc 2.5.0 + libandroid-shmem 0.7, both from
  `https://packages.termux.dev/apt/termux-main` (China mirrors TUNA/USTC returned 403
  to this host; official is reachable — implement failover list [aliyun mirror of
  termux? none] → packages.termux.dev).
- Rootfs sources that returned 206 on this host:
  - Alpine 3.20.3: `https://mirrors.aliyun.com/alpine/v3.20/releases/<arch>/alpine-minirootfs-3.20.3-<arch>.tar.gz`
    (arch `x86_64` / `aarch64`), also `https://dl-cdn.alpinelinux.org/...`.
  - Ubuntu Base 24.04.3: `https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-<arch>.tar.gz`
    (arch `amd64` / `arm64`), also `https://cdimage.ubuntu.com/...`.
- After extraction: write `/etc/resolv.conf` (223.5.5.5, 8.8.8.8), `/etc/hosts`,
  `/root`, and point the distro package manager at a China mirror
  (Alpine: `https://mirrors.aliyun.com/alpine/v3.20/main` + `community`;
  Ubuntu: `https://mirrors.aliyun.com/ubuntu/ noble main universe ...`), keeping the
  original as fallback where the file format allows.
- Never write outside `<filesDir>/linux`. Verify SHA-256 when known; when unknown
  (first run), compute and cache it (`<home>/<file>.sha256`) so upgrades are detectable.

### C. Lead — integration (do not duplicate)

`ShellProfiles.kt`, `TerminalActivity.kt`, `TerminalExtraKeysView.kt`,
`LinuxEnvActivity.kt`, layouts, `strings.xml` ×3, `AndroidManifest.xml`,
`EngineConfig.kt` (env + gate deploy), `AgentBridge.kt` (optional: `/linux/*`,
`/open_terminal`), `SettingsActivity.kt` entry, docs/README, build + emulator verify.

## 5. Verification plan (Lead)

1. `gradle -Pabi=x86_64 assembleDebug` green; `python3 scripts/check-i18n.py` green.
2. Install on `emulator-5554` (Android 14 x86_64, AVD `dsh36`).
3. Terminal: `adb shell am start -n app.dsh.mobile/.term.TerminalActivity`; screenshot;
   run `echo`, `ls`, `top` (alt screen), `printf '\033[31mred\033[0m'`, CJK output,
   `stty size` after rotate.
4. Linux: install Alpine through the UI (or debug intent); open its shell; `apk update`;
   `uname -a`; `ps`; `cat /etc/os-release`; verify resize (`stty size` changes).
5. Agent gate: `adb shell run-as app.dsh.mobile sh -c '<engine env> linux run alpine "apk --version"'`
   plus `linux list` / `linux status`.
6. Arm64: compile `assembleDebug -Pabi=arm64-v8a` (no device) to prove the NDK/ABI path.
