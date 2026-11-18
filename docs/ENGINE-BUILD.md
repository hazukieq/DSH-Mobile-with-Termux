# 引擎层构建：DSH 引擎闭包 / Android 独有库 / Termux 运行时

App 里的「引擎层」是两块拼起来的：

| 层 | 内容 | 位置（设备上） | 什么时候要动 |
| --- | --- | --- | --- |
| **Termux 运行时** | `node` / `bash` / `rg` + 14 个 bionic `.so` + `etc/tls` | `$PREFIX/{bin,lib,etc}` | 换 node 版本、加原生库、换 Termux 包 |
| **JS 闭包** | `lib/node_modules/**`（dsh 及其依赖） | `$PREFIX/lib/node_modules` | 每次升级 dsh |

`dsh-update` 交换包内提供的 `lib/node_modules`、版本标记，以及可选的
`lib/dsh-engine/lib`、`bin/node`、`bin/rg`；其他 Termux 文件不在交换范围。
日常升级可复用已有运行时。这也决定了本篇的顺序：
**先看能不能只换闭包（§1），不行再重收运行时（§3）**；两者都要打同一套 Android 补丁（§1.3）。


## 0.6 编译必过依赖审计（full-check-deps）

每次重打引擎层 / runtime，都必须跑一次依赖审计——它会检查「声明了但没装」的依赖、
Android 上装不起来的原生模块（含 sharp addon 的 RUNPATH/架构/NEEDED）、以及
sharp 的静态后端布局；只有加 `--runtime` 才会实际加载、编解码。历史上 `read_image` 坏掉、`node-pty` 装不上、
架构不符的 `.node` 混进包，都是这一步能提前抓到的。

三个构建脚本已经内置这道静态门禁（FAIL 中断打包；通过不代表真机功能全部正常）：

- `scripts/collect-termux-runtime.sh`（步骤 4.5）
- `scripts/build-engine-layer.sh`（步骤 4.5；同时用 `VIPS_LIBS_DIR` 自动捆绑 libvips）
- `scripts/build-engine-closure.sh`（步骤 4.4）

PC 上手跑（静态检查，不需要设备）：

```bash
# 检查「发布形态闭包」或已解包的引擎树
scripts/check-engine-deps.sh --root /tmp/consumer/node_modules
scripts/check-engine-deps.sh --root /tmp/engine/engine-tree/lib/node_modules --strict
```

设备上在 App 普通模式终端运行探针（真实 dlopen + sharp 编解码，仍需另测 read_image 工具链）：

```bash
# 将本仓库 scripts/ 放到 App 可读目录后，在 App 终端执行；不使用 su。
node ~/dshm/scripts/full-check-deps.mjs --root "$PREFIX/lib/node_modules" --prefix "$PREFIX"
```

构建脚本在主机或手机上均运行静态门禁；真机运行时探针与实际工具调用是额外验收。

豁免：只允许「已确认是宿主专属、Android 永不加载」的**声明缺失**，逐条写在
`scripts/check-engine-deps.allow` 并附原因（格式 `包名<TAB>依赖名<TAB>原因`）；
架构不符 / 加载失败一律不给豁免，必须从闭包里删掉或换掉。审计脚本本身用
`LC_ALL=C` 调用 `readelf`，否则中文 locale 下会把 ELF 架构读成 `?`。

## 1. 只换闭包（PC 上编译 dsh 引擎层）

升级 dsh 引擎**不需要重编 Termux 运行时**。引擎层分两块：

| 块 | 内容 | 变不变 |
| --- | --- | --- |
| 运行时骨架 | `bin/node`、`bin/bash`、`bin/rg`、`lib/*.so`（14 个，含 SONAME 精确名） | 稳定，直接复用 APK 里那份 |
| JS 闭包 | `lib/node_modules/**`（`@deepseek-ai/*` 等） | 每次上游升级才换 |

所以 PC 端一条链就够：**上游打包脚本产闭包 → 打 Android 补丁 → 自检 → 打 tar → stage/apply**。

### 1.1 前置

- Node 24、与所选源码兼容的 pnpm、独立可用的 npm CLI、Python 3、Bash、tar、unzip。
- 显式选 registry，例如 `https://registry.npmjs.org`；按网络情况换镜像并核对包来源。
- 下面主机示例用 `/tmp`，手机必须使用 `$PREFIX/tmp`（见 §1.7）。
- 原生 addon 的构建与验证另见 §2；JS 闭包打包成功不证明原生功能可用。

### 1.2 从 GitHub 源码构建

选定上游 tag 后构建并执行上游发布打包脚本；不要把源码树的 `node_modules` 当引擎闭包。
以下 `dsh-v0.2.2-rc.4` 是版本示例，升级其他版本时需重新核对工具版本和补丁目标。

