# 文档索引

App 内 agent 首读 `assets/agents/DSH-MOBILE.md`。随包文档以
`app/src/main/assets/docs/` 为准，安装后部署到 `$DSH_HOME/docs`，升级时按内容刷新。
设备内用 `docs` 列目录，`docs ACCESSIBILITY` 阅读单篇。
`docs --fetch [tag或分支]` 从仓库更新文档，`--dry-run` 预览差异；更新不会删除本地旧文件。

## 使用说明（随包）

- `ACCESSIBILITY.md` / `.en.md`：读屏、点击、坐标与回执。
- `A11Y-SCOPE.md` / `.en.md`：无障碍依赖边界。
- `CLICK_CHAIN_UPGRADE.md`：点击链、OCR、事务操作。
- `android-agent-routes.md` / `.zh.md`：Android 运行环境与交付方式。
- `AGENT-PRIMITIVES.md`：动作原语。
- `PROGRESS_OVERLAY.md`：后台进度与浮窗使用。
- `OVERLAY.md`：浮窗当前行为、生命周期与避让约束。
- `NOTIFICATIONS.md` / `.en.md`：通知读取与发送。
- `PAYMENT-LINKS.md`：支付链接交接。
- `TERMINAL_AND_LINUX.md` / `.en.md`：内置终端与 Termux 环境。
- `LAN_WEBUI.md` / `.en.md`：局域网代理。
- `ENGINE_RESTART.md`：受控引擎重启与返回行为。
- `ENGINE-BUILD.md`、`CROSS_COMPILE.md`：引擎闭包与原生依赖构建。
- `DEVELOPMENT-ENV.md`：构建环境与验证入口。
- `FAST_PATH_PICKER.md`：精确/语义选择与可选本地模型。
- `SECURITY-AUDIT.md`：权限、组件、网络面与验证方法。
- `translation-mapping.md`：翻译映射。

## 仓库开发与证据（不随包）

- `ANDROID-CLI.md`：本项目 android CLI 使用约定。
- 根目录 `refactor_guide.md`、`code_review.md`：当前模块边界与审计记录。
- `ADB-DEBUG-CHANNEL.md`：主机调试桥接通道。
- `CREDITS.md`：许可与分发说明。
- `qa/`：带设备/版本范围的回归证据，不是当前实现规范。
- `APP-CASES/`：场景示例；`promo/`：宣传素材与生成脚本。

安装、构建与发布入口见仓库根目录 `README.md`；历史版本变更见对应 release notes。
