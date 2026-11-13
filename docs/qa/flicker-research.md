# 无障碍悬浮窗闪烁 / 截图避让 / 点不到 —— 资料研究

> 历史取证记录：结论仅适用于文中设备、版本和采样范围。当前行为与维护约束见相关使用文档；旧行号和临时文件路径不代表当前工作区。

> 调研时间：2026-11-06。所有 AOSP 源码均取自 `refs/heads/main`（即当时最新 main，介于 Android 16 与 17 之间），
> 通过 android.googlesource.com 的 `+/refs/heads/main/<path>?format=TEXT`（返回 base64）实际下载后阅读，
> 报告中的 `文件:行号` 与 §8.1 的 URL 可逐项核对，**行号为抓取当刻的值，上游提交后会漂移**。
>
> 可信度标记：
> - 【源码级】直接读到 AOSP 源码实现（最强）
> - 【官方文档】developer.android.com / 官方行为变更页原文
> - 【社区】issue / 论坛 / 第三方仓库正文（可复现描述，但非权威）
> - 【未验证】链接存在但内容抓不到（JS / 登录墙），**不作为论据**
>
> 每条结论后附来源。凡「源码级」结论，均给出 `文件:行号` 与仓库路径，可在上表 URL 中核对。

---

## 0. TL;DR（先看这 5 条）

1. **`root.visibility = INVISIBLE` 对 TYPE_ACCESSIBILITY_OVERLAY 不是「隐藏图层」，而是「让窗口退场」**：WMS 会走
   `tryStartExitingAnimation → destroySurface`，**真正 remove 掉 SurfaceControl 并释放 BufferQueue**，客户端 Surface /
   BLASTBufferQueue 一起销毁；再次 VISIBLE 时重建全新 layer，要等重绘 + 首帧入队才重新出现在屏上 → 中间若干帧没有这层 = 肉眼「消失又出现」。
   【源码级】—— 这就是 2.9.71 周期性闪烁的直接机制，`hideRefs` / 截图避让每触发一次就付一次重建代价。
2. **「不动 removeView/addView 就不会重建 surface」这个前提是错的**：view 可见性本身就足以让 WMS 销毁 surface，不需要摘窗口。
   `removeViewImmediate + addView` 之所以闪，是走了**同一条** destroy 路径（外加 input channel 重注册）。【源码级】
3. **截图根本不需要隐藏悬浮窗**：`takeScreenshotOfWindow`（API 34）官方文档明确说：当目标窗口可能被无障碍悬浮窗盖住时，
   用它可以在截图里**不带悬浮窗内容**。另外 SurfaceFlinger 的截图路径只重合成 `isVisible` 的 layer，`SURFACE_HIDDEN` /
   layer alpha=0 的层天然不进截图。【官方文档 + 源码级】
4. **「有时点不到」有两条独立成因**：① 切 INVISIBLE 期间，官方规定「根 view GONE/INVISIBLE 的窗口」的触摸会**放行穿透**，
   于是点击落到下层 App；② surface 销毁/重建期间 input window 表只有 animation handler 上的异步刷新，存在层与 input handle
   不一致的窗口期。【官方文档 + 源码级】—— 也与 LayerSnapshotBuilder 里 `skip_invisible_windows_in_input` 的
   `InputConfig.NOT_VISIBLE` 标记相关。
5. **HyperOS 还有一条「整条服务被杀」的路径**：Android 在 accessibility app 被 force-stop 时会**剥离无障碍权限**，
   而 HyperOS 被证实会在某些事件（如进入超级省电）硬编码 force-stop → 服务死、悬浮窗消失、重绑后回来。
   与①的「每几秒」节奏不同，**必须用 logcat 区分**，否则会误判修复效果。【社区】

---

## 1. Q1：给 content view 设 INVISIBLE/GONE 时，窗口 layer / surface 会怎样？

### 1.1 结论（一句话）

**view 的 INVISIBLE / GONE 会让 WMS 立刻销毁该窗口的 surface（`t.remove(mSurfaceControl)`），layer 从合成层级中消失，
BufferQueue 被释放；再次 VISIBLE 时重新 `createSurfaceLocked()` 建新 layer，并等待客户端重新绘制 + finishDrawing 才重新被合成。**

且 **INVISIBLE 与 GONE 在 WMS 眼里没有区别**——判据只是 `viewVisibility == View.VISIBLE`，非 VISIBLE 一律走退场分支。

### 1.2 逐行调用链（客户端 → 服务端 → 客户端）

