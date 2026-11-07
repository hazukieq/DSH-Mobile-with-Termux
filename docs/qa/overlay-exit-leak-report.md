# 悬浮窗「App 已销毁却还在」修复报告（2.9.104 / 2.9.105）

> 用户原话（m04286）：「有个bug：app彻底销毁后，悬浮窗还在」
> 设备：vivo V2520A（10CG1C1M1H0021Z，Android 17 / OriginOS 17，1080×2376，无 root）
> 版本：2.9.104（675）修根因，2.9.105（676）修"重开 App 球不回来"的副作用

## 1. 结论

| 场景 | 修复前 | 修复后（2.9.105 / 676） |
|---|---|---|
| 通知栏「退出」 | 引擎停、窗口拆掉，**约 60s 后球自己回来** | 拆掉后不再回来（70s 采样仍 `root=null`） |
| 最近任务划掉 | 部分机型同「退出」 | 同上 |
| 重新打开 App | 球可能不回来（`hidden` 被置位后无人复位） | 立刻恢复（`overlay resumed after user exited`） |
| 进程被杀（`am force-stop`） | 窗口随进程消失 | 不变（本无泄漏） |

## 2. 为什么"进程死了却没泄漏、进程活着才泄漏"

- `am force-stop`、划掉任务在实测机上都会让进程退出，WMS 随进程销毁窗口
  （`dumpsys window windows | grep com.termux` = 0 条）。
- 真正的泄漏只在**进程还活着**的退出路径：无障碍服务被系统绑着 → App 进程不会被杀 →
  60s 保活心跳照常执行 → 把窗口重新挂回去。

## 3. 根因（两处，缺一不可）

1. `app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt` 的
   `startKeepAliveWatchdog()`：60s 心跳**无条件**重启引擎 + `StatusOverlay.show()`，
   不检查 `EngineService.userExited()`。
2. `app/src/main/java/app/dsh/mobile/MainActivity.kt` 的 `onDestroy()`（`isFinishing`）调用
   `StatusOverlay.hideAuto()`，而 `hideAuto` → `hideInternal(userHidden = false)` 会把
   `hidden` **复位成 false**，正好解开 `teardown()` 设的抑制 ⇒ 之后任何 `show()` 都能重建窗口。

## 4. 修复

| # | 位置 | 改动 |
|---|---|---|
| 1 | `DshAccessibilityService.startKeepAliveWatchdog()` | 心跳先判 `EngineService.userExited()`；为真则跳过引擎自启与悬浮窗，记 `keepalive: user exited, skip engine restart + overlay re-show` |
| 2 | `StatusOverlay.startOverlay(ctx, w, type)` | 入口兜底：`userExited` 为真直接 return，记 `overlay suppressed: user exited` |
| 3 | `StatusOverlay` | 新增 `hiddenByExit` + `resumeAfterExit()`：区分「退出抑制」与「长按隐藏」 |
| 4 | `MainActivity`（两处清 `userExited`） | 清标记后 `if (StatusOverlay.resumeAfterExit()) StatusOverlay.show(this)` |

## 5. 真机验证（2.9.105 / 676）

```
① 通知栏点「退出」  → engineRunning=false ；overlay root=null live=0
② 静置 70s          → 仍 root=null
   logcat: I/DshA11y: keepalive: user exited, skip engine restart + overlay re-show
③ 重新打开 App      → engineRunning=true ；overlay root!=null pos=948,360 size=108x108
   logcat: I/StatusOverlay: overlay resumed after user exited
```

## 6. 复现命令

```bash
cd scripts/probes/flicker
./bridge.sh state                     # 看 engineRunning / overlay
adb shell cmd statusbar expand-notifications
# 展开「DSH 引擎运行中」→ 点「退出」（悬浮球若压住 chevron，先把球拖到左侧）
adb shell "content query --uri content://com.termux.adb/state" | grep -E "engineRunning|overlay"
sleep 70 && adb shell "content query --uri content://com.termux.adb/state" | grep overlay
adb shell am start -n com.termux/app.dsh.mobile.MainActivity
```

注意：`am startservice -a app.dsh.mobile.service.action.EXIT` 会被拒
（`Requires permission not exported from uid …`），必须走通知栏按钮或划掉任务。

