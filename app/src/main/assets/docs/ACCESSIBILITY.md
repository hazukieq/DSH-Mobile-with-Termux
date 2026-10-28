# 无障碍控制：让 AI 真正会用你的手机

> 不是"模拟点击脚本"，而是一套**能看、能判断、能验证、能自我纠错**的手机操作能力：
> 读得懂屏幕、找得到目标、点得准、知道点没点中，失败会自己换路。

## 0. 授权状态：启用 ≠ 已连接

Android 的无障碍服务有两个独立状态（参考
[AccessibilityManager](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager)
的 `getEnabledAccessibilityServiceList`）：

| 状态 | 判定 | 含义 |
|---|---|---|
| 已启用 | `getEnabledAccessibilityServiceList(...)` 里有本包 | 系统设置里已授权 |
| 已连接 | `DshAccessibilityService.instance != null` | 服务进程已绑定，动作可用 |

两个都为 true 才能动作；只看「已连接」会把「授权过但绑定还没完成」（刚启动、被回收后重绑）误判成未授权。

- `/health` 的 `permissions.accessibility` 报**已启用**，另给 `accessibility_connected`；需要无障碍
  的端点进入前等最多 **1.5s** 完成绑定：仍未连接 → 503 `retryable: true`（稍后重试，持续出现就
  关/开一次服务）；未启用 → 503 `retryable: false`（去系统设置启用）。
- 设置页「无障碍（可选）」区分「已连接 / 已开启（未连接）/ 已授权但被系统停用 / 未授权」；首次连接
  成功会持久化 `granted_once`，force-stop/更新摘掉 secure 设置后仍报「曾授权、被停用」。
- **已连接但假活**：`/status` 报 connected 但 `rootInActiveWindow` 为 null、`/screen` 只有 `0nodes`；
  关一次再开即可恢复，排查顺序 `/status` → `/screen` 是否 0 nodes → 关/开服务。
- Android 规定 **force-stop 应用会同时禁用其无障碍服务**，需用户重新去系统设置开启，App 绕不过。

## 范围：只有「读 / 写别人的界面」才需要无障碍

无障碍不是 App 的开关，而是一组能力的前置条件。判断标准只有一条：

> 这个能力是否必须**读别的 App 的界面树 / 屏幕像素**、**往别的 App 注入手势/按键/文字**、
> 或**消费无障碍事件**？是 → 需要；否 → 不需要。

- **需要**：读屏（`/screen`、`/scene`、`/diff`、`/interference`）、等待（`/wait`、`/wait-text`、`/idle`）、
  点击/按键/输入/滚动（`/tap`、`/gesture`、`/key`、`/input`、`/scroll-*`、`/pick`、`/vision/tap|drag`）、
  无障碍截图与帧差（`/screenshot`、`/frame-*`、`/vision/crop|track`）、通知事件缓存（`/notif`），以及
  依赖它们的 `/batch`、`/act`、`/loop/run`；观察别的 App 是否在前台（`wait-app <其他包>`）也属于此类。
- **不需要**：启动/跳转 App（`/launch`、`/launch-intent`、`/open`、`/activity`）、等自己回到前台
  （`wait-app com.termux`，看的是自己的 Activity 生命周期）、自家窗口截图（`appshot`，PixelCopy）、
  分享与文件（`native share`、`/open/file|folder|share`）、媒体播放、通知、TTS、
  引擎/插件/PTY/运行时、记忆系统的元数据操作。无障碍关着时它们**照常工作**。

因此普通聊天、打开应用、分享文件、播放音频、后台引擎都不依赖无障碍；只有「AI 去操作别的 App」
才需要。关着时普通功能先照常做，a11y 那一步再请用户开启（`POST /a11y/enable` 打开系统设置）
或改走不需要 a11y 的路径（用户自己点、`/launch` 交接、`appshot` 截自己、`notify`/`say` 输出）。
完整分类与维护规则见 [A11Y-SCOPE.md](A11Y-SCOPE.md)（英文版 [A11Y-SCOPE.en.md](A11Y-SCOPE.en.md)）。

## 1. 能做什么

开启无障碍服务后，Agent 获得以下能力（全部无需 Root、无需 ADB）：

