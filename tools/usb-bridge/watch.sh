#!/usr/bin/env bash
# 每 10s 检查一次 adb 设备并重建 reverse 隧道（插拔 USB 自动恢复）。
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
while true; do
  "$DIR/attach.sh" >/dev/null 2>&1 || true
  sleep 10
done
