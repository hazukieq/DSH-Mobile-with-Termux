# DSH Mobile GitHub 宣传图设计与制作规格

- 版本：banner-v2；日期：2026-10-28（上一版 banner-v1：2026-10-01）。
- 目标：GitHub README 首屏横幅 + 仓库 Social preview 1280 × 640。
- 主题变更（用户要求）：从「DSH Mobile 产品页」改为 **DeepSeek 与 Termux 的融合创意，苹果发布会风格**。

## 视觉方案

深黑底 + 单点冷光。左半屏是纯黑负空间与排版，右半屏是一台悬浮 Android 手机：金属边被
蓝宝石色轮廓光勾出，**屏幕全黑不通电**，手机前方浮着一块磨砂玻璃卡片；画面右下再叠一枚直排
终端玻璃卡（真实 JetBrains Mono 等宽字，写 `scr dump` / `/act tap` 的真实回执）。

> 为什么屏幕是黑的：v4 让模型在屏幕里画了「抽象代码光带」，缩略图里看着像乱码文字；
v5 直接把屏幕提示词改成 `screen is completely off and pitch black`，再把真正的命令回执
用字体排上去——一切文字都必须由 Pillow 画，模型不画字。

- **融合语义**：深蓝 = DeepSeek，薄荷绿（`#8DF0C8`）= Termux 终端；品牌行写
  `DEEPSEEK HARNESS × TERMUX`，其中 TERMUX 是绿色等宽字的玻璃胶囊，即「两个世界拼成一个 App」。
- **文案层级**（苹果式：主张 > 定位 > 证据）：
  1. `DeepSeek` / `on Android.` —— 112px 粗体，第二行自上而下从 `#607CFF` 渐变到 `#58E4C4`；
  2. `完整的 Agent 引擎，跑在你的手机上。`（29px）+ `终端 · 引擎 · 无障碍自动化 —— 一个 APK 全都有。`（20px）；
  3. 三枚玻璃胶囊：`No Root`、`Termux 内核`、`本地推理`；
  4. 证据行（等宽）：`scr dump · ppocr6 · int8 UI detector`；
  5. 页脚：仓库地址、`OPEN SOURCE · MIT · ANDROID`、右下角版本号。
- 不使用「完全离线」「数据永不离开设备」这类绝对表述；`本地推理` 指 OCR 与控件检测在本机运行。

## 生成方式（重要）

**图像由本地 Qwen-Image 2.1 生成，文字由 Pillow 排版**——不让扩散模型画字（实测会产出
乱码字形与臆造符号，见 v3 被否）。

1. `qwen_t2i.py`：向本机 ComfyUI（`127.0.0.1:8188`）提交 API 图；模型
   `qwen_image_2.1_int8_convrot` + `qwen3vl_8b_int8_convrot` + `qwen_image_2.1_vae_bf16`；
   采样 `euler/simple`、28 步、cfg 3.0、`ModelSamplingAuraFlow` shift 3.1、1600×896、seed 31337（v5）。
   一次约 100–120 s。
2. 提示词（精选）：`Apple keynote hero product shot, pure black background, one modern Android
   smartphone ... screen is dark with only soft abstract horizontal bars of cyan and mint light ...
   absolutely no characters, no letters, no numbers, no symbols on screen ... huge clean black
   negative space on the left half ... 16:9`。
3. `render_banner_v2.py`：2× 画布合成——cover 裁切底图、左侧渐变压暗、字体排版、玻璃胶囊、
   渐变标题、页脚；导出 1600×900 / 3200×1800 / 1600×900 webp / 1280×640（等比缩放后补黑边，不裁文字）。

## 输出文件

- `dsh-mobile-github.png` 1600 × 900（README 首屏）
- `dsh-mobile-github@2x.png` 3200 × 1800
- `dsh-mobile-github.webp` 1600 × 900（网页轻量副本）
- `dsh-mobile-social.png` 1280 × 640（社交预览）
- `source/qwen-hero-v5.png`：选用的生成底图（未叠字，屏幕全黑）
- `qwen_t2i.py` / `render_banner_v2.py`：可复现的生成与排版脚本
- `manifest.json`：模型、seed、提示词要点、字体、sha256 清单
- `samples/`：点击链路文档用的真机标注图（见 `docs/CLICK_CHAIN_UPGRADE.md`）

## 范围

只改宣传图与其制作文件、README 首屏图片引用（路径不变）。不改产品代码、不改 App 版本、不发布。
