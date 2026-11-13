# 构建环境与验证入口

具体版本以仓库配置和当前工具输出为准。以下列出构建所需条件，不保留某台旧主机的硬件、代理、
剩余磁盘或固定测试数量作为工程要求。

## 构建配置

| 项 | 配置来源 |
|---|---|
| Gradle | 本项目使用本地 Gradle 8.9 分发；仓库不带 wrapper |
| AGP / Kotlin | 根 `build.gradle.kts`：8.5.2 / 1.9.24 |
| JVM | 源码与 Kotlin 目标为 17；本机 Gradle JVM 可由 `java -version` / `gradle --version` 核对 |
| Android | `compileSdk=36`、`minSdk=26`、`targetSdk=28`，见 `app/build.gradle.kts` |
| ABI | `-Pabi` 指定，默认 `arm64-v8a`；模拟器可单独构建 x86_64，但内置引擎资产也必须匹配 |
| CMake | 3.22.1；构建 PTY/OCR 原生支持，NDK 以实际安装及构建选择为准 |
| Node / pnpm | 用于引擎源码、闭包与插件脚本；版本需满足对应引擎源码要求 |
| Python | 静态门禁主要用标准库；图像/海报/探针脚本可能额外需要 Pillow 等依赖 |

`targetSdk=28` 是当前执行本地运行时的兼容选择。升级 targetSdk 需要重新验证执行权限、
存储、前台服务、通知和安装流程，不能只改数字。
AGP 8.5.2 对 compileSdk 36 会给兼容性警告；release vital lint 当前关闭，静态验收不等于完整 lint。

## 主机准备

```sh
java -version
android info
gradle --version
```

SDK 默认为 `~/Android/Sdk`。本机 Gradle 分发路径：
`~/Projects/common/dsh-android-build/gradle-8.9/bin/gradle`。
多任务构建使用 `flock /tmp/dsh-gradle.lock <gradle> ...` 避免竞争。

先准备仓库所需 Termux/runtime、引擎层 zip、pnpm 及 vips 闭包资产，按
[ENGINE-BUILD.md](ENGINE-BUILD.md) 和脚本的输入要求执行。`android build` 不负责这些前置，
本项目继续使用 Gradle 组装，不用 android build 替代发布流程。

```sh
bash scripts/acceptance.sh --static
gradle testDebugUnitTest
gradle assembleRelease -Pabi=arm64-v8a
bash scripts/acceptance.sh --skip-build
```

`acceptance.sh --skip-build` 仍包含单测和 APK 断言；真机回归需显式指定设备并保存结果。
完整入口可用 `bash scripts/acceptance.sh`。

## 签名

优先从未入库的 `keystore.properties` 读取，或使用环境变量：
`DSH_KEYSTORE_FILE`、`DSH_KEYSTORE_PASSWORD`、`DSH_KEY_ALIAS`、`DSH_KEY_PASSWORD`。
未配置发布密钥时 release 构建回退 debug 签名；分发前用 apksigner 核对目标 APK。
不要提交或在日志中输出密码。

## 设备与调试

装机、启动、截图、布局、交互和官方文档查询优先 android CLI，详见仓库 `docs/ANDROID-CLI.md`。
截图先带编号采集，再按编号解析目标，不复用旧屏幕坐标。系统日志、端口和调试 provider 仍可用 adb。
Root/Shizuku 是特权测试条件，不是普通模式构建或悬浮窗的先决条件。

| 项 | 位置 |
|---|---|
| Termux 前缀 | `/data/user/0/com.termux/files/usr`（`$PREFIX`） |
| 引擎 home | `/data/user/0/com.termux/files/dsh-home`（`$DSH_HOME`） |
| 引擎层版本 | `$PREFIX/.dsh-engine-version` |
| 私有 bridge token | `$PREFIX/.bridge-token`；调试读取见仓库 `ADB-DEBUG-CHANNEL.md` |
| 平台补丁 | `$PREFIX/android-overlay.yml`，它是引擎配置 overlay，与屏幕悬浮窗不同 |
| Android 插件 | `$PREFIX/android-plugins/` |
| 日志 | `$PREFIX/engine.log`、logcat |

保留端口：3080 WebUI、3082 Shizuku 桥、3083 AgentBridge、3090 LAN 代理；可选本地语义服务使用 3085。
不要把临时服务绑定到这些端口。工作区真实落点用 `native storage` 确认，不能只看软链文本。

## 其他脚本

- `scripts/audit-apk.sh`：APK 权限、签名、组件、载荷与 URL 清单。
- `scripts/check-engine-deps.sh`：引擎依赖闭包审计。
- `scripts/smoke-device.sh`：设备交互与可选特权循环，运行前检查脚本要求。
- `scripts/probes/flicker/`：浮窗窗口/图像/输入取证，历史报告见仓库 `docs/qa/`。

宿主镜像、代理、SDK/NDK 路径可按本地网络与安装位置设置；不把旧局域网地址写成默认前提。
