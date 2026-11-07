# vivo 真机（无 root）体验问题排查与修复报告

- 设备：**vivo V2520A**，Android **17（SDK 37）**，OriginOS **17.0**，1080×2376 @480dpi
- 序列号：`10CG1C1M1H0021Z`（**无 root**；全程仅用 `adb` + 应用自带终端）
- 起点版本：2.9.79 / 650（用户装机后反馈「体验还不乐观」）
- 终点版本：**2.9.88 / 659**
- 用户原话：*「还不乐观，说实话在vivo phone上的体验。你想办法在真机上进行测试(真机是没有root的)」*

---

## 0. 结论速览

| # | 现象 | 根因 | 修复 | 版本 |
|---|---|---|---|---|
| 1 | 悬浮窗**不断闪烁**、且闪烁期间**点不到** | `Router.route` 对每个 `POST /act` 无条件 `beginOpsAvoid()`；agent 连续操作时窗口 3Hz 退场重建，实测 **35% 占空比不可见** | 避让下沉到手势层：只有注入轨迹与悬浮窗矩形相交才隐藏 | 2.9.86 / 657 |
| 2 | 系统**静默撤销无障碍**后悬浮球直接消失 | 2032 窗口由 a11y 服务维持，服务一断窗口即销毁 | `onAccessibilityLost()` 先摘窗再用 `TYPE_APPLICATION_OVERLAY`(2038) 原地复活 | 2.9.84 / 655 |
| 3 | 复权后窗口**卡在 2038**，切别的 App 就看不见 | `working` 标志长期不回落 → 类型切换永远「skipped: working」 | working 只推迟 20 秒，超时强制切回 2032 | 2.9.87 / 658 |

---

## 1. 无 root 怎么驱动 bridge（本报告的方法学前提）

> **2.9.89 / 660 起有官方通道了**：`adb shell content query --uri content://com.termux.adb/token`
> 一条命令直接拿 token + 诊断状态，见 [ADB-DEBUG-CHANNEL.md](../ADB-DEBUG-CHANNEL.md)。
> 下面这套「应用内终端 cp」是 660 之前的唯一办法，保留作历史与兜底。

`/data/user/0/com.termux` 是 0700，`adb shell` 读不到 `.bridge-token`，包也不是 debuggable。
突破口是**应用自带的终端**（设置 → 扩展 → 终端，真 Termux 环境、同一 uid）：

```bash
# 1) 宿主直接拉起终端 Activity，不用手点设置页
adb -s 10CG1C1M1H0021Z shell am start -n com.termux/app.dsh.mobile.term.TerminalActivity
sleep 2.5
# 2) 在终端里执行（%s 是 adb input text 的空格转义）
adb -s 10CG1C1M1H0021Z shell input text "cp%s/data/data/com.termux/files/usr/.bridge-token%s/sdcard/dsh-token.txt"
adb -s 10CG1C1M1H0021Z shell input keyevent 66
# 3) 共享存储可直接读
adb -s 10CG1C1M1H0021Z shell cat /sdcard/dsh-token.txt
# 4) 之后就能全功能驱动
adb -s 10CG1C1M1H0021Z forward tcp:3083 tcp:3083
curl -H "X-DSH-Token: <token>" http://127.0.0.1:3083/diag
```

> token 每次重装 APK 都会变（旧 token 返回 403），重装后要重取。

## 2. 复现与定位过程

### 2.1 先证伪「截图/OCR 会闪」

在 vivo 上逐场景采样自家窗口（`dumpsys window windows` 里 `com.termux type=2032` 块的
`mViewVisibility / mHasSurface / mDrawState / isVisible`），每样本 ~0.12–0.2s：

| 场景 | 采样 | 不可见 |
|---|---|---|
| A 静置 | 25 | 0 |
| B prefetch 高压（20×`/daemon/status`） | 25 | 0 |
| C `/screenshot` | 16 | 0 |
| D `/ocr?engine=ppocr6` | 18 | 0 |
| E `/frame-wait`（250ms×2） | 20 | 0 |

⇒ 免闪截图（2.9.75 起 `takeScreenshotOfWindow` 组合窗口）与 prefetch 修复在 Android 17 上
同样成立，**不是它们**。`/screenshot` 回执 `via=composite`、`/ocr` 76 行/967ms。

