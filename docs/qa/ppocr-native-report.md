# PP-OCR 原生化测试报告（2.9.78 / 649 → 2.9.79 / 650）

> 真机：Redmi K40（M2012K11AC，Android 16 / SDK 36，5f08bc20），Magisk root。
> 目标：用 APK 里已有的 `com.microsoft.onnxruntime:onnxruntime-android:1.31.1` 在 Kotlin 里直接跑
> PP-OCRv6 的 det/rec，**彻底去掉 Termux python + onnxruntime 依赖**，并顺带清掉仍会触发
> 「隐藏悬浮窗」的截图调用点。

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| python 依赖 | **已去掉**。调用 native OCR 期间 `ps -A \| grep -E "python3\|ppocr6"` 恒为 **0 个进程** |
| 模型 | 仍用 APK 自带的 `det_tiny.onnx`(1 780 590 B) / `rec_tiny.onnx`(4 462 639 B) / `rec_tiny.yml`(55 571 B)，未改动 |
| 精度（同图对照 python 实现） | 28/29 框与 python 一一对应，**中心距离中位 0.0 px**、≤15 px 占 28/29 |
| 速度（1080×2400 整屏） | native **1.05–1.31 s**；App 内原 python 路径 2.2–2.4 s；python 脚本直跑 1.56 s |
| 回执 | `/ocr?engine=ppocr6...` 新增 `"runtime":"onnxruntime-android"` |
| 免闪 | `/ocr?engine=ppocr6`、`/calib`、点击链路 OCR 的截图全部切到 `screenshotBase64NoFlash()`（composite），四路判据 **475 采样点全 OK** |
| 回归 | `scripts/probes/flicker/selftest.sh` → `ALL PASS (62 checks)` |

## 1. 背景：为什么「模型随包」却一直用不上

- `PpOcr.kt` 的设计是：模型随 APK 部署，推理交给 Termux 里的 `python3 + onnxruntime`（`PpOcr.kt:16-21` 注释）。
- 真机上 `$PREFIX/share/ppocr6/*.onnx` 与 `$PREFIX/bin/ppocr6` 都在，**但 `$PREFIX/bin/python3` 不存在**
  → `PpOcr.ready()` 恒 `false` → `/ocr?engine=ppocr6` 503、`prefetchOcr` 只写 `n=0`。
- 另一侧 `app/build.gradle.kts:109` 早就引入了 `onnxruntime-android`（图标模型 `iconSession` 在用），
  注释里「避免内置 ORT」与现实不符 —— 所以「原生化」不是新增依赖，只是把已有依赖用起来。

## 2. 实现

新文件：`app/src/main/java/app/dsh/mobile/PpOcrNative.kt`（`internal object`）。

### 2.1 参数（逐条对齐 Termux 侧 `ppocr6` 脚本 `/tmp/ppocr6.py` 271 行）

| 常量 | 值 | 用途 |
|---|---|---|
| `DET_THRESH` | 0.2 | det 概率图二值化 |
| `BOX_THRESH` | 0.45 | 连通域平均概率阈值 |
| `MIN_BOX_SIDE` | 3 | 过小框丢弃 |
| `UNCLIP_RATIO` | 1.4 | `dist = w*h*1.4/(2*(w+h))` 矩形外扩 |
| `MAX_CANDIDATES` | 3000 | 候选上限 |
| `REC_H` / `REC_MAX_W` / `REC_MIN_W` | 48 / 1600 / 16 | rec 输入高度与宽度夹取 |
| `REC_BATCH` | 8 | 同宽 padding 批量 |
| `MIN_CONF` | 0.35 | 识别结果置信度阈值 |
| `CROP_PAD` | 2 | 裁框外扩 |
| `DET_MEAN` / `DET_STD` | `[0.485,0.456,0.406]` / `[0.229,0.224,0.225]` | ImageNet 归一化（BGR 顺序） |

- det 预处理：`nw/nh = ceil(边长/32)*32`、双线性缩放、`getPixels` 拆 BGR、`(x/255-MEAN)/STD`、CHW、N=1。
- det 后处理：`BitSet` + 显式栈做 **4 连通** BFS（等价 `scipy.ndimage.label`），逐域算 bbox/均值/阈值/外扩。
- rec 预处理：pad 2 → `nw = clamp(round(48*w/h),16,1600)` 再 `ceil(nw/8)*8` → 缩到 `(nw,48)` → BGR、`(x/255-0.5)/0.5`。
- CTC 解码：argmax、相邻重复跳过、`0` 为 blank、字典 `["<blank>"] + chars + [" "]`、`conf = mean(选中 prob)`。
- 字典：正则 `character_dict:\n((?:  - .*\n)+)` 从 `rec_<tier>.yml` 抽 6904 条（+blank+空格 = 6906）。

### 2.2 会话管理

- `OrtEnvironment.getEnvironment()` 只取一次；`HashMap<String, Loaded>` 以 `"$tier:$kind"` 缓存 session
  （`det_tiny` / `rec_tiny` 各一个），**模型只在首次调用加载**。
- `SessionOptions`：`setIntraOpNumThreads(min(4, 可用核数))` + `OptLevel.ALL_OPT`。
- `available(ctx,tier)` 只查文件 + `Class.forName("ai.onnxruntime.OrtEnvironment")` —— **刻意不建 session**，
  因为 `ready()` 被 prefetch（每个 HTTP 请求）和点击链路高频调用。
- `detMaxSide`（`@Volatile`，默认 0 = 全分辨率）：检测前可把长边压到 N 再检测，用于速度实验。

### 2.3 接线

