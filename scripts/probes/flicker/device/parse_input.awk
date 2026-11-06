# parse_input.awk — 解析 `dumpsys input`，取证「点不到」：touchable region / NOT_TOUCHABLE / NOT_VISIBLE / 焦点窗口 / 目标点上方的窗口栈
#
# 输入: "@@<ts_ms>" + `dumpsys input` 输出，可重复多轮。
# 输出: 每个命中窗口一行 CSV（同一轮共享 sample_no）；没命中给 NO_MATCH 占位，没窗口行给 DUMP_EMPTY。
#
# 本机实测（Android 16 / LineageOS 23.2，单次 72ms）关键段（注意缩进只有 2 空格！）：
#   Input Dispatcher State:
#     FocusedApplications: ...
#     FocusedWindows:
#       displayId=0, name='3ae02d8 com.android.launcher3/...QuickstepLauncher'
#     TouchStatesByDisplay: <no displays touched>          ← 只有按住期间才有内容
#     Windows:
#       5: name=73085c8 com.termux, id=10474, displayId=0, inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY, alpha=1,
#          frame=[959,425][1058,524], globalScale=1, applicationInfo.name=, applicationInfo.token=<null>,
#          touchableRegion=[959,425][1058,524], ownerPid=21666, ownerUid=10278, dispatchingTimeout=5000ms,
#          token=0x..., touchOcclusionMode=BLOCK_UNTRUSTED, canOccludePresentation
#
# ⚠ 坑:
#   1) frame / touchableRegion 里都含逗号 → **不能 split(",")**，要按 `字段名=` 正则抠；
#      同一行还可能有 `inputConfig=A | B`，所以连 `|` 也要换成 `/` 才好进 CSV。
#   2) 行首索引就是 z 序，0 = 最顶。
#   3) `id=` 与 SurfaceFlinger 的 `#<layerId>` 是同一个 id → 跨工具 join key。
#   4) `RecentQueue` 只给事件类型 + age，**不给投递目标**；判「投给了谁」只能靠
#      (i) 按住期间的 TouchStatesByDisplay，(ii) 本脚本的 stack_at_point 静态推断（z 序 + touchableRegion）。
#   5) `stack_at_point` 是**静态**推断：系统手势/PointerEventDispatcherOverlay 这类窗口可能排在前面，属正常噪声。
#   6) View.INVISIBLE 时 inputConfig 会出现 NOT_VISIBLE（或 region 变 <empty>）→ 这就是「点不到」的直接证据。
#
# 参数: -v tid=<layer id>  -v name_re=<窗口名正则>  -v any=1  -v pt=x,y（诊断点，通常取窗口中心）
# CSV(26): ts_ms,sample_no,found,idx,match_n,name,id,display_id,input_config,alpha,frame,touchable_region,
#          owner_pid,owner_uid,touch_occlusion,not_touchable,not_visible,covers_point,stack_at_point,
#          focused_window,touch_active,touch_state,win_count,dumpsys_ok,layer_id,pt