| 能力 | 说明 |
|---|---|
| **读屏** | 提取屏幕上的文本、控件类型、坐标与层级；纯图形界面自动回退到 **OCR** |
| **点击 / 输入** | 按文本、控件 id、坐标精确点击（支持点击前后断言）；按提示词/邻近标签定位输入框写入，不覆盖别的文字 |
| **滑动 / 滚动** | 方向、距离、时长可控；滚动查找目标、滚动收集列表内容 |
| **长按 / 双击 / 双指缩放 / 拖拽** | 覆盖长按菜单、双击进入、地图缩放、拖拽排序等交互 |
| **按键 / 等待** | 返回、Home、回车等系统按键；等待界面稳定、文本出现或页面加载完成（不是死等固定秒数） |
| **组合** | 重复 N 次、重复直到条件成立、条件分支（`when`）、返回栈回退、收集数据 |

启动 / Intent、分享、媒体、通知、TTS 这类设备动作**不需要**无障碍（见上一节），走普通的
`startActivity` / Intent / 系统服务路径；本节是「无障碍开启后额外获得的那部分」，不构成「必须先开
无障碍」的前提。这些能力由 **21 个动作原语**表达，一次下发给本地闭环执行器执行。

## 2. 快、稳、省

**快**：单步闭环约 **1.45s**（决策+执行+验证，`loop/run` 单步 `actMs=1132 verifyMs=69`）；verify
约 **5ms**（compact 快路径，约为完整 JSON dump 的 1/7）；OCR 链 **326–393ms**（截图节流 + 内容区
裁剪 + 单帧复用）；界面稳定等待 **355–1566ms**；帧差 p50 **~55ms**；场景记忆命中 **0ms**、零云端
调用。**fast mode** 只在**第一步**思考，长序列一次下发、本地执行完毕，不做固定 sleep 的空等。

**稳**：多层视觉互为兜底 —— 无障碍节点树（最快）→ OCR（节点树无文字时，如系统浏览器、Canvas
混合页面）→ 图标识别（无文字按钮）→ 帧差（只回答"画面变了没"，验证点击是否真的生效）。每一步
都验证：点击 → 验证目标状态 → 失败换路重试（换锚点/动作/路径），失败证据（截图、候选、错误
原因）交回决策层。AI 注入的手势会被识别并排除，不会误判"用户接管"；截图时临时隐藏悬浮窗；
滑动轨迹带弧度与轻微抖动，点击坐标带微小随机偏移。

**省**：一次下发、本地闭环执行，页面真正加载完成才唤起下一步，省掉往返与空等。

## 3. 和其他方案比

与「传统 Appium/UIAutomator 脚本」和「云手机 + 远程 Agent」相比：

- **部署**：前者要电脑 + adb + 测试框架，云手机要云端实例 + 网络，本方案手机上装个 App 即可，
  Root/ADB 都不需要（可选 Shizuku/Root 增强）。
- **适配与验证**：脚本写死选择器、界面一变就挂，云端靠模型看图、单步往返 1–3s；本方案读屏 + OCR +
  图标 + 帧差多层兜底，本地闭环约 1.45s/步，每步结构化验证、失败换路；**数据**上脚本在本地/CI、
  云手机把**界面与操作上传云端**，本方案全部在本机，只把文本摘要交给模型。

## 4. 安全边界

- **本机桥 token 门控**：能力桥只监听 `127.0.0.1:3083`，token 每次启动随机生成、只注入引擎环境，
  同设备第三方 App 无法调用（无 token 返回 `unauthorised`）。
- **权限三级**：普通（沙箱）/ Shizuku（adb 级）/ Root（全盘），能力未就绪的选项自动置灰；**su 闸门**：
  普通模式下 `engine/bin` 拦截 AI 子进程提权。**截图避让**：AI 截图时悬浮窗自动隐藏。

## 5. 某些 App 会拒绝无障碍注入

典型两个：**GitHub App**、**Google Play 商店的账号抽屉**。

- Android 14+ 允许 App 把界面标成 `accessibilityDataSensitive`（账号、支付、令牌），framework 只把
  这类节点树交给声明了 `android:isAccessibilityTool="true"` 的无障碍服务；未声明时 `dump` 只剩
  OCR，`dispatchGesture` 会**返回 true 但页面毫无反应**（静默被吞）。
- 服务已声明：`res/xml/dsh_accessibility_service.xml` 里有 `android:isAccessibilityTool="true"`，并补
  `flagRetrieveInteractiveWindows`（账号抽屉是独立 Dialog/PopupWindow）与 `flagIncludeNotImportantViews`。
  ⚠️ 该开关**只能在 XML 声明**（运行时 `isAccessibilityTool` 只读），改完必须把服务**关一次再开**。
