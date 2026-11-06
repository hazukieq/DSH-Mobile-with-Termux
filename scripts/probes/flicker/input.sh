#!/usr/bin/env bash
# input.sh — Tier1「输入可达性」采样：`dumpsys input`
#
# 答的问题：**点得到吗、点被谁吃了**。单次 72ms。
#   not_touchable=1 / not_visible=1     → inputConfig 里带 NOT_TOUCHABLE / NOT_VISIBLE，系统压根不会投给它
#   covers_point=0                      → 诊断点不在 touchableRegion 里（窗口挪走/缩了/region 被改）
#   stack_at_point 里目标不在第一位      → 点在物理上落到了更高层的窗口（手势区、系统 overlay、launcher）
#   focused_window 是别的应用            → 焦点被夺（悬浮窗本身 NOT_FOCUSABLE，通常不是问题）
#   touch_active=1 时的 touch_state      → 按住那一刻 SF/IMS 记录的真实投递状态（最硬的证据）
#
# ⚠ 坑（本机实测）:
#   1) 窗口行里 frame / touchableRegion 都含逗号 → 不能 split(",")，解析器按 `字段名=` 抠。
#   2) `id=` 与 SurfaceFlinger `#<layerId>` 同一编号 → 跨工具 join key；行首索引就是 z 序（0 最顶）。
#   3) RecentQueue 只有事件类型+age，**没有投递目标**；要证「投给了谁」得靠 touch_state（按住期间）
#      或 stack_at_point 的静态推断。系统手势层（PointerEventDispatcherOverlay / [Gesture Monitor]）排前面属正常噪声。
#
# 用法: ./input.sh [秒数] [输出csv] [x,y]
set -uo pipefail
. "$(dirname "$0")/lib.sh"
flicker_env; flicker_check
dur=${1:-$DUR}
out=${2:-$EVID/input-$TS_RUN.csv}
pt=${3:-}
read -r tid tkn <<<"$(flicker_target)" || true
if [ -z "$pt" ] && [ -n "${tid:-}" ]; then
  fr=$(root_ "dumpsys window windows" | tr -d '\r' | awk -v t="$tid" '
    index($0, "(" t ")/") > 0 || index($0, "#" t ")") > 0 { want = 1 }
    want && /Frames: / { if (match($0, /frame=\[[^]]*\]\[[^]]*\]/)) { print substr($0, RSTART + 6, RLENGTH - 6); exit } }' || true)
  [ -n "$fr" ] && pt=$(flicker_center "$fr")
fi
log "diagnostic point: ${pt:-<none>}"
flicker_sample input "$dur" "$out" "${tid:-}" "" "${pt:-}"
echo "$out"
