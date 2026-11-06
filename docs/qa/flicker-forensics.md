# 悬浮窗闪烁 / 输入可达性：真机取证判据

> 真机：`5f08bc20` / M2012K11AC（alioth）/ **LineageOS 23.2 = Android 16 (SDK 36)** / build `BP4A.251205.006` / userdebug / Magisk root（`su -c id` → `uid=0(root) context=u:r:magisk:s0`）。
> 被测：`com.termux`（基线 2.9.64/635）。悬浮窗 = `TYPE_ACCESSIBILITY_OVERLAY`（type 2032）。
> 工具：`scripts/probes/flicker/`。**所有耗时/频率均为本机 `date +%s%3N` 前后取差实测**，不是估计。

---

## 0. TL;DR：四类「闪」判据表

| 类型 | 一句话判据 | 权威命令 / 字段 | 采样上限 |
|---|---|---|---|
| **(a) 窗口真被摘掉** | `--list` 里该 layer **不存在**（`exists=0`）**且** `dumpsys window windows` 里没有 `ty=ACCESSIBILITY_OVERLAY` 块（`found=0`） | `dumpsys SurfaceFlinger --list` + `dumpsys window windows` | 17~23Hz / 14Hz |
| **(b) 窗口在但没内容** | `--list` 有 layer，但完整 SF dump 里 **`seen=0`**（没进 Composition list、没有 Output Layer）；或 Input list 给出 **`invisible reason=nothing to draw`** / **`hidden by parent or layer flag`**；或 `visibleRegion` 非空而 **`visibleNonTransparentRegion count=0`** | `dumpsys SurfaceFlinger` | 12Hz |
| **(c) 内容在但位置/尺寸跳变** | 相邻采样 `displayFrame` 变；或 WMS `frame≠last_frame` | 完整 SF dump 的 `displayFrame=` / `dumpsys window windows` 的 `Frames:` | 12Hz / 14Hz |
| **(d) 陈旧帧（复用旧 buffer）** | `reusedClientComposition=true`；或 `clientCompositionReusedFrames` 单调递增；或帧内容没变而状态变了 | 完整 SF dump `Composition Display State` / `dumpsys SurfaceFlinger --timestats -dump` | 12Hz |
| **点不到** | inputConfig 含 `NOT_TOUCHABLE`/`NOT_VISIBLE`；或 `touchableRegion=<empty>`；或诊断点不在 region；或点上 z 序首位不是它 | `dumpsys input` 的 `Windows:` 行 | 12Hz |

**判别顺序**（一步否决，不用全跑）：
```
--list exists=0 ────────────────► (a) 真摘除
  exists=1
    └ 完整 SF dump seen=1 且 nt_cnt>0 ─► 在画 → 位置变? (c) : OK
    └ 完整 SF dump seen=0 ─────────────► (b) 无内容（再看 input list 的 reason 细分）
    └ comp_vis=visible 但 nt_cnt=0 ────► (b) 全透明
另有 reusedClientComposition=true ──► (d) 陈旧帧（可与上面任一并存）
```

---

## 1. 已验证可用命令（含真实耗时与可循环频率）

| 命令 | 单次耗时 | 循环频率 | 用途 | 备注 |
|---|---|---|---|---|
| `dumpsys SurfaceFlinger --list` | **46ms** | **17~23Hz** | 层对象存在性（(a)/(b) 第一判据） | 含**全部 requested layer**，包括不可见/无 surface 的 |
| `dumpsys SurfaceFlinger` | **70ms** | **12Hz** | 合成真值：是否进合成 / 区域 / 透明度 / 复用 | ~1050 行；root 才有完整 Output Layer |
| `dumpsys window windows` | **68~90ms** | **14Hz** | 应用自述：View 可见性 / frame / surface | 453 行 |
| `dumpsys input` | **72ms** | **12Hz** | 可达性：touchableRegion / inputConfig / 焦点 | 694 行 |
| `dumpsys SurfaceFlinger --timestats -dump` | **69ms** | **12Hz** | (d) 计数器：`clientCompositionReusedFrames` | 计数器是累计值，看差分 |
| `dumpsys window <NAME>` | 47~91ms | — | 局部窗口（子串匹配，支持 `visible`/`surfaces`/`a11y`） | 比全量省一半 |
| `dumpsys SurfaceFlinger --latency '<Name>#<id>'` | 一次性 | — | 该层的 present 时间戳 | **必须带层名**，见下 |
| `screenrecord --size 540x1200 --bit-rate 8M --time-limit N` | — | — | 视觉真值 | **只在画面变化时出帧** |
| `logcat -v epoch <tags>` | — | 事件级 | Tier0 事件时钟（毫秒 + reason 字符串） | 已知 tag：`StatusOverlay`/`DshA11y`/`EngineService` |
| `getprop` / `cat /proc/<pid>/...` / `screencap` | — | — | 辅助 | 均可用 |