```sh
git clone --depth 1 -b dsh-v0.2.2-rc.4 https://github.com/deepseek-ai/deepseek-harness /tmp/dhsrc
cd /tmp/dhsrc
pnpm install --frozen-lockfile --ignore-scripts --registry=https://registry.npmjs.org
pnpm build:official
pnpm exec tsx scripts/release/pack.ts --family dsh --out /tmp/npmpack
```

`--ignore-scripts` 跳过 koffi 等依赖的安装期原生构建；若所选版本需要额外构建步骤，
应单独审阅执行。手机上的 pnpm 版本切换和 registry 配置见 §1.7。

### 1.3 用本地 tarball 装出发布形态闭包

上游 npm registry 可能没有同步发布全部同版本子包；本流程统一使用 `pack.ts` 的本地 tarball。
包数量随 tag 变化，不以固定数量作为完整性证明。consumer 的包名来自 tar 内的
`package/package.json`，全部根依赖指向绝对 `file:` 路径；平台筛选按构建主机 OS/CPU
及包的 `os`、`cpu` 声明处理。非本机包放入 `optionalDependencies`，安装时省略。

在本仓库执行：

```sh
python3 scripts/prepare-engine-consumer.py --pack /tmp/npmpack --out /tmp/release-consumer
cd /tmp/release-consumer
npm install --ignore-scripts --no-audit --no-fund --package-lock=false \
  --omit=optional --registry=https://registry.npmjs.org
```

使用全新的 consumer 目录，不复用旧 `node_modules`。后续 Android 补丁负责平台适配，
依赖审计检查实际依赖解析；不把 `pnpm deploy` 或直接复制 monorepo 作为替代。

### 1.4 换闭包、打补丁和打包

在本仓库执行：

```sh
bash scripts/build-engine-closure.sh \
  --engine app/src/main/assets/engine-dsh-aarch64.zip \
  --closure /tmp/release-consumer/node_modules \
  --version 0.2.2-rc.4+local1 \
  --out /tmp/engine-dsh-aarch64-closure.tar.gz
```

脚本复用骨架、替换闭包、打 Android 补丁、检查 cosmokit / cordis、运行依赖审计并写版本标记。
产物包含 `lib/node_modules`、`.dsh-engine-version`，骨架存在原生库时也包含 `lib/dsh-engine/lib`。
缺失依赖审计脚本或审计失败会中断；`--skip-patch` 仅适用于已适配的闭包与隔离测试。
补丁目标发生变化时需要审阅日志和实际产物，不能把 WARN 当作适配成功。

可选加 `--download-base https://<host>/<release-directory>`，生成 `<out>.manifest.json`。
其中 URL 指向 tar 的文件名，含 ABI、版本、SHA-256、大小；上传 tar 和清单之后才可使用 feed。
生成清单不会上传或发布任何内容。

### 1.4.1 sharp 的 wasm 回退（不打这层，`read_image` 在 Android 上不可用）

dsh 的图片归一化（`read_image` / 附件预处理）用 sharp。sharp 的**预编译平台列表没有
android**（只有 darwin / linux / linuxmusl / win32 / freebsd / wasm32），而 `@img/sharp-linux-arm64`
是 glibc 产物（`NEEDED libc.so.6` / `ld-linux-aarch64.so.1`），bionic 加载不了。结果：

```
Error: Could not load the "sharp" module using the android-arm64 runtime
```

`patch-engine-closure.sh` 的第 3.10 步会自动调 `scripts/patch-sharp-wasm.sh` 补上回退，
**无需手工介入**；也可单独跑：

```sh
NM=/tmp/eng/lib/node_modules bash scripts/patch-sharp-wasm.sh
NM=/tmp/eng/lib/node_modules DRY=1 bash scripts/patch-sharp-wasm.sh   # 只报告
```

要点（踩过的坑）：

- 装 `@img/sharp-wasm32@<sharp 版本>` + `@emnapi/runtime@^1.11.3`，体积约 **9.4 MB**，零编译、跨设备可用；
- **必须双落位**：`<NM>/@img/sharp-wasm32` 与 `<NM>/sharp/node_modules/@img/sharp-wasm32`
  （两边都要 `@emnapi/runtime`）——只放顶层时活跃引擎进程仍报同样的错；
- 包的入口是 `exports["./sharp.node"] → ./index.cjs`，真正的 wasm 在 `lib/*.node.wasm`（≈9.0 MB），
  自检别去找 `sharp.node` 实体文件；
