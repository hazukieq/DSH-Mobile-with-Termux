#!/usr/bin/env bash
# frames.sh — Tier2「合成器真值」采样：完整 `dumpsys SurfaceFlinger`
#
# 答的问题：**这一帧到底画没画、画在哪、是不是复用旧帧**。单次 70ms → 12Hz。
# 四类「闪」里它能直接判三类：
#   seen=0（--list 有、这里没有 Output Layer/HWC 行）        → 层没进入合成 = (b) 无内容
#   comp_vis=visible 但 nt_cnt=0（visibleNonTransparentRegion 空）→ 区域在但全透明 = (b)
#   dispframe 相邻采样变化 / hwc_dispframe≠bounds            → (c) 位置尺寸跳变
#   reused_client=true 或 clientCompositionReusedFrames 递增 → (d) 复用了上一帧 client target
#   fbcounter 不变而 dispframe 不变 → 屏幕没被重画（真静止，不是闪）
#
# ⚠ 坑（本机实测）:
#   1) `bounds={l,t,b,r}` —— **字段顺序是 left,top,bottom,right**，别当 [l,t][r,b] 用！
#   2) Composition list 只有真正参与合成的层；`Input list` 里有全部带 input 信息的层，并给出
#      `invisible reason=nothing to draw` / `invisible reason=hidden by parent or layer flag`。
#      **这两个 reason 就是 (b) 的直接字符串证据**，务必要捞。
#   3) 未知参数（--frame-stats/--help）会被当默认命令**回落成完整 dump**（慢 15 倍），不要用。
#
# 用法: ./frames.sh [秒数] [输出csv]
set -uo pipefail
. "$(dirname "$0")/lib.sh"
flicker_env; flicker_check
dur=${1:-$DUR}
out=${2:-$EVID/frames-$TS_RUN.csv}
read -r tid tkn <<<"$(flicker_target)" || true
[ -n "${tid:-}" ] || die "拿不到 layer id"
flicker_sample sf "$dur" "$out" "$tid"
echo "$out"
