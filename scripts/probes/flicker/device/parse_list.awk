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
# 参数: -v tid=<surface layer id>  -v tkn=<token layer id>[可选]  -v name_re=<层名正则>
# CSV: ts_ms,sample_no,layer_count,dumpsys_ok,exists,parent_id,layer_name,exists_token,token_name,token_name_bare,layer_id,switched
#
# ⚠ layer id **不是稳定标识**：悬浮窗 surface 被销毁重建后会换新 id（实测 10672 → 10786）。
#   只按 id 匹配会把「重建」误判成 exists=0（假 GONE）。所以这里做**名字回退 + id 自愈**：
#   2038 的 surface 层名 `Sys2038:com.termux/...` 是稳定的，按它把当前 id 认出来并沿用。

BEGIN {
  OFS = ","
  print "ts_ms,sample_no,layer_count,dumpsys_ok,exists,parent_id,layer_name,exists_token,token_name,token_name_bare,layer_id,switched"
  if (name_re == "") name_re = "^Sys20(32|38):com\\.termux"
  cur = tid; switched = 0
  ts = ""; n = 0; exists = 0; parent = ""; lname = ""; tok = 0; tname = ""; tbare = ""
}
/^@@/ { if (ts != "") emit(); ts = substr($0, 3) + 0; n = 0; exists = 0; parent = ""; lname = ""; tok = 0; tname = ""; tbare = ""; next }
{
  n++
  if ($0 !~ /^RequestedLayerState\{/) next
  if (!exists && name_re != "" && cur != "") {
    # 名字回退：仅当这一行的层名（#id 之前那截）以 Sys2032:/Sys2038: 开头（= surface 层，mid 层有 hex 前缀）
    q = index($0, "#"); if (q > 21) {
      cand_name = substr($0, 21, q - 21)
      if (cand_name ~ name_re) {
        nid = substr($0, q + 1); sub(/[^0-9].*/, "", nid)
        if (nid != "" && nid != cur) { cur = nid; switched = 1 }
      }
    }
  }
  if (cur != "" && index($0, "#" cur) > 0) {
    p = index($0, "#" cur)
    if (substr($0, p + length(cur) + 1, 1) !~ /[0-9]/) {
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
  print ts, ++sample_no, n, (n > 0 ? 1 : 0), exists, parent, lname, tok, tname, tbare, cur, switched
}