### 不可用 / 有坑（替代方案）

| 坑 | 现象 | 替代 |
|---|---|---|
| `dumpsys SurfaceFlinger --latency`（不带层名） | 只回一行 `11111111`，无数据 | 带层名；**空名 layer 仍无数据**（见下） |
| `--latency '#10474'`（无障碍悬浮窗，层名为空） | 同样只有 `11111111` | 用 `--timestats` 的帧计数 + `frame-counter`（FramebufferSurface BufferQueue）替代 |
| `--frame-stats` / `--layer-stats` / `--help` | 未知参数被当默认命令 → **回落成完整 dump**（慢 15×，会毁掉采样节奏） | 只用已确认的参数 |
| `dumpsys gfxinfo com.termux` | **807ms**，且只统计 app 自身渲染，**不覆盖 overlay 合层** | 只做低频对照；闪烁判据不依赖它 |
| `dumpsys window surfaces` | 只 2 行、无 surface 内容 | 用完整 SF dump 的 Output Layer 段 |
| `dumpsys input` 的 `RecentQueue` | 只有事件类型 + age（`MotionEvent, age=353112ms`），**不含投递目标** | 看 `TouchStatesByDisplay`（仅按住期间有内容）或静态推断（见 §4） |
| Perfetto | `/system/bin/perfetto` v51.2 有 `android.surfaceflinger.layers/frame/frametimeline`；**没有** transactions/windowmanager/input 数据源；设备与宿主都**无 trace_processor** | 仅作可选存档；主判据不依赖 |
| `mViewVisibility` / `ty=` 的数值 | `ty=` 打的是**符号名** `ACCESSIBILITY_OVERLAY`，2032 只在 `mToken=WindowToken{... type=2032 ...}` | 两种都匹配 |
| `mAttrs=(x,y)` 当屏幕坐标 | 它是**相对 parent frame**；本机 status bar 高 95px → `mAttrs y=330` ↔ `frame y=425` | 一律用 `Frames: ... frame=[l,t][r,b]` |
| `bounds={...}` 当 `[l,t][r,b]` | 实际顺序是 **`{left, top, bottom, right}`**，如 `{0,0,218,1080}` = 宽 1080 高 218 | 按 4 个整数解析：l,t,b,r |

---

## 2. 现场事实（判据的地基，均为实测）

```
# 窗口（WMS）——窗口名就是裸包名（StatusOverlay 没 setTitle）
Window #2 Window{73085c8 u0 com.termux}:
  mAttrs={(959,330)(99x99) ... ty=ACCESSIBILITY_OVERLAY fmt=TRANSLUCENT
  mToken=WindowToken{9aa094e type=2032 ...}
  mViewVisibility=0x0 mHaveFrame=true mObscured=false
  mHasSurface=true isReadyForDisplay()=true
  Frames: parent=[0,95][1080,2400] display=[0,95][1080,2400] frame=[959,425][1058,524] last=[959,425][1058,524]
  mSurface=Surface(name=#10474)/@0x381c36        ← surface 名是空的
  Surface: shown=true mDrawState=HAS_DRAWN mLastHidden=false

# 层（--list）——同一窗口两层：
RequestedLayerState{73085c8 com.termux#10473 parentId=9960}   ← WindowToken 层（有名字）
RequestedLayerState{#10474 parentId=10473}                     ← surface 层（名字空！）

# 跨工具 join key：input 的 `id=` == SF 的 `#<id>`
5: name=73085c8 com.termux, id=10474, ..., touchableRegion=[959,425][1058,524], ownerPid=21666, ...
```

**要点**
1. `--list` 是「层对象是否存在」的唯一权威，且包含**完全不可见**的层 → 它证明对象在，不证明在画。
2. 完整 SF dump 的 `Composition list` / `Display N HWC layers` / `- Output Layer` 段**只含真正参与合成的层**；不在里面 = 没画（(b) 的核心）。
3. `Input list` 段列出**带 input 信息的全部层**（含不可见），并给 `invisible reason=...` ——这句话是 (b) 的直接字符串证据，务必捞。
4. 目标 layer id / pid 每次装机都会变（2.9.64 是 `#10474`/`pid 21666`）→ 脚本必须每次重新 discover。