| 步 | 位置 | 关键代码 | 说明 |
|---|---|---|---|
| 1 | `frameworks/base/core/java/android/view/ViewRootImpl.java:3771` | `relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);` | `viewVisibility` 来自 `getHostVisibility()`（同文件 3488）；INVISIBLE/GONE 都 `!= VISIBLE` |
| 2 | `ViewRootImpl.java:3498–3504` | `viewVisibilityChanged` 分支 | 记下可见性变化 |
| 3 | `ViewRootImpl.java:3578–3580` | `if (viewVisibility != View.VISIBLE \|\| mNewSurfaceNeeded) { endDragResizing(); destroyHardwareResources(); }` | **只**销毁 HW 资源，surface 还活着（这一步容易误判） |
| 4 | `ViewRootImpl.java:3593` | `if (mAttachInfo.mWindowVisibility != View.VISIBLE) host.clearAccessibilityFocus();` | 窗口不可见时清无障碍焦点 |
| 5 | `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java:2489` | `win.setViewVisibility(viewVisibility);` | 服务端先记下 `mViewVisibility` |
| 6 | `WindowManagerService.java:2505–2507` | `final boolean shouldRelayout = viewVisibility == View.VISIBLE && (win.mActivityRecord == null \|\| win.mAttrs.type == TYPE_APPLICATION_STARTING \|\| win.mActivityRecord.isClientVisible());` | 非 VISIBLE → `shouldRelayout = false` |
| 7 | `WindowManagerService.java:2508–2512`（注释）+ `2513` | 注释：`If we are not currently running the exit animation, we need to see about starting one.`；`if (!shouldRelayout && winAnimator.hasSurface() && !win.mAnimatingExit) {` | 进入退场判定 |
| 8 | `WindowManagerService.java:2517` | `result \|= RELAYOUT_RES_SURFACE_CHANGED;` | 告诉客户端「surface 要换」 |
| 9 | `WindowManagerService.java:2529` | `tryStartExitingAnimation(win, winAnimator);` | 见下 |
| 10 | `WindowManagerService.java:2726` | `private void tryStartExitingAnimation(WindowState win, WindowStateAnimator winAnimator)`，注释 `Try starting an animation; if there isn't one, we can destroy the surface right away.` | 没动画就**立刻销毁** |
| 11 | `WindowManagerService.java:2734` | `if (win.isVisible() && win.isDisplayed() && win.mDisplayContent.okToAnimate())` | overlay 无 `windowAnimations` → `applyAnimationLocked` 返回 false、`isSelfAnimating` false；shell transition 分支要求 `mActivityRecord != null` → `reason == null` |
| 12 | `WindowManagerService.java:2764–2769` | `if (!win.mAnimatingExit) { boolean stopped = win.mActivityRecord == null \|\| win.mActivityRecord.mAppStopped; win.mDestroying = true; win.destroySurface(false, stopped); }` | **无障碍悬浮窗没有 ActivityRecord → `stopped = true` → 直接销毁** |
| 13 | `WindowState.java:3355` | `boolean destroySurface(boolean cleanupOnResume, boolean appStopped)`；guard `if (!(appStopped \|\| mWindowRemovalAllowed \|\| cleanupOnResume)) return;` | 第 12 步传入 `appStopped=true` → 通过 guard |
| 14 | `WindowState.java:3403–3406` | `void destroySurfaceUnchecked() { mWinAnimator.destroySurfaceLocked(mTmpTransaction); mTmpTransaction.apply();` | 落事务 |
| 15 | `WindowStateAnimator.java:707–721` | `t.remove(mSurfaceControl); setShown(false); mSurfaceControl = null; mWin.setHasSurface(false); mDrawState = NO_SURFACE;` | **layer 从层级移除、buffer 释放、窗口状态归零** |
| 16 | `ViewRootImpl.java:9480–9527` | `final boolean surfaceControlChanged = (relayoutResult & RELAYOUT_RES_SURFACE_CHANGED) == ...;` → `if (mSurfaceControl.isValid()) {...} else { destroySurface(); }` | 客户端跟进销毁 |
| 17 | `ViewRootImpl.java:2844–2860` | `mSurface.release(); mSurfaceControl.release(); if (mBlastBufferQueue != null) { mBlastBufferQueue.destroy(); }` | **BLASTBufferQueue 一起销毁** |
| 18 | `ViewRootImpl.java:3858–3864` | 注释 `When using Blast, the surface generation id may not change when there's a new SurfaceControl. In that case, we also check relayout flag RELAYOUT_RES_SURFACE_CHANGED`；`surfaceReplaced = (... \|\| surfaceControlChanged) && mSurface.isValid();` | 重建识别 |

源码入口（任一浏览器可核对，把 `?format=TEXT` 去掉即为 HTML 视图）：

- `ViewRootImpl.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewRootImpl.java
- `WindowManagerService.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowManagerService.java
- `WindowState.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowState.java
- `WindowStateAnimator.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowStateAnimator.java

### 1.3 时序（一次 INVISIBLE→VISIBLE 实际发生了什么）

```
t0  客户端 setVisibility(INVISIBLE)
t1  requestLayout → traversal → relayoutWindow(viewVisibility=INVISIBLE)
t2  WMS: shouldRelayout=false → tryStartExitingAnimation → destroySurface
      └ t.remove(layer)   ← 合成器里这层立刻消失（这一帧就没有了！）
      └ Surface/BLASTBufferQueue 释放
t3  relayout 返回 RELAYOUT_RES_SURFACE_CHANGED，客户端 destroySurface()
    ⚠ 从 t2 起屏上就"没有这层"了 —— 这就是肉眼看到的"消失"，不是延迟，是立即
t4  客户端 setVisibility(VISIBLE)
t5  requestLayout → traversal → relayoutWindow(VISIBLE) → shouldRelayout=true
      └ WMS: createSurfaceLocked() 新建 SurfaceControl（全新 layer，无 buffer）
t6  客户端重建 Surface/BLASTBufferQueue，performTraversals 全量重绘
t7  finishDrawing → 提交首帧 buffer → SF 下一帧合成
    ⚠ t5–t7 之间 layer 存在但无 buffer(hasSomethingToDraw=false) → 仍不可见
    ⚠ 这里还叠加一个 choreographer 周期（~16.7ms）+ 可能的 surface 分配耗时
```