- 兜底：`scr tap --shell <x> <y>`（`POST /tap {"via":"shell"}`）走 Shizuku/Root 的 `input tap`（uid
  2000 系统合成输入），不受应用层 `filterTouchesWhenObscured` 影响；没装时明确返回
  `via=shell requires Shizuku or root`，不会静默失败。

## 6. 通知栏（shade）与系统 UI

`dispatchGesture` 是合成手势，SystemUI 的 shade 是独立窗口，动画期间会丢弃合成事件——
坐标点得准也点不动。可用手段：

| 目的 | 用法 |
|---|---|
| 展开 / 收起 | `scr key expand-notifications` / `scr key dismiss-shade` |
| 点通知条目或按钮 | `scr tap-desc "文案"`、`scr tap-id`（先 `ACTION_CLICK`，失败才回退手势） |
| 判断是否在 shade / 兜底 | `/scene` 命中 `com.android.systemui` 时返回 `hint`；`scr tap --shell x y`（Shizuku/root 的 `input tap` 属可信注入） |

`tapNode()` 先试 `AccessibilityNodeInfo.ACTION_CLICK`，失败才注入手势；通道写进日志与 `/tap`
响应的 `via` 字段（`action-click` / `node` / `gesture`）。

## 7. OCR 坐标自校准：`scr calib`

OCR 的坐标活在**截图像素空间**，无障碍 bounds 活在**显示坐标空间**。两者在多数设备一致
（竖屏 `1080x2376`、横屏 `2376x1080`），但一旦出现缩放、裁剪、letterbox 或厂商截图缩放，
整屏点击坐标就会系统性偏移——读代码看不出来，只能量出来。

做法：把「无障碍节点里带文字的项」当真值，把「OCR 同名文本块」当观测，**逐轴最小二乘**拟合
`truth = s * obs + d`；`n >= 3` 且拟合后误差真的下降（MAE < 0.9×原值且 < 24px）才落库。
参数按**截图像素尺寸**分桶，只在对应桶存在时生效，没有参数时映射恒等。

```bash
scr calib          # 只测量：配对数、sx/sy/dx/dy、校正前后 MAE
scr calib --apply  # 测量并写库（scr calib --clear 清空）
scr calib --list   # 看已存的分桶参数
```

`/ocr` 与 `/scene` 返回的 OCR 坐标会自动经过该映射。

## 8. `ownOverlay`：自家悬浮窗命中与绕行

`ownOverlay:true` 表示落点命中本 App 的无障碍悬浮窗。**是否会被吞取决于落点性质**：

| 落点 | 谁在处理触摸 | 表现 | 处置 |
| --- | --- | --- | --- |
| 自家悬浮窗的**可交互控件**（胶囊、工具条按钮） | 本 App 的 View | 正常响应 | 直接用坐标手势 |
| 自家悬浮窗的**透明区域**，下方是第三方 App | 悬浮窗吃掉且不响应 | 手势被吞、页面无变化 | 改用节点动作，或先收起悬浮窗 |
| 第三方 App 普通区域 | 第三方 App | 正常 | 直接用坐标手势 |

三步判定：① 点完立刻 `scr changed`，`{"changed":false}` 就是没生效；② 读回执的 `ownOverlay` /
`window.all` / `staleFullScreenNode` —— `window.all` 里同时出现 `com.termux/TYPE_ACCESSIBILITY_OVERLAY`
与前台 App 就优先怀疑自家悬浮窗；③ 绕行（按代价从低到高）：节点语义
`POST /act {"steps":[{"tap":"<屏上文字>"}]}`、收起悬浮窗 `POST /overlay {"show":false}` /
`scr status-hide`、自家悬浮条/球上的按钮用 `scr status --action` 或 `POST /overlay`。

注意：屏幕上看得见的字多是 TextView（文本节点），按 `tap-desc` 可能报 `target not found`，
要按 text 匹配或直接点容器中心。

## 9. 坐标 tap 的语义

