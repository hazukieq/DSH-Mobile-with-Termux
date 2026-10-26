# DSH Mobile with Termux

<p align="center">
  <img src="docs/promo/dsh-mobile-github.png" alt="DSH Mobile with Termux — DeepSeek Harness for Android" width="100%">
</p>

**DeepSeek Harness 的 Android 完整移植（分叉版）：官方 dsh 引擎原样跑在手机沙箱里，自带 Termux 用户态，无需 Root、无需电脑。**

[![License: MIT](https://img.shields.io/badge/license-MIT-brightgreen)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![Fork](https://img.shields.io/badge/fork-of%20Soodok%2FDeepseek--Harness--Local--Android-blue)
![Engine](https://img.shields.io/badge/engine-dsh%200.2.1--rc.2-orange)

> 🌐 英文简介见文末 · [English summary](#english-summary)

---

## ⚠️ 这是分叉（Fork）

本项目是 **[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)**
的分叉，遵循 **MIT** 许可（见 [LICENSE](LICENSE)）。

| 角色 | 仓库 |
|---|---|
| 本仓库（分叉） | `hazukieq/DSH-Mobile-with-Termux` |
| 上游 App 项目 | [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) |
| 引擎上游 | [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) |

分叉保留上游完整 git 历史；原始版权归 Soodok，分叉修改版权归本仓库作者。

---

## 🍴 本分叉改了什么

| 方向 | 改动（可在 git log 中按前缀检索） |
|---|---|
| **浮窗进度** | 改为引擎事件驱动：host 侧插件订阅 `agent/assistant-stream` / `session/event`，推送 `[思考]/[工具]/[结果]/[回复]` 链路；不再抓 WebUI DOM |
| **浮窗交互** | 折叠球 → 展开圆角卡片；正文实时增长、自动滚底、内部滚动；贴左右边（拖动后吸附最近边、垂直位置保留）；无障碍/自动化状态走**独立 notice 条** |
| **收尾可靠** | 监听 `agent/status` 的 `idle` 强制收尾「已完成」；运行中 2.5s 心跳；`done/error` 永不去重 |
| **文本完整** | 端到端上限 16000 字；思考 1400、工具行 420、回复保留末尾 4000；展开后正文可自由滚动 |
| **引擎重启** | 市场「立即重启」与 EngineService 抢 3080：supervisor 解析 `/proc/net/tcp{,6}` 清掉外来监听，spawn 前等端口释放；`plugin-health.mjs` 做 disposal 安全 |
| **agent 文档** | `AGENTS.md`/`DSH-MOBILE.md` 写明分叉仓库、受控重启（`dsh-update restart`、`POST /engine/restart`）与禁用做法 |

相关提交：`fix(overlay)`、`fix(engine)`、`docs(agents)`、`chore(release)`。

---

## ✨ 针对性功能清单

### 1. 引擎与 Termux 运行时
- 官方 dsh 引擎（本分叉适配 `0.2.1-rc.2`）+ bionic 版 Node.js 全量打包进 APK；引擎只监听 `127.0.0.1`。
- 与 Termux 同包名 `com.termux`，`<files>/usr` 即 `$PREFIX`：`pkg` / `apt` / `dpkg` / 真 bash 原生可用。
- 内置终端：原生 VT100/xterm + 真实 PTY，多会话、回滚、CJK 宽字符、真彩、可调字号（[docs/TERMINAL_AND_LINUX.md](docs/TERMINAL_AND_LINUX.md)）。
- 可选 proot Linux（Alpine / Ubuntu Base，按需下载）：`linux run/list/status/exec/shell`，用于 glibc / apt 场景。

### 2. UI 适配（客户端插件，不改引擎源码）
- `narrow-sidebar` 侧栏全宽、`narrow-settings` 设置竖排、`narrow-rightbar` 右侧面板全屏、`narrow-welcome` 首启欢迎页断点。
- `mobile-ui`：平板（≥600 CSS px）只在竖屏启用移动布局，横屏回落到原生桌面 UI。
- 全部通过 `dsh.client` 机制部署，卸载即恢复（[docs/NARROW_UI.md](docs/NARROW_UI.md)）。

### 3. 后台进度与悬浮窗（本分叉重点）
- 两种模式：**通知**（13+ 需通知权限）与 **悬浮窗**（SYSTEM_ALERT_WINDOW，默认）。
- 数据来自引擎官方插件事件，不是定时抓 DOM；链路带步骤标签与耗时心跳。
- 悬浮窗：展开卡片、贴边、独立 notice、长文本滚动；前台/后台都常驻（[docs/PROGRESS_OVERLAY.md](docs/PROGRESS_OVERLAY.md)）。

### 4. Agent 的手机能力（原生层 + 能力桥）
- 21 种动作原语：`tap / tap-id / tap_text / swipe / scroll / scroll_find / scroll_collect / repeat / repeat_until / double_tap / long_press / pinch / drag / back_to / input / key / launch / intent / set_switch / noop / collect`。
- **跨 App 支付跳转**：`scr open "weixin://..."` / `"alipays://..."` / `https://qr.alipay.com/...`（自动改写成支付宝 scheme）/ `intent://`，配合 `scr wait-app` 等用户付完回到会话（[docs/PAYMENT-LINKS.md](docs/PAYMENT-LINKS.md)）。
- 多层视觉兜底：无障碍节点树 → OCR → 图标识别 → 帧差；逐步 PICK → ACT → VERIFY → RETRY（[docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md)）。
- 跨 App 跳转与回流：`POST /open` 直接送到微信/支付宝/浏览器（App Link、`weixin://`、`alipays://`、指定包/Activity，回执带真实前台 `before/after/changed`），`POST /wait-app` 等用户付完回到前台 —— 覆盖 MCP 下单后必须去别的 App 付款/授权这类流程（v2.5.2，`scr open` / `scr wait-app`）。**跳转本身不需要无障碍**（`startActivity` 就是普通 API）；只有「观察别的 App 是否在前台」才需要。
- **无障碍只服务于一件事**：读别的 App 的界面/像素、往别的 App 注入手势按键文字、读无障碍事件。启动/跳转/分享/媒体播放/通知/TTS/文件/引擎/PTY/插件、以及 App 自己窗口的截图（`appshot`，PixelCopy）**一律不需要**无障碍，关着也照常工作（完整清单：[docs/A11Y-SCOPE.md](docs/A11Y-SCOPE.md)）。
- 系统集成：打开/分享到指定 App、接收外部分享进当前会话、SAF DocumentsProvider（文件管理器可见 `DSH Mobile`）、原生 MediaPlayer 播放音频、`trash` 删除、通知与 TTS（`say`）。
- 能力桥只监听 `127.0.0.1:3083`，token 每次启动随机生成（[docs/android-agent-routes.md](docs/android-agent-routes.md)）。

门禁集合只由 `A11Y_DEPENDENT_REASONS` 派生（`A11Y_INDEPENDENT_ACTIONS` 自检兜底），启动/跳转/截图这类能力与无障碍无关。

### 5. 语音（当前没有入口）
- 语音面板与识别链路（系统 `SpeechRecognizer` / 云端 OpenAI 兼容 ASR）代码保留，但**当前没有任何入口**：悬浮窗麦克风已移除，主界面也不再申请录音权限，所以功能不可达。

### 6. 网络、调试与可观测性
- 局域网 WebUI：显式开关 + 随机 6 位验证码，电脑浏览器打开的是手机里的原生 UI（[docs/LAN_WEBUI.md](docs/LAN_WEBUI.md)）。
- WebView CDP 默认开启，agent 可用 Playwright 连真实页面排查；`pnpm/node` 真机用法写进 agent 文档。
- 通知体系：任务状态、长任务步数、局域网开启、完成/失败（[docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md)）。

### 7. 权限、保活与数据
- 三级权限：普通 / Shizuku / Root；普通模式有 `su` 闸门，防止沙箱内 Agent 提权。
- 保活：前台服务 + 电池优化白名单引导 + 后台任务 PARTIAL_WAKE_LOCK + 厂商限制说明；正常退出后**绝不自动重启引擎**。
- 数据：会话/工作区在 `Android/data/.../dshdata`（可见可备份），`profiles/`（含凭证）留在私有目录；卸载会清理外置数据。

### 8. 引擎重启与稳定性（分叉）
- 受控路径：`dsh-update restart`、`POST /engine/restart`；受管引擎由 supervisor 统一拉起。
- 市场「立即重启」的 detach spawn 会与受管引擎抢端口：已由 supervisor 清理外来监听 + 等端口释放修复；细节见 [docs/ENGINE_RESTART.md](docs/ENGINE_RESTART.md)。

### 文档索引

| 文档 | 内容 |
|---|---|
| [docs/PROGRESS_OVERLAY.md](docs/PROGRESS_OVERLAY.md) | 悬浮窗三段式展开卡片、事件来源、贴边与文本上限 |
| [docs/ENGINE_RESTART.md](docs/ENGINE_RESTART.md) | 重启路径、dshmarket 竞态修复、plugin-health 安全性 |
| [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md) | 无障碍动作原语、视觉兜底、验证闭环 |
| [docs/PAYMENT-LINKS.md](docs/PAYMENT-LINKS.md) | 微信/支付宝支付跳转：支持哪些链接、调用序列、`wait-app` 回流、安全边界 |
| [docs/A11Y-SCOPE.md](docs/A11Y-SCOPE.md) / [.en](docs/A11Y-SCOPE.en.md) | 哪些能力需要无障碍、哪些永远不需要、关着时怎么降级（分类以代码为准） |
| [docs/NARROW_UI.md](docs/NARROW_UI.md) | 竖屏/平板 UI 客户端插件 |
| [docs/TERMINAL_AND_LINUX.md](docs/TERMINAL_AND_LINUX.md) | 内置终端与可选 proot Linux |
| [docs/LAN_WEBUI.md](docs/LAN_WEBUI.md) | 局域网访问原生 WebUI |
| [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md) | 通知体系与后台行为 |
| [docs/android-agent-routes.md](docs/android-agent-routes.md) | 能力桥路由（agent 视角） |

---

## 📦 安装

1. 从本仓库的 [Releases](../../releases) 下载 `arm64-v8a` APK（或自行构建）。
2. 首次启动会解压内置运行时（引擎在 APK 内，无需另外下载）。
3. 在 WebUI 配置模型服务地址与 API Key；默认只监听回环地址。

## 🔧 构建

前置：JDK 17、Android SDK（`ANDROID_HOME`）、Gradle 8.9。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle -p . assembleRelease -Pabi=arm64-v8a
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 🔄 同步上游

```bash
git remote add upstream https://github.com/Soodok/Deepseek-Harness-Local-Android.git
git fetch upstream
git merge upstream/<branch>
```

## 🔐 数据与隐私

- 引擎只监听 `127.0.0.1`；是否离开设备取决于你配置的模型服务地址。
- 能力桥 token 每次启动随机，只注入引擎环境。
- 卸载会清理 `Android/data/` 下的外置数据，长期备份请自行导出。

## 📄 许可证与致谢

- **MIT License** — 原始版权 `Copyright (c) 2026 Soodok`，分叉修改版权归本仓库作者；分发请保留原版权声明。
- 感谢 [Soodok](https://github.com/Soodok) 与所有上游贡献者的完整 Android 移植。
- 引擎与插件生态归 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 作者，遵循其仓库许可证。

---

## English summary

**DSH Mobile with Termux** is a **fork of
[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)**
(MIT) that runs the official DeepSeek Harness engine plus a bundled Termux userland on Android.

This fork adds: engine-event-driven floating-window progress (live labelled chain, edge dock,
separate accessibility notice, reliable completion, 16000-char text budget), a restart-race fix
against dshmarket's detached `dsh web` (port 3080), a disposal-safe `plugin-health` probe, and agent
docs that name the fork repository and the controlled restart paths.

Inherited upstream features include: full dsh engine in the app sandbox, Termux `pkg/apt`, built-in
native terminal, optional proot Linux, client-side narrow/mobile UI plugins, community plugins,
LAN WebUI, accessibility automation with multi-layer visual fallbacks, native intents/share/SAF/
MediaPlayer integration, three permission tiers and keep-alive.

License: **MIT** (original copyright Soodok; fork modifications by this repository's author).
