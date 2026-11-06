#!/system/bin/sh
# sampler.sh — 设备侧高频采样器（由 host 侧脚本 push 到 /data/local/tmp/flicker/ 后用 root 跑）
#
# 为什么在设备上跑：dumpsys 原文一轮就上千行，12Hz 上行会先把 adb 拖死、且时间戳会被传输抖动污染。
# 这里「采集 + awk 解析」在同一个管道里完成，只把几千字节的 CSV 回传。
# 需要 root：SurfaceFlinger 的部分 section（Output Layer / buffer 句柄 / reusecounter）非 root 会被裁剪。
#
# 用法:
#   sh sampler.sh <kind> <duration_s> [layer_id] [token_layer_id] [pt]
#     kind   = list | sf | win | input | timestats
#     layer_id       surface 层 id（= dumpsys input 的 id = SF 里的 #id）；list 之外都用它精确定位
#     token_layer_id 仅 list 用：WindowToken 层 id（`... 73085c8 com.termux#10473` 里的 10473）
#     pt     仅 input 用：诊断坐标 "x,y"（一般取窗口中心）
#
# 输出: CSV 到 stdout（awk 打表头）。每轮先 `@@<epoch_ms>` 再 dumpsys 原文。
# 退出: 0 正常 / 1 参数错 / 2 缺解析脚本
set -u
DIR=${FLICKER_DIR:-/data/local/tmp/flicker}
kind=${1:-}; dur=${2:-}; tid=${3:-}; tkn=${4:-}; pt=${5:-}
[ -n "$kind" ] && [ -n "$dur" ] || { echo "usage: sampler.sh <kind> <dur_s> [layer_id] [token_id] [x,y]" >&2; exit 1; }

case "$kind" in
  list)      prog="$DIR/parse_list.awk";      cmd="dumpsys SurfaceFlinger --list" ;;
  sf)        prog="$DIR/parse_sf.awk";        cmd="dumpsys SurfaceFlinger" ;;
  win)       prog="$DIR/parse_win.awk";       cmd="dumpsys window windows" ;;
  input)     prog="$DIR/parse_input.awk";     cmd="dumpsys input" ;;
  timestats) prog="$DIR/parse_timestats.awk"; cmd="dumpsys SurfaceFlinger --timestats -dump" ;;
  *) echo "unknown kind: $kind" >&2; exit 1 ;;
esac
[ -f "$prog" ] || { echo "missing $prog" >&2; exit 2; }

set -- awk -f "$prog"
case "$kind" in
  list)      [ -n "$tid" ] && set -- "$@" -v "tid=$tid"; [ -n "$tkn" ] && set -- "$@" -v "tkn=$tkn" ;;
  sf)        set -- "$@" -v "tid=${tid:-}" ;;
  win)       set -- "$@" -v "tid=${tid:-}" -v "pkg=com.termux" ;;
  input)     set -- "$@" -v "tid=${tid:-}" -v "pt=${pt:-}" ;;
esac

end=$(( $(date +%s) + dur ))
(
  while [ "$(date +%s)" -lt "$end" ]; do
    printf '@@%s\n' "$(date +%s%3N)"
    $cmd 2>/dev/null
  done
) | "$@"
