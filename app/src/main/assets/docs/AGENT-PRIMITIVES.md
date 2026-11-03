# App Agent 自动化原语（统一走 POST /act）

只保留**一个**编排入口 `POST /act`：原来的 tap/gesture 步骤不变，新增四种自动化原语作为 step 的 `op`。
这样 agent 只需要记住一套 `steps[]` 格式，不必在多个端点间选择。

```json
{"steps": [
  {"op": "motion", "samples": 2, "gap_ms": 120},
  {"op": "skip", "words": "跳过|关闭"},
  {"tap": [540, 1700]},
  {"gesture": {"direction": "down", "amount": 1}},
  {"op": "scroll-probe", "direction": "down"},
  {"op": "drag-fast", "x1": 300, "y1": 1600, "x2": 900, "y2": 1600, "duration_ms": 180},
  {"op": "drag-fast", "from": [300, 1600], "to": [900, 1600], "duration_ms": 180},   // 两种写法等价
  {"wait": {"text": "已发送", "timeout_ms": 5000}}
]}
```

`onFail`（默认 `stop`）决定某步 `ok:false` 时是停止还是继续；`returnScreens` 可附带前后截图差异信息。

## 原语 1：`op: "motion"` —— 画面是否在动

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `samples` | 3 | 采样次数（2–8） |
| `gap_ms` | 180 | 采样间隔（60–800） |

连续比对画面差异，任意两次不同即 `moving=true`。用在「点固定坐标之前」判断画面是否还在加载/还在动。

返回：`{ok, moving, samples, changed, hint}`

## 原语 2：`op: "scroll-probe"` —— 滚动方向确认

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `direction` | down | 试滚方向 |
| `amount` | 1 | 滚动量 |
| `settle_ms` | 260 | 滚动后等待 |

执行一次滑动再看画面是否真的变化，用来判断「该区域能不能滚 / 方向对不对 / 是否到边界」。

返回：`{ok, direction, moved, gesture, hint}`

## 原语 3：`op: "drag-fast"` —— 短命控件的连贯拖动

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `x1,y1,x2,y2` | — | 起止坐标 |
| `duration_ms` | 180 | 拖动时长（60–600） |

一次手势完成 A→B。用于**点击后 1–2 秒就消失**的控件（进度条、临时浮层、拖动解锁），必须在一个窗口内做完。

返回：`{ok, elapsed_ms, gesture}`

## 原语 4：`op: "skip"` —— 开屏广告/弹窗跳过

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `words` | 跳过\|关闭\|skip\|× | 依次尝试的文案，`|` 分隔 |

按文案在无障碍树里依次点击，命中即返回；比 OCR 找按钮快一个数量级。

返回：`{ok, clicked:[...], hint}`

## 编排建议

1. 先 `motion` 确认静止，再 `tap` 固定坐标。
2. 瞬态控件用 `drag-fast`，不要拆成两次调用（中间它可能已经消失）。
3. 不确定能否滚动时先 `scroll-probe`，再决定要不要 `gesture`。
4. 开屏/弹窗一律先 `skip`。
5. 每步都带 `wait_ms` 可控制节奏；`onFail: "continue"` 适合「尽力而为」的探测链。

## 语义封装：`op: "transient"` —— 点击 → 控件出现 → 动作（限时）

「点一下 → 出现一个只活 1–2 秒的控件 → 立刻对它做动作」整条链路压成一步，避免多次往返把时间耗光。

