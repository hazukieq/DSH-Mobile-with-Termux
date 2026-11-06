# parse_discover.awk — 从 `dumpsys window windows` 里认出「本 app 的悬浮窗」并给出其 layer id
#
# 为什么要单独一个解析器：
#   app 在**有 SYSTEM_ALERT_WINDOW 权限时用 TYPE_APPLICATION_OVERLAY(2038)**，否则回落
#   ACCESSIBILITY_OVERLAY(2032)（StatusOverlay.kt:167-198）。两者窗口名完全不同：
#     2032: `Window{73085c8 u0 com.termux}:`                    ← 裸包名（无 title，层名空）
#     2038: `Window{619e98a u0 Sys2038:com.termux/app.dsh.mobile.MainActivity}:`
#   所以**只认 ty=2032 会把 2038 全判成 GONE（假阳性，实测 300/300）**。
#   唯一稳的判据：`package=com.termux` 且 window type ∈ {2032, 2038}。
#   另需排除主 Activity 窗口（package 同样是 com.termux，但 ty=BASE_APPLICATION、appop=NONE）
#   和 SYSTEMUI 的 APPLICATION_OVERLAY（ShellDropTarget 等，package≠com.termux）。
#
# 输入: dumpsys window windows 原文
# 输出: 每行一个匹配窗口（通常 1 行）: layer_id,tok_type,win_hash,name,appop,package,ty,view_vis,shown
# 用法: awk -f parse_discover.awk -v pkg=com.termux < window.txt
BEGIN {
  OFS = ","; pkg = (pkg == "" ? "com.termux" : pkg)
  blk = ""; inblk = 0
}
/^  Window #[0-9]+ Window\{/ { flush(); inblk = 1; blk = $0; next }
inblk { blk = blk "\n" $0 }
END { flush() }

function flush(   name, hash, layer_id, tok_type, ty, package, appop, vis, shown, cand) {
  if (blk == "") return
  name = ""; hash = ""; layer_id = ""; tok_type = ""; ty = ""; package = ""; appop = ""; vis = ""; shown = ""
  s = blk; sub(/^  Window #[0-9]+ Window\{/, "", s); sub(/\}:.*$/, "", s)
  if (match(s, /^[0-9a-f]+ /)) { hash = substr(s, 1, RLENGTH - 1); name = substr(s, RLENGTH + 1) }
  if (match(name, /^u[0-9]+ /)) name = substr(name, RLENGTH + 1)
  if (match(blk, /mSurface=Surface\(name=[^)]*\)/)) {
    r = substr(blk, RSTART, RLENGTH)
    if (match(r, /#[0-9]+/)) layer_id = substr(r, RSTART + 1, RLENGTH - 1)
  }
  if (match(blk, /mToken=WindowToken\{[^}]*type=[0-9]+/)) {
    r = substr(blk, RSTART, RLENGTH); sub(/.*type=/, "", r); tok_type = r
  }
  if (match(blk, /ty=[A-Z_]+/)) { ty = substr(blk, RSTART + 3, RLENGTH - 3) }
  if (match(blk, /package=[A-Za-z0-9_.]+/)) { package = substr(blk, RSTART + 8, RLENGTH - 8) }
  if (match(blk, /appop=[A-Z_]+/)) { appop = substr(blk, RSTART + 6, RLENGTH - 6) }
  if (match(blk, /mViewVisibility=0x[0-9a-f]+/)) { vis = substr(blk, RSTART + 16, RLENGTH - 16) }
  if (match(blk, /Surface: +shown=(true|false)/)) { shown = substr(blk, RSTART, RLENGTH); sub(/.*shown=/, "", shown) }
  cand = 0
  if (package == pkg && (ty ~ /^(APPLICATION_OVERLAY|ACCESSIBILITY_OVERLAY)$/ || tok_type ~ /^(2032|2038)$/)) cand = 1
  if (cand) print (layer_id == "" ? "-" : layer_id), tok_type, hash, name, appop, package, ty, vis, shown
  blk = ""; inblk = 0
}