- 宿主侧可验证 payload 完整性：把 `sharp` + 这两个包放进临时 `node_modules` 后
  `node -e "require('sharp').versions"` → `sharp 0.35.5 / vips 8.18.7`（宿主机没有平台二进制时
  正好走 wasm 分支）；
- **原生 addon（可选，更快）**：`scripts/build-sharp-native.sh` 在设备上用 clang 直编（不跑
  node-gyp：没有 npm 后端时按 §1.7 配置 `DSH_NPM_CLI`；Android 临时目录用 `$PREFIX/tmp`）。
  产物约 **340 KB**，放到 `<NM>/sharp/src/build/Release/sharp-android-arm64-<ver>.node` 即 sharp
  加载器的首选路径。实测 103–127 ms（wasm 202–254 ms）。
  ⚠️ 它动态依赖 Termux 的 `libvips`（`RUNPATH=$PREFIX/lib`）；没有 libvips 的设备 dlopen 失败后
  sharp 会自动回落 wasm —— 所以**原生 + wasm 要一起打**，任何设备都能用。
- 原生 addon 还需要注意（`src/build/Release/sharp-android-arm64-0.35.5.node`）更快（103–127 ms vs wasm
  202–254 ms），但依赖 Termux 的 libvips；要做也得**和 wasm 一起打**，否则设备缺 libvips 时没回退。

### 1.4.2 在 PC 上交叉编译 sharp 原生 addon（推荐，几秒出包）

手机编要装 clang + libvips（200 MB+）跑十分钟；**PC 用 NDK 交叉编译只要几秒**，产物等价
（同为 aarch64 bionic ELF）。原理：addon 只依赖 node 头文件 + libvips 头/库，把 Termux 的
libvips sysroot 当 `-I/-L` 交给 NDK clang 即可，不需要 gyp/cmake。

```bash
# ① 设备上打包 libvips sysroot（装了 libvips 的 Termux；也可以只取 deb 解包）
cd $PREFIX && tar czf /sdcard/Download/vips-sysroot.tar.gz \
  include/vips include/glib-2.0 include/gio include/gobject lib/glib-2.0/include \
  lib/libvips*.so* lib/libglib-2.0.so* lib/libgobject-2.0.so* lib/libgio-2.0.so* \
  lib/libgmodule-2.0.so* lib/libexpat.so* lib/libjpeg.so* lib/libpng*.so* lib/libtiff.so* \
  lib/libwebp.so* lib/libheif.so* lib/liblcms2.so* lib/libxml2.so* lib/libpcre2-8.so* \
  lib/libffi.so* lib/libintl.so* lib/libz.so*
adb pull /sdcard/Download/vips-sysroot.tar.gz && mkdir -p /tmp/vipsroot && tar xzf vips-sysroot.tar.gz -C /tmp/vipsroot

# ② PC 上交叉编译（NDK 自动探测；产物 337 KB）
scripts/build-sharp-native-pc.sh --sysroot /tmp/vipsroot \
  --closure /tmp/consumer/node_modules --out /tmp/sharp-android-arm64-0.35.5.node

# ③ 把 addon 放进闭包，再把 libvips 依赖闭包放进引擎层
cp /tmp/sharp-android-arm64-0.35.5.node \
   /tmp/consumer/node_modules/sharp/src/build/Release/sharp-android-arm64-0.35.5.node
scripts/pack-engine-vips.sh --libs /tmp/vipsroot/lib --tree /tmp/engine/engine-tree
#   ④ 重打引擎层 zip/tar（见 §3），APK 资产换成新的 zip
```

实测（2026-10-29）：`libvips` 运行时闭包 **69 个 .so / 39.2 MB**；APK 215 MB 引擎层 + 闭包 =
**322,026,536 B**；设备上把全局 `$PREFIX/lib/libvips*` 移走后仍能加载，
`/proc/self/maps` 显示 vips 来自 `<PREFIX>/lib/dsh-engine/lib/libvips-cpp.so.42`（原生优先，wasm 作兜底）。

> ⚠️ 引擎层只在**首装**时解包（`BootstrapInstaller.ensureDshEngine` 判据是「dsh 入口在 + stamp 在」），
> 老用户升级 APK 不会自动换 lib/；要么走 `dsh-update`（需把 `lib/*.so*` 一起搬），要么让用户重装。

### 1.5 装机与回滚

```sh
adb push /tmp/engine-dsh-aarch64-closure.tar.gz /sdcard/Download/
# 然后在 App 内终端（或已 root/adb shell 进 termux 前缀）执行：
sha256sum /sdcard/Download/engine-dsh-aarch64-closure.tar.gz
dsh-update stage /sdcard/Download/engine-dsh-aarch64-closure.tar.gz <sha256>
dsh-update apply      # 交换 + 重启引擎
dsh-update status     # engine / staged / rollback
dsh-update rollback   # 出问题回上一个引擎
```

