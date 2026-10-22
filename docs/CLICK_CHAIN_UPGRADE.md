# 点击链路升级（v1.8 首批）

> 来源：`点击链路升级提案_手机端实测.md`（2026-10-22，手机端实测）。
> 本文件记录已落地的部分与验收方式；未落地项列在最后。

## 已落地

### P0-1 节点 `bounds` / `center`

- `GET /screen?format=json` 每个节点现在有：
  `bounds:[x0,y0,x1,y1]`、`center:[x,y]`、`x/y/w/h`、`clickable/checkable/checked/scrollable/editable`。
- `POST /tap-id {"id":"..."}` 回执新增 `bounds` 与 `boundsCenter`，便于核对空心图标/行中心。
- 验收：图标按钮用 bounds 中心点击；回执可对比 `boundsCenter` 与实际落点。

### P0-3 `tap` 内联 `expect` 校验

```json
POST /tap
{"text":"搜索","expect":{"anyText":["综合","番剧"],"timeoutMs":4000}}
```

回执：

```json
{"ok":true,"dispatched":true,"verified":true,"verifyHow":"anyText:综合",
 "elapsedMs":400,"foreground":"com.termux","foregroundChanged":true,
 "shot":".../step-1.png","verify":"inline_expect"}
```

- `verified:false` 时带 `failedAt:"tap"`、`screenHash`、`hint`，不再只能拿到 `ok:true`；
- 带 `expect` 的 tap 不会被 `instant` 后处理覆盖 `verify/hint`（v1.8.1）。

### P1-5 无障碍缺失的结构化引导

- 依赖无障碍的端点返回 503：
  `{"ok":false,"error":"a11y_off","code":"a11y_off","reason":"...","retryable":false,
    "action":"open_a11y_settings","actionHint":"POST /a11y/enable opens the system accessibility page"}`;
  短暂未绑定则是 `error:"a11y_connecting"`、`retryable:true`。
- 新增 `POST /a11y/enable`：一次调用打开系统无障碍设置页。
- 结合「曾授权」持久标记，force-stop/更新后被系统停用会提示去重开，而不是“从未授权”。

### P3-9 失败可观测性（部分）

- `tap` 失败回执带 `failedAt`、`screenHash`、`nearest` 候选与 `hint`；
- `expect` 超时带 `elapsedMs`、`foregroundChanged`、`screenHash`、`hint`。

## 实测记录（Android 14 / 1080×2376）

| 用例 | 结果 |
|---|---|
| `/screen?format=json` | 节点含 `bounds:[22,105,176,259]`、`center:[99,182]` |
| `tap desc:"返回" expect anyText:["引擎已就绪"]` | `verified:true`、`verifyHow:anyText:引擎已就绪`、`elapsedMs:400` |
| `tap desc:"设置" expect 不存在文案（1500ms） | `verified:false`、`elapsedMs:1667`、`failedAt:tap`、`screenHash` |
| `POST /a11y/enable`（无障碍关闭时） | `ok:true`、`opened:accessibility-settings` |

## 未落地（下一批）

| 提案 | 状态 | 备注 |
|---|---|---|
| P0-2 `/scene` 合并 a11y+OCR | 待做 | 需要本地 OCR 引擎（见 P1-4） |
| P1-4 内嵌 PP-OCRv6（onnx） | 待做 | 模型 6/31MB；需决定走 Termux onnxruntime 还是内置推理 |
| P1-6 listId/itemIndex | 待做 | 依赖 bounds 已就绪 |
| P2-7 `/act` 事务化 | 待做 | 可在 `loop run` 之上抽象 |
| P2-8 OCR 批量/增量 | 待做 | 依赖 P1-4 |
