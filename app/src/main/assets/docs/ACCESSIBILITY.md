# 无障碍控制：让 AI 真正会用你的手机

> 不是"模拟点击脚本"，而是一套**能看、能判断、能验证、能自我纠错**的手机操作能力：
> 读得懂屏幕、找得到目标、点得准、知道点没点中，失败会自己换路。

## 0. 授权状态：启用 ≠ 已连接（v1.7.4）

Android 的无障碍服务有两个独立状态（参考
[AccessibilityManager](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager)
的 `getEnabledAccessibilityServiceList`）：

| 状态 | 判定 | 含义 |
|---|---|---|
| 已启用 | `AccessibilityManager.getEnabledAccessibilityServiceList(...)` 里有本包 | 系统设置里已授权 |
| 已连接 | `DshAccessibilityService.instance != null` | 服务进程已绑定，动作可用 |

历史 bug：`/health` 与所有动作端点都用「已连接」当作「已启用」，于是**授权过但绑定还没完成**
（App 刚启动、服务被系统回收后重绑）时，agent 预检直接拿到 503，误以为没授权。

现在的行为：

- `/health` 的 `permissions.accessibility` 报**已启用**，另给 `accessibility_connected`；
- 需要无障碍的端点（读屏/动作/视觉/截图）在进入前等最多 **1.5s** 完成绑定，再决定：
  - 仍未连接 → 503，`retryable: true`，提示「已启用但未连接，稍后重试；持续出现就在系统设置里关/开一次」；
  - 未启用 → 503，`retryable: false`，提示去系统设置启用。
- 设置页「无障碍（可选）」区分「已连接 / 已开启（未连接）/ 已授权但被系统停用 / 未授权」；
  服务首次连接成功会持久化 `granted_once`，所以 force-stop/更新把 secure 设置摘掉后，仍能
  报出「曾授权、被系统停用」，而不是误报「从未授权」。

注意：Android 规定 **force-stop 应用会同时禁用其无障碍服务**，需要用户重新去系统设置开启；
这不是 App 能绕过的限制。

另一个实测状态（v1.8.7）：服务可能「已连接但假活」——`/status` 报
`accessibility_connected: true`，但 `rootInActiveWindow` 返回 null、`/screen` 只有
`0nodes`。此时在系统设置里把 DSH 辅助服务关一次再开即可恢复（实测 31 节点恢复）。
排查顺序：`/status` → `/screen` 是否 0 nodes → 关/开服务；不要盲目重装 APK。

## 范围：只有「读 / 写别人的界面」才需要无障碍

无障碍不是这个 App 的开关，而是**一组能力**的前置条件。判断标准只有一条：

> 这个能力是否必须**读别的 App 的界面树 / 屏幕像素**、**往别的 App 注入手势/按键/文字**、
> 或**消费无障碍事件**？是 → 需要；否 → 不需要。

- **需要**：读屏（`/screen`、`/scene`、`/diff`、`/interference`）、等待（`/wait`、`/wait-text`、
  `/idle`）、点击/按键/输入/滚动（`/tap`、`/gesture`、`/key`、`/input`、`/scroll-*`、`/pick`、
  `/vision/tap|drag`）、无障碍截图与帧差（`/screenshot`、`/frame-*`、`/vision/crop|track`）、
  通知事件缓存（`/notif`），以及依赖它们的 `/batch`、`/act`、`/loop/run`；观察别的 App
  是否在前台（`wait-app <其他包>`）也属于这一类。
- **不需要**：启动/跳转 App（`/launch`、`/launch-intent`、`/open`、`/activity`）、等自己回到前台
  （`wait-app com.termux`，看的是自己的 Activity 生命周期）、App 自己窗口的截图
  （`appshot`，PixelCopy）、分享与文件（`native share`、`/open/file|folder|share`）、媒体播放、
  通知、TTS、引擎/插件/PTY/运行时、记忆系统的元数据操作。无障碍关着时它们**照常工作**。