### 2.2 抓到真凶：`/act` 的请求级避让

同一套采样法打 `/act`：3 次请求 24 个样本里 **4 次 `NO_SURFACE/isVisible=false`**，
结束后 5 秒窗口才回来。把版本升到 651（当时只做了「按 body 里的坐标判定」），
日志里出现 **~3Hz 的 `hide(ops)` / `show(ops)` 循环**（每次 20–40ms，线程号每次不同）：

```
I StatusOverlay: hide(ops): visibility=4 ... hideRefs=1 type=2032
I StatusOverlay: show(ops): visibility=0 ... hideRefs=0 type=2032
```

同时 logcat 显示 app 自己的 agent 正在跑任务（`structured progress: kind=thinking`）。
定量：**40 次采样 14 次不可见 = 35% 占空比**。这就是「不断闪烁」+「有时点不到」
（不可见的那 35% 时间里窗口正在退场重建，触摸落空）。

> Android 侧机理见 `docs/qa/flicker-research.md` §1：`INVISIBLE` 对 2032 窗口不是「隐藏图层」，
> 而是 WMS 直接销毁 surface（`WindowStateAnimator.java:707-721`），恢复时重建并等首帧——所以
> 每一次 hide/show 都是一次可见的闪。

### 2.3 两个 OriginOS 特有的健壮性坑

1. **隔夜被撤销无障碍**：`enabled_accessibility_services` 变 `null`、`accessibility_enabled=0`
   （用户没有任何操作）。直接影响：2032 窗口由服务维持，服务一断**窗口立即销毁** → 悬浮球消失。
   → 2.9.84 起 `onAccessibilityLost()` 用 2038 原地复活（实测撤销后窗口变 `type=2038` 且
   `isVisible=true`，切 Chrome 15 秒仍在）。
2. **`working` 卡死导致 2038 回不去**：日志每 60 秒一条
   `overlay type switch skipped: working (type=2038)`，无限循环。而 2038 + Activity token
   一退后台就被 WMS 隐藏（旧已知问题）→ 用户会觉得「切个 App 球就没了」。
   → 2.9.87 起 working 只推迟 **20 秒**，超时强制切换。实测：撤销 a11y → 2038；复权后
   **8 秒内**回到 2032。

## 3. 修复实现

### 3.1 避让下沉到手势层（2.9.86 / 657）

```kotlin
// StatusOverlay.kt
fun beginOpsAvoidIfHit(gestureBounds: Rect): Boolean {
    val r = currentFrameRect() ?: return false
    if (!Rect.intersects(r, gestureBounds)) return false
    beginOpsAvoid()
    return true
}

// DshAccessibilityService.kt：所有注入统一走它
private fun dispatchGestureAvoided(g: GestureDescription, cb, h): Boolean {
    val bounds = gestureBounds(g)                    // 所有 stroke 的 bounds 并集 ±2px
    if (bounds == null || !StatusOverlay.beginOpsAvoidIfHit(bounds)) {
        return dispatchGesture(g, cb, h)             // 不相交 → 一动不动
    }
    // 相交 → 隐藏 → 注入 → 回调里恢复（1200ms 兜底）
}
```

三个必须知道的坑：

- **`AccessibilityService.dispatchGesture` 是 `final`** —— 编译报
  `'dispatchGesture' overrides nothing`。只能在类内做私有助手并替换注入点（本次 6 处）。
- 判定必须在**注入那一刻**：`/pick`、`/tap-id`、`/scroll-*` 的坐标是 handler 内部按节点
  解析的，Router 读 body 时根本不知道会点到哪。
- 节点级 `ACTION_CLICK` 不经过输入通道，**本来就不会被悬浮窗吞掉**，旧逻辑把它也避让了，纯属自伤。

### 3.2 Router 只保留「改窗口自身 / 系统选择器」的避让

```kotlin
private val OPS_AVOID_PATHS = setOf("/overlay", "/open/file", "/open/folder", "/open/share")
val avoid = method == "POST" && path in OPS_AVOID_PATHS
```

## 4. 验证（2.9.88 / 659，vivo 真机）

### 4.1 闪烁

