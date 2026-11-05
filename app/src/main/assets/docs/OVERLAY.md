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

## 2. 三种形态（尺寸只有一个真源）

所有窗口尺寸都在 `applyWindowSize(ctx, view, collapsed)` 里决定，**XML 里一律 `wrap_content`**，不允许在别处写宽高。

### 2.1 折叠球

- 尺寸：`COLLAPSED_BALL_DP = 36dp` 的正方形。
- 内容：只有 `overlayBallDot`（14dp 圆点，`layout_gravity=center`），其余子 View 全部 GONE。
- 圆点颜色：`tintDots()`（空闲灰 / 执行中蓝 / 完成红）。

### 2.2 展开·空闲

- 触发条件：展开且正文文案等于「空闲」（`idleCompactNow = true`）。
- 尺寸：宽高都 `wrap_content` —— 内容就是 `图标 + 「空闲」`，卡片自动贴合。
- 结构：`图标（固定） + ScrollView(正文)`，此态 ScrollView 高度也 wrap。
- 视觉：`padding 10/8/12/8`，深色底、16dp 圆角。

### 2.3 展开·工作

- 触发条件：展开且正文不是「空闲」。
- 尺寸：宽 **240dp 定宽**；高 = padding + **固定 92dp 的正文滚动区**（窗口高度不随正文长短变化）。
- 结构：图标固定在左侧（**不在 ScrollView 里**），正文在右侧 ScrollView 内滚动；`fullScroll` 自动跟到底部。
- 附加行：`overlayA11y`（无障碍分类）与 `overlayFoot`（会话标题 + 耗时）仅工作态可见。

### 2.4 状态点的三种颜色

| 颜色 | 含义 | 触发点 |
| --- | --- | --- |
| 灰 `COLOR_IDLE` | 空闲 | `setIdle()` |
| 蓝 `COLOR_WORK` | 执行中 | `setStatus()`（收到新状态） |
| 红 `COLOR_DONE` | 整轮结束、等待用户看回复 | 只由 `AgentBridge.onTurnEnd`（WebUI `turn/end`）触发，即「提问→思考→最终回复」整轮结束 |

红点不会改写正文，任何新状态或用户操作都会清红。

### 2.0 🔴 不可违背：展开态「空闲」与「工作」是两种**完全不同**的布局

这是本组件最容易被后续修复改坏的一点——历史上两次「顺手统一尺寸」都把它改错了，用户直接看到「空闲不自适应」/「展开后自己缩回小球」。**任何改窗口尺寸、改可见性、改点击范围的修复，都必须先确认下面这张表仍然成立：**

| 维度 | 展开·空闲（`idleCompactNow = true`） | 展开·工作（`idleCompactNow = false`） |
| --- | --- | --- |
| 判定条件 | 正文文案 == `R.string.overlay_idle`（**只看文案，不看 `working` 标志**） | 正文文案不是「空闲」 |
| 宽度 | `wrap_content`（贴着 图标 +「空闲」走，自适应；**不许写死 240dp**） | **固定 240dp**（`dp(ctx, 240)`） |
| 高度 | `wrap_content`（内容一行，卡片自然高度；**不许缩成 34dp 细条**） | `wrap_content`，但正文区**固定 92dp** → 窗口高度稳定、不随正文长短跳动 |
| 正文滚动区 | 高度 wrap，内容就一行，不滚动 | 高 92dp，`fullScroll` 自动跟到底部 |
| 附加行 `overlayA11y` / `overlayFoot` | GONE | VISIBLE |
| 用户观感 | 一张贴着字的小卡片 | 一张固定宽、正文在框内滚动的卡片 |

「空闲缩成 34dp 细条」与「空闲也固定 240dp」**都是错的**：
- 34dp 细条 → 看起来像「展开后自己折叠了」，用户会当成状态机 bug（实际只是尺寸变了）；
- 固定 240dp → 空闲时右边一大片空白，用户会说「空闲状态又不自适应了」。

改这里之前先问自己：**我改的是尺寸规则，还是状态机？** 状态机（`expandLevel` / `autoExpanded`）不允许因为「省空间」「统一视觉」被顺手改掉。

## 3. 解耦原则（改这里必须遵守）

1. **图标与滚动解耦**：`overlayDot` 是 ScrollView 的兄弟节点、不是子节点。正文滚动时图标必须静止。曾把图标做成 `TextView.drawableStart`，结果图标跟着正文一起滚动消失。
2. **窗口尺寸单入口**：只有 `applyWindowSize()` 能写 `LayoutParams` 宽高；其它地方只改内容与可见性。
3. **文案变化必须重排**：`setStatus()` 末尾调用 `applyExpandLevel()`。否则从空闲切到工作时会沿用空闲的 wrap 布局，出现「正文被压成一行」。
4. **可见性由档位决定**：`applyExpandLevel()` 统一设置 `statusRow / statusScroll / a11yText / footRow / ballDot` 的可见性，别在别处 set。
5. **颜色只走 `tintDots()`**：球态点、展开态图标都由它上色，避免两处颜色不一致。
6. **不要用 margin 魔数做居中**：球态靠 `layout_gravity=center`，展开态靠行内 `layout_gravity` 与固定 `layout_marginTop`（图标对齐首行）。
7. **绝不 removeView/addView 来「改外观」**：摘挂窗口会重建 surface，用户看到的就是「一闪一闪」。
   - 截图/读屏避让：改成 `root.visibility = GONE`（不参与绘制、命中测试、无障碍树，窗口不动），结束后 VISIBLE。
   - 档位切换：只 `safeUpdateLayout(wm, view, params)` + `requestLayout()`。
   - 例外只有一种：窗口首次创建（`show()`）时 `addView` 一次。
8. **窗口初值必须是「折叠球尺寸」**：用 `WRAP_CONTENT` 初始化时，首帧会按 XML 自然尺寸（实测 178×79 横条）画出来再变成 99×99 → 每次显示都闪一下。
9. **展开态整张卡片可点，但正文滚动优先**：`root / statusRow / statusTv / statusScroll / footRow / a11yText / ballDot / elapsedText` 都挂 `collapseToBall()`；ScrollView 仍负责滚动（不滚动的点击视为「收起」）。不要为了「只让图标可点」而把其它子 View 的 clickable 关掉——用户会点空白处然后说「点不动」。

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
