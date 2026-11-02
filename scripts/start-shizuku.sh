#!/usr/bin/env bash
# 在已 root 的设备上把 Shizuku 服务拉起来（复用 Shizuku 自己的「启动（针对已 root 设备）」按钮）。
#
#   bash scripts/start-shizuku.sh --device 5f08bc20
#
# 做三件事：① 打开 Shizuku → ② uiautomator 按文本找「启动（针对已 root 设备）」块里的「启动」
# 按钮并点击 → ③ 轮询 shizuku_server 进程（最多 30s）。已运行则直接返回 0。
set -uo pipefail
DEVICE=""
while [ $# -gt 0 ]; do case "$1" in --device) DEVICE="$2"; shift 2;; *) echo "unknown arg: $1">&2; exit 2;; esac; done
[ -z "$DEVICE" ] && { echo "需要 --device <serial>"; exit 2; }
A(){ adb -s "$DEVICE" shell "$1" 2>/dev/null | tr -d '\r'; }

if A "ps -A | grep -q shizuku_server"; then echo "shizuku_server 已在运行"; exit 0; fi

A "am start -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity" >/dev/null
sleep 4
A "uiautomator dump /sdcard/Download/shz-ui.xml >/dev/null 2>&1"
xy=$(A "cat /sdcard/Download/shz-ui.xml" | python3 -c '
import re,sys
x=sys.stdin.read()
nodes=[(m.group(1),int(m.group(2)),int(m.group(3)),int(m.group(4)),int(m.group(5)))
       for m in re.finditer(r"text=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",x)]
# 只在「启动（针对已 root 设备）」这个标题下方找第一个「启动」按钮
anchor=[n for n in nodes if "已 root" in n[0]]
if not anchor: sys.exit(1)
ay=anchor[0][2]
cand=[n for n in nodes if n[0].strip()=="启动" and n[2]>ay]
if not cand: sys.exit(1)
n=cand[0]; print((n[1]+n[3])//2, (n[2]+n[4])//2)
') || { echo "找不到「启动（针对已 root 设备）」按钮（Shizuku 未安装或版本 UI 变了）" >&2; exit 1; }
echo "点击 Shizuku 启动按钮 @ $xy"
A "input tap $xy" >/dev/null
for i in $(seq 1 15); do
  if A "ps -A | grep -q shizuku_server"; then echo "shizuku_server 已启动"; exit 0; fi
  sleep 2
done
echo "启动超时：shizuku_server 未出现" >&2; exit 1
