# 开发工具与环境配置

> 这份文档回答一个问题：**在当前仓库里改一行代码、打一次包、上一台真机验证，到底需要哪些工具、哪个版本、哪些环境变量。**
> 版本号是从本机实测抄下来的（2026-11-03）。换机器时按 §9 从零搭一遍即可。

## 0. 一页速查

| 层 | 工具 | 版本（本机实测） | 用途 |
| --- | --- | --- | --- |
| 构建 | JDK | 21.0.10（BellSoft LTS） | Gradle/AGP 的 JVM |
| 构建 | Gradle | 8.9（本地分发 `~/Projects/common/dsh-android-build/gradle-8.9`） | 打 APK |
| 构建 | AGP / Kotlin | 8.5.2 / 1.9.24 | Android 插件、Kotlin 编译 |
| 构建 | Android SDK | platforms `android-36`、`android-36.1`、`android-35-ext14`；build-tools `36.0.0`/`35.0.0`/`34.0.0` | 编译期 API |
| 交叉编译 | NDK | 28.0.13004108（`~/Android/Sdk/ndk/28.0.13004108`） | 编 aarch64 原生库（sharp addon） |
| 宿主机 | Node / pnpm | v24.14.0 / 11.28.5 | 引擎闭包、脚本 |
| 宿主机 | Python | 3.12.3（仅标准库即可跑全部脚本） | 仓库脚本 |
| 宿主机 | adb | 1.0.41 / 34.0.4-debian | 装机、截图、uiautomator |
| 设备 | Termux 运行时 | bash + node **v24.18.0** + ripgrep | 引擎宿主 |
| 设备 | dsh 引擎 | `0.2.2-alpha.2`（`.dsh-engine-version`，可带 `+sharpN` 后缀） | WebUI/Agent |
| 设备 | Magisk su | 已 root（`su -c id` → uid=0） | 特权测试、读私有目录 |
| 设备 | Shizuku | 13.6.0（`moe.shizuku.privileged.api`） | `shz`（adb 身份执行） |
| 设备 | Android | HyperOS / Android 16（SDK 36），arm64-v8a | 真机验证 |

端口约定：`3080` 引擎 WebUI（401 即健康）、`3082` shz 桥、`3083` AgentBridge、`3090` LAN 代理。

## 1. 主机（PC）环境

### 1.0 宿主系统基线（本仓库验证用的那台）

| 项 | 实测值 |
| --- | --- |
| 发行版 | **Ubuntu 24.04.5 LTS**（x86_64） |
| 内核 | 7.0.0-42-generic |
| CPU | 13th Gen Intel Core i7-13650HX（20 线程） |
| 内存 | 32 GB（`MemTotal 32554084 kB`，构建时可用约 20 GB） |
| 交换分区 | 无（所以 Gradle 堆别开太大：本仓库用 `-Xmx4096m`） |
| 磁盘 | 根分区 196 GB，构建期需 ≥ 60 GB 空闲（APK 318 MB + `node_modules` ~1 GB + NDK/SDK ~20 GB） |
| GPU | Intel Raptor Lake-S UHD + NVIDIA GeForce RTX 5060 Laptop（驱动 580.178.04；本仓库构建不需要 GPU） |
| Shell / locale | zsh / `LANG=en_US.UTF-8`、`LANGUAGE=zh_CN:en_US:en`（**正因 locale 含中文，ELF 解析必须显式 `LC_ALL=C`**） |
| 网络代理 | 默认不设 `http_proxy`；大文件慢时用旁路由 `http://192.168.31.2:7893` |

最低可用配置（能出包即可）：4 核 / 8 GB 内存 / 40 GB 空闲磁盘 / 任意 64 位 Linux（macOS 未验证）。

### 1.1 JDK 与 Gradle
```bash
java -version                      # openjdk 21.0.10 (BellSoft)
~/Projects/common/dsh-android-build/gradle-8.9/bin/gradle --version   # Gradle 8.9 / JVM 21.0.10
```
仓库**不带** wrapper，统一用上面这个本地 Gradle 分发（避免每次联网拉 distribution）。
并发保护：多个任务同时构建会抢 Gradle daemon，脚本里统一用 `flock /tmp/dsh-gradle.lock <gradle> ...`。

