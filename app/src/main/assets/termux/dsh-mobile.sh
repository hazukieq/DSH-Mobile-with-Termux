#!/system/bin/sh
# DSH Mobile helper wrapper (v1.3.22)
#   dsh-mobile info|doc|cdp ...|bridge ...
# 引擎 bin 在 PATH 首位，node 由引擎 runtime 提供；PREFIX 由 Termux 环境给出。
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
DSH_HOME=${DSH_HOME:-$(dirname "$PREFIX")/dsh-home}
NODE="$PREFIX/bin/node"
SCRIPT="$PREFIX/lib/dsh-mobile/webview-cdp.mjs"
case "$1" in
  doc)
    [ -f "$DSH_HOME/DSH-MOBILE.md" ] && exec cat "$DSH_HOME/DSH-MOBILE.md"
    [ -f "$SCRIPT" ] && exec "$NODE" "$SCRIPT" doc
    echo "doc not found at $DSH_HOME/DSH-MOBILE.md" >&2
    exit 1
    ;;
esac
if [ ! -x "$NODE" ]; then
  NODE=$(command -v node 2>/dev/null)
fi
[ -n "$NODE" ] || { echo "dsh-mobile: node not found" >&2; exit 1; }
[ -f "$SCRIPT" ] || { echo "dsh-mobile: helper missing at $SCRIPT" >&2; exit 1; }
exec "$NODE" "$SCRIPT" "$@"
