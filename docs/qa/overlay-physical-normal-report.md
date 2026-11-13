# 悬浮窗真机回归：普通模式

日期：2026-11-13。设备 M2012K11AC / alioth，系统报告 Android 17 / API 37，
build `CP2A.260605.016`。测试对象为 `dev` 工作区的 arm64-v8a release，
版本 `3.0-alpha-1` / 686，沿用手机原安装签名原位升级，保留应用数据。

最终 APK SHA-256：`58a0391916e9feaa7dff230a7ba9f955a11761cb2fcd6cccd904659bbd4b1f68`。
本记录只对应这次构建和设备，不代表其他系统版本或厂商 ROM 已验证。

## 权限与测试隔离

- 未执行 `su`、`adb root`，未借助 Shizuku 运行命令。ADB 为普通 `uid=2000(shell)`。
- 应用与 Node 引擎均为 `u0_a278` / UID 10278，`GET /status` 的权限模式为 `normal`。
- instrumentation 在设置 Normal 后执行，拒绝 root UID；独立触摸靶场仅存在于测试 APK。
- 只向无副作用触摸靶场注入手势，不发送模型消息、不操作用户会话内容。
- 测试结束卸载 `com.termux.test`，恢复 SYSTEM_ALERT_WINDOW 原值 allow，并保留 DSH 与 KDE Connect 的无障碍授权。
- 发布 APK 保留在手机，使用 Normal 模式；原安装 APK 备份在主机 `/tmp/dsh-phone-installed.apk`。

## 发现与修复

1. 相交长按隐藏浮窗后立即注入，系统输入窗口还没更新，实际触摸丢失。
   对照组在窗外能收到 2500ms 长按，相交组连续丢失。
   现相交手势隐藏后等待 120ms 再注入；主线程采用延迟投递，回调或超时释放自身避让凭据。
2. ProgressHub 在节流间隔直接返回，40 帧更新最终停留在 39/40。
   现合并调度一次尾部发布，采用单调时钟，关闭时取消待发布任务。真机显示最终 40/40。
3. 普通模式的通用命令、引擎端口回收、进程清理、OCR 截图回退等路径曾可能自动尝试 su。
   现 Normal 不提权，Shizuku 失败不回退 Root；初始能力展示只读取候选路径，不主动执行 su。

## 结果

| 检查 | 实际证据 |
|---|---|
| 应用 UID 窗口回归 | release instrumentation 返回 UID 10278、normal、PASS；覆盖建窗、展开、末帧、重叠避让、监听器/引用清理、内容恢复、预览、关闭、退出/恢复 |
| ProgressHub 尾帧 | 两次紧邻更新后等待 250ms，直接断言浮窗正文为 `stream final` |
| 系统真实输入 | 窗外一次、穿窗两次 4000ms 长按，靶场依次显示 Touches 1/2/3，Held 均为 4000ms |
| 长手势避让 | 超过 1200ms 时仍 hideRefs=1；结束后 hideRefs=0，窗口展开形态保留 |
| 高频进度 | 40 次 POST 更新期间新增 hide 日志为零，窗口实例不变，最终正文显示 40/40 |
| 截图排除浮窗 | 1080×2400，via=composite；实屏卡片内部像素为 (39,46,55)，原生截图对应点为靶场背景 (244,248,252) |
| 服务断开/重连 | 2032 → 2038 → 2032，内容与形态保留；最终 APK 另行重复验证类型切换 |
| 仅无障碍授权 | SYSTEM_ALERT_WINDOW=deny 时重新绑定，仍成功挂载 type=2032；结束恢复 allow |
| 真实通知退出 | 点击通知的退出按钮，4.1s、36.1s、73.1s 均引擎停止、root=null、live=0，且无障碍全程连接 |
| 退出保活 | 日志出现 `keepalive: user exited, skip engine restart + overlay re-show` |
| 重新打开 | MainActivity 恢复引擎和 2032 浮窗；清除测试进程内状态后再启动正常 |
| 主机验证 | release JVM 23 项、完整验收 11 项通过，arm64 release 构建成功 |

[实屏最终帧](artifacts/overlay-physical-2026-11-13/stream-final.png) 与
[排除浮窗后的原生截图](artifacts/overlay-physical-2026-11-13/native-capture.png)
来自受控触摸页面；[结果摘录](artifacts/overlay-physical-2026-11-13/results.txt) 不包含鉴权凭据。

## 复现

在专用测试设备上安装同签名应用与对应测试 APK，应用选择 Normal、开启无障碍和悬浮窗显示方式：

```sh
gradle testReleaseUnitTest assembleRelease assembleReleaseAndroidTest -Pabi=arm64-v8a -PtestBuildType=release
android install --device <serial> app/build/outputs/apk/release/app-release.apk
android install --device <serial> app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
bash scripts/smoke-overlay.sh <serial>
```

instrumentation 会结束现有应用进程；测试后重新打开应用、等引擎就绪、展开浮窗。
先用带编号的系统截图核实坐标，再运行真实触摸回归；以下坐标只适用于本次布局：

```sh
python3 scripts/test-overlay-device.py <serial> --outside 180 310 --overlap 727 598
```

该脚本自己拉起触摸靶场，校验实际触摸计数、持续时间和避让状态，不以 API `ok` 作为送达依据。

Android CLI 的 `android input` 助手在本机持有 UiAutomation 时会解绑其他无障碍服务。
第一次退出观察因 a11yConnected=false 被排除；停止
`com.android.cli.interact.instrumentation` 后，使用普通 `adb shell input` 点击通知重测，
并要求 73 秒观察窗口内每次采样均 a11yConnected=true。
详见开发手册 `docs/ANDROID-CLI.md`。此项不能用无障碍已被工具停用的结果冒充保活验证。

尚未覆盖 API 34 以下整屏截图回退、长时间后台耗电、屏幕旋转/分屏及其他设备 ROM。
Root/Shizuku 执行能力本次未实机测试；权限路由单测使用闭包替身，不执行提权命令。
