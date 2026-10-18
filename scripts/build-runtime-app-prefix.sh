#!/usr/bin/env bash
# 用 termux-packages 的构建体系，把 dsh 运行时**按本 App 的包名/前缀重编译**。
#
# ## 为什么需要这个脚本
# DSH Mobile 的运行时来自 Termux 官方 .deb。Termux 的二进制把
# `/data/data/com.termux/files/usr` 编译期写死（bash 的 SYS_BASHRC/SYS_PROFILE、
# openssl 的默认配置目录、git 的 git-core/templates、python 的 stdlib 路径……）。
# 装进 `app.dsh.mobile` 之后：
#   · 文本文件可以运行时改写（DebInstaller 的路径改写逻辑），
#   · ELF 里的字符串改不了（我们的路径更长，无法等长替换），只能靠环境变量兜底，
#   · 而 bash 的系统 bashrc 没有环境变量可覆盖 —— 设备上装过 Termux 时会
#     每个交互 shell 打一行 `bash: /data/data/com.termux/files/usr/etc/bash.bashrc:
#     Permission denied`（真机实测）。
#
# 本脚本的做法：让 termux-packages 以
#   TERMUX_APP__PACKAGE_NAME=app.dsh.mobile
#   TERMUX__PREFIX_SUBDIR=engine      →  PREFIX=/data/data/app.dsh.mobile/files/engine
# 从源码重编 path-sensitive 的包，再把产物覆盖进从 Termux 收集的运行时里。
# 编译期路径自然指向本 App，不再需要任何 `--norc` 之类的兜底。
#
# ## 用法
#   ./scripts/build-runtime-app-prefix.sh --abi x86_64 \
#       --base-runtime /path/to/runtime-collected.zip --out /tmp/runtime-app-prefix.zip
#   ./scripts/build-runtime-app-prefix.sh --abi arm64-v8a --base-runtime ... --out ...
#
# --abi 可重复（x86_64 / arm64-v8a）。产物是**无符号链接**的 zip（与
# RuntimeInstaller 的契约一致：所有链接条目必须物化成真实文件）。
#
# ## 宿主前置
#   Linux x86_64；bwrap（造一个可写 /data 的私有 root，宿主没有 /data 权限）；
#   NDK r30（默认 $HOME/lib/android-ndk-r30；termux 工具链只支持 r30/r23c）；
#   gcc / patch / make / zip / unzip / dpkg-deb / python3 / curl。
#   不需要 root：/data 由 bwrap 的 tmpfs 提供。
#
# ## 已验证
#   2026-10-18：termux-packages main（bash 5.3.20 / ncurses 6.5 / readline 8.3），
#   x86_64 与 aarch64 双架构构建通过；产物在 Android 14 模拟器与 Redmi K40
#   (Android 16, arm64) 上实测：bash 5.3.20 正常、rc 正常读取、无 com.termux 字符串。
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SELF_DIR/.." && pwd)"

# 与 app/build.gradle.kts 的 applicationId 保持一致；改包名时必须同步改这里。
APP_PACKAGE="app.dsh.mobile"
# 与 EngineConfig.engineRoot() 保持一致（filesDir/engine）。
PREFIX_SUBDIR="engine"
# minSdk 26：bash 的 mblen 需要 API 26+，本 App 正好 26。
API_LEVEL=26

ABIS=()
BASE_RUNTIME=""
OUT=""
TERMUX_PKGS=""
NDK_DIR="${NDK:-$HOME/lib/android-ndk-r30}"
# 需要重编的包。`bash` 会递归带出 libandroid-support/libiconv/readline/ncurses。
PKGS="${PKGS:-bash proot dpkg zstd}"  # proot+dpkg+zstd: 原生包管理器
KEEP_WORK="${KEEP_WORK:-0}"

