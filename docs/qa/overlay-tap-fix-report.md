# 悬浮窗「点不开」修复报告（2.9.95 / versionCode 666）

> 历史取证记录：结论仅适用于文中设备、版本和采样范围。当前行为与维护约束见相关使用文档；旧行号和临时文件路径不代表当前工作区。

> 设备：vivo V2520A（`10CG1C1M1H0021Z`），Android 17 / OriginOS 17.0，1080×2376 @480dpi，**无 root**
> 复现版本：2.9.94 / 665（`b4fc8c6`）→ 修复版本：2.9.95 / 666
> 关联文档：`docs/OVERLAY.md`（结论入册）、`scripts/probes/flicker/bridge.sh`（免 root 取证）

## 1. 用户报告与现场取证

用户原话：**「现在我只有一个问题：悬浮窗点不开了」**。

取证全部走免 root 通道（`adb forward tcp:3083` + App 自带 `AdbDebugProvider` 取 token）：

```bash
cd scripts/probes/flicker
./bridge.sh state
# versionName=2.9.94 pid=1656 a11yConnected=true engineRunning=true token=ready
# overlay: root=… attached=true live=1 level=1 pos=948,327 size=720x-2 flags=0x1000028 touchable=true hideRefs=0 type=2032
```

三个矛盾点：

| 观察 | 说明 |
|---|---|
| `level=1`（档位=展开）vs 截图里是一只**折叠球** | 档位字段与视图渲染脱钩 |
| `size=720x-2`（展开宽 720）vs `pos.x=948`（球坐标） | `x + 720 = 1668 > 1080`，588px 在屏幕外 |
| `input tap` 后 logcat 有 `handle touch: action=0/1 at …`，但 state 一字未变 | 点击确实到达 View，被逻辑吞掉 |

`dumpsys window` 侧一切正常：`type=2032`、`mHasSurface=true`、`mDrawState=HAS_DRAWN`、`isVisible=true`
——所以**不是窗口被隐藏、不是 touchable 丢失、也不是系统拦截**，纯粹是应用内状态机的问题。

## 2. 根因（两条）

### 2.1 档位与渲染态脱钩 → 点击被吞

- `StatusOverlay.expandLevel` 是唯一档位真源；
- 窗口重建（`hideInternal()` 的 `removeView`、2032↔2038 类型切换）后，创建块会把
  `expandLevel = rememberedLevel`（上次是展开 ⇒ 1），但新 inflate 出来的视图默认是**折叠态**；
- `bindHandle()` 按 `expandLevel > 0` 判断，把 root 的 `onClick` 绑成**空实现**（只有 `overlayDot` 能收起）；
- `expandPanel()` 首行是 `if (expandLevel == 1) return`。

⇒ 只要出现过一次重建，"折叠球 + level=1" 这个非法组合就会永久生效：点击进空 onClick（或早退），
**怎么点都不展开**，直到重装/重启进程。

### 2.2 展开卡片漏锚定 → 卡在屏幕外

- `applyExpandLevel()` 里锚定带门控：`if (lastAppliedLevel != expandLevel) { … anchorToEdge() }`；
- 重建后 `lastAppliedLevel` 与 `expandLevel` 已经相等 ⇒ **不锚定**；
- 新窗口的 `x` 取自 `pendingPos`（类型切换时带过来的是**球**坐标 948）；
- 卡片宽 720 ⇒ 从 948 开始画，右侧 588px 出屏 = 用户看到的"一条竖边"。

## 3. 修复（`app/src/main/java/app/dsh/mobile/StatusOverlay.kt`）

1. `expandPanel()`：早退判据换成**渲染态**
   ```kotlin
   val renderedExpanded = statusRow?.visibility == View.VISIBLE
   if (renderedExpanded && expandLevel == 1) return
   …
   lastAppliedLevel = -1      // 强制重算锚定
   ```
2. `bindHandle()` 展开分支的 root onClick 从空实现改为自愈：
   ```kotlin
   onClick = { if (statusRow?.visibility != View.VISIBLE) expandPanel() }
   ```
3. `applyExpandLevel()` 去掉 `lastAppliedLevel != expandLevel` 门控 —— 每次应用档位都
   `postOnAnimation { anchorToEdge() }`。
4. `anchorToEdge()` 兜底夹取：
   ```kotlin
   prm.x = dockX(v, prm, ctx).coerceIn(margin, (screenW - vw - margin).coerceAtLeast(margin))
   ```
   `vw` 取 `prm.width → v.width → 档位默认宽`。
5. `collapseToBall()` 同样置 `lastAppliedLevel = -1`（收起后按球宽重新贴边）。

> 显示/隐藏仍只用 `visibility` + 引用计数，未引入 `removeView/addView`、未改 `params.flags`。

## 4. 真机验证（2.9.95 / 666）

| 动作 | 期望 | 实测 |
|---|---|---|
| 点球 | 展开 720 宽、完整在屏内 | `level=1 pos=336,360 size=720x-2` |
| 点圆点 | 收起 108 球、贴右 | `level=0 pos=948,360 size=108x108` |
| 连做 3 轮展开/收起 | 每次都正确 | 3/3 一致（`948,360,108x108` ↔ `336,360,720x-2`） |
| 展开态点卡片正文 | 不收起、不崩 | `level=1` 不变 |
| 拖动球 | 跟随并吸附 | `pos=948,768 size=108x108` |
| **展开态撤销/恢复无障碍**（2032→2038→2032，每次重建窗口） | 保持展开且仍在屏内 | 三次重建全部 `pos=336,360 size=720x-2` ✔ 原故障路径已修复 |
| 拖动后点球 | 仍能展开且 x 重新锚定 | `level=1 pos=336,768` |
| 截图（composite 免闪） | 不隐藏、不进画面 | 8/8 采样 `HAS_DRAWN isVisible=true`；PNG 球区域均值 247（无球） |

附带回归：`bash scripts/acceptance.sh --static` → `PASS (5 项)`（i18n / 文档端点 / 开发文档未随包 /
悬浮窗避让边界 / pnpm 完整性）。

## 5. 排查中踩到的两个坐标坑（写给下一次）

1. **vivo 上 `params.y` ≠ 屏幕像素 y**：窗口 `Frames: parent=[0,92][1080,2376]`，差 92px。
   `adb shell input tap` 用视觉坐标（`params.y + 92`），否则会点到球的上方空白处，
   误判成"点了没反应"。
2. **`debugState()` 的 `level` 是字段不是现实**：`level` 读 `expandLevel`，不代表当前长什么样。
   判断渲染态要看 `statusRow.visibility`，或者直接截图。

## 6. 复现与验收命令

```bash
cd scripts/probes/flicker
./bridge.sh state                       # 看 level / pos / size 是否自洽
adb shell input tap 1002 494            # 点球（视觉坐标）
./bridge.sh state | grep overlay
adb shell input tap 400 510             # 点圆点收起
# 原故障路径（展开态下切无障碍）：
adb shell settings put secure enabled_accessibility_services null
adb shell settings put secure accessibility_enabled 0
sleep 6
adb shell settings put secure enabled_accessibility_services com.termux/app.dsh.mobile.DshAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 10 && ./bridge.sh state | grep overlay   # 期望 pos=336,* size=720x-2
```
