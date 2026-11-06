# 悬浮窗「不断闪烁 / 有时点不到」静态分析

- 分析对象：`app/src/main/java/app/dsh/mobile/StatusOverlay.kt`（1180 行）、`DshAccessibilityService.kt`（2635 行）、`bridge/ScreenHandler.kt`（1344 行）及其调用方
- 仓库状态：`HEAD = 26ac148`（2.9.71 / 642），工作树干净
- 真机状态：Xiaomi M2012K11AC，Android 16 / SDK 36，**安装的是 2.9.64（versionCode 635）= `f43f77e`**
- 方法：只读源码 + `git show <sha>` 对照历史实现；**未运行任何真机命令**，未修改 `src/`
- 约定：每条结论都带 `file:line`；凡是无法从源码直接证明、需要真机/系统内部行为印证的，标注 **【推断】**。历史已证伪方向（`removeView` 重建、`alpha=0`、移出屏幕、`GONE`）不重复讨论，见 `docs/OVERLAY.md §3.t/§3.y`。

---

## 0. 结论摘要（按置信度降序）

> **最重要的一条：真机装的 2.9.64 与仓库 HEAD 差 7 个 commit，两者的隐藏机制完全不同。**
> 2.9.64 的截图避让是「`removeViewImmediate` 摘窗口 → `addView` 挂回 → `applyExpandLevel` 整套重放」；
> HEAD 的避让是「`root.visibility = INVISIBLE` 引用计数」。
> 因此「真机上看到的闪烁」和「读 HEAD 源码能解释的闪烁」是两件事，必须分开回答——下面分列。

| 编号 | 结论 | 证据 | 适用版本 | 置信度 |
|---|---|---|---|---|
| **P0-1** | 截图避让会**把窗口从 WindowManager 摘下 400ms 再挂回**，每次避让 = 一次「消失→重现」，且挂回时重放整套布局 | `git show f43f77e:…/StatusOverlay.kt:737-767`（`removeViewImmediate` + `Thread.sleep(400L)`）、`:769-799`（`addView` + `visibility=VISIBLE` + `applyExpandLevel(r)`） | **2.9.64（真机）** | 高（源码直证） |
| **P0-2** | **用户对 OCR prefetch 的怀疑成立**——但在 2.9.64 上成立，在 HEAD 上已被修掉。635 的 `screenshotBase64` 没有 `avoidOverlay` 参数，prefetch 直接调它 → 每个 HTTP 请求（2.5s TTL）摘一次窗口 | `git show f43f77e:…/DshAccessibilityService.kt:1015`（签名无参数）、`git show f43f77e:…/ScreenHandler.kt` 的 `prefetchOcr` 里 `svc.screenshotBase64(2500L)`；触发点 `Router.kt:69`（`routeInner` 第一行，每个请求都调 prefetch）、TTL `ScreenHandler.kt:45-46` | **2.9.64（真机）** | 高（源码直证） |
| **P0-3** | 升级到 HEAD 后闪烁**不会消失**：`screenshotArgb()` 无条件做避让，而它被高频消费者调用 —— `/dump` 图标识别、`/frame-wait`（默认 250ms 一轮）、`verify` 帧轮询（300ms）、`settle`（200ms） | `DshAccessibilityService.kt:909-944`（无 `avoidOverlay` 形参，931 行无条件 `beginCaptureAvoid`）、`:625`（dump 图标识别）、`bridge/ScreenHandler.kt:961-981`（frameWait 循环）、`engine/LocalLoopExecutor.kt:1427-1434`、`:1172-1180` | **HEAD** | 高 |
| **P1-1** | `ProgressHub` 在后台时**每次发布进度都强制 `VISIBLE`**，完全绕过 `hideRefs` → 避让期间窗口被「捞回来」，导致截图带悬浮窗、且与 `hideNow` 交替形成更快闪烁；同样让「操作避让」失效 | `ProgressHub.kt:253`（`if (!appVisible) reassertVisible()`，发布间隔 120ms，`:233-235`）+ `StatusOverlay.kt:699-709`（`reassertVisible` 直接 `visibility = VISIBLE`，不碰 `hideRefs`） | HEAD（2.9.64 无此函数，但有等价问题） | 高（源码直证「不变量被绕过」）；实际闪烁放大倍数需真机确认 |
| **P1-2** | `hideNow`/`showNow` **忽略 `mainSync` 的返回值**：主线程 800ms 内没执行完就静默失败，但引用计数已经加减过 → 计数与真实可见性脱钩 | `StatusOverlay.kt:758-768`、`:770-787`（调用 `mainSync{…}` 不检查 Boolean）；`mainSync` 超时语义 `:1129-1142`（`CountDownLatch.await(800)`） | HEAD | 高（源码直证逻辑缺陷）；触发需要主线程停顿 >800ms，**而主线程同时跑 WebView UI**，现实存在 ⇒ 这解释了「**有时**」 |
| **P1-3** | `hideInternal` **不清 `hideRefs`**：若 hide/hideAuto/teardown/onAccessibilityLost 恰好发生在避让窗口内，计数永久 >0 → 之后所有 `hideNow` 变成 no-op，**截图永远带着悬浮窗**，且日志出现「still hidden refs=N」 | `StatusOverlay.kt:717-729`（`hideInternal`）对比 `:756`（`hideRefs` 唯一声明）与 `:806-812`（只有 ops 看门狗会清零） | HEAD | 中高（路径存在但需时序命中） |
| **P1-4** | `screenshotArgb` **不参与** `awaitCaptureSlot()` 的 333ms 节流排队 → 撞 `errorCode=3` 后 3×450ms 重试，期间悬浮窗一直 INVISIBLE（最长 ~1.4s），放大「长时间消失」 | `DshAccessibilityService.kt:932-941`（`repeat(3)` + `sleep(450)`）、对比 `:999-1004`（`awaitCaptureSlot` 只在 `screenshotBase64*` 里调用：`:1027/1043/1049/1078`） | HEAD | 中高 |
| **P2-1** | 「**有时点不到**」的最一致解释：用户看见球 → 手指落下前那 120ms–1.9s 里恰好被避让隐藏 → 点击**穿透到下层 App**。设备上更糟：2.9.64 是摘窗口，手势会被系统 cancel | 隐藏窗口定义 `StatusOverlay.kt:758-768`；`beginCaptureAvoid` 在隐藏后仅 `sleep(120)`（`:794-797`），截图侧可再拖到 ~1.9s（`DshAccessibilityService.kt:1036-1057`）；触摸语义 **【推断】** 见 §5 | 两版都有，635 更严重 | 中（机制可证，命中率需真机量化） |
| **P2-2** | 635 的自动化避让仍用 `FLAG_NOT_TOUCHABLE`；摘/挂窗口期间 `updateViewLayout` 会抛异常被 `runCatching` 吞掉 → 标志残留 = 「看得见但点不动」 | `git show f43f77e:…/StatusOverlay.kt:808-847`（`notTouch` 常量与加/清逻辑）、`:1170-1175`（作者自己注释「视图被摘除时 updateViewLayout 会抛」）；修复在 2.9.67/2.9.70 | **2.9.64（真机）** | 中高 |
| **P2-3** | 60s 保活会重启引擎；`EngineService.onDestroy` **兜底拆悬浮窗** → 若引擎在小米上反复被回收/重启，表现为「隔一阵闪一下」 | `DshAccessibilityService.kt:236-247`（`KEEPALIVE_INTERVAL_MS = 60_000L`，`:1641`）、`service/EngineService.kt:134-137`（`teardown` + `hideAuto`）、`:271-274` | 两版 | 中（需 logcat 确认重启频率） |

