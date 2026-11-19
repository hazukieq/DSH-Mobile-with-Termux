# 3.1-fix-2 发布验证（2026-11-20）

对应 `RELEASE_NOTES_v3.1-fix-2.md`。本版只改纪律提醒插件与默认配置，不涉及浮窗、`/tmp`
映射与 apt 镜像；后两者沿用 3.1-fix-1 的验证。全程 ADB + 应用 UID，不调用 Root / Shizuku。

## 环境与安装

| 项 | 值 |
| --- | --- |
| 设备 | vivo V2520A（PD2520），Android 17 / API 37 |
| 应用 | `com.termux` 3.1-fix-2（versionCode 690） |
| 安装 | 覆盖安装保留数据；`firstInstallTime=2026-10-28 13:07:43`，`lastUpdateTime=2026-11-20 05:13:27` |
| 应用 UID / 模式 | `10487` / `normal` |
| 引擎 | 设备自建 `0.2.2-rc.4+local1`（APK 内置 `0.2.2-rc.1+sharp1` 未替换） |
| 签名 | `178dc39b…06401`，与仓库 release keystore 一致 |

## 提醒策略生效

覆盖安装并启动后，引擎日志：

```text
[+5687ms] [android-discipline] pre-step reminders registered {"mode":"trigger"}
```

配置已播种，后续归用户所有：

```json
{
  "mode": "trigger"
}
```

引擎健康检查 `http=401`（需要 token，说明 `:3080` 已在监听）；`DSH-MOBILE.md` 为 `app doc v82`，
包含三种模式与注入日志说明。

记录见 `docs/qa/artifacts/release-3.1-fix-2-2026-11-20/`。

## 回归

- 纪律提醒回归（`node --test scripts/tests/test-discipline.mjs`）：**10 项通过**，新增
  trigger 轮首/安全间隔、失败回执触发、off 不注入三组用例。
- 静态门禁：**8 项通过**（i18n、文档端点、开发文档未随包、悬浮窗避让、pnpm、引擎升级回归、
  纪律技能布局、纪律提醒回归）。
- JVM 单测：**37 项通过**；arm64 Release 构建与 `apksigner` 签名校验通过。

## 范围与限制

- 本版未重跑完整真机验收（浮窗 / `/tmp` / apt 源不涉及）；Redmi 本轮未参与。
- trigger 模式的失败判定基于回执文本中的 `ok/verified/changed` 字段与
  `staleFullScreenNode`，非结构化解析；未知回执形态可能与旧版一样静默。
- 改 `mode` 后需重启引擎生效；未评测模型长期执行遵循率。