- `PpOcr.ready(ctx) = PpOcrNative.available(ctx) || pythonReady(ctx)`（原 ready 体转私有 `pythonReady`）。
- `PpOcr.recognizeFull()` / `recognizeFile()`：**先 native**（`bitmapHash`/`fileHash` 做缓存键），
  失败才 `Log.w("PpOcr","native failed, falling back to python: …")` 落回 PNG + python。
- `PpOcr.ensureDeps()`：native 可用直接 `return null`（不再装 python 包）；`&legacy=1` 可强制走旧路。
- `/ocr` 回执：`models=1` 与显式 engine 成功路径都带 `"runtime":"onnxruntime-android" | "termux-python"`。
- 调试口（localhost + token）：`/ocr?engine=ppocr6&tier=tiny&file=<绝对路径PNG>[&detmax=N]` 直接识别指定图，不经截图。

## 3. 精度对照（同一张 PNG）

设备上取一张真实截图（`/screenshot?save=1` 落到 app 私有目录的 `shot-1793957249301.png`，
复制到 `/sdcard/cmp3.png` 给 python 读），两边跑同一张图：

| 引擎/配置 | lines | costMs | 与 python 最近中心距离 中位 | ≤15 px | 备注 |
|---|---|---|---|---|---|
| Termux python（基准） | 29 | det 0.66s + rec 0.47s = 1.56s | — | — | 需 2.75 GB prefix |
| native `detmax=0`（**默认，全分辨率**） | 28 | 1321 | **0.0 px** | **28/29** | 框位与 python 零偏移 |
| native `detmax=1600` | 30 | 883 | 1.0 px | 26/29 | 缩检测会牺牲小字召回 |
| native `detmax=1200` | 28 | 616 | 1.0 px | 24/29 | 更快但召回更差 |

⇒ 默认取 `detmax=0`。`detMaxSide` 保留为可调参数（想换速度可显式打开）。

## 4. 多屏实测（650 / 2.9.79，`cache=0`）

| 场景 | lines | costMs | runtime |
|---|---|---|---|
| Chrome（example.com） | 27 | 1049 | onnxruntime-android |
| 系统设置首页 | 15 | 589 | onnxruntime-android |
| 桌面 | 23 | 587 | onnxruntime-android |
| DSH 聊天页 | 46 | 1306 | onnxruntime-android |

- 首次调用含 session 加载：1833 / 1968 ms；预热后稳定 589–1306 ms。
- **python 进程证据**：native OCR 执行中与执行后 `ps -A | grep -E "python3|ppocr6"` 均为 **0 行**。

## 5. 免闪回归（650）

三个调用点从 `svc.screenshotBase64()`（隐藏悬浮窗 2.3 s）换成 `svc.screenshotBase64NoFlash()`：
`bridge/ScreenHandler.kt:337`（`/calib`）、`:936`（`/ocr?engine=ppocr6` 主路径）、`:1279`（点击链路 OCR）。

每次操作期间对自家悬浮窗做 10 次 `dumpsys window windows` 采样：

| 操作 | 采样 | 悬浮窗状态 |
|---|---|---|
| `GET /screenshot`（composite） | 10 | 10/10 `HAS_DRAWN / shown=true / isVisible=true` |
| `GET /screenshot?flash=1`（负对照） | 10 | **9/10 `NO_SURFACE / isVisible=false`**（证明判据有效） |
| `GET /ocr?engine=ppocr6`（native） | 10 | **10/10 可见**（改前会隐藏 2.3 s） |
| `GET /ocr`（ML Kit） | 10 | 10/10 可见 |
| `GET /calib?tier=tiny` | 12 | **12/12 可见**，26 boxes / 1080×2400 |

四路判据（`scripts/probes/flicker/capture.sh 12`，边采样边跑 3 次 native OCR）：

| OK | GONE(a) | NO_CONTENT(b) | MOVED(c) | STALE(d) | RECREATED(e) |
|---|---|---|---|---|---|
| **475** | 0 | 0 | 0 | 0 | 0 |

## 6. 行为变更与取舍

- **点击链路仍默认 ML Kit**：`usePp = enginePref == "ppocr6"`。原因：整屏 native 约 1.2 s，
  是 ML Kit（约 0.3 s）的 4 倍，而点击链路是热路径。要看中文细节/精度时由调用方显式点名。
- **prefetch 仍 ML Kit**：`engine=mlkit ms≈288–389`（n=21–37）。PP-OCR 只在显式路径使用。
- `PpOcrWorker`（python 常驻 worker）保留为回退路径，native 正常时不会启动。

## 7. 复现命令

```bash
adb forward tcp:3083 tcp:3083
TOK=$(adb shell su -c "cat /data/user/0/com.termux/files/usr/.bridge-token" | tr -d '\r')

# 1) 原生推理（无需 python）
curl -s -H "X-DSH-Token: $TOK" "http://127.0.0.1:3083/ocr?engine=ppocr6&tier=tiny&cache=0" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['lines'],d['costMs'],d['runtime'])"

# 2) 确认没起 python
adb shell su -c 'ps -A | grep -E "python3|ppocr6" | grep -v grep'   # 应为空

# 3) 免闪回归（四路判据）
cd scripts/probes/flicker && ./capture.sh 12 && ./selftest.sh
```

## 8. 限制与后续

- **速度**：1080×2400 全分辨率 det 占大头（约 0.8 s）。可选优化：`detMaxSide`（牺牲召回）、
  NNAPI/XNNPACK EP（未验证在 HyperOS 上的稳定性）、det 输入按 32 对齐后裁剪无用边。
- **medium tier**：只验证了 tiny；medium 需 `/ocr?engine=ppocr6&download=medium` 下载后同法回归。
- 状态栏小字（时钟、电量）偶有误读（如 `4100%`）—— 与 python 实现表现一致，非 native 引入。
- 竖排/艺术字仍依赖 rec 模型能力，未做额外倾斜矫正。
