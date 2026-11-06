#!/usr/bin/env bash
# lib.sh — flicker 取证工具公共层（被 layers/frames/window/input/logcat/record/capture 引用）
#
# 设计约束（本项目实测，Android 16 / LineageOS 23.2 / M2012K11AC）:
#   * dumpsys 必须**在设备上**跑完再解析，只回 CSV；否则 12Hz × 1000 行的原文会压垮 adb 并污染时间戳。
#   * 一律 root（`su -c`）：SurfaceFlinger 的 Output Layer / buffer 句柄 / reuse 计数非 root 会被裁剪。
#   * 只读：本工具集**不注入输入、不改动系统/应用状态**，可与其他装机/交互测试并行（但采样本身有 ~10% 观测者开销）。
#
# 环境变量（都可覆盖）:
#   SERIAL=5f08bc20        设备序列号
#   PKG=com.termux         被测应用包名（悬浮窗窗口名 = 裸包名）
#   EVID=/tmp/flicker-evidence   证据目录
#   DEV=/data/local/tmp/flicker  设备侧工作目录
#   DUR=10                 默认采样时长（秒）
set -uo pipefail

ADB=${ADB:-adb}
SERIAL=${SERIAL:-5f08bc20}
PKG=${PKG:-com.termux}
EVID=${EVID:-/tmp/flicker-evidence}
DEV=${DEV:-/data/local/tmp/flicker}
DUR=${DUR:-10}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

log()  { printf '[*] %s\n' "$*" >&2; }
warn() { printf '[!] %s\n' "$*" >&2; }
die()  { printf '[x] %s\n' "$*" >&2; exit 1; }

adb_() { "$ADB" -s "$SERIAL" "$@"; }
sh_()  { "$ADB" -s "$SERIAL" shell "$@"; }
# root：Magisk 上 `su -c '...'` 是实测可用的形式；不要用 `su 0`（本机 su 是 Magisk applet）
root_() { "$ADB" -s "$SERIAL" shell "su -c '$*'"; }

flicker_env() {
  command -v "$ADB" >/dev/null || die "找不到 adb"
  mkdir -p "$EVID"
  TS_RUN=$(date +%Y%m%d-%H%M%S)
  export TS_RUN
}

flicker_check() {
  local st
  st=$(sh_ getprop ro.build.version.sdk | tr -d '\r')
  log "device=$SERIAL sdk=$st"
  local id
  id=$(root_ id 2>/dev/null | tr -d '\r')
  case "$id" in
    uid=0*) log "root: $id" ;;
    *) die "root 不可用（需要 Magisk）：$id" ;;
  esac
}

flicker_push() {
  adb_ push "$HERE/device/parse_list.awk"      "$DEV/" >/dev/null
  adb_ push "$HERE/device/parse_sf.awk"        "$DEV/" >/dev/null
  adb_ push "$HERE/device/parse_win.awk"       "$DEV/" >/dev/null
  adb_ push "$HERE/device/parse_input.awk"     "$DEV/" >/dev/null
  adb_ push "$HERE/device/parse_timestats.awk" "$DEV/" >/dev/null
  adb_ push "$HERE/device/sampler.sh"          "$DEV/" >/dev/null
  root_ chmod 755 "$DEV/sampler.sh" || true
  log "pushed -> $DEV"
}

# 解析目标 layer id：--list 里 `... com.termux#<tokenId>` 是 WindowToken 层，
# 而 surface 层名是空的，只能用它 parentId=<tokenId> 反查。
# 输出: "TID TKN"（写进 $EVID/meta.env）
flicker_discover() {
  local list tkn tid
  list=$(root_ dumpsys SurfaceFlinger --list | tr -d '\r')
  tkn=$(printf '%s\n' "$list" | awk -v p="$PKG" '
    index($0, p "#") > 0 { s = substr($0, index($0, p "#") + length(p) + 1); sub(/[^0-9].*/, "", s); print s; exit }')
  if [ -z "$tkn" ]; then warn "没找到 $PKG 的 WindowToken 层（悬浮窗当前不在？）"; echo "  " > "$EVID/meta.env"; return 1; fi
  tid=$(printf '%s\n' "$list" | awk -v t="$tkn" 'index($0, "parentId=" t "}") > 0 { s = substr($0, 21); sub(/#.*/, "", s); if (s == "") { match($0, /#[0-9]+/); print substr($0, RSTART + 1, RLENGTH - 1); exit } }')
  printf 'TID=%s\nTKN=%s\nPKG=%s\nSERIAL=%s\n' "$tid" "$tkn" "$PKG" "$SERIAL" > "$EVID/meta.env"
  log "target: surface_layer=$tid token_layer=$tkn"
  echo "$tid $tkn"
}

flicker_target() {   # 读 meta.env 拿 TID/TKN
  local f="$EVID/meta.env"
  [ -f "$f" ] || flicker_discover >/dev/null || return 1
  # shellcheck disable=SC1090
  . "$f"; echo "${TID:-} ${TKN:-}"
}

# 跑一次设备侧采样：flicker_sample <kind> <dur> <out.csv> [tid] [tkn] [pt]
flicker_sample() {
  local kind=$1 dur=$2 out=$3 tid=${4:-} tkn=${5:-} pt=${6:-}
  root_ "sh $DEV/sampler.sh $kind $dur $tid $tkn $pt" > "$out"
  local n; n=$(wc -l < "$out")
  log "$kind -> $out (${n} lines)"
}

# 窗口中心（给 input.sh 的诊断点用）：从 window dump 的 frame=[l,t][r,b] 取中心
flicker_center() {
  local frame=$1
  printf '%s' "$frame" | awk '{
    if (match($0, /\[[0-9]+ [0-9]+\]\[[0-9]+ [0-9]+\]/)) {
      s = substr($0, RSTART, RLENGTH); n = split(s, a, /[^0-9]+/)
      # a: "", l, t, r, b
      print int((a[2]+a[4])/2) "," int((a[3]+a[5])/2)
    } else print ""
  }'
}