**一句话给 lead**：真机 2.9.64 的闪烁 = 「prefetch/读屏/截图每次都摘窗口 400ms 再挂回」；升级到 HEAD 后主因变成「`screenshotArgb` 被 4–5Hz 的帧轮询/dump 反复调用 + `reassertVisible` 绕过引用计数」；「点不到」= 点击瞬间撞上隐藏窗口，触摸穿透到下层。

---

## 1. 所有改变可见性 / 窗口的路径（含触发源与频率）

HEAD 上的可见性**只有一个真源**：`root.visibility`，由 `hideRefs` 引用计数驱动（`StatusOverlay.kt:750-825`，注释 750-755 明写铁律）。除此之外仍有 4 类「窗口级」操作：

### 1.1 引用计数驱动的隐藏/显示（唯一合法机制）

| API | 位置 | 触发源 | 每次调用对窗口做什么 | 频率 |
|---|---|---|---|---|
| `hideNow(reason)` | `StatusOverlay.kt:758-768` | 内部 | `hideRefs++`；首次才 `root.visibility = INVISIBLE`（`mainSync`） | — |
| `showNow(reason)` | `StatusOverlay.kt:770-787` | 内部 | `hideRefs--`；归零才 `VISIBLE` + `requestLayout()` | — |
| `beginCaptureAvoid(svc)` | `StatusOverlay.kt:794-797` | 见 1.2 | `hideNow("capture")` + **`Thread.sleep(120)`**（在调用方线程阻塞） | 见 1.2 |
| `endCaptureAvoid(svc)` | `StatusOverlay.kt:800-803` | 见 1.2 | `showNow("capture")` | 见 1.2 |
| `beginOpsAvoid()` | `StatusOverlay.kt:814-819` | `Router.route` 对 POST+动作类端点（`Router.kt:50-61`）、`ActionHandler` 的 `hideOverlay`（`ActionHandler.kt:504` / `:712-714` finally） | `hideNow("ops")` + 90s 看门狗 | 每个动作类 HTTP 请求 1 次 |
| `endOpsAvoid()` | `StatusOverlay.kt:821-825` | 同上 finally | `showNow("ops")` | 同上 |

### 1.2 `beginCaptureAvoid` 的全部触发点（**闪烁的真正来源**）

| 入口 | file:line | 内部是否真的避让 | 触发频率 |
|---|---|---|---|
| `screenshotBase64(avoidOverlay=true)`（默认） | `DshAccessibilityService.kt:1036` | 是。隐藏后 `sleep(120)` + 2 次 warmup + 3 次 attempt，每次 `awaitCaptureSlot()` 至少 350ms ⇒ **总隐藏 ~1.87s**（`1036-1057`） | 显式 `/screenshot`（`ScreenHandler.kt:276`）、`:836`、`:1021`、`:1077`、`:1168` |
| `screenshotBase64(avoidOverlay=false)` | `DshAccessibilityService.kt:1027-1035` | **否**（直接走 no-avoid 分支） | prefetch（`ScreenHandler.kt:53`），全仓仅此一处 |
| `screenshotArgb()`（**无 avoid 形参**） | `DshAccessibilityService.kt:909-944`（避让在 `931`） | **是，且无条件**（唯一豁免是 700ms 近期帧缓存，`:923-924`，命中时不含悬浮窗） | 见下表 ↓ |
| `screenshotArgbOnce()`（内层） | `DshAccessibilityService.kt:975-981` | 再 `begin/end` 一次（引用计数下被吞成 no-op 日志） | 与 `screenshotArgb` 同频 |

