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

## 点击链路的两层识别：PP-OCRv6 + UI 控件检测（v2.5.37）

点击链路是「读屏 → 定位 → 点击 → 校验」四段，前一段决定了后面能不能点对。
可点目标不是猜出来的，而是三类证据拼出来的：**无障碍节点（真相源）+ OCR 文本（补字）
+ 控件检测（补无字图标）**。本章讲清后两层为什么存在、怎么合并、字段长什么样，
以及它们在真机上什么时候会失效。

### 为什么需要两层补丁

无障碍节点树只覆盖「原生控件」这一种界面：

- 原生界面：`TextView` / `Button` / `RecyclerView` 行都在树里，带 `bounds`/`center`/`clickable`，直接可点；
- **WebView / Canvas / 自绘界面没有可点节点**：实测一个 WebView 聊天页（图 1），整屏 41 个节点里
  可点的多是「整条消息行」，真正的按钮是 CSS 画的、连节点都不存在；
- **纯文字按钮 `clickable=false`**：文字节点在树里，但点文字不触发——真正可点的是它的容器，
  所以按坐标点字很容易点到不可点的子节点上；
- **图标按钮没有文字**：`clickable=true` 但 `text`/`desc` 全空——能点，却不知道那是「搜索」还是「更多」。

两种缺口正好对应两种补丁：

| 缺口 | 表现 | 补它的层 |
|---|---|---|
| 文字读不到 | WebView / Canvas / 节点树 0 文本 | **PP-OCRv6** 文本识别：补 `text` 与 `bounds`/`center` |
| 控件没有文字 | 图标按钮 `clickable=true`、`text`/`desc` 空 | **UI 控件检测**（单类 `icon`）：补 bbox/center |

两层都跑在端侧：模型随 APK 预置（OCR 的 medium 档按需下载），识别过程**不上传任何截图**，离线可用。

### 三层合并：a11y → OCR → icon

`GET /scene` 是合并后的单一视图（`app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt:4373`），
优先级从高到低：

| 层 | 来源 | 提供什么 | 何时参与 |
|---|---|---|---|
| ① a11y 节点 | `dumpScreenJson` | 真相源：`text`/`desc`/`bounds`/`center`/`clickable`/`checked`/`listId`… | 每次必读 |
| ② OCR 文本 | ppocr6 det+rec | 补缺失的 `text` 与坐标；落在可点节点内则继承 `clickable` | 默认开，`ocr=0` 关 |
| ③ icon 检测 | 单类 `icon` YOLO | 补无文字可点控件的 `bounds`/`center` | 仅 `icon=1`（默认关，省 ~266ms/次） |

**OCR 不覆盖 a11y，只做补充与归属**（`AgentBridge.kt:4495`）：

- OCR 行中心落在某个**可点** a11y 节点内 → 标 `clickable:true, hint:"near-clickable", a11yIndex, a11yCenter`，
  意思是「文字在节点上，点它所属的容器」；
- 落在空白处 → 标 `hint:"custom control (no a11y node); tap the center if it is a button"`，
  意思是「可能是自绘按钮，点前先用 expect 校验」。

**icon 只补没人覆盖的区域**（`mergeSceneIcons`，`AgentBridge.kt:1672`）：

- **去重**：检测框中心落在任一已有节点 bbox（内缩 4px）内 → 丢弃；面积超过 `1080×2376×35%`
  的巨型容器**不算覆盖**（否则一个整屏 `ScrollView` 会把所有图标吞掉）；已加入的图标之间再互相去重；
- **抑制**：`conf < 0.45`、边长不在 `24–240px`、`cy < 90`（状态栏）一律丢弃——a11y 已覆盖的区域不重复报；
- 新增节点形如 `{"desc":"icon","source":"icon","clickable":true,"icon":true,"bounds":[…],"center":[x,y],"conf":0.87}`，
  **单类无标签**：它只说「这里有个图标」，语义仍要从上下文推。

极端界面兜底：a11y 节点 <3 个时先铺一遍 OCR 预取结果（响应里 `sceneFallback:"ocr-prefetch"`）。

合并的统计口径也在响应里：`total`/`count`（节点数分页）、`cards`/`controls`/`tabs`/`offscreen`
（`tapPoint` 侧统计）、`iconAdded`/`iconMs`（图标侧）。

### 数据形状：/scene 节点与 tapPoint

`/scene` 节点字段（`verbose=0` 的紧凑形态）：

