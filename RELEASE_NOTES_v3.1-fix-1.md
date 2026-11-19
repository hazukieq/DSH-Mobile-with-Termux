# DSH Mobile 3.1-fix-1

3.1 的修复版：让 agent 在手机内直接写 `/tmp` 不再失败，终端配置在设备上第一次真正生效，
并把默认 apt 源换成实测最快的阿里云。

## 安装与升级

- APK：`dsh-mobile-3.1-fix-1-arm64-v8a.apk`；应用版本 `3.1-fix-1`，versionCode `689`。
- 沿用原发布签名（证书 SHA-256 `178dc39b…06401`），包名 `com.termux`；同签名旧版可覆盖升级并保留数据。
- 内置引擎仍为 `0.2.2-rc.1+sharp1`，不会替换用户自建的 rc.4 引擎。
- `SHA256SUMS` 校验附件；`engine-manifest.json` 记录 APK 与内置载荷溯源，**不是引擎热更新 feed**。

## `/tmp` 透明映射

- 新增预载层 `libdsh-pathmap.so`：拦截 libc 的 `open/stat/mkdir/chmod/execve` 等入口，把
  `/tmp`、`/var/tmp`、`/dev/shm`、`/run` 重写到 `$DSH_TMPDIR`（引擎的 `usr/tmp`）。
- 覆盖 NDK r24+ 的 fortify 入口（`__open_2` / `__openat_2`），否则 coreutils 会出现
  「写成功、`cat` 却 ENOENT」的路径分裂。
- 同一 `LD_PRELOAD` 链串上 Termux 的 `libtermux-exec`：`#!/bin/sh`、`#!/usr/bin/env bash`
  脚本即使解释器路径不存在也能直接执行。
- 引擎、终端、Provider exec 环境统一注入；`record`、`cdp` 日志改用 `$TMPDIR`。
- 限制：只对动态链接进程生效；静态二进制与直接 syscall 不经过映射层。

## 终端与 zsh

- 新设备首启后台补装 `zsh` + `zsh-completions`（失败退避 7 天），终端不再默默回落 bash。
- 修复 `.zshrc` 版本漂移：版本号改为从随包资产首行解析，不再每次启动重写、不再冲掉保留标记的本地修改。
- 修复应用自述文档的同类漂移：`DSH-MOBILE.md` 版本同样取自资产正文，重启不再重复部署。

## 默认镜像源

- apt 默认源由清华 TUNA 改为阿里云（`https://mirrors.aliyun.com/termux/termux-main`）。
  2026-11-20 实测（host + vivo，`Packages.gz` 5 轮中值）：阿里云 4.2–5.3 MB/s，
  中科大 1.5–2.1 MB/s，南大 0.7–1.7 MB/s，交大 0.3–1.5 MB/s，清华/北外 403（本网络被拒）。
- 旧版写入的 TUNA `sources.list` 会在启动时自动迁移；用户自己改过、删掉 App 标记的文件不碰。
- 注释里保留中科大与南大作为手动替代。

## 清理

- 移除过期的 mobile-ui 客户端皮肤工具与文档残留（`tools/mobile-ui/`，约 5000 行），
  并保留构建/安装门禁防止回流。

## 验证与范围

- arm64 Release 构建、发布签名校验、37 项 Release JVM 单测、7 项纪律提醒回归、
  15 项引擎升级回归与完整验收（22 项）通过。
- vivo V2520A / Android 17（API 37）以 Normal 模式和应用 UID 10487 验证，不调用 Root 或 Shizuku；
  浮窗回归 instrumentation 全部通过。
- Redmi 本次未连接，未在 Redmi 上重跑；vivo 的无障碍服务未启用，点击类能力未纳入本版验证。
- 详细证据见 `docs/qa/release-3.1-fix-1-verification.md`。
- 未评测模型长期执行的遵循率；`/tmp` 映射与 zsh 自动安装依赖动态链接进程与网络可用性。
