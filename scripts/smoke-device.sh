#!/usr/bin/env bash
# DSH Mobile 真机冒烟：引擎 / 分享 / mobile-ui 部署 / 终端长按菜单 / 特权模式（NORMAL→ROOT→SHIZUKU）
#
#   bash scripts/smoke-device.sh --device 5f08bc20 [--only priv] [--priv-cycle] [--no-restore]
#
# 约定：
#   - 需要 root（adb shell su）才能读 App 私有目录、改 dsh_priv.xml；无 root 时相关项 SKIP。
#   - 改模式会重启 App 与引擎（每次约 30-60s），--priv-cycle 共切换 3 次，最后恢复原模式。
#   - 退出码：0 全过（可含 SKIP），1 有 FAIL。
set -uo pipefail

DEVICE=""; ONLY=""; PRIV_CYCLE=0; NO_RESTORE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2;;
    --only) ONLY="$2"; shift 2;;
    --priv-cycle) PRIV_CYCLE=1; shift;;
    --no-restore) NO_RESTORE=1; shift;;
    -h|--help) sed -n '2,12p' "$0"; exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

PKG=com.termux
PREFIX=/data/user/0/$PKG/files/usr
HOME_DIR=/data/user/0/$PKG/files/dsh-home
PREFS=/data/user/0/$PKG/shared_prefs/dsh_priv.xml
BRIDGE=http://127.0.0.1:3083
PASS=0; FAIL=0; SKIP=0; FAILS=()
ok(){ printf '  OK   %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf '  FAIL %s\n' "$1"; FAIL=$((FAIL+1)); FAILS+=("$1"); }
skip(){ printf '  SKIP %s\n' "$1"; SKIP=$((SKIP+1)); }
sec(){ printf '\n== %s\n' "$1"; }

A(){ adb -s "$DEVICE" shell "$1" 2>/dev/null | tr -d '\r'; }
S(){ adb -s "$DEVICE" shell "su -c \"$1\"" 2>/dev/null | tr -d '\r'; }
have_root(){ [ "$(A 'id -u')" = "0" ] || A 'su -c id -u' | grep -qx 0; }
token(){ S "cat $PREFIX/.bridge-token" | head -1; }
api(){ local try out; for try in 1 2 3; do out=$(curl -s -m 8 -H "X-DSH-Token: $(token)" "$BRIDGE$1"); case "$out" in *unauthorised*) sleep 2;; *) printf '%s' "$out"; return 0;; esac; done; printf '%s' "$out"; }
engine_up(){ local i; for i in $(seq 1 40); do [ "$(A "curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:3080/")" = "401" ] && return 0; sleep 3; done; return 1; }
engine_uid(){ A "ps -A -o UID,ARGS | grep -E '^[[:space:]]*[0-9]+[[:space:]]+node-bin' | grep 'dsh/lib/bin.js' | head -1 | awk '{print \$1}'"; }
mode(){ S "grep -oE 'priv_mode[^<]*' $PREFS 2>/dev/null" | head -1 | sed 's/.*>//'; }
app_uid(){ A "stat -c %u /data/user/0/$PKG"; }
stop_app(){ A "am force-stop $PKG" >/dev/null; sleep 2; }
start_app(){ A "am start -n $PKG/app.dsh.mobile.MainActivity" >/dev/null; }
setmode(){ local m="$1" uid; stop_app; uid=$(app_uid)
  cat > /tmp/dsh_priv.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="onboarded" value="true" />
    <string name="priv_mode">$m</string>
</map>
XML
  adb -s "$DEVICE" push /tmp/dsh_priv.xml /sdcard/Download/dsh_priv.xml >/dev/null 2>&1
  S "cp -f /sdcard/Download/dsh_priv.xml $PREFS && chown $uid:$uid $PREFS && chmod 660 $PREFS"
  start_app; }

[ -z "$DEVICE" ] && { echo "需要 --device <serial>"; exit 2; }
[ "$(adb devices | grep -c "^$DEVICE[[:space:]]")" = "1" ] || { echo "设备 $DEVICE 未连接"; exit 2; }
ROOT_OK=0; have_root && ROOT_OK=1
[ "$ROOT_OK" = 0 ] && echo "警告：无 root，特权/落盘相关断言将 SKIP"

# ---------- 1 引擎 ----------
if [ -z "$ONLY" ] || [ "$ONLY" = "engine" ]; then
  sec "1 引擎"
  engine_up && ok "引擎健康（:3080 = 401）" || bad "引擎未健康"
  v=$(A "dumpsys package $PKG | grep -m1 versionName" | cut -d= -f2)
  [ -n "$v" ] && ok "已安装版本 $v" || bad "读不到版本"
  st=$(api /status); echo "$st" | grep -q '"ok":true' && ok "桥 /status ok" || bad "桥 /status 失败: ${st:0:120}"
fi

# ---------- 2 分享 ----------
if [ -z "$ONLY" ] || [ "$ONLY" = "share" ]; then
  sec "2 分享"
  A "logcat -c" >/dev/null
  A "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'smoke-device-text' -n $PKG/app.dsh.mobile.MainActivity" >/dev/null
  sleep 4
  A "logcat -d | grep -q DshShare" && ok "分享文本已投递" || bad "分享文本没有 DshShare 日志"
  if [ "$ROOT_OK" = 1 ]; then
    printf 'smoke-%s\n' "$(date +%s)" > /tmp/smoke-share.txt
    adb -s "$DEVICE" push /tmp/smoke-share.txt /sdcard/Download/smoke-share.txt >/dev/null 2>&1
    before=$(S "ls $HOME_DIR/inbox 2>/dev/null | wc -l")
    A "am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM file:///sdcard/Download/smoke-share.txt -n $PKG/app.dsh.mobile.MainActivity" >/dev/null
    sleep 6
    after=$(S "ls $HOME_DIR/inbox 2>/dev/null | wc -l")
    [ "${after:-0}" -gt "${before:-0}" ] && ok "分享文件落盘 inbox（$before → $after）" || bad "分享文件未落盘（$before → $after）"
  else skip "分享文件落盘（需要 root 读 inbox）"; fi
