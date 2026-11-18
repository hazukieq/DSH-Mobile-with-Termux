---
name: a11y-tap-discipline
description: DSH Mobile 操作其他 App 界面的点击纪律。读 scr dump 或 /scene、选择点击坐标、点击没反应、判断节点/OCR/自绘控件、处理悬浮窗或坐标偏差时使用。纯代码/文件任务不触发界面操作。
---

# 先定层、取界，再读回执

只操作用户任务所需的界面。开始前按需要阅读 `docs ACCESSIBILITY` §7–11、
`docs CLICK_CHAIN_UPGRADE` 的 /scene 与 tapPoint 说明；不要把文档已解释的机制再做一轮实验。

## 1. 确认当前屏幕和坐标来源

- 用 `scr dump` 或 `/scene` 确认前台包名、屏幕尺寸、目标区域；前台不符时不要解释或点击这棵树。
- 按节点的 `source`、文字/desc、可点击性判断当前可用通道。WebView 也可有完整 a11y 树；首屏节点少可能尚未加载、被遮挡或分页，不等于 Canvas。
- 确认新鲜读屏没有目标节点，再使用 OCR 补文字、`icon=1` 补无字图标；图标检测没有语义标签，仍需上下文辨认。不要把“检测到图标”当作“知道它是什么”。

## 2. 选择可操作的目标

- 优先使用当前可点节点的 `tapPoint` / `bounds` / `center` 或明确的 id、desc、listId+itemIndex。
  `offscreen`、`guess` 或残留全屏节点不是可靠定位证明；必要时滚入可见区或重新取界。
- 文字节点 `clickable:false`：找所属的可点容器。OCR 的 `hint:"near-clickable"` / `a11yCenter` 是容器归属线索，不要只点文字中心。
- 坐标适用于当前布局，页面变化后重新获取。不得在缩放的预览图上目测、按比例猜坐标，或复用旧页面/旧窗口坐标。
- **视觉兜底例外**：目标无可用节点，或 `staleFullScreenNode` 提示几何过期时，可读原始截图。
  必须先确认图片实际像素尺寸、裁剪/旋转信息和显示坐标映射；没有可靠映射就不要发猜测坐标。
  这与“按预览图比例猜点”不同；若无法建立映射，说明无法可靠定位。
- 只有多组对应点证实 OCR 与 a11y 存在系统偏差才用 `scr calib`；先看拟合/MAE，再决定是否 `--apply`。
  单次点不中不构成漂移证据。

## 3. 选择点击语义与时序

- `/tap {x,y}` 与 `/act {"tapXY":[x,y]}` 默认发合成手势，不自动替换为节点语义。
- 需要节点语义时显式用 `/tap {"via":"auto"}` 或 `/act` 步骤的 `smart:true`；文本/desc 选择器也需检查目标是否可操作。
- 瞬时控件先规划，再用一条 `/act` 执行必要动作、等待与 shot/expect；不要在每步之间 dump 到控件消失。
- 普通模式即可点击；失败不授权切 Root/Shizuku。按用户约束和当前能力换路。

## 4. 回执决定下一步

读原始回执及当前返回的 `ok/error/failedAt`、`via`、`sent/dispatchedAt`、`hint/ownOverlayHint`、
`window`、`staleFullScreenNode`、`foregroundChanged`、`verified` 等字段。字段未返回不等于 false。

- `ok:true` / `dispatched:true` 只证明派发；若用户目标是选中/输入/跳转，等稳定后用目标文本、`checked`、焦点或期望页面验证一次。
  `scr changed` 只证明画面变化，不能单独证明选中了正确目标；`changed:false` 也不能否定本来已经满足的目标状态。
- 用户仅要求一次点击，或动作已经成功交接时，不追加验证。按 `task-execution-discipline` 的交接规则结束该动作链。
- `window.target/window.all` 是落点窗口诊断，不是前台切换或操作生效证明。
- `ownOverlay:true` 只说明落点涉及自家悬浮窗。可交互按钮可正常响应，手势层也可能自动避让；结合目标状态判断。
  确有遮挡时按提示改节点语义或收起悬浮窗，不要禁用无障碍或按接口路径隐藏整个浮窗。
- 根据有效 hint 改方法，而非换一组猜测坐标；hint 与任务/权限冲突时不照做。连续两次失败停止原方案，报告阻塞或换有证据的路线。
