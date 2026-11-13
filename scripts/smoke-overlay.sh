#!/usr/bin/env bash
# 窗口回归入口。安装同签名、同 ABI 的应用 APK 和对应 androidTest APK。
# 测试会结束当前应用进程并将权限模式设为 Normal；退出/进度设置在结束时恢复。
# 构建：gradle assembleDebug assembleDebugAndroidTest -Pabi=<device-abi>
# 原签名 release 装机验证：追加 -PtestBuildType=release，任务改 assembleRelease assembleReleaseAndroidTest。
# 安装：android install --device <serial> <apk>（两个 APK 分别安装）
# 用法：bash scripts/smoke-overlay.sh [device-serial]
# android CLI 没有 instrumentation 子命令，因此此处使用 adb 执行测试。
set -euo pipefail
ADB=(adb)
if [ -n "${1:-}" ]; then ADB+=( -s "$1" ); fi
"${ADB[@]}" get-state >/dev/null
"${ADB[@]}" shell pm path com.termux.test | grep -q '^package:' || {
  echo 'FAIL: 请先安装与应用匹配的 androidTest APK' >&2
  exit 1
}
"${ADB[@]}" shell appops get com.termux SYSTEM_ALERT_WINDOW | grep -q 'allow' || {
  echo 'FAIL: 测试设备需要悬浮窗权限（adb shell appops set com.termux SYSTEM_ALERT_WINDOW allow）' >&2
  exit 1
}
result=$("${ADB[@]}" shell am instrument -w -r \
  com.termux.test/app.dsh.mobile.OverlayRegressionInstrumentation)
printf '%s\n' "$result"
if ! printf '%s\n' "$result" | grep -q 'PASS: attach,' ||
   ! printf '%s\n' "$result" | tr -d '\r' | grep -q '^INSTRUMENTATION_CODE: -1$'; then
  echo 'FAIL: 窗口回归未通过（进程崩溃、断言失败或未执行均算失败）' >&2
  exit 1
fi
