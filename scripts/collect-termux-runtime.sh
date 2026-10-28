#!/usr/bin/env bash
# collect-termux-runtime.sh —— 在 x86_64 Linux（如 GitHub Actions ubuntu runner）上
# 从 Termux 官方 apt 仓库收集 aarch64 Node.js 运行时闭包，产出可被 Android 端
# RuntimeInstaller 解压的 runtime.zip。
#
# 用法: ./collect-termux-runtime.sh <输出zip路径> [架构]（默认 aarch64）
#
# 设计说明：
# - 选择 Termux 仓库而非上游官方 Node 二进制：官方 linux-arm64 是 glibc 链接，
#   在 bionic 上无法直接运行；Termux 的 node 为 bionic 交叉编译，开箱即用。
# - 许可证：node (MIT) + 各依赖库（MIT/BSD/ISC/Zlib），允许再分发；
#   刻意不打包任何 GPL 工具链组件。
# - ⚠️ PKGS 清单基于 Termux 主仓库当前已知包名编写，仓库调整时按报错修正。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_ZIP="${1:?用法: $0 <输出zip路径> [架构]}"
ARCH="${2:-aarch64}"
case "$ARCH" in aarch64|x86_64) ;; *) echo "不支持的架构: $ARCH" >&2; exit 1;; esac
# 提前转为绝对路径：后面子 shell 会 cd 进 WORK，相对路径会写错位置
OUT_ZIP="$(realpath -m "$OUT_ZIP")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TERMUX_REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
# 国内构建可换源：TERMUX_REPO=https://mirrors.ustc.edu.cn/termux/apt/termux-main
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
ROOT="$WORK/root"

mkdir -p "$ROOT" "$WORK/lists/partial" "$WORK/cache/archives/partial"

# ---- 1. 配置 apt 源（仅 arm64 架构；trusted 免签仅限 CI 受控环境）----
cat > "$WORK/sources.list" <<EOF
deb [arch=${ARCH} trusted=yes] ${TERMUX_REPO} stable main
EOF
cat > "$WORK/apt.conf" <<EOF
Dir::Etc::sourcelist "${WORK}/sources.list";
Dir::State::lists "${WORK}/lists";
Dir::Cache "${WORK}/cache";
APT::Architecture "${ARCH}";
APT::Architectures {"${ARCH}"};
Acquire::AllowInsecureRepositories "true";
APT::Get::AllowUnauthenticated "true";
Acquire::Languages "none";
EOF

# 隔离宿主 apt 配置：否则会读 /etc/apt/apt.conf.d 与 host 的 sources.list.d，
# 去抓 Ubuntu 主仓库索引（几十秒到几分钟的无谓下载，甚至卡住）。
APT_ISO=(-c "$WORK/apt.conf" -o Dir::Etc::Parts=/dev/null -o Dir::Etc::sourceparts=-)
apt-get "${APT_ISO[@]}" update

# ---- 2. 下载运行时依赖闭包 ----
# 包名已对照 termux-main binary-aarch64 Packages 索引逐个核实：
#   nodejs-lts 24.x Depends = libc++, openssl, c-ares, libicu, libsqlite, zlib
# bash 的依赖不能依赖 apt-get download 自动递归：bash -> readline -> ncurses，
# 另有 libiconv/termux-tools；ripgrep -> pcre2，全部显式列出以形成可审计闭包。
PKGS=(
  nodejs-lts          # node 本体（含 npm）
  bash readline ncurses libiconv termux-tools  # bash 执行闭包
  ripgrep pcre2       # Android/bionic 原生 rg 及其正则库
  openssl c-ares libicu libsqlite zlib libc++   # nodejs-lts 硬依赖闭包
  libuv brotli        # 静态链接兜底
  libandroid-support  # bionic 兼容层辅助
  ca-certificates     # HTTPS 根证书（dsh 调模型 API 必需）
)

