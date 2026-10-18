#!/usr/bin/env bash
# 构建**自包含**的 dsh 引擎层（APK 资产 zip 与热更新 tar.gz 共用同一套布局）。
#
# 为什么自包含：引擎的 bin/node 是从 Termux nodejs 包抽出来的，NEEDED 里有
# libcares/libicu/libssl/libcrypto/libz/libsqlite3 等。新装的机器上 bootstrap 里
# **没有**这些库（第一台手机因为早期 pkg install nodejs 残留了才没暴露），
# 于是 node 直接 "CANNOT LINK EXECUTABLE ... libcares.so not found"。
# 所以把 node/rg 及其依赖库放进私有目录，用 wrapper 设 LD_LIBRARY_PATH，
# 既不缺库、也不会覆盖 bootstrap 自己的同名库（apt/dpkg 照旧用它的）。
#
# 布局：
#   bin/node            wrapper -> ../lib/dsh-engine/node-bin（私有 LD_LIBRARY_PATH）
#   bin/rg              wrapper -> ../lib/dsh-engine/rg-bin
#   bin/bash            wrapper -> ../lib/dsh-engine/bash-bin（引擎启动即可用 shell；
#                       后台 bootstrap 解压后会被官方 bash 覆盖）
#   lib/dsh-engine/node-bin, rg-bin
#   lib/dsh-engine/lib/*.so*       node/rg 的 NEEDED 闭包
#   lib/node_modules/**            dsh 适配闭包（npm 布局，dshEntry 路径依赖它）
#   etc/tls/{cert.pem,openssl.cnf}  HTTPS 证书/配置
#   .dsh-engine-version
#
# 用法:
#   scripts/build-engine-layer.sh --runtime runtime-arm64.zip --arch aarch64 --out /tmp/engine
#   产物: <out>/engine-tree/（目录）、<out>/engine-dsh-<arch>.zip、
#         <out>/engine-dsh-<arch>.tar.gz、<out>/engine-manifest.json
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME=""; ARCH=aarch64; OUT=""; ENGINE_VERSION=""
while [[ $# -gt 0 ]]; do
	case "$1" in
		--runtime) RUNTIME="$2"; shift 2 ;;
		--arch) ARCH="$2"; shift 2 ;;
		--out) OUT="$2"; shift 2 ;;
		--engine-version) ENGINE_VERSION="$2"; shift 2 ;;
		-h|--help) sed -n '2,30p' "$0"; exit 0 ;;
		*) echo "unknown: $1" >&2; exit 2 ;;
	esac
