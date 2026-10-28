# 交叉编译指南：在 Android 上补上「没有 wheel」的包

DSH Mobile 的运行时是 **Termux 前缀里的 CPython**（`$PREFIX/bin/python3`，bionic libc + arm64）。PyPI 上绝大多数二进制包只发布 `manylinux`（glibc）或 macOS/Windows wheel，**Android 一个都没有**，所以 `pip install` 遇到带扩展的包时通常是这样失败的：

```
ERROR: Could not find a version that satisfies the requirement <pkg>
ERROR: No matching distribution found for <pkg>
```

本文给出判定流程和三条可落地的补法，按「成本从低到高」排序。

---

## 0. 先判断这个包到底需不需要编译

| 包形态 | 判定方法 | 结论 |
|---|---|---|
| 纯 Python | `pip download <pkg> --no-deps -d /tmp/x` 得到 `*py3-none-any.whl` | **直接装**，`pip install` 即可 |
| 带 C/C++ 扩展 | 源码里有 `setup.py`/`pyproject.toml` + `.c/.cpp`，或 sdist 里有 `setup_requires` | 需要编译（§1 或 §2） |
| 带 Rust 扩展 | `pyproject.toml` 里 `build-backend = "maturin"` | 需要 Rust + NDK（§2.3） |
| 只提供桌面端二进制 | 只有 `manylinux*.whl` / `*.dylib` / `.dll` | 必须自己编（§1/§2） |

先用 `python3 -c "import sysconfig;print(sysconfig.get_platform())"` 确认平台标签，Android 上一般形如 `linux-aarch64`（Termux 打的补丁），这也是 pip 找不到 wheel 的根因。

---

## 1. 方案 A：直接在设备上编译（最省心）

Termux 自带完整工具链，只要设备有 ~2 GB 空闲和电，**首选这条路**：

```bash
pkg update
pkg install clang make pkg-config python python-dev rust binutils
# 按需装依赖的开发包（示例）
pkg install openssl-dev libffi-dev zlib-dev libxml2-dev libjpeg-turbo-dev

# 强制源码构建，不查 wheel
pip install --no-binary :all: --no-build-isolation <pkg>
```

要点：

- `--no-build-isolation`：避免 pip 在临时 venv 里重新装一遍 setuptools（会再次触发缺 wheel）。
- `--no-binary :all:` 对**所有**依赖生效；只想对目标包生效时改用 `--no-binary=<pkg>`。
- 编译产物落在 `$PREFIX/lib/python3.12/site-packages/`，重装 App 会随前缀一起重来，所以关键包建议写进 `scripts/build-runtime-app-prefix.sh` 的 `PKGS`/pip 列表，随运行时一起产出。
- Rust 扩展：`pkg install rust` 后直接 `pip install <pkg>`（maturin 会检测到 `aarch64-linux-android` 目标）。

**耗时参考**：numpy 约 12–20 分钟（vivo V2520A），scipy 30 分钟以上；纯 C 小包 1–3 分钟。

---

## 2. 方案 B：在 x86_64 主机上用 NDK 交叉编译（适合批量/CI）

设备编译慢且吃电，批量场景建议在主机上产出 wheel，再推进设备安装。

### 2.1 准备工具链

```bash
# 主机（Linux x86_64）
export ANDROID_NDK=$HOME/Android/Sdk/ndk/26.1.10909125
export API=26
export TARGET=aarch64-linux-android
export TOOLCHAIN=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64
export CC=$TOOLCHAIN/bin/${TARGET}${API}-clang
export CXX=$TOOLCHAIN/bin/${TARGET}${API}-clang++
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export LDSHARED="$CC -shared"
export CFLAGS="-fPIC -O2 -I$PREFIX/include"
export LDFLAGS="-L$PREFIX/lib"
```

`$PREFIX` 指**设备上前缀的副本**（从设备 `adb pull $PREFIX/include`、或直接使用 Termux 的 sysroot 包）。只依赖 libc/libm 的包可以省掉 `-I/-L`。

### 2.2 构建 wheel

```bash
pip wheel --no-deps --no-build-isolation -w dist <pkg>
```

如果 pip 因平台标签拒绝安装，把 wheel 改名成 pip 认的标签即可（Termux 的 python 一般接受 `linux_aarch64`）：

```bash
# 例：foo-1.2-cp312-cp312-linux_x86_64.whl → foo-1.2-cp312-cp312-linux_aarch64.whl
pip install --force-reinstall dist/foo-1.2-cp312-cp312-linux_aarch64.whl
```

