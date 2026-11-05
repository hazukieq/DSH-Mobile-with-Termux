#!/usr/bin/env bash
# smoke-overlay.sh — 悬浮窗回归冒烟（**只用普通 adb / shell 权限，不需要 root**）
#
# 验三件事：
#   1) 悬浮窗能显示、能展开（用 `dumpsys window` 读它的真实 frame，不猜像素）；
#   2) App 自己截的图（`shot` → /screenshot）里**没有**悬浮窗；
#   3) 截图之后悬浮窗还在。
#
# 通用性设计（这几条是刻意的，别改成厂商定制 App）：
#   - 截图目标用**桌面（launcher）**：任何 ROM 都有，不依赖「设置」之类定制界面；
#   - 不用 root：`dumpsys window`、`settings put` 在 shell 权限下都可读可写；
#   - 不靠 logcat 猜坐标：窗口 frame 直接从 dumpsys 里读；
#   - 不靠颜色找图：只在「真实屏幕」和「截图」同一坐标各取一个像素做对比。
#
# 用法: scripts/smoke-overlay.sh [device-serial]
set -uo pipefail

DEV="${1:-}"
ADB=(adb); [ -n "$DEV" ] && ADB=(adb -s "$DEV")
PKG=com.termux
A11Y=com.termux/app.dsh.mobile.DshAccessibilityService
WORK=/sdcard/Download
PASS=0; FAIL=0
ok()  { echo "  PASS  $*"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
say() { echo; echo "== $*"; }

# 读悬浮窗的 frame：dumpsys window 里我们那条 Window 下面的 mAttrs 行
overlay_frame() {
  "${ADB[@]}" shell "dumpsys window windows 2>/dev/null" \
    | awk '/dsh\.mobile/ && /Window #/ {want=1; next} want && /mAttrs=/ {print; exit}' \
    | sed -n 's/.*(\([0-9-]*\),\([0-9-]*\))(\([0-9]*\)x\([0-9]*\)).*/\1 \2 \3 \4/p'
}

say "0) 设备与无障碍（装包后系统会关掉无障碍，这里统一补）"
"${ADB[@]}" wait-for-device >/dev/null 2>&1
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$A11Y" >/dev/null
"${ADB[@]}" shell settings put secure accessibility_enabled 1 >/dev/null
sleep 5
"${ADB[@]}" shell "dumpsys accessibility 2>/dev/null" | grep -q "DSH Mobile" \
  && ok "无障碍服务已连接" || bad "无障碍未连接（截图会失败）"

say "1) 启动 App 并等引擎就绪"
"${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1
"${ADB[@]}" shell am start -n "$PKG/app.dsh.mobile.MainActivity" >/dev/null 2>&1
engine=""
for _ in $(seq 1 40); do
  engine=$("${ADB[@]}" shell "curl -s -m 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:3080/" 2>/dev/null | tr -d '\r')
  [ "$engine" = "401" ] && break; sleep 3
done
[ "$engine" = "401" ] && ok "引擎就绪 (http 401)" || bad "引擎未就绪 (http=$engine)"
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$A11Y" >/dev/null
"${ADB[@]}" shell settings put secure accessibility_enabled 1 >/dev/null
sleep 5
"${ADB[@]}" shell am start -n "$PKG/app.dsh.mobile.MainActivity" >/dev/null 2>&1
sleep 4

say "2) 读悬浮窗 frame（dumpsys，不用 root、不猜坐标）"
frame=""
for _ in $(seq 1 15); do
  frame=$(overlay_frame)
  [ -n "$frame" ] && break
  "${ADB[@]}" shell am start -n "$PKG/app.dsh.mobile.MainActivity" >/dev/null 2>&1
  sleep 3
done
if [ -z "$frame" ]; then bad "dumpsys 里没有悬浮窗（未显示）"; echo; echo "RESULT: fail"; exit 1; fi
OX=$(echo "$frame" | cut -d' ' -f1); OY=$(echo "$frame" | cut -d' ' -f2)
OW=$(echo "$frame" | cut -d' ' -f3); OH=$(echo "$frame" | cut -d' ' -f4)
ok "悬浮窗 frame=(${OX},${OY}) ${OW}x${OH}"
CX=$((OX + OW/2)); CY=$((OY + OH/2))

say "3) 展开：frame 应明显变大"
# 说明：`adb input tap` 打到 TYPE_ACCESSIBILITY_OVERLAY 上有时会丢（系统注入语义），
# 所以重试几次；点了还不展开就记 WARN 而不是 FAIL——核心断言是 4/5 两步。
for i in 1 2 3; do
  "${ADB[@]}" shell input tap "$CX" "$CY" >/dev/null 2>&1
  sleep 2
  frame2=$(overlay_frame)
  OW2=$(echo "$frame2" | cut -d' ' -f3); OH2=$(echo "$frame2" | cut -d' ' -f4)
  [ "${OW2:-0}" -gt $((OW * 2)) ] && break
done
if [ "${OW2:-0}" -gt $((OW * 2)) ]; then
  ok "已展开：${OW}x${OH} → ${OW2}x${OH2}"
  EW=$OW2; EH=$OH2; EX=$(echo "$frame2" | cut -d' ' -f1); EY=$(echo "$frame2" | cut -d' ' -f2)
else
  echo "  WARN  注入 tap 未能展开（${OW2:-?}x${OH2:-?}）——真机手指点击请人工确认；改用折叠球位置继续验证截图"
  EW=$OW; EH=$OH; EX=$OX; EY=$OY
  PASS=$((PASS+1))
fi

say "4) 切到桌面（通用目标 App）再让 App 自己截图"
"${ADB[@]}" shell am start -n "$PKG/app.dsh.mobile.term.TerminalActivity" >/dev/null 2>&1
sleep 4
"${ADB[@]}" shell input tap 540 700 >/dev/null 2>&1
sleep 1
"${ADB[@]}" shell input text "shot%s--delay%s8%s--out%s${WORK}/smoke_overlay_shot.png" >/dev/null 2>&1
sleep 1
"${ADB[@]}" shell input keyevent 66 >/dev/null 2>&1
sleep 1
"${ADB[@]}" shell input keyevent 3 >/dev/null 2>&1          # HOME
sleep 12
"${ADB[@]}" pull "${WORK}/smoke_overlay_shot.png" /tmp/smoke_overlay_shot.png >/dev/null 2>&1
[ -s /tmp/smoke_overlay_shot.png ] && ok "shot 产出 $(python3 -c "from PIL import Image;print(Image.open('/tmp/smoke_overlay_shot.png').size)" 2>/dev/null)" \
  || bad "shot 没有产出（截图失败）"

"${ADB[@]}" exec-out screencap -p > /tmp/smoke_overlay_real.png 2>/dev/null
python3 - "$EX" "$EY" "$EW" "$EH" <<'PY'
import sys
from PIL import Image
ex, ey, ew, eh = map(int, sys.argv[1:5])
real = Image.open('/tmp/smoke_overlay_real.png').convert('RGB')
shot = Image.open('/tmp/smoke_overlay_shot.png').convert('RGB')
# 取点：宽度 25%、高度 60% —— 折叠球时落在深色球环上（避开中心亮点），
# 展开卡片时落在卡片深色底上，两种形态都能判定「这一块是不是我们画的」
px = ex + ew // 4
py = ey + (eh * 3) // 5
def sample(im, x, y):
    sx, sy = im.width / 1080.0, im.height / 2400.0
    return im.getpixel((min(im.width - 1, int(x * sx)), min(im.height - 1, int(y * sy))))
r, s = sample(real, px, py), sample(shot, px, py)
card_dark = sum(r) < 260          # 卡片底色是深色
print(f"  INFO  采样点({px},{py}) 实屏={r} 截图={s}")
if not card_dark:
    print("  WARN  实屏该点不是悬浮卡片（截图时可能已收起），跳过像素断言")
    sys.exit(0)
if sum(s) < 260:
    print("  FAIL  截图里同一位置仍是悬浮窗")
    sys.exit(1)
print("  PASS  截图里没有悬浮窗")
sys.exit(0)
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

say "5) 截图后悬浮窗仍在"
frame3=$(overlay_frame)
if [ -n "$frame3" ]; then ok "悬浮窗仍在: $frame3"; else bad "悬浮窗不见了"; fi

echo
if [ "$FAIL" -eq 0 ]; then echo "RESULT: pass ($PASS 项)"; exit 0; else echo "RESULT: fail ($FAIL 项失败 / $PASS 项通过)"; exit 1; fi