usage() {
	cat <<'EOF'
用法: build-runtime-app-prefix.sh --abi <x86_64|arm64-v8a> [--abi ...] \
        --base-runtime <collected-runtime.zip> --out <output-runtime.zip> \
        [--termux-pkgs <dir>] [--ndk <dir>] [--pkgs "bash ncurses"] [--keep-work]

  --abi            可重复。x86_64 / arm64-v8a
  --base-runtime   从 Termux 收集的基础运行时（scripts/collect-termux-runtime.sh 产物）
  --out            输出 zip（无符号链接，可直接放进 app/src/main/assets/runtime.zip）
  --termux-pkgs    termux-packages 检出目录（缺省：<repo 同级>/termux-packages，缺失则浅克隆）
  --pkgs           要重编的包名（空格分隔），缺省 "bash"
  --keep-work      保留中间目录（默认清理）
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--abi) ABIS+=("$2"); shift 2 ;;
		--base-runtime) BASE_RUNTIME="$2"; shift 2 ;;
		--out) OUT="$2"; shift 2 ;;
		--termux-pkgs) TERMUX_PKGS="$2"; shift 2 ;;
		--ndk) NDK_DIR="$2"; shift 2 ;;
		--pkgs) PKGS="$2"; shift 2 ;;
		--keep-work) KEEP_WORK=1; shift ;;
		-h|--help) usage; exit 0 ;;
		*) echo "未知参数: $1" >&2; usage; exit 2 ;;
	esac
done