因此：**普通聊天、打开应用、分享文件、播放音频、后台引擎都不依赖无障碍**；只有「AI 去操作
别的 App」才需要。关着无障碍时，普通功能先照常做，a11y 那一步再请用户开启
（`POST /a11y/enable` 打开系统设置）或改走不需要 a11y 的路径（用户自己点、`/launch` 交接、
`appshot` 截自己、`notify`/`say` 输出）。

v2.5.2 的事故正是把 `/launch`、`/launch-intent`、`/open`、`/app-screenshot` 误判成需要无障碍，
导致关掉无障碍后「打开微信」也返回 503 —— 是门禁分类 bug，不是 Android 限制；已在 6fb665c
修复。完整分类与维护规则见 [A11Y-SCOPE.md](A11Y-SCOPE.md)（英文版
[A11Y-SCOPE.en.md](A11Y-SCOPE.en.md)）。

## 1. 能做什么

开启无障碍服务后，Agent 获得以下能力（全部无需 Root、无需 ADB）：

| 能力 | 说明 |
|---|---|
| **读屏** | 提取屏幕上的文本、控件类型、坐标与层级；纯图形界面自动回退到 **OCR** |
| **点击** | 按文本、按控件 id、按坐标精确点击；支持点击前后断言 |
| **输入** | 写入指定输入框（按提示词/邻近标签定位），不覆盖别的文字 |
| **滑动 / 滚动** | 方向、距离、时长可控；滚动查找目标、滚动收集列表内容 |
| **长按 / 双击 / 双指缩放 / 拖拽** | 覆盖长按菜单、双击进入、地图缩放、拖拽排序等交互 |
| **按键** | 返回、Home、回车等系统按键 |
| **等待** | 等待界面稳定、等待文本出现、等待页面加载完成（不是死等固定秒数） |
| **组合** | 重复 N 次、重复直到条件成立、条件分支（`when`）、返回栈回退、收集数据 |

启动 / Intent、分享、媒体、通知、TTS 这类设备动作**不需要**无障碍（见上一节），它们走普通的
`startActivity` / Intent / 系统服务路径；本节列的是「无障碍开启后额外获得的那部分」，不构成
「必须先开无障碍」的前提。

这些能力由 **21 个动作原语**表达，一次下发给本地闭环执行器执行 ——
不是"想一步、调一次云端、再想一步"。

## 2. 为什么快（实测数字）

| 环节 | 实测 | 说明 |
|---|---|---|
| **单步闭环** | **~1.45s / 步**（含决策+执行+验证） | 真机 `loop/run` 单步：`picked=设置中心 actMs=1132 verify=True verifyMs=69` |
| **验证（verify）** | **~5ms** | compact 快路径，体积约为完整 JSON dump 的 1/7 |
| **OCR 链** | **326–393ms**（原 1370–1588ms，**约 4 倍**） | 截图节流 + 内容区裁剪 + 单帧复用 |
| **界面稳定等待（settle）** | **355–1566ms**（按页面实际状态） | 加载信号分强弱；空树只走常规预算，不再每次白等 900ms |
| **帧差判断** | **p50 ~55ms**（含截图 IPC） | 64×64 降采样，一次比对 <5ms |
| **场景记忆命中** | **0ms，零云端调用** | 见过的界面直接复用上次决策 |

## 3. 为什么稳（看得见，也验得中）

**多层视觉，互为兜底**：

1. **无障碍节点树**：有标准控件的应用，直接拿到文本与坐标（最快）；
2. **OCR**：节点树拿不到文字时（如部分系统浏览器、Canvas 混合页面）自动补上；
3. **图标识别**：无文字的图标按钮（设置齿轮、返回箭头等）也能匹配；
4. **帧差**：只回答"画面变了没"，用来验证点击是否真的生效。

**每一步都验证**：点击 → 验证目标状态 → 失败则换路重试（换锚点、换动作、换路径），
并把失败证据（截图、候选列表、错误原因）交回给决策层，而不是盲目重试同一招。

**不抢你的操作**：AI 注入的手势会被识别并排除，误判"用户接管"不会发生；
截图时会临时隐藏悬浮窗，AI 看到的画面和你看到的一致。