---

## 3. 四类判据详解

### (a) 本被摘掉（layer 从 SurfaceFlinger 列表消失）
**证据链（三选二即判定，全有则铁证）**
1. `dumpsys SurfaceFlinger --list` 中找不到 `#<id>` → `exists=0`（此时 surface 层与 WindowToken 层应同时消失）
2. `dumpsys window windows` 里没有 `ty=ACCESSIBILITY_OVERLAY` 块 → `found=0`
3. logcat 出现 `StatusOverlay: overlay torn down (engine exited)` / `EngineService: task removed: exiting completely (engine + overlay)`

**易混点**：`mHasSurface=false` / `isReadyForDisplay()=false` 但窗口块还在 → 是 **(b)**，不是 (a)（对象还在，只是没 surface）。

### (b) 窗口在但 buffer 空 / 全透明
三个子情形，按「哪一层先暴露」排序：

| 子情形 | 证据 | 含义 |
|---|---|---|
| 有层、无内容 | `--list exists=1` + 完整 dump `seen=0`（无 Composition list 项、无 Output Layer） | 层对象在，但这一帧没进合成 |
| Input list 给出原因 | `invisible reason=nothing to draw` | 无 buffer / alpha=0 / sourceCrop 为空 |
| | `invisible reason=hidden by parent or layer flag` | **View.INVISIBLE / setVisibility(false)** ← 本项目 `hideNow()` 的主路径 |
| 区域在、但全透明 | `visibleRegion count>0` 而 `visibleNonTransparentRegion count=0` | 位置占着，像素全透（alpha 或内容透明） |
| WMS 侧佐证 | `mViewVisibility=0x4` / `Surface: shown=false` / `mLastHidden=true` / `mHasSurface=false` | 应用侧确实隐藏了 |

> 注意 `dumpsys window windows` 的 `mViewVisibility` 与 SF 的 `reason=hidden by parent` 是**同一条因果链的两端**：View.INVISIBLE → RenderNode 不生成 → 层无 buffer → 不进合成。两端同时采到 = 可证明「闪是应用自己藏的，不是合成器丢帧」。

### (c) 位置 / 尺寸跳变
- **SF 侧**：同一 `#id` 的 `displayFrame=[l t r b]` 相邻采样不同（`frames.csv` 的 `dispframe` / `hwc_dispframe`）。
- **WMS 侧**：`Frames: frame=` 与 `last=` 不一致（同一帧内不一致 = 正在布局/动画中），或相邻采样 `frame` 变。
- **区分动画 vs 跳变**：连续多帧单调变化 = 动画；单帧跳变后稳定 = 布局跳变（`mLayoutSeq` 会一起跳）。

### (d) 陈旧帧（合成器重复提交旧帧 / 双缓冲复用）
| 证据 | 位置 | 说明 |
|---|---|---|
| `reusedClientComposition=true` | 完整 dump `Composition Display State` | 本帧复用了上一帧的 client target（(d) 的直接布尔量） |
| `clientCompositionReusedFrames` 递增 | `--timestats -dump` | 全局累计计数，采样间差分 |
| `frame-counter` 不涨而 `displayFrame` 不变 | `Composition RenderSurface State`（FramebufferSurface BufferQueue） | 屏幕确实没被重画（真静止，不是闪） |
| 视觉真值 | screenrecord 逐帧 ink_ratio | 状态说「可见」但画面几帧不更新 = 显示链路延迟 |

