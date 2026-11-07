# 悬浮窗（StatusOverlay）设计说明

> 适用代码：`app/src/main/java/app/dsh/mobile/StatusOverlay.kt`、`app/src/main/res/layout/overlay_status.xml`。
> 本文只讲**结构与约束**，不讲历史版本；改动前请先读完「解耦原则」与「回归清单」。

## 1. 它是什么

一枚由无障碍服务（`TYPE_ACCESSIBILITY_OVERLAY`）持有的悬浮层，用来在其他 App 之上显示 DSH 的实时状态。
因为持有者是无障碍服务，**不需要**用户额外授权「显示在其他应用上层」。

交互固定为三种：

| 手势 | 效果 |
| --- | --- |
| 点击（位移 < 10dp） | 折叠球 → 展开卡片；展开卡片（点图标/正文区）→ 收起 |
| 拖动 | 移动窗口，松手吸附到最近屏幕边缘（竖屏左右、横屏左右；贴边实时读取屏幕尺寸） |

## 2. 两种核心形态（尺寸稳定定宽，零尺寸跳变）

所有窗口尺寸都在 `applyWindowSize(ctx, view, collapsed)` 里决定，保证展开与折叠态切换时尺寸清晰确定：

### 2.1 折叠球

- 尺寸：`COLLAPSED_BALL_DP = 36dp` 的正方形。
- 内容：只有 `overlayBallDot`（14dp 圆点，`layout_gravity=center`），其余子 View 全部 GONE。
- 圆点颜色：`tintDots()`（空闲灰 / 执行中蓝 / 完成红）。
- 交互：点击展开卡片；拖动移位松手吸边。**长按隐藏已于 2.9.106 移除**（用户要求：过时功能，只会误触把球弄丢）。

### 2.2 展开卡片（恒定定宽 240dp）

- 尺寸：宽 **240dp 固定**；内部正文滚动区高度 **固定 92dp**，外加底部行固定占位，窗口高度与宽度恒定稳定。
- 内容：
  - 图标固定在左侧（`overlayDot`，不在 ScrollView 里），不随正文滚动移动。
  - 正文在右侧 ScrollView 内滚动，`fullScroll` 自动跟随到底部。
  - 底部行：`overlayFoot`（会话标题 + 耗时）在展开态始终保持占位。
  - 无障碍动作链：`overlayA11y` 仅在有无障碍动作时显示。
- 稳定性约束：**展开后无论是空闲还是工作，宽度始终为 240dp，禁止在空闲时缩成细条/自适应导致横向跳动**。流式吐字更新文本时纯在 View 层更新与滚动，绝不调用系统 `WindowManager.updateViewLayout`，确保流式输出零跳动零闪烁。
- 状态机单向锁定：**一旦展开，除非用户点击状态圆点收起，否则保持展开，绝不允许因空闲/定时器擅自折叠**。
- 收起交互：点击左侧状态圆点（`overlayDot`）收起为折叠球；正文区域（`ScrollView` / `TextView`）不挂载点击收起，保证滑动阅读不误触。

### 2.3 状态点的三种颜色

| 颜色 | 含义 | 触发点 |
| --- | --- | --- |
| 灰 `COLOR_IDLE` | 空闲 | `setIdle()` |
| 蓝 `COLOR_WORK` | 执行中 | `setStatus()`（收到新状态） |
| 红 `COLOR_DONE` | 整轮结束、等待用户看回复 | 只由 `AgentBridge.onTurnEnd`（WebUI `turn/end`）触发，即「提问→思考→最终回复」整轮结束 |

红点不会改写正文，任何新状态或用户操作都会清红。

## 3. 解耦与稳定性原则（改这里必须遵守）

