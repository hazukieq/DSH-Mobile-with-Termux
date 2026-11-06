# parse_sf.awk — 解析 `dumpsys SurfaceFlinger`（完整 dump）流，抽目标 layer 的「合成真相」
#
# 输入: "@@<ts_ms>" + 完整 SF dump，重复。输出: 每采样一行 CSV（含表头）。
#
# 本机实测（Android 16 / LineageOS 23.2；完整 dump ≈70ms，12~15Hz）的六个关键 section:
#   A. "Composition list (top to bottom)" —— **只列真正参与合成的可见 layer**（本机 6 层）。
#      层块:  `  Layer [<id>] <name>`
#             `    visible reason= buffer=<n> frame=<n>`      ← reason 为空时 buffer 紧跟在同一行
#             `    bounds={left, top, bottom, right}`          ← ⚠ 顺序是 l,t,b,r（不是 [l,t][r,b]）
#             `    toDisplayTransform={ tx=.. ty=.. }`
#             `    input{(FLAGS) <extra> touchableRegion={l, t, r, b}}`
#   B. "Input list" —— 列**所有带 input info 的 layer（含不可见）**，不可见时给原因字符串：
#      `    invisible reason=nothing to draw`                 ← 无 buffer / alpha=0 / crop 空
#      `    invisible reason=hidden by parent or layer flag`   ← setVisibility(false) / 父层隐藏（View.INVISIBLE 走这条）
#   C. "Display <id> HWC layers (top to bottom)" —— 列 Z | Window Type | Comp Type | Transform | Disp Frame | Source Crop
#   D. "Displays" 下 `  - Output Layer 0x...(name)` 块 —— 只有**参与合成**的层才有：
#      visibleRegion / visibleNonTransparentRegion 的 count + bbox、displayFrame、sourceCrop、hwc composition
#   E. "Composition Display State" —— usesClientComposition / flipClientTarget / **reusedClientComposition** → (d) 陈旧帧
#   F. "Composition RenderSurface State" —— FramebufferSurface BufferQueue **frame-counter** → 全局呈现计数
#
# 参数: -v tid=<layer id>
# CSV: ts_ms,sample_no,dumpsys_ok,seen,comp_vis,comp_reason,buffer,bounds,xform,inp_flags,touchable_sf,input_vis,input_reason,hwc_present,hwc_z,hwc_wintype,hwc_comptype,hwc_transform,hwc_dispframe,dispframe,srccrop,comp_type,hwc_layer,vis_cnt,vis_bbox,nt_cnt,nt_bbox,reused_client,uses_client,flip_client,fbcounter,render_rate,is_secure

BEGIN {
  OFS = ","
  print "ts_ms,sample_no,dumpsys_ok,seen,comp_vis,comp_reason,buffer,bounds,xform,inp_flags,touchable_sf,input_vis,input_reason,hwc_present,hwc_z,hwc_wintype,hwc_comptype,hwc_transform,hwc_dispframe,dispframe,srccrop,comp_type,hwc_layer,vis_cnt,vis_bbox,nt_cnt,nt_bbox,reused_client,uses_client,flip_client,fbcounter,render_rate,is_secure"
  reset(); ts = ""
}

{ nlines++ }
/^@@/ { if (ts != "") emit(); ts = substr($0, 3) + 0; reset(); next }

/^Composition list/ { sec = "comp"; hit = 0; next }
/^Input list/       { sec = "input"; hit = 0; next }
/^Layer Hierarchy/  { sec = ""; hit = 0; next }

