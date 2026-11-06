# 悬浮窗「不断闪烁 / 有时点不到」真机取证与修复报告

- 版本：设备上 2.9.64(635) → 2.9.71(642) → **2.9.72(643，本次修复)**
- 设备：Xiaomi M2012K11AC（Redmi K40）/ Android 16 (SDK 36) / Magisk root / 序列号 5f08bc20
- 取证日期：2026-11-06
- 结论先行：**用户怀疑的 prefetch 确实是 635 上「不断闪烁」的唯一原因（窗口被摘掉 56% 的时间）；642 已修好 prefetch，但把同一把刀留在了帧轮询上（22% 时间 INVISIBLE，约 1 Hz）；643 把这把刀从所有高频内部消费者上撤掉，全部路径实测 0 次窗口状态跳变。**

---

## 1. 症状与判据

| 用户描述 | 本次判据（可量化） |
|---|---|
| 不断闪烁 | 悬浮窗 layer/窗口状态在 200 个高频样本中发生跳变的次数、以及「窗口不存在 / view 不显示 / surface 未显示」的样本占比 |
| 截图避让是正常的 | 显式截图（`GET /screenshot`）期间的隐藏/恢复**允许存在**，只要求「前后状态一致、时长尽量短」 |
| 有时点不到 | 隐藏期间窗口不参与命中测试（触摸穿透到下层 App）→ 用「INVISIBLE 样本占比」当代理指标 |

采样方法（root）：

```bash
# 每样本一次 dumpsys window windows，截取 com.termux 的 2032 窗口块
adb shell su -c "sh /data/local/tmp/sample.sh 200 0.05 /data/local/tmp/flicker/xxx.csv"
# 列：ts, haswin, isVisible, surfaceShown, drawState, viewVisibility, frame
python3 /tmp/dshprobe/analyze.py xxx.csv   # 统计 transitions / window_missing / viewNotVisible / surfaceNotShown
adb logcat -v time -s StatusOverlay:V DshA11y:V Framer:V ScreenOcr:V ProgressHub:V
```

---

## 2. 证据链

### 2.1 2.9.64 (635)：prefetch 摘窗 —— 闪烁主因（用户怀疑成立）

| 场景 | 样本 | 窗口状态跳变 | 窗口不存在(haswin=0) | INVISIBLE | logcat `capture avoid` |
|---|---|---|---|---|---|
| A 静置（无 HTTP 流量） | 200 | **0** | 0 | 0 | 0 |
| B 每秒 1 次 `GET /daemon/status`（触发 prefetch） | 200 | **11** | **113（56.5%）** | — | **30 组** |

logcat 形态（635）：

```
capture avoid: before (root=... attached=true ...)
capture avoid: removed=true beforeAttached=true afterAttached=false parent=null
capture avoid: done (attached=false)         # ~400ms 后
```

`Router.kt:69` 的 `routeInner` 第一行是 `screen.prefetchOcr(it)` —— **任何** HTTP 请求都会触发一次后台 OCR 预取（TTL 2500ms），而 635 的 `prefetchOcr` 调的是 `svc.screenshotBase64(2500L)`（`avoidOverlay` 默认 true）→ `removeViewImmediate` 摘窗 + `sleep(400)` + `addView` + `applyExpandLevel` 重放。

**⇒ 引擎每轮询一次，悬浮窗就被摘掉/重建一次；56% 的采样时间里窗口根本不存在。这就是「不断闪烁」；同时也是「有时点不到」——窗口不在，触摸自然穿透。**

### 2.2 2.9.71 (642)：prefetch 已修，闪烁转移到帧轮询

| 场景 | 样本 | 跳变 | 窗口不存在 | INVISIBLE | `capture avoid` | `avoid=false` 截图 |
|---|---|---|---|---|---|---|
| A 静置 | 200 | 0 | 0 | 0 | 0 | 0 |
| B prefetch 高压（70 次请求） | 200 | **0** | 0 | 0 | **0** | **14**（每 5.2s 一次，各 ~1.4s） |
| C `POST /frame-wait`（intervalMs=250，3×12s） | 200 | **67** | 0 | **45（22.5%）** | **65** | — |

