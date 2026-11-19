# 3.1-fix-1 发布验证（2026-11-20）

对应 `RELEASE_NOTES_v3.1-fix-1.md` 的「验证与范围」。全程 ADB + 应用 UID，不调用 Root / Shizuku。
本版未改浮窗代码，浮窗回归仍按惯例在 vivo 上跑了一遍（PASS），Redmi 未连接、未重跑。

## 环境与安装

| 项 | 值 |
| --- | --- |
| 设备 | vivo V2520A（PD2520），Android 17 / API 37 |
| 应用 | `com.termux` 3.1-fix-1（versionCode 689） |
| 安装 | 覆盖安装保留数据；`firstInstallTime=2026-10-28 13:07:43`，`lastUpdateTime=2026-11-20 04:11:09` |
| 应用 UID / 模式 | `10487` / `normal` |
| 引擎 | 设备自建 `0.2.2-rc.4+local1`（APK 内置 `0.2.2-rc.1+sharp1`，未替换） |
| Node | `v24.18.0` |
| zsh | `5.9.2 (aarch64-unknown-linux-android)` |
| 签名 | `178dc39b…06401`，与仓库 release keystore 一致 |

## 验收与回归

```
bash scripts/acceptance.sh --device 10CG1C1M1H0021Z   → PASS (22 项)
```

包含：8 项静态门禁、37 项 JVM 单测、`assembleRelease`、9 项 APK 断言（含
`lib/arm64-v8a/libdshpathmap.so`）、覆盖安装并启动、真机引擎/隔离升级回归、真机 `/tmp` 映射。

浮窗 instrumentation（`com.termux.test/...OverlayRegressionInstrumentation`）：

```text
INSTRUMENTATION_RESULT: appUid=10487
INSTRUMENTATION_RESULT: privMode=normal
INSTRUMENTATION_RESULT: stream=PASS: attach, expand, final frame, overlapping avoidance, callback cleanup, content restore, preview, off, exit/resume
INSTRUMENTATION_CODE: -1
```

记录见 `docs/qa/artifacts/release-3.1-fix-1-2026-11-20/overlay-instrumentation.txt`。

## `/tmp` 映射

```
PASS uid=10487 tmpdir=/data/user/0/com.termux/files/usr/tmp
PASS pathmap: /tmp is writable through $TMPDIR
```

- 覆盖 bash/coreutils `cat`、node、python、`mkdir -p`、`rm -rf`、`chmod +x` 与
  `#!/bin/sh` / `#!/usr/bin/env bash` 脚本执行；文件实际落盘在 `$PREFIX/tmp`。
- 预载层从 APK 解出到 `$PREFIX/lib/libdsh-pathmap.so`（15416 bytes），删除后重启会自愈重建。
- 记录见 `docs/qa/artifacts/release-3.1-fix-1-2026-11-20/pathmap-device-test.txt`。

## 默认 apt 源

`sources.list`（旧 TUNA 带 App marker，启动时迁移）：

```text
# DSH Mobile writes this once; edit freely afterwards.
# Alternatives: https://mirrors.ustc.edu.cn/termux/termux-main
#               https://mirrors.nju.edu.cn/termux/apt/termux-main
deb https://mirrors.aliyun.com/termux/termux-main stable main
```

`apt update` 实测 **2s** 完成（此前 TUNA 在本网络返回 403）。记录见
`docs/qa/artifacts/release-3.1-fix-1-2026-11-20/apt-update.txt`。

## 终端与 zsh

- `$PREFIX/bin/zsh` 存在时终端标签为 `Android (zsh)`；`zsh -i -c` 验证：
  `alias ll='ls -l'`、`ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE=fg=242`、`.zshrc` 版本标记 v7。
- 新设备首启后台自动 `pkg install -y zsh zsh-completions`；已实测该命令在 vivo 上 57s 完成，
  失败写 `.dsh-zsh-install` 时间戳退避 7 天。
- `DSH-HOME/DSH-MOBILE.md` 为 `app doc v81`，重启两次确认不再重复部署。

## 范围与限制

- Redmi M2012K11AC 本次未连接，未在其上重跑；vivo 无障碍服务未启用，点击类能力未纳入本版验证。
- `/tmp` 映射只对动态链接进程生效（静态二进制/裸 syscall 不经过）；zsh 自动安装需要网络。
- 未评测模型长期执行遵循率；未发布独立引擎 tar / 在线更新 feed。
