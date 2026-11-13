# `AccessibilityService.takeScreenshotOfWindow` 语义研究（API 34+）

> 历史取证记录：结论仅适用于文中设备、版本和采样范围。当前行为与维护约束见相关使用文档；旧行号和临时文件路径不代表当前工作区。

> 目的：把 `takeScreenshotOfWindow` 的行为钉死在**源码 + CTS + 官方文档**三档证据上，供
> `GET /screenshot` 从「先藏悬浮窗再截整屏」迁移到「截目标窗口、悬浮窗保持可见」的方案做决策。
>
> 相关文档：闪烁与避让的根因见 [`flicker-research.md`](./flicker-research.md)（尤其是 §2、
> §3.2、§6）。本文不重复那篇的结论，只补 `takeScreenshotOfWindow` 的**逐行语义**。
>
> 取证基线：AOSP `refs/heads/main`（2026-11 拉取），并用 `refs/tags/android-14.0.0_r1` /
> `android-15.0.0_r1` / `android-16.0.0_r1` 做跨版本比对。源码统一前缀：
>
> - Java：`https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/`
> - C++：`https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/`
> - CTS：`https://android.googlesource.com/platform/cts/+/refs/heads/main/`
>
> 文中 `文件:行号` 均为上述基线下的行号（行号会随 main 漂移，方法名/常量名不会）。

---

## 0. TL;DR（5 条）

1. **签名只有三参**：`takeScreenshotOfWindow(int accessibilityWindowId, Executor, TakeScreenshotCallback)`
   ——**没有 displayId**。`takeScreenshot(int displayId, Executor, TakeScreenshotCallback)` 才是带 display 的版本
   （API 30；单窗口版 API 34）。见 `AccessibilityService.java:2761`。
   > lead 实测佐证：按四参写会被 Kotlin 编译器拒绝，报
   > `Too many arguments for public open fun takeScreenshotOfWindow(p0: Int, p1: Executor, p2: AccessibilityService.TakeScreenshotCallback)`。
2. **截的是「目标窗口自己的 layer 子树」，不是屏幕可见区域**。截图指令被送到**目标窗口所在进程**的
   `ViewRootImpl`，以该窗口的 `SurfaceControl` 为 root 调 `ScreenCapture.captureLayers(...)`
   （`AccessibilityInteractionController.java:613-633`）。→ **其它窗口（含任何 `TYPE_ACCESSIBILITY_OVERLAY`）
   天然不在这棵子树里**；即便在，也会被 `setUid(Process.myUid())` 的 uid 过滤丢掉。
3. **输出 Bitmap 是窗口本地坐标**：buffer 宽高 = 该窗口 layer 的 cropped buffer size，
   原点 = 该 layer 的 (0,0)。CTS 直接断言 `hardwareBuffer.getWidth()/getHeight()` 等于
   `activity.getWindow().getDecorView().getWidth()/getHeight()`
   （`AccessibilityTakeScreenshotTest.java:252-275, 416-428`）。要贴回全屏必须自己加
   `AccessibilityWindowInfo.getBoundsInScreen()` 的偏移。
4. **与整屏版各有一套 333ms 计时器，互不消耗**：窗口版按 `windowId` 存在
   `SparseArray<Long> mRequestTakeScreenshotOfWindowTimestampMs`（`acc.java:250, 1416-1427`），
   整屏版是单个字段（`acc.java:1467`）。CTS `testTakeScreenshotOfWindow_MultipleWindowsIntervalTime`
   用「同批多个不同窗口全部成功、166ms 内第二批全部 `INTERVAL_TIME_SHORT`」把这件事测死了。
5. **它不要求悬浮窗隐藏**，官方 javadoc 明说这正是它的用途：目标窗口「被 accessibility overlay 盖住」时
   仍要拿到窗口内容、且不被 overlay 污染。→ 本项目「截图时把 2032 窗口设 INVISIBLE」这一步可以整段删掉。

---

## 1. Q1：它到底截什么？包含别的 AccessibilityOverlay(2032) 吗？

### 1.1 结论（先给答案）

| 问题 | 结论 | 证据强度 |
|---|---|---|
| 截「窗口内容」还是「屏幕可见区域」 | **窗口自己的 layer 子树**（含该窗口内同进程子层，如 `SurfaceView`），**不含**其它窗口 | AOSP 源码（逐行）+ CTS |
| 是否含其它 `TYPE_ACCESSIBILITY_OVERLAY`(2032) | **不含** | AOSP 源码三重证据 |
| 是否含本服务自己挂的 2032 窗口 | 不含（除非你把该 overlay 窗口本身当作 target） | 源码推断 + 语义 |
| 目标窗口被 overlay 遮挡时还能截到吗 | 能，且截到的是窗口内容（这正是该 API 的设计目的） | 官方 javadoc + 源码 |

### 1.2 完整调用链（service → WMS → 目标 app 进程 → SurfaceFlinger → service）

