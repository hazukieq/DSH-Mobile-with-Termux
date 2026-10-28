#!/usr/bin/env bash
# 给引擎闭包补上 sharp 的 WebAssembly 回退（Android 必需）。
#
# ## 为什么
# dsh 用 sharp 做图片归一化（`read_image` / 附件预处理）。sharp 的预编译二进制只覆盖
# darwin/linux/linuxmusl/win32/freebsd/wasm32 —— **没有 android**；而 `@img/sharp-linux-arm64`
# 是 glibc 产物（NEEDED libc.so.6 / ld-linux-aarch64.so.1），Android 的 bionic 加载不了。
# 于是 sharp 在 Android 上既无预编译、又找不到自家 wasm 回退，最终抛：
#   Could not load the "sharp" module using the android-arm64 runtime
# 结果是 `read_image` 永久不可用。
#
# ## 做法
# 把 `@img/sharp-wasm32` + `@emnapi/runtime` 装进闭包，并**双落位**：
#   <NM>/@img/sharp-wasm32
#   <NM>/@emnapi/runtime
#   <NM>/sharp/node_modules/@img/sharp-wasm32
#   <NM>/sharp/node_modules/@emnapi/runtime
# 只放顶层时活跃引擎进程仍会报同样的错（实测），嵌套一份才会被解析到 —— 推测与 Node
# 对首次失败解析路径的缓存有关。
#
# ## 用法
#   NM=/tmp/eng/lib/node_modules bash scripts/patch-sharp-wasm.sh
#   NM=... FORCE=1 bash scripts/patch-sharp-wasm.sh     # 覆盖已存在
#   NM=... DRY=1 bash scripts/patch-sharp-wasm.sh       # 只报告
#
# 环境变量：NPM_REGISTRY（默认 https://registry.npmmirror.com）、EMNAPI_RANGE（默认 ^1.11.3）
set -euo pipefail

NM="${NM:?需要 NM=<引擎的 lib/node_modules 目录>}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
EMNAPI_RANGE="${EMNAPI_RANGE:-^1.11.3}"
FORCE="${FORCE:-0}"
DRY="${DRY:-0}"

say() { echo "[sharp] $*"; }
die() { echo "[sharp] 错误：$*" >&2; exit 1; }

SHARP_DIR="$NM/sharp"
[ -d "$SHARP_DIR" ] || { say "闭包里没有 sharp，跳过"; exit 0; }

sharp_ver="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SHARP_DIR/package.json" | head -n1)"
[ -n "$sharp_ver" ] || die "读不到 $SHARP_DIR/package.json 的 version"
say "sharp $sharp_ver"

top_wasm="$NM/@img/sharp-wasm32"
top_emnapi="$NM/@emnapi/runtime"
nested_wasm="$SHARP_DIR/node_modules/@img/sharp-wasm32"
nested_emnapi="$SHARP_DIR/node_modules/@emnapi/runtime"

have_all=1
for d in "$top_wasm" "$top_emnapi" "$nested_wasm" "$nested_emnapi"; do
  [ -d "$d" ] || have_all=0
done
if [ "$have_all" = 1 ] && [ "$FORCE" != 1 ]; then
  say "已存在（$top_wasm 等 4 处），跳过；FORCE=1 可覆盖"
  exit 0
fi

fetch_tgz() {  # <url> <destdir> <label>
  local url="$1" dest="$2" label="$3" tmp
  tmp="$(mktemp -d)"
  say "下载 $label"
  curl -fsSL --retry 3 --max-time 300 "$url" -o "$tmp/pkg.tgz" || { rm -rf "$tmp"; die "下载失败：$url"; }
  rm -rf "$dest"; mkdir -p "$dest"
  tar xzf "$tmp/pkg.tgz" -C "$dest" --strip-components=1 || { rm -rf "$tmp"; die "解包失败：$label"; }
  rm -rf "$tmp"
}

wasm_url="$NPM_REGISTRY/@img/sharp-wasm32/-/sharp-wasm32-$sharp_ver.tgz"
emnapi_latest="$(curl -fsSL --max-time 60 "$NPM_REGISTRY/@emnapi/runtime/latest" 2>/dev/null \
  | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
[ -n "$emnapi_latest" ] || emnapi_latest="1.11.3"
case "$emnapi_latest" in 1.*) : ;; *) emnapi_latest="1.11.3" ;; esac
emnapi_url="$NPM_REGISTRY/@emnapi/runtime/-/runtime-$emnapi_latest.tgz"

if [ "$DRY" = 1 ]; then
  say "DRY: 将下载 $wasm_url 与 $emnapi_url，写入 4 处"
  exit 0
fi

fetch_tgz "$wasm_url" "$top_wasm" "@img/sharp-wasm32@$sharp_ver"
fetch_tgz "$emnapi_url" "$top_emnapi" "@emnapi/runtime@$emnapi_latest"

mkdir -p "$(dirname "$nested_wasm")" "$(dirname "$nested_emnapi")"
rm -rf "$nested_wasm" "$nested_emnapi"
cp -a "$top_wasm" "$nested_wasm"
cp -a "$top_emnapi" "$nested_emnapi"

# 校验：包的入口是 exports["./sharp.node"] → index.cjs（sharp 的加载器正是 require
# "@img/sharp-wasm32/sharp.node"），真正的大块在同目录 lib/*.node.wasm。
[ -f "$top_wasm/index.cjs" ] || die "解包后缺 index.cjs（@img/sharp-wasm32 布局异常）"
wasm_blob="$(find "$top_wasm/lib" -maxdepth 1 -name '*.node.wasm' -print -quit 2>/dev/null || true)"
[ -n "$wasm_blob" ] || die "解包后找不到 lib/*.node.wasm"
sz=$(stat -c%s "$wasm_blob" 2>/dev/null || stat -f%z "$wasm_blob")
[ "$sz" -gt 8000000 ] || die "wasm 太小（$sz B），疑似下载不完整"
# exports 自检：sharp 加载器写的是 require("@img/sharp-wasm32/sharp.node")，
# 该子路径由包的 exports 映射到 ./index.cjs —— 确认映射与目标文件都在。
if command -v node >/dev/null 2>&1; then
  node -e "
    const fs=require('fs'),path=require('path');
    const dir='$top_wasm';
    const pkg=JSON.parse(fs.readFileSync(path.join(dir,'package.json'),'utf8'));
    const tgt=pkg.exports && pkg.exports['./sharp.node'];
    if(!tgt) { console.error('exports[\"./sharp.node\"] 缺失'); process.exit(1); }
    if(!fs.existsSync(path.join(dir,tgt))) { console.error('exports 指向的文件不存在: '+tgt); process.exit(1); }
  " || die "exports 自检失败"
fi
for d in "$top_wasm" "$top_emnapi" "$nested_wasm" "$nested_emnapi"; do
  [ -d "$d" ] || die "缺目录 $d"
done

say "已安装 @img/sharp-wasm32@$sharp_ver + @emnapi/runtime@$emnapi_latest（4 处落位，wasm $(du -sh "$top_wasm" | cut -f1)）"
say "重启引擎后生效：read_image 会走 wasm 回退"