`screenshotArgb` 的消费者与频率（**这些数字决定闪烁的快慢**）：

| 消费者 | file:line | 频率 |
|---|---|---|
| `Framer.captureAndDiff` ← `/frame-wait` | `bridge/ScreenHandler.kt:961-981`（`intervalMs` 默认 **250ms**，`timeoutMs` 默认 5000，可到 30000） | **4 Hz，持续 5–30s** |
| `Framer.captureAndDiff` ← `verify` 的 `frameChanged` 轮询 | `engine/LocalLoopExecutor.kt:1172-1180`（`nextFrameAt = now + 300`） | **3.3 Hz** |
| `Framer.captureAndDiff` ← `settle` 帧兜底 | `engine/LocalLoopExecutor.kt:1425-1434`（`sleep(minOf(200, …))`） | **5 Hz，最多 800ms** |
| `Framer.captureAndDiff` ← `/frame-diff` | `bridge/ScreenHandler.kt:936-948` | 按需 |
| `dumpScreenCompact` 的图标识别 | `DshAccessibilityService.kt:620-634`（`cands.isNotEmpty() && elapsed < 900` → `screenshotArgb(700L)`） | 每次 `/dump`，**被 700ms 帧缓存限流到 ≤1.4 Hz**；而 verify 的 dump 间隔可低到 **80ms**（`LocalLoopExecutor.kt:1167`）⇒ 实际 ≈1.4 Hz 持续 |
| `ScreenOcr.recognize` | `ScreenOcr.kt:120` | 3s 缓存（`ScreenOcr.kt:77 CACHE_MS=3000`）⇒ ≤0.33 Hz |
| VisionHandler 模板匹配 | `bridge/VisionHandler.kt:48`、`:105` | 按需 |
| `/screenshot` 的 argb 变体 | `bridge/ScreenHandler.kt:562` | 按需 |

> 结论：**只要 AI 在「等画面变化」，悬浮窗就按 3–5Hz 消失/出现**。这不是截图避让的副产品，而是 `screenshotArgb` 没有 `avoidOverlay` 开关造成的设计缺口——`screenshotBase64` 有开关（`1022`），`screenshotArgb` 没有（`909`），而高频消费者全都用后者。

### 1.3 其它会动窗口的路径（不属于隐藏，但会改窗口状态）

| 调用 | file:line | 触发源 | 频率 | 风险 |
|---|---|---|---|---|
| `reassertVisible()` | `StatusOverlay.kt:699-709` | `ProgressHub.maybePublish` 在 `!appVisible` 时（`ProgressHub.kt:253`） | 进度发布间隔 **120ms**（`:233-235`） | **强制 `VISIBLE`，绕过 `hideRefs`**；每次还 `updateViewLayout` + `requestLayout` |
| `safeUpdateLayout` → `wm.updateViewLayout` | `StatusOverlay.kt:1151-1166` | 拖动、`applyWindowSize`（`:625-654`）、`reassertVisible`、`startOverlay` 的 `post`（`:298-305`） | 拖动~60Hz / 发布 8Hz | 尺寸不变时不重分配 buffer；**展开态 `height=WRAP_CONTENT`（`:628`）时高度变化会真正 resize** |
| `applyExpandLevel` | `StatusOverlay.kt:551-618` | 展开/折叠、`ensureExpandedContent`（`:1010-1025`，**每秒 ticker 调一次**，`:996-1003`）、635 的 `endCaptureAvoid` | 每秒 1 次自检 | 只在「异常」时才真重放；正常路径只是 `bindHandle` + `requestLayout` |
| `hideInternal`（`removeView`） | `StatusOverlay.kt:717-729` | `hide` / `hideAuto` / `teardown` / `onAccessibilityLost` | 用户长按、引擎退出、服务断开、60s 保活重启（`EngineService.kt:134-137`） | **摘窗口 = 必然闪一次**，且不清 `hideRefs`（P1-3） |
| `startOverlay` / `dropStaleRoot` | `:204-310` / `:689-697` | `show(ctx)` 由 `ProgressHub`（`:104`、`:257`、`:411`）与 a11y 服务（`DshAccessibilityService.kt:116`、`:202`）调用 | 每次进度发布都调 `show()`，但 `root != null` 时立刻 return（`:181-182`） | 只有在窗口被摘掉后才会重建 ⇒ 与 `hideInternal` 组合成「消失→重建」 |

---

## 2. `hideRefs` 引用计数：证明/否证失衡

### 2.1 成对路径是平衡的（证明）

- `Router.route`：`begin`(`Router.kt:57`) / `finally end`(`:61`) —— 平衡。
- `ActionHandler` 批处理：`begin`(`:504`) / `finally end`(`:712-714`)，注释明写「否则整批结束后悬浮窗永久不可点」—— 平衡。
- `ScreenHandler` 截图回退：`begin`(`:794`) / `finally end`(`:795`) —— 平衡。
- `screenshotArgb`：外层 `931` / `finally 943`；内层 `976` / `finally 981` —— 416 内嵌套，引用计数下净变化 0（只是多两条 no-op 日志：「already hidden refs=2」「still hidden refs=1」）。
- `screenshotBase64(avoid=true)`：`1036` / `finally 1056` —— 平衡。