```json
{"op": "transient",
 "tap": [540, 1600],
 "expect": {"text": "进度", "desc": "", "id": ""},
 "appear_ms": 600,
 "budget_ms": 2000,
 "action": {"kind": "tap"}}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `tap` / `tapText` | — | 触发动作：点坐标或点文字 |
| `expect` | — | 目标控件：`{text,desc,id}` 任一包含即命中 |
| `appear_ms` | 600 | 等控件出现的最长窗口（60–1900ms） |
| `budget_ms` | 2000 | **硬预算**：控件出现后必须在此时限内完成动作，超时即 `ok:false / budget_exceeded` |
| `action.kind` | none | `tap` / `swipe` / `drag-to` / `none`（只定位不动作，用于探测） |
| `action.dx,dy` | 0 | `swipe` 时相对控件中心的位移 |
| `action.x,y` | — | `drag-to` 的目标坐标（进度条拖到最大值） |
| `action.duration_ms` | 180 | 拖动时长 |

返回：`{ok, expect, node:{x,y,text,id}, appear_ms, action, action_result, action_ms, spent_ms, budget_ms}`；
控件没出现返回 `{ok:false, error:"not_found"}`，超时返回 `{ok:false, error:"budget_exceeded"}`。

## /act steps 完整字段（2.9.6 起）

一次请求里按顺序执行，`onFail` 默认 `stop`：

| 字段 | 说明 |
| --- | --- |
| `tap`: [x,y] / "文字" / {x,y} | 点击（坐标/文字） |
| `tapText` / `tapDesc` / `tapId` | 按文字/描述/id 点 |
| `tapXY`: [x,y] + `smart` | 带 smart 的坐标点击（避开容器误命中） |
| `input`: "文本" + `append` | 无障碍 SET_TEXT 输入（中文可用） |
| `key`: "back"/"home"/"recents"/"enter"… | 全局按键 |
| `swipe`: {x1,y1,x2,y2,durationMs 或 duration_ms} / [x1,y1,x2,y2,(dur)] | 手势滑动；**时长会透传** |
| `wait`: {text, timeout_ms 或 timeoutMs, gone} | 等文字出现/消失（等价 `waitText` + `timeout_ms`） |
| `sleepMs`: 600 | 固定等待 |
| `expect`: {anyText:[...], timeoutMs} | 本步执行后再校验画面文字 |

### 关于滑动时长（seekbar / 进度条）

- `durationMs` / `duration_ms` 都会透传到 `dispatchGesture`；**短于 ~400ms 且距离 >300px 会被系统当 fling**，滑块往往回到原位。
- 进度条要**从手柄起手**拖：`{"op":"transient", "expect":{"desc":"视频进度"}, "action":{"kind":"drag-to","x":600,"y":673,"from":[372,673],"duration_ms":800}}`。
- 起点不对（比如从整条的中点起手）会跳到别处 —— 这不是时长问题。
- 兜底：`POST /gesture {"type":"swipe","x1":..,"durationMs":2000}` 是同一注入路径，可用于对照验证。

### transient 的 expect 匹配（2.9.6 起）

- 匹配范围是节点的 **text + desc + id + label + hint 拼接串**，**包含**即命中、大小写不敏感；
- 支持多候选：`"expect": {"any": ["视频进度", "暂停"]}`；
- 数值会变的控件给稳定片段即可（`视频进度` 能命中 `视频进度，已播放到2分38秒，共3分52秒`）；
- 触发 `tap` 是**可选**的：控件已可见时不要 tap（视频画面上的 tap 会切换控制条显隐，反而把它藏起来）；
- 失败回执带 `scanned` 与 `sample`（前 6 个节点文本），便于判断是「树里没有」还是「文案对不上」。

### 相对位移式 seekbar（B 站竖屏播放器等）

这类播放器的进度条**不支持点选**（点轨道不跳转），只支持「抓住手柄水平拖」，且位移→时间是**相对线性**换算：

| 试验（vivo V2520A / B 站 3:22 视频） | 位移 | 时间变化 | 比例 |
| --- | --- | --- | --- |
| from 397 → 463 | +66 px | 1:29 → 1:54（+25s） | 0.379 s/px |
| from 247 → 432 | +185 px | 0:31 → 1:40（+69s） | 0.373 s/px |

经验值 **0.375 秒/像素（≈2.67 px/秒）**，有效轨道约 539px（a11y bounds 562px，差值≈滑块半径）。

可复用写法：
```json
POST /act
{"steps":[{"op":"transient","tap":[540,400],"expect":{"desc":"视频进度"},
 "appear_ms":1300,"budget_ms":3000,
 "action":{"kind":"drag-to","x":432,"y":1584,"from":[247,1584],"duration_ms":1800}}]}
```

四条前置条件：
1. `from` 必须落在**当前手柄位置**（不到手柄上起手不会进入拖动状态）；
2. `duration_ms` 建议 **≥1600ms**（短距离+短时长会被当 fling）；
3. 拖动后要判断落点：**在同一个事务/同一次 shell 里**「tap 唤出控制条 → 立刻截图或读屏」，别分两次调用（实测 `input tap …; screencap -p …` 与 `/act` 后立刻 `/screen` 都能抓到控制条；控制条只显示 2–3 秒，分两次调用会被显隐节奏吃掉）；
4. 「点一下画面 → 300ms → 点左下播放键」必须放在**同一个 /act 事务**里，分两次调用会被控制条显隐节奏吃掉。

回执里 `action_detail` 会带上 `{from:[x,y], to:[x,y], duration_ms}`，便于自查起止点与时长是否如预期。
