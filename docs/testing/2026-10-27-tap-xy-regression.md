# 坐标 tap 回归：v2.5.30 实测报告（回应 2026-10-27-tap-enhancement-gap.md）

- 设备：vivo V2520A（10CG1C1M1H0021Z），Android 17 / SDK 37，1080×2376
- 版本：2.5.29 (462) → **2.5.30 (463)**
- 方法：电脑端 `adb forward tcp:3083` + `curl` 直连 AgentBridge（不依赖手机端 agent），
  每次动作前后各取一次 `/scene`，用节点文本集合的 Jaccard 判页面是否真的变化。

## 1. 根因（与原报告的猜测不同）

原报告怀疑「窗口坐标系没叠加 insets」和「SystemUI 过滤合成触摸」。实测两者都不是主因：

1. **a11y bounds 就是屏幕坐标**。在酷狗当前页跑 `/calib?debug=1`：
   `pairs=5, sx=1.002, sy=0.999, dx=2.1, dy=-0.4, maeBefore=5.1px` —— a11y 中心与 OCR 墨迹中心
   相差 5px 以内，不存在 100px 的整体上移。`/screen` 里报的 `height=2284` 只是 displayMetrics，
   节点 bounds 最大到 2376，说明按屏幕坐标给。
2. **真正的回归是 v2.5.29 把坐标 tap 改成了「节点优先」**：`tapSmart()` 先按坐标找最深节点、
   沿父链找 clickable、发 ACTION_CLICK。于是「我说点 (540,830)」变成「点 a11y 认为在
   (540,830) 的那个节点」：
   - 酷狗当前页挂着两个**残留全屏节点** `关闭侧边栏 / 播放`，bounds `[0,0,1080,2376]`，
     它们覆盖任何坐标，于是点击落到装饰层 → 「点了没反应」或误触；
   - 通知栏里的卡片节点与其子节点合并关系不稳定，节点优先路径点了不响应的祖先；
   - 用户看到的「整体上移 100px 误命中上方控件」= 命中了相邻节点（父链/兄弟），不是坐标被加偏移。

## 2. 修复

| 改动 | 位置 |
| --- | --- |
| 坐标 tap **默认只发合成手势**（精确、jitter=0），节点语义要显式 `{"via":"auto"}` | `AgentBridge.tap()` / `DshAccessibilityService.tapAtExact()` |
| `tapSmart` 拒绝面积 ≥60% 屏的节点（残留全屏层），命中即退回手势 | `DshAccessibilityService.tapSmart()` |
| `/tap` 坐标回执新增 `sent / dispatchedAt / via / node / staleFullScreenNode / spaceHint / ownOverlay` | `AgentBridge.tap()` |
| `/scene` 在非 shade 场景下也报 `staleFullScreenNode` + `spaceHint` | `AgentBridge.screen()` |
| 落在本 App 悬浮窗上时提示改用 `/overlay` / `scr status --action` | `pointInOwnOverlay()` |

## 3. 实测结果（2.5.30）

| 用例 | 坐标（a11y/OCR 一致） | 结果 |
| --- | --- | --- |
| 酷狗 点列表行（雨天） | 540,804 | `via=gesture`，`J=0.07` → 打开歌曲详情，正常 |
| 酷狗 点残留全屏节点区域（`via:auto`） | 540,1142 | 护栏生效：`via=gesture`（0.86，未点装饰层） |
| 酷狗 点「已关注」按钮 | 390,405 | 跳转歌曲页（`已收藏` 出现），正常 |
| 通知栏 shade 点通知卡（酷狗音乐） | 490,1074 | 收起 shade 并打开酷狗（此前 2.5.29 无反应） |
| 通知栏 shade 点「分享状态」 | 930,426 | 打开微信 `TextStatusEditActivity`（此前无反应） |
| GitHub 底部 tab「探索」 | 678,2256 | 页面切换（`J=0.17`） |
| DSH 顶部悬浮条「隐藏」 | 861,174 / 540,140 | **仍无效**：属我们自己的 TYPE_ACCESSIBILITY_OVERLAY 窗口，合成手势被吞；正确通道 `/overlay {"show":false}` 或 `scr status … --action`（已加 `ownOverlay` 提示） |

`dispatchedAt` 实测带抖动后落点：`sent 540,804 → dispatchedAt 541,803`（jitter≤4px，符合预期）。

## 4. 没解决/留待观察

- `ownOverlay` 检测依赖 `AccessibilityService.windows` 是否枚举到我们自己的悬浮窗，本轮实测返回 null
  （提示未触发）；功能保留但不保证总能探测到。
- 悬浮条「隐藏」的节点动作依赖 overlay 自己的动作链，不走 a11y，故 `/act tap "隐藏"` 能成功而
  坐标点击不能——这不是坐标 bug，建议调用方按能力矩阵选通道。
- 原报告 §2.1 的「shade 完全吞手势」在本机复测未能重现，可能与其时段省电/后台限制或 2.5.29
  节点优先路径有关，已在文档能力矩阵中标注为「✅ 本版实测可用」，若再出现请附 `/tap` 回执
  （现在能看到 `via` 与 `dispatchedAt`）。