**结论：正常路径下 `hideRefs` 不会漂移。** OVERLAY.md §3.z 的回归判据（静置 20s `capture avoid: removed=true` 计数为 0）在 HEAD 上仍然成立——但注意该判据是 635 时代的字符串（`removed=` 只存在于 `removeViewImmediate` 那版），HEAD 应改看 `capture avoid: begin` 条数。

### 2.2 三条**会**让计数失衡/失真的路径（否证「绝对安全」）

1. **`hideInternal` 不清 `hideRefs`**（`StatusOverlay.kt:717-729`）。
   只要 `hide/hideAuto/teardown/onAccessibilityLost` 落在某次避让窗口内（例如 prefetch 的 ~1.9s `screenshotBase64` 期间引擎被保活重启、或无障碍服务被小米回收），`hideRefs` 就永久 >0：
   - 之后每次 `beginCaptureAvoid` → `hideNow` 走 `if (hideRefs > 1) return` 分支 → **不再隐藏** → 截图带悬浮窗；
   - 每次 `endCaptureAvoid` → 走 `still hidden` 分支 → 不再显示（此时窗口是新建的，本身 VISIBLE，所以肉眼看不出来，但 `debugState()` 里 `hideRefs` 永远非 0）；
   - 只有 ops 看门狗（`:806-812`，且只有 `beginOpsAvoid` 才会排它）能清零 ⇒ **capture 泄漏不会被自动修复**。

2. **`hideNow`/`showNow` 无视 `mainSync` 成败**（`:761-767`、`:776-786`）。
   `mainSync` 超时 800ms 返回 false 时（`:1129-1142`），计数已经 `+1`/`-1`，但可见性没改。**`hideNow` 失败 ⇒ 计数永久偏高**（和 P1-3 相同后果）；**`showNow` 失败 ⇒ 窗口停在 INVISIBLE、计数却为 0**，而 `showNow` 下次进入时 `if (hideRefs <= 0) { hideRefs = 0; return }`（`:771-774`）**直接放弃显示** ⇒ 悬浮窗卡死在「看不见」，直到下一次完整的 `hideNow→showNow` 或后台进度发布触发 `reassertVisible`。
   主线程在本 App 里同时承载 WebView UI，>800ms 停顿并不罕见 ⇒ 这与「**有时**点不到 / **有时**不见了」的现象吻合。

3. **`reassertVisible()` 完全绕过计数**（`:699-709`，由 `ProgressHub.kt:253` 调用）。
   它不属于任何 `hideRefs` 事务，却直接把 `visibility` 置 `VISIBLE`：
   - **截图避让期间**被调用 ⇒ 截图里拍到悬浮窗（`docs/OVERLAY.md §3.z` 想避免的正是这个）；
   - **操作避让期间**被调用 ⇒ 窗口重新可命中 ⇒ 注入手势点在自己身上（「AI 点不到目标」）；
   - 与 `hideNow`/`showNow` 交替 ⇒ 隐藏-显示频率 = 进度发布频率（120ms）⇒ **8Hz 级别的闪烁**。
   作者注释写「部分 ROM 会把应用悬浮窗 surface 置 hidden」，所以这个函数存在有其原因，但它**没有和引用计数协调**，是 HEAD 上最明确的不变量破坏点。

---

## 3. OCR prefetch 是否仍会触发隐藏/显示

| 版本 | 代码 | 是否隐藏 | 说明 |
|---|---|---|---|
| **2.9.64（真机 635）** | `git show f43f77e:…/ScreenHandler.kt` 的 `prefetchOcr`：`svc.screenshotBase64(2500L)` | **是，且是摘窗口** | 635 的 `screenshotBase64` 签名是 `fun screenshotBase64(timeoutMs: Long = 3000L)`（`git show f43f77e:…/DshAccessibilityService.kt:1015`），**没有 `avoidOverlay` 参数**；内部 `beginCaptureAvoid`（`:1019`）走的正是 `removeViewImmediate + sleep(400) + addView + applyExpandLevel`。触发点：`Router.routeInner` 第一行对**每个 HTTP 请求**都调 `prefetchOcr`（`Router.kt:69`），TTL 仅 2500ms（`ScreenHandler.kt:45-46`）。⇒ **AI 干活期间每 2.5s 必摘一次窗口**；AI 不干活时若 WebUI 仍轮询，同样每 2.5s 一次。这正是用户「静置时约 4 秒消失一次」的观感，也正是用户对 prefetch 的怀疑。 |
| **HEAD（2.9.71 / 642）** | `ScreenHandler.kt:53` `svc.screenshotBase64(2500L, avoidOverlay = false)` | **否** | 走 `DshAccessibilityService.kt:1026-1035` 的 no-avoid 分支，只做 333ms 排队 + 2 次尝试；悬浮窗留在画面里，由 `currentFrameRect()`（`StatusOverlay.kt:135-145`）在 OCR 结果里按矩形过滤（`ScreenHandler.kt:58-70`，日志 `dropped=`）。 |

**判定：prefetch 在 HEAD 已不是闪烁来源（已被 2.9.65 修掉）；但真机跑的是 635，prefetch 是当前的头号来源。**