**净效果：`INVISIBLE` 立刻消失在 t2；`VISIBLE` 要到 t7 才回来。中间是「无层」而不是「有层但透明」，
所以哪怕只有 1 帧的 visibility 抖动，肉眼也能看到闪。** 更糟的是若 hideRefs / 截图避让周期性触发，
每次触发都重付 t2–t7 的代价。

### 1.4 为什么这解释了「每几秒闪一次」

`hideRefs` 引用计数意味着「有任一持有者要求隐藏」就 INVISIBLE。只要有周期性逻辑（截图避让、自动隐藏、
其他模块短暂 hide/show）在跑，就每轮触发一次 surface 销毁 + 重建 → 每轮一次可见的闪。
**关键推论：闪烁频率 ≈ 隐藏逻辑的调用频率，与「绘制慢」「GPU 卡」无关。**（可据此验证：临时把
`beginCaptureAvoid` 全部 return，若闪消失，机制即确认。）

### 1.5 为什么 `removeViewImmediate + addView` 也闪

同一个 `destroySurface`：`removeViewImmediate` 走 `WindowManagerImpl.removeViewImmediate → ViewRootImpl.dispatchDetachedFromWindow`
→ 客户端 `destroySurface()` + `mWindowSession.remove()`；`addView` 重新 `addToDisplay` → `createSurfaceLocked()`。
差别只是多了一次 Binder 往返与 input channel 重注册（更贵），**闪烁成因相同**。这与你已观察到的「能截干净但丢触摸状态」
完全一致。

### 1.6 已被排除的相邻假设

- **「系统对话框 / 弹窗把非系统 overlay 顶掉」**：`WindowManagerService.java:8946`
  `updateNonSystemOverlayWindowsVisibilityIfNeeded(WindowState, boolean)` 与 `WindowState.java:3147–3156`
  `setForceHideNonSystemOverlayWindowIfNeeded` 只作用于 `isSystemAlertWindowType(baseType) || baseType == TYPE_TOAST`；
  且 `WindowManagerService.java:4362` 的 `hideNonSystemOverlayWindowsWhenVisible()` 需要 privateFlag
  `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`。→ **不会**因此隐藏 `TYPE_ACCESSIBILITY_OVERLAY`。【源码级】
- **「INVISIBLE 只是不绘制、surface 还在」**：见上，已被第 15 步直接否证。【源码级】

### 1.7 真机实证（本项目，M2012K11AC / Android 16 / HyperOS）

上述 15 步是源码推理；本项目在真机上抓到了**直接对应**的运行时证据：

在 `/screenshot` 避让期间（`beginCaptureAvoid()` 之后）连续采样 `dumpsys window windows`，该
`TYPE_ACCESSIBILITY_OVERLAY` / type=2032 窗口的 `Surface` 行**连续 10 个样本均为**：

```
Surface: shown=false mDrawState=mDrawState=NO_SURFACE
```

避让结束（`endCaptureAvoid()`）后，同一个窗口立刻回到：

```
Surface: shown=true  mDrawState=mDrawState=HAS_DRAWN
```

`mDrawState = NO_SURFACE` 正是 `WindowStateAnimator.destroySurface()` 在
`WindowStateAnimator.java:707–721` 里写的终态（`t.remove(mSurfaceControl); ... mWin.setHasSurface(false); mDrawState = NO_SURFACE;`），
`HAS_DRAWN` 则是重建 surface 并完成首帧绘制之后的状态。

→ **这不是「只是不绘制」的延迟帧，而是 surface 真的被销毁、再真的被重建**。【源码级 + 真机实证】
换句话说：每一次 `beginCaptureAvoid → endCaptureAvoid`，屏上该层的生命周期就是「被移除 → 重新创建 → 等首帧」，
肉眼看到的一闪与 `dumpsys` 的 `NO_SURFACE ↔ HAS_DRAWN` 翻转是同一件事，两者可互相印证。

**这条实证也给出了一个可量化的验收指标**：改动后再次采样，避让期间应**不再出现** `NO_SURFACE`
（例如方案 C 的 `alpha=0f` 期间仍是 `HAS_DRAWN`），且 `dumpsys SurfaceFlinger --list` 里层名不消失。

---

## 2. Q2：takeScreenshot / takeScreenshotOfWindow 的真实行为

### 2.1 两个 API 与能力声明

| API | API level | 语义 | 来源 |
|---|---|---|---|
| `takeScreenshot(int displayId, Executor, TakeScreenshotCallback)` | **30** | 整屏截图（`captureDisplay`） | 官方文档 |
| `takeScreenshotOfWindow(int accessibilityWindowId, Executor, TakeScreenshotCallback)` | **34** | 截「某个无障碍窗口」的内容，**不含盖在它上面的 overlay** | 官方文档 |
| `attachAccessibilityOverlayToWindow(int, SurfaceControl)` | **34** | 把自建 SurfaceControl 挂成某窗口之上的 overlay | 官方文档 |
| `attachAccessibilityOverlayToDisplay(int, SurfaceControl)` | **34** | 挂到 display 上 | 官方文档 |

来源：https://developer.android.com/reference/android/accessibilityservice/AccessibilityService

官方原文（takeScreenshot 段）：

> Note: In order to take screenshot your service has to declare the capability to take screenshot by setting the
> `R.styleable.AccessibilityService_canTakeScreenshot` property in its meta-data.

即服务 XML 里需要 `android:canTakeScreenshot="true"`（API 30+；`minSdk` 低的设备上该属性被忽略，运行时按 API 判断）。

