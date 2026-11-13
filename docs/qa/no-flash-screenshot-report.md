# 显式截图零闪烁 + prefetch OCR 修复报告（2.9.73–2.9.76 / 644–647）

> 历史取证记录：结论仅适用于文中设备、版本和采样范围。当前行为与维护约束见相关使用文档；旧行号和临时文件路径不代表当前工作区。

> 第二轮。第一轮见 [flicker-fix-report.md](flicker-fix-report.md)（闪烁主因：截图避让让 WMS 销毁 surface）。
> 本文只讲第一轮**没能彻底解决**的两件事：
> ① 显式截图（`/screenshot`）仍会闪 2.3 秒；② prefetch OCR 每次都 `n=0`。
> 环境：Redmi K40 / Android 16 (SDK 36) / 5f08bc20 / Magisk root。

---

## 0. 结论先行

| 问题 | 旧行为 | 新行为 | 判据 |
|---|---|---|---|
| 显式截图 | 悬浮窗 `INVISIBLE` 2.32s，surface `NO_SURFACE` | 悬浮窗**全程 `HAS_DRAWN`**，0 hide/show | 663 采样点全 OK，负对照 10 次 RECREATED |
| 键盘/弹窗场景 | 无解 → 回退整屏避让（就闪） | **组合窗口合成**：App+键盘+状态栏一次拍全 | `painted=3`，`ms=309` |
| prefetch OCR | `n=0`，白烧 780 ms，每 2.5s 一次 | ML Kit 回退，`n=5~37`，238–434 ms | `ocr ready ... engine=mlkit` |

一台机器上的负对照（`?flash=1` 走旧路径）：**205 GONE / 3 NO_CONTENT / 10 RECREATED**，
同一条采样流水线对新路径是 **0 / 0 / 0**。所以「不闪」不是采样没看出来。

---

## 1. 为什么 INVISIBLE 一定会闪（真机直接证据）

第一轮已经查清 AOSP 路径，本轮的窗口 dump 又把它钉死了一次：

```
# 截图前
mViewVisibility=0x0  Surface: shown=true  mDrawState=HAS_DRAWN  isVisible=true
# 截图期间（示例，643 的 D 场景，持续 16:19:05.470 → 16:19:07.792 = 2.32s）
mViewVisibility=0x4  Surface: shown=false mDrawState=NO_SURFACE  isVisible=false
# 截图后
mViewVisibility=0x0  Surface: shown=true  mDrawState=HAS_DRAWN  isVisible=true
```

`mDrawState=NO_SURFACE` 就是按 [WindowStateAnimator.java:707-721](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowStateAnimator.java)
把 layer 从 SurfaceFlinger 摘掉、释放 BufferQueue。恢复时重建 layer + 等首帧 ⇒ 肉眼闪、
且这段时间**窗口不可触摸**（触摸穿透到下层 App）——这正是「有时点不到」。

推论：**任何靠切换 window 可见性来"避开截图"的做法都必然闪**。要真不闪，只能让截图
本身不包含悬浮窗，即 `AccessibilityService.takeScreenshotOfWindow`（API 34+）。

---

## 2. 实现：组合窗口截图（`screenshotCompositeBase64`）

代码：`app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt`

```kotlin
// 真实签名（compileSdk 36，三参数、没有 displayId；四参数会编译失败）
takeScreenshotOfWindow(int accessibilityWindowId, Executor, TakeScreenshotCallback)
```

流程：

1. `windows` 取当前所有无障碍可见窗口；
2. **按 bounds 精确比对 `StatusOverlay.currentFrameRect()` 排除自己的悬浮窗**
   （不能按包名——主 WebView 也是 `com.termux`；也不能按 a11y type——application overlay
   在 a11y 里报成 `TYPE_SYSTEM`，和状态栏撞型）；
3. 按 `layer` 升序（底层先画）取最多 6 个窗口，逐个 `takeScreenshotOfWindow`；
4. 每张按 `getBoundsInScreen()` 贴回 `realScreenSize()` 画布（窗口本地坐标 → 屏幕坐标，
   保证与 `/screen` 节点坐标、点击坐标一致）；
5. 失败/空窗口跳过，全失败则回退（单窗口 → 整屏+避让）。

`realScreenSize()`（`WindowManager.maximumWindowMetrics`）是本轮另一个必要修正：
`resources.displayMetrics` 在无障碍服务里是**去掉导航栏**的可用区（真机 1080x2400 报
1080x2305），与 `takeScreenshot` 的整屏 2400 混用会让截图坐标和点击坐标差 95px。

回退链（`screenshotBase64NoFlash`）：`composite` → `window`（单窗口）→ `display + 避让`。
`/screenshot?flash=1` 保留旧路径，作为「要看整屏含系统层」的逃生舱与回归负对照。

---

## 3. 真机实测

### 3.1 常规场景（自家 App 前台）