> 顺带一个 HEAD 遗留缺陷（不造成闪烁，但会造成「看不见」）：`prefetchOcr` 里 `svc.screenshotBase64(...) ?: return@Thread` 位于 `try` 内、`finally { ocrPrefetching = false }` 之外？——实测代码在 `finally` 内复位（`ScreenHandler.kt:71-74`），**无泄漏**，此条排除。

---

## 4. `mainSync` 主线程风险 + `INVISIBLE` 对窗口/surface 的系统语义

### 4.1 `mainSync`（`StatusOverlay.kt:1129-1142`）

- 语义：已在主线程 → 内联执行；否则 `post` + `CountDownLatch.await(800ms)`，超时返回 `false`。
- **它不会阻塞主线程**（只是让调用方线程等 —— 调用方是 HTTP/工作线程），这点比 2.9.47 时代（主线程内等栅栏导致冻结）已经好了。
- 但有两个真实风险：
  1. **静默失败**（P1-2）：返回值被 `hideNow`/`showNow` 丢弃。
  2. **调用方线程被拖 800ms**：`beginCaptureAvoid` 在 `hideNow` 之后还要 `sleep(120)`（`:796`）。若主线程正忙（WebView 排版 / 帧轮询的 `screenshotArgb` 也在抢），一次避让的等待可以到 ~920ms；叠加 3 次重试就是秒级。这正是「截图慢、悬浮窗消失很久」的机理。
  3. 主线程同时也是 **WebView 的 UI 线程**（同一进程），AI 输出流频繁触发进度发布（`update` → `maybePublish` → `main{…}`）+ `requestLayout`，**主线程负载与悬浮窗可见性耦合**。

### 4.2 `INVISIBLE` 的系统语义（这条是 HEAD 设计的地基，必须写清）

- `root.visibility = INVISIBLE` ⇒ 下一次 traversal 时 `ViewRootImpl.performTraversals()` 以 `viewVisibility = INVISIBLE` 调 `relayoutWindow()` ⇒ `WindowManagerService` 把该 window 标记为不可见，**对应 `SurfaceControl` 被隐藏**。因此：**不绘制**（解决截图拍到悬浮窗）、**不参与合成**（不闪白框）、**不接收触摸**（命中测试里 `visible=false` 的窗口被跳过）。
- 作者在 `StatusOverlay.kt:750-755` 把这三条写成「铁律」，`docs/OVERLAY.md §3.t` 同款。**静态上成立**；`§3.v` 的历史证据（626–640 那版真出现过「看得见但点不动」）也反证了触摸确实受窗口可见性影响。
- **【推断】** `InputDispatcher` 是按 `InputWindowInfo.visible`（由 `mViewVisibility` 推出）过滤触摸目标的；因此 INVISIBLE 期间点击会**穿透到下层 app**，而不是被吞掉。这条对「点不到」的定性很关键，建议真机验证（§8 命令 3）。
- 副作用：因为「隐藏 = surface 隐藏」，恢复可见时要重新显示 surface 并等一帧合成 ⇒ **一次隐藏/显示在视觉上必然有 1 帧的空白**。**在 3–5Hz 的频率下，这就是人眼看到的「闪烁」**，即便实现完全正确。结论：闪烁的根治不是「换一种隐藏方式」，而是**降低隐藏次数**。

---

## 5. 「有时点不到」专项

### 5.1 候选机理与判定

| 机理 | 证据 | 判定 |
|---|---|---|
| **A. 点击瞬间撞上隐藏窗口，触摸穿透到下层 App** | 隐藏可达 120ms–1.9s（`StatusOverlay.kt:796`；`DshAccessibilityService.kt:1036-1057`），且在 AI 工作时按 3–5Hz 反复进入隐藏态（§1.2）。人类「看到→落下」约 100–200ms，**完全落在隐藏窗口内** | **最一致**（P2-1）。机制可证，命中率需真机量化 |
| **B. 635 上 `FLAG_NOT_TOUCHABLE` 残留** | `git show f43f77e:…/StatusOverlay.kt:808-847`：自动化避让用 flags 实现；`:1170-1175` 作者注释「视图被摘除时 `updateViewLayout` 会抛」被 `runCatching` 吞掉 ⇒ 清标志失败 = 永久点不动。2.9.67/2.9.70 两次修复正是这个 | **在真机上很可能同时存在**（P2-2）。这是历史 §3.v 的原始症状 |
| **C. 635 摘窗口导致手势被 cancel** | `removeViewImmediate`（`f43f77e:…:755`）发生在 DOWN 与 UP 之间时，系统会 cancel 该窗口的触摸序列 ⇒ UP 永不到达 ⇒ `attachGesture` 的 `onClick` 不触发（`StatusOverlay.kt:394-467`），球既不展开也不响应 | **很可能**（P2-1 的另一半） |
| D. 引用计数泄漏导致「看不见」而不是「点不到」 | §2.2 第 1/2 条 | 解释「有时不见了」，不解释「点不到」 |
| E. `attachGesture` 在 UP 前被 `bindHandle` 重挂监听 | `ensureExpandedContent` 每秒调 `applyExpandLevel` → `bindHandle`（`:551-618`、`:328-379`）会 `setOnTouchListener(null)` 再重设 | **排除**：同一 `View` 对象上的 listener 重设不影响其已接收的 MOVE/UP；且 DOWN 已 `return true` 吃掉事件 |
| F. targetSdk=28 兼容行为导致窗口不可点 | `AndroidManifest` 的 `targetSdk=28` 只影响兼容开关；`TYPE_ACCESSIBILITY_OVERLAY` 的命中区域来自窗口 frame，与 targetSdk 无关 | **未能从源码证明有影响**，暂列未决（§9） |