**结论：`/tap {x,y}` 的语义是「屏幕像素」，默认只发合成手势（dispatchGesture），不做节点语义
替换。** 需要节点语义时显式传 `{"via":"auto"}`（`tapSmart`），此时有两道护栏：面积 ≥60% 屏的节点
（关掉侧边栏后残留的 `[0,0,W,H]` 全屏层）不能作为点击目标，命中这类节点时自动退回合成手势，
`via` 回执里能看到实际用的是什么。`/tap` 的坐标回执带自证信息：

```json
{"ok":true,"dispatched":true,"via":"gesture","sent":{"x":540,"y":804},
 "dispatchedAt":{"x":541,"y":803},"shot":"…/step-3.png",
 "staleFullScreenNode":"关闭侧边栏","spaceHint":"…"}
```

- `sent` 是调用方给的坐标，`dispatchedAt` 是抖动/边界钳制后真正派发的点（差分大说明坐标被改写过）；
  `staleFullScreenNode` 存在时该窗口的 a11y 几何不可信，坐标请从截图上量；`ownOverlay:true` 只说明
  落点在本 App 的无障碍悬浮窗上，**不等于一定被吞**（见 §8）。

### 9.1 能力矩阵

| 窗口类型 | 坐标手势 | 节点动作 (tap-desc/tap-id) |
| --- | --- | --- |
| 普通第三方 App（GitHub / 酷狗 / 设置 / B站）、SystemUI 通知栏 shade | ✅ | ✅ |
| DSH 悬浮窗上的**可交互控件**（胶囊、工具条按钮） | ✅ | ✅ |
| DSH 悬浮窗的**透明区域**（下方是第三方 App） | ❌（被悬浮窗吃掉、我们又不响应） | ✅（改节点点击或先收起悬浮窗） |
| 存在残留全屏节点的窗口 | ✅（手势不受影响） | ⚠️ 中心坐标可能偏，改用截图坐标 |

### 9.2 统一分发与窗口诊断

`POST /act` 的 `{"tapXY":[x,y]}` 与 `POST /tap {x,y}` 共用同一套 `tapDispatch`：默认合成手势（jitter≤4px），
`{"smart":true}` 或 `/tap {"via":"auto"}` 才走节点语义。两者回执字段一致：

| 字段 | 含义 |
| --- | --- |
| `via` | `gesture` / `action-click` / `shell` / `list` / `failed`；`via` 为节点语义时另有 `node` 命中标签 |
| `sent` / `dispatchedAt` | 请求坐标 / **实际**落点（含抖动，已夹取屏内） |
| `window.target` / `window.all` | 落点所属窗口（优先前台 App 的 `window/type/top/left/area`）/ 所有包含该点的窗口，按面积升序（系统 UI 窗口也可能是全屏，勿只看面积） |
| `staleFullScreenNode` | 当前页存在面积 ≥ 全屏的 clickable 残留节点时给出其标签 |
| `ownOverlay` | 落点在本 App 无障碍悬浮窗上；是否被吞看落点性质（§8） |

## 10. 边界（请知悉）

- **纯 Canvas/游戏画面**：没有可读文本时只能靠 OCR 与视觉定位，复杂画面仍可能失手；**无文字且无
  标准图标的控件**需要视觉模型辅助，默认不启用。
- **系统级权限弹窗**：仍需你手动点「允许」；**部分应用的防自动化检测**：不保证 100% 通过（也不做
  规避检测的对抗）。

## 11. 两条执行纪律：回执必读 · 坐标来源

### 11.1 回执必读

- 任何 bridge 调用（`/act`、`/gesture`、`/tap`、`/share`、`/launch`、`/open` …）都必须立即读回执，
  检查 `ok` / `error` / `verified` / `changed` / `opened` / `handoff` / `ownOverlay` / `window`。
- `ok:false`、`verified:false`、`changed:false`、`opened:false` 一律按失败处理，不得当成成功。
- 禁止 `command >/dev/null` 静默丢弃回执；需要精简时用摘要封装（例如 `actq`）。
- `handoff:true` 的动作（share / open / launch）不得重试，重试会产生重复副作用。

### 11.2 坐标来源

- 坐标只能来自当前屏幕的 `/scene` 或 `/screen?format=json` 的 `bounds`/`center`，或上一次成功
  点击的回执；**禁止目测、按比例估算、复用旧页面坐标**。
- 截图预览（如 581×1280）与真实屏幕（1080×2376）比例不同，禁止在预览图上手算坐标。
- 需要系统级注入时才用 `scr tap --shell`（Shizuku/Root）；普通点击优先节点语义或 `/act` 事务。