官方原文（takeScreenshotOfWindow 段，**这是本项目最该读的一段**）：

> Both this method and `takeScreenshot(int, Executor, TakeScreenshotCallback)` can be used for machine learning-based
> visual screen understanding. **Use `takeScreenshotOfWindow` if your target window might be visually underneath an
> accessibility overlay (from your or another accessibility service) in order to capture the window contents without
> the screenshot being covered by the overlay contents drawn on the screen.**

→ 官方立场：**当被截对象可能被自己的无障碍悬浮窗盖住时，用 `takeScreenshotOfWindow`，而不是把悬浮窗藏起来。**
这正是「不重建窗口、不摘窗口、不闪」的官方路径。

### 2.2 333ms 限流：两个常量、两套计时器

- 客户端常量：`frameworks/base/core/java/android/accessibilityservice/AccessibilityService.java:787–788`

  ```java
  @TestApi
  public static final int ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333;
  ```

  官方文档里对应 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`，`Constant Value: 3 (0x00000003)`
  （`ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR` = 1）。

- 服务端限流：`frameworks/base/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java`
  - `:1418–1431` `takeScreenshotOfWindow`：按 `accessibilityWindowId` 存在 `SparseArray` 里，间隔 `<= 333` 直接返回
    `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`；
  - `:1470–1480` `takeScreenshot`：单一字段 `mRequestTakeScreenshotTimestampMs`，同样 333ms；
  - **两套计时器互不干扰**（按窗口 vs 按 display）→ 想高频截不同窗口，用 `takeScreenshotOfWindow` 可以每个窗口各自 333ms。
  - `:1525` 附近：`takeScreenshot` 最终 `mWindowManagerService.captureDisplay(displayId, null, screenCaptureListener)` → **整屏合成**。

源码：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/AccessibilityService.java
源码：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java

**对 120ms `Thread.sleep` 的推论**：333ms 是服务端硬限流，**但它是"两次请求之间"的下限，不是"隐藏必须持续 120ms"的依据**。
如果只是想让截图里没有悬浮窗，正确做法是让它一开始就不被合成，根本不需要 sleep。

### 2.3 截图路径 = 从「当前 layer snapshot」重新合成可见层（所以隐藏层天然不在截图里）

- `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp:8298`
  `SurfaceFlinger::getLayerSnapshotsForScreenshots(std::optional<ui::LayerStack>, uint32_t uid, std::function<bool(const frontend::LayerSnapshot&, bool&)> snapshotFilterFn)`，
  内部走 `mLayerSnapshotBuilder.forEachVisibleSnapshot([&](std::unique_ptr<frontend::LayerSnapshot>& snapshot){ ... if (!snapshot->hasSomethingToDraw()) return; ... })`。
  `captureDisplay`（`SurfaceFlinger.cpp:7167` 起）与 `captureLayers`（`:7320` 起）都走它。
- `frameworks/native/services/surfaceflinger/FrontEnd/LayerSnapshotBuilder.cpp:1219–1225`

  ```cpp
  void LayerSnapshotBuilder::forEachVisibleSnapshot(const ConstVisitor& visitor) const {
      for (...) { LayerSnapshot& snapshot = *mSnapshots[(size_t)i];
                  if (!snapshot.isVisible) continue; visitor(snapshot); }
  }
  ```

- `frameworks/native/services/surfaceflinger/FrontEnd/LayerSnapshot.cpp:211–232` `bool LayerSnapshot::getIsVisible() const`：
  `if (reachablilty != Reachable) return false;` / `if (handleSkipScreenshotFlag & outputFilter.toInternalDisplay) return false;`
  （`eLayerSkipScreenshot`，FLAG_SECURE 那类）/ `if (!hasSomethingToDraw()) return false;` /
  `if (isHiddenByPolicy()) return false;` / `return color.a > 0.0f || hasBlur();`
  → reason 串里有 `"hidden by parent or layer flag"`、`"eLayerSkipScreenshot"`、`"alpha = 0 and no blur"`、`"nothing to draw"`。
- `LayerSnapshot.cpp:205–208` `isHiddenByPolicy() = invalidTransform || isHiddenByPolicyFromParent || isHiddenByPolicyFromRelativeParent`
- `LayerSnapshotBuilder.cpp:735–737` `snapshot.isHiddenByPolicyFromParent = parentSnapshot.isHiddenByPolicyFromParent || parentSnapshot.invalidTransform || requested.isHiddenByPolicy() || (args.excludeLayerIds.find(path.id) != args.excludeLayerIds.end());`
- `RequestedLayerState.cpp:444–446` `bool RequestedLayerState::isHiddenByPolicy() const { return (flags & layer_state_t::eLayerHidden) == layer_state_t::eLayerHidden; };`
- `libs/gui/include/gui/LayerState.h:156` `eLayerHidden = 0x01,         // SURFACE_HIDDEN in SurfaceControl.java`
- 正常合成同样跳过：`CompositionEngine/src/Output.cpp:572` `if (CC_UNLIKELY(!layerFEState->isVisible)) { return; }`

源码：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/surfaceflinger/SurfaceFlinger.cpp
源码：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/surfaceflinger/FrontEnd/LayerSnapshot.cpp
源码：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/surfaceflinger/FrontEnd/LayerSnapshotBuilder.cpp
源码：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/gui/include/gui/LayerState.h

### 2.4 那为什么「截到 INVISIBLE overlay」还会发生？

