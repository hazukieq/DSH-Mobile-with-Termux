#!/usr/bin/env bash
# 免 root 在 App 自己的 Termux 环境里执行命令（走 AdbDebugProvider 的 exec 通道）。
#
# 为什么需要它：bridge token 在 app 私有目录、无 root 的 adb 读不到；在 App 内终端里
# 手打命令又受手机输入法影响（中文 IME 会把 `_`/`.`/`/` 变成全角）。这个通道用
# `content call` 直接把命令送进 App 的 bash，输出原样回传，全程不需要 root、不需要打字。
#
# 前提：设备已装 2.9.103+，且 `settings put global dsh_adb_bridge 1`（默认 1）。
#
# 用法：
#   ./adb-exec.sh 'ls $HOME | head'
#   SERIAL=10CG1C1M1H0021Z ./adb-exec.sh 'cat $HOME/DSH-MOBILE.md | head -1'
#   ./adb-exec.sh --raw 'echo x'        # 只打印 Bundle 里的 out（去掉包装）
#   ./adb-exec.sh --timeout 120000 'sleep 30; echo done'
set -uo pipefail

SERIAL="${SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "adb-exec: no device" >&2; exit 1; }

RAW=0; TIMEOUT=60000
while [ $# -gt 0 ]; do
  case "$1" in
    --raw) RAW=1; shift ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    *) break ;;
  esac
done
CMD="${1:-}"
[ -n "$CMD" ] || { sed -n '7,16p' "$0" >&2; exit 2; }

# 单引号包一层，内部单引号转义成 '\'' —— 这样命令作为一个 argv 交给 content call
ESC=$(printf '%s' "$CMD" | sed "s/'/'\\\\''/g")
OUT=$(adb -s "$SERIAL" shell "content call --uri content://com.termux.adb --method exec --arg '$ESC' --extra timeoutMs:l:$TIMEOUT" 2>&1)
RC=$?

if [ "$RAW" = 1 ]; then
  printf '%s' "$OUT" | python3 -c 'import sys
s=sys.stdin.read()
i=s.find(", out="); j=s.rfind(", exit=")
sys.stdout.write((s[i+6:j] if i>=0 and j>i else s) + ("\n" if i>=0 else ""))'
else
  printf '%s\n' "$OUT"
fi
# content call 自己返回 0；真正的退出码在 Bundle 的 exit= 里
EXIT_CODE=$(printf '%s' "$OUT" | sed -n 's/.*, exit=\([-0-9]*\),.*/\1/p' | head -1)
exit "${EXIT_CODE:-$RC}"
