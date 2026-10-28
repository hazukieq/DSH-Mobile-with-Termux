# DSH Mobile

<p align="center">
  <img src="docs/promo/dsh-mobile-github.png" alt="DSH Mobile — DeepSeek Harness for Android" width="100%">
</p>

**把 DeepSeek Harness 完整跑在 Android 上：官方引擎 + 自带 Termux 用户态，免 Root、免电脑。**

[![License: MIT](https://img.shields.io/badge/license-MIT-brightgreen)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![Version](https://img.shields.io/badge/version-2.5.46-blue)

---

## 核心能力

**1. 引擎即应用** — 官方 `dsh 0.2.1-rc.2` 与 bionic Node.js 全部打包进 APK，装好就能用；引擎只监听 `127.0.0.1`。

**2. 真 Termux 用户态** — 与 Termux 同包名（`com.termux`），`$PREFIX` 就是 `files/usr`，`pkg` / `apt` / `dpkg` / 真 bash 原生可用。内置 VT100/xterm 终端：多会话、真实 PTY、CJK 宽字符、真彩、可调字号。

**3. 手机能力桥** — 只监听 `127.0.0.1:3083`，token 每次启动随机生成并注入引擎。86 个路由、36 个 `scr` 子命令、16 个 gate 脚本，动作原语覆盖点击 / 长按 / 双指 / 滑动 / 滚动查找 / 输入 / 按键 / 启动 / 开关。

**4. 看得懂界面** — 三层识别：无障碍节点树 → PP-OCRv6 → int8 单类 `icon` 检测模型（30 MB，专补「没有文字的控件」，默认关闭、`icon=1` 开启）。附 `scr calib` 坐标自校准：同一批文本的 a11y 与 OCR 坐标做最小二乘，按截图尺寸分桶修正。

**5. 能跳出应用再回来** — `POST /open` 把 `weixin://` / `alipays://` / App Link / 指定包或 Activity 直接送到目标 App，回执带真实前后台（`before` / `after` / `changed`）；`POST /wait-app` 等用户付完款、授完权回到会话，不用轮询截图。

**6. 进度看得见** — 悬浮窗由引擎事件驱动，输出「思考 / 工具 / 结果 / 回复」链路，可拖动贴边、长文在卡片内滚动；也可切到通知模式。

## 本地运行的模型

APK 里没有大语言模型——**推理走你配置的 API**。默认的两类识别模型（OCR `tiny` + icon 检测）**全部随包附带、离线可用**，装完不联网就能用；只有可选的 OCR `medium` 档需要另外下载。

| 模型 | 任务 | 体积 | 运行时 | 默认 |
|---|---|---|---|---|
| **PP-OCRv6** `det_tiny` + `rec_tiny`（ONNX）[¹] | 截图文字识别 + 行框坐标 | 1.8 MB + 4.5 MB | ONNX Runtime 1.31（CPU） | 开 |
| **int8 单类 icon 检测**（ONNX）[²] | 无文字控件（图标、头像、开关、tab） | 30 MB | ONNX Runtime 1.31（CPU，640×640，单类 + NMS） | **关**（`icon=1` 开启） |

### 1. 界面理解模型（APK 内，离线）

- 模型随包放在 `assets/models/omni_icon.onnx` 与 `assets/ppocr6/`，首次使用时复制到 `cacheDir`；换模型只需改 assets（缓存文件名为 `omni_icon_640.onnx`，改形状必须换名，否则会继续用旧缓存）。默认档（`tiny`）随 APK 附带、零联网；只有可选的高精度档 `medium` 才按需从 ModelScope 下载（`/ocr?tier=medium`）。
- 推理全部在 CPU：实机（8 核 arm64，1080×2376 截图）上 icon 检测约 **0.7 s/帧**，PP-OCRv6 tiny 为百毫秒级；两者都不跑时 `/scene` 只用无障碍节点树，几乎无延迟。速度敏感时用默认配置，只有遇到「整屏都是图标、没有文字」的界面才开 `icon=1`。
- 三层结果合并进 `/scene`：无障碍节点树 → OCR 文本框 → icon 框，重叠的按「中心点落在谁里面」去重。文字控件永远由 a11y/OCR 负责，icon 只补空缺，因此不开它也完全可用。
- 坐标可信度用 `scr calib` 自校准：同一批文本的 a11y 框中心与 OCR 框中心做最小二乘，按截图尺寸分桶存 `coord-calib.json`，修正厂商 webview/eink 类偏移。
¹ [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)（PP-OCRv6，Apache-2.0）；² [microsoft/OmniParser](https://github.com/microsoft/OmniParser) 的 `icon_detect`（按其仓库许可，本项目蒸馏 + 量化到 30 MB）；推理框架 [onnxruntime](https://github.com/microsoft/onnxruntime)（MIT）。完整清单见 [docs/CREDITS.md](docs/CREDITS.md)。

### 2. Termux 用户态（模型和工具都跑在它上面）

上层所有本地能力——OCR/icon 的 ONNX 会话、`pkg` 装出来的 Python/Node、`rg`/`git`、脚本 gate——都由**随包内置的真 Termux 前缀**承载，不需要另装 Termux：

```bash
echo $PREFIX          # /data/user/0/com.termux/files/usr
pkg install python    # 真 dpkg/apt 流程，装完即用
python3 -c "import onnxruntime as o; print(o.get_available_providers())"
```

从设备上取模型文件：`assets/models/`、`assets/ppocr6/` 属于构建产物，运行时副本在 App 私有目录，取用请走 `scr`/`/scene` API —— 参考 [Termux](https://github.com/termux/termux-app) 的目录约定。

### 3. 海报来源

仓库里的 banner 不是素材站下的图，是本地用 [Qwen-Image 2.1](https://github.com/QwenLM/Qwen-Image) 生成底图、再用 Pillow 排版的，脚本与参数都在版本库里：

```bash
# 1) 起 ComfyUI（默认 127.0.0.1:8188），加载 Qwen-Image 2.1 int8 convrot + Qwen3-VL 8B 文本编码器
python main.py --listen 127.0.0.1 --port 8188
# 2) 生成底图：<prompt> <输出前缀> [seed]，1600x896 / 28 steps / cfg 3.0
python3 docs/promo/qwen_t2i.py "Apple keynote style hero product shot of a black smartphone floating in a dark void, cyan rim light, screen completely black, no UI, no letters" hero_v6 4242
# 3) 排版：2x 画布，导出 1600x900 / @2x / webp / social
python3 docs/promo/render_banner_v2.py docs/promo/source/qwen-hero-v6.png docs/promo/dsh-mobile-github
```

提示词、seed 与产物 sha256 都记在 [`docs/promo/manifest.json`](docs/promo/manifest.json)。这一步与 App 运行无关，纯桌面出图用。

## 无障碍的边界

无障碍只做三件事：读别的 App 的界面与像素、往别的 App 注入手势文字按键、消费无障碍事件（如 `/notif` 读通知、`/interference` 判断干扰）。

启动、跳转、分享、媒体播放、发通知、TTS、文件、引擎、PTY、插件，以及 App 自身窗口截图（`/app-screenshot`，PixelCopy）**都不需要**无障碍，关着也照常工作。完整清单见 [docs/A11Y-SCOPE.md](docs/A11Y-SCOPE.md)。

## 安装

1. 从 [Releases](../../releases) 下载 `arm64-v8a` APK（约 281 MiB，含引擎、Node、OCR 与图标检测模型）。
2. 打开 App，首次启动会解压内置运行时，随后直接进入内嵌 WebUI。
3. 在 WebUI 里配置模型服务地址与 API Key。

可选授权：通知（Android 13+）用于通知模式；「悬浮窗」权限用于默认的悬浮窗进度卡片；无障碍按需开启。

## 构建

前置：JDK 17、Android SDK（`compileSdk = 36`）、系统 Gradle（仓库不带 wrapper）。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle -p . assembleRelease -Pabi=arm64-v8a
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/CLICK_CHAIN_UPGRADE.md](docs/CLICK_CHAIN_UPGRADE.md) | 点击链路：a11y / PP-OCRv6 / icon 如何合并进 `/scene` |
| [docs/A11Y-SCOPE.md](docs/A11Y-SCOPE.md) | 哪些能力需要无障碍、关着时如何降级 |
| [docs/PAYMENT-LINKS.md](docs/PAYMENT-LINKS.md) | 微信/支付宝跳转与回流 |
| [docs/TERMINAL_AND_LINUX.md](docs/TERMINAL_AND_LINUX.md) | 内置终端（PTY / VT100 / 与 AI 同环境） |
| [docs/PROGRESS_OVERLAY.md](docs/PROGRESS_OVERLAY.md) | 悬浮窗链路、贴边与文本上限 |
| [docs/LAN_WEBUI.md](docs/LAN_WEBUI.md) | 局域网访问手机里的原生 WebUI |
| [docs/ENGINE_RESTART.md](docs/ENGINE_RESTART.md) | 受控重启路径与端口竞态 |
| [docs/android-agent-routes.md](docs/android-agent-routes.md) | 能力桥路由（agent 视角） |

## 风险与反馈

- 内置 **Root / Shizuku** 三档权限；开启特权模式等于把设备控制权交给模型，风险自负。
- 问题与建议请开 [Issue](../../issues)。

## 参考与引用

本项目的核心能力来自这些开源项目，完整清单与分发注意事项见 [docs/CREDITS.md](docs/CREDITS.md)：

| 项目 | 在本项目里承担什么 | 许可 |
|---|---|---|
| [DeepSeek Harness (dsh)](https://github.com/deepseek-ai/deepseek-harness) | Agent 引擎：会话、工具调用、插件系统、WebUI | 见上游 |
| [Termux](https://github.com/termux/termux-app) | 运行时用户态：`$PREFIX`、`pkg`/`dpkg`、bash、终端约定 | GPL-3.0 |
| [PaddleOCR / PP-OCRv6](https://github.com/PaddlePaddle/PaddleOCR) | 截图文字识别（APK 内置 `det_tiny` + `rec_tiny`） | Apache-2.0 |
| [microsoft/OmniParser](https://github.com/microsoft/OmniParser) | 无文字控件检测模型来源（蒸馏 + int8 量化后 30 MB） | 见上游 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | 设备端推理（CPU） | MIT |
| [Qwen-Image 2.1](https://github.com/QwenLM/Qwen-Image) + [ComfyUI](https://github.com/comfyanonymous/ComfyUI) | 仅用于生成本仓库海报底图，不参与 App 运行 | Apache-2.0 / GPL-3.0 |
| [霞鹜文楷](https://github.com/lxgw/LxgwWenKai) · [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | 海报排版字体 | OFL-1.1 |

重新分发 APK 时请注意 Termux 的 GPL-3.0 义务（详见 [docs/CREDITS.md](docs/CREDITS.md#分发注意)）。

## 分叉与许可

本项目是 **[Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)** 的分叉，遵循 **MIT** 许可，保留上游完整历史，可持续合并上游改动。

- 原始版权：`Copyright (c) 2026 Soodok`
- 引擎与插件生态归 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)

## English

**DSH Mobile** runs the official DeepSeek Harness engine plus a bundled Termux userland on Android — no root, no PC, APK only. It adds a local bridge (`127.0.0.1:3083`) for phone control (accessibility + PP-OCRv6 + optional int8 icon detection), cross-app payment hand-off (`/open`, `/wait-app`), a native terminal, an engine-event-driven progress overlay, and fully offline on-device models (PP-OCRv6 + int8 icon detector); the repo banners are generated locally with Qwen-Image 2.1 via ComfyUI. Fork of [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android), MIT.