**本项目的设计内佐证**：`StatusOverlay.beginCaptureAvoid()` 在 `hideNow()` 之后 `Thread.sleep(120L)`，源码注释写的就是「让合成器吃掉这一帧，双缓冲陈旧帧由截图侧 warmup 处理」——即代码自己承认存在 (d) 这一档，且它正是「截图里还看得到悬浮窗」的根因。取证时把 logcat 的 `capture avoid: begin` 时间戳与录像帧时间戳对齐，就能证明某次截图拿到的是陈旧帧。

---

## 4. 「点不到」

**判定链（从便宜到贵）**
```
1) WMS flags : mAttrs ... fl= 里有没有 NOT_TOUCHABLE；本机悬浮窗 = NOT_FOCUSABLE | NOT_TOUCH_MODAL（可触摸）
2) input 配置 : inputConfig 是否含 NOT_TOUCHABLE / NOT_VISIBLE；touchableRegion 是否 <empty>
3) 几何      : 诊断点是否落在 touchableRegion 内（covers_point）
4) z 序拦截  : stack_at_point 首位是不是它（0 = 最顶）
5) 实测投递  : TouchStatesByDisplay 在按住期间记录的真实状态（最硬）
```

**本机正常基线**（悬浮窗在场时）：
```
5: name=73085c8 com.termux, id=10474, ..., inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY,
   touchableRegion=[959,425][1058,524], ownerPid=21666, ownerUid=10278, canOccludePresentation
```
→ `not_touchable=0, not_visible=0, covers_point=1`。

**点不到的四种典型形态**
1. `inputConfig` 多出 `NOT_TOUCHABLE`（应用加了 `FLAG_NOT_TOUCHABLE`）或 `NOT_VISIBLE`（View 隐藏时期）
2. `touchableRegion=<empty>`（region 被清空 / 尺寸为 0）
3. region 不含目标点（窗口挪走、`mTouchableInsets` 裁边）
4. 点在**更高层**：本机实测 z 序最前的是 `PointerEventDispatcherOverlay0`、`[Gesture Monitor] swipe-up`、`[Gesture Monitor] edge-swipe` —— 落在边缘手势区时触摸会被手势层吃掉（这属系统正常行为，不代表应用 bug）。
5. 旁证：`scripts/smoke-overlay.sh` 实测 `input tap` 打到 TYPE_ACCESSIBILITY_OVERLAY 上**会丢**（需重试 3 次）——「点不到」是真实现象，不是脚本错觉。

---

## 5. 基线采集命令序列（可直接照抄）

### 一键（推荐）
```bash
cd scripts/probes/flicker
./selftest.sh                 # 先确认解析器没坏（不需要设备）
./capture.sh 15 --light       # 装机/复验时最轻：logcat + layers + frames
./capture.sh 20               # 完整：+ window + input
./capture.sh 20 --record      # 再加 screenrecord 视觉真值
# 产物：/tmp/flicker-evidence/run-<ts>/{layers,frames,window,input}.csv logcat.txt report.md
```

### 手工只读快照（四条就够定位一次「闪」）
```bash
S=5f08bc20
adb -s $S shell su -c 'dumpsys SurfaceFlinger --list'        | grep -E 'termux|#[0-9]+ parentId'
adb -s $S shell su -c 'dumpsys window windows'               | grep -A20 'Window.*com\.termux'
adb -s $S shell su -c 'dumpsys SurfaceFlinger'               | grep -nE '#10[0-9]+|invisible reason'
adb -s $S shell su -c 'dumpsys input'                        | grep -E 'com\.termux|FocusedWindows|TouchStatesByDisplay'
```
高频循环模板（**在设备上跑，别每拍一次就 adb 一次**）：
```bash
adb -s $S shell "su -c 'sh /data/local/tmp/flicker/sampler.sh list 10 <tid> <tkn>'"
adb -s $S shell "su -c 'sh /data/local/tmp/flicker/sampler.sh sf   10 <tid>'"
adb -s $S shell "su -c 'sh /data/local/tmp/flicker/sampler.sh win  10 <tid>'"
adb -s $S shell "su -c 'sh /data/local/tmp/flicker/sampler.sh input 10 <tid> \"\" 1000,470'"
```

