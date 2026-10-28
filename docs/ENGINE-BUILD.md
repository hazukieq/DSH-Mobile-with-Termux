# 引擎层构建：DSH 引擎闭包 / Android 独有库 / Termux 运行时

App 里的「引擎层」是两块拼起来的：

| 层 | 内容 | 位置（设备上） | 什么时候要动 |
| --- | --- | --- | --- |
| **Termux 运行时** | `node` / `bash` / `rg` + 14 个 bionic `.so` + `etc/tls` | `$PREFIX/{bin,lib,etc}` | 换 node 版本、加原生库、换 Termux 包 |
| **JS 闭包** | `lib/node_modules/**`（dsh 及其依赖） | `$PREFIX/lib/node_modules` | 每次升级 dsh |

`dsh-update` 只交换第二层（`lib/node_modules` + `$PREFIX/.dsh-engine-version`），
第一层由 APK 内置、装机时铺好，日常升级不需要重编。这也决定了本篇的顺序：
**先看能不能只换闭包（§1），不行再重收运行时（§3）**；两者都要打同一套 Android 补丁（§1.3）。


## 1. 只换闭包（PC 上编译 dsh 引擎层）

升级 dsh 引擎**不需要重编 Termux 运行时**。引擎层分两块：

| 块 | 内容 | 变不变 |
| --- | --- | --- |
| 运行时骨架 | `bin/node`、`bin/bash`、`bin/rg`、`lib/*.so`（14 个，含 SONAME 精确名） | 稳定，直接复用 APK 里那份 |
| JS 闭包 | `lib/node_modules/**`（`@deepseek-ai/*` 等） | 每次上游升级才换 |

所以 PC 端一条链就够：**上游打包脚本产闭包 → 打 Android 补丁 → 自检 → 打 tar → stage/apply**。

### 1.1 前置

- Node 24 + pnpm（`corepack enable pnpm`）
- 国内源：`NPM_REGISTRY=https://registry.npmmirror.com`、`TERMUX_REPO=https://mirrors.ustc.edu.cn/termux/apt/termux-main`（TUNA 的 termux 路径 403）
- 不需要 Android NDK（闭包是纯 JS + 少量 `.node`，补丁会改成纯 JS 兜底）

### 1.2 从 GitHub 源码构建（不要拉 npm 上的 dsh 发布包）

```sh
git clone --depth 1 -b dsh-v0.2.2-alpha.2 https://github.com/deepseek-ai/deepseek-harness /tmp/dhsrc
cd /tmp/dhsrc && pnpm install --frozen-lockfile && pnpm build:official
# 上游自己的发布打包：产出该版本全部可发布包的 tarball
pnpm exec tsx scripts/release/pack.ts --family dsh --out /tmp/npmpack
```

### 1.3 用这些 tarball 装出「发布形态闭包」

`/tmp/npmpack` 里是 370 个 `.tgz`。把它们当 `file:` 依赖装一次，得到的 `node_modules`
与官方发布闭包同形（有 `cosmokit`、无 `workspace:`）。平台不符的包（darwin/arm64 等）放
`optionalDependencies` 并 `--omit=optional`，否则 `npm` 会 `EBADPLATFORM` 中断。