BEGIN {
  OFS = ","
  print "ts_ms,sample_no,found,idx,match_n,name,id,display_id,input_config,alpha,frame,touchable_region,owner_pid,owner_uid,touch_occlusion,not_touchable,not_visible,covers_point,stack_at_point,focused_window,touch_active,touch_state,win_count,dumpsys_ok,layer_id,pt"
  ts = ""; sample_no = 0; nmatch = 0; nout = 0; win_count = 0
  pt_s = (pt == "" ? "-" : pt); gsub(/,/, ";", pt_s)
  if (name_re == "") name_re = "com\\.termux$"
}
/^@@/ { settle(); sample_no++; nmatch = 0; nout = 0; win_count = 0; stack = ""; ts = substr($0, 3) + 0; next }
/^ *FocusedWindows:/ { infocus = 1; next }
infocus && /name='/ { f = $0; sub(/.*name='/, "", f); sub(/'.*/, "", f); focused = f; infocus = 0; next }
infocus && /^ *[A-Za-z]/ { infocus = 0 }
/^ *TouchStatesByDisplay:/ { intouch = 1; tstate = $0; sub(/.*TouchStatesByDisplay: */, "", tstate); next }
intouch && /^ *[A-Z][A-Za-z]+ByDisplay:/ { intouch = 0 }
intouch && /^ +/ { t = $0; gsub(/[ \t]+/, " ", t); tstate = tstate " |" t; next }
intouch { intouch = 0 }
/^ *[0-9]+: name=/ { parse_win(); next }
END { settle() }

function settle(   k) {
  if (ts == "") return
  if (win_count == 0) { placeholder("DUMP_EMPTY"); return }
  if (nout == 0)      { placeholder("NO_MATCH"); return }
  for (k = 1; k <= nout; k++)
    print ts, sample_no, 1, c_idx[k], k, c_name[k], c_id[k], 0, c_cfg[k], c_al[k], c_fr[k], c_tr[k], c_op[k], c_ou[k], c_om[k], c_nt[k], c_nv[k], c_cov[k], (stack == "" ? "<none>" : stack), focused, (intouch ? 1 : 0), tstate, win_count, 1, c_id[k], pt_s
  nout = 0
}
function placeholder(lbl,   i, s) {
  s = ts OFS sample_no OFS 0 OFS 0 OFS 0 OFS lbl
  for (i = 7; i <= 26; i++) s = s OFS ""
  gsub(/\n/, " ", s); print s
}
function bbox(t) { gsub(/,/, " ", t); return t }
# 从 "[l,t][r,b]" / "l t r b" / "x,y" 里顺序抠整数（toybox awk 不支持 3 参数 match）
function nums(t, arr,   n, tt) {
  n = 0; tt = t
  while (match(tt, /[-0-9]+/)) { arr[++n] = substr(tt, RSTART, RLENGTH); tt = substr(tt, RSTART + RLENGTH) }
  return n
}
function inside(region, point) {
  if (nums(region, rb) < 4) return -1
  if (nums(point, pb) < 2) return -1
  return (pb[1] + 0 >= rb[1] + 0 && pb[1] + 0 <= rb[3] + 0 && pb[2] + 0 >= rb[2] + 0 && pb[2] + 0 <= rb[4] + 0) ? 1 : 0
}
function parse_win(   i, s, id, nm, cfg, tr, nt, nv, cov) {
  win_count++
  s = $0
  if (match(s, /^ *[0-9]+:/)) { i = substr(s, RSTART, RLENGTH); gsub(/[ :]/, "", i) }
  if (match(s, /name=[^,]*/)) nm = substr(s, RSTART + 5, RLENGTH - 5)
  if (match(s, /id=[0-9]+/)) { id = substr(s, RSTART + 3, RLENGTH - 3) }
  if (match(s, /inputConfig=[^,]*/)) { cfg = substr(s, RSTART + 12, RLENGTH - 12) }
  if (match(s, /touchableRegion=<empty>/)) tr = "<empty>"
  else if (match(s, /touchableRegion=\[[^]]*\]\[[^]]*\]/)) tr = bbox(substr(s, RSTART + 16, RLENGTH - 16))
  nt = (cfg ~ /NOT_TOUCHABLE|NO_INPUT_CHANNEL/) ? 1 : 0
  nv = (cfg ~ /NOT_VISIBLE/) ? 1 : 0
  cov = (pt != "" && !nt && !nv) ? inside(tr, pt) : -1
  if (cov == 1) stack = (stack == "" ? nm : stack ";" nm)
  cand = 0
  if (any) cand = 1
  else if (tid != "" && id == tid) cand = 1
  else if (name_re != "" && nm ~ name_re) cand = 1
  if (!cand) { nmatch += 0; return }
  nmatch++
  nout++
  c_idx[nout] = i; c_id[nout] = id; c_tr[nout] = tr
  c_nt[nout] = nt; c_nv[nout] = nv; c_cov[nout] = (cov == 1 ? 1 : 0)
  c_cfg[nout] = cfg; gsub(/\|/, "/", c_cfg[nout]); gsub(/,/, ";", c_cfg[nout])
  c_name[nout] = nm; gsub(/,/, ";", c_name[nout])
  if (match(s, /alpha=[-0-9.]+/)) { c_al[nout] = substr(s, RSTART + 6, RLENGTH - 6) }
  if (match(s, /frame=\[[^]]*\]\[[^]]*\]/)) { c_fr[nout] = bbox(substr(s, RSTART + 6, RLENGTH - 6)) }
  if (match(s, /ownerPid=[0-9]+/)) { c_op[nout] = substr(s, RSTART + 9, RLENGTH - 9) }
  if (match(s, /ownerUid=[0-9]+/)) { c_ou[nout] = substr(s, RSTART + 9, RLENGTH - 9) }
  if (match(s, /touchOcclusionMode=[A-Z_]+/)) { c_om[nout] = substr(s, RSTART + 19, RLENGTH - 19) }
  gsub(/,/, ";", tstate)
}
