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

# grep 消费完整清单，避免 -q 提前退出使 unzip 在 pipefail 下因 SIGPIPE 误报。
has()  { unzip -Z1 "$APK" | grep -F "$1" >/dev/null; }
nothas(){ ! unzip -Z1 "$APK" | grep -E "$1" >/dev/null; }
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
AAPT2=${AAPT2:-$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)}
[ -x "$AAPT2" ] || { echo "找不到 aapt2（检查 $SDK/build-tools）" >&2; exit 2; }

echo "== 1/4 静态门禁"
run "i18n 一致性"      python3 scripts/check-i18n.py
run "文档端点/子命令"   python3 scripts/check-doc-endpoints.py
run "开发文档未随包"    python3 scripts/check-bundled-docs.py
run "悬浮窗避让边界"    python3 scripts/check-overlay-avoid.py
run "内置 pnpm 完整性"  python3 scripts/check-bundled-pnpm.py
run "引擎升级回归"      python3 -m unittest discover -s scripts/tests -p test_engine_upgrade.py
run "纪律技能布局"      python3 scripts/check-agent-skills.py
run "纪律提醒回归"      node --test scripts/tests/test-discipline.mjs
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
  run "纪律技能资产"    has assets/skills/a11y-tap-discipline/SKILL.md
  run "任务纪律资产"    has assets/skills/task-execution-discipline/SKILL.md
  run "纪律提醒插件"    has assets/android-plugins/discipline.mjs
  run "路径映射预载层"  has lib/arm64-v8a/libdshpathmap.so
  run "产物文档"        has assets/docs/SECURITY-AUDIT.md
  run "无开发文档"      nothas "assets/docs/(ANDROID-CLI|refactor_guide|code_review|AI_CONTEXT)"
fi
fi

if [ -n "$DEVICE" ]; then
echo "== 4/4 真机冒烟 ($DEVICE)"
run "装机并启动" android run --device "$DEVICE" --activity app.dsh.mobile.MainActivity "$APK"
# ContentProvider exec is not exposed by android CLI. This test uses the app UID
# in Normal mode and never switches privilege mode or invokes su/Shizuku.
run "真机引擎与隔离升级回归" python3 scripts/test-engine-upgrade-device.py --device "$DEVICE" --require-live-engine
run "真机 /tmp 映射"    python3 scripts/test-pathmap-device.py --device "$DEVICE"

fi

echo
if [ ${#FAIL[@]} -eq 0 ]; then echo "PASS (${#OK[@]} 项)"; exit 0; fi
echo "FAIL (${#FAIL[@]}): ${FAIL[*]}"; exit 1