### 5.2 触摸通路复核（无发现）

- 折叠态：`bindHandle` 里 `attachGesture(view, onClick=expandPanel, onLongPress=hide)`，DOWN 一律 `return true`（`:394-467`）——正常。
- 展开态：只有 `statusDot` 挂 `collapseToBall` + 12dp `TouchDelegate`（`:343-358`），滚动区按 `canScrollVertically` 放行（`:418-424`，2.9.69 修的）——与 `docs/OVERLAY.md §3.w` 一致，无新问题。
- **唯一可疑点**：窗口尺寸/位置由 `applyWindowSize`（`:625-654`）决定，而 `p.x/p.y` 在 `startOverlay` 的 `view.post` 里按屏幕宽回算（`:298-305`）+ `snapToEdge` 改。若 `updateViewLayout` 连续失败（`safeUpdateLayout` 只 `Log.w`，`:1151-1166`），窗口会停在旧位置，而视觉上因为窗口内容没变，用户可能「点在球上但窗口其实偏了几像素」——概率低，列未决。

---

## 6. 根因排序（可直接据此排修复优先级）

### 6.1 针对**当前真机（2.9.64 / 635）**

1. **P0-2 prefetch 摘窗口**（每 HTTP 请求 / 2.5s）——已由 2.9.65 修复，**升级即消除**。
2. **P0-1 每次截图/读屏都摘窗口 400ms 并重放布局**——已由 2.9.68 改为 INVISIBLE，**升级即大幅缓解**。
3. **P2-2 `FLAG_NOT_TOUCHABLE` 残留**——已由 2.9.67/2.9.70 修复。

> 因此**第一优先动作不是改代码，而是把 635 升到 642**，再复测。若升级后仍闪，那就是 HEAD 的问题（下面 6.2）。

### 6.2 针对**HEAD（2.9.71 / 642）**——升级后仍会闪的部分

1. **P1-1 `reassertVisible` 绕过引用计数**（`ProgressHub.kt:253` + `StatusOverlay.kt:699-709`）：改法有两种，都不许重建窗口 —— ① 后台时**不要**每次发布都 `reassert`，改成「检测到 surface 被 ROM 置 hidden 时」才调（例如 `root.isAttachedToWindow && !root.isShown()` 才调）；② 或者让 `reassertVisible` 遵守计数：`if (hideRefs > 0) return`。
2. **P0-3 `screenshotArgb` 缺 `avoidOverlay` 开关**（`DshAccessibilityService.kt:909`）：给 Framer / 帧轮询 / `frameWait` 传 `avoidOverlay=false`，让它们**不避让**（画面里带一个 36dp 球对「变了没」几乎无影响，而 64×64 降采样会直接把它摊平）。这是收益最大、风险最小的一刀：**把 3–5Hz 的隐藏降到 0**。
3. **P1-2 `hideNow/showNow` 检查 `mainSync` 返回值并回滚计数**（`:758-787`）。
4. **P1-3 `hideInternal` 里把 `hideRefs` 归零**（`:717-729`），或在 `startOverlay` 成功后重置。
5. **P1-4 让 `screenshotArgb` 也走 `awaitCaptureSlot()`**（`:932` 之前），避免 3×450ms 的长隐藏。

---

## 7. 对「用户怀疑 prefetch」的直接回答

- **在 635 上：用户怀疑正确**，而且是最大单一来源（每个 HTTP 请求 → 2500ms TTL → 摘窗口 400ms + 重建 + 重放布局）。
- **在 HEAD 上：已不成立**（`avoidOverlay=false`，见 `ScreenHandler.kt:53`）。HEAD 上真正的高频来源变成了 `screenshotArgb` 的四个消费者（§1.2 表）与 `reassertVisible`。

---

## 8. 建议 lead 在真机上跑的 3 条验证命令

> 目标：① 确认设备版本差异；② 量化隐藏频率并归因到具体消费者；③ 确认「点不到」是穿透还是被吞。

**① 版本与机制二选一确认（决定后续所有结论的前提）**

```bash
adb -s <serial> shell dumpsys package com.termux | grep -E "versionCode|versionName"
# 期望 635 ⇒ 下面看 removeView 版日志；若是 642 ⇒ 看 visibility 版日志
```

**② 量化闪烁频率 + 归因（静置 20s vs 跑一轮 AI，各抓一次）**

```bash
adb -s <serial> logcat -c
timeout 30 adb -s <serial> logcat -v time -s StatusOverlay:I DshA11y:I \
  | grep -E "capture avoid: (begin|removed=)|capture restore|hide\\(|show\\(|screenshot: (begin|avoid|warmup|attempt|restore)|still hidden refs" \
  > /tmp/overlay.log
# 635 判据：静置 20s 若出现任何一行 "capture avoid: removed=true" 或 "screenshot:" ⇒ 被高频消费者打中（正常应为 0）
# 642 判据：统计 "capture avoid: begin" 行数 / 秒 ⇒ 预期 0（静置）；>1/s 即 P0-3 成立
#          再统计 "hide(capture)" 与 "show(capture)" 是否严格 1:1 ⇒ 不等即 P1-1/P1-2/P1-3 失衡
#          若出现 "still hidden refs=" ⇒ 引用计数已漂移（P1-3）
grep -c "capture avoid: begin" /tmp/overlay.log
grep -oE "hide\([a-z]+\)|show\([a-z]+\)" /tmp/overlay.log | sort | uniq -c
```

