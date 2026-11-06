#!/usr/bin/env bash
# capture.sh — 一条命令跑完「基线采集」：logcat 事件时钟 + 4 路并行 dumpsys 采样 + 可选录像 + 自动判定
#
# 这是给 lead / 复验同学**直接照抄跑**的入口。只读，不改设备状态（除 screenrecord 会在 /data/local/tmp 落 mp4）。
#
# 用法:
#   ./capture.sh                 # 默认 10s，4 路采样 + logcat
#   ./capture.sh 20              # 20s
#   ./capture.sh 15 --record     # 加 screenrecord（更重，但能给视觉真值）
#   ./capture.sh 15 --light      # 只跑 layers+frames（最轻，装机/复验时用这个）
#   ./capture.sh 15 --calm       # 含 input+window 但 4 路错峰启动，降低观测者效应
#
# 产物（$EVID/<run>/）: layers.csv frames.csv window.csv input.csv logcat.txt rec.mp4 recframes.csv
#                       report.md（判定表 + 时间线 + 闪烁事件）
set -uo pipefail
. "$(dirname "$0")/lib.sh"
secs=$DUR; record=0; lanes="list sf window input"
while [ $# -gt 0 ]; do
  case "$1" in
    --record) record=1 ;;
    --light)  lanes="list sf" ;;
    --calm)   lanes="list sf window input" ; calm=1 ;;
    --lanes=*) lanes=${1#*=} ;;
    -*) die "unknown option $1" ;;
    *) secs=$1 ;;
  esac
  shift
done
flicker_env; flicker_check; flicker_push
EVID_RUN="$EVID/run-$TS_RUN"; mkdir -p "$EVID_RUN"
export EVID="$EVID_RUN"
export LOGCAT_OUT="$EVID_RUN/logcat.txt"   # logcat.sh 里 TS_RUN 会重新生成，必须显式指定落点
read -r tid tkn <<<"$(flicker_discover)" || true
[ -n "${tid:-}" ] || warn "悬浮窗不在场：只能采到 GONE 基线（先让 lead 触发一个 turn）"
pt=""
if [ -n "${tid:-}" ]; then
  fr=$(root_ "dumpsys window windows" | tr -d '\r' | awk -v t="$tid" '
    index($0, "#" t ")") > 0 { want = 1 }
    want && /Frames: / { if (match($0, /frame=\[[^]]*\]\[[^]]*\]/)) { print substr($0, RSTART + 6, RLENGTH - 6); exit } }' || true)
  [ -n "$fr" ] && pt=$(flicker_center "$fr")
fi
log "run dir: $EVID_RUN  duration=${secs}s  lanes=[$lanes]  tid=${tid:-none}  pt=${pt:-none}"

./logcat.sh start >/dev/null 2>&1 || true
pids=()
i=0
for lane in $lanes; do
  i=$((i+1))
  [ "${calm:-0}" = 1 ] && sleep 0.$((i*2))
  case "$lane" in
    list)  ./layers.sh "$secs" "$EVID_RUN/layers.csv"  >/dev/null 2>&1 & ;;
    sf)    ./frames.sh "$secs" "$EVID_RUN/frames.csv"  >/dev/null 2>&1 & ;;
    window) ./window.sh "$secs" "$EVID_RUN/window.csv" >/dev/null 2>&1 & ;;
    input) ./input.sh  "$secs" "$EVID_RUN/input.csv" "${pt:-}" >/dev/null 2>&1 & ;;
    *) warn "skip unknown lane $lane" ;;
  esac
  pids+=($!)
done
[ "$record" = 1 ] && { ./record.sh "$secs" >/dev/null 2>&1 & pids+=($!); }
for p in "${pids[@]}"; do wait "$p" 2>/dev/null || true; done
./logcat.sh stop >/dev/null 2>&1 || true

log "analyzing..."
python3 "$HERE/analyze.py" "$EVID_RUN" | tee "$EVID_RUN/report.md"
echo
log "report: $EVID_RUN/report.md"