```sh
python3 - <<'PY'
import json, os, re, subprocess
pack, out = '/tmp/npmpack', '/tmp/release-consumer'
os.makedirs(out, exist_ok=True)
deps, opt = {}, {}
pat = re.compile(r'^deepseek-ai-(.+)-(\d+\.\d+\.\d+[^/]*?)\.tgz$')
def compatible(man):
    oses, cpus = man.get('os') or [], man.get('cpu') or []
    return ((not oses or 'linux' in oses or 'any' in oses)
            and (not cpus or 'x64' in cpus or 'any' in cpus))
for f in sorted(os.listdir(pack)):
    m = pat.match(f)
    if not m:
        continue
    path = os.path.abspath(os.path.join(pack, f))
    man = json.loads(subprocess.run(['tar', '-xzOf', path, 'package/package.json'],
                                    capture_output=True, text=True).stdout)
    (deps if compatible(man) else opt)[man['name']] = 'file:' + path
json.dump({'name': 'dsh-release-consumer', 'private': True,
           'dependencies': deps, 'optionalDependencies': opt},
          open(os.path.join(out, 'package.json'), 'w'), indent=2)
print(len(deps), 'deps', len(opt), 'optional')
PY

cd /tmp/release-consumer
npm install --ignore-scripts --no-audit --no-fund --package-lock=false \
  --omit=optional --registry https://registry.npmmirror.com
```

> 为什么不用 `pnpm deploy` monorepo：那份 `node_modules` 会残留 `workspace:` 协议、
> 缺打包期注入的 `cosmokit`，装到手机上引擎 `exit=1`（v2.5.48 踩过这个坑）。

### 1.4 换闭包 + 打补丁 + 打包（一条命令）

```sh
scripts/build-engine-closure.sh \
  --engine app/src/main/assets/engine-dsh-aarch64.zip \
  --closure /tmp/release-consumer/node_modules \
  --version 0.2.2-alpha.2+src1 \
  --out /tmp/engine-dsh-aarch64-closure.tar.gz
```

脚本做六件事：解包骨架 → 换 `lib/node_modules` → 调 `scripts/patch-engine-closure.sh`
打 bionic 补丁 → 三道自检 → 写 `.dsh-engine-version` →
打只含 `lib/node_modules/**` 与 `.dsh-engine-version` 的 tar。
实测 x86_64 主机 20 秒出包（142 MB、378 个 `@deepseek-ai` 包）。

补丁全部「按存在性自适应」，上游变了不会炸：目标文件不在只 WARN。
已覆盖的差异：`dsh-host-apiproxy` 0.2.2 已删、`corepack` 0.2.2 不带、
`libhistory` 缺失、`lib/` 与 `lib/dsh-engine/lib/` 两种布局。

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

---

### 1.7 没有主机？在手机上自建（Termux 内，10–15 分钟）

App 自带的就是完整 Termux，手机端能直接 build，不需要 root 也不需要电脑：

```sh
pkg update && pkg install -y nodejs-lts pnpm git zip tar   # 国内源（NJU/USTC）约 1–2 分钟
cd ~ && git clone --depth 1 --branch dsh-v0.2.2-alpha.2 \
    https://github.com/deepseek-ai/deepseek-harness.git dhs
cd dhs
pnpm config set registry https://registry.npmmirror.com      # 不改会卡在 npmjs
NODE_OPTIONS=--max-old-space-size=4096 \
  pnpm install --frozen-lockfile && pnpm build:official
```

构建完只是源码树，还要把「发布形态闭包」挑出来并打 Android 补丁（否则引擎起不来）：

```sh
# 1) 组装闭包：只保留 @deepseek-ai/* 与其运行期依赖（别整包 node_modules 打进去）
mkdir -p ~/eng/lib && cp -a node_modules ~/eng/lib/node_modules
# 2) 打补丁（脚本随 App 一起装在 $PREFIX/bin 之外，仓库里有）
bash "$DSH_HOME/../scripts/patch-engine-closure.sh"   # 无仓库时按 §2 的手工补丁清单逐条打
# 3) 只打最小布局：lib/node_modules/** + 版本标记
tar -C ~/eng -czf ~/engine-mine.tar.gz lib .dsh-engine-version
dsh-update stage ~/engine-mine.tar.gz "$(sha256sum ~/engine-mine.tar.gz | cut -d' ' -f1)"
dsh-update apply
```

手机端的三个硬约束：