1. **图标与滚动解耦**：`overlayDot` 是 ScrollView 的兄弟节点、不是子节点。正文滚动时图标必须静止。
2. **窗口尺寸单入口**：只有 `applyWindowSize()` 能写 `LayoutParams` 宽高；其它地方只改内容与可见性。
3. **流式更新不调 WindowManager**：`setStatus()` 仅更新 TextView 文字并在 View 层平滑滚动，绝不调用 `applyExpandLevel`、`safeUpdateLayout` 或 `anchorToEdge`。
4. **贴边坐标不随内容抖动**：`anchorToEdge` 仅在「折叠球 ↔ 展开卡片」档位切换、用户拖拽放手吸边或屏幕旋转时计算，流式文本更新期间坐标完全冻结。
5. **展开态防误触**：正文滚动区不挂收起事件，仅点击状态圆点收起。
6. **避让截图不销毁 Surface**：使用 `root.visibility = View.GONE` 避让截图，禁止使用 `removeViewImmediate` 导致画面硬闪烁。
7. **可见性由档位决定**：`applyExpandLevel()` 统一设置各控件的可见性，不在别处零散设置。
8. **颜色只走 `tintDots()`**：球态点、展开态图标都由它上色，避免两处颜色不一致。
6. **不要用 margin 魔数做居中**：球态靠 `layout_gravity=center`，展开态靠行内 `layout_gravity` 与固定 `layout_marginTop`（图标对齐首行）。
7. **绝不 removeView/addView 来「改外观」**：摘挂窗口会重建 surface，用户看到的就是「一闪一闪」。
   - 截图/读屏现在**根本不碰窗口**（见 §3.s 窗口合成截图）；只有回退路径才用 `INVISIBLE` 躲一下。
   - 档位切换：只 `safeUpdateLayout(wm, view, params)` + `requestLayout()`。
   - 例外只有一种：窗口首次创建（`show()`）时 `addView` 一次。
8. **窗口初值必须是「折叠球尺寸」**：用 `WRAP_CONTENT` 初始化时，首帧会按 XML 自然尺寸（实测 178×79 横条）画出来再变成 99×99 → 每次显示都闪一下。
9. **展开态只有状态圆点收起，正文空白不收起**：`overlayDot` 绑 `collapseToBall()`（热区外扩 12dp）；
   root 的点击只在「档位说展开、实际渲染是球」这种脱钩状态下自愈成展开（见 §3.aa）。正文区交给 ScrollView。
   这样设计是为了**防误触**：卡片上滑动/点正文时不会被判成「收起」。

### 3.x 两条硬不变量（2026-11 踩过）

- **`overlayScroll` 的 `weight` 绝不能被清零。** 它的 `layout_width=0dp + layout_weight=1` 靠横向权重撑开；
  任何「顺手把 weight 归零」的写法都会让文字宽度变成 0 —— 卡片照画、圆点也在，但**文字全被裁掉**，
  表现就是「展开态文本不显示」。只允许改 `height`。
- **空闲态与工作态同宽同高**（240dp × 92dp 滚动区 + 底行）。不做「空闲自动缩成一行」的适配：
  那会让卡片在空闲/工作之间跳变，用户会读成「它自己折叠了」。



### 3.y 截图避让：2.9.72 及以前的回退路径（现行见 §3.s）

### 3.s 截图不再需要「藏悬浮窗」（2.9.75 起：窗口合成截图，零闪烁）

> **现行机制（2.9.75 / 2.9.79 起）**：`/screenshot`、`/ocr`、`/calib`、`/frame-*`、`/act` 步内截图一律走
> `screenshotBase64NoFlash()` → `screenshotCompositeBase64()`：用 `takeScreenshotOfWindow(windowId)`
> 逐个抓「目标 App + 输入法 + 状态栏」等窗口，再按 `boundsInScreen` 贴回整屏画布。
> **自己的悬浮窗按 bounds 精确排除，窗口本身既不隐藏也不重建** ⇒ 截图期间球不会消失。

- 回执带 `via`：`composite`（正常）/ `window`（单窗回退）/ `display`（回退，会躲藏窗口）/ `display-forced`（`?flash=1` 取证用）。
- 回执同时带 `capturedAt`（毫秒时间戳）与 `latencyMs`——判断「这张图是不是动作之后拍的」看 `capturedAt`，不要靠猜。
- 三条硬规则：
  1. **排除自己只能按 bounds 比对**（`StatusOverlay.currentFrameRect()`，逐边 ±2px）：主 WebView 的包名也是
     `com.termux`，按包名排除会把自己的界面也切掉；按无障碍 type 排除也不可靠（2038 会报 `TYPE_SYSTEM`）。
  2. `takeScreenshotOfWindow` 给的是**窗口本地坐标**，贴回整屏必须加 `boundsInScreen`。
  3. 333ms 节流**按 windowId 分桶**，一次合成连拍多个窗口不会互相消耗额度。
