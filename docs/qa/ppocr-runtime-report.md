# PP-OCR 自带模型「用不上」的根因与运行时落地（2.9.77 / 648）

> 背景：`/ocr` 的内置 PP-OCRv6 路径长期返回 `n=0 blocks`，一度被怀疑「APK 自带的模型是白带的」。
> 本文给出真机取证、根因、落地步骤与实测性能，并记录由此引出的 prefetch 顺序反转。

## 1. 结论先说

- **模型没问题、也没白带**：`det_tiny.onnx` / `rec_tiny.onnx` / `rec_tiny.yml` 随 APK 部署到 app 私有
  prefix（`/data/user/0/com.termux/files/usr/share/ppocr6`），推理脚本 `bin/ppocr6` 也在。
- **缺的是运行时**：脚本 shebang 指向 `$PREFIX/bin/python3`，而该 prefix 里没有 python、没有
  onnxruntime/numpy/scipy。`PpOcr.ready()` 因此恒为 false，`PpOcr.recognize()` 返回
  `ok=false / unavailable=true`，旧 `prefetchOcr` 不检查 `ok` → 每 2.5s 白烧约 780ms 的截图 + 起进程，
  日志只留下 `n=0 blocks`（看起来像「识别不出来」，实际是「根本没跑」）。
- **补装即用**：`/ocr?engine=ppocr6&ensure=1` 会 `pkg install -y python python-numpy
  python-pillow python-scipy python-onnxruntime`（Termux 的 onnxruntime 绑定是独立包
  `python-onnxruntime`，pip 在 bionic 上没有可用 wheel）。真机实测 65s 装完，`realBinding=true`，
  tiny 立刻可用。

## 2. 真机取证（5f08bc20 / Android 16 / Magisk）

| 项 | 修复前 | 补装后 |
|---|---|---|
| `share/ppocr6/det_tiny.onnx` | 1 780 590 B ✅ | 同左 |
| `share/ppocr6/rec_tiny.onnx` | 4 462 639 B ✅ | 同左 |
| `share/ppocr6/rec_tiny.yml` | 55 571 B ✅ | 同左 |
| `bin/ppocr6` | ✅（shebang `#!/…/usr/bin/python3`） | 同左 |
| `bin/python3` | ❌ 不存在 | ✅ → `python3.14` |
| `lib/python3.14` + onnxruntime/numpy/scipy | ❌ 不存在 | ✅（`realBinding=true`） |
| `PpOcr.ready()` | false | true |
| `/ocr?engine=ppocr6&tier=tiny` | `{"ok":false,"unavailable":true,"reason":"ppocr6 deps missing: run /ocr?engine=ppocr6&ensure=1"}` | `{"ok":true,"engine":"ppocr6-tiny","lines":45,"costMs":2394}` |

补装回执：

```json
{"ok":true,"engine":"ppocr6","deps":true,"deployed":true,"realBinding":true,
 "hint":"deps installed; OCR itself still needs the accessibility screenshot (or call /a11y/enable)"}
```

补装后 prefix 体积 2.0 G → 2.9 G（含 apt 缓存，可用 `pkg clean` 回收）。

## 3. 一个环境坑：镜像 429

首次 `ensure=1` 失败：

```
Err:1 https://mirror.textcord.xyz/termux/termux-main stable InRelease
  429  Too Many Requests
E: The repository '… stable InRelease' is not signed.
```

`$PREFIX/etc/apt/sources.list` 当时指向 `mirror.textcord.xyz`。从设备上用 prefix 自带 curl 实测各镜像
`dists/stable/InRelease`：

| 镜像 | HTTP |
|---|---|
| mirrors.ustc.edu.cn | 200（0.25s） |
| mirror.sjtu.edu.cn | 200（0.28s） |
| mirrors.tuna.tsinghua.edu.cn | 403 |
| mirrors.nyist.edu.cn / mirrors.cbrx.io | 404 |
| termux.niranjan.co | 200（3.7s） |
| mirror.textcord.xyz（当时默认） | 429 |

换成 USTC 后一次通过：

```
deb https://mirrors.ustc.edu.cn/termux/termux-main stable main
```

> 注：这台设备上 `su` 对 `/data/data/...` 的读写时好时坏（`magisk` 域的 SELinux 访问不稳定），
> 改 `sources.list` 需要多试几次，或改用 `su -Z` / 重试后再读。

## 4. 性能对照 → prefetch 顺序反转（v2.9.77）

补装后 PP-OCR 真的跑起来了，但**每次都要重起 python 进程 + 重新加载 det/rec 两个 onnx 模型**：

| 路径 | 耗时（真机） | 说明 |
|---|---|---|
| ML Kit（内置） | **137–380 ms** | 同屏结果缓存 3s；不依赖 python/网络 |
| PP-OCRv6 tiny（显式 `/ocr?engine=ppocr6`） | 2 282–2 394 ms | 冷启动含模型加载 |
| PP-OCRv6 tiny 作 prefetch | **2 860–4 119 ms** | 高频路径上不可接受 |

prefetch 是「每个 HTTP 请求预热一次」的高频路径，原来优先 PP-OCR 的写法在 deps 装好后会把 CPU 烧满
还赶不上下一帧。v2.9.77 起反转顺序：

1. prefetch **先走 ML Kit**（~150ms）；
2. ML Kit 不可用或零结果时，才回退 PP-OCR（`ppocr6UnusableUntil` 熔断 5 分钟）；
3. PP-OCR 留给**显式** `/ocr?engine=ppocr6[&tier=…]`——需要更高精度时用，不进预热。

实测日志：

```
17:13:54 I/prefetch ocr ready n=45 dropped=0 engine=ppocr6-tiny size=1080x2400 ms=4119   ← 补装后、反转前
17:15:22 I/prefetch ocr ready n=5  dropped=0 engine=mlkit        size=1080x2400 ms=137    ← 648 反转后
17:15:34 I/prefetch ocr ready n=12 dropped=0 engine=mlkit        size=1080x2400 ms=192
```

## 5. 复现步骤

```bash
# 1) 触发运行时补装（需要联网；约 65s）
curl -s -H "X-DSH-Token: $TOK" 'http://127.0.0.1:3083/ocr?engine=ppocr6&ensure=1'
# 2) 确认模型与绑定
curl -s -H "X-DSH-Token: $TOK" 'http://127.0.0.1:3083/ocr?engine=ppocr6&models=1'
# 3) 跑一次 tiny
curl -s -H "X-DSH-Token: $TOK" 'http://127.0.0.1:3083/ocr?engine=ppocr6&tier=tiny'
# 4) 看 prefetch 走哪个引擎
curl -s -H "X-DSH-Token: $TOK" 'http://127.0.0.1:3083/log?n=20' | grep prefetch
```

## 6. 后续可选方向

- PP-OCR 想快，得避免「每调用一起进程」：可以让 `ppocr6` 常驻守护进程（一次加载模型、后续走 stdin/stdout 协议），
  预计单次降到 300–600ms；或用已内置的 `onnxruntime-android`（`com.microsoft.onnxruntime:onnxruntime-android:1.31.1`，
  `ScreenHandler` 的图标模型 `iconSession` 正在用）在 Kotlin 里直接跑 det/rec，彻底去掉 python 依赖，
  代价是 DB 后处理 + CTC 解码约 600 行。
