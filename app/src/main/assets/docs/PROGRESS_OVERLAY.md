# 后台进度与悬浮窗

> 目标：AI 在跑长任务时，用户不用盯着 WebUI 也能知道「它在想什么、做到哪一步、完成没有」。
> 本分叉把进度源从「抓 WebUI DOM」换成了**引擎官方插件事件**，并重做了悬浮窗的交互与文本上限。

## 1. 两种显示方式

设置 → **后台进度提示 → 显示方式**：

| 模式 | 行为 | 依赖 |
|---|---|---|
| 关闭 | 不显示任何进度 | — |
| 通知 | 常驻通知原地更新标题/正文，完成 2 分钟后自动清除 | Android 13+ 需通知权限 |
| 悬浮窗（默认） | 应用悬浮窗显示链路，前台/后台都常驻 | `SYSTEM_ALERT_WINDOW`；缺权限时通知提示去授权 |

从「通知」切到「悬浮窗」、或在悬浮窗模式下打开 App，会**立即创建窗口**（没有进度时显示
「空闲 / 悬浮窗已开启」占位），不需要等下一次任务。

相关代码：`ProgressHub.kt`（模式/节流/通知/唤醒锁）、`StatusOverlay.kt`（窗口与布局）。

## 2. 事件来源（为什么不是 DOM）

host 侧插件 `app/src/main/assets/android-plugins/mobile-progress/index.js` 订阅 DSH 官方插件 API：

- `ctx.on('agent/assistant-stream', ({ agent, frame }) => …)`：`block-start` / `text-delta` /
  `reasoning-delta` / `tool-call-delta` / `block-end`；一个 turn 内的多个 attempt 会合并，不会把前几步清掉。
- `ctx.on('session/event', (session, event) => …)`：`tool/call` 补齐工具名与参数，`tool/result` 标记成功/失败。
- `ctx.on('agent/status', …)`：状态变 `idle` 时**强制 flush 一次 done**，保证会话结束一定收尾。

无障碍/自动化动作不走这里：门禁脚本的 `POST /overlay {status,action}`、`POST /launch`、
`POST /open` 会由原生侧 `StatusOverlay.setA11yAction()` 追加到第 2 段动作链；
WebView 侧插件也可直接调 `window.DshNative.a11y(line)`。

插件把事件组织成带标签的行：`[思考]` / `[工具] 调用` / `[工具] 调用->完成|失败` / `[回复]`，
250ms 节流 POST 到
Android 的 AgentBridge `POST /overlay`（自动带 `X-DSH-Token`）；原生侧 `AgentBridge` 解析
`kind/session/detail/elapsed` 后交给 `ProgressHub`。

## 3. 悬浮窗形态

- **折叠球**：点击展开圆角卡片，长按隐藏。
- **展开卡片（三段式，v2.5.8）**，自上而下：
  1. **思考链**：状态点 + 可滚动正文，工具行**并回正文**，格式固定为
     `[思考] …` / `[工具] 调用` / `[工具] 调用->完成`（失败为 `->失败`） / `[回复] …`；
     每次更新自动滚到最新一行。
  2. **无障碍/打开应用动作链**：无障碍服务真正做的事——读屏点击/滑动/按键、
     启动或跳转微信/支付宝/浏览器等，最多 4 行，与思考链分开（v2.5.6 起）。
  3. **会话标题 + 耗时**：标题过长中间省略，右侧秒数运行中每秒跳动、完成后停住。
- **点击语义**：折叠态点球展开；展开态点圆点收起，长按隐藏。
  思考正文**不挂任何点击手势**（旧版「点一下继续展开档位」已删除），只用来滚动。
- **贴边**：默认贴右侧；拖动后松手吸附最近的左/右边，垂直位置保留；展开/折叠后仍贴同一边。
- **旧控件已移除**：底部 8 秒 notice 小条、会话列表/上次动作区、daemon 退出按钮、独立动作行
  均已删除（v2.5.6–v2.5.8），无障碍文案统一并入第 2 段。

## 4. 文本预算（避免「只显示前几行」）

| 层 | 上限 |
|---|---|
| 插件总量 | 16000 字符 |
| 单个思考块 | 1400 字符 |
| 工具行 | 420 字符 |
| 回复 | 取**末尾 4000** 字符（结论在尾部） |
| ProgressHub / StatusOverlay | 16000 字符 |

卡片正文高度上限 132dp，超出部分在卡片内部滚动（展开态下正文区域不绑拖动手势，手指滑动是
滚动内容而不是拖动窗口）。

## 5. 完成与失败

- 收到 `agent/status=idle` 或 `assistant-stream end`：flush `done`，标题显示「已完成 · Ns」并保留链路。
- `done/error` 快照**不做去重**，避免「最后一帧内容相同被丢弃、状态停在 thinking」。
- 运行中每 2.5s 心跳一次，长工具调用期间不会看起来停滞。

## 6. 排障

| 现象 | 检查 |
|---|---|
| 完全看不到悬浮窗 | 显示方式是否「悬浮窗」；悬浮窗权限是否已授权；切模式后是否杀过 App |
| 只在系统设置/权限页看不到 | Android 会主动隐藏受保护页面上的悬浮窗，属正常 |
| 内容只显示前几行 | 确认版本 ≥ 1.6.8（文本预算与滚动修复）；在卡片正文上滑应能滚动 |
| 状态停在 thinking | 看 `adb logcat -s AgentBridge ProgressHub`：最后一条是否 `kind=done`；引擎是否已 idle |
| 无障碍动作看不到 | 只在展开态显示；确认版本 ≥ 2.5.6（旧的底部 notice 条已删，改到思考链下方第 2 段） |

## 7. 相关文件

- `app/src/main/java/app/dsh/mobile/ProgressHub.kt`
- `app/src/main/java/app/dsh/mobile/StatusOverlay.kt`
- `app/src/main/res/layout/overlay_status.xml`
- `app/src/main/assets/android-plugins/mobile-progress/{index.js,client.js,package.json}`