| 字段 | 含义 |
|---|---|
| `text` / `desc` | 文本；`desc` 非空才出现 |
| `bounds` | `[left,top,right,bottom]`，屏幕像素，与截图 1:1 |
| `center` | `[x,y]`，节点**可见区域**中心（部分滚出屏幕的节点也保证落在屏内） |
| `source` | `a11y` / `ocr` / `icon` —— 这一条是谁看见的 |
| `clickable` | 是否可点；OCR 行落在可点节点内会继承 `true` |
| `conf` | 仅 `source:"icon"`：检测置信度（合并前已滤 `<0.45`） |
| `role` / `tapSource` / `tapPoint` | 见下，`annotateTapPoints` 之后加 |
| `checkable` / `checked` / `scrollable` / `editable` | 按需带；开关状态看 `checked` |
| `listId` / `itemIndex` | 列表行锚点，可显式点第 N 项 |
| `x` / `y` / `w` / `h` / `cls` / `rid` / `hint` | 仅 `verbose=1`（`x/y` 是中心，`w/h` 是尺寸） |

`tapPoint` 才是「该点哪里」（`annotateTapPoints`，`AgentBridge.kt:1583`）：

- `role=control`（可点控件）→ 几何中心 `((l+r)/2,(t+b)/2)`；
- `role=card`（面积 ≥8% 屏或高 ≥220px 的可点容器）→ x 取 `left + w*45%`，**让开右侧 ⋮ 菜单**；
- `role=text` → **不给 `tapPoint`**，只附 `note:"mobile: text is not clickable; tap the enclosing card/control"`；
- `role=action`（无边框文字控件：箭头 `>`，或命中 `assets/scene/action_words.json` 词表，或同行条带推断）
  → 给 `tapPoint` 但标 `guess:true`，点完必须校验；
- 所有坐标夹进 `x∈[2,W-2] y∈[90,H-40]`，被夹过的节点带 `offscreen:true`（说明这一刀可能落空）。

读屏自证：`scr dump` 首行固定是 `fg=<package> WxH Nnodes`（`DshAccessibilityService.kt:1002`），
跨 App 操作先看这行是不是目标包；`/scene` 不带包名，跨窗口场景先用 `scr dump` 确认，
再按节点坐标操作。

### 样例图：三张真机标注

三张图都是真机截屏叠加识别结果：**绿=可点控件（白点是节点中心）、蓝=纯文字节点、
灰虚线=滞后 bounds、红虚线=整屏容器**。

![聊天界面：WebView 里大量纯文字 + 少量可点行](promo/samples/clickability-chat.png)

**图 1 · 聊天界面（WebView）**：可点控件 26、纯文字 6、坐标过期 6、全屏容器 3。
识别要点：消息正文全是纯文字（蓝），可点的只有整条消息行和底部输入区的少数控件；
`#1 root` 整屏可点（95%），所以在空白处或文字上做坐标点击会「成功」并落到这个容器上——
这正是「点了没反应」最常见的一种。灰色虚线是 WebView 复用节点留下的**滞后 bounds**：
`#3–#6` 是上一条消息的残留（已不在屏幕上），`#35`「缓存命中 98%」实际在底部状态栏。
判据来源：`/screen?format=json` 的 `clickable` 字段（41 节点）与 ppocr6 的 40 个文本块交叉校验。

![搜索结果页：卡片整行可点，标题/时长/UP 名不可点](promo/samples/clickability-search.png)

**图 2 · 哔哩哔哩搜索结果页**：可点控件 27、文字/标签 32、全屏容器 3。
识别要点：分类 tab `#6–#11`、视频大卡 `#15/#24/#47`、剧集小格 `#35/#38/#41/#44` 都是**整块独立可点**；
标题、时长、UP 主、播放量这些 `TextView` **全部不可点**——想点标题就点它所属的卡片中心（`role=card`）。
红虚线是 `ScrollView`/`ViewPager`/`GridView` 这类整屏容器，坐标点在空白处会落到它们上面。

![播放页：surface 视频区不可读，按钮带 checked 状态](promo/samples/clickability-player.png)

**图 3 · 哔哩哔哩播放页（竖屏播放器）**：可点控件 25、文字/标签 34、全屏容器 2。
识别要点：播放器本体是 **surface 渲染，a11y 树里没有播放器控件**——整块视频区不可读，
进度条/暂停键是点一下画面才临时出现的覆盖层（有时序，见下节「限制」）；
互动按钮 `#23–#31`（点赞/不喜欢/投币/收藏/分享）是 `switch`/`button`，**状态看 `checked`**，
不要用「点了之后文案变了没」判断；弹幕开关 `#5` 是 `switch`，同理用 `checked` 自证。

### 性能与体积

| 层 | 模型 | 体积 | 单帧耗时（真机） | 备注 |
|---|---|---|---|---|
| 文本识别 | `assets/ppocr6/det_tiny.onnx` + `rec_tiny.onnx`（+ `rec_tiny.yml` 字符表） | 1.78MB + 4.46MB ≈ **6.0MB** | tiny 档整屏 **40–900ms**（`region=1` 区域增量命中 ~93ms、截图缓存命中 0ms；低端机整屏冷跑实测 1.8–2.0s，见本文 P2-8） | det+rec 两段式，随 APK 预置；medium 档需下载，更慢 |
| 控件检测 | `assets/models/omni_icon.onnx`（单类 `icon`） | 30.0MB（静态 QDQ int8；fp32 为 80.4MB） | **median 266ms**（vivo V2520A / Android 17；首次 `loadMs` 195） | 640 静态输入、4 线程；真机 `rawBoxes 519 / kept 83` |