# ── A/B 层头
/^  Layer \[[0-9]+\] / {
  n = $0
  match(n, /\[[0-9]+\]/)
  lid = substr(n, RSTART + 1, RLENGTH - 2)
  hit = (lid == tid)
  if (hit) seen = 1
  next
}
# ── A/B 可见性与原因（visible 行里 buffer 紧跟同行，invisible 行里原因是行尾文本）
hit && /^    (in)?visible reason=/ {
  s = $0
  if (index(s, "invisible") > 0) { v = "invisible"; sub(/^    invisible reason=/, "", s) } else { v = "visible"; sub(/^    visible reason=/, "", s) }
  p = index(s, " buffer=")
  if (p > 0) {
    r = substr(s, 1, p - 1)
    b = substr(s, p + 8)
    q = index(b, " frame="); if (q > 0) b = substr(b, 1, q - 1)
    buffer = b
  } else r = s
  sub(/ +$/, "", r)
  if (sec == "comp") { comp_vis = v; comp_reason = r } else if (sec == "input") { input_vis = v; input_reason = r }
  next
}
hit && /^    bounds=\{/ {
  s = $0
  match(s, /bounds=\{[^}]*\}/); b = substr(s, RSTART + 8, RLENGTH - 9); gsub(/,/, " ", b)
  if (sec == "comp") bounds = b; else if (sec == "input" && bounds == "") bounds = b
  if (match(s, /toDisplayTransform=\{ tx=[-0-9.]+ ty=[-0-9.]+ \}/)) {
    x = substr(s, RSTART, RLENGTH)
    sub(/^toDisplayTransform=\{/, "", x); sub(/ \}$/, "", x); gsub(/ +/, " ", x); gsub(/,/, ";", x); xform = x
  }
  next
}
hit && /^    input\{/ {
  s = $0; sub(/^    input\{/, "", s)
  # 注意：touchableRegion 的 {} 是嵌套的，不能「取第一个 }」；先定位 touchableRegion={ 再取到最后一个 }
  q = index(s, "touchableRegion={")
  t = ""
  if (q > 0) { fl = substr(s, 1, q - 1); st = substr(s, q + 17); e = index(st, "}"); t = substr(st, 1, e - 1); gsub(/,/, ";", t) }
  else { fl = s; sub(/\}$/, "", fl) }
  gsub(/ +/, " ", fl); sub(/ +$/, "", fl)
  if (sec == "comp") { inp_flags = fl; touchable_sf = t } else if (sec == "input") { inp_flags_in = fl; touchable_in = t }
  next
}

# ── C HWC 表：层名行（单空格缩进、以 #<id> 结尾，空名字时就是 ` #10474`），下一行是 `|` 分隔数值
/^ #[0-9]+$/ { hname = $0; hid = substr($0, 3); next }
/^ [^ ].*#[0-9]+$/ {
  hname = $0
  if (match(hname, /#[0-9]+$/)) hid = substr(hname, RSTART + 1); else hid = ""
  next
}
hid != "" && /^[ ]*[0-9]+ \|/ {
  if (split($0, a, "|") >= 5) {
    z = trim(a[1]); wt = trim(a[2]); ct = trim(a[3]); tr = trim(a[4]); df = trim(a[5]); sc = trim(a[6]); gsub(/,/, ";", sc)
    if (hid == tid) { hwc_present = 1; hwc_z = z; hwc_wintype = wt; hwc_comptype = ct; hwc_transform = tr; hwc_dispframe = df }
  }
  hid = ""
  next
}

# ── D Output Layer 块
/^  - Output Layer / {
  s = $0
  if (match(s, /#[0-9]+\)$/)) oid = substr(s, RSTART + 1, RLENGTH - 2); else oid = ""
  inout = (oid == tid)
  if (inout) seen = 1
  pend = ""
  next
}
inout && /^        Region [A-Za-z]+ \(this=/ {
  s = $0
  match(s, /Region [A-Za-z]+/); rn = substr(s, RSTART + 7, RLENGTH - 7)
  if (match(s, /count=[0-9]+/)) rc = substr(s, RSTART + 6, RLENGTH - 6); else rc = ""
  pend = rn
  next
}
inout && /^    \[ *-?[0-9]+, *-?[0-9]+, *-?[0-9]+, *-?[0-9]+\]/ {
  s = $0; sub(/^    \[ */, "", s); sub(/\] *$/, "", s); gsub(/ *, */, " ", s)
  if (pend == "visibleRegion") { vis_cnt = rc; vis_bbox = s }
  else if (pend == "visibleNonTransparentRegion") { nt_cnt = rc; nt_bbox = s }
  pend = ""
  next
}
inout && /^      forceClientComposition=/ {
  s = $0
  if (match(s, /displayFrame=\[[^]]*\]/)) dispframe = substr(s, RSTART + 14, RLENGTH - 15)
  if (match(s, /sourceCrop=\[[^]]*\]/)) srccrop = substr(s, RSTART + 12, RLENGTH - 13)
  next
}
inout && /^      hwc: layer=/ {
  s = $0
  if (match(s, /composition=[A-Z]+ \([0-9]+\)/)) comp_type = substr(s, RSTART + 12, RLENGTH - 12)
  if (match(s, /layer=0x[0-9a-f]+/)) hwc_layer = substr(s, RSTART + 6, RLENGTH - 6)
  next
}

# ── E/F 显示级状态
/^   isEnabled=/ {
  if (match($0, /isSecure=[a-z]+/)) is_secure = substr($0, RSTART + 9, RLENGTH - 9)
  next
}
/usesClientComposition=/ {
  if (match($0, /usesClientComposition=[a-z]+/)) uses_client = substr($0, RSTART + 22, RLENGTH - 22)
  if (match($0, /flipClientTarget=[a-z]+/)) flip_client = substr($0, RSTART + 17, RLENGTH - 17)
  if (match($0, /reusedClientComposition=[a-z]+/)) reused_client = substr($0, RSTART + 24, RLENGTH - 24)
  next
}
/frame-counter=/ {
  if (match($0, /frame-counter=[0-9]+/)) fbcounter = substr($0, RSTART + 14, RLENGTH - 14)
  next
}
/renderRate=/ {
  if (match($0, /renderRate=[0-9.]+ Hz/)) render_rate = substr($0, RSTART + 11, RLENGTH - 14)
  next
}

END { if (ts != "") emit() }

function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
function clean(s) { gsub(/,/, ";", s); gsub(/ +$/, "", s); return s }

function emit() {
  print ts, ++sample_no, (nlines > 1 ? 1 : 0), seen, comp_vis, clean(comp_reason), buffer, bounds, xform, inp_flags, touchable_sf, input_vis, clean(input_reason), hwc_present, hwc_z, hwc_wintype, hwc_comptype, hwc_transform, hwc_dispframe, dispframe, srccrop, comp_type, hwc_layer, vis_cnt, vis_bbox, nt_cnt, nt_bbox, reused_client, uses_client, flip_client, fbcounter, render_rate, is_secure
}
function reset() {
  sec = ""; lid = ""; hit = 0; seen = 0; nlines = 0; hid = ""; oid = ""; inout = 0; pend = ""; rc = ""
  comp_vis = ""; comp_reason = ""; input_vis = ""; input_reason = ""
  bounds = ""; xform = ""; buffer = ""; inp_flags = ""; touchable_sf = ""; inp_flags_in = ""; touchable_in = ""
  hwc_present = 0; hwc_z = ""; hwc_wintype = ""; hwc_comptype = ""; hwc_transform = ""; hwc_dispframe = ""
  dispframe = ""; srccrop = ""; comp_type = ""; hwc_layer = ""
  vis_cnt = ""; vis_bbox = ""; nt_cnt = ""; nt_bbox = ""
  reused_client = ""; uses_client = ""; flip_client = ""; fbcounter = ""; render_rate = ""; is_secure = ""
}
