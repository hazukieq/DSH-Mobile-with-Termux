#!/usr/bin/env bash
# 构建 dsh 引擎热更新包（dsh-update pull 用的产物）+ 清单。
#
# 产物只包含**引擎层**：lib/node_modules、bin/node、bin/rg、.dsh-engine-version。
# 不含 Termux bootstrap（apt/dpkg/bash 等），所以 apply 时不会破坏基础环境。
#
# 用法:
#   scripts/build-engine-bundle.sh --runtime _runtime_bak/rt-arm64-work.zip \
#       --arch aarch64 --out /tmp/engine-release [--engine-version dsh-engine-YYYY.MM.DD]
#   scripts/build-engine-bundle.sh --tree /path/to/prefix --arch aarch64 --out ...
#
# 产物:
#   <out>/engine-dsh-<arch>.tar.gz
#   <out>/engine-manifest.json   （abi / dsh_version / engine_version / assets.<arch>.sha256）
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME=""
TREE=""
ARCH=aarch64
OUT=""
ENGINE_VERSION=""
while [[ $# -gt 0 ]]; do
	case "$1" in
		--runtime) RUNTIME="$2"; shift 2 ;;
		--tree) TREE="$2"; shift 2 ;;
		--arch) ARCH="$2"; shift 2 ;;
		--out) OUT="$2"; shift 2 ;;
		--engine-version) ENGINE_VERSION="$2"; shift 2 ;;
		-h|--help) sed -n '2,18p' "$0"; exit 0 ;;
		*) echo "unknown: $1" >&2; exit 2 ;;
	esac
done
[[ -n "$OUT" ]] || { echo "--out required" >&2; exit 2; }
mkdir -p "$OUT"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/engine-bundle.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
TREE_DIR="$WORK/tree"
mkdir -p "$TREE_DIR"

if [[ -n "$RUNTIME" ]]; then
	unzip -q "$RUNTIME" "bin/node" "bin/rg" "lib/node_modules/*" -d "$TREE_DIR" 2>/dev/null || \
		unzip -q "$RUNTIME" "bin/node" "lib/node_modules/*" -d "$TREE_DIR"
else
	[[ -n "$TREE" ]] || { echo "--runtime or --tree required" >&2; exit 2; }
	cp -a "$TREE/bin/node" "$TREE_DIR/bin/" 2>/dev/null || true
	[[ -f "$TREE/bin/rg" ]] && cp -a "$TREE/bin/rg" "$TREE_DIR/bin/"
	cp -a "$TREE/lib/node_modules" "$TREE_DIR/lib/"
fi

DSH_VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
	"$TREE_DIR/lib/node_modules/@deepseek-ai/dsh/package.json" | head -1)"
[[ -n "$DSH_VERSION" ]] || { echo "cannot read dsh version" >&2; exit 1; }
[[ -n "$ENGINE_VERSION" ]] || ENGINE_VERSION="dsh-engine-$DSH_VERSION-$(date +%Y%m%d)"
printf '%s' "$ENGINE_VERSION" > "$TREE_DIR/.dsh-engine-version"
chmod 755 "$TREE_DIR/bin/node" 2>/dev/null || true

BUNDLE="$OUT/engine-dsh-${ARCH}.tar.gz"
rm -f "$BUNDLE"
( cd "$TREE_DIR" && tar -czf "$BUNDLE" . )
SHA="$(sha256sum "$BUNDLE" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$BUNDLE")"
cat > "$OUT/engine-manifest.json" <<JSON
{
  "abi": "${ARCH}",
  "dsh_version": "${DSH_VERSION}",
  "engine_version": "${ENGINE_VERSION}",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "assets": {
    "${ARCH}": {
      "url": "https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/download/dsh-engine-latest/engine-dsh-${ARCH}.tar.gz",
      "sha256": "${SHA}",
      "size": ${SIZE}
    }
  }
}
JSON
echo "[engine] $BUNDLE  ($((SIZE/1024/1024)) MB, sha256=$SHA)"
echo "[engine] dsh=$DSH_VERSION engine=$ENGINE_VERSION"