`apply` 只在健康检查通过后保留新引擎，否则自动回滚（旧引擎 401 冒充健康的问题已修：
先等旧 PID 消失再探活）。

### 1.6 自检清单（缺一条别上机）

1. `ls node_modules/@deepseek-ai | grep -c cosmokit` > 0
2. `grep -c 'workspace:' node_modules/@deepseek-ai/cordis/package.json` == 0
3. `node -e "require('./node_modules/@deepseek-ai/cordis/lib/index.js')"` 不报错
4. `node node_modules/@deepseek-ai/dsh/lib/bin.js --version` 等于目标版本
5. tar 里存在 `lib/node_modules/@deepseek-ai/dsh/lib/bin.js`
6. `ls node_modules/@img/sharp-wasm32 node_modules/sharp/node_modules/@img/sharp-wasm32`
   两处都在（否则 Android 上 `read_image` 报 "Could not load the sharp module"）

---

### 1.7 手机端自建（App 普通模式终端）

不使用 Root。需要联网下载源码和依赖，并为源码、pnpm store、consumer、临时树与回滚包
预留空间；耗时和内存占用取决于版本与设备，不保证固定分钟数或固定存储量。
不要安装 Termux `nodejs` / `pnpm` 来覆盖 App 运行时。以下 npm、pnpm 均解到独立目录，
由现有 Node 显式运行，不依赖 `/usr/bin/env` 或 corepack。

```bash
export TMPDIR="$PREFIX/tmp"
mkdir -p "$TMPDIR"
pkg install -y git tar zip unzip python
export REGISTRY=https://registry.npmjs.org
export BUILD_HOME="$HOME/dsh-engine-build-rc4"
mkdir -p "$BUILD_HOME/tools/npm" "$BUILD_HOME/tools/pnpm"

curl -fL "$REGISTRY/npm/-/npm-11.6.2.tgz" -o "$BUILD_HOME/tools/npm.tgz"
tar -xzf "$BUILD_HOME/tools/npm.tgz" -C "$BUILD_HOME/tools/npm" --strip-components=1
curl -fL "$REGISTRY/pnpm/-/pnpm-11.28.5.tgz" -o "$BUILD_HOME/tools/pnpm.tgz"
tar -xzf "$BUILD_HOME/tools/pnpm.tgz" -C "$BUILD_HOME/tools/pnpm" --strip-components=1
export DSH_NPM_CLI="$BUILD_HOME/tools/npm/bin/npm-cli.js"
export DSH_PNPM_CLI="$BUILD_HOME/tools/pnpm/bin/pnpm.mjs"
node "$DSH_NPM_CLI" --version
node "$DSH_PNPM_CLI" --version

# 本仓库提供补丁与 consumer 生成器；上游仓库提供源码与 pack.ts。
git clone --depth 1 --branch dev --filter=blob:none --sparse \
  https://github.com/hazukieq/DSH-Mobile-with-Termux.git "$BUILD_HOME/dshm"
git -C "$BUILD_HOME/dshm" sparse-checkout set scripts
git clone --depth 1 --branch dsh-v0.2.2-rc.4 \
  https://github.com/deepseek-ai/deepseek-harness.git "$BUILD_HOME/upstream"
cd "$BUILD_HOME/upstream"

# pnpm 11.28.5 使用 pmOnFail；旧 managePackageManagerVersions 配置已移除。
# 显式禁用自动切换，避免下载另一份不适用 Android 的 pnpm 可执行文件。
export PNPM_CONFIG_PM_ON_FAIL=ignore
export PNPM_CONFIG_REGISTRY="$REGISTRY"
# 上游构建脚本若再调用 pnpm，也使用同一份 JS CLI；兼容尚未更新的 App wrapper。
mkdir -p "$BUILD_HOME/tools/bin"
cat > "$BUILD_HOME/tools/bin/pnpm" <<'SH'
#!/system/bin/sh
exec "$PREFIX/bin/node" "$DSH_PNPM_CLI" --pm-on-fail=ignore --config.registry="$REGISTRY" "$@"
SH
chmod 755 "$BUILD_HOME/tools/bin/pnpm"
export PATH="$BUILD_HOME/tools/bin:$PATH"
pnpm_build() {
  node "$DSH_PNPM_CLI" --pm-on-fail=ignore --config.registry="$REGISTRY" "$@"
}
pnpm_build config get registry
pnpm_build install --frozen-lockfile --ignore-scripts
pnpm_build build:official
pnpm_build exec tsx scripts/release/pack.ts --family dsh --out "$BUILD_HOME/pack"

python3 "$BUILD_HOME/dshm/scripts/prepare-engine-consumer.py" \
  --pack "$BUILD_HOME/pack" --out "$BUILD_HOME/consumer" --os linux --cpu arm64
cd "$BUILD_HOME/consumer"
node "$DSH_NPM_CLI" install --ignore-scripts --no-audit --no-fund \
  --package-lock=false --omit=optional --registry="$REGISTRY"

# 只复制复用骨架必需的文件；不要把整个 $PREFIX（含 tmp）复制到自己的临时子目录。
mkdir -p "$BUILD_HOME/skeleton/bin" "$BUILD_HOME/skeleton/lib/dsh-engine"
cp -a "$PREFIX/bin/bash" "$BUILD_HOME/skeleton/bin/bash"
cp -a "$PREFIX/lib/dsh-engine/rg-bin" "$BUILD_HOME/skeleton/bin/rg"
cp -a "$PREFIX/lib/dsh-engine/lib" "$BUILD_HOME/skeleton/lib/dsh-engine/"
bash "$BUILD_HOME/dshm/scripts/build-engine-closure.sh" \
  --engine "$BUILD_HOME/skeleton" --closure "$BUILD_HOME/consumer/node_modules" \
  --arch aarch64 --version 0.2.2-rc.4+local1 --out "$BUILD_HOME/engine-rc4.tar.gz"
sha256sum "$BUILD_HOME/engine-rc4.tar.gz"
```

