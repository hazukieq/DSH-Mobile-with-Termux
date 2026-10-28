#!/data/data/com.termux/files/usr/bin/bash
# 在 Android 设备上（或任何 bionic + Termux 前缀环境）编译 sharp 的原生 addon。
#
# 为什么不用 node-gyp/npm：
#  · 0.2.2 引擎不带 corepack，`npm` 包装器会去 require lib/node_modules/corepack/dist/npm.js
#    直接报 MODULE_NOT_FOUND（所以这里 curl 下 tarball，不跑 npm）；
#  · Android 的 `/tmp` 只读，必须用 `$PREFIX/tmp`；
#  · Android 没有 `/usr/bin/env`，`.bin/node-gyp` 这类脚本不能直接执行。
#
# 产物：sharp-android-arm64-<ver>.node（约 340 KB），放到
#   <PREFIX>/lib/node_modules/sharp/src/build/Release/
# 即 sharp 加载器的**首选路径**，无需改 JS。
#
# 依赖（pkg install）：libvips clang pkg-config curl tar
# 注意：原生 addon 动态依赖 Termux 的 libvips（RUNPATH=$PREFIX/lib）。没有 libvips 的设备
# 上 dlopen 会失败，sharp 自动回落到 wasm（所以二者要一起打，见 patch-sharp-wasm.sh）。
#
# 用法（设备内）：
#   bash scripts/build-sharp-native.sh            # 编到 $PREFIX/tmp 并拷到 /sdcard/Download
#
# 环境变量：SHARP_NODE_MODULES（默认 $PREFIX/lib/node_modules）、NODE_VERSION（默认 v24.18.0）
set -euo pipefail
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
NM="${SHARP_NODE_MODULES:-$PREFIX/lib/node_modules}"
NODE_VERSION="${NODE_VERSION:-v24.18.0}"
WORK="$PREFIX/tmp/sharp-native-build"
OUT_DIR="${OUT_DIR:-/sdcard/Download}"

say() { echo "[sharp-native] $*"; }
die() { echo "[sharp-native] 错误：$*" >&2; exit 1; }

command -v clang++ >/dev/null || die "缺 clang++（pkg install clang）"
command -v pkg-config >/dev/null || die "缺 pkg-config"
pkg-config --exists vips-cpp || die "缺 libvips 开发文件（pkg install libvips）"
sharp_ver="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$NM/sharp/package.json" | head -n1)"
[ -n "$sharp_ver" ] || die "读不到 sharp 版本"

rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"

say "下载 node $NODE_VERSION 头文件"
curl -fsSL --retry 3 -o node-headers.tar.gz \
  "https://registry.npmmirror.com/-/binary/node/$NODE_VERSION/node-$NODE_VERSION-headers.tar.gz" \
  || die "node 头文件下载失败"
tar xzf node-headers.tar.gz || die "头文件解包失败"

say "下载 node-addon-api 8.9.2"
curl -fsSL --retry 3 -o naa.tgz \
  https://registry.npmmirror.com/node-addon-api/-/node-addon-api-8.9.2.tgz || die "node-addon-api 下载失败"
mkdir -p naa && tar xzf naa.tgz -C naa --strip-components=1

say "准备 sharp $sharp_ver 源码"
cp -a "$NM/sharp" ./sharp
cd sharp

VIPS_CFLAGS="$(pkg-config --cflags vips-cpp glib-2.0)"
VIPS_LIBS="$(pkg-config --libs vips-cpp)"
INC="-I$WORK/node-$NODE_VERSION/include/node -I$WORK/naa $VIPS_CFLAGS"
DEFS="-DG_DISABLE_ASSERT -DG_DISABLE_CAST_CHECKS -DG_DISABLE_CHECKS -DNAPI_VERSION=9 \
-DNODE_ADDON_API_DISABLE_DEPRECATED -DNODE_API_SWALLOW_UNTHROWABLE_EXCEPTIONS \
-DSHARP_USE_GLOBAL_LIBVIPS -D_GLIBCXX_USE_CXX11_ABI=1"
CXXFLAGS="-fPIC -std=c++17 -fexceptions -Os -w $DEFS $INC"

mkdir -p obj
for f in src/common.cc src/metadata.cc src/stats.cc src/operations.cc src/pipeline.cc \
         src/utilities.cc src/sharp.cc; do
  say "编译 $(basename "$f")"
  clang++ $CXXFLAGS -c "$f" -o "obj/$(basename "${f%.cc}").o" || die "编译失败：$f"
done

OUT="sharp-android-arm64-$sharp_ver.node"
say "链接 $OUT"
clang++ -shared -o "$OUT" obj/*.o $VIPS_LIBS \
  -L"$PREFIX/lib" -Wl,-rpath,"$PREFIX/lib" -Wl,-z,nodelete -Wl,-s || die "链接失败"

say "产物：$(ls -l "$OUT" | awk '{print $5}') B"
readelf -d "$OUT" | sed -n '1,8p'
cp -a "$OUT" "$OUT_DIR/" && say "已拷到 $OUT_DIR/$OUT"
say "装机：cp 到 \$PREFIX/lib/node_modules/sharp/src/build/Release/ 后重启引擎"