- int8 是**按截图批量标定**的静态 QDQ：per-channel，QInt8 权重/激活，40 张截图标定；
  量化时排除 `/model.22`、`/model.23` 两个头部，否则 Sigmoid 输出被量化成全 0、置信度恒 0；
- 精度对照（host，10 张截图）：fp32 220 框 vs int8 231 框，IoU>0.5 匹配 201、meanIoU **0.939**、漏检 8.6%；
- 体积账：APK 从 336MB 降到 294MB（图标模型 80.4MB → 30.0MB），OCR 两模型合计 6.0MB；
- 自查：`GET /yolo`（别名 `/icons`）是同一套检测的实验室接口，直接回
  `modelMb`/`loadMs`/`medianMs`/`rawBoxes`/`kept`/`top`；`/scene?icon=1` 另回 `iconAdded`/`iconMs`。

### 限制

- **视频播放器（surface 渲染）节点树不可读**：视频区没有控件节点，播放/暂停/进度条是点画面后
  才出现的临时覆盖层——先读一次屏、规划完整序列、用 `POST /act` 一口气执行，中间不插 dump；
- **弹窗/覆盖层是临时的**：Toast 2–3s、seek bar 约 3s、下拉菜单更短，「点一下 → dump → 思考 → 再点」
  的节奏会直接错过窗口；
- **长列表要滚动**：节点树里只有当前可见的行，滚过的行坐标全部失效；`scr find`（滚到目标）/ `/act` 的 `listId+itemIndex`
  比猜坐标可靠；
- **坐标只在同一屏有效**：换屏、旋转、键盘弹出、滚动之后必须重新读屏；OCR 坐标（a11y 关闭时来自
  截图）可用 `scr calib` 做一次自校准，但校准也不跨屏复用；
- **WebView 会给出滞后的 bounds**：复用节点把上一条消息的矩形留在树里（图 1 的灰色虚线），
  此时以截量为准，或改用 `scr tap-text` / `scr tap-desc` 走节点点击；
- **OCR 是「看见」不是「理解」**：同一 y 带的相邻框会被合成一行（±18px、间距 <0.8 字高），长文本会被
  截断，纯图标/纯图形区域 OCR 无输出；
- **icon 检测是单类、无语义的**：只回答「这里像图标」，不回答「这是搜索还是更多」；新增节点必须
  点后校验，且 `conf` 低时优先找邻近文字推断语义。

### 最小点击链路示例

```bash
# 0) 先自证前台包：首行 fg=<package> WxH Nnodes，不是目标包就别往下走
scr dump | head -1

# 1) 定位：按文案找到可点行，@x,y 已经是节点中心，可直接点
scr dump | grep -E '评论|发送|搜索' | head -5
#   → 4 评论 55 @540,612 c [row]

# 2) 点击：坐标手势（像素语义，默认 dispatchGesture）
scr tap 540 612
#   或者交给 /act 按文案点（省一次读屏，也避免坐标过期）
dsh-mobile bridge POST /act '{"steps":[{"tap":"评论 55"},{"sleepMs":300},{"expect":"评论"}]}'

# 3) 校验模板：把第 2 步那次点击夹在两次读屏之间，用 Jaccard 判断是否真的换页
scene_text() { dsh-mobile bridge GET '/scene?limit=200&ocr=0' \
  | python3 -c 'import json,sys; print("\n".join(n.get("text","") for n in json.load(sys.stdin)["nodes"]))'; }

scene_text > /tmp/scene-before.txt
scr tap 540 612          # ← 这里放第 2 步的点击（两种写法任选其一）
scr idle 600
scene_text > /tmp/scene-after.txt

python3 - <<'PY'
b={l for l in open('/tmp/scene-before.txt',encoding='utf-8').read().split('\n') if l}
a={l for l in open('/tmp/scene-after.txt',encoding='utf-8').read().split('\n') if l}
j=len(a&b)/ (len(a|b) or 1)
print(f"jaccard={j:.2f} 新增={len(a-b)} 消失={len(b-a)}")
PY
```

读法：`jaccard` 接近 **1.0** = 没换页（点了但没生效，或落到了整屏容器上）；
**0.3–0.8** = 有变化（弹层、局部刷新、加载态）；
**< 0.3** = 明显换页。阈值只是先验，最终证据仍以页面上的目标文本/`checked` 为准——
`scr tap` 的 `ok:true` 只代表手势已派发，不代表目标响应（回执纪律见 `ACCESSIBILITY.md` §10）。