```
AccessibilityService.takeScreenshotOfWindow(windowId, executor, cb)     // AccessibilityService.java:2761
  └→ AccessibilityInteractionClient.takeScreenshotOfWindow(...)        // AccessibilityInteractionClient.java:814
       ├─ 建 ScreenCapture.ScreenCaptureListener（直接用 SF 的结果，少一跳 IPC）
       │    注释原文：A11yService -> App -> SurfaceFlinger -> A11yService
       ├─ connection.takeScreenshotOfWindow(...)                       // Binder 调用
       └─ 5s 超时兜底 → ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR           // TIMEOUT_INTERACTION_MILLIS = 5000（:114）

AbstractAccessibilityServiceConnection.takeScreenshotOfWindow(...)     // acc.java:1416
  ├─ 333ms / windowId 限流（SparseArray，:250/:1421-1427）
  ├─ hasRightsToCurrentUserLocked()            → 失败 INTERNAL_ERROR
  ├─ canTakeScreenshotLocked()                 → 失败 NO_ACCESSIBILITY_ACCESS   // 查 CAPABILITY_CAN_TAKE_SCREENSHOT
  ├─ checkAccessibilityAccess()                → 失败 NO_ACCESSIBILITY_ACCESS
  ├─ resolveAccessibilityWindowIdLocked(id)    // :2157，ACTIVE_WINDOW_ID → 当前 focus
  ├─ mA11yWindowManager.getConnectionLocked(userId, windowId)  // AccessibilityWindowManager.java:1425
  │     == null → ERROR_TAKE_SCREENSHOT_INVALID_WINDOW
  └─ connection.getRemote().takeScreenshotOfWindow(interactionId, listener, cb)
        └→ 【目标窗口所在进程】ViewRootImpl 的内部 connection 实现  // ViewRootImpl.java:12199
             └→ AccessibilityInteractionController.takeScreenshotOfWindowClientThread / ...UiThread
                （:598-633，投递到目标进程的 mHandler 异步执行）
                  ├─ FLAG_SECURE → ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                  └─ ScreenCapture.LayerCaptureArgs.Builder(mViewRootImpl.getSurfaceControl())
                        .setChildrenOnly(false).setUid(Process.myUid()).build()
                     → ScreenCapture.captureLayers(...)                  // ScreenCapture.java:175
                          └→ SurfaceFlinger::captureLayers(...)          // SurfaceFlinger.cpp:7230
```

### 1.3 关键源码（逐行）

`core/java/android/view/AccessibilityInteractionController.java:598-633`（**核心 25 行**）：

```java
/**
 * Take a screenshot using {@link ScreenCapture} of this {@link ViewRootImpl}'s {@link SurfaceControl}.
 */
public void takeScreenshotOfWindowClientThread(int interactionId,
        ScreenCapture.ScreenCaptureListener listener,
        IAccessibilityInteractionConnectionCallback callback) {
    Message message = PooledLambda.obtainMessage(
            AccessibilityInteractionController::takeScreenshotOfWindowUiThread,
            this, interactionId, listener, callback);
    // Screenshot results are returned to the service asynchronously, so the same-thread
    // message wait logic from #scheduleMessage() is not needed.
    mHandler.sendMessage(message);
}

private void takeScreenshotOfWindowUiThread(int interactionId,
        ScreenCapture.ScreenCaptureListener listener,
        IAccessibilityInteractionConnectionCallback callback) {
    try {
        if ((mViewRootImpl.getWindowFlags() & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            callback.sendTakeScreenshotOfWindowError(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW, interactionId);
            return;
        }
        final ScreenCapture.LayerCaptureArgs captureArgs =
                new ScreenCapture.LayerCaptureArgs.Builder(mViewRootImpl.getSurfaceControl())
                        .setChildrenOnly(false).setUid(Process.myUid()).build();
        if (ScreenCapture.captureLayers(captureArgs, listener) != 0) {
            callback.sendTakeScreenshotOfWindowError(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, interactionId);
        }
    } catch (RemoteException re) {
        /* ignore - the other side will time out */
    }
}
```

三个参数各自的作用（都来自 `core/java/android/window/ScreenCapture.java`）：

| 参数 | 源码位置 | 语义（javadoc / 默认值） |
|---|---|---|
| root layer = `mViewRootImpl.getSurfaceControl()` | `ScreenCapture.java:602-660` 的 `LayerCaptureArgs` | 只截这个 layer 及其子树；**兄弟窗口（含 2032 overlay）不在这棵树里** |
| `.setChildrenOnly(false)` | `ScreenCapture.java:660` | javadoc：`Whether to include the layer itself in the screenshot or just the children and their descendants.`；Builder 默认是 `true`，这里显式设 `false` → 窗口根层本身也入图 |
| `.setUid(Process.myUid())` | `ScreenCapture.java:440` | javadoc 原文：`Set the uid of the content that should be screenshot. The code will skip any surfaces that don't belong to the specified uid.` |

### 1.4 「不含其它 2032」的三重源码证据

**证据 ①：只遍历 root 的子树。** `SurfaceFlinger.cpp:8380`
`SurfaceFlinger::getLayerSnapshotsForScreenshots(rootLayerId, uid, excludeLayerIds, childrenOnly, parentCrop)`
内部 `auto root = mLayerHierarchyBuilder.getPartialHierarchy(rootLayerId, childrenOnly);` ——
遍历范围是「root layer 的子树」，不是整屏所有 layer。overlay 是**平级窗口**（另一个 `ViewRootImpl`
的另一个 SurfaceControl），不在子树内。

**证据 ②：uid 过滤。** `SurfaceFlinger.cpp:8298-8316`（`captureLayers` 走的重载），
遍历时：

```cpp
if (layerStack && snapshot->outputFilter.layerStack != *layerStack) return;
if (uid != CaptureArgs::UNSET_UID && snapshot->uid != gui::Uid(uid)) return;
if (!snapshot->hasSomethingToDraw()) return;
```

截图进程是**目标 app**，`Process.myUid()` 是目标 app 的 uid；我们的 overlay 属于
`com.termux` 的 uid → 即使它出现在同一 layer stack，也会被这一行丢掉。

**证据 ③：输出区域只按该 layer 的 buffer 算。** `SurfaceFlinger.cpp:7268-7278`

```cpp
Rect parentSourceBounds = parent->getCroppedBufferSize(parent->getDrawingState());
if (crop.width() <= 0)  { crop.left = 0; crop.right  = parentSourceBounds.getWidth();  }
if (crop.height() <= 0) { crop.top  = 0; crop.bottom = parentSourceBounds.getHeight(); }
```

`reqSize = crop.size`（`:7296`），`LayerRenderArea` 的 `getSourceCrop()` 无 crop 时返回
`mLayerBufferSize`（`services/surfaceflinger/LayerRenderArea.cpp`）→ 画布就是「该窗口的 buffer 大小」。