B 的日志证明 prefetch 确实在跑而且不再碰窗口：

```
DshA11y: screenshot: begin avoid=false svc=true overlays=root=... attached=true live=1
         level=0 pos=959,330 size=99x99 flags=0x1000028 touchable=true hideRefs=0 type=2032
DshA11y: screenshot(no-avoid): attempt=1 ok=true
```

C 的日志是纯引用计数抖动，周期 0.7–1.0s：

```
StatusOverlay: hide(capture): visibility=4 ... hideRefs=1
StatusOverlay: hide(capture): already hidden refs=2
StatusOverlay: show(capture): still hidden refs=1
StatusOverlay: show(capture): visibility=0 ... hideRefs=0
```

**根因：`DshAccessibilityService.screenshotArgb()` 没有 `avoidOverlay` 形参，无条件 `beginCaptureAvoid()`。** 它的消费者全是高频内部测量：`Framer.captureAndDiff`（`/frame-wait` intervalMs 默认 250、verify `nextFrameAt=now+300`、settle sleep 200）、`/frame-diff`、dump 里的图标识别、`ScreenOcr`。**⇒ 凡跑自动化/读屏，悬浮窗就以 1–4 Hz 闪，且 22.5% 的时间点不到。**

### 2.3 「闪」的物理原因（AOSP + 真机双向证实）

`root.visibility = INVISIBLE` 对 `TYPE_ACCESSIBILITY_OVERLAY` 窗口**不是「隐藏图层」，而是「窗口退场」**：

```
WindowManagerService.java:2513 → :2726 → :2764
  → WindowStateAnimator.java:707-721
      t.remove(mSurfaceControl); setHasSurface(false); mDrawState = NO_SURFACE
```

再置 VISIBLE 时走 `createSurfaceLocked()` **重新分配 BufferQueue / 重建 layer 并等首帧** —— 肉眼就是一闪。

真机直接证据（本次在 643 的显式截图避让期间抓到的窗口块，连续 10 个样本）：

```
haswin=1 vis=0 surf=0 draw=mDrawState=NO_SURFACE view=mViewVisibility=0x4   ← 避让中（surface 已销毁）
haswin=1 vis=1 surf=1 draw=mDrawState=HAS_DRAWN  view=mViewVisibility=0x0   ← 恢复后（重建完成）
```

**⇒ 「不 removeView、只改 visibility 就不会重建」这个前提是错的。** 642 虽然删掉了 removeView/addView，走的却是同一条 destroy 路径，所以躲不开闪。

所以真正的解法只有一条：**不要把「隐藏窗口」当作截图的必要手段**。只让**确实需要干净画面**的消费者避让；内部测量（帧差 / OCR / 图标）不避让，改为在结果里把悬浮窗矩形排除掉。

### 2.4 「有时点不到」的官方语义

- Android 12 起 untrusted-touch 例外表：**根 view 为 `GONE`/`INVISIBLE` 的窗口，触摸放行穿透**到下层。
- 叠加 `InputMonitor.java:149 / :333-348`：输入窗口表在 animation handler 上**异步刷新**，隐藏/恢复瞬间存在输入表与窗口状态不一致的窗口期。

**⇒ 「点不到」不是玄学，是隐藏窗口的必然结果。** 只要窗口在 INVISIBLE，那一刻的点击就落到别的 App 上。降低隐藏时间占比 = 直接降低点不到的概率：

| 版本 / 场景 | INVISIBLE 时间占比 | 点不到概率（同窗口期） |
|---|---|---|
| 635 + prefetch | **56.5%**（窗口直接不存在） | 极高 |
| 642 + 帧轮询 | **22.5%** | 高 |
| 643 + 帧轮询 | **0%** | 仅剩显式截图/自动化避让期间 |

---

## 3. 截图前后的窗口状态（用户点名要求）

`GET /screenshot?save=1`（`screenshotBase64`，`avoidOverlay=true` → 唯一允许避让的路径）在 643 上的实测：

| 阶段 | `haswin` | `isVisible` | `Surface shown` | `mDrawState` | `mViewVisibility` |
|---|---|---|---|---|---|
| 截图前 | 1 | 1 | 1 | HAS_DRAWN | 0x0 |
| 截图中（10 个样本） | 1 | **0** | **0** | **NO_SURFACE** | **0x4** |
| 截图后 | 1 | 1 | 1 | HAS_DRAWN | 0x0 |