### 2.3 Rust 扩展（maturin）

```bash
rustup target add aarch64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CC
export PYO3_CROSS=1
export PYO3_CROSS_PYTHON_VERSION=3.12
maturin build --release --target aarch64-linux-android --out dist
```

### 2.4 常见坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `fatal error: 'openssl/ssl.h' not found` | 缺头文件 | 设备 `pkg install openssl-dev`，把 `$PREFIX/include` 同步到主机并加 `-I` |
| `cannot find -lssl` | 缺 `.so` | 同上，加 `-L$PREFIX/lib`；或静态链接 `-l:libssl.a` |
| `undefined reference to 'pthread_...'` | bionic 把 pthread 并进 libc | 链接时去掉 `-lpthread`（或保留，Android 上是空实现） |
| `cannot find -lcrypt` | bionic 没有 libcrypt | 用 `libxcrypt` 或让包走 `cryptography` 的 OpenSSL 后端 |
| `_PYTHON_HOST_PLATFORM` 相关失败 | 交叉编译时 sysconfig 错乱 | `export _PYTHON_HOST_PLATFORM=linux-aarch64` |
| `ImportError: cannot locate symbol "__cxa_*"` | C++ 运行库未带上 | 用 `$CXX` 链接，或加 `-static-libstdc++` |
| `pip` 仍去下 manylinux | 标签匹配 | 加 `--only-binary :all:` 反选、或先 `pip download --no-deps` 再手动改名 |
| `auditwheel` 报错 | 它是给 glibc 用的 | Android 上不用 auditwheel；用 `patchelf --set-rpath '$ORIGIN'` 修依赖路径 |

### 2.5 验收

```bash
# 设备上
python3 -c "import foo; print(foo.__version__)"
readelf -d $PREFIX/lib/python3.12/site-packages/foo*.so | grep NEEDED
```

`NEEDED` 里只应出现 `libc.so libm.so libdl.so liblog.so` 和前缀内的库（`libssl.so` 等）；出现 `libgcc_s.so.1` 之外的 glibc 库就说明链错了目标。

---

## 3. 方案 C：换实现，不编译

优先考虑这条，维护成本最低：

| 场景 | 替代 |
|---|---|
| 只是要 JSON/HTTP/YAML | 用标准库 + 纯 Python 实现（`json`、`urllib`、`pyyaml` 有纯 Python 模式） |
| numpy 数值计算 | `pkg install python-numpy`（Termux 官方已编译） |
| 图像处理 | `pkg install python-pillow`；或直接用项目内的 PP-OCR / int8 检测模型（见 `CLICK_CHAIN_UPGRADE.md`） |
| 数据库 | `pkg install python-sqlite`（或标准库 `sqlite3`） |
| 加解密 | `pkg install python-cryptography`（Termux 有预编译包） |

Termux 官方仓库里已有大量预编译科学包，**先 `pkg search python-` 找一遍再决定编不编**。

---

## 4. 在本项目里怎么落地

1. 长期需要的包写进 `scripts/build-runtime-app-prefix.sh` 的 `PKGS`（apt 包）或该脚本末尾的 pip 列表，随运行时 zip 一起产出，设备端零等待。
2. 只需要在单台设备上临时补的包，直接 §1 设备编译；编完记得 `pip freeze` 记录版本，否则下次重装前缀就丢了。
3. agent 侧执行编译类任务时，先跑 `pkg install` 再 `pip install --no-binary :all:`，并把 `CFLAGS=-O2 -fPIC` 写进环境变量，避免默认 `-O0` 编出大而慢的 `.so`。
4. 编译期间建议 `termux-wake-lock`（App 内：设置 → 后台进度提示 → 保持唤醒），避免息屏被杀。

---

> 引擎层（JS 闭包 / Android 独有库交叉编译 / Termux 运行时收集）已独立成篇：
> **[ENGINE-BUILD.md](ENGINE-BUILD.md)**。本篇只讲「Android 上没有 wheel 的 Python 包怎么编」。

## 参考

- Termux 包索引：<https://packages.termux.dev>
- Android NDK 交叉编译：<https://developer.android.com/ndk/guides/other_build_systems>
- PyO3 交叉编译：<https://pyo3.rs/latest/building-and-distribution#cross-compiling>
- bionic libc 差异：<https://android.googlesource.com/platform/bionic/+/master/docs/status.md>