```
screenshot(composite): ok painted=2 out=1080x2400 ms=663
  [id=2230 t=1 0,0 1080x2400] [id=2229 t=3 0,0 1080x105]
```
`t=1` 应用窗口 + `t=3` 状态栏。状态栏是组合截图顺带修好的（单窗口路径拍不到它）。
耗时 263–663 ms，`hide/show` 日志 **0 条**。

### 3.2 键盘弹出（之前只能回退到会闪的路径）

```
screenshot(composite): ok painted=3 out=1080x2400 ms=309
  [id=2232 t=1 0,0 1080x2400] [id=2221 t=2 0,1532 1080x868] [id=2229 t=3 0,0 1080x105]
```
`t=2` 就是输入法窗口（1080x868，屏幕底部）。导出的 PNG 目视确认键盘完整、悬浮窗所在
区域是页面背景。

### 3.3 通知栏下拉

```
screenshot(composite): ok painted=1 out=1080x2400 ms=308 [id=2225 t=3 0,0 1080x2400]
```
整屏系统窗口，合成结果就是用户看到的通知面板。

### 3.4 四路判据交叉验证（`scripts/probes/flicker/capture.sh`）

同一条流水线，两种截图路径，各 15 秒：

| 路径 | 采样点 | OK | (a) GONE | (b) NO_CONTENT | (c) MOVED | (d) STALE | (e) RECREATED |
|---|---|---|---|---|---|---|---|
| **composite（新）** | 663 | **663** | 0 | 0 | 0 | 0 | 0 |
| `?flash=1`（旧，负对照） | 685 | 467 | **205** | **3** | 0 | 0 | **10** |

`(e) RECREATED` = surface 层被销毁后重建（layer id 变化），是**硬件级真闪**的判据；
旧路径 2 次截图就抓到 10 次，新路径 3 次截图 0 次。

`/frame-wait`（帧轮询，250ms 间隔，2 轮共 58 次 poll）同时跑：670 点全 OK。

### 3.5b 悬浮窗窗口类型纠正（2.9.76 / 647）

排查中发现一个和「有时」高度相关的状态：**窗口类型取决于启动顺序**。

- `StatusOverlay.show(ctx)`：无障碍在跑 → `TYPE_ACCESSIBILITY_OVERLAY`(2032)；
  无障碍还没连上但有悬浮窗权限 → `TYPE_APPLICATION_OVERLAY`(2038) + **Activity token**；
- 而 `show(svc)` 原来开头是 `if (root != null) return`，所以**先起 App、后连无障碍**时，
  2038 那个窗口会被一直留着。

2038 + Activity token 的窗口正是 `StatusOverlay.kt` 注释里记录的坑：App 退到后台被 WMS 置
`mLastHidden=true / isVisible=false`（小米还叠 `mIsForceHiddenNonSystemOverlayWindow`）——
表现就是「切个 App 悬浮窗就没了，回来又有了」。这解释了症状里的「有时」。

实测（复位后）：`dumpsys window` 里 `mAttrs=… ty=APPLICATION_OVERLAY`；
`dumpsys accessibility` 里 `Bound services:{}`（无障碍没连）。

修复：`show(svc)` 发现 `root != null && windowTypeUsed != 2032` 时，**保留坐标与展开档位**
换成 2032（idle 时才换，避免丢正在显示的进度文本）：

```
I/StatusOverlay: overlay type switch: 2038 → TYPE_ACCESSIBILITY_OVERLAY (a11y ready)
mAttrs={(959,330)(99x99) … ty=ACCESSIBILITY_OVERLAY …}   # 坐标未跳
```

修复后重跑四路判据：**686 采样点全 OK**（3 次 composite 截图，`target: window=com.termux`，
GONE / NO_CONTENT / MOVED / STALE / RECREATED 全 0）。

### 3.5 截图内容正确性

- 尺寸：1080x2400（等于 `wm size`，不是 2305）；
- 悬浮窗矩形 `(959,330)-(1058,429)` 区域 std ≈ `[6.7, 4.5, 0.4]`（页面背景），无状态球；
- `/ocr` 正文字块坐标落在整屏坐标系（状态栏文字 `y=53`），与 `/screen` 一致。

---

## 4. prefetch OCR `n=0` 的真实原因

不是模型没部署，也不是截图坏了——是**引擎没装好**，而代码没检查返回值：

1. `PpOcr.deployed()`（`app/src/main/java/app/dsh/mobile/PpOcr.kt`）只查模型文件，
   而模型确实随 APK 部署 → `deployed=true`；
2. 但 PP-OCR 走 Termux 里的 `python + onnxruntime`，`PpOcr.ready()` 为 false；
3. `PpOcr.recognize()` 不抛异常，只返回 `ok=false, unavailable=true, reason="ppocr6 deps missing"`；
4. 旧 `prefetchOcr` **只看 blocks，不看 `ok`** → 每 2.5 s 白烧约 780 ms 截图 + 起进程，
   日志永远 `n=0`。

真机复现（修复前）：

