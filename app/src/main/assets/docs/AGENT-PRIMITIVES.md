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
