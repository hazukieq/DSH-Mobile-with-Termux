# DSH Mobile 3.1-fix-2

紧急修复版：把每轮固定注入的执行纪律摘要改为**触发式**，降低固定 token 开销，同时保留
「回执不等于成功」「失败两次换线」「不按预览图猜坐标」三条最关键的提醒。

## 安装与升级

- APK：`dsh-mobile-3.1-fix-2-arm64-v8a.apk`；应用版本 `3.1-fix-2`，versionCode `690`。
- 沿用原发布签名（证书 SHA-256 `178dc39b…06401`），包名 `com.termux`；同签名旧版（含 3.1 / 3.1-fix-1）
  可直接覆盖升级并保留数据。
- 内置引擎仍为 `0.2.2-rc.1+sharp1`，不会替换用户自建的引擎。
- `SHA256SUMS` 校验附件；`engine-manifest.json` 记录 APK 与内置载荷溯源，**不是引擎热更新 feed**。

## 纪律提醒改为触发式

- 新增 `$DSH_HOME/.dsh-discipline.json`，缺失时 App 自动播种 `{"mode":"trigger"}`，之后归用户所有。
- `trigger`（默认）只在以下时机各注入一次，并使用约 100 token 的三条短版：
  - 轮首 / 用户插话或纠正；
  - 失败回执（`ok:false`、`verified:false`、`changed:false`、`staleFullScreenNode`）；
  - 长 tool 链每 20 步的兜底。
- `every`：保留旧行为（轮首 + 每 6 步，完整正文）；`off`：完全不注入。
- 每次注入写日志：
  `[android-discipline] reminder injected {"mode":…,"reason":…,"turn":…,"step":…,"chars":…}`，
  可在 `$DSH_HOME` 的引擎日志中核对触发原因与开销。
- 完整纪律正文仍在 `AGENTS.md` 系统提示中，注入只做短增量提醒，不重复全文。

## 验证与范围

- 纪律回归 10 项、静态门禁 8 项、37 项 Release JVM 单测通过；arm64 Release 构建与签名校验通过。
- vivo V2520A / Android 17（API 37），Normal 模式、应用 UID 10487 覆盖安装；引擎日志确认
  `pre-step reminders registered {"mode":"trigger"}`，配置文件已生成，引擎 `:3080` 健康。
- 详细证据见 `docs/qa/release-3.1-fix-2-verification.md`。
- 本版未改浮窗、`/tmp` 映射与 apt 镜像逻辑（沿用 3.1-fix-1）；Redmi 本轮未重跑完整设备验收。
- 未评测模型长期执行遵循率；trigger 模式依赖失败回执文本可识别（`ok/verified/changed` 字段）。
