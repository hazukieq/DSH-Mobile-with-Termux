# 悬浮窗（StatusOverlay）设计说明

> 适用代码：`app/src/main/java/app/dsh/mobile/StatusOverlay.kt`、`app/src/main/res/layout/overlay_status.xml`。
> 本文只讲**结构与约束**，不讲历史版本；改动前请先读完「解耦原则」与「回归清单」。

## 1. 它是什么

一枚由无障碍服务（`TYPE_ACCESSIBILITY_OVERLAY`）持有的悬浮层，用来在其他 App 之上显示 DSH 的实时状态。
因为持有者是无障碍服务，**不需要**用户额外授权「显示在其他应用上层」。

交互固定为三种：

| 手势 | 效果 |
| --- | --- |
| 点击（位移 < 10dp） | 折叠球 → 展开卡片；展开卡片（点图标/正文区）→ 收起 |
| 拖动 | 移动窗口，松手吸附到最近屏幕边缘（竖屏左右、横屏左右；贴边实时读取屏幕尺寸） |
| 长按 | 隐藏悬浮窗 |

## 2. 两种核心形态（尺寸稳定定宽，零尺寸跳变）

所有窗口尺寸都在 `applyWindowSize(ctx, view, collapsed)` 里决定，保证展开与折叠态切换时尺寸清晰确定：

### 2.1 折叠球

- 尺寸：`COLLAPSED_BALL_DP = 36dp` 的正方形。
- 内容：只有 `overlayBallDot`（14dp 圆点，`layout_gravity=center`），其余子 View 全部 GONE。
- 圆点颜色：`tintDots()`（空闲灰 / 执行中蓝 / 完成红）。
- 交互：点击展开卡片；拖动移位松手吸边；长按隐藏。

### 2.2 展开卡片（恒定定宽 240dp）

- 尺寸：宽 **240dp 固定**；内部正文滚动区高度 **固定 92dp**，外加底部行固定占位，窗口高度与宽度恒定稳定。
- 内容：
  - 图标固定在左侧（`overlayDot`，不在 ScrollView 里），不随正文滚动移动。
  - 正文在右侧 ScrollView 内滚动，`fullScroll` 自动跟随到底部。
  - 底部行：`overlayFoot`（会话标题 + 耗时）在展开态始终保持占位。
  - 无障碍动作链：`overlayA11y` 仅在有无障碍动作时显示。
- 稳定性约束：**展开后无论是空闲还是工作，宽度始终为 240dp，禁止在空闲时缩成细条/自适应导致横向跳动**。流式吐字更新文本时纯在 View 层更新与滚动，绝不调用系统 `WindowManager.updateViewLayout`，确保流式输出零跳动零闪烁。
- 状态机单向锁定：**一旦展开，除非用户点击状态圆点收起，否则保持展开，绝不允许因空闲/定时器擅自折叠**。
- 收起交互：点击左侧状态圆点（`overlayDot`）收起为折叠球；正文区域（`ScrollView` / `TextView`）不挂载点击收起，保证滑动阅读不误触。

### 2.3 状态点的三种颜色

| 颜色 | 含义 | 触发点 |
| --- | --- | --- |
| 灰 `COLOR_IDLE` | 空闲 | `setIdle()` |
| 蓝 `COLOR_WORK` | 执行中 | `setStatus()`（收到新状态） |
| 红 `COLOR_DONE` | 整轮结束、等待用户看回复 | 只由 `AgentBridge.onTurnEnd`（WebUI `turn/end`）触发，即「提问→思考→最终回复」整轮结束 |

红点不会改写正文，任何新状态或用户操作都会清红。

## 3. 解耦与稳定性原则（改这里必须遵守）

1. **图标与滚动解耦**：`overlayDot` 是 ScrollView 的兄弟节点、不是子节点。正文滚动时图标必须静止。
2. **窗口尺寸单入口**：只有 `applyWindowSize()` 能写 `LayoutParams` 宽高；其它地方只改内容与可见性。
3. **流式更新不调 WindowManager**：`setStatus()` 仅更新 TextView 文字并在 View 层平滑滚动，绝不调用 `applyExpandLevel`、`safeUpdateLayout` 或 `anchorToEdge`。
4. **贴边坐标不随内容抖动**：`anchorToEdge` 仅在「折叠球 ↔ 展开卡片」档位切换、用户拖拽放手吸边或屏幕旋转时计算，流式文本更新期间坐标完全冻结。
5. **展开态防误触**：正文滚动区不挂收起事件，仅点击状态圆点收起。
6. **避让截图不销毁 Surface**：使用 `root.visibility = View.GONE` 避让截图，禁止使用 `removeViewImmediate` 导致画面硬闪烁。
7. **可见性由档位决定**：`applyExpandLevel()` 统一设置各控件的可见性，不在别处零散设置。
8. **颜色只走 `tintDots()`**：球态点、展开态图标都由它上色，避免两处颜色不一致。
6. **不要用 margin 魔数做居中**：球态靠 `layout_gravity=center`，展开态靠行内 `layout_gravity` 与固定 `layout_marginTop`（图标对齐首行）。
7. **绝不 removeView/addView 来「改外观」**：摘挂窗口会重建 surface，用户看到的就是「一闪一闪」。
   - 截图/读屏避让：改成 `root.visibility = GONE`（不参与绘制、命中测试、无障碍树，窗口不动），结束后 VISIBLE。
   - 档位切换：只 `safeUpdateLayout(wm, view, params)` + `requestLayout()`。
   - 例外只有一种：窗口首次创建（`show()`）时 `addView` 一次。
