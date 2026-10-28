# 无障碍（a11y）依赖范围：哪些端点需要，哪些永远不需要

> **一条规则。** 只有同时满足下面三条之一的端点才需要无障碍服务：
> (a) 读**别的 App** 的 UI 节点树或屏幕像素；
> (b) 往**别的 App** 注入手势/按键/文本；
> (c) 消费无障碍事件（节点/窗口/触摸干预/通知事件）。
> 其余一切 —— 启动 App、跳转交接、分享、媒体播放、发通知、TTS、文件读写、
> 引擎/PTY/插件控制、截**本 App 自己**的窗口 —— 都是普通 Android API，
> 无障碍关掉也必须照常工作。

英文版：[A11Y-SCOPE.en.md](A11Y-SCOPE.en.md)。

## 1. 本文件的用途

门禁集合由逐路径原因表派生（`A11Y_DEPENDENT_REASONS`，见 `AgentBridge.kt`）：要进集合必须同时
写清「为什么需要」，不能只加路径不解释。归类一律按 handler 的实现机制判定，不按端点名、文件名或
类别臆测。本文档是那份分类的人读版；**代码是唯一事实来源**，两者不一致时以代码为准并修本文。

## 2. 需要无障碍的端点（硬门禁，共 28 个）

服务不可用时，这些端点由 `route()` 开头的门禁直接返回结构化 503（见 §4）；每个 handler 都确实在
拿 `DshAccessibilityService.instance` 完成主功能。

| 分组 | 端点 | 为什么需要 |
|---|---|---|
| 读节点树 | `GET /screen`、`GET /screen-changed`、`GET /scene`、`GET /diff`、`POST /wait-text`、`POST /wait`、`POST /idle` | 节点树/窗口变更事件只有无障碍服务能拿到；`/wait-*` 轮询节点树或等事件安静下来 |
| 读节点树+点按 | `POST /pick` | 对屏上无障碍节点排序，并可选点其中一个 |
| 注入手势/按键/文本/滑动 | `POST /tap`、`POST /tap-id`、`POST /gesture`、`POST /key`、`POST /input`、`POST /scroll-find`、`POST /scroll-center`、`POST /scroll-edge` | 手势、全局按键、文本注入都走服务；滚动要边滚边读节点树 |
| 屏幕图像 | `GET /screenshot`、`GET /frame-diff`、`POST /frame-wait`、`POST /vision/crop`、`POST /vision/track`、`POST /vision/tap`、`POST /vision/drag` | 截图来自 `AccessibilityService.takeScreenshot`（不是 MediaProjection，`/screenshot` **没有**特权回退），视觉分析/点按/拖拽先截无障碍图 |
| 复合动作 / 事件缓存 | `POST /batch`、`POST /act`、`POST /loop/run`、`GET /interference`、`GET /notif` | 内部同样读树/点按/截图（注意 §9 的 `/batch` 遗留问题）；触摸干预计数与通知事件来自无障碍事件回调（`/notif` **不是** NotificationListenerService） |

## 3. 永远不需要无障碍的端点（禁止进门禁）

服务关着时它们必须继续返回 200。**不要**为了它们让用户去开无障碍，也不要因为它们把任务挂起。

| 分组 | 端点 |
|---|---|
| 启动 / 交接 / 系统页 | `POST /launch`、`POST /launch-intent`、`POST /open`、`POST /activity`、`POST /a11y/enable`（只是打开系统无障碍设置页） |
| 等本 App 回来 | `POST /wait-app`（目标包是本 App 时） |
| 状态 / 设置 / 引擎 | `GET /status`、`GET /settings`、`POST /settings`、`GET /apps`、`GET /process`、`GET /diag`、`GET /plugin/list`、`POST /plugin/install`、`POST /engine/restart`、`POST /notify`、`POST /say`、`GET /say`、`POST /overlay`、`GET /local/status`、`POST /local/ensure`、`GET /daemon/status`、`POST /daemon/stop` |
| 文件 / 分享 / 媒体 | `POST /open/file`、`POST /open/folder`、`POST /open/share`、`POST /share/text`、`POST /media/play`、`POST /media/pause`、`POST /media/resume`、`POST /media/stop`、`GET /media/status`、`POST /export` |
| 本 App 自己的窗口 | `GET /app-screenshot`（PixelCopy 抓本进程窗口，不要任何权限） |
| PTY / 运行时 / 视觉列表 | `POST /pty/open`、`POST /pty/write`、`POST /pty/resize`、`POST /pty/kill`、`POST /pty/close`、`GET /pty/read`、`GET /runtime/status`、`GET /runtime/feed`、`POST /runtime/pull`、`POST /runtime/stage`、`POST /runtime/apply`、`POST /runtime/rollback`、`GET /vision/list` |
| 记忆记账（不读屏） | `GET /memory/list`、`GET /memory/stats`、`POST /memory/confirm`、`POST /memory/clear` |

