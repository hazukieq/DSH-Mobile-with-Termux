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

### P0-2 `/scene` 单一合并视图（v1.8.2）

`GET /scene`（`ocr=0` 可关 OCR；`engine=mlkit|ppocr6` 可指定引擎）返回统一节点列表：

```json
{"ok":true,"count":35,"ocrEngine":"mlkit",
 "timings":{"a11y":27,"ocr":348},
 "scale":[1080,2400],
 "nodes":[
   {"text":"引擎已就绪…","bounds":[0,105,653,215],"center":[326,160],"source":"a11y","clickable":false},
   {"text":"同意及確認","bounds":[...],"source":"ocr","clickable":true,"hint":"near-clickable","a11yIndex":12}
 ]}
```

- OCR 行按提案规则合并：同一 y 带 ±18px、间距 < 0.8 字高；
- OCR 框落在可点 a11y 节点 bounds 内时继承 `clickable/a11yIndex`（hint:*near-clickable*）；
- `timings` 分开报无障碍与 OCR 耗时，`ocrEngine` 说明用了哪个引擎。

### P1-4 PP-OCRv6 tiny 本地兜底（v1.8.2）

- 随 APK 内置 `assets/ppocr6/`：脚本 + `det_tiny.onnx` + `rec_tiny.onnx/yml`（约 6.3MB）；
- 引擎启动时部署到 `$PREFIX/bin/ppocr6` 与 `$PREFIX/share/ppocr6/`；
- `GET /ocr?engine=ppocr6`：依赖缺失时返回 `unavailable:true, reason`（含安装提示）；
- `GET /ocr?engine=ppocr6&ensure=1`：依次安装 `python`、`python-numpy/python-pillow/python-scipy`，
  并 **reinstall onnxruntime**（Termux 的 python 绑定不会随 python 升级自动重装）；网络/体积较大，显式触发；
- ⚠️ 实测边界（2026-10-22，LineageOS 测试机）：Termux 只装了 `libonnxruntime.so` C 库，
  `site-packages/onnxruntime` 仍缺失（python 3.14 与包内 wheel 版本不匹配），`ensure` 后
  仍会返回 `unavailable` + 具体原因。要实现「干净设备开箱可用」，需要改走内置
  onnxruntime（route B）或提供一个可用的 python 绑定包；当前实现依赖设备已有该绑定
  （手机端提案环境即如此）；
- `/scene` 在 ML Kit 不可用或 `engine=ppocr6` 时自动用 PP-OCRv6。

### P1-6 listId / itemIndex（v1.8.2）

- `/screen?format=json` 与 `/scene` 的节点：滚动容器内的可点行带 `listId`、`itemIndex` 与
  `label`（行容器本身常无 text/desc，label 从子节点聚合最多 3 段）；
- 可滚动容器即使没有文本/desc 也会保留一条节点记录，作为 listId 锚点；
- `POST /tap {"listId":"L12","itemIndex":3}`：App 实时重读节点树定位并点击，不依赖上一屏坐标；
- 找不到时返回 404 + `failedAt:"tap"` + 重新读取的提示。

实测（系统设置列表）：

```
L0 0 | -
L0 1 | 网络 Hazukie-spot
L0 2 | SIM 卡 China Unicom, CMHK
POST /tap {"listId":"L2","itemIndex":0}
→ {"ok":true,"via":"list","listId":"L2","itemIndex":0,"x":540,"y":470,"bounds":[0,364,1080,576]}
后续 /scene 显示页面已进入「网络和互联网」（标题/网络/Hazukie-spot）
```

## 未落地（下一批）

| 提案 | 状态 | 备注 |
|---|---|---|
| P2-7 `/act` 事务化 | 待做 | 可在 `loop run` 之上抽象 |
| P2-8 OCR 批量/增量 | 待做 | 依赖 P1-4 已就绪 |
| P3-9 失败可观测性（完整版） | 部分 | tap 已带 failedAt/screenHash/candidates/hint |
