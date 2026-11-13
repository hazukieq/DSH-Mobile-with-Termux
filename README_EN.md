# DSH Mobile with Termux

**Run DeepSeek Harness on Android and give your agent access to phone UI, files, and command-line tools.**

[Download APK](https://github.com/hazukieq/DSH-Mobile-with-Termux/releases) · [Documentation](docs/README.md) · [Issues](https://github.com/hazukieq/DSH-Mobile-with-Termux/issues) · [中文](README.md) · [Deutsch](README.de.md)

DSH Mobile bundles an Android-adapted DeepSeek Harness engine, WebUI, Node.js, and a Termux userland.
Use conversations, tools, and plugins on your phone without installing Termux separately. Neither Root nor a computer is required.

The engine runs on the phone. Chat inference uses the model endpoint and API key you configure;
the APK does not include a conversational large language model. Local OCR and optional icon detection help read phone UI.

## Features

| Capability | What it provides |
|---|---|
| Conversations and tools | Embedded WebUI with sessions, tools, and plugins; narrow-screen layout comes from the engine WebUI |
| Phone control | Read UI, tap, swipe, type, and capture screenshots through an authorized accessibility service and the local device bridge |
| UI recognition | Bundled ML Kit by default, optional in-process PP-OCRv6 and icon detection; OCR needs no Python installation |
| Terminal and files | PTY terminal sharing the engine's Termux environment, `pkg` / `apt` package management, file handling and sharing |
| Background progress | Floating ball/card, notifications, or no progress display; scrollable text, edge snapping, session and elapsed time |
| Android integration | Open apps and links, share, play media, control volume, and hand APK installation to the system installer for confirmation |
| LAN access | Optional LAN WebUI proxy with an access code, letting another device use the engine running on your phone |

## Get started

1. Download a compatible APK from [Releases](https://github.com/hazukieq/DSH-Mobile-with-Termux/releases). The usual device build is `arm64-v8a`.
2. Open the app and choose language, orientation, and permission mode. Start with **Normal** mode.
3. Wait for the bundled runtime to unpack and the engine to start, then configure your model endpoint and API key in the WebUI.
4. Grant notifications, file access, or accessibility when needed for your tasks.

The source minimum is Android 8.0 (API 26); runtime and UI support also depend on the device and WebView.
Cross-app screenshots require Android 11 (API 30) or later and a connected accessibility service.
The app prompts for an update when the WebView is too old.

**Termux package conflict:** this app uses `com.termux` and cannot coexist with another app using that package name.
Back up existing Termux data before switching. APKs signed with different keys cannot update each other in place.
See the [terminal guide](docs/TERMINAL_AND_LINUX.en.md).

## Permissions and progress

Normal mode supports the engine, terminal, file tools, and accessibility-based phone control.
Phone control and floating progress do not require Root.

| Option | Scope |
|---|---|
| Normal | Engine runs as the app UID; built-in maintenance and fallback paths do not automatically invoke su or Shizuku |
| Shizuku | Authorized shell capabilities; the engine keeps the app UID and a failed channel does not fall back to Root |
| Root | Explicitly runs the engine with Root privileges, expanding what the agent can read and modify |
| Accessibility | Reads other apps' UI, injects actions, captures the screen, and receives related events; ordinary engine, terminal, sharing, and file operations remain independent |
| Display over other apps | Needed for application overlays when accessibility is disconnected; a connected accessibility service can host the progress overlay directly |
| Notifications and files | Enable notification delivery and file access within the granted scope |

Tap the ball to expand, tap the card's left dot to collapse, and drag to reposition.
Choose overlay, notifications, or off in settings. Turning progress off leaves accessibility, OCR, and the terminal available.
The notification's Exit action stops the engine and removes the overlay; opening the app restores operation.

See [accessibility requirements](docs/A11Y-SCOPE.en.md) and [progress display](docs/PROGRESS_OVERLAY.md).

## Data and network

The engine WebUI defaults to `127.0.0.1:3080`; the device bridge uses `127.0.0.1:3083` with request-token validation.
LAN access is off by default and uses a separate proxy with an access code when enabled.

Configuration and credentials live in app-private storage. Sessions and workspaces may be moved to shared storage.
Model requests, search, web fetching, TTS, downloads, and plugin execution may use the network.
External data transmission depends on your configured services and tasks. Check actual private and external storage
locations when backing up; a logical path alone does not tell you what survives uninstalling.

See [permissions and audit notes](docs/SECURITY-AUDIT.md) for network entry points, data, debug access, and APK checks.

## Build from source

Use JDK 17, Gradle 8.9, Android SDK 36, CMake 3.22.1, and a corresponding NDK. There is no Gradle wrapper.

**Prepare runtime assets first.** The Termux bootstrap and adapted engine archives are large files excluded from Git;
a source clone alone is insufficient to produce a usable APK. Follow the [build environment](docs/DEVELOPMENT-ENV.md)
and [engine build guide](docs/ENGINE-BUILD.md), including pnpm and native dependency payloads, then run:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
bash scripts/acceptance.sh --static
gradle testDebugUnitTest
gradle assembleRelease -Pabi=arm64-v8a
```

Output: `app/build/outputs/apk/release/app-release.apk`. Use the project's Gradle workflow rather than `android build`.
Configure release signing through `keystore.properties` or environment variables; without it, release builds use a debug key.
See [signing and validation](docs/DEVELOPMENT-ENV.md).

Prefer the android CLI for device installation, capture, and interaction; see the [CLI guide](docs/ANDROID-CLI.md).
The [physical-device overlay report](docs/qa/overlay-physical-normal-report.md) records the tested build, device, results, and coverage limits.

## Documentation and credits

Start with the [documentation index](docs/README.md), [phone control](docs/ACCESSIBILITY.en.md),
[terminal](docs/TERMINAL_AND_LINUX.en.md), [overlay behavior](docs/OVERLAY.md), or [LAN WebUI](docs/LAN_WEBUI.en.md).
Some detailed development documents are in Chinese.

Forked from [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android),
built on [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) and [Termux](https://github.com/termux/termux-app),
with ML Kit, PaddleOCR, ONNX Runtime, and other components.

Repository code is under the [MIT license](LICENSE), retaining the original copyright notices.
Bundled runtimes, dependencies, and models retain their own licenses. See [credits and distribution notes](docs/CREDITS.md).
