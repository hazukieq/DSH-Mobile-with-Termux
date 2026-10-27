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

## banner-v4 排版修订（用户反馈）

用户看过 v3 后指出「字都是歪歪扭扭的」。排查结论：不是排版脚本的问题，而是**底图手机屏幕上
由扩散模型生成的“发光代码光带”**——在缩略图尺寸下就被读成歪扭的伪文字。

同时按用户新增要求做了四处修改：

1. **终端卡挪到玻璃卡上**：不再画一张独立的浮空终端卡，改为把 `$ dsh web / :3080 ready /
   $ scr dump / 1080x2376 / 37 nodes / $ /act tap / ok gesture` 这段真实等宽文字，按底图
   玻璃卡的倾角（-12.6°）直接排在那块磨砂玻璃卡内部，中心对准卡片实测中心 (1032,560)。
2. **背景提亮**：底图整体 lift（`v*0.92 + 36`，并轻度去饱和），黑底从 `#06070a` 抬到约
   `#2a2e36`，保留手机轮廓的蓝色边缘光。
3. **中文改霞鹜文楷**：`~/Ly*` → `LXGWWenKai-Regular.ttf`，副标题与说明行都用文楷；
   副标题字号 23 → 27（“完整…的底下文字再大些”）。
4. **顶部图标与同排文字平齐**：鲸鱼图标按 15px 大写字高的视觉中心（+5.1px）对齐
   `DEEPSEEK HARNESS` 基线，不再按图片顶部贴。

## banner-v7（定稿，用户逐轮验收）

- 底图：`source/qwen-hero-v6.png`（Qwen-Image 2.1，seed 4242，1600×896，机身青色轮廓光从右后方打来）
- 主标题：**DSH Mobile**，`DSH` 纯白 + `Mobile` 水平渐变（#4D6BFF → #5AE2C6）
  - 基线对齐：2× 画布下 `DSH` 与 `Mobile` 墨迹底边同为 y=471
  - 光学左对齐：墨迹左缘 111，与分隔线网格线 110 相差 1px（字形左侧含 8px 边距，故 x 从 110 收到 101）
  - 垂直居中：眉标墨迹中心 120.5、副标题墨迹顶 350.5 → 中点 235.5；标题墨迹中心实测 235.75
- 终端卡：白底不透明、横版 6 行、JetBrains Mono 16px
  - bbox `x 762–1115 / y 351–584`；顶边与「完整的 Agent 引擎…」墨迹顶（350）差 1px
  - 右缘 1115 ≈ 屏幕中线，即压在手机**屏幕左半边**；左半截探出机身在暗场里
- 信息轴（1600×900）：眉标 115 / 标题 196 / 分隔线 318 / 副标题 350 / 说明 408 / 胶囊 460 / 证据行 548 / 页脚 790
- 复现：`python3 docs/promo/render_banner_v2.py <hero.png> <outprefix>`，参数 `ONSCREEN=0 MONO_PX=16 CARD_DEG=0 SQUEEZE=1.0 SKEW=0 A_CX=951 A_CY=480`
