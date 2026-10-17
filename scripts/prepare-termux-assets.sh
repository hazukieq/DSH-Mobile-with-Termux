#!/usr/bin/env bash
# 准备 com.termux 版运行时所需的两个资产（不进 git，产物在 app/src/main/assets/）：
#
#   bootstrap-<arch>.zip      Termux 官方 bootstrap（apt/dpkg/pkg/bash 等完整用户态）
#   engine-dsh-<arch>.zip     适配版 dsh 引擎：bin/node + lib/node_modules + bin/rg
#
# 用法:
#   scripts/prepare-termux-assets.sh --abi arm64-v8a \
#       --runtime _runtime_bak/rt-arm64-work.zip [--bootstrap-tag bootstrap-2026.10.11-r1+apt.android-7]
#
# ── 为什么引擎必须「内置」而不是 npm install ────────────────────────────────
# 上游 @deepseek-ai/dsh 在 Android/Termux 上装不起来（原生依赖/平台判断），本项目
# 存在的意义就是这份适配过的运行时；而且 npm 会把更新的嵌套依赖带进来，覆盖适配版
# （实测直接导致 dsh 启动时找不到 android 原生 binding）。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ABI="arm64-v8a"
RUNTIME=""
TAG="bootstrap-2026.10.11-r1+apt.android-7"
while [[ $# -gt 0 ]]; do
	case "$1" in
		--abi) ABI="$2"; shift 2 ;;
		--runtime) RUNTIME="$2"; shift 2 ;;
		--bootstrap-tag) TAG="$2"; shift 2 ;;
		-h|--help) sed -n '2,16p' "$0"; exit 0 ;;
		*) echo "unknown: $1" >&2; exit 2 ;;
	esac
done

case "$ABI" in
	arm64-v8a) TARCH=aarch64 ;;
	x86_64)    TARCH=x86_64 ;;
	*) echo "unsupported abi: $ABI" >&2; exit 2 ;;
esac

ASSETS="$REPO_DIR/app/src/main/assets"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/termux-assets.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# 1) 官方 bootstrap
ENC_TAG="$(printf '%s' "$TAG" | sed 's/+/%2B/g')"
BOOT_URL="https://github.com/termux/termux-packages/releases/download/${ENC_TAG}/bootstrap-${TARCH}.zip"
echo "[assets] bootstrap: $BOOT_URL"
curl -fL --retry 3 -o "$ASSETS/bootstrap-${TARCH}.zip" "$BOOT_URL"

# 2) 适配版引擎（从已构建好的 runtime.zip 里抽取 node + node_modules + rg）
if [[ -z "$RUNTIME" ]]; then
	echo "[assets] --runtime 未给出：只准备 bootstrap，engine-dsh 需手动放置" >&2
	exit 0
fi
echo "[assets] engine from $RUNTIME"
mkdir -p "$WORK/tree/bin" "$WORK/tree/lib"
unzip -q "$RUNTIME" "bin/node" "bin/rg" -d "$WORK/tree" 2>/dev/null || \
	unzip -q "$RUNTIME" "bin/node" -d "$WORK/tree"
unzip -q "$RUNTIME" "lib/node_modules/*" -d "$WORK/tree"
for lib in libc++_shared.so libpcre2-8.so libandroid-support.so; do
	unzip -q "$RUNTIME" "lib/$lib*" -d "$WORK/tree" 2>/dev/null || true
done
chmod 755 "$WORK/tree/bin/node" "$WORK/tree/bin/rg" 2>/dev/null || true
( cd "$WORK/tree" && zip -q -r "$ASSETS/engine-dsh-${TARCH}.zip" bin lib )
echo "[assets] done: bootstrap + engine-dsh-${TARCH}.zip"