fi

# ---------- 3 mobile-ui ----------
if [ -z "$ONLY" ] || [ "$ONLY" = "ui" ]; then
  sec "3 mobile-ui"
  for f in android-plugins/mobile-ui/client.js android-plugins/mobile-ui/package.json android-plugins/mobile-ui/prefixes.json android-overlay.yml; do
    if [ "$ROOT_OK" = 1 ]; then
      [ "$(S "test -f $PREFIX/$f && echo 1")" = "1" ] && ok "已部署 $f" || bad "缺 $f"
    else skip "检查 $f（需要 root）"; fi
  done
  if [ "$ROOT_OK" = 1 ]; then
    S "grep -q 'mobile-ui' $PREFIX/android-overlay.yml && echo 1" | grep -q 1 && ok "overlay 注册 mobile-ui" || bad "overlay 未注册 mobile-ui"
  fi
fi

# ---------- 4 终端长按菜单 ----------
if [ -z "$ONLY" ] || [ "$ONLY" = "menu" ]; then
  sec "4 终端长按菜单"
  A "am start -n $PKG/app.dsh.mobile.term.TerminalActivity" >/dev/null; sleep 4
  A "input swipe 540 700 540 700 900" >/dev/null; sleep 2
  A "uiautomator dump /sdcard/Download/ui.xml" >/dev/null 2>&1
  if A "cat /sdcard/Download/ui.xml 2>/dev/null" | grep -q '会话菜单'; then ok "长按出现「会话菜单」"
  elif A "cat /sdcard/Download/ui.xml 2>/dev/null" | grep -q 'menu'; then bad "菜单文案不是「会话菜单」"
  else skip "uiautomator 未拿到菜单（可能被输入法/窗口遮挡）"; fi
fi

# ---------- 5 特权模式 ----------
if [ -z "$ONLY" ] || [ "$ONLY" = "priv" ]; then
  sec "5 特权模式"
  if [ "$ROOT_OK" = 0 ]; then skip "特权模式全组（需要 root 改 prefs）"
  else
    orig=$(mode); orig=${orig:-NORMAL}
    echo "  当前模式: $orig"
    check_mode(){
      local m="$1" want_su want_shz want_uid
      case "$m" in
        ROOT)    want_su=0; want_shz=0; want_uid=0;;
        SHIZUKU) want_su=1; want_shz=1; want_uid="";;
        *)       want_su=1; want_shz=0; want_uid="";;
      esac
      engine_up || { bad "$m: 引擎未起"; return; }
      local su_present=0; [ "$(S "test -f $PREFIX/bin/su && echo 1")" = "1" ] && su_present=1
      if [ "$want_su" = 1 ]; then
        [ "$su_present" = 1 ] && ok "$m: su 遮罩存在" || bad "$m: 缺 su 遮罩"
        S "grep -q 'Permission denied' $PREFIX/bin/su && echo 1" | grep -q 1 && ok "$m: su 被拒绝" || bad "$m: su 遮罩内容不对"
      else
        [ "$su_present" = 0 ] && ok "$m: su 遮罩已移除（AI 可用 su）" || bad "$m: ROOT 下仍有 su 遮罩"
      fi
      local shz_present=0; [ "$(S "test -f $PREFIX/bin/shz && echo 1")" = "1" ] && shz_present=1
      if [ "$want_shz" = 1 ]; then
        [ "$shz_present" = 1 ] && ok "$m: shz 已注入" || bad "$m: 缺 shz"
        if [ "$shz_present" = 1 ]; then
          out=$(A "$PREFIX/bin/shz id" | head -1)
          echo "$out" | grep -q 'uid=' && ok "$m: shz 实跑（$out）" || skip "$m: shz 实跑（Shizuku 服务未运行/未授权）"
        fi
      else
        [ "$shz_present" = 0 ] && ok "$m: 无 shz" || bad "$m: 非 SHIZUKU 模式下仍有 shz"
      fi
      if [ -n "$want_uid" ]; then
        local uid; uid=$(engine_uid)
        [ "${uid:-x}" = "$want_uid" ] && ok "$m: 引擎 uid=$uid" || bad "$m: 引擎 uid=$uid（期望 $want_uid）"
      fi
    }
    if [ "$PRIV_CYCLE" = 1 ]; then
      for m in NORMAL ROOT SHIZUKU; do
        echo "  → 切到 $m"
        setmode "$m"; sleep 8
        check_mode "$m"
      done
      if [ "$NO_RESTORE" = 0 ] && [ "$(mode)" != "$orig" ]; then
        echo "  → 恢复 $orig"; setmode "$orig"; sleep 5; engine_up && ok "已恢复到原模式 $orig" || bad "恢复 $orig 后引擎未健康"
      fi
    else
      check_mode "$orig"
    fi
  fi
fi

printf '\n==============================\nPASS %d   FAIL %d   SKIP %d\n' "$PASS" "$FAIL" "$SKIP"
[ "$SKIP" -gt 0 ] && echo "SKIP: ${SKIP} 项（见上）"
if [ "$FAIL" -gt 0 ]; then printf 'FAIL (%d): %s\n' "$FAIL" "${FAILS[*]}"; exit 1; fi
exit 0