示例针对 arm64 设备，其他架构需调整 consumer CPU 和构建 ABI。堆内存不足时再按设备余量
设置 `NODE_OPTIONS`，不要把固定大堆配置当作保证。上游 tag 的工具链要求变化时先核对版本。
两个工具包的入口和 Node 要求可查对应的 npm registry 元数据；上述 pnpm 入口为 `.mjs`。

核对 tar 内容和 SHA-256 后，再手动 `dsh-update stage <path> <sha256>`、`dsh-update apply`。
重启会中断当前引擎会话，保留构建日志与旧引擎回滚点。验收包括冷启动、工具调用、图片附件、
实际 `read_image`、终端与回滚；仅 `require('sharp')` 成功不能证明图片工具端到端可用。

### 1.8 热更新 feed 与 APK 溯源清单

默认 feed 地址属于 `hazukieq/DSH-Mobile-with-Termux` 的 `dsh-engine-latest` 滚动 Release，
但地址存在于代码不代表已经发布。`check` 只接受含当前 ABI tar URL 和 SHA-256 的 feed，
并在候选 Release 中跳过 APK 溯源清单。v3.0 APK 附带的 `engine-manifest.json` 描述 APK 与载荷，
没有引擎 tar 下载地址，不能作为 feed。没有可用 feed 时会报告 unavailable / unknown。

- 临时指定：`dsh-update check --manifest <URL或本地路径>`。
- 环境配置：`DSH_ENGINE_MANIFEST=<URL或路径> dsh-update pull`。
- 参数优先于环境变量；显式来源出错时不切到其他 feed。
- `pull <version>` 校验所请求版本与 feed 的 dsh 或引擎版本一致；不查询历史版本目录。
- 版本不同仅表示有另一个包可用，不推断版本升降顺序。
- `pull` 下载并校验 SHA-256 后暂存；`--apply` 才会切换运行中的引擎。

标准清单由 `scripts/make-engine-feed.py` 生成，结构为
`{kind: "dsh-engine-feed", schema_version: 1, dsh_version, engine_version, assets.<arch>.{url,sha256,size}}`。
旧版无 kind / schema_version 的清单若具备这些必需版本和资产字段仍可读取。

## 2. Android 独有库的交叉编译（bionic 与 glibc 的坑）

先说结论：**能不打原生就不打。** Android 用 bionic libc，任何按 glibc 编出来的 `.so` /
`.node` 在手机上都 `dlopen` 失败（不是找不到文件，而是 `library "libc.so.6" not found`
或符号版本不匹配）。本项目对原生依赖的策略是「能换实现就换实现」：

| 依赖 | 做法 | 原因 |
| --- | --- | --- |
| `koffi`（FFI） | 补丁改成纯 JS inert（`module.exports = () => { throw }`） | 上游只发 glibc 预编译 `.node`，手机用不到 FFI |
| `node-pty`（伪终端） | shim 成空实现 | 引擎只用 `child_process`，不需要 PTY |
| `ripgrep`（搜索） | 用 Termux 的 `rg` 注入 `@vscode/ripgrep-android-arm64/bin/rg` | 有官方 bionic 二进制，比自编划算 |
| `sharp` 等图像库 | 保留 `sharp-wasm32` 回退，删 `sharp-linux-x64` | wasm 无架构依赖 |
| `better-sqlite3` 之类 | 目前不用；要引入必须按下面 A 节十字编 | 带原生 `.node` |