- 什么时候才需要 `?flash=1`（走旧的「藏窗口 + 整屏截图」）：只用于要**像素级证据**的取证（例如验证
  「截图里确实没有悬浮窗」）。它会隐藏悬浮窗 2s 左右，**日常调用绝不要用**。
- 已知限制：API < 34 无窗口截图；`FLAG_SECURE` 窗口截不到；半透明窗口背后是黑；一次最多合 6 个窗口。

### 3.u 谁可以碰悬浮窗的可见性（2.9.72 起的调用分工）

| 调用方 | 是否避让 | 原因 |
|---|---|---|
| 帧轮询 `Framer.captureAndDiff`（`/frame-wait`、`/frame-diff`、verify/settle） | ❌ 不避让 | 250–300ms 一次，避让=持续闪；改为在 64×64 差值图上**抹平悬浮窗矩形** |
| 读屏/OCR（`/ocr`、点击链路、图标识别） | ❌ 不避让 | 同样高频；改为**丢弃落在悬浮窗矩形内的 block**（回执 `dropped=`） |
| `screenshotBase64`（旧显式截图） | ✅ 避让（已基本被 §3.s 取代） | 只有回退路径才走到 |
| 自动化注入（`/gesture`、点击/滑动/长按） | 仅**轨迹与悬浮窗相交**时避让 | 见 §3.p；相交才藏，藏完立刻恢复 |

引用计数铁律：`hideRefs` 只由 `hideNow/showNow` 增减，**恢复失败必须回滚计数**（`mainSync` 返回 false =
主线程 800ms 内没跑到，此时若不动计数，计数与真实可见性永久脱钩 → 「有时候再也点不到」）。
`reassertVisible()` 在 `hideRefs > 0` 时必须让位（否则避让期间被强行捞回来 = 加倍闪）。

### 3.p 端点语义：谁能碰悬浮窗可见性（2.9.91 起）

**请求级避让已整条删除**。现在只有两种隐藏来源：

1. **手势层**：注入手势的轨迹与悬浮窗矩形相交 → `beginOpsAvoidIfHit()`（`dispatchGestureAvoided` 包裹 6 处注入点）；
2. **显式**：请求 body 里写 `"avoid":true`（或 `/act` 的 `hideOverlay`）。

事故记忆（别再犯）：
- `/act` 曾无条件避让 → agent 连续操作时占空比 35%，用户看到的就是「一直闪、点不到」；
- `/overlay` 是 **agent 的思考进度推送通道**（`ProgressHub.update`，思考时每 ~250ms 一条），曾被列进避让表
  → 30s 内隐藏 1159 次、3–4Hz 闪烁。它**绝不能进任何避让逻辑**。

### 3.aa 「点不开」的两个真根因（2.9.95 定案）

1. **档位与渲染态脱钩**：窗口重建（hide→show、2032↔2038 类型切换）后 `expandLevel` 被 `rememberedLevel`
   恢复成 1，但视图按折叠态 inflate；若点击逻辑只看 `expandLevel`（空 onClick / `if (expandLevel == 1) return`），
   点击就被吞掉 → **球永远点不开**。判据必须看**渲染态**（`statusRow.visibility`），并在脱钩时自愈。
2. **展开卡片漏锚定**：锚定若只在「档位值变了」时做，重建后就不锚定；`x` 还用着球坐标（948），
   720 宽卡片 588px 出屏 → 用户只看到一条竖边。**每次应用档位都要重新锚定，并夹取 `x` 使右边不出屏。**

排查备忘（vivo 实测）：`debugState()` 的 `level` 只是字段，不代表当前外观；悬浮窗 `params.y` 与屏幕像素 y
差 92px（`Frames: parent=[0,92]`），`adb shell input tap` 要用视觉坐标。

### 3.y 截图 / 读屏时怎么让悬浮窗不出现在图里（历史实现，保留作对照）

流程（`DshAccessibilityService.screenshotBase64` + `StatusOverlay.begin/endCaptureAvoid`）：

1. `beginCaptureAvoid()`：`mainSync { root.visibility = INVISIBLE }` —— 视图不绘制、不进命中测试，
   **窗口不摘不挂**（`capture avoid: hidden=0 …`）；
