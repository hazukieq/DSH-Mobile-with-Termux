#!/usr/bin/env bash
# 只换 JS 闭包、复用现有引擎骨架，产出一个可被 `dsh-update stage/apply` 的引擎 tar。
#
# 为什么只换闭包：Termux 运行时（node/bash/rg + lib/*.so）在 App 里是稳定的，
# 每次升级 dsh 只变 lib/node_modules。重抓 apt、重编运行时既慢又容易把环境弄脏
# （2026-10 v2.5.48 实测：collect 全流程 2 分钟且会牵动宿主 apt）。
#
# 输入闭包必须是「发布形态」：上游 `scripts/release/pack.ts --family dsh` 打出的
# tarball 用 `file:` 装出来的 node_modules，或官方 npm 发布闭包；
# 直接塞 monorepo 的 node_modules 会残留 workspace: 且缺 cosmokit，引擎起不来。
#
# 用法：
#   scripts/build-engine-closure.sh \
#     --engine app/src/main/assets/engine-dsh-aarch64.zip \
#     --closure /tmp/release-consumer/node_modules \
#     --version 0.2.2-alpha.2+src1 \
#     --out /tmp/engine-dsh-aarch64-closure.tar.gz
#
# 输出 tar 只含 `lib/node_modules/**` 与 `.dsh-engine-version`（dsh-update 约定的最小布局）。
set -euo pipefail

ENGINE="" CLOSURE="" VERSION="" OUT="" ARCH="aarch64" SKIP_PATCH=0 KEEP_TREE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --engine) ENGINE="$2"; shift 2 ;;
    --closure) CLOSURE="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --arch) ARCH="$2"; shift 2 ;;
    --skip-patch) SKIP_PATCH=1; shift ;;
    --keep-tree) KEEP_TREE="$2"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$ENGINE" ] && [ -n "$CLOSURE" ] && [ -n "$VERSION" ] && [ -n "$OUT" ] || {
  echo "usage: $0 --engine <engine.zip|dir> --closure <node_modules> --version <ver> --out <tar.gz>" >&2
  exit 2
}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORK="$(mktemp -d /tmp/dsh-engclosure.XXXXXX)"
trap '[ -n "${KEEP_TREE:-}" ] || rm -rf "$WORK"' EXIT
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
node -e "require('$NM_DIR/@deepseek-ai/cordis/lib/index.js')" >/dev/null 2>&1 \
  || { echo "错误：require cordis 失败" >&2; exit 6; }
echo "      cosmokit=$COSMOKIT workspace:$WS cordis=require-ok"

echo "[5/6] 写版本标记: $VERSION"
printf '%s\n' "$VERSION" > "$ROOT/.dsh-engine-version"

echo "[6/6] 打包（仅 lib/node_modules + .dsh-engine-version）"
rm -f "$OUT"
# ---- 4.4 依赖审计门（每次编译必跑；FAIL 直接中断，避免发出「read_image 坏」这类包）----
if [ -x "$SCRIPT_DIR/check-engine-deps.sh" ]; then
  echo "[deps] 依赖审计：$ROOT/lib/node_modules"
  "$SCRIPT_DIR/check-engine-deps.sh" --root "$ROOT/lib/node_modules" || {
    echo "错误：依赖审计未通过，停止打包（豁免见 scripts/check-engine-deps.allow）" >&2; exit 1; }
fi

tar -czf "$OUT" -C "$ROOT" .dsh-engine-version lib/node_modules
SIZE=$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT")
SHA=$(sha256sum "$OUT" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$OUT" | cut -d' ' -f1)
PKGS=$(ls "$NM_DIR/@deepseek-ai" | wc -l | tr -d ' ')
echo "engine tar: $OUT"
echo "  version=$VERSION  size=$SIZE  bytes  packages=$PKGS"
echo "  sha256=$SHA"
if [ -n "${KEEP_TREE:-}" ]; then
  mkdir -p "$KEEP_TREE"; cp -a "$ROOT/." "$KEEP_TREE/"; echo "  解包树保留在 $KEEP_TREE"
fi
echo "下一步：adb push $OUT /sdcard/Download/ && dsh-update stage <path> $SHA && dsh-update apply"