因为 **view 的 INVISIBLE 与「layer 的隐藏」之间隔着一次 relayout**，而截图可能发生在过渡中间：

1. 客户端 `setVisibility(INVISIBLE)` 只是 `mViewFlags` 变了；**要等下一次 traversal 的 relayout** 才把
   `viewVisibility=INVISIBLE` 送到 WMS；
2. 在那之前，SF 仍是「有 layer、有 buffer」的状态 → 此时截图拍到旧内容（且这是**最后一帧的内容**，因为客户端不再重绘）；
3. 反过来，VISIBLE 之后 layer 虽已重建，但**没有 buffer**（`hasSomethingToDraw() == false`）→ 那段时间 layer 不进截图，
   甚至可能因为「同 layer 无新 buffer」被拍到陈旧帧。

**结论：靠 view visibility 做截图避让，窗口期本质上是不可封闭的**——它改变的是「窗口是否参与合成」，
而截图读取的是「任意时刻的 layer 状态」，两者不同步。这也解释了为什么加 `Thread.sleep(120)` 只是碰运气。

### 2.5 「缓存帧 / 陈旧帧」

- 不存在「整屏旧帧缓存」问题：截图是对当前 layer snapshot 的**重新合成**（见 §2.3），
  不是把上一帧的成品再拷一份。
- 但**单层**可能陈旧：某层没有提交新 buffer 时，其 snapshot 仍用最后入队的 buffer
  （`LayerSnapshot::getIsVisible()` 里的 `hasSomethingToDraw()` / `hasBufferOrSidebandStream()` 判据）→ 表现是
  「这个窗口的内容是旧的」，不是「整屏是旧的」。
- 官方文档里没有「stale frame」这种表述（已 grep 四份行为变更页与 AccessibilityService 页，无 `screenshot` 相关条目），
  §2.5 属**源码推理**，标记为【源码级推理】。

### 2.6 一个容易忽略的副作用：合成可见性会反向影响 input

`LayerSnapshotBuilder.cpp:258–280` 的 `updateVisibility(...)` 里：

```cpp
if (FlagManager::getInstance().skip_invisible_windows_in_input())
    snapshot.inputInfo.setInputConfig(gui::WindowInfo::InputConfig::NOT_VISIBLE, !visible);
```

否则用 `canReceiveInput` 兜底。→ **layer 被判为不可见时，SF 会给该层的 input window 打上 `NOT_VISIBLE`**，
这会与 §1 的 surface 销毁叠加，放大「层与触摸不一致」的窗口期。与本项目「有时点不到」直接相关。

---

## 3. Q3：Android 14/15/16 上的已知变更与 bug

### 3.1 官方行为变更：**没有**针对「无障碍悬浮窗合成/可见性」的变更

逐页核对（均已实际抓取正文并 grep）：

| 页面 | 与 window/overlay 相关的变更 | 是否解释本项目闪烁 |
|---|---|---|
| https://developer.android.com/about/versions/15/behavior-changes-15 | targetSdk 35 起，前台服务 `SYSTEM_ALERT_WINDOW` 豁免要求 overlay 窗口**可见** | 否（本项目是 a11y 窗，且 targetSdk 28） |
| https://developer.android.com/about/versions/16/behavior-changes-16 | edge-to-edge 强制、opt-out 取消等 | 否 |
| https://developer.android.com/about/versions/16/behavior-changes-all | 唯一 accessibility 相关：弃用 `announceForAccessibility` / `TYPE_ANNOUNCEMENT` 播报 | 否 |
| https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events | untrusted touch 拦截 + 例外（见 §4.1） | 部分（解释「点不到」，不解释闪烁） |

四页 grep 均**无** `screenshot` / `accessibility overlay` 的可见性行为条目。
→ **不能把闪烁归因于官方行为变更**；它在本项目的代码路径里（§1），或在系统侧 bug（§3.3）。

### 3.2 API 34 新增的两件事（对本项目是本条研究的最大收获）

1. `takeScreenshotOfWindow` —— 官方推荐的「不让 overlay 进截图」的方案（§2.1 原文）。
2. `attachAccessibilityOverlayToWindow` / `attachAccessibilityOverlayToDisplay` + `SurfaceControlViewHost` ——
   官方原文：

   > create a `SurfaceControlViewHost` and attach the View using `SurfaceControlViewHost.setView(View, LayoutParams)`.
   > Then obtain the `SurfaceControl` by calling `viewHost.getSurfacePackage().getSurfaceControl()`.
   > To remove this overlay and free the associated resources, use
   > `new SurfaceControl.Transaction().reparent(sc, null).apply();`
   >
   > Use `new SurfaceControl.Transaction().setLayer(sc, layer).apply();` to coordinate the order of the overlays on screen.

   → 这条路让 App **自己持有 SurfaceControl**，于是可以用 `Transaction.hide()/setVisibility()/setAlpha()`
   直接翻 layer 标志，**不销毁 surface、不重建 buffer**（§3.2 下）。
   来源：https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#attachAccessibilityOverlayToWindow(int,android.view.SurfaceControl)

### 3.3 `SurfaceControl.Transaction` 只翻标志位，不动 buffer（源码级）

`frameworks/base/core/java/android/view/SurfaceControl.java`：