**像人一样操作**：滑动轨迹带弧度与轻微抖动，不是机械直线；
点击坐标带微小随机偏移，避免固定点位特征。

## 4. 为什么省（不烧钱、不空转）

- **fast mode**：无障碍任务只在**第一步**思考，后续步骤不再付推理成本（长任务省下大头）；
- **一次下发、本地执行**：长序列在本地走完，不必每步一次云端往返；
- **页面状态检测**：真正加载完成才唤起下一步，不做固定 sleep 的空等；

## 5. 和其他方案比

| | 传统 Appium / UIAutomator 脚本 | 云手机 + 远程 Agent | **DSH Mobile** |
|---|---|---|---|
| 部署 | 电脑 + adb + 测试框架 | 云端实例 + 网络 | **手机上装个 App** |
| 需要 Root/ADB | 需要 | 需要 | **不需要**（可选 Shizuku/Root 增强） |
| 适配新界面 | 写死选择器，界面一变就挂 | 每次截图上传云端判断 | **读屏 + OCR + 图标 + 帧差多层兜底** |
| 单步延迟 | 依赖脚本执行 | 云端往返 1–3s | **本地闭环，实测 ~1.45s/步** |
| 验证 | 靠脚本断言 | 靠模型"看图" | **每步结构化验证 + 失败换路** |
| 数据 | 本地/CI | **界面与操作上传云端** | **全部在本机，只把文本摘要交给模型** |

## 6. 安全边界

- **本机桥 token 门控**：能力桥只监听 `127.0.0.1:3083`，token 每次启动随机生成、只注入引擎环境，
  同设备第三方 App 无法调用（实测无 token 返回 `unauthorised`）；
- **权限三级**：普通（沙箱）/ Shizuku（adb 级）/ Root（全盘），能力未就绪的选项自动置灰；
- **su 闸门**：普通模式下 `engine/bin` 拦截 AI 子进程提权；
- **截图避让**：AI 截图时悬浮窗自动隐藏，不把自己的 UI 当页面内容。

## 7. 实测案例

- **打开系统设置并定位目标项**：单步闭环 1.45s（含点击 1.13s、验证 69ms）；
- **多功能页面综合任务**：7 次调用 / 64.7s 完成（含滚动查找、到底检测、二次确认）；
- **噪声测试站 7 页**（滚动列表、延迟加载、图标无文字、弹窗遮挡、长列表滚动）：全部通过；
- **跨应用任务**（浏览器 → 设置 → 返回原应用）：验证"操作完必须返回应用"的约束可被稳定执行。

## 8.5 某些 App 会拒绝无障碍注入（v2.5.25）

实测两个典型：**GitHub App**、**Google Play 商店的账号抽屉**。

- Android 14+ 允许 App 把界面标成 `accessibilityDataSensitive`（账号、支付、令牌），
  framework 只把这类节点树交给声明了 `android:isAccessibilityTool="true"` 的无障碍服务；
  未声明时：`dump` 只剩 OCR（实测 GitHub App 从 `21 nodes / ocr=21 lines` 变成修好后的
  `38 nodes`，Play 商店同理能读到「查看任务 / 赞助商广告 / Grok Bot …」），
  而 `dispatchGesture` 会**返回 true 但页面毫无反应**（静默被吞）。
- 修法（已内置）：`app/src/main/res/xml/dsh_accessibility_service.xml` 里声明
  `android:isAccessibilityTool="true"`，并补 `flagRetrieveInteractiveWindows`
  （账号抽屉是独立 Dialog/PopupWindow）与 `flagIncludeNotImportantViews`。
  ⚠️ 这个开关**只能在 XML 声明**（运行时 `AccessibilityServiceInfo.isAccessibilityTool` 只读），
  改完必须去系统设置里把无障碍服务**关一次再开**，新 XML 才生效。
- 兜底通道：`scr tap --shell <x> <y>`（`POST /tap {"via":"shell"}`）走 Shizuku/Root 的
  `input tap`（uid 2000 系统合成输入），不受应用层 `filterTouchesWhenObscured` 影响。
  没装 Shizuku/Root 时会明确返回 `via=shell requires Shizuku or root`，不会静默失败。

