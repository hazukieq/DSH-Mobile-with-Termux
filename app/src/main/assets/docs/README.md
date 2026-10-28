# docs 索引

> 这份目录随 App 打包（当前 22 篇）：安装/升级后在设备上用 `docs` 门读（`docs` 列目录、
> `docs ACCESSIBILITY` 打印单篇），落盘位置 `$DSH_HOME/docs`，每次 App 更新按内容比对自动刷新。
> 想立刻拿到仓库最新文档而不用等发版：`docs --fetch`（可加 `--dry-run` 先看差异，
> 或 `docs --fetch v2.5.52` 指定 tag/branch）；它是覆盖式更新，**不删**仓库里已没有的本地文件。
> **不随包**的只有两类：`CREDITS.md`（许可与分发义务，仓库内查阅；`docs --fetch` 会一并拉下来，
> 便于离线看许可证）与 `testing/`（仓库内的回归实测记录）。

App 内置文档是 `app/src/main/assets/agents/DSH-MOBILE.md`（agent 首读）；这里放细节展开与实测记录。
按问题找文件：

## 能做什么 / 怎么用
- `ACCESSIBILITY.md` · `ACCESSIBILITY.en.md` —— 无障碍能力全景：端点语义、坐标 tap 语义与回归修复、
  §8.7 `ownOverlay` 命中与绕行、§9 能力矩阵、§10 回执必读 / 坐标来源两条纪律。
- `CLICK_CHAIN_UPGRADE.md` —— 多步点击链的写法与失败重试策略。
- 依赖许可与分发义务见仓库根 `docs/CREDITS.md`（**不随包**，不上机）。
- `CROSS_COMPILE.md` —— 交叉编译指南：Android 上没有 wheel 的 Python 包（C/C++/Rust 扩展）如何在设备内或主机 NDK 上编出来。
- `android-agent-routes.md` · `android-agent-routes.zh.md` —— AgentBridge 路由清单（端点级）。
- `A11Y-SCOPE.md` · `A11Y-SCOPE.en.md` —— 无障碍服务的边界：哪些操作需要它、哪些绝不需要。

## 通知 / 悬浮窗 / 支付跳转
- `NOTIFICATIONS.md` · `NOTIFICATIONS.en.md` —— 通知栏读写、shade 场景。
- `PROGRESS_OVERLAY.md` —— 悬浮窗进度与折叠布局。
- `PAYMENT-LINKS.md` —— 支付宝/微信支付链接与 `intent://` 改写。

## 终端 / 引擎 / 网络
- `ENGINE-BUILD.md` —— 引擎层构建：§1 只换 JS 闭包（20 秒滚动升级）、§2 Android 独有库
  十字编（NDK / C++ / Rust / Python / N-API + bionic 错误对照表）、§3 收集 Termux 运行时。
- `TERMINAL_AND_LINUX.md` · `TERMINAL_AND_LINUX.en.md` —— 内置终端（PTY / VT100，与 AI 同环境）。
- `ENGINE_RESTART.md` —— 引擎重启与 WebView 返回栈。
- `LAN_WEBUI.md` · `LAN_WEBUI.en.md` —— 局域网 WebUI 与代理。

## 安全 / 审计
- `SECURITY-AUDIT.md` —— 权限表、对外组件、网络面、数据落盘、供应链校验、审计 prompt。
  取证脚本在**仓库**里（不随包）：`scripts/audit-apk.sh` + `scripts/lib/manifest-components.py`；
  手机上要跑就先 `pkg install git` 再 `git clone` 本仓库（或直接在 PC 上跑）。

## 实测记录（回归证据，按日期）
- 仅仓库内 `testing/`（**不随包**）：一个主题一份，含设备/版本/方法/结论。

## 发布
- 根目录 `README.md` 的「安装」段与 GitHub Releases；版本号与改动见 release notes。