done
[[ -n "$RUNTIME" && -n "$OUT" ]] || { echo "--runtime and --out are required" >&2; exit 2; }
READELF="${READELF:-$(ls -d "$HOME"/.termux-build/_cache/android-r*-api-*/bin/llvm-readelf 2>/dev/null | tail -1)}"
[[ -n "$READELF" ]] || { echo "llvm-readelf not found; set READELF=" >&2; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/engine-layer.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
RT="$WORK/rt"
mkdir -p "$RT"
unzip -q "$RUNTIME" "bin/node" "bin/rg" "bin/bash" "lib/node_modules/*" "lib/*.so*" "etc/tls/*" -d "$RT"
TREE="$OUT/engine-tree"
rm -rf "$TREE"; mkdir -p "$TREE/lib/dsh-engine/lib" "$TREE/bin"
cp -a "$RT/lib/node_modules" "$TREE/lib/node_modules"
mkdir -p "$TREE/etc"; cp -a "$RT/etc/tls" "$TREE/etc/tls" 2>/dev/null || true

python3 - "$RT" "$TREE" "$READELF" <<'PY'
import os, re, shutil, subprocess, sys
rt, tree, readelf = sys.argv[1], sys.argv[2], sys.argv[3]

def needed(path):
    out = subprocess.run([readelf, '-d', path], capture_output=True, text=True).stdout
    return re.findall(r'NEEDED\)\s+Shared library: \[([^\]]+)\]', out)

def find_lib(name):
    p = os.path.join(rt, 'lib', name)
    if os.path.isfile(p): return p
    for f in os.listdir(os.path.join(rt, 'lib')):
        q = os.path.join(rt, 'lib', f)
        if not os.path.isfile(q): continue
        try:
            d = subprocess.run([readelf, '-d', q], capture_output=True, text=True).stdout
            m = re.search(r'SONAME\)\s+Library soname: \[([^\]]+)\]', d)
            if m and m.group(1) == name: return q
        except Exception:
            pass
    return None

seen, queue = set(), []
for b in ('node', 'rg', 'bash'):
    src = os.path.join(rt, 'bin', b)
    if not os.path.isfile(src):
        continue
    dst = os.path.join(tree, 'lib/dsh-engine', b + '-bin')
    shutil.copyfile(src, dst); os.chmod(dst, 0o755)
    queue.extend(needed(src))
while queue:
    name = queue.pop(0)
    if name in seen: continue
    seen.add(name)
    if name.startswith(('libc.so', 'libm.so', 'libdl.so', 'liblog.so')): continue
    p = find_lib(name)
    if not p: continue
    dst = os.path.join(tree, 'lib/dsh-engine/lib', os.path.basename(p))
    if not os.path.exists(dst):
        shutil.copyfile(p, dst); os.chmod(dst, 0o755)
    queue.extend(needed(p))
print('[engine] private libs:', len(os.listdir(os.path.join(tree, 'lib/dsh-engine/lib'))))
PY

for n in node rg bash; do
cat > "$TREE/bin/$n" <<WRAP
#!/system/bin/sh
# [dsh-android] $n wrapper: 私有 lib 目录（不覆盖 Termux bootstrap 的库）
d=\$(cd "\${0%/*}/.." && pwd)
LD_LIBRARY_PATH="\$d/lib/dsh-engine/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}" exec "\$d/lib/dsh-engine/$n-bin" "\$@"
WRAP
chmod 755 "$TREE/bin/$n"
done

DSH_VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$TREE/lib/node_modules/@deepseek-ai/dsh/package.json" | head -1)"
[[ -n "$DSH_VERSION" ]] || { echo "cannot read dsh version" >&2; exit 1; }
[[ -n "$ENGINE_VERSION" ]] || ENGINE_VERSION="dsh-engine-$DSH_VERSION-$(date +%Y%m%d)"
printf '%s' "$ENGINE_VERSION" > "$TREE/.dsh-engine-version"

# 符号链接物化（zip/RuntimeInstaller 契约：条目一律当普通文件）
python3 - "$TREE" <<'PY'
import os, shutil, sys
root=sys.argv[1]; real=os.path.realpath(root)
for dp, dns, fns in os.walk(root, followlinks=False):
    for n in dns+fns:
        p=os.path.join(dp,n)
        if not os.path.islink(p): continue
        try:
            t=os.path.realpath(p)
            if not t.startswith(real+os.sep): os.unlink(p); continue
            if os.path.isdir(t): os.unlink(p); shutil.copytree(t,p,symlinks=False)
            else:
                m=os.stat(t).st_mode & 0o777
                os.unlink(p); shutil.copyfile(t,p); os.chmod(p,m)
        except Exception:
            try: os.unlink(p)
            except Exception: pass
PY

rm -f "$OUT/engine-dsh-$ARCH.zip" "$OUT/engine-dsh-$ARCH.tar.gz"
( cd "$TREE" && zip -q -r "$OUT/engine-dsh-$ARCH.zip" . && tar -czf "$OUT/engine-dsh-$ARCH.tar.gz" . )
SHA="$(sha256sum "$OUT/engine-dsh-$ARCH.tar.gz" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT/engine-dsh-$ARCH.tar.gz")"
cat > "$OUT/engine-manifest.json" <<JSON
{
  "abi": "$ARCH",
  "dsh_version": "$DSH_VERSION",
  "engine_version": "$ENGINE_VERSION",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "assets": {
    "$ARCH": {
      "url": "https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/download/dsh-engine-latest/engine-dsh-$ARCH.tar.gz",
      "sha256": "$SHA",
      "size": $SIZE
    }
  }
}
JSON
echo "[engine] $OUT/engine-dsh-$ARCH.zip  $(sha256sum "$OUT/engine-dsh-$ARCH.zip" | cut -d' ' -f1)"
echo "[engine] $OUT/engine-dsh-$ARCH.tar.gz $SHA"
echo "[engine] dsh=$DSH_VERSION engine=$ENGINE_VERSION"
