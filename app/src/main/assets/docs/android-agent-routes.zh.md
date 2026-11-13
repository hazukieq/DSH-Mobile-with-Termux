# Android 运行环境选择

本项目将 DeepSeek Harness 与 Termux 用户态打包进 `com.termux`，运行 Android/bionic 原生程序，
不启动 proot Linux 发行版。额外工具通过 Termux `pkg`/`apt` 安装，没有一键环境扩展商店。
引擎层在本地构建、校验；仓库没有 CI 工作流。

手动 Termux、社区初始化脚本和 proot 发行版是其他交付方式。本文不再维护第三方方案的
兼容性、安装成本、体积或性能比较，旧版宣传表不能作为当前选择依据。

本项目的具体实现见 [终端与运行时](TERMINAL_AND_LINUX.md)、[引擎构建](ENGINE-BUILD.md)、
[无障碍边界](A11Y-SCOPE.md) 和 `assets/agents/DSH-MOBILE.md` 的能力清单。
AgentBridge 路由的事实来源是 `bridge/Router.kt`。