> 结论：**截目标应用窗口时，本项目的 2032 状态球不会出现在图里**——不是「画在上面」，而是
> 既不在子树里、也会被 uid 过滤掉。

### 1.5 一个边界：`attachAccessibilityOverlayToWindow` 挂出来的 overlay 会不会入库？

API 34 的 `AccessibilityService.attachAccessibilityOverlayToWindow(windowId, SurfaceControl)`
是把 overlay 的 layer **reparent 成目标窗口 layer 的子层**（官方文档："attach ... to the window"，
移除时 `new SurfaceControl.Transaction().reparent(sc, null).apply()`）。
按 §1.4 的 ①：它在子树里了；但按 ②：它的 uid 是**无障碍服务的 uid**，而截图是目标 app 用
`setUid(自己 uid)` 跑的 → 仍被过滤。

→ **源码推断（未跑 CTS/真机）**：即使用 attach 方式挂 overlay，截目标窗口时也不会入图。
这条如果后续要依赖，必须自己写个 instrumented test 验一遍（见 §6.3 的验证清单）。

### 1.6 反向场景：把「自己的 2032 窗口」当 target 会怎样？

会截到**你自己 overlay 的内容**，而不是屏幕内容。原因同上：root 换成你的 overlay 窗口的
SurfaceControl，uid 也是你自己的，两者都匹配。所以「用 takeScreenshotOfWindow 截自己的悬浮窗」是
合法但无意义的调用；`GET /screenshot` 必须传**别人**的窗口 id。

---

## 2. Q2：与 `takeScreenshot(displayId)` 的区别 + 333ms 是否共用

### 2.1 对照表

| 维度 | `takeScreenshot(int displayId, ...)` | `takeScreenshotOfWindow(int windowId, ...)` |
|---|---|---|
| API level | 30（`AccessibilityService.java:2705`） | 34（`AccessibilityService.java:2761`） |
| 参数里的 display | 有，且 javadoc 写 `must be Display.DEFAULT_DISPLAY` | **没有**（window id 自带 display 归属） |
| 截图执行者 | system_server：`WMS.captureDisplay(...)` → `SurfaceFlinger::captureDisplay` | **目标窗口所在进程**：`ViewRootImpl` → `ScreenCapture.captureLayers` → `SurfaceFlinger::captureLayers` |
| 输出尺寸 | 显示区尺寸（CTS：`mDisplaySize.x × y`） | 目标窗口 buffer 尺寸（CTS：decorView 宽高） |
| 是否含 overlay / 其它窗口 | 含（所有 visible layer 的合成结果） | **不含**（单 window 子树 + uid 过滤） |
| 坐标原点 | 屏幕左上 (0,0) | 目标窗口左上 (0,0) |
| 需要窗口有 accessibility connection | 否 | **是**（`getConnectionLocked == null` → `INVALID_WINDOW`） |
| FLAG_SECURE 窗口 | 该窗口内容不入图（secure layer 被跳过），整屏仍返回 | 直接失败 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` |
| 限流 | 单字段，**整个 display**、按 connection 记 | `SparseArray`，**按 windowId 分桶** |

### 2.2 两套计时器（源码 + CTS 双向确认）

服务端两个实现分别在 `acc.java:1416`（window）与 `acc.java:1467`（display）：

```java
// window 版：按 windowId 分桶（字段在 :250）
private SparseArray<Long> mRequestTakeScreenshotOfWindowTimestampMs = new SparseArray<>();
if ((currentTimestamp
        - mRequestTakeScreenshotOfWindowTimestampMs.get(accessibilityWindowId, 0L))
        <= ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS) { /* INTERVAL_TIME_SHORT */ }