### 1.2 Android SDK / NDK
```bash
ls ~/Android/Sdk/platforms      # android-36  android-36.1  android-35-ext14
ls ~/Android/Sdk/build-tools    # 36.0.0 35.0.0 34.0.0
ls ~/Android/Sdk/ndk            # 28.0.13004108（本仓库交叉编译用的那个）
```
构建命令里需显式设置：`export ANDROID_HOME=~/Android/Sdk ANDROID_SDK_ROOT=~/Android/Sdk`。
`compileSdk = 36`、`minSdk = 26`、`targetSdk = 28`（targetSdk=28 是刻意选择：sideload 分发豁免 Android 10+ 的 W^X，见 `app/build.gradle.kts` 注释）。

### 1.3 Node / Python
```bash
node -v      # v24.14.0（PC 侧只用于跑 scripts/*.mjs 与引擎闭包）
pnpm -v      # 11.28.5（构建上游引擎源码时用）
python3 -V   # 3.12.3（scripts/*.py 只用标准库：pathlib/re/json/lzma/hashlib/...）
```

## 2. 项目构建配置

| 项 | 值 | 说明 |
| --- | --- | --- |
| applicationId / namespace | `com.termux` / `app.dsh.mobile` | applicationId 必须保持 `com.termux`（Termux 兼容层） |
| ABI | 仅 `arm64-v8a` | `-Pabi=arm64-v8a`，单 ABI 减少体积 |
| 版本号 | `app/build.gradle.kts` 的 `versionCode` / `versionName` | 每次发布成对递增 |
| Gradle JVM 参数 | `-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8` | `gradle.properties` |
| 签名 | `keystore.properties` 或环境变量 | 见下 |
| 输出 | `app/build/outputs/apk/release/app-release.apk` | ~318 MB（含引擎层 + vips 闭包） |

签名（缺省会**回退 debug 签名**，正式包必须提供）：
```bash
export DSH_KEYSTORE_FILE=/abs/path/release.jks
export DSH_KEYSTORE_PASSWORD=...      # 或 keystore.properties: storePassword
export DSH_KEY_ALIAS=...              # keyAlias
export DSH_KEY_PASSWORD=...           # keyPassword，省略时用 storePassword
```

打包：
```bash
export ANDROID_HOME=~/Android/Sdk ANDROID_SDK_ROOT=~/Android/Sdk
flock /tmp/dsh-gradle.lock ~/Projects/common/dsh-android-build/gradle-8.9/bin/gradle \
  -p . assembleRelease -Pabi=arm64-v8a --no-daemon
```
网络不好时加初始化脚本（阿里云 Maven 镜像插队）：`-I scripts/gradle-mirror.gradle`。

## 3. 设备侧环境

| 项 | 位置/值 |
| --- | --- |
| App 私有根 `$PREFIX` | `/data/user/0/com.termux/files/usr` |
| 引擎 home `$DSH_HOME` | `/data/user/0/com.termux/files/dsh-home` |
| 引擎层版本文件 | `$PREFIX/.dsh-engine-version` |
| 桥 token | `$PREFIX/.bridge-token`（0600，每次启动随机，只经环境变量给引擎） |
| Android 插件 | `$PREFIX/android-plugins/`（mobile-ui、mobile-progress、fast-mode、priv-mode、bridge-token、plugin-health） |
| overlay 配置 | `$PREFIX/android-overlay.yml`（引擎 `--patch` 用它注册插件） |
| 门脚本 | `$PREFIX/bin/{docs,dsh-update,native,priv,scr,notify,shz,su}` |
| 日志 | `$PREFIX/engine.log`（逐行 `[+Nms]`）+ logcat（`DshColdStart`、`DshShare`、`EngineConfig`） |

设备上常用的 adb 工具：`adb shell am/screencap/input/uiautomator dump/dumpsys/logcat/pidof`。
长按、点击这类交互在脚本里用 `input tap|swipe` + `uiautomator dump` 断言，不要靠截图肉眼判断。

