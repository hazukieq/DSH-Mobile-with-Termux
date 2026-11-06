#!/usr/bin/env bash
# logcat.sh — Tier0「事件时钟」：app 自己打的显示/隐藏日志（毫秒级、带 reason 字符串）
#
# 为什么它是 Tier0：dumpsys 最快也只有 ~17Hz，而「闪」可能只持续 1~2 帧。日志里
# `hide(capture)` / `show(capture)` / `capture avoid: begin` / `opsAvoid on/off` / `overlay torn down`
# 这些**带 reason 的成对事件**才是精确的事件边界；dumpsys 采样用来给每条事件配「状态快照」。
#
# 已知事件（源码 StatusOverlay.kt + 现场 logcat）:
#   StatusOverlay: hide(<reason>): visibility=4 (...)      reason = capture / ops / auto / user ...
#   StatusOverlay: show(<reason>): visibility=0 (...)
#   StatusOverlay: capture avoid: begin (...) / capture restore: done (...)
#   StatusOverlay: opsAvoid on/off / opsAvoid watchdog fired (endOpsAvoid missed) — force show
#   StatusOverlay: overlay hidden (auto) / overlay torn down (engine exited)
#   EngineService: task removed: exiting completely (engine + overlay)
#   DshA11y: keepalive: engine service not running, restarting
#
# 用法:
#   ./logcat.sh start          # 后台开始录（写 $EVID/logcat-<run>.txt 与 .pid）
#   ./logcat.sh stop
#   ./logcat.sh dump 10        # 前台录 N 秒
#   ./logcat.sh clear          # 清 logcat 缓冲（**会动系统状态**，默认不给用，仅显式传 --yes）
set -uo pipefail
. "$(dirname "$0")/lib.sh"
flicker_env
FILT='StatusOverlay:V DshA11y:V EngineService:V AndroidRuntime:E ActivityManager:I WindowManager:I ActivityTaskManager:I *:S'
case "${1:-dump}" in
  start)
    out="$EVID/logcat-$TS_RUN.txt"; : > "$out"
    adb_ logcat -v epoch $FILT > "$out" 2>&1 &
    echo $! > "$EVID/.logcat.pid"; echo "$out"; log "logcat recording -> $out" ;;
  stop)
    if [ -f "$EVID/.logcat.pid" ]; then kill "$(cat "$EVID/.logcat.pid")" 2>/dev/null || true; rm -f "$EVID/.logcat.pid"; fi
    log "logcat stopped" ;;
  dump)
    secs=${2:-10}; out="$EVID/logcat-$TS_RUN.txt"
    timeout "$((secs + 2))" "$ADB" -s "$SERIAL" logcat -v epoch $FILT > "$out" 2>&1 || true
    log "logcat $secs s -> $out ($(wc -l < "$out") lines)"; echo "$out" ;;
  clear)
    [ "${2:-}" = "--yes" ] || die "clear 会清空系统日志缓冲，需显式 ./logcat.sh clear --yes"
    adb_ logcat -c && log "cleared" ;;
  *) die "usage: logcat.sh start|stop|dump <secs>|clear --yes" ;;
esac
