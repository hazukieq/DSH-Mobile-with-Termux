#!/usr/bin/env bash
# record.sh — Tier3「视觉真值」：screenrecord 录像 + 逐帧抽出「悬浮窗区域内还有没有内容」
#
# 答的问题：**人眼看到的那一下，到底有没有内容**。这是唯一不依赖 dumpsys 自述的证据。
# 做法：录 mp4（screenrecord 只在画面变化时出帧，天然等于「变化时刻表」）→ ffmpeg 逐帧抽 PNG →
#      对悬浮窗 bbox 算 ink_ratio（暗像素占比）+ 整帧均值，输出 frames.csv。
#      ink_ratio 从 ~0.8 掉到 ~0.05 = 那一刻悬浮窗区域变回背景 → 视觉上「消失了」。
#
# ⚠ 坑（本机实测）:
#   1) screenrecord 静止画面 3s 只出 1 帧 → **不要**用「帧数少」当静止证据，要看时间戳。
#   2) size 必须偶数；540x1200 可跑（4M 码率 3s ≈ 27KB）。
#   3) DRM/受保护内容会黑屏：若整帧全黑（mean<3 且 ink_ratio≈0）先怀疑这个，再怀疑窗口。
#   4) 悬浮窗 99x99 在 540 宽的录制里只有 ~50px → bbox 必须按录制缩放比例换算。
#
# 用法: ./record.sh [秒数] [l,t,r,b（屏幕坐标，可选）]
set -uo pipefail
. "$(dirname "$0")/lib.sh"
flicker_env; flicker_check
secs=${1:-$DUR}
bbox=${2:-}
W=540; H=1200
rmp4="$EVID/rec-$TS_RUN.mp4"; fdir="$EVID/frames-$TS_RUN"; out="$EVID/recframes-$TS_RUN.csv"
mkdir -p "$fdir"
root_ "screenrecord --size ${W}x${H} --bit-rate 8M --time-limit $secs /data/local/tmp/flicker/rec.mp4" || warn "screenrecord 失败（DRM/权限？）"
adb_ pull /data/local/tmp/flicker/rec.mp4 "$rmp4" >/dev/null 2>&1 || die "拉不到录像"
log "mp4: $(du -h "$rmp4" | cut -f1)"
ffmpeg -loglevel error -i "$rmp4" -vsync 0 "$fdir/f%05d.png" || die "抽帧失败"
python3 - "$fdir" "$out" "$bbox" "$W" "$H" <<'PY'
import sys, os, glob
from PIL import Image
fdir, out, bbox, W, H = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
crop = None
if bbox:
    l, t, r, b = [int(x) for x in bbox.replace(']', ' ').replace('[', ' ').replace(',', ' ').split()]
    # 屏幕坐标 -> 录制坐标（录像按屏幕比例缩放，留 2px 余量防抖动）
    sx, sy = (fdir and W / 1080.0), H / 2400.0
    crop = (max(0, int(l*sx)-2), max(0, int(t*sy)-2), min(W-1, int(r*sx)+2), min(H-1, int(b*sy)+2))
rows = [("idx","file","mean_all","ink_ratio","mean_crop","bbox_used")]
prev = None
for i, f in enumerate(sorted(glob.glob(os.path.join(fdir, "*.png")))):
    im = Image.open(f).convert('L')
    px = list(im.getdata()); n = len(px)
    mean_all = sum(px)/n
    if crop:
        c = im.crop(crop); cp = list(c.getdata()); ink = sum(1 for v in cp if v < 60)/len(cp)
        mean_crop = sum(cp)/len(cp)
    else:
        ink, mean_crop = -1.0, -1.0
    rows.append((i, os.path.basename(f), f"{mean_all:.2f}", f"{ink:.4f}", f"{mean_crop:.2f}",
                 f"{crop[0]},{crop[1]},{crop[2]},{crop[3]}" if crop else "-"))
with open(out, "w") as fh:
    for r in rows: fh.write(",".join(str(x) for x in r) + "\n")
print(f"{len(rows)-1} frames -> {out}")
PY
# 帧时间戳（真实呈现时刻）：screenrecord 的 mp4 时间基就是呈现时间
ffprobe -v error -select_streams v:0 -show_entries frame=pts_time -of csv=p=0 "$rmp4" > "$EVID/recpts-$TS_RUN.txt" 2>/dev/null || true
log "frames: $fdir ; pts: $EVID/recpts-$TS_RUN.txt"
echo "$out"