mRequestTakeScreenshotOfWindowTimestampMs.put(accessibilityWindowId, currentTimestamp);
```

display 版是**单个字段** `mRequestTakeScreenshotTimestampMs`，两个分支都不读对方的字段 →
**互不消耗**。常量仍共用 `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333`
（`AccessibilityService.java:788`，`@TestApi`）。

行为侧证据（比源码更硬，因为它测的是真实 IPC 行为），
`cts/tests/accessibilityservice/src/android/accessibilityservice/cts/AccessibilityTakeScreenshotTest.java:317-343`：

```java
// 同一批：对 getWindows() 里所有窗口各发一次 → 全部 onSuccess
for (TakeScreenshotCallback cb : takeScreenshotsOfWindows(accessibilityWindowIds)) {
    verify(cb, timeout(...)).onSuccess(Mockito.any());
}
// 睡 166ms（半个间隔）后再发一批 → 全部 INTERVAL_TIME_SHORT
Thread.sleep(halfInterval);
... onFailure(ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
// 再睡 167ms → 又全部成功
```

→ ① 不同 windowId 之间**不互相限流**；② 同一个 windowId 的间隔是 333ms；
③ 这个 333ms 与 `takeScreenshot(display)` 那套无关（否则第一批多窗口循环里早就互相踩了）。

### 2.3 对本项目的直接含义

- 连续截多个窗口（例如合成分屏两块）**不会**因为 333ms 互相失败；但**同一个** target 窗口
  连续截必须 ≥ 333ms 间隔，否则 `INTERVAL_TIME_SHORT`。
- 整屏 `takeScreenshot` 与窗口截图可以交替调用、互不节流，但**不要**指望它们时间对齐
  （两次调用之间会有真实时间差，图内容可能不同帧）。

---

## 3. Q3：失败码全集（含「窗口不存在 / 不可见 / 属于自己」）

### 3.1 码表

常量定义在 `core/java/android/accessibilityservice/AccessibilityService.java:747-790`，
`@IntDef` 列表（`:733-742`）只含 1–5；**6 也在公开 API 里但没进 IntDef**（文档页上有这一项）。

| 值 | 常量 | 触发点（源码） | 典型场景 |
|---|---|---|---|
| 0 | `TAKE_SCREENSHOT_SUCCESS` | `sendWindowScreenshotSuccess`（`AccessibilityInteractionClient.java:1354`） | 成功 |
| 1 | `ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR` | ① 客户端拿不到 connection（AIC `:820-824`）；② 目标进程 `ViewRootImpl`/`mView` 已空（`ViewRootImpl.java:12207-12214`）；③ SF `captureLayers` 返回非 0（AIController `:625-628`）；④ **5s 超时**（AIC `:857-865`，`TIMEOUT_INTERACTION_MILLIS = 5000`）；⑤ `hasRightsToCurrentUserLocked()` 失败（acc `:1429-1434`） | 目标进程刚死/刚冻结、SF 侧 layer 已移除、IPC 卡死 |
| 2 | `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS` | `canTakeScreenshotLocked()` 失败（`AccessibilitySecurityPolicy.java:392-396`，查 `CAPABILITY_CAN_TAKE_SCREENSHOT`）；或 `checkAccessibilityAccess()` 失败（AppOps `OPSTR_ACCESS_ACCESSIBILITY`） | 未声明 `android:canTakeScreenshot="true"`；服务被禁用/受限 |
| 3 | `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` | acc `:1421-1424`，**同一 windowId** 距上次 ≤333ms | 连续两次截同一个窗口 |
| 4 | `ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY` | **只有 `takeScreenshot(displayId)` 路径**会产生；window 版源码里没有这条分支 | displayId 非法 |
| 5 | `ERROR_TAKE_SCREENSHOT_INVALID_WINDOW` | `getConnectionLocked(...) == null`（acc `:1447-1450`）；CTS：`takeScreenshotOfWindow(-1, ...)` | id 不存在 / 窗口已销毁 / 该窗口从未注册 accessibility connection |
| 6 | `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` | `(mViewRootImpl.getWindowFlags() & FLAG_SECURE) != 0`（AIController `:617-620`） | 目标窗口是安全窗口（银行/密码框等） |

CTS 对应关系：`testTakeScreenshotOfWindow_InvalidWindow`（`:346-352`）→ 5；
`testTakeScreenshotOfWindow_ErrorForSecureWindow`（`:279-311`，**标了 `@FlakyTest`**）→ 6；
`testTakeScreenshotOfWindow_ErrorForServiceWithoutScreenshotCapability`（`:355-372`）→ 2。

### 3.2 三个模糊场景的诚实回答

| 场景 | 返回什么 | 依据 |
|---|---|---|
| 目标窗口**不存在**（id 是旧的 / 已 removeView） | `INVALID_WINDOW`(5)（连接已摘）；若进程刚死但连接还在，可能落到 `INTERNAL_ERROR`(1) | acc `:1447`、AIC `:820` |
| 目标窗口**存在但不可见**（GONE / 被完全遮挡 / 在后台） | **源码没有显式可见性检查**：只要 connection 还在，就照常去 `captureLayers`。被遮挡时是设计内可用；GONE 时大概率返回「最后一帧 buffer」或空图（`CaptureFill::CLEAR`），**未验证** | AIController 全文无 visible 判断；SF `LayerRenderArea` 用 `CLEAR` 填充 |
| 目标窗口**属于自己**（同 uid，如自家 overlay/Activity） | 会成功，截到的是自家窗口内容；源码里 `getConnectionLocked` **没有任何 owner/uid 校验** | `AccessibilityWindowManager.java:1425-1442` |

> 第 2 行是最需要真机确认的一条：如果「后台窗口」会返回全透明图，调用方必须自己判空/判 alpha，
> 不能把「成功但空白」当成有效截图。

---

## 4. Q4：windowId 怎么取 + 多窗口/分屏/PiP/自家窗口

### 4.1 `getWindows()` 的官方语义（这决定你能选到什么）

`AccessibilityService.java:1051-1075` javadoc 原文要点：

- 只返回「**视力正常用户可交互**」的窗口（被模态窗口完全挡住的下层窗口不返回）；
- **顺序 = layer 降序，最上层在前**；
- 「focused window is always returned (even if covered by a modal window)」；
- 前置条件：meta-data `canRetrieveWindowContent` + `AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS`，
  否则 `getWindows()` 返回空列表（`AccessibilityServiceInfo.java:303-321` javadoc 原文：
  「If this flag is not set ... calling AccessibilityService.getWindows() will return an empty list」）。

### 4.2 类型对照（`AccessibilityWindowInfo` vs `LayoutParams.type`）

`core/java/android/view/accessibility/AccessibilityWindowInfo.java`：

| 常量 | 值 | 行号 | 对应 LayoutParams type |
|---|---|---|---|
| `TYPE_APPLICATION` | 1 | `:58` | 普通应用窗口（含 PiP 里的应用窗口） |
| `TYPE_INPUT_METHOD` | 2 | `:64` | 输入法 |
| `TYPE_SYSTEM` | 3 | `:70` | 状态栏/导航栏/`TYPE_APPLICATION_OVERLAY`(2038) 等 |
| `TYPE_ACCESSIBILITY_OVERLAY` | 4 | `:83` | **`TYPE_ACCESSIBILITY_OVERLAY`(2032)** |
| `TYPE_SPLIT_SCREEN_DIVIDER` | 5 | `:89` | 分屏分隔条 |
| `TYPE_MAGNIFICATION_OVERLAY` | 6 | `:96` | 放大镜浮层 |

映射代码在 `services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java:1030-1037`
（`TYPE_APPLICATION_OVERLAY` 落在 `TYPE_SYSTEM` 那一支，`TYPE_ACCESSIBILITY_OVERLAY` 单独一支）。

> 本项目用的是 2032 → 在 `getWindows()` 里会显示为 `TYPE_ACCESSIBILITY_OVERLAY`(4)，**不会**混进
> `TYPE_APPLICATION`。选窗时可以放心按 `type == TYPE_APPLICATION` 过滤。

### 4.3 遮挡/过滤规则（为什么你的 overlay 不会把目标窗口挤掉）

`AccessibilityWindowManager.java`：

- `windowMattersToAccessibilityLocked(...)`（`:573-600`）：focused 一定保留；不可触摸且非分隔条/非 PiP 菜单的丢弃；
  **被完全覆盖的丢弃**（用 unaccounted space 判断）；
- `windowMattersToUnaccountedSpaceComputation(...)`（`:619-633`）：**`TYPE_ACCESSIBILITY_OVERLAY` 不消耗
  unaccounted space**（`if (a11yWindow.getType() == TYPE_ACCESSIBILITY_OVERLAY) return false;`）。

配合 `AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY` 的 javadoc 原文：

> if there is a full screen accessibility overlay that is touchable, the windows below it will be
> introspectable by an accessibility service regardless they are covered by a touchable window

→ 即使状态球是 touchable 且盖在目标 app 上，**目标 app 窗口仍在 `getWindows()` 里**，可以直接拿来当 target。

### 4.4 各场景选窗策略（推荐写法）

| 场景 | 选择规则 | 备注 |
|---|---|---|
| 普通单窗口 | `windows.firstOrNull { it.type == TYPE_APPLICATION }` | 列表已是 z 序降序 |
| 有系统弹窗/权限框 | 优先取同时 `isFocused()` 的 `TYPE_APPLICATION`；若 focused 是 `TYPE_SYSTEM`，说明焦点在系统层，此时「用户看到的」是系统窗口 | `isFocused()` = `AccessibilityWindowInfo.java:445` |
| 分屏 | 两个 `TYPE_APPLICATION`，取 `isFocused()` 的那个；要两块都截就分别截再拼 | 两次调用不同 windowId 不互相限流（§2.2） |
| 画中画 | `TYPE_APPLICATION` 中 `isInPictureInPictureMode()` 为 true 的那个；主窗口另有 `isFocused()` | AWM 有 `getPictureInPictureWindowLocked()`（observer 侧 `:312-315`，对外 `:1911`）可参考判断口径 |
| 多显示器 | `getWindowsOnAllDisplays(): SparseArray<List<AccessibilityWindowInfo>>`（`AccessibilityService.java:1101`），key = displayId | 单窗口 API 没有 displayId 参数，window id 自带归属 |
| 输入法展开 | IME 是独立窗口（`TYPE_INPUT_METHOD`），**不会**出现在应用窗口截图里 | 需要 IME 就单独截它或改用整屏 |
| 自己的 2032 浮窗 | `type == TYPE_ACCESSIBILITY_OVERLAY`，能截但没意义（§1.6） | 不要当 target |

`windowId` 的另一个来源是 `AccessibilityEvent.getWindowId()`（事件里带），但用之前建议先用
`getWindows()` 校核一次该 id 仍在列表里——**源码推断，未实测**（事件与窗口生命周期有时序间隙）。

### 4.5 「无障碍服务能否截自己的窗口」

- **能**：`getConnectionLocked` 只按 windowId 查表，没有 owner 校验（`AccessibilityWindowManager.java:1425-1442`）；
  自家 overlay 的 `ViewRootImpl` 在无障碍开启时会注册 interaction connection
  （`ViewRootImpl.java:11947-11975` `AccessibilityInteractionConnectionManager.ensureConnection()`，
  调用点在 `:1746`）。
- **但没意义**：截到的是你自己的 overlay 内容（§1.6）。「截自己窗口来验证 API 是否可用」可以，
  「截自己窗口当屏幕截图」不行。
- 标注：这是源码推断，未在 M2012K11AC 上实测。

---

## 5. Q5：返回 Bitmap 的坐标系（窗口本地 vs 屏幕）

### 5.1 结论

**窗口本地坐标**：原点 = 目标窗口 layer 的 (0,0)，尺寸 = 该窗口的 cropped buffer size。
不含窗口在屏幕上的偏移，也不含状态栏/导航栏/其它窗口。

### 5.2 决定性证据

**① CTS 直接断言尺寸等于窗口 decorView 尺寸**（`AccessibilityTakeScreenshotTest.java:252-275` + `:416-428`）：

```java
final View activityRootView = activity.getWindow().getDecorView();
verifyScreenshotResult(mSuccessResultArgumentCaptor.getValue(),
        activityRootView.getWidth(), activityRootView.getHeight(), timestampBeforeTakeScreenshot);
...
assertThat(hardwareBuffer.getWidth()).isEqualTo(expectedWidth);
assertThat(hardwareBuffer.getHeight()).isEqualTo(expectedHeight);
```

同一个测试类里整屏版传的 expectedWidth/Height 是 `mDisplaySize.x / mDisplaySize.y`（`:412-414`）——
两者口径不同，正好证明窗口版不是「屏幕尺寸画布 + 偏移」。

**② SF 侧裁切区域就是 layer 的 buffer**（`SurfaceFlinger.cpp:7268-7278`，见 §1.4），
`reqSize = crop.size`（`:7296`），渲染目标即该尺寸、原点 (0,0)
（`LayerRenderArea.cpp` 的 `getSourceCrop()` 返回 `mLayerBufferSize`）。

### 5.3 贴回全屏画布的换算

```java
// w: AccessibilityWindowInfo
val bounds = Rect().also { w.getBoundsInScreen(it) }      // AccessibilityWindowInfo.java:404
// buffer 宽高来自 ScreenshotResult.getHardwareBuffer()
val sx = bounds.width()  / bufferWidth.toFloat()
val sy = bounds.height() / bufferHeight.toFloat()

screenX = bounds.left + localX * sx
screenY = bounds.top  + localY * sy
```

要点与坑：

1. **先比较尺寸**：正常情况下 `bufferWidth == bounds.width()`（同密度、无缩放）。若不等（应用兼容缩放 /
   窗口被 scale / 旋转），上面的 `sx/sy` 就是补偿系数；**不要**假设 1:1。
2. `bounds` 是 **window frame**，buffer 是**窗口内容**：绝大多数应用窗口两者一致，但有 insets（如
   刘海/手势区）时可能差几个像素，建议用一次真机对拍（截一张、拿一个已知 UI 元素的位置核对）来定
   是否需要补 offset。
3. 状态栏 / 导航栏 / 输入法是**独立窗口**，窗口截图里没有 → 若要「和现在的整屏图一致」，得自己用
   `TYPE_SYSTEM` / `TYPE_INPUT_METHOD` 窗口补齐，或改方案（§8）。
4. 返回的是 `HardwareBuffer`，不是 `Bitmap`：要 `Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)`
   再 `.copy(ARGB_8888, false)`，用完 `hardwareBuffer.close()`（`AccessibilityService.java:3300-3320`
   `ScreenshotResult` javadoc 明确要求 close）。
5. 时间戳 `getTimestamp()` 是 `SystemClock.uptimeMillis()`（`ScreenshotResult` javadoc）——可用于
   判断两次截图是否同帧。

---

## 6. Q6：API 34/35/36 行为变更与已知 bug

### 6.1 跨版本源码比对：**实现完全一致**

拉取 `refs/tags/android-14.0.0_r1` / `android-15.0.0_r1` / `android-16.0.0_r1` 的
`core/java/android/view/AccessibilityInteractionController.java`，`takeScreenshotOfWindowUiThread`
在三版中逐字一致（行号仅差 1，因为上方多了一行 import/注释）：

| 版本 | 方法行 | SECURE 检查 | childrenOnly | setUid |
|---|---|---|---|---|
| Android 14 | `:612` | 有 | `false` | `Process.myUid()` |
| Android 15 | `:613` | 有 | `false` | `Process.myUid()` |
| Android 16 | `:614` | 有 | `false` | `Process.myUid()` |

→ 不存在「15/16 收紧或放宽」的迹象。

### 6.2 官方行为变更：没有相关条目

已逐个抓取正文并检索（`curl -sL` + 去标签）：

- Android 14 页里唯一的 `screenshot` 相关项是**独立的 Screenshot detection 功能**（`DETECT_SCREEN_CAPTURE`），
  与 accessibility 截图 API 无关；
- Android 15 / 16 页 `screenshot` 零命中。

- https://developer.android.com/about/versions/14/behavior-changes-14
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all

API 34 新增本 API 本身即「能力变化」；之后没有针对它语义的变更记录。

### 6.3 已知 bug：**未找到可信的公开条目**（诚实结论）

- CTS 里 `testTakeScreenshotOfWindow_ErrorForSecureWindow` 标了 **`@FlakyTest`**（`:281`）——
  说明官方自己认为 secure-window 这条路径在测试环境不稳定（弱证据，但值得记录）。
- 搜索 `takeScreenshotOfWindow` + blank/black/bug 未命中可信 issue（issuetracker 需登录且 JS 渲染，
  抓不到正文，**不构成论据**）。→ 标「未验证」。
- 社区帖多为「如何截别的 app」的入门问答，没有可引用的行为差异。

### 6.4 迁移前建议的真机验证清单（每项 1 次即可）

| # | 场景 | 期望 | 为什么 |
|---|---|---|---|
| 1 | 普通 app + 状态球可见 | 图里**没有**状态球，尺寸 = 目标窗口 | Q1 核心 |
| 2 | 目标 app 是 `FLAG_SECURE` | `onFailure(6)` | Q3 |
| 3 | 传一个已销毁窗口的旧 id | `onFailure(5)` | Q3 |
| 4 | 目标 app 退到后台再截 | 观察是 `onSuccess` 空图还是失败 → 决定要不要自己判空 | §3.2 未验证项 |
| 5 | 分屏两块连续截 | 两次都成功（不互相限流） | Q2 |
| 6 | 同一窗口连截两次（<333ms） | 第二次 `onFailure(3)` | Q2 |
| 7 | PiP 窗口 | 能否按 `isInPictureInPictureMode()` 选中并截到 | Q4 |
| 8 | 状态球用 `attachAccessibilityOverlayToDisplay` 挂（若走方案 B） | 整屏 `takeScreenshot` 里没有状态球 | §8 |

---

## 7. Q7：能力要求与 `targetSdk=28` 的影响

### 7.1 需要什么

| 要求 | 说明 | 依据 |
|---|---|---|
| meta-data `android:canTakeScreenshot="true"` | 写在 `<accessibility-service>` 上；解析后置 `CAPABILITY_CAN_TAKE_SCREENSHOT = 1 << 7` | `AccessibilityServiceInfo.java:786-788`、`:161` |
| `CAPABILITY_CAN_TAKE_SCREENSHOT` | 服务端 `canTakeScreenshotLocked()` 唯一判据 | `AccessibilitySecurityPolicy.java:392-396` |
| AppOps `OPSTR_ACCESS_ACCESSIBILITY` 允许 | `checkAccessibilityAccess()`（`AccessibilitySecurityPolicy.java:636-660`） | 服务正常启用即可 |
| `getWindows()` 先决条件 | `canRetrieveWindowContent="true"` + `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` | `AccessibilityServiceInfo.java:303-321` |

**不存在** `FLAG_REQUEST_TAKE_SCREENSHOT` 这个 flag（`AccessibilityServiceInfo` 的 `FLAG_REQUEST_*` 全集：
`:270-391`，没有截图相关项）。如果需要，用「能力」而不是「flag」。

### 7.2 `targetSdk=28` 有影响吗

- **能力声明不受 targetSdk 影响**：XML 属性 → `mCapabilities` 是无条件解析（`AccessibilityServiceInfo.java:774-789`）。
- 在 `AbstractAccessibilityServiceConnection` / `AccessibilitySecurityPolicy` 里 grep `targetSdkVersion`，
  只有一处与本 API 无关的 `supportsFlagForNotImportantViews()`（`acc.java:475-479`，判 `>= JELLY_BEAN`）。
  → **源码级：没有 targetSdk 门禁**。
- 唯一的实际约束是运行时版本：`Build.VERSION.SDK_INT >= 34`（在 28 的 targetSdk 上照样要运行时判版本）。
- 编译期：`android:canTakeScreenshot` 属性自 API 30 起可用；本项目 compileSdk=36 没问题，
  但 minSdk=26 的 `aapt` 不会拦属性（只影响 lint 提示）。

> 结论：**targetSdk=28 不是障碍**；真正的门槛是「设备 ≥ Android 14」+「XML 里声明能力」+「服务已启用」。

---

## 8. 对本项目 `GET /screenshot` 的落地方案

> 现状（2.9.71）：`beginCaptureAvoid()` 把状态球 root 设 INVISIBLE + sleep 120ms → 整屏
> `takeScreenshot` → `endCaptureAvoid()` 设回 VISIBLE。已证实 INVISIBLE 会让 WMS **销毁并重建
> surface**（见 `flicker-research.md` §1），肉眼闪 2.3s。

### 8.1 三个可选方案

| 方案 | 做法 | 图的内容 | 代价 |
|---|---|---|---|
| **A. 截目标窗口 + 拼回全屏** | `getWindows()` 选 `TYPE_APPLICATION`（focused 优先）→ `takeScreenshotOfWindow` → 按 `getBoundsInScreen()` 贴到显示尺寸画布 | 只有目标 app 窗口；状态栏/导航栏/IME/其它 app 窗口为空白 | 无闪烁、无 surface 重建；但**语义变了**（不再是整屏） |
| **B. 整屏截图 + 用 SurfaceControl 隐藏浮窗** | 浮窗改用 API 34 `attachAccessibilityOverlayToDisplay` + `SurfaceControlViewHost` 自持 SurfaceControl；截图前 `Transaction.hide(sc)`，截完 `show(sc)` | 完整整屏（含系统栏），**不含**浮窗 | 需要重构浮窗创建路径；`Transaction` 只翻 `eLayerHidden` 标志、不销毁 layer（`SurfaceControl.java:3197-3262` + SF `RequestedLayerState.isHiddenByPolicy`），无闪烁 |
| **C. 保持现状** | INVISIBLE + sleep | 整屏（不含浮窗） | 2.3s 闪烁 + surface 重建 + 触摸状态丢失风险 |

### 8.2 推荐

- 如果 `/screenshot` 的**契约就是「整屏、和用户看到的一致」** → 走 **B**；`takeScreenshotOfWindow`
  只适合「只要目标 app 内容」的新接口（例如给 LLM 看目标应用 UI）。B 的关键前提是浮窗必须由
  自己持有 SurfaceControl（现在的 `WindowManager.addView` 拿不到可用的 `SurfaceControl`，
  `View.getRootSurfaceControl` 是 @hide，targetSdk≥28 反射不可靠）。
- 如果可以先改契约 → 走 **A**，并明确文档写「返回目标窗口内容，坐标系为窗口本地，窗口位置见
  `boundsInScreen`」。
- **A/B 都不需要**在截图期间改 `root.visibility`；`takeScreenshotOfWindow` 本身就不含浮窗，
  B 的 `Transaction.hide` 不销毁 layer。

### 8.3 方案 A 的伪代码（含坐标换算）

```kotlin
fun captureTargetWindow(): Bitmap? {
    if (Build.VERSION.SDK_INT < 34) return null            // API 34+
    val windows = windows ?: return null                    // 需 FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    val target = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .firstOrNull { it.isFocused }
        ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        ?: return null

    val latch = CountDownLatch(1)
    var result: AccessibilityService.ScreenshotResult? = null
    takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
        override fun onSuccess(screenshot: ScreenshotResult) { result = screenshot; latch.countDown() }
        override fun onFailure(errorCode: Int) { latch.countDown() }   // 3=限流 5=窗口没了 6=secure
    })
    if (!latch.await(2, TimeUnit.SECONDS) || result == null) return null

    val hb = result!!.hardwareBuffer
    val local = Bitmap.wrapHardwareBuffer(hb, result!!.colorSpace)!!.copy(Bitmap.Config.ARGB_8888, false)
    hb.close()

    val bounds = Rect().also { target.getBoundsInScreen(it) }
    val canvas = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
    Canvas(canvas).drawBitmap(local, null,
        Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), null)   // 1:1 时
    return canvas
}
```

> 注意：上面按「bounds 尺寸 == buffer 尺寸」写的 `drawBitmap` 拉伸；若两者不等，`Rect` 目标仍是
> `bounds`，`drawBitmap` 会自动缩放，等价于 §5.3 的 `sx/sy`。

### 8.4 验收指标（可量化）

1. 截图前后各采一次 `dumpsys window windows`：目标 2032 窗口的 `Surface:` 行**始终** `shown=true
   mDrawState=HAS_DRAWN`（不再出现 `NO_SURFACE`）。
2. 连续 20 次 `GET /screenshot`：状态球无可见闪烁（录屏逐帧 diff，变化像素 < 阈值）。
3. 截图内容：目标 app 的已知 UI 元素在图上位置 = `boundsInScreen` + 窗口内偏移（误差 ≤1px）。
4. 耗时：`takeScreenshotOfWindow` 往返 P50/P95（预期 30–120ms，需实测；比 sleep 120ms 更短或相当）。

---

## 9. 未验证清单（**不要当论据用**）

1. 目标窗口 `GONE`/后台时 `takeScreenshotOfWindow` 的返回：成功但空图？还是 `INTERNAL_ERROR`？
   → 源码无显式可见性判断，需真机实测。
2. `attachAccessibilityOverlayToWindow` 挂出的 overlay 是否真被 uid 过滤掉（§1.5）→ 源码推断，需 instrumented test。
3. `AccessibilityEvent.getWindowId()` 直接当 target 的可靠性（§4.4）→ 未实测。
4. HyperOS / MIUI 对本 API 的私有改动 → 未找到公开资料；建议在 M2012K11AC 上按 §6.4 跑一遍。
5. `ScreenshotHardwareBuffer.getWidth()` 在 `frameScale != 1` 时的具体缩放口径
   → SF 侧 `reqSize = crop * frameScale` 清楚，但 Java 侧未暴露 `frameScale`，实际值需实测。
6. Google Issue Tracker 相关条目 → 登录墙，未抓到正文。

---

## 10. 参考清单（按可信度分档）

### 10.1 AOSP 源码（已逐行阅读，最强）

| 文件 | 路径 | 关键行 |
|---|---|---|
| AccessibilityService.java | `core/java/android/accessibilityservice/AccessibilityService.java` | 734-741, 747, 773, 780, 788, 1051-1075, 1101, 2705, 2761, 3274-3312 |
| AccessibilityInteractionClient.java | `core/java/android/view/accessibility/AccessibilityInteractionClient.java` | 114, 814-870, 1354-1374 |
| AccessibilityInteractionController.java | `core/java/android/view/AccessibilityInteractionController.java` | 598-633 |
| ViewRootImpl.java | `core/java/android/view/ViewRootImpl.java` | 11947-11975, 12199-12216 |
| AbstractAccessibilityServiceConnection.java | `services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java` | 250, 1416-1455, 1467, 2157-2168 |
| AccessibilityWindowManager.java | `services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java` | 280-283, 312-315, 573-600, 604-615, 619-633, 1030-1037, 1425-1442, 1911 |
| AccessibilitySecurityPolicy.java | `services/accessibility/java/com/android/server/accessibility/AccessibilitySecurityPolicy.java` | 392-396, 636-660 |
| AccessibilityServiceInfo.java | `core/java/android/accessibilityservice/AccessibilityServiceInfo.java` | 161, 270-391, 303-321, 774-789 |
| AccessibilityWindowInfo.java | `core/java/android/view/accessibility/AccessibilityWindowInfo.java` | 58, 64, 70, 83, 89, 96, 213, 404, 423, 445 |
| ScreenCapture.java | `core/java/android/window/ScreenCapture.java` | 175, 186, 440, 602, 660 |
| SurfaceFlinger.cpp | `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` | 7230, 7268-7278, 7296, 7313, 7327, 8298-8316, 8380 |
| LayerRenderArea.cpp | `frameworks/native/services/surfaceflinger/LayerRenderArea.cpp` | `getSourceCrop()` 全函数 |

CTS（行为级证据，比源码更接近真机）：

| 文件 | 关键行 |
|---|---|
| `cts/tests/accessibilityservice/src/android/accessibilityservice/cts/AccessibilityTakeScreenshotTest.java` | 252-275（窗口尺寸断言）、279-311（secure，@FlakyTest）、317-343（多窗口限流）、346-352（invalid）、355-372（无能力）、416-428（verifyScreenshotResult） |

### 10.2 官方文档（已抓取正文）

- AccessibilityService 参考（`takeScreenshot` API 30 / `takeScreenshotOfWindow` API 34 / 错误码含 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`）
  https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- AccessibilityWindowInfo 参考（type 常量）
  https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo
- AccessibilityServiceInfo 参考（`FLAG_RETRIEVE_INTERACTIVE_WINDOWS`、`CAPABILITY_CAN_TAKE_SCREENSHOT`）
  https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo
- TakeScreenshot / 截图相关（`ScreenshotResult`、`HardwareBuffer.close()` 要求）
  https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult
- Android 14/15/16 行为变更（均无本 API 相关条目）
  https://developer.android.com/about/versions/14/behavior-changes-14
  https://developer.android.com/about/versions/15/behavior-changes-15
  https://developer.android.com/about/versions/16/behavior-changes-16

### 10.3 社区 / 二手资料

- Microsoft Learn（.NET for Android 绑定，可用于交叉核对签名与 `ApiSince=34`）
  https://learn.microsoft.com/en-us/dotnet/api/android.accessibilityservices.accessibilityservice.takescreenshotofwindow?view=net-android-34.0
  抓到的关键信息：`[Android.Runtime.Register("takeScreenshotOfWindow", "(ILjava/util/concurrent/Executor;Landroid/accessibilityservice/AccessibilityService$TakeScreenshotCallback;)V", ... ApiSince=34)]`
  —— 参数列表与本文一致（三参，无 displayId）。

### 10.4 未验证（链接可达但正文抓不到，**不构成论据**）

