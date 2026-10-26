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
- `timings` 分开报无障碍与 OCR 耗时，`ocrEngine` 说明用了哪个引擎；
- v2.1.6 起 `/scene` 的 OCR 默认走 **PP-OCRv6 + region=1 增量**（agent 无感）：
  - 同屏无变化：`ocrCached:true, ocrChanged:false`，OCR 只付截图成本；
  - 局部变化：`ocrRegion:[l,t,r,b]`，只识别变化区域并与上一轮合并成完整文本；
  - 覆盖参数：`engine=mlkit` 强制 ML Kit、`region=0` 关闭增量、`cache=0` 强制重算。
  实测：第二次同屏 `ocr:266ms`（含截图），音量面板变化 `ocr:380ms / region [868,724,1072,1780]`，
  全屏冷启动 2709ms（只第一次）。

### P1-4 PP-OCRv6 tiny 本地兜底（v1.8.2）

- 随 APK 内置 `assets/ppocr6/`：脚本 + `det_tiny.onnx` + `rec_tiny.onnx/yml`（约 6.3MB）；
- 引擎启动时部署到 `$PREFIX/bin/ppocr6` 与 `$PREFIX/share/ppocr6/`；
- `GET /ocr?engine=ppocr6`：依赖缺失时返回 `unavailable:true, reason`（含安装提示）；
- `GET /ocr?engine=ppocr6&ensure=1`：依次安装 `python`、`python-numpy/python-pillow/python-scipy`，
  并 **reinstall onnxruntime**（Termux 的 python 绑定不会随 python 升级自动重装）；网络/体积较大，显式触发；
- **python 绑定**：Termux 的 `onnxruntime` 包只有 C 库，python 绑定要装 `python-onnxruntime`
  （pip 在 bionic 上没有可用 wheel）；`ensure=1` 即 `pkg install -y python python-numpy
  python-pillow python-scipy python-onnxruntime`，装好后 `onnxruntime 1.31.0` 可用，
  `/ocr?engine=ppocr6` 46 行 / ~1.96s。
- **兜底保留（v1.8.8）**：用 Termux clang + `onnxruntime_c_api.h`
  编译了 `libortshim.so`（`tools/ortshim/ortshim.c`），并在 `assets/ppocr6/py/onnxruntime.py`
  用 ctypes 提供 `InferenceSession / SessionOptions / GraphOptimizationLevel /
  set_default_logger_severity`，ppocr6 无需安装任何 python 绑定即可 `import onnxruntime`；
  shim 随 APK 部署到 `$PREFIX/share/ppocr6/py/`；
- **medium 档按需下载（v1.9.7）**：`GET /ocr?engine=ppocr6&download=medium`，源顺序
  **ModelScope（国内，实测约 9MB/s）→ hf-mirror → HuggingFace**，支持 `.part` 断点续传
  （det 62MB + rec 76MB + yml 150KB，实测全部下载成功）；状态查
  `GET /ocr?engine=ppocr6&models=1`；`&tier=medium` 切换识别档位（实测 43 行 / 13.2s）；
- **无 a11y 截图回退（v1.9.6）**：无障碍不可用时，OCR 走 `Privilege.runPrivileged`（Shizuku 优先
  → su 兜底）执行系统 `screencap`，返回 `via:"privileged-screencap"`；没有 root/Shizuku 时仍回
  `a11y_off` 并引导开服务；
- 实测（LineageOS/aarch64，1080×2400）：`/ocr?engine=ppocr6` → `lines:45, costMs:2022`；
  无障碍关闭 + root：`via:"privileged-screencap", lines:45, costMs:1975`；
  `/scene?engine=ppocr6` → 98 节点（31 个 OCR 行，`near-clickable` 正确继承 a11y 索引）；
  CLI `ppocr6 ... --tier tiny --json` 单图 0.98s（det 0.64 + rec 0.09）；
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

### P2-7 `/act` 事务化（v1.9.8）

```json
POST /act
{"steps":[
  {"tapDesc":"返回","expect":{"anyText":["引擎已就绪"],"timeoutMs":4000}},
  {"tapDesc":"设置","expect":{"anyText":["权限中心"],"timeoutMs":5000}},
  {"key":"back"}],
 "onFail":"stop"}
```