- 隐藏时长：`16:19:05.470 hide` → `16:19:07.792 restore` = **2.32s**（一次显式截图）。
- 截图成品验证：对落盘 PNG 取悬浮窗矩形 `(959,330)-(1059,430)` 求均值 = **纯白 (255,255,255)，标准差 0.0**（对照区域标准差 72.3）⇒ **截图里完全没有悬浮窗**。
- 前后状态一致、`hideRefs` 1→0 归零、无残留。

`avoidOverlay=false` 的路径（prefetch / 帧差 / OCR / 图标）实测：**窗口状态 0 变化**（A/B/C/E 全部 0 transitions），且 `hideRefs` 恒 0、`touchable=true`。

---

## 4. 修复清单（2.9.72 / 643）

| # | 文件 | 改动 | 动机 |
|---|---|---|---|
| 1 | `DshAccessibilityService.kt` | `screenshotArgb(timeoutMs, avoidOverlay = true)` + `screenshotArgbOnce(timeoutMs, avoidOverlay)`：只有 `avoidOverlay=true` 时才 `begin/endCaptureAvoid` | 把「要不要避让」交还给消费者；高频内部测量不再碰窗口 |
| 2 | `Framer.kt` | `captureAndDiff` 改传 `avoidOverlay=false`；新增 `maskOverlayRegion()`：把悬浮窗矩形覆盖的 64×64 格子用上一帧值抹平 | 帧差只关心「界面变没变」；悬浮窗自身重绘（进度环/状态点）不得算成变化 |
| 3 | `ScreenOcr.kt` | `screenshotArgb(avoidOverlay=false)`；识别块按 `StatusOverlay.currentFrameRect()` 过滤（与 prefetch 同策略） | OCR 不需要干净画面，只需要不把状态球读成文本 |
| 4 | `DshAccessibilityService.kt` | dump 里的图标识别 `screenshotArgb(700L, avoidOverlay=false)` | 同上，且它在每次 dump 里跑 |
| 5 | `StatusOverlay.kt` | `reassertVisible()` 增加 `if (hideRefs > 0) return` | ProgressHub 每 120ms 无脑 reassert，避让期间把 INVISIBLE 硬捞回 VISIBLE → 截图带悬浮窗 + 加倍闪 |
| 6 | `StatusOverlay.kt` | `hideRefs` 所有读写加 `refLock` 同步（原 `+=` 在多线程下是竞态） | 计数漂移 = 永久不再显示 / 永久不再隐藏 |
| 7 | `StatusOverlay.kt` | `hideInternal()` 归零 `hideRefs` | 窗口被摘掉后计数不归零 → 此后每个 `showNow()` 只减到 1 就 return → **「有时候再也点不到（球没了）」** |
| 8 | `StatusOverlay.kt` | `hideNow/showNow` 检查 `mainSync` 是否真的执行/生效，未生效则回滚计数 | `mainSync` 有 800ms 超时且静默返回，主线程忙（WebView 高负载）时计数与真实可见性脱钩 |

显式截图（`/screenshot`、视觉模型）与自动化（`/act`、`/batch`、`/pick`、`/overlay`、`/open/*`）的避让**保持不变**：这两类场景要么需要干净画面，要么需要触摸穿透，避让是功能需求，用户也认可。

---

## 5. 复验数据（643，同一套测试脚本）

| 场景 | 样本 | 跳变 | 窗口不存在 | INVISIBLE | `capture avoid` 次数 | 备注 |
|---|---|---|---|---|---|---|
| A 静置 | 200 | **0** | 0 | 0 | **0** | 无谓隐藏彻底消失 |
| B prefetch 高压（70 次请求） | 200 | **0** | 0 | 0 | **0** | prefetch 照跑（`avoid=false` 截图 24 次） |
| C `POST /frame-wait` ×3（intervalMs=250） | 200 | **0** | 0 | 0 | **0** | 每轮 `polls=41–43`、`waitedMs=12000`，帧差照常工作 |
| D `GET /screenshot?save=1` | 60 | 1（=1 对 hide/show） | 0 | 10（~2.3s） | **1** | 功能所需，截图中无悬浮窗 |
| E `GET /ocr` | 40 | 0 | 0 | 0 | **0** | 37 blocks / 300ms |
| F `POST /act`（`{"steps":[{"op":"skip"}]}`） | 60 | 1（=1 对） | 0 | 3（~0.72s） | 0（走 ops 通道 1 次） | `hideRefs` 1→0 正确归零 |