- https://issuetracker.google.com/ （JS + 登录墙，搜索结果无法读到正文）
- stackoverflow 上的 `takeScreenshotOfWindow` 相关问答（只有入门级问题，无行为差异证据）

---

## 11. 一页纸结论

- **它截的是窗口，不是屏幕。** root = 目标窗口 `ViewRootImpl.getSurfaceControl()`，子树遍历 +
  `setUid(目标 app uid)` 过滤 → 你的 2032 状态球**不会**进图（三重源码证据）。
- **它是窗口本地坐标。** buffer 宽高 = 窗口 layer buffer；要贴回全屏必须自己加 `boundsInScreen`，
  并处理 scale/insets（CTS 用 decorView 宽高断言钉死了这一点）。
- **它有自己的 333ms 计时器，按 windowId 分桶**，与整屏 `takeScreenshot` 完全独立（源码 + CTS 双证）。
- **失败码 6 个**：1 内部/超时、2 无能力、3 限流、4 仅 display 版、5 窗口无效、6 FLAG_SECURE。
  目标窗口「不可见」没有显式检查 → 这是唯一需要真机确认的行为。
- **它不需要你隐藏浮窗**，这正是官方设计目的；本项目「截图前 INVISIBLE + sleep」可以整段删掉。
- **但对 `/screenshot` 的语义要重新定义**：窗口版给不了状态栏/导航栏/IME/其它窗口。要「整屏且无浮窗」，
  正解是把浮窗改成自己持有 SurfaceControl（API 34 `attachAccessibilityOverlayToDisplay`），
  用 `Transaction.hide()/show()`（只翻标志位、不销毁 layer），而不是改 view 可见性。