## 7. 回归 / 门禁

- `python3 scripts/check-bundled-docs.py` → OK
- `python3 scripts/check-i18n.py` → `All i18n checks passed`（新增日志均为英文）
- `python3 scripts/check-overlay-avoid.py` → `OK: 115 个 Kotlin 文件，0 处路径级避让`
- `bash scripts/acceptance.sh --static` → PASS

## 8. 文档

- `docs/OVERLAY.md` §3.ab（完整根因 + 验证表 + 两条硬规则）
- `app/src/main/assets/docs/OVERLAY.md` §3.ab（随包精简版，装机后部署到 `$HOME/docs`）

## 9. 2.9.106 追加：移除「长按隐藏」

- 用户（m04427）：「彻底移除 悬浮窗的长按隐藏，根本不需要，已经过时的功能」。
- 改动：`StatusOverlay.attachGesture()` 删掉 `onLongPress` 形参，`bindHandle()` 两处调用不再传；
  折叠态/展开态都不再 `setOnLongClickListener`；`hideInternal()` 日志改为
  `overlay hidden (sticky)` / `overlay hidden (auto)`。
- 现在让悬浮窗消失的入口只剩两个（都是显式操作，不是手势）：设置里关掉「显示悬浮窗」、退出 App。
- 真机验证（vivo / 2.9.106 / 677）：`input swipe x y x y 2000`（2s 长按）后窗口仍
  `mViewVisibility=0x0 / attached=true`，`level` 未变（收起态长按会当点击展开，不会隐藏）；
  收起/展开点击、拖动吸边均正常。
- 手机侧文档已部署：`$HOME/docs/OVERLAY.md` 含「3.ac」与「长按隐藏已于 2.9.106 移除」，300 行。

## 10. 2.9.107 追加：关闭悬浮窗必须真的关得住（能力面不受影响）

- 用户提问（m04473）：「当关闭悬浮窗时，所有 a11y 功能、ocr 识别、分享等所有功能，是否正常运作？」
- 实测发现的问题：设置 → 后台进度提示 → 显示方式 → 关闭 只调 `ProgressHub.setMode(MODE_OFF)` →
  `hideAuto()`（把 `hidden` 复位成 false）；之后多条通路各自 `show()` 一次，球 **11 秒后又出现**
  （`02:20:33 overlay hidden (auto)` → `02:20:44 overlay shown at 948,360`，2.9.106 / 677）。
- 修复（2.9.107 / 678）：在唯一建窗入口 `StatusOverlay.startOverlay()` 加模式闸门，
  `ProgressHub.mode(ctx) != MODE_OVERLAY` → return + 日志 `overlay suppressed: progress mode off`。
- 能力面实测（悬浮窗关闭状态下，vivo 10CG1C1M1H0021Z）：

| 能力 | 端点 | 结果 |
|---|---|---|
| 读屏 | `GET /screen` | fg=com.termux 57 nodes（OCR 兜底 24 行/822ms） |
| 无障碍文本点击 | `POST /act {"steps":[{"tap":"轨迹"}]}` | ok:true，界面切换（57→147 节点） |
| 坐标点击 / 手势 | `POST /tap`、`POST /gesture` | ok（`via=gesture`） |
| OCR | `GET /ocr?cache=0` | 24 行 / 725ms |
| OCR 预热 | `GET /daemon/status` ×5 | `prefetch ocr ready n=29 engine=mlkit ms=322` |
| 免闪截图 | `GET /screenshot?save=1` | `via=composite 1080x2376` |
| 帧差 | `GET /frame-diff` | ok |
| 分享 | `POST /share/text` | `ok:true opened:true handoff:true` |
| 音量 / 安装 | `GET /volume`、`GET /install` | ok / `can_install:true` |
| 保活心跳 2 周期 | — | `overlay suppressed: progress mode off` ×2，窗口保持 null |
| 切回「悬浮窗」 | — | `overlay shown at 948,360 (108x108)` 立刻回来 |

- 结论：能力面与悬浮窗完全解耦；关掉的只是那个球（失去"看 AI 在做什么"的可视反馈，可切「通知」模式补）。
