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
   - 截图/读屏避让：改成 `root.visibility = GONE`（不参与绘制、命中测试、无障碍树，窗口不动），结束后 VISIBLE。
   - 档位切换：只 `safeUpdateLayout(wm, view, params)` + `requestLayout()`。
   - 例外只有一种：窗口首次创建（`show()`）时 `addView` 一次。
8. **窗口初值必须是「折叠球尺寸」**：用 `WRAP_CONTENT` 初始化时，首帧会按 XML 自然尺寸（实测 178×79 横条）画出来再变成 99×99 → 每次显示都闪一下。
9. **展开态整张卡片可点，但正文滚动优先**：`root / statusRow / statusTv / statusScroll / footRow / a11yText / ballDot / elapsedText` 都挂 `collapseToBall()`；ScrollView 仍负责滚动（不滚动的点击视为「收起」）。不要为了「只让图标可点」而把其它子 View 的 clickable 关掉——用户会点空白处然后说「点不动」。

### 3.x 两条硬不变量（2026-11 踩过）

- **`overlayScroll` 的 `weight` 绝不能被清零。** 它的 `layout_width=0dp + layout_weight=1` 靠横向权重撑开；
  任何「顺手把 weight 归零」的写法都会让文字宽度变成 0 —— 卡片照画、圆点也在，但**文字全被裁掉**，
  表现就是「展开态文本不显示」。只允许改 `height`。
- **空闲态与工作态同宽同高**（240dp × 92dp 滚动区 + 底行）。不做「空闲自动缩成一行」的适配：
  那会让卡片在空闲/工作之间跳变，用户会读成「它自己折叠了」。



### 3.y 截图 / 读屏时怎么让悬浮窗不出现在图里（**2.9.72 及以前**：只隐藏、不摘窗口）