「不需要」也覆盖这些端点背后的普通 Android 行为：`startActivity`、`Intent`/App Link 派发、
`ACTION_SEND` 分享面板、`MediaPlayer`、`NotificationManager`、`TextToSpeech`、
SAF/DocumentsProvider 文件访问、读写本 App 设置。

## 4. 「服务没开」时应该看到什么

`GET /status` 会报两个独立状态：

- `permissions.accessibility` —— 系统设置里已授权；
- `permissions.accessibility_connected` —— 进程里真的绑上了。

两个都为 true 才能动作。硬门禁端点返回的 503 是带行动信息的结构体（`error`/`code` 只有两种：
`a11y_connecting` = 已授权但绑定未完成，`retryable:true`，等 1~2 秒重试一次；`a11y_off` = 从未
授权或被 Android 停用，强停/更新后常见，此时 `accessibility_listed_but_disabled:true`）：

```json
{"ok":false,"error":"a11y_off","code":"a11y_off","endpoint":"/screen","retryable":false,
 "because":"reads the on-screen node tree","reason":"...","message":"...","how_to_fix":"...",
 "accessibility_enabled":false,"accessibility_connected":false,"accessibility_listed_but_disabled":false,
 "action":"open_a11y_settings","actionHint":"POST /a11y/enable opens the system accessibility page"}
```

## 5. 有条件依赖的端点（故意不进硬门禁，由 handler 自己判定）

下面共 7 个端点（6 种行为 —— `/memory/lookup` 与 `/memory/store` 共用一条），它们存在
「不需要无障碍也能成功」的路径，静态门禁会把可用场景误杀；handler 负责在真的需要却不可用时
返回**说清原因和开法**的错误。

| 端点 | 什么时候需要 | 服务关着时的行为 |
|---|---|---|
| `GET /ocr` | 默认路径用无障碍截图做 OCR | `engine=ppocr6` 可走特权 `screencap`（Shizuku/root）；`ensure`/`download`/`models` 分支完全不需要 a11y |
| `POST /wait-app` | 等**别的** App 的前台状态 | 目标是本 App 时走 Activity 生命周期，无需 a11y；等第三方才 503 `a11y_unavailable` |
| `POST /memory/lookup`、`POST /memory/store` | 需要用当前屏幕算场景指纹 | body 带 `dump`（调用方自己给节点树）时完全不读屏；指纹里的包名可空 |
| `POST /overlay` | `hide=true` 时顺手收掉悬浮窗 | 更新/显示进度条不依赖；`StatusOverlay.hide(svc)` 接受 null |
| `POST /activity` | 只影响回执里的 `bal_exempt.accessibility` 提示 | 启动走 `startActivity`，照常 200 |
| `POST /open` | 只用于读跳转前后的前台包名做「是否真的切过去了」验证 | 服务关着仍 200，回执带 `hint` 说明未验证 |

## 6. 服务关着时该怎么做（给 Agent 的行为规范）

1. **普通能力继续做。** 它们不受影响；绝不要让任务因为无障碍没开而停摆。每个 UI 任务**探一次**
   `GET /status` 就够，不要每个动作前都探；`retryable:true` → 等约 2 秒重试一次，仍是 false 就别
   循环重试。
2. 否则给用户开页面的入口（`POST /a11y/enable` 或 `POST /activity
   {"name":"accessibility-settings"}`），让用户自己决定 —— App 无法自己开无障碍。
3. **优先绕行**：用 `/launch` + `/open` 做交接、`POST /wait-app <本包>` 等回来、
   `GET /app-screenshot` 看自家窗口、`POST /share/text` 转发、`POST /notify`/`POST /say` 输出。
4. 不要对非重试的无障碍错误死循环，也不要把普通能力描述成「被 Android 挡住了」。

## 7. 代码侧不变式与维护规则

1. `A11Y_DEPENDENT_REASONS`（`AgentBridge.kt`）**就是**门禁集合：门禁遍历它的 key。加路径必须在
   同一张表里写人读原因 —— 写不出原因就说明分类没想清楚。
2. `A11Y_INDEPENDENT_ACTIONS` 是反例集：启动/交接/分享/媒体/自家窗口等明确不需要无障碍的端点。
   启动时若与门禁表重叠，会 `Log.e` 打出 `a11y path classification conflict`，按本文修正。
