#!/usr/bin/env bash
# 启动 USB 桥（脱离当前 shell，用 systemd-run --user 保活；已装 systemd 的机器）
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
NODE="${NODE:-$(command -v node)}"
systemctl --user stop dsh-usb-bridge 2>/dev/null || true
exec systemd-run --user --unit=dsh-usb-bridge --collect \
  --setenv=DSH_BRIDGE_PORT="${DSH_BRIDGE_PORT:-9100}" \
  --setenv=PATH="$PATH" \
  "$NODE" "$DIR/bridge.mjs"