日志侧核对：`hide(capture)` 1 次、`hide(ops)` 1 次、`show(capture)` 1 次、`show(ops)` 1 次，**计数完全对称**；`refs rolled back / FAILED` **0 次**；崩溃 **0 次**。

帧差功能回归（掩码没把画面改坏）：

```
GET /frame-diff（静置）  → {"changed":false,"changedRatio":0.0029,"costMs":68}
展开通知栏后            → {"changed":true ,"changedRatio":0.8228,"costMs":107, hotRegions:[...]}
```

---

## 6. 残留风险与后续建议

1. **显式截图仍会闪一次，且隐藏 2.32s。** 这是当前架构的上限（`screenshotBase64` 的 warmup + 3 次取帧 + 333ms 节流叠加）。要让「截图也不再闪」，需要换机制而不是换参数：
   - 首选 `AccessibilityService.takeScreenshotOfWindow()`（API 34+，本机 SDK 36 可用）：官方文档明说「目标窗口可能被 a11y overlay 覆盖时用它」，**根本不必隐藏悬浮窗**；
   - 或自持 `SurfaceControl` + `Transaction.hide()`（只翻 `eLayerHidden`，不销毁 buffer）；
   - 或 `root.alpha = 0f`（不改 window visibility，不触发 destroy 路径）。后两者在 API 26–33 上也可降级使用。
2. **ops 避让仍是 INVISIBLE**（每个动作 ~0.7s）。若要彻底消灭「点不到」，可改为「仅在注入点落在悬浮窗矩形内时才隐藏」——悬浮窗只占 99×99，绝大多数点击根本不需要避让。
3. **prefetch 的 `tier=tiny` 目前返回 `n=0 blocks`**（`prefetch ocr ready n=0`）。这是既有问题（tiny 模型/输入尺寸），与本次闪烁无关，但意味着 prefetch 实际没提供 OCR 结果，值得单独排查。
4. 本次未做长时间 soak（>10min）与「引擎真实跑完整任务」场景的连续录像；建议后续用 `scripts/probes/flicker/capture.sh 60 --record` 补一次。

---

## 7. 复现 / 回归命令

```bash
# 设备端采样器（已在 /data/local/tmp/）
adb shell su -c "sh /data/local/tmp/sample.sh 200 0.05 /data/local/tmp/flicker/x.csv"
adb shell su -c "sh /data/local/tmp/test643.sh"          # A–E 全套

# 专业取证工具链（device-forensics 交付，支持四类判据 a/b/c/d）
cd scripts/probes/flicker && ./capture.sh 15 --light
cd scripts/probes/flicker && ./capture.sh 20 --record

# 宿主机需要端口转发（bridge 只监听设备 127.0.0.1:3083）
adb forward tcp:3083 tcp:3083
TOK=$(adb shell su -c "cat /data/user/0/com.termux/files/usr/.bridge-token" | tr -d '\r')
curl -s -H "X-DSH-Token: $TOK" http://127.0.0.1:3083/frame-diff
```

配套材料：

| 文件 | 内容 |
|---|---|
| `docs/qa/flicker-analysis.md` | 静态分析：全部可见性路径 + 引用计数失衡点 + 根因排序 |
| `docs/qa/flicker-research.md` | AOSP/官方文档级机理与方案表 |
| `docs/qa/flicker-forensics.md` | root 取证工具链、四类判据（layer 消失 / buffer 空 / 位置跳变 / 陈旧帧） |
| `scripts/probes/flicker/` | 可复用采集脚本（layers/frames/input/logcat/record + analyze.py + selftest.sh） |
| `docs/OVERLAY.md` §3.u | 本次结论固化成的硬规则 |