2. 等 120ms；
3. **先丢两帧**（`warmup1`/`warmup2`），再取真图；
4. `endCaptureAvoid()`：`mainSync { root.visibility = VISIBLE; applyExpandLevel(); requestLayout() }`。

为什么不是「摘窗口」：`removeViewImmediate` + `addView` 图像最干净，但代价大 —— 真实窗口增删会
①肉眼可见地闪；②**带走触摸状态**，用户「自动化刚跑完想点一下悬浮窗看输出」时点不动。
用户定调：能隐藏就别移除。

为什么 `INVISIBLE` 现在够用了（2.9.64 之前不够，是因为缺了下面两条，不是 INVISIBLE 本身不行）：

- **节流**：相邻两次 `takeScreenshot` 必须 ≥ **333ms**（AOSP `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`），
  否则回 `errorCode=3 INTERVAL_TIME_SHORT`；实现里 `awaitCaptureSlot()`（350ms）统一排队，
  `screenshotArgb` 与 `screenshotBase64` 共用同一时间戳。
  参考：https://github.com/kaeawc/auto-mobile/pull/4995
- **陈旧帧**：SurfaceFlinger 双缓冲，隐藏后的**第一张**很可能还是「隐藏前」那一帧；
  所以必须丢两帧再取（日志 `warmup1=` / `warmup2=`）。
  同类现象：https://community.amazondeveloper.com/t/vega-sdk-0-24-vvd-automation-toolkit-crashes-on-every-takescreenshot-econnreset-then-dies-after-3-calls-until-reboot/29134/12

已证伪、别再用：`params.alpha=0`、`params.x=-10000`（合成缓存里仍有这一层，会被拍到）；
`removeViewImmediate`（能干净，但重建窗口丢触摸状态，见上）。

**连带教训**：任何「重建窗口」的路径（摘挂、重放布局）都必须
①按当前 `opsAvoid` 重算 `params.flags`（否则残留 `FLAG_NOT_TOUCHABLE` → 重建后点不动/拖不动/滚不动）；
②绝不改 `overlayScroll` 的 `weight`（`width=0dp` + weight 是撑宽唯一手段，清零会让文字宽度变 0，
表现「展开态文字不显示」）；③空闲态与工作态同宽同高。

排障日志：`DshA11y: screenshot: begin avoid=… / warmup1=… / warmup2=… / attempt=N ok=…` 与
`StatusOverlay: capture avoid: hidden=… ` / `capture restore: shown …`，`debugState()` 里带
`root/attached/live/level/pos/size/flags/touchable/opsAvoid/hiddenForCapture/type`。

## 4. 状态机速查

```
折叠球 --点击--> 展开(空闲) --setStatus(非空闲)--> 展开(工作)
   ^                  |                              |
   |                  +-------- 点击图标/正文 --------+
   |                                                 |
   +-- setIdle()/turn end（自动收球：仅当是本层自动展开的）--+
```

自动收球的前提是 `autoExpandedByStatus`：用户手动展开的档位不会被自动收起。

## 5. 回归清单（每次改动都要过）

- [ ] 折叠球是正方形，圆点在正中，点一下能展开。
- [ ] 展开空闲：卡片贴合「图标 + 空闲」，不留大片空白。
- [ ] 展开工作：宽度 240dp、高度固定、正文在 92dp 区域内滚动，图标不随滚动移动。
- [ ] 长正文滚动到底部，最后一行可见。
- [ ] 一轮对话结束，圆点变红；再次发消息变蓝。
- [ ] 旋转屏幕 / 分屏后贴边仍然正确。
- [ ] 长按**不再**隐藏窗口（2.9.106 起）；要隐藏请在设置里关掉「显示悬浮窗」。
- [ ] **空闲态是自适应的**（宽度贴着「图标 + 空闲」，右边没有 240dp 空白；也不是 34dp 细条）。
- [ ] **工作态是 240dp 定宽 + 92dp 固定滚动区**，正文长短不改变窗口高度。
- [ ] 空闲 ↔ 工作切换时只看得到内容变化，**没有窗口重挂/闪白**（可通过 `[win] level=... 尺寸` 日志确认没有尺寸跳变或重挂）。
- [ ] 展开态点卡片上任意位置（含空白、动作行、正文）都能收起；正文区可以正常滚动。
- [ ] `logcat -s StatusOverlay` 里没有 `cleared stale NOT_TOUCHABLE`、`opsAvoid watchdog fired` 之外的告警。