### 2.1 NDK 工具链

```sh
NDK=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.0.12077973}
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=26                                  # 不低于 App 的 minSdk
TARGET=aarch64-linux-android$API
export CC=$TOOLCHAIN/bin/$TARGET-clang
export CXX=$TOOLCHAIN/bin/$TARGET-clang++
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip
export CFLAGS="-O2 -fPIC -D__ANDROID_API__=$API"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-L$TOOLCHAIN/sysroot/usr/lib/$TARGET -L$TOOLCHAIN/sysroot/usr/lib"
```

- 必须 `-fPIC`（Android 强制 PIE），必须链接 `libc++_shared`（`-lc++_shared`）而不是 libstdc++；
- `.so` 要带正确的 SONAME（`-Wl,-soname,libfoo.so.1`），因为 Android linker 只按 NEEDED 里的
  精确文件名查找，没有 `ldconfig`、也没有 `libfoo.so → libfoo.so.1` 的惯例；
- SELinux 禁止应用目录里 `symlink()`/`link()`，所以「别名」只能 `cp` 出实体文件。

### 2.2 A. C/C++ 库（autotools / cmake）

```sh
./configure --host=aarch64-linux-android --prefix=/tmp/and-prefix \
            --enable-shared --disable-static
make -j"$(nproc)" && make install DESTDIR=/tmp/and-stage

# 必须核验：依赖全在 Android 体系内，SONAME 正确
llvm-readelf -d /tmp/and-stage/prefix/lib/libfoo.so | grep -E 'NEEDED|SONAME'
```

判定标准：`NEEDED` 只能出现 `libc.so`、`libm.so`、`libdl.so`、`libc++_shared.so` 或自己带的库；
一旦出现 `libc.so.6` / `libpthread.so.0`，说明用了宿主 cc，重编。

CMake 用 `-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26`。

### 2.3 B. Rust（含 PyO3 / openssl）

```sh
rustup target add aarch64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TOOLCHAIN/bin/$TARGET-clang
# openssl 必须 vendored，否则找宿主 openssl 头文件
OPENSSL_DIR=/tmp/and-openssl cargo build --release --target aarch64-linux-android
```

- **edition 2024 起 `#[no_mangle]` 必须写成 `#[unsafe(no_mangle)]`**，否则报
  `error: unsafe attribute used without unsafe`（2026-10 实测，rustc 1.98）；
- PyO3 产物是 `.so` 不是 wheel：`manylinux` 标签在 Android 上无意义，直接把 `.so` 拷进
  `$PREFIX/lib/python3.x/site-packages/`，并确保 `NEEDED` 里是 `libpython3.x.so`（Termux 的
  python 包提供）而不是宿主绝对路径；
- 有 build script 探测宿主时，用 `--target` + `PKG_CONFIG_ALLOW_CROSS=1` 并显式给
  `PKG_CONFIG_PATH=/tmp/and-prefix/lib/pkgconfig`。

### 2.4 C. Python C 扩展

```sh
CC=$CC CFLAGS="$CFLAGS" LDSHARED="$CC -shared" \
  python3 setup.py build_ext --inplace --plat-name=android_26_arm64_v8a
```

`pip install` 的 `manylinux`/`musllinux` wheel 在 Android 上不可用（标签不匹配且 glibc 链接），
必须源码构建，或用 `pip install --no-binary :all:`；装完 `llvm-readelf -d` 复核一次。

### 2.5 D. Node 原生插件（`.node`）

- 必须针对 **Termux 的 node 头文件**编译，ABI 与手机上的 node 完全一致。头文件不在
  `runtime.zip` 里（收集脚本为省体积不取 `include/`），要单独从同一个 deb 取：

  ```sh
  curl -O https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/n/nodejs-lts/nodejs-lts_24.22.1_aarch64.deb
  dpkg-deb -x nodejs-lts_24.22.1_aarch64.deb ./nhdr
  H=$(find ./nhdr -type d -name node | head -1)     # …/usr/include/node
  $CC -O2 -fPIC -shared -I"$H" -DNAPI_VERSION=8 -o addon.node addon.c
  ```

- 上游 npm 包里自带的 `*.node`（如 `koffi`、`node-pty`、`sherpa-onnx`）基本都是 glibc x64/arm64 预编译，
  **在 bionic 上不可 dlopen**；本项目用纯 JS 兜底或直接不用；
