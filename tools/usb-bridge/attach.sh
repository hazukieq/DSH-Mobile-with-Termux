#!/usr/bin/env bash
# 给已连接的 Android 设备建立 adb reverse 隧道：手机 127.0.0.1:9100 -> 电脑 127.0.0.1:9100
set -uo pipefail
PORT="${DSH_BRIDGE_PORT:-9100}"
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device"{print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then echo "no device"; exit 1; fi
for d in "${DEVICES[@]}"; do
  adb -s "$d" reverse --remove-all 2>/dev/null || true
  if adb -s "$d" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
    echo "attached: $d 127.0.0.1:$PORT -> host 127.0.0.1:$PORT"
  else
    echo "failed to attach: $d"
  fi
done
