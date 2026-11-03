# App Agent 自动化原语（/probe、/act、/chain）

给 App 内 agent 做手机自动化时的高频动作，都做成**一次调用即可**的桥端点，并且可以被 `/chain` 串成点击链路。

所有端点：`POST http://127.0.0.1:3083/...`，请求头带 `X-DSH-Token: <token>`（token 在 `$PREFIX/.bridge-token`）。
返回统一为 `{ok, ...}`，失败带 `error`，不抛异常。

## 1. `/probe/motion` —— 画面是否在动

```json
{"samples": 3, "gap_ms": 180}
```

- 连续采样画面差异（`/diff` 同源），任意两次不同即 `moving=true`。
- 用途：点固定坐标前先判断「画面是否还在动/还在加载」，避免点到旧布局的坐标。
- 返回：`{ok, moving, samples, changed, hint}`。

## 2. `/probe/scroll` —— 滚动方向确认

```json
{"direction": "down", "amount": 1, "settle_ms": 260}
```

- 执行一次滑动 → 等待 `settle_ms` → 再取一帧差异，判断画面是否真的动了。
- 用途：确认「这个区域到底能不能滚 / 方向对不对 / 是否已到边界」。
- 返回：`{ok, direction, moved, gesture, hint}`。

## 3. `/act/drag-fast` —— 短命控件的连贯拖动

```json
{"x1": 540, "y1": 1600, "x2": 900, "y2": 1600, "duration_ms": 180}
```

- 一次手势从 A 拖到 B，默认 180ms（可短到 60ms）。
- 用途：**点击后 2 秒就消失**的控件（进度条、临时浮层、拖动解锁），必须在窗口内完成动作。
- 返回：`{ok, elapsed_ms, gesture}`。

## 4. `/act/skip` —— 开屏广告/弹窗的跳过

```json
{"words": "跳过|跳过广告|关闭|skip"}
```

- 按常见文案在无障碍树里依次尝试点击，命中即返回。
- 用途：开屏广告、升级弹窗、协议弹窗的「跳过/关闭」。
- 返回：`{ok, clicked:[已点词], hint}`。

## 5. `/chain` —— 把原语编排成点击链路

```json
{"steps": [
  {"op": "motion", "samples": 2, "gap_ms": 120},
  {"op": "skip", "words": "跳过"},
  {"op": "tap", "x": 540, "y": 1700},
  {"op": "gesture", "direction": "down", "amount": 1},
  {"op": "wait", "text": "已发送", "timeout_ms": 5000}
]}
```

- 支持 `tap` / `gesture`(=`scroll`) / `skip` / `motion` / `wait`，每步可带 `wait_ms` 先等待。
- 任一步返回 `ok:false` 即停止并回报已执行结果（不会静默继续）。
- 用途：把「判断画面稳定 → 跳过广告 → 点目标 → 滚动确认 → 等文本出现」合成一次调用，减少往返。
- 返回：`{ok, steps:[{op, code, result}]}`。

## 6. 编排建议

1. 先用 `/probe/motion` 确认画面静止，再点固定坐标。
2. 目标控件可能瞬态（进度条/浮层）→ 用 `/act/drag-fast`，不要分两次调用。
3. 需要滚动时，先用 `/probe/scroll` 确认该区域可滚，再用 `/chain` 里的 `gesture`。
4. 开屏/弹窗一律先 `/act/skip`，比 OCR 找按钮快一个数量级。
