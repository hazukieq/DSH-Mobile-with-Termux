#!/usr/bin/env bash
# bridge.sh — 免 root 驱动设备上的 DSH bridge（token / 端口 / 请求 / 悬浮窗状态）
#
# 为什么单独一个脚本：layers/frames/capture 这些依赖 root + SurfaceFlinger，
# 在 vivo 这类**无 root** 设备上跑不了；而 bridge 只要 adb 就能用。
#
# 用法:
#   ./bridge.sh token                 # 打印本进程有效 token（每次 App 启动会变）
#   ./bridge.sh forward               # adb forward tcp:3083（幂等）
#   ./bridge.sh ping                  # /diag → HTTP 码
#   ./bridge.sh state                 # provider 诊断（版本/pid/a11y/engine/overlay）
#   ./bridge.sh get  /screenshot      # GET 任意 bridge 路径
#   ./bridge.sh post /act '{"steps":[{"op":"skip"}]}'
#   ./bridge.sh overlay               # 自家悬浮窗窗口块（window 状态）
#   ./bridge.sh tap 540 1200          # 注入 tap（会走手势层避让）
#   ./bridge.sh shot  [/tmp/x.png]    # 截图并落到本地文件
#
# 环境变量: SERIAL / PKG / DSH_BRIDGE_PORT / DSH_TOKEN / DSH_TIMEOUT
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$HERE/lib.sh"

cmd=${1:-help}; shift || true

case "$cmd" in
  token)   dsh_token; echo ;;
  forward) dsh_forward; echo "forward tcp:$(dsh_bridge_port) ok" ;;
  ping)
    dsh_forward
    code=$(curl -s -m 8 -o /dev/null -w '%{http_code}' \
           -H "X-DSH-Token: $(dsh_token)" "http://127.0.0.1:$(dsh_bridge_port)/diag")
    echo "HTTP $code"
    [ "$code" = "200" ] || exit 1
    ;;
  state)   sh_ content query --uri "content://$PKG.adb/state" ;;
  get)     dsh_api GET "${1:?path required}" ;;
  post)    dsh_api POST "${1:?path required}" "${2:-{\}}" ;;
  overlay) overlay_block ;;
  tap)
    x=${1:?x required}; y=${2:?y required}
    dsh_api POST /gesture "{\"type\":\"tap\",\"x\":$x,\"y\":$y}" >/dev/null && echo "tap $x,$y dispatched"
    ;;
  shot)
    out=${1:-/tmp/dsh-shot-$(date +%s).png}
    dsh_api GET '/screenshot' \
      | python3 -c 'import sys,json,base64;d=json.load(sys.stdin);open(sys.argv[1],"wb").write(base64.b64decode(d["base64"]));print(d.get("via"),d.get("width"),d.get("height"))' "$out"
    echo "saved $out"
    ;;
  help|*)
    sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