```
GET /ocr?engine=ppocr6&models=1   → tiny deployed=true
GET /ocr?engine=ppocr6&tier=tiny  → {"ok":false,"unavailable":true,
   "reason":"ppocr6 deps missing: run /ocr?engine=ppocr6&ensure=1 (installs python + onnxruntime)"}
GET /ocr                          → 37 行，177–300 ms（内置 ML Kit）
```

### 修复

`app/src/main/java/app/dsh/mobile/bridge/ScreenHandler.kt`

- `prefetchOcr` 先判 `PpOcr.ready()`；不 ready 或识别失败/空 → **回退内置 ML Kit**
  （`ScreenOcr.recognize`），不再烧那次进程；
- 判死后 `ppocr6BrokenUntil = now + 5min`，5 分钟内不再尝试，避免每 2.5 s 重蹈；
- 日志补上引擎与尺寸：`ocr ready n=… dropped=… engine=… size=WxH ms=…`。

修复后：

```
I/prefetch ocr ready n=37 dropped=0 engine=mlkit size=1080x2400 ms=351
I/prefetch ocr ready n=22 dropped=0 engine=mlkit size=1080x2400 ms=280
I/prefetch ocr ready n=5  dropped=0 engine=mlkit size=1080x2400 ms=238
```

`n=0 / 780 ms` → `n=5~37 / 238–434 ms`，且**完全不碰悬浮窗**（走 `avoidOverlay=false`，
按 overlay 矩形过滤 block）。

---

## 5. 已知限制 / 残留风险

| # | 限制 | 影响 | 处置 |
|---|---|---|---|
| 1 | API < 34 没有 `takeScreenshotOfWindow` | 老设备仍走整屏+避让，会闪 | 回退路径保留，`hideRefs` 加固（第一轮）保证不失控 |
| 2 | `FLAG_SECURE` 窗口截不到 | 该窗口区域为黑 | `/screenshot` 回执仍 `ok`；需要内容时人工确认 |
| 3 | 半透明窗口背后的内容拍不到 | 通知面板/对话框后面是黑底 | 系统面板本身自带模糊背景，实际观感可接受 |
| 4 | 窗口数上限 6、耗时随窗口数线性 | 极端多窗口 +200 ms | 上限保护已加 |
| 5 | `mIsForceHiddenNonSystemOverlayWindow` | Settings 等持有 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` 的界面里，系统**强制隐藏**我们的悬浮窗（`mPolicyVisibility=false`，view 仍是 `0x0`） | 系统行为，非本 app bug；换到 Chrome/桌面即恢复 `HAS_DRAWN` |
| 6 | 组合截图的窗口顺序按 a11y `layer` 升序 | 理论上同 layer 的窗口顺序可能不严格 | 实测 App/键盘/状态栏/通知栏均正确 |

---

## 6. 复现 / 回归命令

```bash
# 端口转发（bridge 只监听设备 127.0.0.1:3083）
adb forward tcp:3083 tcp:3083
TOK=$(adb shell su -c "cat /data/user/0/com.termux/files/usr/.bridge-token" | tr -d '\r')

# 新路径（应 via=composite，且日志无 hide/show）
curl -s -H "X-DSH-Token: $TOK" "http://127.0.0.1:3083/screenshot?save=1"

# 旧路径（负对照，应 via=display-forced 且 forensics 报 GONE/RECREATED）
curl -s -H "X-DSH-Token: $TOK" "http://127.0.0.1:3083/screenshot?save=1&flash=1"

# 四路判据（边采样边截图）
cd scripts/probes/flicker && ./capture.sh 15

# prefetch 判定
curl -s -H "X-DSH-Token: $TOK" "http://127.0.0.1:3083/daemon/status" >/dev/null   # 触发一次 prefetch
adb logcat -d | grep "prefetch ocr ready"
```

---

## 7. 改动清单

| 文件 | 改动 |
|---|---|
| `StatusOverlay.kt` | `show(svc)` 在「应用悬浮窗(2038) → 无障碍窗口(2032)」时热切换，保留坐标/档位（2.9.76） |
| `DshAccessibilityService.kt` | `screenshotCompositeBase64` / `captureWindowBitmap` / `screenshotWindowBase64` / `pickCaptureWindow` / `screenshotBase64NoFlash` / `realScreenSize` / `lastShotVia` |
| `bridge/ScreenHandler.kt` | `prefetchOcr` 引擎判定 + ML Kit 回退 + `ppocr6BrokenUntil`；`/screenshot` 走 `screenshotBase64NoFlash`，`?flash=1` 走旧路径，回执加 `via` |
| `app/build.gradle.kts` | versionCode 643→647，versionName 2.9.72→2.9.76 |
| `docs/qa/take-screenshot-window.md` | AOSP 级行为研究（窗口截图语义/坐标系/节流分桶） |
| `scripts/probes/flicker/` | 判据扩到 type ∈ {2032, 2038}、新增 `(e) RECREATED` 硬件级真闪判据 |