- 宿主机在 x86_64 上 `npm install` 会按宿主平台装 `*-linux-x64` 的 optional 包，必须删掉
  （`collect-termux-runtime.sh` 里已显式 `rm -rf sharp-linux-x64 / sherpa-onnx-linux-x64 / …`），
  否则白占 ~50MB 且永远加载不了。

### 2.6 错误对照表

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| `CANNOT LINK EXECUTABLE: library "libreadline.so.8" not found` | deb 里只有 `libreadline.so.8.2`，缺精确 SONAME 名 | `cp` 出 `libreadline.so.8`（SELinux 禁 symlink） |
| `EVP_MD_CTX_CREATE` 符号缺失 / `curl` 段错误 | Android 系统自带的 `libcrypto` 太旧，与系统 `curl` 不匹配 | 不用系统 curl，用 node `fetch` 包一层 `bin/curl` |
| `dlopen failed: library "libc++.so.6" not found` | 用了 libstdc++/宿主 cc | 用 `clang++ -stdlib=libc++ -lc++_shared` 重编 |
| `Python.h: No such file` | 用了宿主 python 的 include | 指定 Termux python 的 `include/python3.x` |
| wheel 明明下载成功却 `ImportError` | manylinux wheel + glibc | 源码构建 |
| `llvm-readelf` 显示 `libc.so.6` | 走错工具链 | 清 `CC/CXX` 重编 |

### 2.7 三条验收命令

```sh
file mylib.so                                  # 应为 ELF 64-bit LSB shared object, ARM aarch64
llvm-readelf -d mylib.so | grep -E 'NEEDED|SONAME'
# 手机上：python -c "import mymod" / node -e "require('./mylib.node')" 不报错
```

设备侧注意：Android 11+ 的 scoped storage 让 `node` 读不到 `/sdcard` 根目录（会 `MODULE_NOT_FOUND`），
把文件放 `/sdcard/Download/`，再用脚本 `cp` 到 `$PREFIX/tmp` 后 `require` 即可（2026-10 实测通过：
`node v24.18.0 -> hello-android`）。

---

## 3. 编译 / 收集 Termux 运行时（runtime.zip）

引擎层有两块（见 §1），这里说的是**下面那块：Termux 运行时**。
只在换 node 版本、加原生库、换 Termux 包时才需要重做；平时升级 dsh 用 §1 就够。

### 3.1 运行时里有什么

`runtime.zip` 解出来就是 Termux 前缀树（`bin/ lib/ etc/ share/ tmp/`），关键内容：

| 路径 | 内容 |
| --- | --- |
| `bin/node`、`bin/bash`、`bin/rg` | Termux 为 bionic 交叉编译的可执行文件 |
| `lib/*.so*` | 14 个库：`libc++_shared`、`libicu*`、`libsqlite3`、`libssl/libcrypto`、`libcares`、`libz`、`libreadline`、`libncursesw`、`libpcre2-8`、`libiconv`、`libandroid-support` 等 |
| `etc/tls/cert.pem` | HTTPS 根证书（模型 API 必需） |
| `lib/node_modules/**` | dsh JS 闭包（发布形态） |

**不要用 nodejs.org 的官方 linux-arm64 包**：那是 glibc 链接，bionic 上无法运行。
Termux 的 `nodejs-lts` 才是正确的二进制来源。

### 3.2 一条命令

```sh
export TERMUX_REPO=https://mirrors.ustc.edu.cn/termux/apt/termux-main   # 国内源
export DSH_SOURCE_DIR=/tmp/release-consumer/node_modules
bash scripts/collect-termux-runtime.sh /tmp/rt-arm64.zip aarch64
```

先完成 §1.3 的本地 consumer；未提供 `DSH_SOURCE_DIR` 会在下载运行时前报错。

### 3.3 脚本做了哪些事

1. **配置隔离 apt 源**：只留 `deb [arch=aarch64] ${TERMUX_REPO} stable main`，并用
   `-o Dir::Etc::Parts=/dev/null -o Dir::Etc::sourceparts=-` 屏蔽宿主 apt 配置
   （否则会去抓 Ubuntu / VSCode / Chrome 的源，几分钟白等）；
2. **下载显式依赖闭包**（不用 apt 自动递归，便于审计）：
   `nodejs-lts`、`bash readline ncurses libiconv termux-tools`、`ripgrep pcre2`、
   `openssl c-ares libicu libsqlite zlib libc++`、`libuv brotli`、`libandroid-support`、`ca-certificates`；
