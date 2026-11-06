#!/usr/bin/env bash
# layers.sh — Tier1「层对象存在性」采样：`dumpsys SurfaceFlinger --list`
#
# 答的问题：**层还在不在**（(a) 窗口真被摘掉，还是只是不画）。
#   exists=1  → 层对象还在（SF 侧没销毁），闪只可能是「没内容/没合成」→ (b)/(d)
#   exists=0  → 层对象消失 → (a) 真摘除（removeView / 窗口销毁 / 进程死）
# 单次 46ms，循环可达 17~23Hz，是本工具集**采样率最高**的证据。
#
# ⚠ 坑：--list 里层名带 `#<id>` 后缀、且无障碍悬浮窗的 surface 层名是**空串** → 只能按 id 认。
#      --list 列出**全部 requested layer（含完全不可见/无 surface）**，所以它只证明「对象存在」，不证明「在画」。
#
# 用法: ./layers.sh [秒数] [输出csv]        （默认 10s、$EVID/layers-<run>.csv）
set -uo pipefail
. "$(dirname "$0")/lib.sh"
flicker_env; flicker_check
dur=${1:-$DUR}
out=${2:-$EVID/layers-$TS_RUN.csv}
read -r tid tkn <<<"$(flicker_target)" || true
[ -n "${tid:-}" ] || die "拿不到 layer id（悬浮窗不在场？先用 ./capture.sh --discover 看）"
flicker_sample list "$dur" "$out" "$tid" "$tkn"
echo "$out"