> **2.9.75 起重写，见 [§3.s](#3s-显式截图彻底不闪2-9-75--646takeScreenshotOfWindow-组合窗口)。**
> 本节保留作为**回退路径**与历史根因记录：`INVISIBLE` 会真的销毁 surface（闪 2.3s + 期间点不到），
> 只在 API<34 / 窗口截图不可用时才会用到。

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

### 3.s 显式截图彻底不闪（2.9.75 / 646：takeScreenshotOfWindow 组合窗口）

**核心事实**：`root.visibility = INVISIBLE` 对 2032/2038 窗口不是「不画」，而是让 WMS 走
`performSurfacePlacement` → `WindowStateAnimator` 的 destroy 路径（`mDrawState: HAS_DRAWN →
NO_SURFACE`，layer 从 SF 摘掉、BufferQueue 释放）。恢复时重建 layer **必然闪**，重建期间窗口
**不可触摸**。所以「藏起来再拍」这条路本身就是错的方向，只能让**截图不含悬浮窗**。

实现（`DshAccessibilityService.kt`）：

| 函数 | 作用 |
|---|---|
| `screenshotCompositeBase64()` | **首选**。`windows` 按 `layer` 升序取 ≤6 个窗口，逐个 `takeScreenshotOfWindow(id, exec, cb)`，按 `getBoundsInScreen()` 贴回 `realScreenSize()` 画布 |
| `screenshotWindowBase64()` | 退路一：只截单个前台应用窗口 |
| `screenshotBase64(avoidOverlay=true)` | 退路二（会闪）：整屏 + `INVISIBLE` 避让 |
| `screenshotBase64NoFlash()` | 上面三条的编排：composite → window → display+avoid，`lastShotVia` 记录实际路径 |
| `realScreenSize()` | `WindowManager.maximumWindowMetrics`；**不要用 `resources.displayMetrics`**（服务里是去导航栏的可用区，1080x2400 报 1080x2305，坐标差 95px） |

三条硬规则：

1. **排除自己的悬浮窗只能按 bounds**：不能按包名（主 WebView 也是 `com.termux`），也不能按
   a11y `type`（application overlay 在 a11y 里报 `TYPE_SYSTEM`，与状态栏撞型）。用
   `StatusOverlay.currentFrameRect()` 逐边比对（±2px）。
2. **窗口本地坐标 ≠ 屏幕坐标**：窗口截图给的是窗口 buffer（CTS 断言等于 decorView 尺寸），
   必须加 `boundsInScreen` 平移才能当整屏图用，否则点击整体偏移。
3. **节流按 windowId 分桶**（AOSP `AccessibilityInteractionController`，窗口版 `SparseArray`、
   display 版单字段），所以一次合成里连拍多个窗口**不会**互相消耗 333ms 额度；
   但「相邻两次同窗截图」仍要 ≥333ms。

验收（`scripts/probes/flicker/capture.sh` 四路判据 + `(e) RECREATED`）：

```
composite（新）: 663 采样点 全 OK；GONE 0 / NO_CONTENT 0 / RECREATED 0
?flash=1（旧）: GONE 205 / NO_CONTENT 3 / RECREATED 10
```

**别再做**：用 `INVISIBLE`/`GONE`/`removeView`/`alpha` 去「让截图里没有悬浮窗」——
都会闪，且期间点不到。`?flash=1` 只作为逃生舱与回归负对照保留。

**窗口类型必须收敛到 2032（2.9.76）**：App 先起、无障碍后连时，窗口是按
`TYPE_APPLICATION_OVERLAY`(2038)+Activity token 建的，这种窗口 App 一退后台就被 WMS 置
hidden（小米还叠 `mIsForceHiddenNonSystemOverlayWindow`），表现「切个 App 就没了」。所以
`show(svc)` 在无障碍就绪时会把 2038 热切换成 2032，并保留坐标/展开档位：

```
I/StatusOverlay: overlay type switch: 2038 → TYPE_ACCESSIBILITY_OVERLAY (a11y ready)
```

排查时注意：`dumpsys window windows` 里两者的名字不同（2032 为 `Window{… u0 com.termux}` 且
`appop=CREATE_ACCESSIBILITY_OVERLAY`；2038 为 `Sys2038:com.termux/…` 且
`appop=SYSTEM_ALERT_WINDOW`），判据不要只看 `ty=`。

### 3.q 2.9.86 / 2.9.88：避让下沉到「手势层」——只有轨迹真的压到悬浮窗才隐藏

**为什么改**：在此之前避让是**请求级**的（`Router.route` 里 `POST /act` 就 `beginOpsAvoid`）。
真机（vivo V2520A / OriginOS 17 / Android 17，无 root）实测：agent 连续操作时悬浮窗以
**~3 Hz** 在 `INVISIBLE ↔ VISIBLE` 之间切换，20 秒采样 40 次里 **14 次 `mDrawState=NO_SURFACE`
（35% 占空比）**——用户嘴里的「不断闪烁」和「有时点不到」就是同一件事：那 35% 的时间里
窗口在退场重建，触摸无处可落。

**怎么改**（v2.9.86）：

| 层 | 旧 | 新 |
|---|---|---|
| `Router.route` | `POST /act` → 无条件 `beginOpsAvoid()` | **请求级避让整条删除**（2.9.91）：正常请求一律不碰悬浮窗可见性，只剩手势层 + 显式 `"avoid":true`。见 §3.p |
| `DshAccessibilityService` | 直接 `dispatchGesture(...)` | 统一走 `dispatchGestureAvoided(...)`：`gestureBounds()`（所有 stroke 的 `path.computeBounds` 并集，±2px）→ `StatusOverlay.beginOpsAvoidIfHit(bounds)` → 相交才 `beginOpsAvoid()`，手势回调（或 1200ms 兜底）里 `endOpsAvoid()` |

关键点：

1. **`AccessibilityService.dispatchGesture` 是 `final`，不能被 override**（编译报错
   `'dispatchGesture' overrides nothing`）。所以是在类内做了私有助手，替换 6 个注入点
   （`dispatchTap` / 双击 / `dispatchSwipe` / `dispatchSwipeHuman` / `dispatchLongPress` /
   `dispatchPinch`），而不是拦截基类。
2. **为什么必须在手势层判定**：`/pick`、`/tap-id`、`/scroll-*` 的目标坐标是在 handler
   内部按节点解析的，Router 拿到 body 时根本不知道会点到哪；只有到 `dispatchGesture`
   这一刻坐标才是确定的。
3. **无障碍节点级点击（`ACTION_CLICK`）根本不需要避让**——它不经过输入通道，不会被
   悬浮窗吞掉。旧逻辑把它们也一起避让了，纯属自伤。
4. **真实手指完全不受影响**：避让只发生在「a11y 注入的手势」路径上，
   `input tap` / 用户手指仍会正常命中悬浮窗（实测：真实点击球 → 展开成 720px 卡片）。

**实测（vivo 2.9.88 / 659）**：

| 场景 | 结果 |
|---|---|
| agent 式连点 40 次（全部远离球） | 窗口不可见 **0/40**（旧版同场景 14/40 = 35%） |
| 注入 tap 压在球上 `(1000,506)` | 球**不展开**（避让生效，点击穿透到底层 App） |
| 注入 swipe 穿过球 `700,506 → 1050,506` | 不展开、不移动；`hide(ops)` 仅 1 对，隐藏 357ms |
| 真实手指点球 / 点圆点收起 | 正常展开 720px / 正常收回 108px |

**硬规则（新增截图/注入代码必须遵守）**：
- 任何「注入输入」的新入口都必须走 `dispatchGestureAvoided`，禁止直接调 `dispatchGesture`；
- 禁止在 `Router` 里按「路径是不是 /act」这类**请求级**条件做避让；
- 避让时长必须由手势结束（`onCompleted/onCancelled`）决定，兜底 timer 只用于异常路径。

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

### 3.aa 2.9.95「悬浮窗点不开」：档位与渲染态脱钩 + 展开卡片漏锚定（vivo 实测定案）

- 现象（用户原话「现在我只有一个问题：悬浮窗点不开了」）：球看得见、点下去 `handle touch` 日志也有，
  但毫无反应；偶发时展开卡片只露出屏幕右缘一条竖边，点哪儿都没用。
- 根因①**档位与视图各说各话**：`expandLevel` 是唯一档位真源，但窗口重建（hide→show、
  2032↔2038 类型切换）时会用 `rememberedLevel` 把它恢复成 1，而新视图是按**折叠态** inflate 的；
  `bindHandle()` 又按 `expandLevel > 0` 把 root 的 onClick 绑成空实现，`expandPanel()` 首行还是
  `if (expandLevel == 1) return` ⇒ 点击要么落在空 onClick、要么被早退吞掉 = **点不开**。
- 根因②**展开卡片漏锚定**：`applyExpandLevel()` 只在 `lastAppliedLevel != expandLevel` 时锚定，
  重建后两者已相等 ⇒ 不锚定；新建窗口的 `x` 用的是 `pendingPos`（球坐标 948），展开宽度 720
  ⇒ 588px 在屏幕外，用户只看到一条边。
- 修复（4 条，`visibility` + 引用计数机制不变）：
  1. `expandPanel()` 早退判据改成**渲染态**（`statusRow?.visibility == View.VISIBLE`），并置
     `lastAppliedLevel = -1` 强制重算锚定；
  2. 展开态 root 的 onClick 从空实现改成自愈：`if (statusRow?.visibility != View.VISIBLE) expandPanel()`；
  3. `applyExpandLevel()` 去掉 `lastAppliedLevel != expandLevel` 门控 —— 每次应用档位都
     `postOnAnimation { anchorToEdge() }`；
  4. `anchorToEdge()` 兜底夹取 `x ∈ [margin, screenW - w - margin]`（`w` 取 `prm.width → v.width → 档位默认宽`）。
- 真机验证（vivo 10CG1C1M1H0021Z / 2.9.95 / versionCode 666）：

| 动作 | 期望 | 实测 |
|---|---|---|
| 点球 | 展开 720 宽、完整在屏内 | `level=1 pos=336,360 size=720x-2`，连做 3 轮一致 |
| 点圆点 | 收起成 108 球、贴右 | `level=0 pos=948,360 size=108x108` |
| 展开态点正文 | 不收起 | `level=1` 不变 |
| 拖动球 | 跟随并吸附到最近边缘 | `level=0 pos=948,768 size=108x108` |
| 展开态撤销/恢复无障碍（2032→2038→2032，每次重建窗口） | 仍展开且仍在屏内 | 三次重建全部 `pos=336,360 size=720x-2` |
| 截图（composite 免闪路径） | 不隐藏、不进画面 | 8/8 采样 `HAS_DRAWN isVisible=true`；PNG 球区域均值 247（无球） |

- 两个排查时必须记住的坐标事实：
  - vivo 上悬浮窗 `params.y` 与屏幕像素 y 相差 **92px**（`Frames: parent=[0,92][1080,2376]`），
    `adb shell input tap` 要用视觉坐标（= `params.y + 92`）。
  - `debugState()` 的 `level` 读的是 `expandLevel` 字段，**不代表"现在长什么样"**；判断渲染态看
    `statusRow.visibility` 或直接截图。

### 3.ab 2.9.104/2.9.105「App 彻底销毁后悬浮窗还在」：退出语义必须闭环（vivo 实测定案）

- 现象（用户原话「app彻底销毁后，悬浮窗还在」）：从通知栏点「退出」/把 App 从最近任务划掉后，
  通知没了、引擎也停了，但**过一分钟左右悬浮球又自己冒出来**；再打开 App 时球反而可能不见。
- 为什么进程死了却没有窗口泄漏：`am force-stop`、划掉任务都会让进程退出，WMS 随进程销毁窗口
  （实测 `dumpsys window windows | grep com.termux` 为 0 条）——**泄漏只发生在进程还活着的退出路径**：
  无障碍服务被系统绑着，App 进程不会被杀，于是保活心跳仍会去 `show()`。
- 根因（两处，缺一不可）：
  1. `DshAccessibilityService.startKeepAliveWatchdog()` 的 60s 心跳**无条件**重启引擎 +
     `StatusOverlay.show()`，不看 `EngineService.userExited`；
  2. `MainActivity.onDestroy()`（`isFinishing`）会 `StatusOverlay.hideAuto()`，而它把 `hidden`
     **复位成 false**（本意"下次能自动显示"），正好解开 `teardown()` 设的抑制 ⇒ 随后任何 `show()` 都能重建窗口。
- 修复：
  1. 心跳先判 `EngineService.userExited()`，为真则只记一条
     `keepalive: user exited, skip engine restart + overlay re-show` 并跳过引擎与悬浮窗；
  2. `StatusOverlay.startOverlay()` 入口再兜一层：`userExited` 为真直接 `return`
     （日志 `overlay suppressed: user exited`），杜绝任何通路把窗口挂回去；
  3. 新增 `hiddenByExit` 标志 + `resumeAfterExit()`：区分「退出抑制」与「设置里关掉悬浮窗」，
     只有前者会在用户重新打开 App 时恢复（`MainActivity` 清 `userExited` 的两处调用），
     日志 `overlay resumed after user exited`。
- 真机验证（vivo 10CG1C1M1H0021Z / 2.9.105 / versionCode 676）：

| 步骤 | 期望 | 实测 |
|---|---|---|
| 通知栏点「退出」 | 引擎停、窗口拆掉 | `engineRunning=false`、`overlay root=null live=0` |
| 静置 70s（跨过 60s 心跳） | 球不再出现 | 仍 `root=null`；日志 `keepalive: user exited, skip engine restart + overlay re-show` |
| 重新打开 App | 引擎重启、球立刻回来 | `engineRunning=true`、`root!=null pos=948,360 size=108x108`；日志 `overlay resumed after user exited` |

- 两条硬规则（改保活/退出相关代码前先读）：
  - **a11y 保活通路必须尊重 `userExited`**：无障碍被系统绑着 ≠ 用户还想让 App 活着；
  - **两种 hidden 要分开**：退出抑制（可自动恢复）vs 设置开关关闭（用户显式选择，不得自动恢复）。

### 3.ac 2.9.106：长按隐藏已移除（用户要求）

- 决策：悬浮球「长按 = 隐藏」是过时功能，且是误触把球弄丢的主要来源（用户原话「彻底移除…根本不需要」）。
- 改动：`StatusOverlay.attachGesture()` 删掉 `onLongPress` 形参、`bindHandle()` 两处调用不再传长按、
  折叠态/展开态都不再 `setOnLongClickListener`；`hideInternal()` 的日志改为
  `overlay hidden (sticky)` / `overlay hidden (auto)`（不再有 "by user" 语义）。
- 现在还能让悬浮窗消失的入口（仅两个，都是显式而非手势）：
  1. 设置 → 「显示悬浮窗」开关关掉 → `DshAccessibilityService.setOverlayEnabled(false)` → `hide(svc)`；
  2. 退出 App（通知栏「退出」/划掉任务）→ 引擎退出 → `teardown()`，见 §3.ab。
- 回归清单新增一条：**长按不再隐藏窗口**（`input swipe x y x y 1500` 模拟长按，窗口应保持可见）。

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

### 3.u 2.9.72：高频消费者不再避让 + 引用计数加固（真机取证定案）

真机 root 取证（详见 `docs/qa/flicker-fix-report.md`）把两件事钉死了：

1. **`INVISIBLE` 就是「窗口退场」，不是「不绘制」。** 避让期间 `dumpsys window windows` 里该 2032 窗口
   连续样本是 `Surface: shown=false mDrawState=NO_SURFACE`，恢复后回到 `HAS_DRAWN`——
   对应 `WindowManagerService → WindowStateAnimator.java:707-721` 的 `t.remove(mSurfaceControl)` +
   `setHasSurface(false)`。**所以任何一次隐藏都会销毁并重建 surface，必然闪一次。**
   推论：闪多少次 = 隐藏多少次，唯一解法是**少隐藏**。
2. **隐藏期间点不到是官方语义**（Android 12 untrusted-touch 例外表：根 view GONE/INVISIBLE 的窗口
   触摸放行穿透），再叠 `InputMonitor` 输入表的异步刷新。**不隐藏的时间占比 = 能点到的概率。**

定下的分工（改代码必须遵守）：

| 消费者 | 是否避让 | 理由 |
|---|---|---|
| `screenshotArgb(Framer / 帧轮询 / verify / settle)` | ❌ **不避让** | 只要「变没变」；悬浮窗矩形用 `Framer.maskOverlayRegion()` 抹平 |
| `ScreenOcr` / prefetch / dump 图标识别 | ❌ **不避让** | 只要文字/图标；落在悬浮窗矩形里的结果直接剔掉 |
| `screenshotBase64`（显式 `/screenshot`、视觉模型） | ✅ 避让 | 需要干净画面给用户/模型看 |
| `beginOpsAvoidIfHit`（手势层，`dispatchGestureAvoided` 调用） | ✅ 仅当注入轨迹与窗口相交 | 注入手势需要触摸穿透；不在轨迹上的点击一律不藏 |
| `beginOpsAvoid`（显式 `"avoid":true` 或 `/act` 的 `hideOverlay:true`） | ✅ 调用方自选 | 批量动作期间怕被自己的卡片吃掉手势 |

计数加固（否则「球没了 / 再也点不到」）：

- `hideRefs` 读写全部走 `refLock`（原来裸 `+=` 在多线程下是竞态）；
- `hideNow/showNow` 检查 `mainSync` 是否真执行、是否真生效（`root` 为空 / 主线程 800ms 超时），
  **未生效就回滚计数**，不让计数与真实可见性脱钩；
- `hideInternal`（摘窗口）必须把 `hideRefs` 归零，否则之后每个 `showNow()` 只减到 1 就 return；
- `reassertVisible()` 在 `hideRefs > 0` 时必须让位（ProgressHub 每 120ms 调一次，会把避让硬捞回来）。

实测（643）：静置 / prefetch 高压 / `frame-wait` 3 轮 / `/ocr` 四条路径 **0 次窗口状态跳变、0 次无谓隐藏**；
仅显式 `/screenshot`（1 次，2.32s，截图成品中悬浮窗区域为纯白、标准差 0）与 `/act`（1 次，0.72s）各隐藏一次。

### 3.r 2.9.79：连 OCR/校准类截图也免闪 —— 「需要干净画面」不再等于「藏悬浮窗」

2.9.79 之前剩下的闪点只有一个来源：**显式截图类调用**还在用 `screenshotBase64()`（默认
`avoidOverlay=true` → 隐藏 2.3s）。现在显式截图默认走 `screenshotBase64NoFlash()`
（composite：`takeScreenshotOfWindow` 逐窗合成，见 §3.s），只有 `?flash=1` 才回旧路。

调用点清单（`bridge/ScreenHandler.kt`）：

| 位置 | 用途 | 现状 |
|---|---|---|
| `:1125` 附近 | `/screenshot` 默认 | `screenshotBase64NoFlash()`（composite） |
| `:1127` | `/screenshot?flash=1`（负对照/需要完整画面） | `screenshotBase64()` —— **唯一的避让入口** |
| `:337` | `/calib`（OCR 框 ↔ a11y 真值对齐） | `screenshotBase64NoFlash()` |
| `:936` | `/ocr?engine=ppocr6` 主路径 | `screenshotBase64NoFlash()` |
| `:1279` | 点击链路（`/act` 里的 OCR 定位） | `screenshotBase64NoFlash()` |

**硬规则**：新增任何「截一张图给 OCR/模型看」的代码，一律用 `screenshotBase64NoFlash()`；
只有「要拿干净画面做像素级证据」这一种场景才允许 `?flash=1` 那种避让路径。

实测（650，每次操作期间 10 次 `dumpsys window windows` 采样）：

| 操作 | 悬浮窗可见 |
|---|---|
| `/screenshot`（composite） | 10/10 `HAS_DRAWN` |
| `/screenshot?flash=1`（负对照） | 1/10（9 次 `NO_SURFACE`） |
| `/ocr?engine=ppocr6`（native PP-OCR，改前会隐藏 2.3s） | **10/10** |
| `/ocr`（ML Kit） | 10/10 |
| `/calib` | 12/12 |

四路判据边采样边跑 3 次 native OCR：**475 采样点全 OK**（GONE/NO_CONTENT/MOVED/STALE/RECREATED 全 0）。

## §3.p 端点语义：谁能碰悬浮窗可见性（2.9.91 / 662）

> **这一节是唯一的权威表。** 改 Router / 加端点前先看它，不要凭端点名字猜。

### 事故：两次踩同一个坑

| 版本 | 错误 | 真机表现 |
|---|---|---|
| 2.9.72 及以前 | `POST /act` 无差别 `beginOpsAvoid()` | agent 连续操作 = 3Hz 闪烁，占空比 35%（40 采样 14 次不可见） |
| 2.9.90 及以前 | `/overlay` 也在避让表里 | `/overlay` 是 **agent 的思考进度推送**（`ActionHandler.overlay` → `ProgressHub.update` / `setA11yAction`），思考时每 ~250ms 一条 ⇒ 每条进度都把窗口 INVISIBLE 一次：30s 内 1154 次 `ops avoid: /overlay`、1159 次 `hide(ops)` |

两次都不是「避让机制坏了」，而是**把不该避让的端点塞进了避让表**。更难看的是：`/overlay` 这件事
在 `docs/A11Y-SCOPE.md` §5 里写着「更新/显示进度条不依赖 a11y」——文档早就说清它是进度通道，
代码里的路径级避让表却把它当动作端点，两边各说各话，没人对账。

### 现在的规则（2.9.91 起，机制已删除）

**代码里不再存在「路径级避让表」**——`OPS_AVOID_PATHS` 整条删除（不是清空成空集，留着空表
就会有人「顺手补两个」）。避让只剩两条通道：

1. **手势层**：`DshAccessibilityService.dispatchGestureAvoided` → `StatusOverlay.beginOpsAvoidIfHit(bounds)`
   —— 注入的触摸轨迹与悬浮窗矩形相交才藏，抬手立刻恢复。
2. **显式 opt-in**：请求体带 `"avoid": true`（`Router.explicitAvoid`），或 `/act` 带 `"hideOverlay": true`。

判断一个端点会不会碰可见性，只问一句：**它注入触摸吗？**

| 端点 | 性质 | 避让 |
|---|---|---|
| `/act` `/gesture` `/tap` `/tap-id` `/key` `/input` `/scroll-*` `/vision/tap` `/vision/drag` `/pick` `/loop/run` | 注入触摸 | **手势层判**（轨迹相交才藏） |
| `/overlay` | **思考进度推送**（250ms 级，高频） | **绝不** |
| `/screenshot` `/ocr` `/frame-wait` `/frame-diff` `/calib` | 截图/识别，已走免闪 composite（§3.s） | **绝不** |
| `/open/file` `/open/folder` `/open/share` `/launch` `/notify` `/say` | hand-off / 提示 | **绝不**（请求内藏几十 ms 毫无意义） |

### 验证（662，vivo 10CG1C1M1H0021Z）

| 场景 | 采样 | 隐藏 |
|---|---|---|
| `/overlay` 进度推送 ×40（250ms 间隔） | 40 | **0** |
| `/overlay` 结构化 `detail` 推送 ×24 | 24 | **0** |
| `/screenshot` / `/ocr` | 10 / 10 | 0 / 0 |
| 注入 tap 压在球上 `(1000,506)` | — | 球仍收起（避让 + 穿透生效） |

`ops avoid` 与 `hide(ops)` 计数均为 **0**（2.9.90 之前同场景 1154 / 1159）。

### 机械闸门（2.9.91 起）

只靠文档约束不够 —— 上两次事故都是「有人凭端点名字猜」。所以加了形状闸门：

```bash
python3 scripts/check-overlay-avoid.py        # 0 = 通过；1 = 检出路径级避让
python3 scripts/check-overlay-avoid.py --selftest
```

它不看端点名字，只看三种形状（`app/src/main/java/**/*.kt`）：

| 规则 | 形状 |
|---|---|
| R1 `PATH_SET_DECL` | 名字含 avoid/hide/ops/overlay 的集合字面量里放了 `"/..."` 路径串 |
| R2 `PATH_GATED_HIDE` | `path in / == / != / startsWith` 条件 3 行内出现隐藏调用 |
| R3 `METHOD_GATED_HIDE` | `method == "POST"` 条件 3 行内出现隐藏调用 |

例外需显式写 `// overlay-gate: allow <理由>`（并回本文件记录原因）。已接进
`bash scripts/acceptance.sh --static`。

**回归证明**（拿历史版本当负样本）：

| 版本 | 命中 |
|---|---|
| 2.9.88 / 659（`OPS_AVOID_PATHS = setOf("/overlay", …)`） | R1 + R2 两处 |
| 2.9.90 / 661（清成空集但保留机制） | R2 一处 |
| 2.9.91 / 662（机制删除） | 0 |