8. **窗口初值必须是「折叠球尺寸」**：用 `WRAP_CONTENT` 初始化时，首帧会按 XML 自然尺寸（实测 178×79 横条）画出来再变成 99×99 → 每次显示都闪一下。
9. **展开态整张卡片可点，但正文滚动优先**：`root / statusRow / statusTv / statusScroll / footRow / a11yText / ballDot / elapsedText` 都挂 `collapseToBall()`；ScrollView 仍负责滚动（不滚动的点击视为「收起」）。不要为了「只让图标可点」而把其它子 View 的 clickable 关掉——用户会点空白处然后说「点不动」。

### 3.x 两条硬不变量（2026-11 踩过）

- **`overlayScroll` 的 `weight` 绝不能被清零。** 它的 `layout_width=0dp + layout_weight=1` 靠横向权重撑开；
  任何「顺手把 weight 归零」的写法都会让文字宽度变成 0 —— 卡片照画、圆点也在，但**文字全被裁掉**，
  表现就是「展开态文本不显示」。只允许改 `height`。
- **空闲态与工作态同宽同高**（240dp × 92dp 滚动区 + 底行）。不做「空闲自动缩成一行」的适配：
  那会让卡片在空闲/工作之间跳变，用户会读成「它自己折叠了」。



### 3.y 截图避让的正确姿势：窗口 alpha=0（不是 GONE / 不是摘窗口）

- `beginCaptureAvoid()`：`params.alpha = 0f` + 临时 `FLAG_NOT_TOUCHABLE` → `updateViewLayout`；`endCaptureAvoid()` 原样恢复。
- 为什么不用 `visibility=GONE`：`takeScreenshot` 抓的是**已合成缓冲**，GONE 只是「下一帧不画」，上一帧的悬浮窗还会被拍进去。
- 为什么不用 `removeView/addView`：摘窗口会触发重排/重测，历史上引发过「展开态文字被裁成 0 宽、卡片空框、漏挂回」一连串问题。
- 截图前顺手 `sweep` 掉 `liveViews` 里的重复窗口：只对 root 设 alpha 时，历史副本仍会被拍到。
- 现场自检：logcat 应有 `capture avoid: alpha=0.0 ... applied=true`。

## 4. 状态机速查

```
折叠球 --点击--> 展开(空闲) --setStatus(非空闲)--> 展开(工作)
   ^                  |                              |
   |                  +-------- 点击图标/正文 --------+
   |                                                 |
   +-- setIdle()/turn end（自动收球：仅当是本层自动展开的）--+
```

自动收球的前提是 `autoExpandedByStatus`：用户手动展开的档位不会被自动收起。

## 5. 回归清单（每次改动都要过）

- [ ] 折叠球是正方形，圆点在正中，点一下能展开。
- [ ] 展开空闲：卡片贴合「图标 + 空闲」，不留大片空白。
- [ ] 展开工作：宽度 240dp、高度固定、正文在 92dp 区域内滚动，图标不随滚动移动。
- [ ] 长正文滚动到底部，最后一行可见。
- [ ] 一轮对话结束，圆点变红；再次发消息变蓝。
- [ ] 旋转屏幕 / 分屏后贴边仍然正确。
- [ ] 长按能隐藏，隐藏后没有残留的常驻通知。
- [ ] **空闲态是自适应的**（宽度贴着「图标 + 空闲」，右边没有 240dp 空白；也不是 34dp 细条）。
- [ ] **工作态是 240dp 定宽 + 92dp 固定滚动区**，正文长短不改变窗口高度。
- [ ] 空闲 ↔ 工作切换时只看得到内容变化，**没有窗口重挂/闪白**（可通过 `[win] level=... 尺寸` 日志确认没有尺寸跳变或重挂）。
- [ ] 展开态点卡片上任意位置（含空白、动作行、正文）都能收起；正文区可以正常滚动。
- [ ] `logcat -s StatusOverlay` 里没有 `cleared stale NOT_TOUCHABLE`、`opsAvoid watchdog fired` 之外的告警。