# ---- 3. 解包合并 ----
# 注意：apt-get download 把 .deb 下到【当前目录】而非 Dir::Cache，
# 因此先 cd 进 WORK 再下载。
cd "$WORK"
apt-get "${APT_ISO[@]}" download "${PKGS[@]}"

shopt -s nullglob
DEBS=("$WORK"/*.deb)
if [ ${#DEBS[@]} -eq 0 ]; then
  echo "错误：未下载到任何 .deb，请检查 PKGS 清单与 Termux 仓库可达性" >&2
  exit 1
fi
for deb in "${DEBS[@]}"; do
  dpkg-deb -x "$deb" "$ROOT"
done

# Termux deb 按【绝对路径】打包：文件位于 data/data/com.termux/files/usr/…
# （而不是 usr/）。定位真实 usr 后整体平铺为 zip 根 —— 与 Android 端
# EngineConfig 的 PATH(bin)/LD_LIBRARY_PATH(lib)/dshEntry(lib/node_modules)
# 以及 Termux 惯例 $PREFIX/etc/tls/cert.pem 证书路径完全对齐。
USR_DIR="$(find "$ROOT" -type d -path '*com.termux/files/usr' | head -n1)"
if [ -z "$USR_DIR" ]; then
  echo "错误：解包后未找到 com.termux/files/usr，Termux 打包布局可能已变更" >&2
  find "$ROOT" -maxdepth 6 -type d >&2 || true
  exit 1
fi
cp -a "$USR_DIR/." "$ROOT/"
rm -rf "$ROOT/data"

# ---- 3.5 集成 dsh 引擎（平台无关 JS 依赖闭包）----
# 用 runner 自带 npm 在 x64 环境解析整棵依赖树（JS 文件与运行架构无关）；
# --ignore-scripts 禁掉 postinstall，防Linux-x64 native 构建/二进制混入。
# 若未来引入真 native 依赖（如 better-sqlite3），需针对 bionic 十字编译，
# 到时按报错在此处做平台裁剪或替换实现。
# 锁定版本。浮动 latest 曾撞上上游重构（0.1.5-rc.1 时 session-persistence-jsonl
# 从依赖树移除 → 补丁断言失败 → CI 全挂）。
# v1.2.28 升级到 0.2.0-rc.2：已逐包核验补丁目标——session-persistence 的
# fs/promises import 多了 lstat；sandbox-local 的 landlock import 已被上游移除
# （补丁正则自然 no-op）；fs-local 的 linkFile 调用、web-frontend 的 polyfill
# 锚点均未变；koffi/node-pty 改为存在才打桩。
DSH_VERSION="${DSH_VERSION:-0.2.0-rc.2}"
mkdir -p "$ROOT/lib/node_modules"
if [ -n "${DSH_SOURCE_DIR:-}" ]; then
  # 从上游 GitHub 源码构建的闭包（不访问 npm 的 @deepseek-ai/dsh 发布包）。
  # 用法：
  #   git clone --depth 1 -b dsh-v<ver> https://github.com/deepseek-ai/deepseek-harness
  #   pnpm install && pnpm build:official
  #   apps/runtime-bundle 的依赖改成 workspace:* 后
  #     pnpm --filter dsh-runtime-bundle deploy --prod --legacy \
  #          --node-linker=hoisted --config.allowUnusedPatches=true <out>
  #   DSH_SOURCE_DIR=<out>/node_modules … collect-termux-runtime.sh
  # 这样后面 3.6 的 bionic 补丁照常打在源码构建产物上。
  SRC="$(realpath "$DSH_SOURCE_DIR")"
  [ -d "$SRC" ] || { echo "DSH_SOURCE_DIR 不存在: $SRC" >&2; exit 2; }
  [ -d "$SRC/@deepseek-ai/dsh" ] || { echo "DSH_SOURCE_DIR 里没有 @deepseek-ai/dsh（要先 deploy 出闭包）" >&2; exit 2; }
  echo "使用本地源码构建的闭包: $SRC ($(du -sh "$SRC" | cut -f1))"
  cp -a "$SRC/." "$ROOT/lib/node_modules/"
else
  mkdir -p "$WORK/bundle" && cd "$WORK/bundle"
  printf '{"name":"dsh-runtime","private":true,"dependencies":{"@deepseek-ai/dsh":"%s"}}' \
    "$DSH_VERSION" > package.json
  # dsh 依赖闭包巨大，npm 理想树解析默认堆会 OOM（SIGABRT/134），放开到 5.5GB
  export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=5632"
  npm install --registry "$NPM_REGISTRY" --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error
  cp -a "$WORK/bundle/node_modules/." "$ROOT/lib/node_modules/"
fi

# 体积修剪：README/TS 类型/sourcemap 可安全删除；LICENSE 一律保留（分发合规）
find "$ROOT/lib/node_modules" \( -name '*.md' -o -name '*.map' \) -not -iname 'LICENSE*' -delete 2>/dev/null || true
find "$ROOT/lib/node_modules" -type f -name '*.d.ts' -delete 2>/dev/null || true

# ---- 3.6 Android (bionic) 兼容补丁 —— 唯一允许触碰上游的位置，逐条注明理由 ----
# ---- 3.6 Android (bionic) 兼容补丁（实现见 scripts/patch-engine-closure.sh）----
# 与自建引擎层共用同一份补丁：
#   ROOT=<引擎根> ARCH=<arch> bash scripts/patch-engine-closure.sh
# 之所以抽出去：PC 端「只换闭包」重新打包时也要打同一套补丁，不能两处各写一份。
ROOT="$ROOT" ARCH="$ARCH" bash "$SCRIPT_DIR/patch-engine-closure.sh"

# ---- 4. 精简：剔除文档/头文件/npm 冗余，控制体积 ----
rm -rf "$ROOT/share/man" "$ROOT/share/doc" "$ROOT/include" \
       "$ROOT/var/cache" "$ROOT/var/log" \
       "$ROOT/lib/node_modules/npm/docs" \
       "$ROOT/lib/node_modules/npm/man" \
       "$ROOT/lib/node_modules/npm/html" 2>/dev/null || true
find "$ROOT" \( -name "*.a" -o -name "*.map" \) -delete 2>/dev/null || true

# 平台专用二进制清理：CI 跑在 Ubuntu x86_64，npm 会按【该平台】安装
# optionalDependencies，于是包里混进了 linux-x64 的 .node/.so ——
# 这些是 ELF x86-64，Android arm64 上根本无法 dlopen，属纯浪费（~50MB）。
# sharp 在 Android 走同 scope 下的 sharp-wasm32 回退（已保留），故删除安全；
# sherpa-onnx 属实验性语音输入，移动端本就不加载。
rm -rf "$ROOT/lib/node_modules/sherpa-onnx-linux-x64" \
       "$ROOT/lib/node_modules/@img/sharp-linux-x64" \
       "$ROOT/lib/node_modules/@img/sharp-libvips-linux-x64" \
       "$ROOT/lib/node_modules/node-addon-require-builtin-linux-x64-gnu" 2>/dev/null || true

# ---- 4.5 依赖审计门（每次编译必跑；FAIL 直接中断，避免发出「read_image 坏」这类包）----
if [ -x "$SCRIPT_DIR/check-engine-deps.sh" ]; then
  echo "[deps] 依赖审计：$ROOT/lib/node_modules"
  "$SCRIPT_DIR/check-engine-deps.sh" --root "$ROOT/lib/node_modules" || {
    echo "错误：依赖审计未通过，停止打包（豁免见 scripts/check-engine-deps.allow）" >&2; exit 1; }
fi

# ---- 5. 打 zip ----
( cd "$ROOT" && zip -qr "$OUT_ZIP" . )

echo "runtime.zip 已生成: $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1))"
echo "SHA-256: $(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
