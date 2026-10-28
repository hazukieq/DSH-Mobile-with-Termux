# 参考与引用

DSH Mobile 站在一批开源项目上。本文件列清「用了什么、用在哪、许可如何」，方便审计与二次分发时核对义务。

| 项目 | 在本项目里承担什么 | 许可 | 链接 |
|---|---|---|---|
| **DeepSeek Harness（`dsh`）** | Agent 引擎本体：会话、工具调用、插件系统、WebUI。随包内置 `pubspec` 发行版 `0.2.1-rc.2` | 见上游仓库 | https://github.com/deepseek-ai/deepseek-harness |
| **Termux** | 运行时用户态：`files/usr` 前缀、`pkg`/`apt`/`dpkg`、bash、VT100 终端约定。APK 与 Termux 同包名 `com.termux` | GPL-3.0 | https://github.com/termux/termux-app |
| **PaddleOCR / PP-OCRv6** | 截图文字识别（`assets/ppocr6/` 的 `det_tiny` + `rec_tiny` ONNX）；可选 `medium` 档从 ModelScope 下载 | Apache-2.0 | https://github.com/PaddlePaddle/PaddleOCR |
| **microsoft/OmniParser** | 图标/无文字控件检测模型的来源（`icon_detect`），本项目蒸馏 + int8 量化后为 30 MB 单类模型 | 见上游仓库 | https://github.com/microsoft/OmniParser |
| **ONNX Runtime** | 设备端推理（`com.microsoft.onnxruntime:onnxruntime-android:1.31.1`，CPU provider） | MIT | https://github.com/microsoft/onnxruntime |
| **Qwen-Image 2.1** | 仅用于生成本仓库宣传海报的底图（`docs/promo/`），不参与 App 运行 | Apache-2.0 | https://github.com/QwenLM/Qwen-Image |
| **ComfyUI** | 桌面端跑 Qwen-Image 的工作流宿主（`docs/promo/qwen_t2i.py` 调用其 HTTP API） | GPL-3.0 | https://github.com/comfyanonymous/ComfyUI |
| **霞鹜文楷 LXGW WenKai** | 海报排版用中文字体（`docs/promo/render_banner_v2.py`） | OFL-1.1 | https://github.com/lxgw/LxgwWenKai |
| **JetBrains Mono** | 海报与终端示意里的等宽字体 | OFL-1.1 | https://github.com/JetBrains/JetBrainsMono |
| **AndroidX / Material Components** | 原生页面（设置、关于、引导、扩展市场）与悬浮窗 | Apache-2.0 | https://github.com/androidx/androidx |
| **Android `android.jar`**（`assets/android-stub/`） | 供内置 `javac` 编译插件用 | Apache-2.0 | https://source.android.com/docs/setup |

## 分发注意

- **Termux（GPL-3.0）**：APK 内包含其用户态前缀与终端实现。重新分发本 APK 时需一并提供对应源码获取方式；本仓库已开源全部改动。
- **ComfyUI（GPL-3.0）**：只在桌面出图时使用，不随 APK 分发。
- **模型权重**：PP-OCRv6、`icon_detect` 量化模型的许可以上游仓库为准；若用于商业产品，请先核对上游条款。
- 海报字体（霞鹜文楷、JetBrains Mono，OFL-1.1）仅用于仓库内的宣传图，不随 APK 分发。

## 上游与分叉

本项目是 [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) 的分叉（MIT，保留上游历史）。本仓库维护地址：[hazukieq/DSH-Mobile-with-Termux](https://github.com/hazukieq/DSH-Mobile-with-Termux)。