补充规则（issue-overlay-flicker 之后定的）：

- **只有显式截图才摘窗口**：`screenshotBase64(avoidOverlay=true)` 用于 `/screenshot`、`shot` 门、agent 取图。
- **后台 OCR prefetch 必须 `avoidOverlay=false`**：它每 3–5 秒抓一帧，摘/挂窗口会让悬浮窗肉眼可见地闪
  （现象：静置时约 4 秒消失一次）。prefetch 的画面里如果带着悬浮窗，用
  `StatusOverlay.currentFrameRect()` 把落在该矩形内的 OCR block 丢掉（日志 `ocr ready n=… dropped=…`）。
- **双缓冲要丢两帧**：切 App / 展开动画刚结束的第一张仍可能拿到旧帧，所以 warmup 做两次
  （`warmup1`/`warmup2` 各打一行日志），再取真图。
- **opsAvoid 去抖**：`beginOpsAvoid/endOpsAvoid` 400ms 内重复切换直接忽略（`opsAvoid on/off debounced`），
  避免每 250ms 翻一次导致状态栏/窗口抖动。

### 3.ab 「App 彻底销毁后悬浮球还在」：退出即闭环（2.9.104 / 2.9.105 定案）

- 现象：通知栏「退出」或划掉任务后，约 60s 悬浮球又出现；再次打开 App 时球可能不见。
- 原因：无障碍服务被系统绑着 → 进程不死 → 60s 保活心跳无条件 `show()` 并重启引擎；
  同时 `MainActivity.onDestroy` 的 `hideAuto()` 把 `hidden` 复位成 false，解开了退出抑制。
- 修复：① 心跳判断 `EngineService.userExited()`，为真跳过引擎与悬浮窗；
  ② `startOverlay()` 入口同样拦掉（日志 `overlay suppressed: user exited`）；
  ③ `hiddenByExit` + `resumeAfterExit()`：只有"退出抑制"会在重开 App 时恢复（设置开关关闭不恢复）。
- 真机（vivo / 2.9.105 / 676）：退出后 `root=null`；70s 仍为 null（日志 `keepalive: user exited, skip …`）；
  重开 App → `overlay resumed after user exited`，球回到 `948,360 108x108`。
- 硬规则：a11y 保活通路必须尊重 `userExited`；「退出抑制」与「设置开关关闭」是两种 hidden，语义不同。

### 3.ac 2.9.106：长按隐藏已移除（用户要求）

- 决策：悬浮球「长按 = 隐藏」是过时功能，且是误触把球弄丢的主要来源（用户原话「彻底移除…根本不需要」）。
- 改动：`StatusOverlay.attachGesture()` 删掉 `onLongPress` 形参、`bindHandle()` 两处调用不再传长按、
  折叠态/展开态都不再 `setOnLongClickListener`；`hideInternal()` 的日志改为
  `overlay hidden (sticky)` / `overlay hidden (auto)`（不再有 "by user" 语义）。
- 现在还能让悬浮窗消失的入口（仅两个，都是显式而非手势）：
  1. 设置 → 「显示悬浮窗」开关关掉 → `DshAccessibilityService.setOverlayEnabled(false)` → `hide(svc)`；
  2. 退出 App（通知栏「退出」/划掉任务）→ 引擎退出 → `teardown()`，见 §3.ab。
- 回归清单新增一条：**长按不再隐藏窗口**（`input swipe x y x y 1500` 模拟长按，窗口应保持可见）。

### 3.ad 2.9.107「关闭悬浮窗」必须真的关闭：能力面与悬浮窗解耦

- 问题（用户提问「当关闭悬浮窗时，所有 a11y 功能、ocr 识别、分享等所有功能，是否正常运作？」）：
  设置 →「后台进度提示 → 显示方式 → 关闭」只调 `ProgressHub.setMode(MODE_OFF)` → `hideAuto()`，
  而 `hideAuto()` 会把 `hidden` 复位成 false；随后多条通路（`ProgressHub.ensureOverlayVisible`、
  `ProgressHub.maybePublish`、无障碍看门狗、`MainActivity.onResume`）各自 `show()` 一次
  ⇒ 实测关掉后 **11 秒球又冒出来**（`02:20:33 overlay hidden (auto)` → `02:20:44 overlay shown at 948,360`）。
