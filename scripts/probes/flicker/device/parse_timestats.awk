# parse_timestats.awk — 解析 `dumpsys SurfaceFlinger --timestats -dump`（单次 69ms）
# 用途：给「陈旧帧 (d)」提供**单调递增计数器**——clientCompositionReusedFrames 涨 = 合成器整帧复用上一帧的 client target。
#
# 输入: "@@<ts_ms>" + timestats 输出。输出: 每轮一行 CSV（计数器是 SF 内部累计值，分析时看差分）。
# CSV: ts_ms,sample_no,total_frames,missed_frames,client_composition_frames,client_composition_reused_frames,
#      refresh_rate_switches,composition_strategy_changes,avg_frame_duration_ms,hist_present_to_present,jank_payload
BEGIN {
  OFS = ","
  print "ts_ms,sample_no,total_frames,missed_frames,client_composition_frames,client_composition_reused_frames,refresh_rate_switches,composition_strategy_changes,avg_frame_duration_ms,hist_present_to_present,jank_payload"
  ts = ""; sample_no = 0
}
/^@@/ { settle(); sample_no++; ts = substr($0, 3) + 0; reset(); next }
{ vs = vs "\n" $0 }
END { settle() }
function reset() {
  total=""; missed=""; ccf=""; ccr=""; rrs=""; csc=""; afd=""; hist=""; jank=""
}
function kv(tag) {                      # 取 "tag=<数字>" 的值
  if (match(vs, tag "=[0-9.]+")) return substr(vs, RSTART + length(tag) + 1, RLENGTH - length(tag) - 1)
  return ""
}
function settle(   s, i, t) {
  if (ts == "") return
  total = kv("totalFrames"); missed = kv("missedFrames")
  ccf = kv("clientCompositionFrames"); ccr = kv("clientCompositionReusedFrames")
  rrs = kv("refreshRateSwitches"); csc = kv("compositionStrategyChanges")
  afd = kv("averageFrameDuration")
  # presentToPresent histogram: 一行 "presentToPresent histogram:" 后跟若干 "Ns: n" 行；这里只留总量与最大桶
  hist = ""; jank = ""
  n = split(vs, L, "\n")
  for (i = 1; i <= n; i++) {
    if (L[i] ~ /^[0-9.]+ms *:/) { t = L[i]; gsub(/[ \t]+/, " ", t); hist = (hist == "" ? t : hist "; " t) }
    if (L[i] ~ /^ *[0-9]+ *jank|jank payload/i) { t = L[i]; gsub(/[ \t]+/, " ", t); jank = (jank == "" ? t : jank "; " t) }
  }
  if (hist == "") hist = "-"; if (jank == "") jank = "-"
  print ts, sample_no, total, missed, ccf, ccr, rrs, csc, afd, hist, jank
  vs = ""
}
