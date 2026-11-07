# 原生页面必须跟主界面同一套朝向策略（2.9.110 / 681）

## 1. 症状

平板上（vivo PA2353，1840x2800，默认横屏）主界面是横屏，**一进「设置」就突然跳成竖屏**；
退出设置又跳回横屏 —— 同一个 App 里两种朝向行为。

## 2. 根因

三个原生 Activity 各自硬编码竖屏，完全无视用户的「横屏模式」偏好：

- `app/src/main/java/app/dsh/mobile/SettingsActivity.kt:70`
- `app/src/main/java/app/dsh/mobile/AboutActivity.kt:24`
- `app/src/main/java/app/dsh/mobile/UserPromptActivity.kt:32`

```kotlin
requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT   // ← 无条件竖屏
```

而主界面走的是 `OrientationPrefs`（portrait / landscape / auto 三态，v1.2.120 起）：
`MainActivity.applyOrientation()` → `OrientationPrefs.toActivityInfo(mode)`。
手机上默认偏好就是竖屏，所以这个 bug 一直没暴露；平板上默认横屏才现形。

## 3. 修复

- `app/src/main/java/app/dsh/mobile/OrientationPrefs.kt` 新增
  `fun applyTo(activity: Activity) { activity.requestedOrientation = toActivityInfo(get(activity)) }`
  （带注释说明「原生页面必须和主界面共用同一套策略」）。
- 三个 Activity 的硬编码竖屏改为 `OrientationPrefs.applyTo(this)`，并删除不再使用的
  `ActivityInfo` import。
- `OnboardingActivity` 本来就不设朝向（它紧接着 finish，方向请求会丢），保持原样。
- `MainActivity` / `TerminalActivity` 不动（前者本来就走偏好，后者是终端按系统朝向）。

## 4. 实测（vivo PA2353 平板 / Android 14，2.9.110 / 681）

| 场景 | 结果 |
|---|---|
| 主界面（偏好=横屏） | 2800×1840 横屏 |
| 点齿轮进「设置」 | **2800×1840 横屏**（修复前会跳 1840×2800 竖屏） |
| 设置页显示版本 | 2.9.110 |
| 系统旋转锁死竖屏（`accelerometer_rotation=0`、`user_rotation=0`）+ 偏好=横屏 | 仍 2800×1840 —— 偏好优先，与主界面一致 |
| 平板装包后自检 | `content query --uri content://com.termux.adb/state` → `versionName=2.9.110, a11yConnected=true, engineRunning=true, type=2032` |

## 5. 规则（写给后来的人）

**任何新加的原生 Activity 都不许硬编码 `requestedOrientation`**；要跟随用户偏好就调
`OrientationPrefs.applyTo(this)`。硬编码会让「横屏模式」开关在原生页面上失效，
平板上表现为「进出页面就跳来跳去」。
