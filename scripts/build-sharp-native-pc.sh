#!/usr/bin/env bash
# 在 **PC 上交叉编译** sharp 的原生 addon（aarch64 Android / bionic），不需要手机参与。
#
# 为什么要在 PC 上编：手机编要装 clang+libvips（200 MB+）、跑 10 分钟；PC 上 NDK 一次编译
# 只要几秒，产物与手机编出来的功能等价（都是 aarch64 bionic ELF）。
#
# 原理：sharp 的 addon 只依赖两样外部东西 —— node/v8 的头文件、libvips 的头+库。
# 用 NDK clang 指定 `--target=aarch64-linux-android<api>`，把 Termux 的 libvips sysroot
# （bionic 编的 .so + 头文件）当 -I/-L 用即可，无需 CMake/gyp。
#
# 前置：
#   1. NDK（r26/r28 都行）：$ANDROID_HOME/ndk/<ver>/toolchains/llvm/prebuilt/linux-x86_64
#   2. Termux 的 libvips sysroot（含 include/{vips,glib-2.0} + lib/glib-2.0/include +
#      lib/*.so*）。在设备上一条命令生成、拉回 PC：
#        # 设备内（装了 libvips 的 Termux）：
#        cd $PREFIX && tar czf /sdcard/Download/vips-sysroot.tar.gz \
#          include/vips include/glib-2.0 include/gio include/gobject lib/glib-2.0/include \
#          lib/libvips.so* lib/libvips-cpp.so* lib/libglib-2.0.so* lib/libgobject-2.0.so* \
#          lib/libgio-2.0.so* lib/libgmodule-2.0.so* lib/libexpat.so* lib/libjpeg.so* lib/libpng*.so* \
#          lib/libtiff.so* lib/libwebp.so* lib/libheif.so* lib/liblcms2.so* lib/libxml2.so* \
#          lib/libpcre2-8.so* lib/libffi.so* lib/libintl.so* lib/libz.so*
#        # PC 上：adb pull /sdcard/Download/vips-sysroot.tar.gz && mkdir sysroot && tar xzf ... -C sysroot
#   3. 闭包里已有 sharp 源码：<closure>/sharp/src/*.cc（发布形态闭包自带）
#
# 用法：
#   scripts/build-sharp-native-pc.sh \
#     --sysroot /tmp/vipsroot \
#     --closure /tmp/consumer/node_modules \
#     --out /tmp/sharp-android-arm64.node \
#     [--ndk <path>] [--api 26] [--node-version v24.18.0]
#
# 产物用法：拷到 <PREFIX>/lib/node_modules/sharp/src/build/Release/sharp-android-arm64-<sharpver>.node
# （sharp 加载器首选路径）。它 NEEDED Termux 的 libvips；把 libvips 依赖闭包一起放进
# <PREFIX>/lib/dsh-engine/lib（RUNPATH 已含该目录）就能开箱即用，见 ENGINE-BUILD.md。
set -euo pipefail

SYSROOT="" CLOSURE="" OUT="" NDK="" API=26 NODE_VERSION=v24.18.0
while [ $# -gt 0 ]; do
  case "$1" in
    --sysroot) SYSROOT="$2"; shift 2 ;;
    --closure) CLOSURE="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --ndk) NDK="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --node-version) NODE_VERSION="$2"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SYSROOT" ] && [ -n "$CLOSURE" ] && [ -n "$OUT" ] || {
  echo "usage: $0 --sysroot <dir> --closure <node_modules> --out <file.node>" >&2; exit 2; }

say() { echo "[sharp-pc] $*"; }
die() { echo "[sharp-pc] 错误：$*" >&2; exit 1; }

# NDK 自动探测
if [ -z "$NDK" ]; then
  for base in "${ANDROID_HOME:-$HOME/Android/Sdk}" "$HOME/Android/Sdk" /opt/android-sdk; do
    [ -d "$base/ndk" ] || continue
    NDK="$base/ndk/$(ls "$base/ndk" | sort -V | tail -n1)"
    [ -n "$NDK" ] && break
  done
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || die "找不到 NDK（--ndk 指定，或设 ANDROID_HOME）"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang++"
[ -x "$CXX" ] || die "缺编译器：$CXX"

[ -d "$SYSROOT/include/vips" ] || die "sysroot 里没有 include/vips"
[ -f "$SYSROOT/lib/glib-2.0/include/glibconfig.h" ] || die "sysroot 里没有 lib/glib-2.0/include/glibconfig.h"
SRC="$CLOSURE/sharp"
[ -d "$SRC/src" ] || die "闭包里没有 sharp 源码：$SRC/src"
sharp_ver="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SRC/package.json" | head -n1)"
[ -n "$sharp_ver" ] || die "读不到 sharp 版本"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
say "取 node $NODE_VERSION 头文件"
curl -fsSL --retry 3 -o nh.tar.gz \
  "https://registry.npmmirror.com/-/binary/node/$NODE_VERSION/node-$NODE_VERSION-headers.tar.gz" || die "node 头下载失败"
tar xzf nh.tar.gz
say "取 node-addon-api 8.9.2"
curl -fsSL --retry 3 -o naa.tgz https://registry.npmmirror.com/node-addon-api/-/node-addon-api-8.9.2.tgz || die "下载失败"
mkdir -p naa && tar xzf naa.tgz -C naa --strip-components=1

PREFIX_DEVICE=/data/data/com.termux/files/usr
INC="-I$WORK/node-$NODE_VERSION/include/node -I$WORK/naa \
-I$SYSROOT/include -I$SYSROOT/include/glib-2.0 -I$SYSROOT/lib/glib-2.0/include -I$SYSROOT/include/vips"
DEFS="-DG_DISABLE_ASSERT -DG_DISABLE_CAST_CHECKS -DG_DISABLE_CHECKS -DNAPI_VERSION=9 \
-DNODE_ADDON_API_DISABLE_DEPRECATED -DNODE_API_SWALLOW_UNTHROWABLE_EXCEPTIONS \
-DSHARP_USE_GLOBAL_LIBVIPS -D_GLIBCXX_USE_CXX11_ABI=1"
FLAGS="-fPIC -std=c++17 -fexceptions -Os -w $DEFS $INC"

mkdir -p obj
for f in common metadata stats operations pipeline utilities sharp; do
  say "编译 $f.cc"
  "$CXX" $FLAGS -c "$SRC/src/$f.cc" -o "obj/$f.o" || die "编译失败：$f.cc"
done

NAME="sharp-android-arm64-$sharp_ver.node"
say "链接 $NAME"
# RUNPATH 两处都搜：全局 $PREFIX/lib（用户 pkg install libvips 时）与引擎层
# $PREFIX/lib/dsh-engine/lib（我们捆绑 libvips 时）
"$CXX" -shared -o "$NAME" obj/*.o \
  -L"$SYSROOT/lib" -L"$PREFIX_DEVICE/lib" \
  -lvips-cpp -lvips -lglib-2.0 -lgobject-2.0 -lgio-2.0 \
  -Wl,-rpath,"$PREFIX_DEVICE/lib:$PREFIX_DEVICE/lib/dsh-engine/lib" \
  -Wl,-z,nodelete -Wl,-s || die "链接失败"

mkdir -p "$(dirname "$OUT")"
cp -f "$NAME" "$OUT"
say "产物：$OUT ($(stat -c%s "$OUT") B)"
"${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$OUT" 2>/dev/null | sed -n '1,10p' || true
say "自检：file 应为 'ARM aarch64'；NEEDED 含 libvips-cpp.so.42"