回执：每步 `index/action/ok/verified/verifyHow/elapsedMs`，失败带 `failedAt/hint`；顶层
`ok/elapsedMs/onFail`，可选 `returnScreens:true`（before/after 紧凑读屏）。
step 支持 `tap`、`tapDesc`、`tapId`、`tapXY`、`listId+itemIndex`、`input(+append)`、`key`、
`sleepMs`、`expect`。实测 3 步（返回→设置→back）一次调用 878ms，全部 verified。

### P2-8 OCR 常驻 worker（v1.9.9）

- `ppocr6_worker.py` 常驻进程：模型只加载一次，App 按 JSON 行喂图片路径；
  进程崩溃/超时自动销毁并回落一次性 CLI；
- 实测同一屏连续两次：冷启动 1823ms → 热调用 **1251ms**（快约 31%，目标区间 1.2–1.8s）；
- **rec 批量推理（v2.0.1）**：同一批 8 个框 pad 到同宽后一次推理（模型拒绝 batch 时自动回落单条），
  45 行同屏热调用 1251ms → **~1150ms**；
- **截图级缓存（v2.0.1）**：同一张截图 5s 内重复请求直接复用结果（`cached:true, costMs:0`）；
  `cache=0` 可强制重算。实测连续 3 次：1835ms（冷）→ 0ms → 0ms；
- **`ifChanged` 预触发（v2.1.2）**：`/ocr?engine=ppocr6&ifChanged=1` 在截图哈希未变时直接返回
  缓存结果（`changed:false, cached:true, costMs:0`），不再跑 OCR；
- **区域增量（v2.1.3/2.1.4）**：`region=1` 用上一帧降采样做像素 diff，只裁变化区域识别，
  再与上一轮结果合并（未变化区域沿用旧文本）。实测：同屏第二次 45 行 / 0ms；音量面板变化后
  49 行 / **93ms**（只识别右侧 204×1056 区域），远低于全屏 1.8s；
- 到此 P2-8 的「批处理 + 缓存 + ifChanged + 区域增量」全部落地。

## 未落地（下一批）

| 提案 | 状态 | 备注 |
|---|---|---|
| P2-8 完整版（batch rec / 区域增量） | ✅ 完成（v2.1.4） | 批推理 + 截图缓存 + ifChanged + 区域增量 |
| P3-9 失败可观测性（完整版） | ✅ 完成（v2.1.4） | tap/tap-id/wait-text/act 统一 failedAt/screenHash/hint；20 次失败注入 20/20 可定位 |


## 实战反馈（2026-10-22 真机报告）与 v2.4.0 对齐

真机报告最重要的结论不是“引擎能力不够”，而是**时序错配**：有时间窗的控件（B站控制条约 3s、
Toast 2–3s）经不起“点一下→dump→思考→再点”的节奏（1.5–3s/步），第二次点击会落到页面上。

### 已写进 agent 文档（AGENTS.md / DSH-MOBILE.md）

- **规划慢、执行快**：时间窗操作先读一次屏、规划完整序列，再用 `POST /act` 一次执行，中间
  **不插 dump / 不插推理**，最后只验证一次；
- **手势参数优先于坐标**：拖动 `durationMs >= 800–1200`；短时长长距离会被识别成 fling；
  双击 `gapMs <= 150`；列表滚动 400–600px/次；坐标必须现读（同一条进度条不同布局 y 不同）；
- **图标按钮不要猜**：用 `/screen?format=json` 或 `/scene` 的 `bounds`/`center`。

### v2.4.0 新增的 API 支撑

- `/act` 的 step 新增：
  - `swipe`: `{"x1":..,"y1":..,"x2":..,"y2":..,"durationMs":1200,"human":false}`
    （也接受 `[x1,y1,x2,y2,durationMs]` 数组）；
  - `doubleTap`: `[x,y]` + 顶层 `gapMs`（默认 90，范围 40–400）；
- `/gesture` 的 swipe 在「距离 >300px 且 duration <300ms」时返回 `warning`，提示会被当成 fling；
- `/local/status` 增加 `installHint` 与 `modelUrl`，配合 2.3.9 的 `serverBinary/lastError`，
  “模型下载了但 llama-server 缺失”可以直接定位（`pkg install -y llama-cpp`）。