3. **`dpkg-deb -x` 解包后平铺**：Termux deb 用绝对路径打包（`data/data/com.termux/files/usr/…`），
   脚本定位真实 `usr` 再平铺成 zip 根，与 Android 端 `PATH=$PREFIX/bin`、
   `LD_LIBRARY_PATH=$PREFIX/lib`、`dshEntry=$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js` 对齐；
4. **集成 JS 闭包**：必须提供 `DSH_SOURCE_DIR=<发布形态 consumer/node_modules>`，
   复制上游本地 pack 组装的闭包（见 §1.3），不从 registry 安装 dsh；
5. **打补丁**：`scripts/patch-engine-closure.sh`（koffi inert、node-pty shim、session-persistence、
   fs-local、flock、SONAME 别名、ripgrep 平台包注入、pnpm/curl wrapper）；
6. **清平台残留**：删 `*.a`、`*.map`、`*-linux-x64` 的 optional 包；
7. **打 zip**：`zip -qr` 产出 `runtime.zip` 并打印 SHA-256。

### 3.4 加包 / 换版本

- **加一个 Termux 包**：在 `PKGS` 里加名字，并先去 `https://packages.termux.dev` 的
  `binary-aarch64/Packages` 索引核对该包存在、且它的依赖也被显式列出（`apt-get download`
  不会递归拉依赖，漏了会在设备上 `CANNOT LINK`）。
- **换 node 大版本**：把 `nodejs-lts` 换成 `nodejs`（current）或锁某一版；换完必须重跑
  §1.2 的闭包构建（native 相关的 ABI 变了），并重新验证 `bin/node --version`。
- **加原生库**：按 §2 十字编出 `.so` 后，把它放进 `lib/` 并确保 `NEEDED` 里其余依赖都在
  `lib/` 里（Android 没有系统级 libc++，必须自带 `libc++_shared.so`）。

### 3.5 收尾与验证

```sh
# 1) 运行时 → 引擎层（结构化校验 + manifest）
scripts/build-engine-layer.sh --runtime /tmp/rt-arm64.zip --arch aarch64 \
    --out /tmp/engine-mine --engine-version "0.2.2-rc.4+local1"
# 2) 产物自检
tar -tzf /tmp/engine-mine/engine-dsh-aarch64.tar.gz | grep -c 'lib/node_modules/@deepseek-ai/dsh'
llvm-readelf -d /tmp/engine-mine/engine-tree/lib/dsh-engine/lib/libreadline.so.8 | head -5
# 3) 装机（见 §1.5）
```


---

## 参考

- Termux 包索引：<https://packages.termux.dev>
- Android NDK 交叉编译：<https://developer.android.com/ndk/guides/other_build_systems>
- PyO3 交叉编译：<https://pyo3.rs/latest/building-and-distribution#cross-compiling>
- bionic libc 差异：<https://android.googlesource.com/platform/bionic/+/master/docs/status.md>

## 8. 把 libvips 闭包一起打进引擎层（原生 sharp）

`sharp` 的原生 addon 需要 `libvips`。不打包时设备上必须 `pkg install libvips`，否则回落 wasm。
`build-engine-layer.sh` 支持 `VIPS_LIBS_DIR`：设了就自动跑 `scripts/pack-engine-vips.sh`，
把 69 个 `.so`（约 39 MB）落到 **`<engine-tree>/lib/dsh-engine/lib`** —— 引擎私有库目录，不是 Termux 的 `$PREFIX/lib`。

```bash
# 1) 设备上收集一份完整依赖闭包（一次性）
#    tar -czf /tmp/termlibs.tar.gz -C $PREFIX/lib .
#    adb pull /tmp/termlibs.tar.gz /tmp/ && mkdir -p /tmp/termlibs && tar -xzf /tmp/termlibs.tar.gz -C /tmp/termlibs

# 2) 构建引擎层时一起打
VIPS_LIBS_DIR=/tmp/termlibs \
  scripts/build-engine-layer.sh --runtime /tmp/rt.zip --arch aarch64 \
    --out /tmp/engine-vips --engine-version "0.2.2-rc.4+sharp1"
```

要点：

- 落地文件名用 **ELF 的 SONAME**（`readelf -d` 读），不要用源文件名。Termux 的 `libpng16.so`
  常是指向 `libpng16.so.16` 的符号链接；按源名落地会让 `dlopen("libpng16.so.16")` 失败。
- `pack-engine-vips.sh` 用 `LC_ALL=C` 跑 `readelf`（中文 locale 下输出本地化，正则匹配不到）。
- 库必须放引擎私有目录：写进 `$PREFIX/lib` 等于污染 bootstrap，换引擎也不会被替换。
- 设备侧 `dsh-update apply` 自 v2.5.62 起会连 `lib/dsh-engine/lib` 一起换，升级也能带上它们。