[[ ${#ABIS[@]} -gt 0 ]] || { echo "缺少 --abi" >&2; usage; exit 2; }
if [[ ${#ABIS[@]} -gt 1 ]]; then
	echo "一次只能构建一个架构（产物是一棵完整运行时树）；请分多次运行，例如：" >&2
	echo "  $0 --abi x86_64     --base-runtime x86.zip --out runtime-x86_64.zip" >&2
	echo "  $0 --abi arm64-v8a  --base-runtime arm.zip --out runtime-arm64.zip" >&2
	exit 2
fi
[[ -n "$BASE_RUNTIME" && -f "$BASE_RUNTIME" ]] || { echo "--base-runtime 不存在: $BASE_RUNTIME" >&2; exit 2; }
[[ -n "$OUT" ]] || { echo "缺少 --out" >&2; exit 2; }

log() { echo "[build-runtime] $*" >&2; }

# ---------------------------------------------------------------------------
# 0) 依赖检查
# ---------------------------------------------------------------------------
for cmd in bwrap curl patch make zip unzip dpkg-deb python3 sha256sum; do
	command -v "$cmd" >/dev/null 2>&1 || { echo "缺少依赖: $cmd" >&2; exit 3; }
done
[[ -f "$NDK_DIR/source.properties" ]] || {
	echo "NDK 不存在: $NDK_DIR（termux 工具链需要 r30；见脚本头注释）" >&2; exit 3
}
NDK_REV="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK_DIR/source.properties" | head -1)"
case "$NDK_REV" in
	30.*) TERMUX_NDK_VERSION_NUM=30 ;;
	*) echo "NDK 版本 $NDK_REV 不被 termux-packages 支持（只认 r30/r23c）" >&2; exit 3 ;;
esac

# ---------------------------------------------------------------------------
# 1) 取 termux-packages 检出（只读参考仓库；本脚本会打本地补丁，全局只影响该检出）
# ---------------------------------------------------------------------------
if [[ -z "$TERMUX_PKGS" ]]; then
	TERMUX_PKGS="$(cd "$REPO_DIR/.." && pwd)/termux-packages"
fi
if [[ ! -d "$TERMUX_PKGS/scripts" ]]; then
	log "浅克隆 termux-packages 到 $TERMUX_PKGS"
	git clone --depth 1 --filter=blob:none --sparse https://github.com/termux/termux-packages.git "$TERMUX_PKGS"
	( cd "$TERMUX_PKGS" && git sparse-checkout set scripts packages root-packages x11-packages ndk-patches sample )
fi
log "termux-packages: $TERMUX_PKGS"

# ---------------------------------------------------------------------------
# 2) 给 termux-packages 打宿主构建补丁（幂等；每处都带 [dsh-patch] 标记）
#    这些补丁只解决「在普通 Linux 宿主机上、用非 com.termux 包名构建」的问题，
#    不改变包的构建逻辑。
# ---------------------------------------------------------------------------
apply_host_patches() {
	local tp="$1"
	python3 - "$tp" <<'PY'
import io, os, re, sys
tp = sys.argv[1]

def patch(rel, pairs):
	path = os.path.join(tp, rel)
	s = io.open(path, encoding='utf-8').read()
	# 幂等：本脚本的补丁都带 [dsh-patch] 标记；文件里已有标记就跳过（可能上一次
	# 运行/手工修补过）。这样脚本可以重复执行，也不会与已有补丁打架。
	if '[dsh-patch]' in s:
		print('patch: skipped (already patched) ' + rel, file=sys.stderr)
		return
	changed = False
	for old, new in pairs:
		if new in s:
			continue
		if old not in s:
			raise SystemExit('补丁锚点缺失: %s :: %r' % (rel, old[:60]))
		s = s.replace(old, new, 1)
		changed = True
	if changed:
		io.open(path, 'w', encoding='utf-8').write(s)
		print('patch: ' + rel, file=sys.stderr)

# 2.1 宿主没有 /data：构建标记与 arch 标记改放 $TERMUX_TOPDIR
patch('scripts/build/termux_step_setup_variables.sh', [(
	'\telse\n\t\tTERMUX_BUILT_PACKAGES_DIRECTORY="/data/data/.built-packages"\n\tfi',
	'\telse\n'
	'\t\t# [dsh-patch] host build: /data does not exist; keep markers under TOPDIR\n'
	'\t\tTERMUX_BUILT_PACKAGES_DIRECTORY="${TERMUX_TOPDIR:?}/.built-packages"\n\tfi')])

patch('scripts/build/termux_step_handle_buildarch.sh', [(
	'\tlocal TERMUX_ARCH_FILE=/data/TERMUX_ARCH',
	'\t# [dsh-patch] host build: /data is not writable\n'
	'\tlocal TERMUX_ARCH_FILE="${TERMUX_TOPDIR:?}/TERMUX_ARCH"\n'
	'\tmkdir -p "$(dirname "$TERMUX_ARCH_FILE")"'),
	('\t\t\tif [ -d /data/data ]; then', '\t\t\tif [ -d /data/data ] && [ -w /data ]; then')])

# 2.2 包名与前缀目录可由环境覆盖；DSH 运行时用 <rootfs>/engine 而非 usr-merge
patch('scripts/properties.sh', [
	('TERMUX_APP__PACKAGE_NAME="com.termux"',
	 'TERMUX_APP__PACKAGE_NAME="${TERMUX_APP__PACKAGE_NAME:-com.termux}"  # [dsh-patch]'),
	('TERMUX__PREFIX_SUBDIR="usr"',
	 'TERMUX__PREFIX_SUBDIR="${TERMUX__PREFIX_SUBDIR:-usr}"  # [dsh-patch]'),
	('__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="true"',
	 '__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="${__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT:-false}"  # [dsh-patch] <rootfs>/engine 不是 usr-merge'),
])

# 2.3 宿主没有 fuse-overlayfs（apt 需要 root）：toolchain 直接复制，语义等价
patch('scripts/build/toolchain/termux_setup_toolchain_30.sh', [(
	'\tif ! mountpoint -q "${TERMUX_STANDALONE_TOOLCHAIN}"; then\n'
	'\t\tfuse-overlayfs \\\n'
	'\t\t\t"${TERMUX_STANDALONE_TOOLCHAIN}" \\\n'
	'\t\t\t-o lowerdir="${NDK}/toolchains/llvm/prebuilt/linux-x86_64" \\\n'
	'\t\t\t-o upperdir="${TERMUX_STANDALONE_TOOLCHAIN}-upper" \\\n'
	'\t\t\t-o workdir="${TERMUX_STANDALONE_TOOLCHAIN}-work"\n\tfi',
	'\tif ! mountpoint -q "${TERMUX_STANDALONE_TOOLCHAIN}"; then\n'
	'\t\t# [dsh-patch] no fuse-overlayfs on the host -> plain copy (same semantics)\n'
	'\t\tif command -v fuse-overlayfs >/dev/null 2>&1; then\n'
	'\t\t\tfuse-overlayfs \\\n'
	'\t\t\t\t"${TERMUX_STANDALONE_TOOLCHAIN}" \\\n'
	'\t\t\t\t-o lowerdir="${NDK}/toolchains/llvm/prebuilt/linux-x86_64" \\\n'
	'\t\t\t\t-o upperdir="${TERMUX_STANDALONE_TOOLCHAIN}-upper" \\\n'
	'\t\t\t\t-o workdir="${TERMUX_STANDALONE_TOOLCHAIN}-work"\n'
	'\t\telse\n'
	'\t\t\tcp -a "${NDK}/toolchains/llvm/prebuilt/linux-x86_64/." "${TERMUX_STANDALONE_TOOLCHAIN}/"\n'
	'\t\tfi\n\tfi')])

# 2.4 github.com/.../archive/*.tar.gz 常被 429；走 codeload 直链（字节一致）
patch('scripts/build/termux_download.sh', [(
	'\tlocal URL="$1"\n\tlocal DESTINATION="$2"',
	'\tlocal URL="$1"\n\tlocal DESTINATION="$2"\n'
	'\t# [dsh-patch] avoid github.com 429 for archive downloads\n'
	'\tif [[ "$URL" =~ ^https://github\\.com/([^/]+)/([^/]+)/archive/(.+)\\.tar\\.gz$ ]]; then\n'
	'\t\tURL="https://codeload.github.com/${BASH_REMATCH[1]}/${BASH_REMATCH[2]}/tar.gz/${BASH_REMATCH[3]}"\n'
	'\tfi')])

# 2.5 ncurses 的 terminfo 额外源里有 foot（codeberg），该站对本网络返回 429。
#     我们不需要 foot 的 terminfo，去掉它的源与安装项。
p = os.path.join(tp, 'packages/ncurses/build.sh')
s = io.open(p, encoding='utf-8').read()
if 'x11-packages/foot/build.sh' in s:
	# 同理幂等：只在还没去掉 foot 时处理
	for old, new in [
		('                    "$(. "$TERMUX_SCRIPTDIR/x11-packages/alacritty/build.sh"; echo "$TERMUX_PKG_VERSION")"\n'
		 '                    "$(. "$TERMUX_SCRIPTDIR/x11-packages/foot/build.sh"; echo "$TERMUX_PKG_VERSION")")\n',
		 '                    "$(. "$TERMUX_SCRIPTDIR/x11-packages/alacritty/build.sh"; echo "$TERMUX_PKG_VERSION")")\n'),
		('                   "$(. "$TERMUX_SCRIPTDIR/x11-packages/alacritty/build.sh"; echo "$TERMUX_PKG_SRCURL")"\n'
		 '                   "$(. "$TERMUX_SCRIPTDIR/x11-packages/foot/build.sh"; echo "$TERMUX_PKG_SRCURL")")\n',
		 '                   "$(. "$TERMUX_SCRIPTDIR/x11-packages/alacritty/build.sh"; echo "$TERMUX_PKG_SRCURL")")\n'),
		('                   "$(. "$TERMUX_SCRIPTDIR/x11-packages/alacritty/build.sh"; echo "$TERMUX_PKG_SHA256")"\n'
		 '                   "$(. "$TERMUX_SCRIPTDIR/x11-packages/foot/build.sh"; echo "$TERMUX_PKG_SHA256")")\n',
		 '                   "$(. "$TERMUX_SCRIPTDIR/x11-packages/alacritty/build.sh"; echo "$TERMUX_PKG_SHA256")")\n'),
		('\tcp "$TERMUX_PKG_TMPDIR"/full-terminfo/f/foot{,+base,-direct} "$TI/f/"\n', ''),
		("\t# Upstream instructions for building foot's terminfo\n"
		 '\t# See: https://codeberg.org/dnkl/foot/src/branch/master/INSTALL.md#terminfo\n'
		 "\tsed 's/@default_terminfo@/foot/g' \"$TERMUX_PKG_SRCDIR/foot/foot.info\" | \\\n"
		 '\ttic -x -e foot,foot-direct -o "$TI" -\n', ''),
	]:
		s = s.replace(old, new, 1)
	io.open(p, 'w', encoding='utf-8').write(s)
	print('patch: packages/ncurses/build.sh (drop foot terminfo)', file=sys.stderr)

# 2.6 bash：不依赖 termux-tools（会拉起 pkg/coreutils 等只为 Termux App 服务的包），
#     宿主没有 clang-18，用 gcc 做 build-time 工具（GCC 13 不触发已知 bool 问题）。
patch('packages/bash/build.sh', [
	('TERMUX_PKG_DEPENDS="libandroid-support, libiconv, readline (>= 8.3), termux-tools"',
	 '# [dsh-patch] runtime 自带 PATH/env，不需要 termux-tools 依赖链\n'
	 'TERMUX_PKG_DEPENDS="libandroid-support, libiconv, readline (>= 8.3)"'),
	('\texport CC_FOR_BUILD="clang-${TERMUX_HOST_LLVM_MAJOR_VERSION}"',
	 '\t# [dsh-patch] host has gcc only; GCC 13 does not trigger the bool issue\n'
	 '\texport CC_FOR_BUILD="gcc"'),
# 2.7 原生包管理器（proot + dpkg + 其依赖）在 NDK r30 / 非 com.termux 包名下的修补。
#     · dpkg：只留库依赖（coreutils/tar 等运行期工具单独装），禁用 dpkg-perl 等
#       会拉起 perl/clang 的子包，源码从 salsa 换到 GitHub 镜像（salsa 对本网络 429）
#     · proot：ashmem_memfd 扩展没 include <string.h>，新 clang 直接报错
#     · attr / libacl：bionic 没有 glibc 的 IFTODT 宏
#     · libandroid-shmem：NDK r30 的 <paths.h> 没有 _PATH_TMP
def ensure_file_patch(rel, block, marker):
	path = os.path.join(tp, rel)
	s = io.open(path, encoding='utf-8').read()
	if marker in s:
		print('patch: skipped (already patched) ' + rel, file=sys.stderr)
		return
	io.open(path, 'w', encoding='utf-8').write(s.rstrip() + '\n' + block)
	print('patch: ' + rel, file=sys.stderr)

ensure_file_patch('packages/dpkg/build.sh', """
# [dsh-patch] 原生包管理器：只保留构建/运行必需的库依赖。coreutils/tar/gzip/xz-utils
# 这些是维护脚本用的运行时工具（单独编译后直接并进运行时），它们会拉进
# autoconf/perl 一整条构建期依赖链，在交叉编译环境里没必要。
TERMUX_PKG_DEPENDS="libbz2, liblzma, libmd, zlib, zstd"
""", '[dsh-patch] 原生包管理器')
ensure_file_patch('packages/dpkg/build.sh', """
# [dsh-patch] salsa.debian.org 对本网络限流（HTTP 429），改用官方 GitHub 镜像
termux_step_post_get_source() {
	:
}
""", 'salsa.debian.org 对本网络限流')

# dpkg 的两处源码级替换（镜像 + 子包禁用）
path = os.path.join(tp, 'packages/dpkg/build.sh')
s = io.open(path, encoding='utf-8').read()
s = s.replace('TERMUX_PKG_SRCURL=git+https://salsa.debian.org/dpkg-team/dpkg.git',
              'TERMUX_PKG_SRCURL=git+https://github.com/guillemj/dpkg.git  # [dsh-patch] salsa 429 -> github mirror', 1)
io.open(path, 'w', encoding='utf-8').write(s)
import shutil
for sub in ('dpkg-perl', 'dpkg-scanpackages'):
	src = os.path.join(tp, 'packages/dpkg', sub + '.subpackage.sh')
	if os.path.exists(src):
		os.rename(src, src + '.disabled-by-dsh')

ensure_file_patch('packages/proot/build.sh', """
# [dsh-patch] NDK r30 clang 默认 -Werror=implicit-function-declaration，proot 的
# ashmem_memfd 扩展没 include <string.h>（老 NDK 靠传递包含蒙混过关）。只补
# src/extension（loader.c 自带 basename 定义，注入 string.h 会冲突）。
termux_step_post_get_source() {
	for f in $(find src/extension -name '*.c'); do
		sed -i '1i #include <string.h>' "$f"
	done
}
""", 'src/extension 没 include')

ensure_file_patch('packages/attr/build.sh', """
# [dsh-patch] bionic 没有 glibc 的 IFTODT 宏（libmisc/walk_tree.c 用到）
termux_step_post_get_source() {
	sed -i '1i #include <sys/stat.h>\n#ifndef IFTODT\n#define IFTODT(mode) (((mode) & 0170000) >> 12)\n#endif' libmisc/walk_tree.c
}
""", 'bionic 没有 glibc 的 IFTODT')

ensure_file_patch('packages/libacl/build.sh', """
# [dsh-patch] 同 attr：libacl 自带一份 walk_tree.c
termux_step_post_get_source() {
	sed -i '1i #include <sys/stat.h>\n#ifndef IFTODT\n#define IFTODT(mode) (((mode) & 0170000) >> 12)\n#endif' libmisc/walk_tree.c
}
""", 'libacl 自带一份 walk_tree.c')

ensure_file_patch('packages/libandroid-shmem/build.sh', """
# [dsh-patch] NDK r30 的 <paths.h> 不再提供 _PATH_TMP（Termux 自己的 NDK 版本有）
termux_step_post_get_source() {
	sed -i '1i #ifndef _PATH_TMP\n#define _PATH_TMP "/tmp/"\n#endif' shmem.c
}
""", '不再提供 _PATH_TMP')
PY
}
log "应用宿主构建补丁"
apply_host_patches "$TERMUX_PKGS"

# ---------------------------------------------------------------------------
# 3) bwrap 包装：宿主没有 /data，用私有 root + 可写 /data 跑构建
#    fake /data 里会真的“安装”依赖包（termux 的交叉编译依赖这一步来找 .so/头文件）。
# ---------------------------------------------------------------------------
WORK="$(mktemp -d "${TMPDIR:-/tmp}/dsh-runtime-build.XXXXXX")"
FAKE_DATA="$WORK/fake-data"
mkdir -p "$FAKE_DATA"
cleanup() { [[ "$KEEP_WORK" == "1" ]] || rm -rf "$WORK"; }
trap cleanup EXIT

cat > "$WORK/in-bwrap.sh" <<'WRAP'
#!/bin/bash
set -euo pipefail
FAKE="$1"; shift
exec bwrap --tmpfs / \
	--ro-bind /usr /usr --ro-bind /lib /lib --ro-bind /lib64 /lib64 \
	--ro-bind /bin /bin --ro-bind /sbin /sbin --ro-bind /etc /etc --ro-bind /opt /opt \
	--bind /home /home --bind /tmp /tmp \
	--bind "$FAKE" /data \
	--proc /proc --dev /dev --ro-bind /sys /sys \
	--ro-bind /run/systemd/resolve /run/systemd/resolve \
	"$@"
WRAP
chmod +x "$WORK/in-bwrap.sh"

# 依赖包会被“安装”进 /data/data/<pkg>/files/engine；base runtime 解出来的库
# 不在那里，所以每次构建前把 base 的 lib/include 铺一份，让后续包能链接。
seed_fake_prefix() {
	local base="$1" dst="$2"
	dst="$dst/data/data/$APP_PACKAGE/files/$PREFIX_SUBDIR"
	mkdir -p "$dst/lib" "$dst/include"
	( cd "$base" && cp -a lib/. "$dst/lib/" 2>/dev/null || true )
}

# ---------------------------------------------------------------------------
# 4) 逐架构构建 + 覆盖到运行时树
# ---------------------------------------------------------------------------
TMP_BASE="$WORK/base"
mkdir -p "$TMP_BASE"
unzip -q "$BASE_RUNTIME" -d "$TMP_BASE"
log "base runtime 已解开: $(du -sh "$TMP_BASE" | cut -f1)"

declare -a RESULTS=()
for abi in "${ABIS[@]}"; do
	case "$abi" in
		x86_64) tarch=x86_64 ;;
		arm64-v8a) tarch=aarch64 ;;
		*) echo "不支持的 --abi: $abi" >&2; exit 2 ;;
	esac
	log "=== 构建 $abi（termux arch=$tarch）==="

	# 每次构建都从干净的 fake /data 开始，再把 base 的 lib/include 铺进去
	rm -rf "${FAKE_DATA:?}/data"
	seed_fake_prefix "$TMP_BASE" "$FAKE_DATA"

	rm -f "${TMPDIR:-/tmp}/.termux-build.lck"
	# termux 的 .built-packages 标记只按包名记账、不区分架构；不清掉的话第二个
	# 架构会「跳过依赖构建」，而依赖并没有装进这次的 fake /data，链接必然失败。
	rm -rf "${HOME}/.termux-build/.built-packages"
	(
		cd "$TERMUX_PKGS"
		export TERMUX_APP__PACKAGE_NAME="$APP_PACKAGE"
		export TERMUX__PREFIX_SUBDIR="$PREFIX_SUBDIR"
		export TERMUX_PKG_API_LEVEL="$API_LEVEL"
		export TERMUX_NDK_VERSION_NUM
		export NDK="$NDK_DIR"
		export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
		export TERMUX_HOST_LLVM_MAJOR_VERSION="${TERMUX_HOST_LLVM_MAJOR_VERSION:-18}"
		# ⚠️ 2026-10-18 实测坑：bionic 的 faccessat() 不支持 AT_EACCESS（传了返回
		# EINVAL），而 bash 的 sh_eaccess() 在「HAVE_FACCESSAT && AT_EACCESS」成立时
		# 就只走 faccessat —— 结果**重编出来的 bash 里 test -x 对所有文件都为假**
		# （`[ -x /system/bin/sh ]` 也假）。这会静默打断所有 shell 脚本门禁
		# （linux 门脚本因此报 "proot missing"，尽管文件确实是 0777）。
		# 关掉 configure 对 faccessat 的探测，让它回落到 access(2) 分支（bionic 正常）。
		export ac_cv_func_faccessat=no
		# shellcheck disable=SC2086
		"$WORK/in-bwrap.sh" "$FAKE_DATA" bash -lc \
			"cd '$TERMUX_PKGS' && ./build-package.sh -a $tarch -f $PKGS" \
			2>&1 | tail -40
	) || { echo "构建失败（$abi）" >&2; exit 4; }

	# 把该架构的产物解出来覆盖到运行时树
	debs=("$TERMUX_PKGS"/output/*_"$tarch".deb)
	[[ -e "${debs[0]}" ]] || { echo "没有产物 deb（$tarch）" >&2; exit 4; }
	for d in "${debs[@]}"; do
		case "$(basename "$d")" in
			*-static_*) continue ;;   # 静态库不进运行时
		esac
		db="$WORK/deb.$$"
		rm -rf "$db"; mkdir -p "$db"
		dpkg-deb -x "$d" "$db"
		if [[ -d "$db/data/$APP_PACKAGE/files/$PREFIX_SUBDIR" ]]; then
			cp -a "$db/data/$APP_PACKAGE/files/$PREFIX_SUBDIR/." "$TMP_BASE/"
		fi
		rm -rf "$db"
	done

	# 契约校验：运行时里的 bash 不能再引用 com.termux
	if [[ -f "$TMP_BASE/bin/bash" ]] && strings "$TMP_BASE/bin/bash" | grep -q 'com\.termux'; then
		echo "校验失败：bin/bash 仍含 com.termux 路径" >&2; exit 5
	fi
	RESULTS+=("$abi: $(ls -la "$TMP_BASE/bin/bash" | awk '{print $5}') bytes bash, $(grep -o 'bashrc' <<<"$(strings "$TMP_BASE/bin/bash")" | head -1)")
done

# ---------------------------------------------------------------------------
# 5) 打包（**不要符号链接**：RuntimeInstaller 的 zip 契约是条目录化）
#    bwrap 里的 fake /data 里那些依赖安装文件不需要进包，只拿 base+覆盖后的树。
# ---------------------------------------------------------------------------
log "打包 -> $OUT"
rm -f "$OUT"
# Termux 的 deb 里大量符号链接（bin/sh -> bash、lib*.so -> lib*.so.X）。zip 会把
# 它们存成链接条目，而 RuntimeInstaller 一律按普通文件写 → 装完 bin/sh 变成
# 0 字节垃圾（dpkg 维护脚本全挂）。拆包前先解引用成真文件。
python3 - "$TMP_BASE" <<'PY'
import os, shutil, sys
root = sys.argv[1]
realroot = os.path.realpath(root)
links = copied = dropped = 0
for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
	for name in dirnames + filenames:
		p = os.path.join(dirpath, name)
		if not os.path.islink(p):
			continue
		links += 1
		try:
			target = os.path.realpath(p)
			if not target.startswith(realroot + os.sep):
				os.unlink(p); dropped += 1; continue
			if os.path.isdir(target):
				os.unlink(p); shutil.copytree(target, p, symlinks=False)
			else:
				mode = os.stat(target).st_mode & 0o777
				os.unlink(p); shutil.copyfile(target, p); os.chmod(p, mode); copied += 1
		except Exception:
			try: os.unlink(p)
			except Exception: pass
			dropped += 1
print(f'[build-runtime] materialised symlinks: {links} (files {copied}, dropped {dropped})', file=sys.stderr)
PY
( cd "$TMP_BASE" && zip -q -r "$OUT" . )

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT")"
log "完成: $OUT"
log "  size  : $SIZE bytes ($((SIZE/1024/1024)) MB)"
log "  sha256: $SHA"
for r in "${RESULTS[@]}"; do log "  $r"; done
log "提示：把 zip 放到 app/src/main/assets/runtime.zip，并把 runtime/MANIFEST.json 的"
log "      version 改成一个**新值**、sha256 改成上面的值（prepareRuntime 会按 .abi 覆盖）。"