| 约束 | 说明 |
| --- | --- |
| 内存 | 默认 node 堆会 OOM，必须 `NODE_OPTIONS=--max-old-space-size=4096`；构建时别开别的 App |
| 存储 | 源码 + `node_modules` + 构建产物约需 3 GB，先 `df -h $_PREFIX` 看一眼 |
| 网络 | `pnpm` 与 `git` 都要换国内源（`registry.npmmirror.com` / `ghproxy` 或用 App 内已有的代理） |

> 手机端自建的意义是「完全离线可控」，不是更快：同样一步在 PC 上 20 秒，手机上 10–15 分钟。
> 只是换闭包而不改代码时，优先用 §1.4 的一条命令（复用现有骨架）。

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
- CI 在 x86_64 上 `npm install` 会按宿主平台装 `*-linux-x64` 的 optional 包，必须删掉
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
export NPM_REGISTRY=https://registry.npmmirror.com
scripts/collect-termux-runtime.sh /tmp/rt-arm64.zip aarch64
```

x86_64 主机实测 ~2 分钟出包（450 MB，含 JS 闭包）。

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
4. **集成 JS 闭包**：默认用 npm 装 `@deepseek-ai/dsh`；设 `DSH_SOURCE_DIR=<发布形态闭包>` 则直接
   拷 GitHub 源码构建出来的闭包（见 §1.3），不发 npm 请求；
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
    --out /tmp/engine-mine --engine-version "0.2.2-alpha.2+my1"
# 2) 产物自检
tar -tzf /tmp/engine-mine/engine-dsh-aarch64.tar.gz | grep -c 'lib/node_modules/@deepseek-ai/dsh'
llvm-readelf -d /tmp/engine-mine/engine-tree/lib/dsh-engine/lib/libreadline.so.8 | head -5
# 3) 装机（见 §1.5）
```

### 3.6 实测记录（2026-10-28，Ubuntu 24.04 / NDK 28 / rustc 1.98）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 收运行时 | `collect-termux-runtime.sh /tmp/rt-verify.zip aarch64`（USTC 源 + `DSH_SOURCE_DIR`） | 9 秒下完 29.2 MB 依赖（3.27 MB/s）；`runtime.zip` **220 MB**（解包 756 MB / 70,127 文件 / 66 个 `.so`）；`bin/node` 为 ELF aarch64；补丁 7 个 corepack shim + 28 个 shebang |
| 建引擎 tar | `build-engine-closure.sh --engine /tmp/eng0 --closure … --version 0.2.2-alpha.2+verify1` | **19 秒**；142,605,127 B；378 包；自检 `cosmokit=1 workspace:0 cordis=require-ok`；tar 只含 `.dsh-engine-version` + `lib/node_modules/**` |
| C 库十字编 | `clang -shared -Wl,-soname,libfoo.so.1` | ELF ARM aarch64；`NEEDED` = `libdl/libc`；`SONAME` = `libfoo.so.1` |
| C++ 十字编 | `clang++ -stdlib=libc++` | `NEEDED` = `libc++_shared/libm/libdl/libc` |
| Rust 十字编 | `cargo build --target aarch64-linux-android` | ELF ARM aarch64；`NEEDED` = `libdl/libc` |
| N-API 插件 | 用 Termux 头文件编 `addon.node` | 手机端 `require()` 成功输出 `hello-android` |

> 判断该走哪条路：**只升 dsh 版本 → §1（换闭包，20 秒）；换 node / 加原生库 / 改 Termux 包
> → §3（重收运行时，2 分钟）**。两条路最后都产同样布局的引擎 tar，`stage/apply/rollback` 完全一致。

---

## 参考

- Termux 包索引：<https://packages.termux.dev>
- Android NDK 交叉编译：<https://developer.android.com/ndk/guides/other_build_systems>
- PyO3 交叉编译：<https://pyo3.rs/latest/building-and-distribution#cross-compiling>
- bionic libc 差异：<https://android.googlesource.com/platform/bionic/+/master/docs/status.md>
