# parse_win.awk — 解析 `dumpsys window windows` 流，抽目标窗口（默认无障碍悬浮窗）的 WMS 状态
#
# 输入: "@@<ts_ms>" + `dumpsys window windows` 输出，可重复多轮。
# 输出: CSV，每个匹配窗口一行；本轮无匹配则输出 found=0 的一行占位。
#
# 本机实测（Android 16 / LineageOS 23.2，单次 68~90ms → 14Hz）悬浮窗块：
#   `  Window #2 Window{73085c8 u0 com.termux}:`                      ← 名字就是裸包名（没 setTitle）
#   `    mAttrs={(959,330)(99x99) gr=TOP START CENTER ... ty=ACCESSIBILITY_OVERLAY fmt=TRANSLUCENT`  ← 跨行
#   `    Requested w=99 h=99 mLayoutSeq=13846`
#   `    mBaseLayer=311000 mSubLayer=0    mToken=WindowToken{9aa094e type=2032 android.os.Binder@9a5e250}`
#   `    mViewVisibility=0x0 mHaveFrame=true mObscured=false`
#   `    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false`
#   `    Frames: parent=[0,95][1080,2400] display=[0,95][1080,2400] frame=[959,425][1058,524] last=[959,425][1058,524] insetsChanged=false`
#   `     surface=[0,0][0,0]` / `      mSurface=Surface(name=#10474)/@0x381c36`（surface 名为空！）
#   `      Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false`
#   `    isOnScreen=true` / `    isVisible=true` / `    mBufferSeqId=0`
#
# ⚠ 坑（本机实测）:
#   1) `ty=` 打**符号名**（ACCESSIBILITY_OVERLAY / BASE_APPLICATION / NAVIGATION_BAR_PANEL），
#      数值 2032 只出现在 mToken=WindowToken{... type=2032 ...} 里 → 匹配要两种都认。
#   2) mAttrs 的 (x,y) 是**相对 parent frame**；真实屏幕矩形看 `frame=[l,t][r,b]`
#      （本机 status bar 高 95：mAttrs y=330 ↔ frame y=425）。
#   3) WxH 可能是 fillxfill / fillxwrap，别强转数字。
#   4) 无障碍悬浮窗块里**没有** mTouchableInsets / touchable region 行 → 触摸可达性必须看 `dumpsys input`。
#   5) View.INVISIBLE 时：mViewVisibility=0x4、Surface: shown=false 或 mLastHidden=true，窗口仍在列表 → 这本身是 (b) 的证据。
#
# 参数: -v ty_re=<正则>  -v pkg=<包名>  -v name_re=<名字正则>  -v tid=<layer id>  -v any=1（不过滤，全打）
# CSV(32): ts_ms,sample_no,found,idx,match_n,name,win_hash,ty,token_type,package,view_visibility,have_frame,obscured,has_surface,ready_for_display,draw_state,surface_shown,last_hidden,on_screen,is_visible,frame,last_frame,surface_rect,alpha,buf_seq,layout_seq,req_w,req_h,touch_insets,touchable_region,layer_id,attrs_xywh

BEGIN {
  OFS = ","
  print "ts_ms,sample_no,found,idx,match_n,name,win_hash,ty,token_type,package,view_visibility,have_frame,obscured,has_surface,ready_for_display,draw_state,surface_shown,last_hidden,on_screen,is_visible,frame,last_frame,surface_rect,alpha,buf_seq,layout_seq,req_w,req_h,touch_insets,touchable_region,layer_id,attrs_xywh,appop"
  if (ty_re == "") ty_re = "ACCESSIBILITY_OVERLAY|APPLICATION_OVERLAY|2032|2038"
  if (pkg   == "") pkg   = "com.termux"
  ts = ""; sample_no = 0; nmatch = 0; inblk = 0; blk = ""
}
/^@@/ {
  flush(); settle()
  sample_no++; nmatch = 0; blocks_seen = 0; ts = substr($0, 3) + 0
  next
}
/^  Window #[0-9]+ Window\{/ {
  flush()
  inblk = 1; blk = $0; blocks_seen++
  s = $0; sub(/^  Window #[0-9]+ Window\{/, "", s); sub(/\}:?$/, "", s)
  win_hash = ""; name = s
  if (match(s, /^[0-9a-f]+ /)) { win_hash = substr(s, 1, RLENGTH - 1); name = substr(s, RLENGTH + 1) }
  if (match(name, /^u[0-9]+ /)) name = substr(name, RLENGTH + 1)
  next
}
inblk { blk = blk "\n" $0 }
END { flush(); settle() }