**③ 「点不到」定性：点球那一刻窗口是否在隐藏态**

```bash
# 一边连续点球所在位置，一边看窗口可见性与计数（642 才有 hideRefs 字段）
while true; do adb -s <serial> shell dumpsys window windows \
  | grep -E "app.dsh.mobile/.*(Overlay|StatusOverlay)" -A2 ; sleep 0.2; done
# 同时另开一个 shell：
adb -s <serial> logcat -v time -s StatusOverlay:I | grep -E "handle touch: action=0|hide\\(|show\\("
# 判据：若用户报「点不到」的瞬间日志里没有 "handle touch: action=0"（DOWN 没到我们），
#       且同一时刻有 hide(capture)/hide(ops) ⇒ 触摸穿透（机理 A）成立，与 targetSdk 无关。
#       若 DOWN 到了、但 UP 之后没有 "click"/expandPanel ⇒ 手势被 cancel（机理 C）。
```

---

## 9. 未决 / 需要真机或系统内部才能定的点

1. **INVISIBLE 窗口是否真的不参与命中测试**：AOSP 里 `InputWindowInfo.visible` 由 `mViewVisibility` 推出，静态推理成立，但未在 Android 16 + MIUI/HyperOS 上实测；若某些 ROM 忽略该字段，则 ops-avoid 会静默失效（注入手势点到隐形悬浮窗）。
2. **`reassertVisible` 的实际必要性与频率**：注释称「部分 ROM 会把应用悬浮窗 surface 置 hidden」，本机是否为其中之一未知；若否，`ProgressHub.kt:253` 可以整段删掉，闪烁直接少一大截。
3. **targetSdk=28 对该窗口的影响**：无法仅从源码判定；`dumpsys window` 里该窗口的 `mAttrs.targetSdk` / `fitInsetsTypes` 可直接读出，建议顺带抓一份。
4. **保活重启频率（P2-3）**：`keepalive` 每 60s 判断一次 `EngineService.isRunning()`（`DshAccessibilityService.kt:236-247`），若真机上引擎组反复被杀，`EngineService.onDestroy → teardown`（`EngineService.kt:134-137`）会造成周期性「闪一下」。需 `logcat -s DshA11y:I | grep keepalive` 佐证。
5. `ProgressHub.preview()`（`:407-425`）在 `setStatus` 之后紧跟 `clearAll(ctx)`，缩进可疑，疑似「预览立即被清空」；与本问题无关，仅记录。

---

## 10. 显式核对：任务点名的其余状态路径是否改可见性

| 路径 | file:line | 会不会改 `root.visibility` | 说明 |
|---|---|---|---|
| `onTurnEnd`（WebUI turn/end） | `engine/AgentBridge.kt:262-266` → `StatusOverlay.markReplyReady` `:1074-1080` | **不会** | 只 `tintDots(COLOR_DONE)` + `replyReady=true`，不排定时器、不动 visibility |
| `flashComplete` / `flashCompleteDefault` | `StatusOverlay.kt:1091-1099` / `:1102-1104` | **不会** | 同上，只染色；`holdMs` 形参实际未使用（`@Suppress("UNUSED_PARAMETER")`） |
| `setIdle` | `:1043-1056` | **不会** | 改文字/圆点/停 ticker + `ensureExpandedContent()` |
| `scheduleIdle` 定时器 | `:864-869` | **不会** | 只延时调 `setIdle`；且可被 `cancelIdleReset` 取消（`:843-848`） |
| `tickRunnable`（每 1000ms） | `:996-1003` | **不会**（间接可能） | `updateElapsedText` + `ensureExpandedContent()`；后者仅在「子视图/文字/颜色不一致」时 `applyExpandLevel`（`:1010-1025`），即修复性重排，不是隐藏 |
| `opsAvoidWatchdog`（90s） | `:806-812` | **会**（`VISIBLE` + 计数清零） | 只在 `beginOpsAvoid` 排过它时触发 |
| `refreshActionVisibility` | `:857-861` | **不会** | 只动 `a11yText` / `footRow` 两个子视图的 `GONE/VISIBLE` |
| `setStatus` / `setA11yAction` / `setSession` / `setElapsed` | `:908-936`、`:881-892`、`:970-994` | **不会** | 只改文字；`setStatus` 里 `statusText.visibility = expandLevel>0` 是子视图 |

**结论：root 级可见性只有 §1.1 表里的 6 个公开入口（`hideNow` 的两个封装 + `reassertVisible` + `hideInternal` + `applyWindowSize` 的 stale-flag 清理 + `startOverlay` 建窗）。**其余状态机代码都不碰可见性，可以排除。

---

## 11. 文本调用图