3. `ACTING_PATHS` 只决定「AI 正在操作设备」通知的计数；在里面**不代表**需要无障碍，反之亦然。
4. 条件依赖端点（§5）留在门禁之外；handler 失败时也必须给出 `because`/`how_to_fix`。
5. 新增/改动端点：先读 handler（及 `DshAccessibilityService.kt`、`ScreenOcr.kt`、`Framer.kt`、
   `VisionTracker.kt`、`ShotFallback.kt` 等 helper）确认是否真的用了 svc → 按本文归类 → 写 handler
   → 在 `A11Y_DEPENDENT_REASONS` 加人读原因 → 更新本文 §2/§3/§5。只有 handler **无条件**需要
   `DshAccessibilityService.instance` 才进门禁表；只做 `Intent`/`startActivity`、读自家窗口、碰
   文件/设置的一律不许进。
6. 永远不要因为「它看起来像自动化」就把端点塞进门禁。启动、分享、媒体、通知、文件、
   PTY、引擎控制也都是自动化，它们都不需要无障碍。
7. 若某端点从「无条件依赖」改造成「有回退」（例如 `/ocr` 的特权截图），必须**同时**把它从
   原因表移除并改本文 —— 否则又会出现「明明能用却被 503」的反向问题。
8. 自检：`python3 scripts/check-i18n.py`（Kotlin 字符串字面量禁中文）、
   `python3 scripts/check-doc-endpoints.py`（文档端点必须在 `route()` 里真实存在），并 grep
   `A11Y_DEPENDENT_REASONS` 确认集合仍由原因表派生，没有第二份硬编码名单。

## 8. 真机验证清单（a11y 开 / 关各跑一遍）

**关闭无障碍**（设置 → 无障碍，关掉 `DSH Mobile Helper (optional)`；`/status` 应为
`accessibility=false, accessibility_connected=false`）：

- 应 200：`POST /launch`、`POST /launch-intent`、`POST /open`、`POST /activity`、
  `GET /app-screenshot`、`POST /notify`、`POST /say`、`GET /status`（含 `POST /settings`）、
  `POST /open/file`、`POST /share/text`、`GET /process`、`GET /diag`、`POST /overlay`；
- 应 200（条件依赖）：`POST /wait-app {"package":"com.termux"}`、`GET /ocr?engine=ppocr6&ensure=1`、
  `POST /memory/lookup`（body 带 `dump`）；
- 应 503 且 `because`/`how_to_fix` 齐全、`endpoint` 正确：`GET /screen`、`GET /scene`、`GET /screenshot`、
  `GET /notif`、`GET /interference`、`POST /tap`、`POST /act`、`POST /batch`、`POST /key`、
  `POST /gesture`、`POST /wait-text`、`GET /diff`、`POST /loop/run`、`POST /vision/tap`；同一批 503 里
  **不允许**出现 §3 的任何端点。

**开启后再验**：上述依赖端点全部 200；`/status` 两项权限都为 true。**接着补一个竞态**：开着无障碍
时用系统设置关掉或强停 App，立刻打一个硬门禁端点，应拿到 `a11yGone()` 的可行动 503，而不是裸
`{"error":"..."}`。

## 9. 已知遗留 / 待复核

1. `POST /batch` **无条件**要求无障碍：即使 steps 只含 `launch`/`open` 这类普通动作，handler 开头
   仍是 `svc ?: 503`，所以如实归为硬依赖；若将来改成「先扫描 steps，全普通则放行」，必须同步从原因
   表移除并改本文。
2. `GET /notif` 依赖无障碍通知事件缓存（`DshAccessibilityService.recentNotifications()`），
   **不是** `NotificationListenerService`；若改用 NLS，应移入 §3 并删掉原因表条目。
3. `GET /screenshot` 无特权回退（`ShotFallback` 只服务 `/ocr`），保持硬依赖；要加 Shizuku/root 回退
   的话加完必须重新归类。`GET /interference` 在原因表里，503 由门禁统一给出 `because`/`how_to_fix`。
4. 服务显示名以 `res/values/strings.xml` 的 `accessibility_label` 为准（`DSH Mobile Helper (optional)`，
   中文 `DSH Mobile 辅助（可选）`）。

## 10. 相关文档

完整路由表见 [android-agent-routes.md](android-agent-routes.md)；能力与界面细节见
[ACCESSIBILITY.md](ACCESSIBILITY.md) / [ACCESSIBILITY.en.md](ACCESSIBILITY.en.md)。