## 8.6 OCR 坐标自校准：`scr calib`（v2.5.28）

OCR 的坐标活在**截图像素空间**，无障碍的 bounds 活在**显示坐标空间**。实测这台机器上两者一致
（竖屏截图 `1080x2376`、横屏 `2376x1080` 都与显示尺寸相同），但一旦出现缩放、裁剪、
letterbox 或厂商截图缩放，整屏点击坐标就会系统性偏移——这类偏差读代码看不出来，只能实测。

做法：把「无障碍节点里带文字的项」当真值，把「OCR 同名文本块」当观测，
**逐轴最小二乘**拟合 `truth = s * obs + d`；`n >= 3` 且拟合后误差真的下降（MAE < 0.9×原值
且 < 24px）才落库。参数按**截图像素尺寸**分桶（`1080x2376` / `2376x1080` 天然分开），
只在对应桶存在时生效，没有参数时映射恒等、老行为不变。

```bash
scr calib              # 只测量：配对数、sx/sy/dx/dy、校正前后 MAE
scr calib --apply      # 测量并写库（不满意可 scr calib --clear 清空）
scr calib --list       # 看已存的分桶参数
```

返回示例：`{"pairs":14,"fit":{"sx":1.0,"sy":1.0,"dx":0,"dy":-3},"maeBefore":2.4,"maeAfter":0.9,"applied":true}`。
`/ocr` 与 `/scene` 返回的 OCR 坐标会自动经过该映射。

## 8.7 `ownOverlay`：自家悬浮窗命中与绕行（v2.5.35）

`ownOverlay:true` 表示落点命中本 App 的无障碍悬浮窗。**是否会被吞取决于落点性质**，不要凭矩阵猜：

| 落点 | 谁在处理触摸 | 表现 | 处置 |
| --- | --- | --- | --- |
| 自家悬浮窗的**可交互控件**（胶囊、工具条按钮） | 本 App 的 View | 正常响应 | 直接用坐标手势（实测 6/6 有效） |
| 自家悬浮窗的**透明区域**，下方是第三方 App | 悬浮窗吃掉且不响应 | 手势被吞、页面无变化 | 改用节点动作，或先收起悬浮窗 |
| 第三方 App 普通区域 | 第三方 App | 正常 | 直接用坐标手势 |

三步判定（不要靠猜）：

1. 点完立刻 `scr changed`：`{"changed":false}` 就是没生效；
2. 读回执的 `ownOverlay` / `window.all` / `staleFullScreenNode`：
   `window.all` 里同时出现 `com.termux/TYPE_ACCESSIBILITY_OVERLAY` 与前台 App → 优先怀疑自家悬浮窗；
3. 绕行（按代价从低到高）：
   - 节点语义：`POST /act {"steps":[{"tap":"<屏上文字>"}]}`（实测有效）；
   - 收起悬浮窗再点：`POST /overlay {"show":false}`、`scr status-hide`；
   - 自家悬浮条/球上的按钮：`scr status --action` 或 `POST /overlay`。

实测案例（2026-10-27，B 站 v8.x「热门」页）：目标「排行榜」行中心 `(128,480)`；
`POST /tap {"x":128,"y":480}` → `ok:true`、`ownOverlay:true`、`window.all` 含
`com.termux/TYPE_ACCESSIBILITY_OVERLAY (top 415, left 24, area 529137)`，`scr changed` 返回
`{"changed":false}`（重复两次一致）；改用 `POST /act {"steps":[{"tap":"排行榜"}]}` 立即进入
「全区排行榜」。注意该行是**文本节点**：`tap-desc "排行榜"` 会报 `target not found`（回执 nearest
显示 `排行榜(dist=0)`），要按 text 匹配。

实测对照（胶囊）：先用节点动作收起工具条并自证（`scr dump` 里 `重启 @` 计数为 0），再
`POST /tap {"x":540,"y":144}` → 工具条恢复；6 轮全中，控制组 `(300,144)` 无变化。胶囊节点
`center [540,144] bounds [444,120,636,168] clickable`。

