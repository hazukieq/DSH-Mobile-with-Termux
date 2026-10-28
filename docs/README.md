# docs 索引

> 这份目录随 App 打包：安装/升级后在设备上用 `docs` 门读（`docs` 列目录、`docs ACCESSIBILITY` 打印单篇），
> 落盘位置 `$DSH_HOME/docs`，每次 App 更新按内容比对自动刷新。

App 内置文档是 `app/src/main/assets/agents/DSH-MOBILE.md`（agent 首读）；这里放细节展开与实测记录。
按问题找文件：

## 能做什么 / 怎么用
- `ACCESSIBILITY.md` · `ACCESSIBILITY.en.md` —— 无障碍能力全景：端点语义、坐标 tap 语义与回归修复、
  §8.7 `ownOverlay` 命中与绕行、§9 能力矩阵、§10 回执必读 / 坐标来源两条纪律。
- `CLICK_CHAIN_UPGRADE.md` —— 多步点击链的写法与失败重试策略。
- `CREDITS.md` —— 参考与引用：依赖了哪些开源项目、各自许可与分发义务（Termux GPL-3.0 等）。
- `CROSS_COMPILE.md` —— 交叉编译指南：Android 上没有 wheel 的 Python 包（C/C++/Rust 扩展）如何在设备内或主机 NDK 上编出来。
- `android-agent-routes.md` · `android-agent-routes.zh.md` —— AgentBridge 路由清单（端点级）。
- `A11Y-SCOPE.md` · `A11Y-SCOPE.en.md` —— 无障碍服务的边界：哪些操作需要它、哪些绝不需要。

## 通知 / 悬浮窗 / 支付跳转
- `NOTIFICATIONS.md` · `NOTIFICATIONS.en.md` —— 通知栏读写、shade 场景。
- `PROGRESS_OVERLAY.md` —— 悬浮窗进度与折叠布局。
- `PAYMENT-LINKS.md` —— 支付宝/微信支付链接与 `intent://` 改写。

## 终端 / 引擎 / 网络
- `TERMINAL_AND_LINUX.md` · `TERMINAL_AND_LINUX.en.md` —— 内置终端（PTY / VT100，与 AI 同环境）。
- `ENGINE_RESTART.md` —— 引擎重启与 WebView 返回栈。
- `LAN_WEBUI.md` · `LAN_WEBUI.en.md` —— 局域网 WebUI 与代理。
- `NARROW_UI.md` · `NARROW_UI.en.md` —— 窄屏布局。

## 实测记录（回归证据，按日期）
- `testing/` 下其它文件同规则：一个主题一份，含设备/版本/方法/结论。

## 发布
- 根目录 `README.md` 的「安装」段与 GitHub Releases；版本号与改动见 release notes。
