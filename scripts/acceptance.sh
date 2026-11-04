#!/usr/bin/env bash
# 完整性验收：静态门禁 -> 构建 -> APK 断言 -> 可选真机冒烟
#   bash scripts/acceptance.sh                     # 静态+构建+APK 断言
#   bash scripts/acceptance.sh --device 5f08bc20   # 再加真机
#   bash scripts/acceptance.sh --static            # 只跑静态
#   bash scripts/acceptance.sh --skip-build        # 复用已有 APK
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
DEVICE=""; STATIC_ONLY=0; SKIP_BUILD=0
APK=app/build/outputs/apk/release/app-release.apk
while [ $# -gt 0 ]; do case "$1" in
  --device) DEVICE="$2"; shift 2;;
  --static) STATIC_ONLY=1; shift;;
  --skip-build) SKIP_BUILD=1; shift;;
  *) echo "unknown arg: $1" >&2; exit 2;; esac; done

OK=(); FAIL=()
run() { local n="$1"; shift; if "$@" >/tmp/acc.log 2>&1; then OK+=("$n"); printf '  OK   %s\n' "$n";
        else FAIL+=("$n"); printf '  FAIL %s (log /tmp/acc.log)\n' "$n"; tail -4 /tmp/acc.log|sed 's/^/       /'; fi; }

has()  { unzip -l "$APK" | grep -q "$1"; }
nothas(){ ! unzip -l "$APK" | grep -qE "$1"; }
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
AAPT2=${AAPT2:-$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)}
[ -x "$AAPT2" ] || { echo "找不到 aapt2（检查 $SDK/build-tools）" >&2; exit 2; }

echo "== 1/4 静态门禁"
run "i18n 一致性"      python3 scripts/check-i18n.py
run "文档端点/子命令"   python3 scripts/check-doc-endpoints.py
run "开发文档未随包"    python3 scripts/check-bundled-docs.py
run "内置 pnpm 完整性"  python3 scripts/check-bundled-pnpm.py
if [ "$STATIC_ONLY" = 0 ]; then
  export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
  run "JVM 单测" flock /tmp/dsh-gradle.lock "$HOME/Projects/common/dsh-android-build/gradle-8.9/bin/gradle" -p . testDebugUnitTest --no-daemon
fi

echo "== 2/4 构建 release APK"
if [ "$SKIP_BUILD" = 1 ]; then echo "  SKIP (--skip-build)"
elif [ "$STATIC_ONLY" = 1 ]; then echo "  SKIP (--static)"
else
  export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
  run "assembleRelease" flock /tmp/dsh-gradle.lock "$HOME/Projects/common/dsh-android-build/gradle-8.9/bin/gradle" -p . assembleRelease -Pabi=arm64-v8a --no-daemon
fi

if [ "$STATIC_ONLY" = 0 ]; then
echo "== 3/4 APK 断言"
if [ ! -f "$APK" ]; then FAIL+=("APK 存在"); echo "  FAIL $APK 不存在"; else
  run "包名/版本/ABI"   bash -c 'set -e; b=$('"$AAPT2"' dump badging '"$APK"'); echo "$b"|grep -q "name=.com.termux"; echo "$b"|grep -q "arm64-v8a"; echo "$b"|grep -q "versionName="'
  run "引擎层资产"      has assets/engine-dsh-aarch64.zip
  run "mobile-ui 已移除"  nothas "assets/android-plugins/mobile-ui/"
  run "产物文档"        has assets/docs/SECURITY-AUDIT.md
  run "无开发文档"      nothas "assets/docs/(ANDROID-CLI|refactor_guide|code_review|AI_CONTEXT)"
fi
fi

if [ -n "$DEVICE" ]; then
echo "== 4/4 真机冒烟 ($DEVICE)"
A() { adb -s "$DEVICE" shell "$@"; }
run "装机" adb -s "$DEVICE" install -r -d "$APK"
run "引擎就绪(401)" bash -c 'adb -s'"$DEVICE"' shell am force-stop com.termux; adb -s'"$DEVICE"' logcat -c; adb -s'"$DEVICE"' shell am start -n com.termux/app.dsh.mobile.MainActivity >/dev/null; for i in $(seq 1 40); do c=$(adb -s '"$DEVICE"' shell "curl -s -o /dev/null -m 3 -w %{http_code} http://127.0.0.1:3080/"|tr -d "\r"); [ "$c" = 401 ] && exit 0; sleep 2; done; exit 1'
run "引擎版本文件" bash -c 'adb -s '"$DEVICE"' shell "su -c \"cat /data/user/0/com.termux/files/usr/.dsh-engine-version\""|grep -q "0\.2\."'
run "冷启动打点" bash -c 'for i in $(seq 1 20); do adb -s '"$DEVICE"' logcat -d -s DshColdStart|grep -q engine.portReady && exit 0; sleep 2; done; exit 1'

# 交互与特权的完整回归（分享 / 原生窄屏 / 长按菜单 / NORMAL→ROOT→SHIZUKU 模式切换 / Agent 原语）
run "Shizuku 服务就绪" bash scripts/start-shizuku.sh --device "$DEVICE"
run "真机交互+特权冒烟" bash scripts/smoke-device.sh --device "$DEVICE" --priv-cycle
fi

echo
if [ ${#FAIL[@]} -eq 0 ]; then echo "PASS (${#OK[@]} 项)"; exit 0; fi
echo "FAIL (${#FAIL[@]}): ${FAIL[*]}"; exit 1