- 修复：在唯一的建窗入口 `StatusOverlay.startOverlay(ctx, w, type)` 加模式闸门——
  `ProgressHub.mode(ctx) != MODE_OVERLAY` 时直接 return 并记 `overlay suppressed: progress mode off`。
  与 §3.ab 的 `userExited` 闸门并列（退出 / 关闭悬浮窗 是两种"不要窗口"的意图）。
- 能力面（2.9.107 / versionCode 678，vivo 10CG1C1M1H0021Z，悬浮窗关闭状态下逐项实测）：

| 能力 | 端点 | 结果 | 窗口状态 |
|---|---|---|---|
| 读屏（无障碍节点树） | `GET /screen` | `fg=com.termux 57 nodes`（含 OCR 兜底 24 行/822ms） | `root=null` |
| 无障碍文本点击 | `POST /act {"steps":[{"tap":"轨迹"}]}` | `ok:true`，界面切换（57→147 节点） | `root=null` |
| 坐标点击注入 | `POST /tap` | `{"ok":true,"via":"gesture"}` | `root=null` |
| 手势注入 | `POST /gesture` | ok | `root=null` |
| OCR 识别 | `GET /ocr?cache=0` | 24 行 / 725ms | `root=null` |
| OCR 预热（每请求触发） | `GET /daemon/status` ×5 | `prefetch ocr ready n=29 engine=mlkit ms=322` | `root=null` |
| 免闪截图 | `GET /screenshot?save=1` | `via=composite 1080x2376` | `root=null` |
| 帧差 | `GET /frame-diff` | ok | `root=null` |
| 分享 | `POST /share/text` | `{"ok":true,"opened":true,"handoff":true}` | `root=null` |
| 音量 | `GET /volume` | ok | `root=null` |
| 安装 | `GET /install` | `can_install=true perm=true` | `root=null` |
| 保活心跳（2 个周期） | — | 日志 `overlay suppressed: progress mode off` ×2 | `root=null` |

- 切回「悬浮窗」：`02:25:34 overlay shown at 948,360 (108x108)` 立刻回来。
- 结论：**能力面（读屏/点击/OCR/截图/分享/音量/安装）与悬浮窗完全解耦**，关掉悬浮窗只是不显示那个球；
  唯一副作用是失去"看 AI 在做什么"的可视反馈（可按需切「通知」显示方式）。

### 3.z 为什么 OCR prefetch 绝不能摘悬浮窗（抖动 / flicker）

- 现象：静置、不点任何截图，悬浮窗仍大约每 **3–5 秒**消失一次再出现（肉眼可见）。
- 原因链：后台 OCR prefetch 每 3–5 秒抓一帧 → 若它也走「截图避让」→ 每帧都
  `removeView` 摘掉悬浮窗再 `addView` 挂回 → 屏幕上一闪一闪。
  摘/挂窗口是**真实窗口增删**，不是改 alpha，所以必然可见。
- 结论（硬规则）：**只有显式截图允许摘窗口**（`screenshotBase64(avoidOverlay = true)`：
  `/screenshot`、`shot` 门、agent 主动取图）；**prefetch 必须 `avoidOverlay = false`**。
- prefetch 画面里带悬浮窗怎么办：用 `StatusOverlay.currentFrameRect()` 把落在悬浮窗矩形内的
  OCR block 丢掉（日志 `ocr ready n=… dropped=…`），**不要靠摘窗口去获得干净画面**。
- prefetch 的回执要告诉调用方：当 `overlay_occluded > 0` 时返回
  `overlay_occluded` / `occluded_by:"own_overlay"` / `hint` / `actionHint:"GET /screenshot?save=1"`，
  含义是「被悬浮窗盖住的区域这次没读到；要读/要点击那块区域，请先手动调用一次显式截图」。
  显式截图路径会临时摘掉悬浮窗（并先丢两帧破双缓冲），拍完立即挂回。
- 反向验证（回归判据）：静置 20 秒，logcat 里 `capture avoid: removed=true` 计数必须是 **0**；
  若 >0 说明又有后台路径走了避让。

### 3.w 展开态「正文滚不动」的根因

