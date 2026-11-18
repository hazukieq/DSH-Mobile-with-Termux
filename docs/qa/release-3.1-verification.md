# 3.1 发布验证（2026-11-18）

本记录对应 `RELEASE_NOTES_v3.1.md` 的「验证与范围」一节，覆盖实机覆盖安装、引擎启动、
纪律插件加载、技能种子升级和浮窗回归。全程使用 ADB shell 与应用 UID，不调用 Root 或 Shizuku。

## 环境与安装

| 项 | 值 |
| --- | --- |
| 设备 | Redmi M2012K11AC（alioth），Android API 37 |
| 应用 | `com.termux` 3.1（versionCode 688） |
| 安装方式 | 覆盖安装，保留数据；`firstInstallTime=2026-10-31 11:52`，`lastUpdateTime=2026-11-18 09:53` |
| 应用 UID / 权限模式 | `10278` / `normal` |
| 已装引擎 | `0.2.2-rc.1+sharp1`（`$PREFIX/.dsh-engine-version`） |
| Node | `v24.22.1` |
| 签名 | `apksigner` 证书 SHA-256 `178dc39b…06401`，与仓库 release keystore 一致；能直接覆盖 3.0 安装即证明签名相同 |

装机使用 `android run --device 5f08bc20 --activity app.dsh.mobile.MainActivity <apk>`
（3.1 Release APK，见 `dist/release-3.1/`）。

## 纪律插件与指令迁移

安装后启动引擎（`adb logcat` 摘录，应用 UID）：

```text
I AgentContextSeed: AGENTS.md guard armed (bytes=31651, skills=task-execution-discipline, a11y-tap-discipline, evidence-discipline)
I EngineConfig: plugin deployed: discipline.mjs (2455 bytes, readable=true)
I EngineConfig: plugin deployed: discipline-reminder.md (1140 bytes, readable=true)
D EngineProcess: [android-discipline] pre-step reminders registered
```

- `AGENTS.md` 由种子 v120 重建，管理段包含三组纪律技能；用户段保留流程未改动。
- 插件与提醒文案部署到 `$PREFIX/android-plugins/`，逐文件散列与 APK 内资产一致：

| 文件 | SHA-256 |
| --- | --- |
| `discipline.mjs` | `c3b15bee97970c05535868ab042498f39a8cc93f8c177fa09131a9df324d736d` |
| `discipline-reminder.md` | `45f03b688c2562b2779ca11704882e648b6b1c77f63a76c3c630d4d94261b134` |

- 技能发现副本部署到 `$DSH_HOME/skills/`，散列与源文件一致，且已写入
  `.bundled-skill-state/*.sha256`（指纹升级路径）：

| 技能 | SHA-256 |
| --- | --- |
| `task-execution-discipline` | `e8cf47af2c18ed552a4a0424249e8c4263cf0b9700dd075a58d982a9cb002707` |
| `a11y-tap-discipline` | `a3f9b34b95cd93438fd5faf6cfa7d73a7857571693e4fdd2d6a36e0e14fcb958` |
| `evidence-discipline` | `7578eca161c94c7f47478013add3618494046e98757a4236595386c3cf591cfc` |

注入机制的行为边界由 7 项 Node 回归覆盖（首次/周期/新轮次/用户纠正/拒绝/空首步骤/重试去重）；
本次没有发起模型对话，因此不评测模型对提醒的长期遵循率。

## 浮窗回归

`com.termux.test` 窗口回归在 3.1 应用进程内执行，连续两次通过：

```text
INSTRUMENTATION_RESULT: appUid=10278
INSTRUMENTATION_RESULT: privMode=normal
INSTRUMENTATION_RESULT: stream=PASS: attach, expand, final frame, overlapping avoidance, callback cleanup, content restore, preview, off, exit/resume
INSTRUMENTATION_CODE: -1
```

断言覆盖挂窗、展开、末帧更新、截图/手势重叠避让、回调与引用清理、隐藏后内容恢复、预览、
`off` 与退出/恢复。另以 `-e holdMs 12000` 保持窗口并截图，
[overlay-idle.png](artifacts/release-3.1-2026-11-18/overlay-idle.png) 显示卡片在浮窗位置实际渲染，
原始回执见 [overlay-instrumentation.txt](artifacts/release-3.1-2026-11-18/overlay-instrumentation.txt)。

## 回归与门禁

```sh
bash scripts/acceptance.sh --skip-build --device 5f08bc20
```

`PASS (19 项)`：8 项静态门禁（i18n、文档端点、开发文档未随包、浮窗避让边界、pnpm 完整性、
引擎升级回归、纪律技能布局、纪律提醒回归）、JVM 单测、8 项 APK 内容断言、
装机启动以及真机引擎/隔离升级回归。

- JVM：Release 与 Debug 变体各 37 项、0 失败；其中 `BundledAgentSkillsTest` 7 项、
  `NodeToolingShimsTest` 7 项。
- 引擎升级：15 项离线集成回归通过（feed 过滤、版本/SHA-256 校验、坏包暂存保护、TMPDIR、
  Bash 门禁、rg 注入来源）。
- APK 断言确认 `assets/skills/*-discipline/SKILL.md`、`assets/android-plugins/discipline.mjs`
  随包，且 `mobile-ui` 与开发文档未混入。

## 未覆盖范围

- 没有发起模型请求，未评测纪律提醒对模型长期执行的影响。
- 没有在 vivo 上重跑 rc.4 构建或其 `read_image` 端到端验收。
- 本轮未测试退出后跨越 60 秒的保活观察；该项在 3.0 已验证，3.1 未改动该路径。
- 未发布独立引擎 tar 或在线更新 feed；`engine-manifest.json` 是 APK 溯源记录，不是热更新清单。
