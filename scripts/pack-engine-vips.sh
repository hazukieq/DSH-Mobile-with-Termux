#!/usr/bin/env bash
# 把 libvips 的**运行时依赖闭包**拷进引擎层私有库目录（<engine-tree>/lib/dsh-engine/lib），
# 这样设备上没装 libvips 也能走 sharp 原生 addon（否则 dlopen 失败 → 回落 wasm）。
#
# 背景：sharp 原生 addon 的 NEEDED 含 libvips-cpp/libvips/glib/gio…（Termux 里 60+ 个 .so）。
# 引擎层的 node 启动时把 <PREFIX>/lib/dsh-engine/lib 放进库搜索路径，工具链的 RUNPATH 也含它，
# 所以把这批 .so 放进 <engine-tree>/lib/dsh-engine/lib 就等于「开箱可用」。
# ⚠️ 必须放私有库目录，不能放 Termux 的 $PREFIX/lib：那属于 bootstrap，dsh-update 不碰，
#    写进去等于污染用户系统库，而且换引擎时不会被替换。
#
# 用法：
#   scripts/pack-engine-vips.sh --libs /tmp/vipsroot/lib --tree /tmp/engine/engine-tree
#   （--libs 是 PC 上的目录，里面是从设备 $PREFIX/lib 拉回来的 *.so*；见 build-sharp-native-pc.sh 头注释）
#
# 可选：--dry-run 只打印闭包与体积。
set -euo pipefail

LIBS=""; TREE=""; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --libs) LIBS="$2"; shift 2 ;;
    --tree) TREE="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$LIBS" ] && [ -n "$TREE" ] || { echo "usage: $0 --libs <dir> --tree <engine-tree> [--dry-run]" >&2; exit 2; }
[ -d "$LIBS" ] || { echo "no such dir: $LIBS" >&2; exit 2; }

DEST="$TREE/lib/dsh-engine/lib"
mkdir -p "$DEST"
python3 - "$LIBS" "$DEST" "$DRY" <<'PY'
import os, re, shutil, subprocess, sys
libs, tree, dry = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
# 这些由引擎层/系统自己提供，不要重复打（libc++_shared 在引擎 lib 已有）
SKIP = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
        "libstdc++.so", "libc++_shared.so"}
env = {**os.environ, "LC_ALL": "C"}
readelf = shutil.which("llvm-readelf") or shutil.which("readelf") or "readelf"

def needed(p):
    out = subprocess.run([readelf, "-d", p], capture_output=True, text=True, env=env).stdout
    return re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", out)

def soname(p):
    """读 ELF 的 SONAME：落地文件名必须用它，否则 dlopen 按 SONAME 找不到。
    Termux 里常见只有 libpng16.so（无 .16 实体）——它自带的 SONAME 才是 libpng16.so.16。"""
    out = subprocess.run([readelf, "-d", p], capture_output=True, text=True, env=env).stdout
    m = re.search(r"\(SONAME\)\s+Library soname: \[([^\]]+)\]", out)
    return m.group(1) if m else os.path.basename(p)

files = os.listdir(libs)
def find(n):
    if n in files: return os.path.join(libs, n)
    for f in files:
        if f.startswith(n): return os.path.join(libs, f)
    return None

roots = [find("libvips-cpp.so.42"), find("libvips.so.42")]
roots = [r for r in roots if r]
if not roots:
    sys.exit("错误：--libs 里没有 libvips-cpp.so.42 / libvips.so.42")
seen, missing, todo = set(), set(), list(roots)
while todo:
    p = todo.pop()
    if not p or p in seen: continue
    seen.add(p)
    for n in needed(p):
        if n in SKIP: continue
        q = find(n)
        todo.append(q) if q else missing.add(n)

total = sum(os.path.getsize(p) for p in seen)
print(f"[vips] 闭包 {len(seen)} 个 .so，合计 {total/1048576:.1f} MB")
if missing:
    print("[vips] 警告：缺以下依赖（设备上可能没装/名字变了）：", ", ".join(sorted(missing)[:8]))
if dry:
    for p in sorted(seen, key=os.path.getsize, reverse=True)[:10]:
        print(f"  {os.path.getsize(p)/1048576:6.2f} MB {os.path.basename(p)}")
    sys.exit(0)

dst = tree  # 调用方已传进来私有库目录（<tree>/lib/dsh-engine/lib）
os.makedirs(dst, exist_ok=True)
n = 0
for p in sorted(seen):
    name = soname(p)
    shutil.copyfile(p, os.path.join(dst, name))
    # 同时保留源文件名，兼容「NEEDED 写的是无版本名」的情况
    base = os.path.basename(p)
    if base != name and not os.path.exists(os.path.join(dst, base)):
        shutil.copyfile(p, os.path.join(dst, base))
    n += 1
print(f"[vips] 已拷 {n} 个 .so 到 {dst}（按 SONAME 落地）")
PY