- 现象：展开卡片里正文比较长时，用手指上下滑没有任何反应。
- 根因：展开态为了支持「拖卡片 / 长按隐藏」，在**根容器**上装了 `OnTouchListener`，并且
  `ACTION_DOWN` 一律 `return true` 把事件吃掉 —— 子 View（`overlayScroll`）根本收不到触摸，
  于是 ScrollView 永远不滚。
- 修法：`attachGesture(..., scrollChild = sScroll)`；DOWN 时先做命中判断
  「点是否落在滚动区内 **且** `canScrollVertically(±1)` 为真」→ 满足就 `return false`，
  把事件交给 ScrollView（日志 `handle touch: pass through to scroll area at (x,y)`）；
  不在滚动区（或内容不够滚）才走拖动/点击逻辑。
- 判据：日志里应看到 `pass through to scroll area` 而不是只有 `handle touch: action=0`。

### 3.v 悬浮窗「看得见但点不动/拖不动」的两个真凶

1. **`opsAvoid` 去抖吞掉了 `endOpsAvoid`**：曾经给 begin/end 都加了 400ms 去抖，
   自动化里 `begin` 之后紧接着的 `end` 被判定为「太近」直接 return →
   `FLAG_NOT_TOUCHABLE` 永久留在窗口上，悬浮窗从此完全不可点。
   **定论：只允许对「重复的 begin」做抑制，`end` 必须立刻生效，不许去抖。**
2. **截图恢复路径没有纠正 flags**：2.9.68 把截图避让改成 `visibility=INVISIBLE` 时，
   重写 `endCaptureAvoid` 顺手丢了「按当前 opsAvoid 重算 flags」那一步 →
   opsAvoid 期间截过图就会留下 `FLAG_NOT_TOUCHABLE`。
   **定论：任何恢复/重建窗口的路径，收尾都要 `want = opsAvoid ? flags|NOT_TOUCHABLE : flags&~NOT_TOUCHABLE`，
   并 `safeUpdateLayout` 让它真正生效，同时打日志 `capture restore: flags fixed -> 0x… touchable=…`。**

排查口诀：悬浮窗可见但无响应 → 先看 `debugState()` 里的 `flags/touchable/opsAvoid`；
`touchable=false` 就是上面两条之一。

### 3.t 最终形态：显示/隐藏只用 view 级 `visibility`（一刀切重写）

结论先行（2.9.71 起）：`StatusOverlay` 里**只有一种**显示/隐藏机制——`root.visibility`。

- 隐藏用 `hideNow(reason)`（INVISIBLE，引用计数），显示用 `showNow(reason)`（计数归零才 VISIBLE）。
- `beginCaptureAvoid/endCaptureAvoid`（截图）与 `beginOpsAvoid/endOpsAvoid`（自动化）**共用同一套**：
  它们只是 `hideNow("capture")` / `hideNow("ops")`。因为 `INVISIBLE` 的视图不绘制、不进命中测试，
  本身就**不会挡住注入手势**，所以「操作避让」根本不需要动窗口 flags。
- 恢复时**不做布局重放**（不调 `applyExpandLevel`、不改 `layoutParams`）：只把 visibility 改回来 +
  `requestLayout()`。重放布局正是「INVISIBLE 之后再点击出现 UI 变形/窗口错位」的来源。

明令禁止（都踩过，见 3.v / 3.y）：

1. ❌ `removeView/addView` 重建窗口 —— 丢触摸状态（点不动/拖不动/滚不动）+ 可见闪烁。
2. ❌ 改 `params.flags` 做避让（`FLAG_NOT_TOUCHABLE`）—— 一旦残留就永久点不动；
   且 `endOpsAvoid` 被任何「去抖」吞掉都会导致这个残留。
3. ❌ 恢复时重放布局/改 weight —— UI 变形、正文文字被裁。
4. ❌ `params.alpha=0` / 移出屏幕 —— `takeScreenshot` 仍会拍到。

截图干净靠三件事配合：`INVISIBLE` 隐藏 → 120ms 等一帧 → 截图侧「≥333ms 节流 + 先丢两帧 warmup」。

排查口诀：悬浮窗**看不见但还在** → `hideRefs` 没归零（看 `hide(...)/show(...)` 日志配对）；
悬浮窗**看得见但点不动** → 看 `debugState()` 里 `flags/touchable`（应当 `touchable=true`，且 flags 里没有 0x10）。