function settle(   ) {
  if (ts == "") return
  if (blocks_seen == 0)      placeholder("DUMP_EMPTY")
  else if (nmatch == 0)      placeholder("NO_MATCH")
}
function placeholder(lbl,   i, s) {   # 33 列占位行：lbl=DUMP_EMPTY / NO_MATCH
  s = ts OFS sample_no OFS 0 OFS 0 OFS 0 OFS lbl
  for (i = 7; i <= 33; i++) s = s OFS ""
  print s
}
function reset_blk() {
  ty=""; tok_type=""; package=""; view_vis=""; have_frame=""; obscured=""; has_surface=""; ready=""
  draw_state=""; shown=""; last_hidden=""; on_screen=""; is_visible=""; frame=""; last_frame=""
  surface_rect=""; alpha=""; buf_seq=""; layout_seq=""; req_w=""; req_h=""
  touch_insets=""; touch_region=""; layer_id=""; attrs_xywh=""; appop=""
}
function grab(tag) {          # tag 含 "="，返回其后的值
  if (match(blk, tag "[^ \n]+")) return substr(blk, RSTART + length(tag), RLENGTH - length(tag))
  return ""
}
function flush(   r, wwh, ab) {
  if (!inblk) return
  inblk = 0
  reset_blk()
  ty        = grab("ty=")
  tok_type  = grab("type=")
  package   = grab("package=")
  appop     = grab("appop=")
  view_vis  = grab("mViewVisibility=")
  have_frame= grab("mHaveFrame=")
  obscured  = grab("mObscured=")
  has_surface = grab("mHasSurface=")
  if (match(blk, /isReadyForDisplay\(\)=[a-z]+/)) ready = substr(blk, RSTART + 20, RLENGTH - 20)
  draw_state= grab("mDrawState=")
  shown     = grab("Surface: shown=")
  last_hidden = grab("mLastHidden=")
  buf_seq   = grab("mBufferSeqId=")
  layout_seq= grab("mLayoutSeq=")
  touch_insets = grab("mTouchableInsets=")
  frame     = grab("isOnScreen=")
  on_screen = frame; frame = ""
  is_visible= grab("isVisible=")
  if (match(blk, /mShownAlpha=[-0-9.]+/)) alpha = substr(blk, RSTART + 13, RLENGTH - 13)
  else if (match(blk, /mAlpha=[-0-9.]+/)) alpha = substr(blk, RSTART + 7, RLENGTH - 7)
  if (match(blk, /Frames: [^\n]*/)) {
    r = substr(blk, RSTART, RLENGTH)
    if (match(r, /frame=\[[^]]*\]\[[^]]*\]/))     { frame      = substr(r, RSTART + 6, RLENGTH - 6);  gsub(/,/, " ", frame) }
    if (match(r, /last=\[[^]]*\]\[[^]]*\]/))      { last_frame = substr(r, RSTART + 5, RLENGTH - 5);  gsub(/,/, " ", last_frame) }
    if (match(r, /display=\[[^]]*\]\[[^]]*\]/))   { display_rect = substr(r, RSTART + 8, RLENGTH - 8); gsub(/,/, " ", display_rect) }
  }
  if (match(blk, /surface=\[[^]]*\]\[[^]]*\]/)) { surface_rect = substr(blk, RSTART, RLENGTH); sub(/^surface=/, "", surface_rect); gsub(/,/, " ", surface_rect) }
  if (match(blk, /Requested w=[0-9]+ h=[0-9]+/)) {
    r = substr(blk, RSTART, RLENGTH)
    sub(/^Requested w=/, "", r); sub(/ h=/, " ", r); gsub(/ +/, " ", r)
    split(r, wwh, " "); req_w = wwh[1]; req_h = wwh[2]
  }
  if (match(blk, /mSurface=Surface\(name=[^)]*\)/)) {
    r = substr(blk, RSTART, RLENGTH)
    if (match(r, /#[0-9]+/)) layer_id = substr(r, RSTART + 1, RLENGTH - 1)
  }
  if (match(blk, /touchable region=SkRegion\([^)]*\)/)) {
    touch_region = substr(blk, RSTART, RLENGTH); sub(/^touchable region=SkRegion\(/, "", touch_region); sub(/\)$/, "", touch_region); gsub(/,/, " ", touch_region)
  }
  if (match(blk, /mAttrs=\{[^}]*\}/)) {
    ab = substr(blk, RSTART, RLENGTH)
    if (match(ab, /\([^)]*\)\([^)]*\)/)) {
      attrs_xywh = substr(ab, RSTART, RLENGTH)
      gsub(/[(),]/, " ", attrs_xywh); gsub(/ +/, " ", attrs_xywh); sub(/^ /, "", attrs_xywh); sub(/ $/, "", attrs_xywh)
    }
  }
  # 目标判定
  # 判据：id 命中 > 名字命中 > （包名 == pkg 且 window type 是 overlay）
  # 注意顺序：**不能**先用 ty 门槛把 2038 挡掉（曾因此 300/300 假阳性 GONE）
  cand = 0
  if (any) cand = 1
  else if (tid != "" && layer_id == tid) cand = 1
  else if (name_re != "" && name ~ name_re) cand = 1
  else if (name == pkg) cand = 1
  else if (package == pkg && (ty ~ ty_re || tok_type ~ ty_re) && \
           (appop ~ /^(SYSTEM_ALERT_WINDOW|CREATE_ACCESSIBILITY_OVERLAY)$/ || appop == "")) cand = 1
  if (!cand) return
  nmatch++
  gsub(/,/, ";", name); if (touch_region == "") touch_region = "empty"
  print ts, sample_no, 1, nmatch, nmatch, name, win_hash, ty, tok_type, package, view_vis, have_frame, obscured, has_surface, ready, draw_state, shown, last_hidden, on_screen, is_visible, frame, last_frame, surface_rect, alpha, buf_seq, layout_seq, req_w, req_h, touch_insets, touch_region, layer_id, attrs_xywh, appop
}
