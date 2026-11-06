#!/usr/bin/env bash
# selftest.sh — 用 testdata/ 里的合成 fixture 验证 5 个 awk 解析器（不需要设备、不需要 root）
#
# 合成 fixture 覆盖四类判据的关键分支；任何解析器改坏了这里会先红。
# 用法: ./selftest.sh
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
D=$HERE/device; T=$HERE/testdata
fail=0; n=0
chk() { # chk <名字> <期望> <实际>
  n=$((n+1))
  if [ "$2" = "$3" ]; then printf '  ok   %-42s %s\n' "$1" "$2"
  else printf '  FAIL %-42s want=[%s] got=[%s]\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}
field() { # field <csv> <列名>  （取最后一行）
  awk -F, -v want="$2" 'NR==1 { for (i=1;i<=NF;i++) if ($i==want) c=i } { if (c) v=$c } END { print v }' "$1"
}
run() { # run <awk> <fixture> [额外 -v]...  → 打印 CSV 文件路径
  local prog=$1 fx=$2; shift 2
  local out; out=$(mktemp)
  awk -f "$D/$prog" "$@" < "$T/$fx" > "$out"
  echo "$out"
}

echo "== parse_sf.awk"
o=$(run parse_sf.awk sf_visible.txt -v tid=10474)
chk "visible: seen" 1 "$(field "$o" seen)"; chk "visible: comp_vis" visible "$(field "$o" comp_vis)"
chk "visible: nt_cnt" 1 "$(field "$o" nt_cnt)"; chk "visible: dispframe" "959 425 1058 524" "$(field "$o" dispframe)"
o=$(run parse_sf.awk sf_gone.txt -v tid=10474);  chk "gone: seen" 0 "$(field "$o" seen)"
o=$(run parse_sf.awk sf_hidden.txt -v tid=10474); chk "hidden: input_reason" "hidden by parent or layer flag" "$(field "$o" input_reason)"
chk "hidden: 未进合成" "" "$(field "$o" comp_vis)"
o=$(run parse_sf.awk sf_nodraw.txt -v tid=10474); chk "nodraw: input_reason" "nothing to draw" "$(field "$o" input_reason)"
o=$(run parse_sf.awk sf_transparent.txt -v tid=10474); chk "transparent: nt_cnt" 0 "$(field "$o" nt_cnt)"
o=$(run parse_sf.awk sf_moved.txt -v tid=10474); chk "moved: dispframe" "959 900 1058 999" "$(field "$o" dispframe)"
o=$(run parse_sf.awk sf_visible.txt -v tid=99999); chk "未命中: seen" 0 "$(field "$o" seen)"

echo "== parse_list.awk"
o=$(run parse_list.awk list_visible.txt -v tid=10474 -v tkn=10473)
chk "list: exists" 1 "$(field "$o" exists)"; chk "list: parent" 10473 "$(field "$o" parent_id)"
chk "list: token 存在" 1 "$(field "$o" exists_token)"; chk "list: token 裸名" com.termux "$(field "$o" token_name_bare)"
o=$(run parse_list.awk list_gone.txt -v tid=10474 -v tkn=10473); chk "list gone: exists" 0 "$(field "$o" exists)"

echo "== parse_win.awk"
o=$(run parse_win.awk win_visible.txt -v tid=10474)
chk "win: found" 1 "$(field "$o" found)"; chk "win: name" com.termux "$(field "$o" name)"
chk "win: ty" ACCESSIBILITY_OVERLAY "$(field "$o" ty)"; chk "win: token type" 2032 "$(field "$o" token_type)"
chk "win: view_visibility" 0x0 "$(field "$o" view_visibility)"; chk "win: frame" "[959 425][1058 524]" "$(field "$o" frame)"
chk "win: layer_id" 10474 "$(field "$o" layer_id)"; chk "win: attrs_xywh" "959 330 99x99" "$(field "$o" attrs_xywh)"
o=$(run parse_win.awk win_hidden.txt -v tid=10474); chk "win hidden: view_visibility" 0x4 "$(field "$o" view_visibility)"
chk "win hidden: surface_shown" false "$(field "$o" surface_shown)"
o=$(run parse_win.awk win_gone.txt -v tid=10474); chk "win gone: found" 0 "$(field "$o" found)"
chk "win gone: 标记" NO_MATCH "$(field "$o" name)"

echo "== parse_input.awk"
o=$(run parse_input.awk input_visible.txt -v tid=10474 -v pt=1000,470)
chk "input: found" 1 "$(field "$o" found)"; chk "input: id" 10474 "$(field "$o" id)"
chk "input: not_touchable" 0 "$(field "$o" not_touchable)"; chk "input: covers_point" 1 "$(field "$o" covers_point)"
chk "input: 区域" "[959 425][1058 524]" "$(field "$o" touchable_region)"
chk "input: 焦点窗口" "ccc3333 com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher" "$(field "$o" focused_window)"
case "$(field "$o" stack_at_point)" in *"73085c8 com.termux"*) chk "input: 在点上栈里" 1 1 ;;
  *) chk "input: 在点上栈里" 1 0 ;; esac
o=$(run parse_input.awk input_notouchable.txt -v tid=10474 -v pt=1000,470)
chk "input nottouch: not_touchable" 1 "$(field "$o" not_touchable)"
chk "input nottouch: covers_point" 0 "$(field "$o" covers_point)"
rm -f "$o"

echo
if [ "$fail" = 0 ]; then echo "ALL PASS ($n checks)"; else echo "FAILED: $fail / $n"; exit 1; fi
