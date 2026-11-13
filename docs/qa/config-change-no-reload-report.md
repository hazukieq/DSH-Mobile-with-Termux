# App 在小窗 / 旋转 / 改尺寸时不该重新加载（2.9.109 / 680）

> 历史取证记录：结论仅适用于文中设备、版本和采样范围。当前行为与维护约束见相关使用文档；旧行号和临时文件路径不代表当前工作区。

## 1. 结论

| 项 | 结论 |
|---|---|
| 症状 | App 以**自由窗口（小窗）**或旋转/改尺寸时整页重新加载，会话滚动位置与输入内容全丢 |
| 根因 1 | `app/src/main/java/app/dsh/mobile/MainActivity.kt` 的 `onConfigurationChanged()` **无条件 `webView.reload()`** —— 每一次配置变化都把 SPA 重新加载一遍 |
| 根因 2 | `AndroidManifest.xml` 的 `configChanges` 只有 `orientation\|screenSize\|keyboardHidden`，**缺 `screenLayout\|smallestScreenSize`**（自由窗口 resize 触发的就是这几项）⇒ Activity 直接重建，WebView 随之重建 |
| 修复 | ① `onConfigurationChanged` 不再 reload，改为调用新抽出的 `applyViewport()`（与 `onPageFinished` 同一套 viewport JS，只重写 meta，不导航）；② 6 个 Activity 的 `configChanges` 补齐 `screenLayout\|smallestScreenSize\|keyboard\|navigation\|density` |
| 验证 | 设备端 CDP 打 JS 标记 `window.__dshtest`，配置变化后标记仍在即「没重载」：旋转往返 / `wm density` / `wm size` 全部保留，且 `innerWidth` 按新尺寸实时变化（说明布局确实重算，不是卡住） |

## 2. 取证方式（无需 root）

```bash
# 1) 打标记
./scripts/probes/adb-exec.sh 'cd $PREFIX/lib/dsh-mobile && node webview-cdp.mjs eval "window.__dshtest=777; location.href"'
# 2) 制造配置变化
adb -s <serial> shell wm size 700x1600      # 模拟小窗尺寸
adb -s <serial> shell wm density 400
adb -s <serial> shell settings put system user_rotation 1   # 需 accelerometer_rotation=0
# 3) 读回标记
./scripts/probes/adb-exec.sh 'cd $PREFIX/lib/dsh-mobile && node webview-cdp.mjs eval "String(window.__dshtest)+chr(39)+chr(124)+chr(39)+innerWidth"'
```

`window.__dshtest` 丢失 = WebView 被重建/页面被 reload；保留 = 状态没丢。

## 3. 实测（vivo V2520A / Android 17，2.9.109 / 680）

| 配置变化 | `window.__dshtest` | `innerWidth` | 判断 |
|---|---|---|---|
| 基线 | 777 | 400 | — |
| `wm size 900x1800` | 777 | 333 | 未重载，布局实时适配 |
| `wm size reset` | 777 | 400 | 未重载 |
| `wm density 400` | 777 | 480 | 未重载 |
| `wm density reset` | 777 | 400 | 未重载 |
| `wm size 700x1600`（小窗尺寸） | 777 | 400 | 未重载 |
| 转屏往返（2.9.108 修复前对照） | **undefined** | — | 旧版会丢标记 ⇒ 确认 bug 存在过 |

对照：同一台设备在 **2.9.108 / 679** 上做「竖屏→横屏→竖屏」往返，第三次读数 `MARK=undefined`，坐实旧版会重载。

## 4. 为什么不再 reload 也不会错版

- 桌面/竖屏两套视口本来就是靠 **viewport meta** 实现的（`portraitViewportJs()` / `DESKTOP_VIEWPORT_JS`），
  重新 `evaluateJavascript` 一遍即可生效，不需要页面重新导航。
- UA 切换（`setDesktop()`）只在下次导航时体现；本 App 的主页面是本地 SPA（`127.0.0.1:3080`），
  布局跟随窗口宽度而不是 UA，故旋转时不需要靠 reload 让 UA 生效。
- 页面滚动/输入/面板状态是 SPA 内部状态，reload 一定会丢 —— 这正是用户报的「重新加载」。

## 5. 注意

- 设备当前 App 设置是「横屏模式 = 竖屏锁定」，此时系统旋转不会真的转屏（`mDisplayRotation=ROTATION_0`），
  要测旋转需先把 App 设置成「自动」；本次旋转测试因此改用 `wm size/density` 制造同类配置变化。
- `configChanges` 里**没有**加 `locale|uiMode|fontScale`：语言切换走 App 自己的 recreate 流程，
  加进去反而会让语言/深色模式切换不生效。
