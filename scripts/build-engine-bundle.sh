#!/usr/bin/env bash
# 构建 dsh 引擎热更新包（dsh-update pull 用）+ 清单。
#
# 自 2026-10-18 第二轮起，引擎层是**自包含**的（node/rg 走 wrapper + 私有 lib 目录），
# 因为新装机器上 bootstrap 没有 node 的依赖库（libcares/libicu/libssl…），
# 老布局会直接 "CANNOT LINK ... libcares.so not found"。布局与构建逻辑统一在
# scripts/build-engine-layer.sh，这里只是保持旧参数入口（CI 与手工命令不变）。
#
# 用法:
#   scripts/build-engine-bundle.sh --runtime runtime-arm64.zip --arch aarch64 --out /tmp/engine-release
# 产物:
#   <out>/engine-dsh-<arch>.tar.gz / .zip / engine-manifest.json
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$REPO_DIR/scripts/build-engine-layer.sh" "$@"
