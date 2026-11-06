#!/usr/bin/env bash
# window.sh — Tier1「WMS / View 侧」采样：`dumpsys window windows`
#
# 答的问题：**应用自己以为窗口是什么状态**。单次 68~90ms → 14Hz。
#   mViewVisibility=0x0 + surface_shown=true          → View 可见、surface 已显示
#   mViewVisibility=0x4（INVISIBLE）/ surface_shown=false → 走了 view 级隐藏（本项目 StatusOverlay 的主路径）
#   found=0（整个窗口块消失）                          → 真 removeView / 窗口销毁 → (a)
#   frame≠last_frame，或相邻采样 frame 变化             → (c) 位置/尺寸跳变
#   mHasSurface=false / ready_for_display=false         → (b) 有窗口没 surface
#
# ⚠ 坑（本机实测）:
#   1) `ty=` 打的是**符号名** ACCESSIBILITY_OVERLAY，数值 2032 只在 `mToken=WindowToken{... type=2032 ...}` 里。
#   2) mAttrs 的 (x,y) 相对 parent frame（status bar 高 95）→ 真实矩形只看 `frame=[l,t][r,b]`。
#   3) 无障碍悬浮窗块里**没有** touchable region 行 → 可达性必须看 input.sh。
#
# 用法: ./window.sh [秒数] [输出csv]
set -uo pipefail
. "$(dirname "$0")/lib.sh"
flicker_env; flicker_check
dur=${1:-$DUR}
out=${2:-$EVID/window-$TS_RUN.csv}
read -r tid tkn <<<"$(flicker_target)" || true
flicker_sample win "$dur" "$out" "${tid:-}"
echo "$out"
