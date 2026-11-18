#!/usr/bin/env bash
# 只换 JS 闭包、复用现有引擎骨架，产出一个可被 `dsh-update stage/apply` 的引擎 tar。
#
# 输入必须来自上游 pack.ts --family dsh 的本地 tarball consumer，不能复制 monorepo。
# --download-base <HTTP(S)目录URL> 另产 <out>.manifest.json；生成不代表已经发布。
set -euo pipefail

ENGINE="" CLOSURE="" VERSION="" OUT="" ARCH="aarch64" SKIP_PATCH=0 KEEP_TREE="" DOWNLOAD_BASE="${DSH_ENGINE_DOWNLOAD_BASE:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --engine) ENGINE="$2"; shift 2 ;;
    --closure) CLOSURE="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --download-base) DOWNLOAD_BASE="$2"; shift 2 ;;
    --arch) ARCH="$2"; shift 2 ;;
    --skip-patch) SKIP_PATCH=1; shift ;;
    --keep-tree) KEEP_TREE="$2"; shift 2 ;;
    -h|--help) echo "usage: $0 --engine <zip|dir> --closure <node_modules> --version <ver> --out <tar.gz> [--arch aarch64|x86_64] [--download-base <URL>] [--skip-patch] [--keep-tree <dir>]"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$ENGINE" ] && [ -n "$CLOSURE" ] && [ -n "$VERSION" ] && [ -n "$OUT" ] || {
  echo "usage: $0 --engine <engine.zip|dir> --closure <node_modules> --version <ver> --out <tar.gz>" >&2
  exit 2
}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/dsh-engclosure.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
ROOT="$WORK/root"
mkdir -p "$ROOT"

echo "[1/6] 展开引擎骨架: $ENGINE"
if [ -d "$ENGINE" ]; then
  cp -a "$ENGINE/." "$ROOT/"
else
  unzip -q "$ENGINE" -d "$ROOT"
fi
# 兼容两种交付形态：解包 zip（lib/dsh-engine/...）与 engine-tree（lib/...）
NM_DIR="$ROOT/lib/node_modules"
if [ -d "$ROOT/lib/dsh-engine/lib" ] && [ ! -d "$ROOT/lib/node_modules" ]; then
  echo "      检测到 ZIP 内嵌布局（lib/dsh-engine），闭包仍放 lib/node_modules"
fi

echo "[2/6] 替换闭包: $CLOSURE"
[ -d "$CLOSURE/@deepseek-ai/dsh" ] || { echo "错误：闭包里没有 @deepseek-ai/dsh，看起来不是发布形态" >&2; exit 3; }
rm -rf "$NM_DIR"
mkdir -p "$(dirname "$NM_DIR")"
cp -a "$CLOSURE" "$NM_DIR"

if [ "$SKIP_PATCH" = "0" ]; then
  echo "[3/6] 打 Android/bionic 补丁"
  ROOT="$ROOT" ARCH="$ARCH" bash "$SCRIPT_DIR/patch-engine-closure.sh" | sed 's/^/      /'
else
  echo "[3/6] 跳过补丁（--skip-patch）"
fi

echo "[4/6] 发布形态自检"
COSMOKIT=$(ls "$NM_DIR/@deepseek-ai" 2>/dev/null | grep -c '^cosmokit' || true)
[ "$COSMOKIT" -gt 0 ] || { echo "错误：闭包缺 @deepseek-ai/cosmokit（monorepo 直出？）" >&2; exit 4; }
WS=$(grep -c 'workspace:' "$NM_DIR/@deepseek-ai/cordis/package.json" 2>/dev/null || true)
[ "$WS" = "0" ] || { echo "错误：cordis/package.json 里还有 workspace: 协议" >&2; exit 5; }
node -e 'require(process.argv[1])' "$NM_DIR/@deepseek-ai/cordis/lib/index.js" >/dev/null 2>&1 \
  || { echo "错误：require cordis 失败" >&2; exit 6; }
echo "      cosmokit=$COSMOKIT workspace:$WS cordis=require-ok"

echo "[5/6] 写版本标记: $VERSION"
printf '%s\n' "$VERSION" > "$ROOT/.dsh-engine-version"

echo "[6/6] 打包（lib/node_modules + lib/dsh-engine/lib + .dsh-engine-version）"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT" "$OUT.manifest.json"
# ---- 4.4 依赖审计门（每次编译必跑；FAIL 直接中断，避免发出「read_image 坏」这类包）----
if [ -f "$SCRIPT_DIR/check-engine-deps.sh" ]; then
  echo "[deps] 依赖审计：$ROOT/lib/node_modules"
  bash "$SCRIPT_DIR/check-engine-deps.sh" --root "$ROOT/lib/node_modules" || {
    echo "错误：依赖审计未通过，停止打包（豁免见 scripts/check-engine-deps.allow）" >&2; exit 1; }
else
  echo "missing dependency gate: $SCRIPT_DIR/check-engine-deps.sh" >&2; exit 1
fi

NATIVE_DIR=""
[ -d "$ROOT/lib/dsh-engine/lib" ] && NATIVE_DIR="lib/dsh-engine/lib"
if [ -n "$NATIVE_DIR" ]; then
  echo "      原生库: $(ls "$ROOT/$NATIVE_DIR" | wc -l) files（dsh-update 会连 lib/dsh-engine/lib 一起换）"
fi
tar -czf "$OUT" -C "$ROOT" .dsh-engine-version lib/node_modules $NATIVE_DIR
SIZE=$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT")
SHA=$(sha256sum "$OUT" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$OUT" | cut -d' ' -f1)
PKGS=$(ls "$NM_DIR/@deepseek-ai" | wc -l | tr -d ' ')
echo "engine tar: $OUT"
echo "  version=$VERSION  size=$SIZE  bytes  packages=$PKGS"
echo "  sha256=$SHA"
if [ -n "${KEEP_TREE:-}" ]; then
  mkdir -p "$KEEP_TREE"; cp -a "$ROOT/." "$KEEP_TREE/"; echo "  解包树保留在 $KEEP_TREE"
fi
if [ -n "$DOWNLOAD_BASE" ]; then
  DSH_VERSION=$(node -p 'require(process.argv[1]).version' "$NM_DIR/@deepseek-ai/dsh/package.json")
  python3 "$SCRIPT_DIR/make-engine-feed.py" --artifact "$OUT" --arch "$ARCH" \
    --dsh-version "$DSH_VERSION" --engine-version "$VERSION" \
    --download-base "$DOWNLOAD_BASE" --out "$OUT.manifest.json"
fi
echo "核对产物后：dsh-update stage <设备内路径> $SHA；再手动 apply"