## 8. 边界（请知悉）

- **纯 Canvas/游戏画面**：没有可读文本时只能靠 OCR 与视觉定位，复杂画面仍可能失手；
- **无文字且无标准图标的控件**：需要视觉模型辅助，默认不启用；
- **系统级权限弹窗**：仍需你手动点「允许」；
- **部分应用的防自动化检测**：不保证 100% 通过（我们也不做规避检测的对抗）。


## 8.8 通知栏（shade）与系统 UI

无障碍的 `dispatchGesture` 是合成手势，SystemUI 的 shade 是独立窗口，动画期间会丢弃合成事件——所以「坐标点得准也点不动」。可用手段：

| 目的 | 用法 |
|---|---|
| 展开 / 收起 | `scr key expand-notifications` / `scr key dismiss-shade` |
| 点通知条目或按钮 | `scr tap-desc "文案"`、`scr tap-id`（先 `ACTION_CLICK`，失败才回退手势） |
| 判断是否在 shade | `/scene` 命中 `com.android.systemui` 时返回 `hint` |
| 兜底 | `scr tap --shell x y`（Shizuku/root 的 `input tap` 属可信注入） |

`tapNode()` 自 v2.5.32 起先试 `AccessibilityNodeInfo.ACTION_CLICK`，失败才注入手势；`tapNode` 的通道会写进日志与 `/tap` 响应的 `via` 字段（`action-click` / `node` / `gesture`）。

## 9. 坐标 tap 的语义与 v2.5.30 回归修复

**结论：`/tap {x,y}` 的语义是「像素」，默认只发合成手势（dispatchGesture），不做节点语义替换。**

背景：v2.5.29 曾把坐标 tap 改成「先按坐标找节点 → 点该节点」，本意是提高命中率，实测反而把坐标点击变坏：

| 场景 | v2.5.29（节点优先） | v2.5.30（默认手势） |
| --- | --- | --- |
| 酷狗音乐 点列表行/关注按钮 | 点到整屏残留节点或错误祖先，无反应/误命中 | 正常跳转 |
| 通知栏 shade 点通知卡 / 「分享状态」 | 常无反应 | 正常打开通知目标 / 微信状态编辑 |
| DSH 自己顶部工具条「隐藏」 | 无反应 | 无反应（见下，属另一类问题） |

另加了两道护栏（`tapSmart`，仅在显式 `{"via":"auto"}` 时启用）：

1. **整屏节点拒绝**：面积 ≥60% 屏的节点（酷狗实测挂着 `[0,0,1080,2376]` 的「关闭侧边栏」「播放」）不能作为点击目标；
2. 命中这类节点时自动退回合成手势，`via` 回执里能看到实际用的是什么。

`/tap` 的坐标回执现在带自证信息：

```json
{"ok":true,"dispatched":true,"via":"gesture","sent":{"x":540,"y":804},
 "dispatchedAt":{"x":541,"y":803},"shot":"…/step-3.png",
 "staleFullScreenNode":"关闭侧边栏","spaceHint":"…"}
```

- `sent` 是调用方给的坐标，`dispatchedAt` 是抖动/边界钳制后真正派发的点（差分大说明坐标被改写过）；
- `staleFullScreenNode` 存在时，该窗口的 a11y 几何不可信，坐标请从截图上量；
- `ownOverlay:true` 只说明「落点在本 App 的无障碍悬浮窗上」，**不等于一定被吞**：
  落在悬浮窗的可交互控件（胶囊、工具条按钮）上会正常响应；落在悬浮窗的透明区域（下方是第三方 App）
  才会被吃掉。判定见 §8.7。

**能力矩阵（实测 2026-10-27，vivo V2520A / Android 17）**