```
[HTTP 请求线程]                                   [AI/引擎线程]                [主线程]
Router.route(path)
 ├─ POST 动作类 ──► beginOpsAvoid() ─────────────────────────────┐
 │                                                              │
 └─ routeInner 首行 ──► prefetchOcr()                            │
        │(2.5s TTL, 防重入)                                     │
        └─► Thread { screenshotBase64(avoidOverlay=**false**) } │   ← HEAD: 不隐藏
                └─ awaitCaptureSlot(350ms) → takeScreenshot      │
                                                                 │
/screenshot  ──► screenshotBase64(avoid=true) ──┐                │
/dump        ──► screenshotArgb(700) ───────────┤                │
/frame-wait  ──► Framer.captureAndDiff ─────────┤                │
/frame-diff  ──► Framer.captureAndDiff ─────────┤                │
verify/settle──► Framer.captureAndDiff ─────────┤                │
ScreenOcr    ──► screenshotArgb() ──────────────┤                │
VisionHandler──► screenshotArgb() ──────────────┘                │
                                                 │               │
                          beginCaptureAvoid(svc)│               │
                            ├─ hideNow("capture") ─ mainSync ───┼─► root.visibility=INVISIBLE
                            │     hideRefs++ ；>1 直接 return    │      └ relayout(viewVisibility=INVISIBLE)
                            └─ Thread.sleep(120)                │        └ WMS 隐藏 surface
                                                 │               │
                          …takeScreenshot…       │               │
                                                 │               │
                          endCaptureAvoid(svc)   │               │
                            └─ showNow("capture") ─ mainSync ───┼─► root.visibility=VISIBLE
                                  hideRefs-- ；>0 直接 return     │      └ requestLayout()

ProgressHub.maybePublish()  ──(gap 120ms)──┬─► if(!appVisible) reassertVisible() ─► VISIBLE（绕过计数！）
                                           ├─► resetHidden(); show(ctx)  ─► 仅在 root==null 时重建
                                           ├─► setStatus/setElapsed/setSession（纯文字）
                                           └─► cancelNotification / publishNotification

hide()/hideAuto()/teardown()/onAccessibilityLost() ─► hideInternal ─► wm.removeView(root)（不动 hideRefs！）
setIdle()/onTurnEnd()/flashComplete()/tick(1s) ─► 只改文字/颜色，不改 root 可见性

[保活] keepAlive(60s) → 引擎不在则 EngineService.start()
EngineService.onDestroy → teardown() + hideAuto()（拆窗）
```

---

## 12. 最小修复 patch 草案（**文字描述，未 apply**）

> 原则：不新增隐藏机制、不重建窗口、不改 flags —— 全部沿用 `docs/OVERLAY.md §3.t` 的铁律，只修「次数」和「不变量」。

1. **给 `screenshotArgb` 加 `avoidOverlay` 形参，并让帧类消费者传 `false`**（收益最大）
   - `DshAccessibilityService.kt:909`：`fun screenshotArgb(timeoutMs: Long = 5000L, avoidOverlay: Boolean = true)`
   - `:930-931` 与 `finally :943` 用 `if (avoidOverlay) { … }` 包起来；`screenshotArgbOnce` 的内层避让（`:975-981`）同理或直接删除（外层已覆盖）。
   - 调用点改传 `false`：`Framer.kt:80`（一次改，覆盖 `/frame-wait`、`/frame-diff`、`verify`、`settle` 全部）、`DshAccessibilityService.kt:625`（dump 图标识别 —— 图标匹配只关心局部矩形，球盖住的概率极低）。
   - 保留 `true` 给：`ScreenOcr.kt:120`（OCR 文本要求干净）、`VisionHandler.kt:48/105`、`ScreenHandler.kt:562`。
   - 预期效果：3–5Hz 的隐藏/显示 → 0；`/screenshot` 仍干净。

2. **让 `reassertVisible` 遵守引用计数**
   - `StatusOverlay.kt:699-709` 开头加 `if (hideRefs > 0) return`（需把该判断放在 `main{}` 内、读 `@Volatile` 值即可）。
   - 或更保守：`ProgressHub.kt:253` 改成「只有在 `root != null && !root.isShown() && root.isAttachedToWindow` 时才 reassert」，把 120ms 一次的 `updateViewLayout + requestLayout` 也一并消掉。

3. **`hideNow`/`showNow` 检查 `mainSync` 结果并回滚**
   - `:758-768`：`if (!mainSync { … }) { hideRefs -= 1; Log.w(...) }`
   - `:770-787`：`if (!mainSync { … }) { hideRefs += 1; Log.w(...) }`（失败时把计数还原，避免「计数 0 但窗口不可见」的死状态）

4. **`hideInternal` 归零计数**
   - `:717-729`：`hideRefs = 0`（窗口已摘，计数失去意义；不归零会让后续所有避让变 no-op）。放在 `root = null` 旁边即可。

5. **`screenshotArgb` 也走节流排队**
   - `:932` 之前插 `awaitCaptureSlot()`（与 `screenshotBase64` 共用 `lastCaptureStamp`），避免 `errorCode=3` 触发 3×450ms 重试造成的 ~1.4s 长隐藏。

> 以上 5 条互不冲突，可独立验收；1 与 2 是「闪烁」的主要矛盾，3 与 4 是「偶发/永久消失」的兜底，5 是「消失时长」。

---

## 附：本报告未做的事（避免误解）

- 未修改任何 `src/` 文件；本文件是唯一新增文件。
- 未运行任何 adb/logcat 命令（设备由 lead 独占），未读取真机日志（`/tmp` 下无可用 logcat 抓取）。
- 未评估「改用 MediaProjection 帧流」这类架构级方案——`DshAccessibilityService.kt:968-971` 的注释已指出这是「每帧截图」路线的根本障碍，但那属于重写，不在本次静态分析范围。