- `:3224–3243` `public Transaction show(SurfaceControl sc)` → `nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SURFACE_HIDDEN);`
- `:3245–3262` `public Transaction hide(SurfaceControl sc)` → `nativeSetFlags(..., SURFACE_HIDDEN, SURFACE_HIDDEN);`
- `:3197–3212` `public Transaction setVisibility(SurfaceControl sc, boolean visible)` 只是 show/hide 的封装，
  javadoc：`Toggle the visibility of a given Layer and it's sub-tree`
- `:908` `private static final int SURFACE_HIDDEN = 0x01;`

→ 一旦拿到自己的 `SurfaceControl`，`hide()` 等价于把 `eLayerHidden` 打上（`LayerState.h:156`），
SF 侧 `isHiddenByPolicy()` 为真 → 该层**不进合成、也不进截图**（§2.3），而 **buffer 与 layer 都保留**，
去掉标志后下一帧就回来 → **零重建、零闪烁**。

源码：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceControl.java

### 3.4 社区已知 bug：Android 16.1 上 a11y overlay「随机不可见」（与 App 无关）

https://github.com/keymapperorg/KeyMapper/issues/1933 —— 标题
「Floating buttons stop working randomly after a while on Android 16.1」，正文原文：

> All `ACCESSIBILITY_OVERLAY`s are invisible randomly after a while. There is nothing in logcat or dumpsys window to
> suggest why. The "accessibility menu" service built-in to AOSP shows the same symptoms.

设备 Pixel 7 / Android 16.1 / app 4.0.0 Beta；报于 2025-12-19；作者 2025-12-27 自评
「I haven't seen this for a week now」，随后以 not planned 关闭。

可信度【社区】。价值在于：**Android 16 上确实存在与具体 App 无关的 a11y overlay 图层可见性异常**
（连 AOSP 自带 accessibility menu 都中招），且 logcat/dumpsys 无痕。→ 若本项目的闪烁在
「完全禁用截图避让与 hideRefs」后仍存在，则要考虑这条系统侧路径；否则不要用它解释。

### 3.5 未验证线索（**不作为论据**）

- https://issuetracker.google.com/issues/378566922 —— 抓取返回 200 但正文只有 `Google Issue Tracker Sign in`
  （纯 JS / 登录墙），**内容未验证**。一处 StackOverflow 修订页称「已上报，Google 正在处理」，但这只是二手转述，
  本报告不据此下结论。
- https://issuetracker.google.com/issues/35819848 、`/35824183` —— 仅在搜索结果摘要里出现，未抓取，**未验证**。
- https://developer.huawei.com/consumer/cn/forum/topic/0202108637941208003 —— 抓取只得到标题
  「华为开发者问答 | 华为开发者联盟」，正文 JS 渲染，**未验证**（搜索摘要称其建议用
  `TYPE_ACCESSIBILITY_OVERLAY` 替代 `TYPE_APPLICATION_OVERLAY`，但原文没抓到，禁止引用）。

### 3.6 把 §3 映射回本项目

两条互斥的解释路径，**必须用实验分开**：

| 路径 | 特征 | 判别实验 |
|---|---|---|
| A. 本项目 view visibility → surface 销毁 | 闪烁**与 hideRefs / 截图避让调用严格同步**；`dumpsys SurfaceFlinger --list` 里该层名会消失再出现 | 注释掉 `beginCaptureAvoid/endCaptureAvoid` 与所有 hide 调用，若闪消失 → 路径 A |
| B. 系统侧 a11y overlay 可见性异常（Android 16） | 闪烁**与代码调用无关**，随机；logcat / dumpsys 无痕 | 保持代码不变，静置观察；若闪仍在 → 路径 B（只能规避，如改为自持 SurfaceControl 或降级 targetSdk 无意义） |

---

## 4. Q4：「悬浮窗有时点不到」的已知成因

### 4.1 官方规定：**根 view 为 INVISIBLE/GONE 的窗口，触摸会被放行穿透**

https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events 原文（例外清单）：

> **Invisible windows.** The window's root view is `GONE` or `INVISIBLE`.
> **Completely transparent windows.** The `alpha` property is `0.0` for the window.

以及 trusted window 清单原文：

