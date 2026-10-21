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

本项目是 **[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)** 的分叉，遵循原项目的 **MIT** 许可（见 [LICENSE](LICENSE)）。

| 角色 | 仓库 |
|---|---|
| **本仓库（分叉）** | `hazukieq/DSH-Mobile-with-Termux` |
| **上游 App 项目** | [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) |
| **引擎上游** | [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) |

分叉保留上游的完整 git 历史；本仓库只在上游基础上做增量修改（见下一节）。所有原始版权归 Soodok；分叉修改版权归本仓库作者。

---

## 🍴 本分叉做了什么

在上游 App 的基础上，本分叉主要补齐「后台可见性」与「引擎重启健壮性」：

- **悬浮窗进度（引擎事件驱动，非 DOM 抓取）**：通过 DSH 官方插件 API
  `ctx.on('agent/assistant-stream')` / `ctx.on('session/event')` 获取 reasoning、tool-call、
  tool-result、回答文本，按 `[思考]/[工具]/[结果]/[回复]` 组织成实时链路，POST 到 App 的
  AgentBridge 后由悬浮窗渲染。
- **悬浮窗交互**：折叠球 → 点击展开圆角卡片；正文实时增长并自动滚到最新行；卡片高度有上限
  （内部滚动）；贴左右边（拖动后吸附最近边，垂直位置保留）；无障碍/自动化状态走**独立的
  notice 小条**，不覆盖思考链。
- **完成收尾可靠**：监听 `agent/status` 的 idle 事件强制收尾为「已完成」，运行中每 2.5s 心跳，
  不会出现「会话结束了但浮窗卡在半截、状态不变」。
- **dshmarket「立即重启」竞态修复**：监督器解析 `/proc/net/tcp{,6}` 的 LISTEN socket，
  杀掉非受管引擎却占着 3080 的进程，并在 spawn 前等待端口释放——解决市场按钮自起
  `dsh web` 与 EngineService 抢端口导致的 `ERR_CONNECTION_REFUSED` 重启风暴。
- **plugin-health 探针 disposal 安全**：Cordis context 销毁后不再因 `ctx.loader` 抛
  `cannot get required service "loader" in inactive context` 而崩溃。
- **文档**：`AGENTS.md` / `DSH-MOBILE.md` 写明 agent 重启引擎的受控方式
  （`dsh-update restart`、`POST /engine/restart`）与禁用做法（杀进程、市场按钮）。

上游已有的完整能力（内置终端、可选 Linux rootfs、Termux `pkg/apt`、社区插件、局域网 WebUI、
无障碍设备操作、三级权限、竖屏 UI 适配等）全部保留，详见 [上游 README](https://github.com/Soodok/Deepseek-Harness-Local-Android#readme)。

---

## ✨ 功能概览（继承自上游）

- **完整 Agent 引擎**：官方 dsh 引擎（本分叉适配 `0.2.1-rc.2`）连同 Node.js 运行时、工具链
  跑在应用沙箱内，只监听 `127.0.0.1`；会话、凭证、工作区全部留在设备本地。
- **Termux 用户态**：与 Termux 同包名 `com.termux`，`<files>/usr` 即 `$PREFIX`，
  `pkg` / `apt` / `dpkg` 原生可用，真 bash（`[[ ]]`、`=~`、数组）。
- **内置终端**：原生 VT100/xterm + 真实 PTY，多会话、回滚、CJK 宽字符、真彩色、可调字号。
- **可选 Linux 用户空间**：Alpine / Ubuntu Base 经 proot 以伪 root 运行，按需下载，glibc/apt
  场景使用；`linux run` 让 AI 直接把命令送进发行版执行。
- **社区插件**：App 内搜索 npm 上的 dsh 生态插件，一键安装/卸载；插件走引擎官方客户端机制。
- **局域网 WebUI**：显式开关 + 随机 6 位验证码，界面就是手机里正在运行的原生 WebUI。
- **无障碍设备操作**：读屏/点击/滑动/输入/截图等能力，经只监听回环的能力桥与 token 门控。
- **竖屏 UI 适配**：窄屏侧栏/设置/预览面板自动切换为手机布局，通过客户端插件实现，不改引擎源码。
- **后台进度与彻底退出**：通知/悬浮窗展示任务进展；退出时停止引擎、代理与推理子进程并释放内存。

文档入口：[`docs/`](docs/)（无障碍、局域网 WebUI、窄屏 UI、构建等）。

---

## 📦 安装

1. 从本仓库的 [Releases](../../releases) 下载 `arm64-v8a` 的 APK（或自行构建，见下）。
2. 安装后首次启动会完成运行时初始化（引擎内置在 APK 内，不需要另外下载引擎）。
3. 在 WebUI 里配置你的模型服务地址与 API Key；默认所有服务只监听回环地址。

> 旧版本升级会保留数据；卸载会清理 `Android/data/` 下的外置数据，长期备份请自行导出。

## 🔧 构建

前置：JDK 17、Android SDK（`ANDROID_HOME` 指向 SDK），Gradle 8.9。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle -p . assembleRelease -Pabi=arm64-v8a
# 产物：app/build/outputs/apk/release/app-release.apk
```

仓库内置的运行时快照（`app/src/main/assets/runtime.zip`）与签名文件不入库，构建机需按上游
CI 的方式注入；如果需要带签名的发布包，请使用自己的 keystore。

## 🔄 同步上游

```bash
git remote add upstream https://github.com/Soodok/Deepseek-Harness-Local-Android.git
git fetch upstream
git merge upstream/port/upstream-v1.3.3   # 分支名以上游实际为准
```

本仓库的增量提交集中在 `fix(overlay)`、`fix(engine)`、`docs(agents)` 等前缀下，便于 review。

## 🔐 数据与隐私

- 引擎只监听 `127.0.0.1`；是否离开设备取决于你配置的模型服务地址。
- 能力桥 token 每次启动随机生成，只注入引擎环境；同设备第三方 App 无 token 会被拒绝。
- 会话、凭证、工作区默认在应用私有存储；外置数据目录卸载时会被系统清理，注意备份。

## 📄 许可证与致谢

- **MIT License** — 原始版权 `Copyright (c) 2026 Soodok`，分叉修改版权归本仓库作者。
  完整文本见 [LICENSE](LICENSE)；分发时请保留原版权声明。
- 感谢 [Soodok](https://github.com/Soodok) 及所有上游贡献者做出的完整 Android 移植。
- 引擎与插件生态归 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 作者所有，
  遵循其仓库许可证。

---

## English summary

**DSH Mobile with Termux** is a **fork of
[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)**
(MIT). It runs the official DeepSeek Harness engine inside an Android app sandbox with a bundled
Termux userland — no root, no PC.

This fork keeps all upstream features (built-in terminal, optional Linux rootfs, `pkg`/`apt`,
community plugins, LAN WebUI, accessibility automation, three permission tiers) and adds:

- progress in the floating window driven by the official plugin events
  (`agent/assistant-stream`, `session/event`), rendered as a live `[思考]/[工具]/[结果]/[回复]` chain;
- a draggable, edge-docked card whose body auto-scrolls, plus a separate notice area for
  accessibility/automation status;
- reliable completion (idle-status flush + heartbeat) instead of a chain stuck mid-way;
- a restart-race fix against dshmarket's self-spawned `dsh web` (port 3080), and a disposal-safe
  `plugin-health` probe;
- documented, controlled engine-restart paths for agents.

License: **MIT** (original copyright Soodok; fork modifications by this repository's author).