| 场景 | 采样 | 窗口不可见 |
|---|---|---|
| agent 式连点 **40 次**（全部远离球） | 40 | **0**（旧版同场景 35%） |
| 30 次连点（复测） | 30 | **0** |
| `/screenshot` | 20 | 0 |
| `/ocr?engine=ppocr6` | 20 | 0 |
| `/ocr`(ML Kit) | 20 | 0 |
| `/frame-wait` | 20 | 0 |
| `/act {"steps":[{"op":"skip"}]}` | 20 | 0 |
| prefetch（`/daemon/status` 触发） | 日志 | `n=28–29 dropped=0 engine=mlkit ms=208/265` |

### 4.2 交互正确性

| 动作 | 结果 |
|---|---|
| **真实手指**点球 `input tap 1000 506` | 展开成 `frame=[336,452][1056,875]`（720 宽）✅ |
| **真实手指**点圆点 `input tap 382 494` | 收回到 `frame=[948,452][1056,560]`（108）✅ |
| **注入** tap 压在球上 | 球保持 108 宽不展开（避让生效、点击穿透）✅ |
| **注入** swipe 穿球 `700,506→1050,506` 400ms | 不展开、不移动窗口 ✅ |
| **注入** tap 点齿轮 `(1010,176)` | 系统/应用功能正常（打开设置页）✅ |

`hide(ops)` 日志侧：穿球 swipe 只产生 **1 对** hide/show，隐藏 **357ms**
（`18:16:41.330 → .687`），与手势时长同量级。

### 4.3 无障碍丢失恢复

| 步骤 | 观测 |
|---|---|
| `settings put secure enabled_accessibility_services null` + `accessibility_enabled 0` | 4s 后窗口 `com.termux type=2038`，`mDrawState=HAS_DRAWN isVisible=true`（球还在） |
| 复权 | 8s 内变回 `type=2032` |

## 5. 复现命令

```bash
S=10CG1C1M1H0021Z; TOK=<从 /sdcard/dsh-token.txt 读>
adb -s $S forward tcp:3083 tcp:3083

# 连点压测（观察是否闪）
for i in $(seq 1 40); do
  curl -s -H "X-DSH-Token: $TOK" -H 'Content-Type: application/json' \
       -d "{\"type\":\"tap\",\"x\":$((200+i*15)),\"y\":$((400+i*30))}" \
       http://127.0.0.1:3083/gesture >/dev/null &
  adb -s $S shell dumpsys window windows | \
    awk '/Window #[0-9]+ Window\{[^}]*com\.termux type=/{f=1} f{print} f&&/isVisible=/{exit}' | \
    grep -oE "mDrawState=[A-Z_]+"
  sleep 0.1
done

# 注入压球（应当「不」展开）
curl -s -H "X-DSH-Token: $TOK" -H 'Content-Type: application/json' \
     -d '{"type":"tap","x":1000,"y":506}' http://127.0.0.1:3083/gesture
```

## 6. 残留风险 / 未覆盖

1. **2038 兜底期仍有窗口类型抖动**：a11y 被 OriginOS 反复撤销/复权时，会在 2032 ↔ 2038 间切换
   1–2 次（每次是一次 view 重建）。已把切换收敛到 20 秒窗口内，但极端抖动场景仍可见一次闪。
2. **本次终验只在 vivo 上做**（Redmi K40 当时未接入）。改动是纯路径级（注入→判定→隐藏），
   与设备无关；2.9.86 之前在 Redmi 上验证过同一套 `dumpsys` 判据，但 2.9.88 未在 Redmi 复测。
3. **节点级点击不避让**是设计选择：它天然不会被悬浮窗吞掉；但如果目标控件**被悬浮窗遮挡**
   且只看得到一小部分，点击仍可能落到悬浮窗上——这种情况走 `/gesture` 注入坐标才会触发避让。
4. `/open/*`（系统文件选择器等）仍整段避让，未做粒度化。

## 7. 版本轨迹

| 版本 | 内容 |
|---|---|
| 2.9.80 / 651 | Router 按 body 坐标判定避让（过渡方案） |
| 2.9.81–2.9.84 / 652–655 | a11y 丢失兜底（2038 复活）、类型收敛、keepalive 心跳补 show |
| 2.9.86 / 657 | **避让下沉到手势层**（本次核心修复） |
| 2.9.87 / 658 | working 卡死时 20s 强制切回 2032 |
| 2.9.88 / 659 | 手势层判定加诊断日志（`ops avoid skip: overlay=… gesture=…`） |