> **Trusted windows.** These windows include (but aren't limited to) the following:
> Accessibility windows / Input method editor (IME) windows / Assistant windows
> Note: Windows of type `TYPE_APPLICATION_OVERLAY` aren't trusted.

→ 两条结论：
1. `TYPE_ACCESSIBILITY_OVERLAY` 属于 trusted window，**不会被 untrusted-touch 拦截**（排除「targetSdk 28 被当不可信窗口」的假设）；
2. 但**切到 INVISIBLE 期间，官方明确允许触摸穿透到下层 App**。所以「闪一下的同时点不到 / 点穿到下面」
   是官方定义的行为，不是 bug —— 只要还用 view visibility 做隐藏，这个症状就必然存在。

### 4.2 源码：`TYPE_ACCESSIBILITY_OVERLAY` 在 trusted overlay 白名单里

`frameworks/base/services/core/java/com/android/server/wm/InputMonitor.java:722–734`：

```java
static boolean isTrustedOverlay(int type) {
    return type == TYPE_ACCESSIBILITY_OVERLAY
        || type == TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY
        || type == TYPE_INPUT_METHOD
        || type == TYPE_STATUS_BAR
        || type == TYPE_NOTIFICATION_SHADE
        || type == TYPE_NAVIGATION_BAR
        || type == TYPE_VOICE_INTERACTION
        || ...;
}
```

（白名单还含其它系统窗类型；native 侧同样维护一份：AOSP 提交
「Add TYPE_MAGNIFICATION_OVERLAY to isTrustedOverlay」
https://android.googlesource.com/platform/frameworks/native/+/51eb29fa9a2ffe9c5adb9ea25bfbd51992ff16a4%5E%21/services
—— 该提交只证明了机制存在与白名单会被维护，**没逐行读过 native 版**，标【社区/索引级】。）

源码：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/InputMonitor.java

### 4.3 源码：input window 表是**异步**刷新的（这是「点不到」的时间窗口来源）

- `InputMonitor.java:149` `mHandler = mService.mAnimationHandler;`
- `InputMonitor.java:333–348` `updateInputWindowsLw(force)` → `scheduleUpdateInputWindows()` → `mHandler.post(mUpdateInputWindows)`
- `accept()` 里：`if (w.mInputChannelToken == null || w.mRemoved || !w.canReceiveTouchInput())` →
  用 `populateOverlayInputInfo(...)` 把该窗口降级成「只遮挡、不收 input」（`setDispatchingTimeoutMillis(0)`、`setFocusable(false)`），
  否则才 `populateInputWindowHandle(...)`。

→ surface 销毁/重建导致 `mInputChannelToken` 语义变化时，input 表要等下一个 animation handler 周期才更新。
这中间「层已经没了但 input handle 还在」或「层回来了但 input 还没注册」——
触摸要么被旧 handle 吃掉（表现为点了没反应），要么穿透到下层（表现为点错东西）。

### 4.4 已被排除的假设（**别再查了**）

| 假设 | 排除依据 | 可信度 |
|---|---|---|
| `FLAG_NOT_TOUCHABLE` 残留 | `WindowState.java:3016` `canReceiveTouchInput()` 对无 ActivityRecord 的 overlay 基本恒 true；本项目从不改 flags | 【源码级】 |
| 系统对话框 / 弹窗把非系统 overlay 强制隐藏 | 见 §1.6：`WMS:8946` / `WindowState:3147` 只覆盖 system alert / toast | 【源码级】 |
| untrusted touch（Android 12）拦截 a11y 窗 | §4.1 trusted window + §4.2 isTrustedOverlay | 【官方文档+源码级】 |
| targetSdk=28 导致窗口被当「不可信」 | untrusted touch 判定在 native/WMS 侧按窗口类型，与 targetSdk 无关（§5） | 【推理，未找到直接文档】 |
| 触摸被系统的「触摸探索（TalkBack）」抢走 | 未找到证据，且症状是「有时」而非「总是」→ 低优先级 | 【未验证】 |

### 4.5 HyperOS 专有路径：**force-stop 会剥离无障碍权限**

https://github.com/chkndrp/hyperos-accessibility-fix 的 README 原文：

> On Android, when an accessibility app is force-stopped via `ActivityManager`, **the accessibility permission of the
> app is stripped.**

并引 AOSP `AccessibilityManagerService`/`AccessibilityUserState` 的
`boolean onPackagesForceStoppedLocked(String[] packages, AccessibilityUserState userState)`：
把该包组件从 `mEnabledServices` 移除，并 `persistComponentNamesToSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ...)`。

以及 HyperOS 的行为原文：

> **HyperOS is hardcoded to trigger a force-stop (not a kill)** on certain events like when the user is entering
> Ultra Battery Saver (`LockScreenSave`/`LockScreenClean`), and others:

```
I ActivityManager: Force stopping com.urbandroid.lux appid=10415 user=0: LockScreenClean
D ActivityManager: Force removing proc 8855:com.urbandroid.lux:background/u0a415 ...
```

→ 在 HyperOS 上，「无障碍服务整体被杀 → 悬浮窗消失 → 用户重新进入设置/系统重绑 → 又出现」是一条真实存在的路径。
判据：`logcat | grep -iE "Force stopping|accessibility.*(disable|unbind)"`，以及
`settings get secure enabled_accessibility_services` 是否丢了你的组件。

同类社区工具：https://github.com/sgsongx/androidAccServerManage（用 Shizuku 管理无障碍权限，README 明说
「小米 HyperOS 等系统会因安全扫描自动关闭 RustDesk、Tasker 等应用的无障碍服务」）——【社区】。

### 4.6 关于 M2012K11AC / HyperOS 3 的诚实结论

- **没有抓到**任何 MIUI/HyperOS 官方文档或 issue，专门描述「a11y overlay 周期性闪烁」或「a11y overlay 触摸失效」。
- 能站得住的两条是：① HyperOS 的 force-stop 硬化（§4.5）；
  ② Android 16 系统侧 a11y overlay 可见性异常（§3.4，Pixel 设备上的证据，是否复现于 HyperOS 未知）。
- 结论：**不能把「HyperOS 特有 bug」当既定事实写进结论**，只能作为待验证假设，且必须先用 logcat 排除。

---

## 5. Q5：targetSdk=28 在 Android 16 上的窗口/兼容性差异

| 维度 | 结论 | 依据 |
|---|---|---|
| untrusted touch | **不按 targetSdk 豁免**，判定在系统侧（窗口类型 + 遮挡）；且 a11y 窗本身 trusted | §4.1 官方文档 + §4.2 源码 |
| 部分遮挡（partially obscuring） | 同上：a11y 窗在 trusted 白名单，不会因遮挡被拦 | 同上 |
| 新 API 可用性 | **与 targetSdk 无关**，只看 `compileSdk` 与运行时 API level。compileSdk=36，故 `takeScreenshotOfWindow` / `attachAccessibilityOverlayTo*` 可编译，运行时判 `Build.VERSION.SDK_INT >= 34` | 通用 Android 规则 |
| compat mode | targetSdk 28 会进入一批 legacy 行为（如 30 起的 intent 可见性、31 起的 PendingIntent 可变性默认、34 起的 receiver flag 要求），**这些都不涉及窗口合成/可见性** | 官方行为变更页（未逐条 grep 窗口相关，标【推理】） |
| 333ms 限流 | 与 targetSdk 无关 | §2.2 |
| 截图能力 | `canTakeScreenshot` 在 API 30+ 生效；targetSdk 28 不影响 | §2.1 |

**未验证项**（不要在文档/代码注释里当事实）：是否存在「targetSdk < 30 走 legacy surface/relayout 路径」导致
`INVISIBLE` 的销毁时机不同 —— 未找到任何文档或源码分支说明此事（`WindowManagerService` 的 relayout 逻辑里没有
targetSdk 分支）。标【未验证】。

---

## 后续实现

研究阶段的 alpha 隐藏试验和实施建议已被后续窗口合成截图方案替代，删除原建议，避免再次作为落地指令。
当前截图路径和回退约束见 `docs/OVERLAY.md`，本记录保留前面的系统源码证据与参考来源。

## 8. 参考清单（按可信度分档）

### 8.1 AOSP 源码（已逐行阅读，最强）

| 文件 | 路径 | 关键行 |
|---|---|---|
| ViewRootImpl.java | `frameworks/base/core/java/android/view/ViewRootImpl.java` | 3488, 3498–3504, 3578–3580, 3593, 3771, 3858–3864, 9480–9527, 2844–2860 |
| WindowManagerService.java | `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java` | 2489, 2505–2513, 2517, 2529, 2726, 2734, 2764–2769, 4362, 8946 |
| WindowState.java | `.../wm/WindowState.java` | 1788–1800, 1853–1859, 3016, 3147–3156, 3355, 3403–3406 |
| WindowStateAnimator.java | `.../wm/WindowStateAnimator.java` | 707–721 |
| InputMonitor.java | `.../wm/InputMonitor.java` | 149, 333–348, 722–734 |
| AccessibilityService.java | `frameworks/base/core/java/android/accessibilityservice/AccessibilityService.java` | 787–788, 2738–2770 |
| AbstractAccessibilityServiceConnection.java | `frameworks/base/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java` | 1418–1431, 1470–1480, 1525 |
| SurfaceControl.java | `frameworks/base/core/java/android/view/SurfaceControl.java` | 908, 3197–3212, 3224–3243, 3245–3262 |
| SurfaceFlinger.cpp | `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` | 7167, 7320, 8298 |
| LayerSnapshotBuilder.cpp | `.../surfaceflinger/FrontEnd/LayerSnapshotBuilder.cpp` | 258–280, 735–737, 1219–1225 |
| LayerSnapshot.cpp | `.../surfaceflinger/FrontEnd/LayerSnapshot.cpp` | 205–208, 211–232 |
| RequestedLayerState.cpp | `.../surfaceflinger/FrontEnd/RequestedLayerState.cpp` | 444–446 |
| LayerState.h | `frameworks/native/libs/gui/include/gui/LayerState.h` | 156 |
| Output.cpp | `.../surfaceflinger/CompositionEngine/src/Output.cpp` | 572 |

统一访问前缀：`https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/`（Java）与
`https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/`（C++）。

### 8.2 官方文档（已抓取正文）

- AccessibilityService 参考（`takeScreenshot` API 30 / `takeScreenshotOfWindow` API 34 / `attachAccessibilityOverlayTo*` API 34）
  https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- WindowManager.LayoutParams（`TYPE_ACCESSIBILITY_OVERLAY` 定义）
  https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- Android 12 行为变更 · untrusted touch events（信任窗口 / 例外清单）
  https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events
- Android 15 行为变更 https://developer.android.com/about/versions/15/behavior-changes-15
- Android 16 行为变更（针对性） https://developer.android.com/about/versions/16/behavior-changes-16
- Android 16 行为变更（全部应用） https://developer.android.com/about/versions/16/behavior-changes-all

### 8.3 社区 / 厂商（已抓取正文）

- KeyMapper #1933：Android 16.1 上 a11y overlay 随机不可见，AOSP accessibility menu 同样
  https://github.com/keymapperorg/KeyMapper/issues/1933
- hyperos-accessibility-fix：HyperOS force-stop 剥离无障碍权限（含 AOSP 方法名与 logcat 实例）
  https://github.com/chkndrp/hyperos-accessibility-fix
- androidAccServerManage：HyperOS 安全扫描自动关闭无障碍服务（Shizuku 管理）
  https://github.com/sgsongx/androidAccServerManage
- AOSP 提交：Add TYPE_MAGNIFICATION_OVERLAY to isTrustedOverlay（native 侧白名单存在性）
  https://android.googlesource.com/platform/frameworks/native/+/51eb29fa9a2ffe9c5adb9ea25bfbd51992ff16a4%5E%21/services

### 8.4 未验证（链接可达但内容抓不到，**不构成论据**）

- https://issuetracker.google.com/issues/378566922 （登录墙/JS）
- https://developer.huawei.com/consumer/cn/forum/topic/0202108637941208003 （JS 渲染）
- https://issuetracker.google.com/issues/35819848 、 https://issuetracker.google.com/issues/35824183 （未抓取）

---