特权相关：
- **Magisk su**：`su -c 'id'` → uid=0；改 `dsh_priv.xml` 必须「停 App → push XML → `su cp` + chown 到 app uid → 启动」。
- **Shizuku**：`scripts/start-shizuku.sh`（uiautomator 点「启动（针对已 root 设备）」）拉起 `shizuku_server`；首次需在 Shizuku 弹窗点「始终允许」授权 `com.termux`。

## 4. 引擎层构建链（PC 侧）

上游源码：`https://github.com/deepseek-ai/deepseek-harness`，tag **`dsh-v0.2.2-alpha.2`**。

| 步骤 | 命令 | 产物 |
| --- | --- | --- |
| 1 打 tarball | `pnpm exec tsx scripts/release/pack.ts --family dsh --out /tmp/npmpack` | 370 个 tarball |
| 2 装发布形态闭包 | `npm install --omit=optional --ignore-scripts --registry https://registry.npmmirror.com` | `node_modules`（~968 MB） |
| 3 打 Android 补丁 | `bash scripts/patch-engine-closure.sh` | 修复 bionic 下的 shebang/corepack/ripgrep/soname/curl 等 |
| 4 出引擎 tar | `bash scripts/build-engine-closure.sh --engine <骨架zip> --closure <nm> --version 0.2.2-alpha.2+xxx --out /tmp/engine.tar.gz` | 最小 tar（`.dsh-engine-version` + `lib/node_modules/**` + `lib/dsh-engine/lib/**`） |
| 5 装到设备 | `dsh-update stage <tar> <sha256>` → `dsh-update apply` | 引擎层热更新（可 rollback） |
| 全量运行时 | `bash scripts/collect-termux-runtime.sh /tmp/rt.zip aarch64` | Termux 运行时 zip（node/bash/rg + 14 个 .so） |

引擎层相关脚本：`collect-termux-runtime.sh`、`build-engine-layer.sh`、`build-engine-closure.sh`、`patch-engine-closure.sh`、`pack-engine-vips.sh`、`check-engine-deps.sh`、`full-check-deps.mjs`。
说明与实测数字见 `docs/ENGINE-BUILD.md`。

## 5. 交叉编译（aarch64 原生库）

| 用途 | 脚本 | 依赖 |
| --- | --- | --- |
| sharp 原生 addon（PC 交叉编译） | `scripts/build-sharp-native-pc.sh` | NDK clang（`aarch64-linux-android26`）、node v24.18.0 headers、libvips sysroot、node-addon-api |
| sharp 原生 addon（设备内编） | `scripts/build-sharp-native.sh` | 设备 `pkg install libvips clang pkg-config` |
| libvips 依赖闭包 | `scripts/pack-engine-vips.sh` | `LC_ALL=C readelf` 递归 NEEDED |
| sharp wasm 兜底 | `scripts/patch-sharp-wasm.sh` | registry 拉 `@img/sharp-wasm32` + `@emnapi/runtime` |

关键约束：ELF 解析必须 `LC_ALL=C`（中文 locale 下 readelf 输出被本地化，架构会读成 `built for ?`）；落地文件名按 **SONAME**（Termux 里 `libpng16.so` 只是符号链接）。

## 6. 验收、测试与审计

| 入口 | 覆盖 | 说明 |
| --- | --- | --- |
| `bash scripts/acceptance.sh` | 静态门禁 → JVM 单测 → 构建 → APK 断言 | `--static` 只跑前两段，`--device <serial>` 追加真机 |
| `gradle testDebugUnitTest` | 16 个 JVM 单测（ShareLogic / EngineEnvSpec / overlayDocument） | 纯逻辑，不需要设备 |
| `bash scripts/smoke-device.sh --device <serial> [--priv-cycle]` | 引擎/分享/mobile-ui/长按菜单/特权三模式 | 21 项断言 |
| `bash scripts/start-shizuku.sh --device <serial>` | 拉起 Shizuku 服务 | uiautomator 定位按钮 |
| `bash scripts/audit-apk.sh <apk>` | 签名指纹/权限/组件/载荷哈希/URL 扫描 | 对外审计用 |
| `bash scripts/check-engine-deps.sh --root <nm>` | 缺依赖 + native addon 架构 + 运行时探针 | 三处构建内置硬门禁 |
| `bash scripts/check-doc-endpoints.py` / `check-i18n.py` | 文档端点一致性 / 中英配对 | 改文档后必跑 |