---

## 6. 观测者效应（必读）

- 4 路并行 ≈ **50 次 dumpsys/s**。每个 dumpsys 都要拿全局锁、遍历合成状态，**本身会拖慢合成** → 可能「造出」闪烁。
- 对策：装机/复验用 `--light`（只 28 次/s）；或用 `--calm` 错峰；或用 **logcat-only 对照组**（只录事件不采样）判断某次闪烁是否只在采样时出现。
- 设备侧 `date` 每 fork ≈13ms；`date +%s%N` 的 19 位数在设备 shell 算术里会溢出成负数 → 一律用 `%s%3N`。
- 采样时间戳取的是「dumpsys 开始前」的时刻，单次 dump 内跨越 ~70ms → 事件边界精度 ≈ 70ms；要更细粒度只能靠 logcat（毫秒）。

---

## 7. 已知缺口（下一轮实测补）

1. **隐藏期间 layer 是否仍留在 `--list`**：现有判据来自「本来就不可见的层（Taskbar/NotificationShade/Dim layer）不在完整 dump、但从不出现在 Composition list」+ 合成 fixture 推演；**尚未在真实 `hideNow()` 期间同时抓 `--list` 与完整 dump**。这是唯一需要现场补的经验点（用 `./capture.sh 20` 在 `capture avoid` 期间抓一次即可闭环）。
2. `TouchStatesByDisplay` 在真实按住期间的完整格式（需要一次人为长按 + 采样，当前纪律下未做）。
3. 空名 layer 的 `--latency` 无数据（替代已给：timestats/frame-counter）。
4. 无 `trace_processor`，Perfetto 只能留原始 trace 存档。

---

## 8. 附录：字段速查

**`--list` 行**：`RequestedLayerState{<name>#<layerId> parentId=<n> [z=<n>]}` —— 名字可能为空（`RequestedLayerState{#10474 parentId=10473}`），匹配必须按 `#id`。

**完整 SF dump 段序**：`Active Layers` → `Composition list (top to bottom)`（只含在合成的）→ `Input list`（含不可见 + reason）→ `Layer Hierarchy` → `Offscreen` → `Mergeable` → `Display N HWC layers` → `Window Infos` → `Displays`（`Composition Display State` / 每层 `- Output Layer 0x..(#id)` / `Composition RenderSurface State`）。

**层块关键字段**：`visible reason=<空> buffer=<handle> frame=<n>`；`bounds={l,t,b,r}`；`toDisplayTransform={ tx= ty= }`；`input{(FLAGS) touchableRegion={l,t,r,b}}`；`Region visibleRegion (count=N)` + bbox；`Region visibleNonTransparentRegion (count=N)` + bbox；`displayFrame=[l t r b]`；`sourceCrop=[l t r b]`；`hwc: layer=0x.. composition=DEVICE (2)`。

**`Composition Display State`**：`isEnabled/isSecure/usesDeviceComposition/usesClientComposition/flipClientTarget/reusedClientComposition`。

**logcat 事件表**（`StatusOverlay` tag）：`hide(<reason>): visibility=4` / `show(<reason>): visibility=0` / `capture avoid: begin` / `capture restore: done` / `opsAvoid on|off` / `opsAvoid watchdog fired ... force show` / `overlay hidden (auto)` / `overlay torn down (engine exited)`。

**本工具集文件**：`scripts/probes/flicker/{lib.sh,layers.sh,frames.sh,window.sh,input.sh,logcat.sh,record.sh,capture.sh,analyze.py,selftest.sh}` + `device/`（5 个 awk 解析器 + sampler.sh）+ `testdata/`（13 个合成 fixture，`./selftest.sh` 37 项断言）。
