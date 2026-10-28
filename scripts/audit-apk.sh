#!/usr/bin/env bash
# 第三方审计辅助：把「装之前应该看的东西」一次性打出来。
#
# 用法：
#   scripts/audit-apk.sh app/build/outputs/apk/release/app-release.apk
#   scripts/audit-apk.sh ~/Downloads/dsh-mobile-v2.5.49.apk
#
# 输出：体积/版本/签名指纹/权限/导出组件/内置载荷哈希/APK 内出现的所有 URL。
# 它不判断「安全不安全」——只负责取证，判断留给审计者（见 docs/SECURITY-AUDIT.md）。
set -uo pipefail

APK="${1:-}"
[ -f "$APK" ] || { echo "usage: $0 <app-release.apk>" >&2; exit 2; }

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AAPT2="$(command -v aapt2 || echo "$BT/aapt2")"
APKSIGNER="$(command -v apksigner || echo "$BT/apksigner")"

echo "== 1. 基本信息"
ls -l "$APK" | awk '{print "  文件: "$9"\n  大小: "$5" 字节"}'
sha256sum "$APK" | awk '{print "  SHA-256: "$1}'
"$AAPT2" dump badging "$APK" 2>/dev/null | grep '^package' | \
  cut -d' ' -f2-4 | sed 's/^/  /'
"$AAPT2" dump badging "$APK" 2>/dev/null | grep -E "^(sdkVersion|targetSdkVersion|native-code)" | sed 's/^/  /'

echo
echo "== 2. 签名（对照发布页/仓库公布的证书指纹）"
if [ -x "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -E "Signer #1 certificate (DN|SHA-256 digest)" | sed 's/^/  /'
else
  echo "  (未找到 apksigner，跳过；装 build-tools 后重跑)"
fi

echo
echo "== 3. 权限（逐条问：这个权限该不该给它）"
"$AAPT2" dump permissions "$APK" 2>/dev/null | grep -oE 'android\.permission\.[A-Z_]+' | \
  sed 's/android.permission.//' | sort -u | sed 's/^/  /'

echo
echo "== 4. 组件一览（exported=true 且无 permission 的才是对外的门）"
python3 "$(dirname "$0")/lib/manifest-components.py" "$APK" "$AAPT2" 2>/dev/null || \
  echo "  (需要 python3 + aapt2；缺一个就跳过这节)"

echo
echo "== 5. 内置载荷（体积大头与哈希：引擎层 / 插件载荷 / 模型）"
for f in assets/engine-dsh-aarch64.zip assets/bundled-plugins.zip assets/models/icon_detector_int8.onnx; do
  h="$(unzip -l "$APK" 2>/dev/null | awk -v p="$f" '$4==p{print $1}')"
  [ -n "${h:-}" ] || continue
  echo "  $f  ${h} 字节"
  unzip -p "$APK" "$f" 2>/dev/null | sha256sum | awk '{print "    sha256 "$1}'
done

echo
echo "== 6. APK 内出现的所有 URL（问：哪些是默认会连的？）"
unzip -p "$APK" 'assets/*' 2>/dev/null | grep -aoE 'https?://[a-zA-Z0-9._~:/?#@!$&*+,;=%-]+' | \
  sed 's/[),.;]$//' | sort -u | head -40 | sed 's/^/  /'
echo "  (dex 里的 URL 可用: unzip -p app.apk 'classes*.dex' | strings | grep -oE 'https?://[^ ]+')"

echo
echo "== 7. 结论填空（审计者写）"
cat <<'TXT'
  [ ] 签名指纹与仓库/发布页公布的一致
  [ ] 每个权限都能在 docs/SECURITY-AUDIT.md 的权限表里找到对应用途
  [ ] 没有默认会连的陌生域名（只有 api.deepseek.com / github.com 等你认可的目标）
  [ ] 导出组件里没有未保护的入口（桥/终端都必须带 token 或本机回环）
  [ ] 内置载荷哈希与 release 附件里的 engine-manifest.json 一致
TXT