## 7. 网络与镜像

| 场景 | 配置 |
| --- | --- |
| apt（宿主机） | `/etc/apt/sources.list.d/ubuntu.sources` 指向 `mirrors.aliyun.com` + `mirrors.ustc.edu.cn` |
| npm/pnpm | `registry=https://registry.npmmirror.com` |
| Gradle | `-I scripts/gradle-mirror.gradle`（阿里云 Maven 镜像插队） |
| Termux apt（收运行时） | `TERMUX_REPO=https://mirrors.ustc.edu.cn/termux/apt/termux-main` |
| 大文件/下载慢 | 旁路由 OpenClash：`http_proxy=http://192.168.31.2:7893`（dl.google.com 已改为走代理节点） |
| APK/引擎 tar 传输 | `adb push` 到 `/data/local/tmp`（不要放 `/sdcard` 根，scoped storage 读不到） |

## 8. 环境变量总表

| 变量 | 用于 | 取值示例 |
| --- | --- | --- |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Gradle、脚本 | `~/Android/Sdk` |
| `DSH_KEYSTORE_FILE` / `_PASSWORD`、`DSH_KEY_ALIAS` / `_PASSWORD` | release 签名 | 见 §2 |
| `DSH_SOURCE_DIR` | `collect-termux-runtime.sh` 用仓库闭包替代上游拉取 | `/tmp/consumer/node_modules` |
| `TERMUX_REPO` | 收 Termux 运行时用的 apt 源 | USTC 镜像地址 |
| `VIPS_LIBS_DIR` | `build-engine-layer.sh` 捆绑 libvips 闭包 | `/tmp/termlibs` |
| `NM` / `NPM_REGISTRY` | `patch-sharp-wasm.sh` | `<node_modules>` / npmmirror |
| `ARCH` / `API` / `DSH_VERSION` | 各构建脚本参数 | `aarch64` / `26` / `0.2.2-alpha.2` |
| `DSH_BRIDGE_TOKEN` / `DSH_SHZ_PORT` | 设备侧调桥（引擎内由 App 注入） | token / `3082` |
| `LC_ALL=C` | readelf/ELF 解析、依赖审计 | 必须 |

## 9. 从零搭一套环境

1. 装 JDK 21、Android SDK（platform 36 + build-tools 36 + NDK 28）、Node 24、pnpm 11、Python 3.12、adb。
2. 下载本地 Gradle 8.9 到 `~/Projects/common/dsh-android-build/gradle-8.9`。
3. `export ANDROID_HOME=~/Android/Sdk ANDROID_SDK_ROOT=~/Android/Sdk`。
4. 设备装本仓库 APK：`adb install -r app-release.apk`，首次启动等「正在准备运行环境」完成（会解包引擎层）。
5. 健康检查：`adb shell curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3080/` → **401**。
6. 想要 Shizuku 能力：装 Shizuku APK → `scripts/start-shizuku.sh` → Shizuku 弹窗点「始终允许」。
7. 跑一遍验收：`bash scripts/acceptance.sh --device <serial>`。

## 10. 已踩过的坑（快速对照）

| 现象 | 原因 / 处理 |
| --- | --- |
| `built for ?` / addon 架构误报 | 中文 locale 下 readelf 输出被本地化 → `LC_ALL=C` |
| `npm` 在设备上必挂 | 0.2.2 引擎无 corepack；用 curl 拉 tarball + clang 直编 |
| `/tmp` 在 Android 只读 | 一律用 `$PREFIX/tmp` |
| `adb shell input text` 打不进 WebView | 用 CDP `Input.insertText`；`input text` 的空格要写 `%s` |
| 分享大文件卡主线程 | 已修：后台线程 + 512 MB/1 GB/20 文件上限 |
| 引擎层只在首装解包 | 升级 APK 不会换 `lib/`；用 `dsh-update apply` 双换（闭包 + 原生库） |
| release 包是 debug 签名 | 缺 keystore 时的回退；正式发布必须设 §2 的四个变量 |
| 文档端点门禁报 unknown | 路由在 `bridge/Router.kt`，`check-doc-endpoints.py` 同时扫两个文件 |