| 窗口类型 | 坐标手势 | 节点动作 (tap-desc/tap-id) |
| --- | --- | --- |
| 普通第三方 App（GitHub / 酷狗 / 设置 / B站） | ✅ | ✅ |
| SystemUI 通知栏 shade | ✅（本版实测可用） | ✅ |
| DSH 悬浮窗上的**可交互控件**（胶囊、工具条按钮） | ✅（2.5.35 实测 6/6） | ✅ |
| DSH 悬浮窗的**透明区域**（下方是第三方 App） | ❌（触摸被悬浮窗吃掉、我们又不响应） | ✅（改节点点击或先收起悬浮窗） |
| 存在残留全屏节点的窗口 | ✅（手势不受影响） | ⚠️ 中心坐标可能偏，改用截图坐标 |

## 9.1 统一分发与窗口诊断（v2.5.31 / 2.5.35）

`POST /act` 的 `{"tapXY":[x,y]}` 不再单独调 `dispatchTap`，改为与 `POST /tap {x,y}` 共用同一套
`tapDispatch`：默认合成手势（jitter≤4px），`{"smart":true}` 或 `/tap {"via":"auto"}` 才走节点语义。
两步的回执字段因此一致：

| 字段 | 含义 |
| --- | --- |
| `via` | `gesture` / `action-click` / `shell` / `list` / `failed` |
| `sent` | 调用方请求的坐标 |
| `dispatchedAt` | **实际**落点（含抖动，已夹取屏内） |
| `window.target` | 落点所属窗口，优先取前台 App 的窗口（`window/type/top/left/area`） |
| `window.all` | 所有包含该点的窗口，按面积升序（系统 UI 窗口也可能是全屏，勿只看面积） |
| `node` | `via` 为节点语义时命中的节点标签 |
| `staleFullScreenNode` | 当前页存在面积 ≥ 全屏的 clickable 残留节点时给出其标签 |
| `ownOverlay` | 落点在本 App 无障碍悬浮窗上；是否被吞看落点性质（§8.7）——自家控件正常，透明区被吃 |

回归自测（2.5.35，1080×2376）：酷狗列表行 `540,804` → `via=gesture`、`dispatchedAt=540,804`、
`window.target=kugou TYPE_APPLICATION`；通知栏通知卡 `540,846` → `window.target=systemui
TYPE_SYSTEM`，点后 shade 收起；`/act` 里同样的字段可见。

## 10. 两条执行纪律：回执必读 · 坐标来源（2026-10-27 实测代价换来的）

### 10.1 回执必读

- 任何 bridge 调用（`/act`、`/gesture`、`/tap`、`/share`、`/launch`、`/open` …）都必须立即读回执，
  检查 `ok` / `error` / `verified` / `changed` / `opened` / `handoff` / `ownOverlay` / `window`。
- `ok:false`、`verified:false`、`changed:false`、`opened:false` 一律按失败处理，不得当成成功。
- 禁止 `command >/dev/null` 静默丢弃回执；需要精简时用摘要封装（例如 `actq`）。
- `handoff:true` 的动作（share / open / launch）不得重试，重试会产生重复副作用。

真实翻车（同一天）：`/gesture` 漏传 `type` 返回 `{"ok":false,...}`，因为 stdout 被丢掉，
被误判成「系统吞了手势」；`native share` 把 `handoff` 当失败重试，同一文件在 QQ「我的电脑」发了 3 份；
`am force-stop` 返回 rc=0 但其实没停；把 `/proc`、`ps` 当「任务栈已清」的定论。

### 10.2 坐标来源

- 坐标只能来自当前屏幕的 `/scene` 或 `/screen?format=json` 的 `bounds`/`center`，或上一次成功
  点击的回执；**禁止目测、按比例估算、复用旧页面坐标**。
- 截图预览（如 581×1280）与真实屏幕（1080×2376）比例不同，禁止在预览图上手算坐标。
- 需要系统级注入时才用 `scr tap --shell`（Shizuku/Root）；普通点击优先节点语义或 `/act` 事务。

真实翻车：胶囊被估成 `(465,186)`/`(465,195)` 连点失败（真实中心 `(540,144)`），随后被错误推广成
「自家悬浮层不吃坐标手势」；复用上一次页面的开屏「跳过」坐标，点进了视频页。
