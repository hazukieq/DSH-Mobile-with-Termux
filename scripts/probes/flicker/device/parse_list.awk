# parse_list.awk — 解析 `dumpsys SurfaceFlinger --list` 流（段间用 `@@<ts_ms>` 分隔，一次采样一段）
#
# 输入:  "@@1793952405487\nRequestedLayerState{...}\n...\n@@1793952405546\n..."
# 输出:  每采样一行 CSV（含表头）
#
# 关键事实（本机实测，Android 16 / LineageOS 23.2）:
#   1) 行格式 `RequestedLayerState{<name>#<layerId> parentId=<n> [z=<n>]}`；**名字带 #id 后缀**
#      → 不能按裸名字精确匹配。本脚本按 id 精确匹配（并校验 #id 后面不是数字，防 10474/104740 混）。
#   2) 无障碍悬浮窗（未 setTitle）的**层名是空串**：`RequestedLayerState{#10474 parentId=10473}`
#      → 只能按 id 认。父层是 WindowToken 层，名字形如 `73085c8 com.termux`（hex 句柄 + 窗口 token 名）
#      → (surface_id, token_id) 这一对就是「窗口还活着」的最小指纹。
#   3) --list 含**全部 requested layer（包括完全不可见、无 surface 的）**，是「层对象是否存在」的唯一权威。
#      单次 46ms，循环 17~23Hz。
#
# 参数: -v tid=<surface layer id>  -v tkn=<token layer id>[可选]
# CSV: ts_ms,sample_no,layer_count,dumpsys_ok,exists,parent_id,layer_name,exists_token,token_name,token_name_bare

BEGIN {
  OFS = ","
  print "ts_ms,sample_no,layer_count,dumpsys_ok,exists,parent_id,layer_name,exists_token,token_name,token_name_bare"
  ts = ""; n = 0; exists = 0; parent = ""; lname = ""; tok = 0; tname = ""; tbare = ""
}
/^@@/ { if (ts != "") emit(); ts = substr($0, 3) + 0; n = 0; exists = 0; parent = ""; lname = ""; tok = 0; tname = ""; tbare = ""; next }
{
  n++
  if ($0 !~ /^RequestedLayerState\{/) next
  if (tid != "" && index($0, "#" tid) > 0) {
    p = index($0, "#" tid)
    if (substr($0, p + length(tid) + 1, 1) !~ /[0-9]/) {
      exists = 1
      # "RequestedLayerState{" 长 20，名字从第 21 字符起；名字可能为空（无障碍悬浮窗）
      if (p > 21) lname = substr($0, 21, p - 21)
      if (match($0, /parentId=[0-9]+/)) parent = substr($0, RSTART + 9, RLENGTH - 9)
    }
  }
  if (tkn != "" && index($0, "#" tkn) > 0) {
    p = index($0, "#" tkn)
    if (substr($0, p + length(tkn) + 1, 1) !~ /[0-9]/) {
      tok = 1
      if (p > 21) tname = substr($0, 21, p - 21)
      tbare = tname
      if (tbare ~ /^[0-9a-f]+ /) sub(/^[0-9a-f]+ /, "", tbare)
    }
  }
}
END { if (ts != "") emit() }

function emit() {
  gsub(/,/, ";", lname); gsub(/,/, ";", tname); gsub(/,/, ";", tbare)
  print ts, ++sample_no, n, (n > 0 ? 1 : 0), exists, parent, lname, tok, tname, tbare
}
