#!/usr/bin/env python3
"""analyze.py — 把 capture.sh 的四路 CSV + logcat + 逐帧统计合成一份判定报告（markdown 到 stdout）。

四类「闪」的判定（每条都可回指到具体 CSV 列，不猜）：
  (a) GONE        : layers.exists=0（层对象消失）或 window.found=0（WMS 里窗口块没了）
  (b) NO_CONTENT  : 层对象在但没内容 —— frames.seen=0（--list 有 / 完整 dump 无），
                    或 comp_vis=visible 且 nt_cnt=0（区域在但全透明），
                    或 input_reason ∈ {nothing to draw, hidden by parent or layer flag}
  (c) MOVED       : frames.dispframe 相邻采样变化，或 window.frame≠window.last_frame
  (d) STALE       : frames.reused_client=true（合成器复用上一帧 client target）
「点不到」单独一列：input.not_touchable / not_visible / covers_point=0 / stack_at_point 首位不是目标。

用法: analyze.py <run_dir>
"""
import csv, os, sys, glob
from collections import Counter, defaultdict


def load(path):
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return []
    with open(path, newline="", encoding="utf-8", errors="replace") as f:
        return [r for r in csv.DictReader(f) if r.get("ts_ms", "").strip().isdigit()]


def num(v, d=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


def bbox(s):
    if not s:
        return None
    p = [x for x in s.replace("]", " ").replace("[", " ").replace(",", " ").split() if x.lstrip("-").isdigit()]
    return tuple(int(x) for x in p[:4]) if len(p) >= 4 else None


def state_at(rows, ts, key="ts_ms"):
    """给某个时刻找该源上最新的样本（四路频率不同，必须按时间对齐而不是按下标）。"""
    best = None
    for r in rows:
        t = num(r.get(key))
        if t is None:
            continue
        if t <= ts:
            if best is None or t >= num(best.get(key)):
                best = r
        elif best is None:
            best = r  # ts 早于第一帧，用第一帧
    return best


def main(run):
    layers = load(os.path.join(run, "layers.csv"))
    frames = load(os.path.join(run, "frames.csv"))
    win    = load(os.path.join(run, "window.csv"))
    inp    = load(os.path.join(run, "input.csv"))
    rec    = load(os.path.join(run, "recframes.csv"))
    logf   = os.path.join(run, "logcat.txt")
    logl   = open(logf, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(logf) else []

    out = []
    p = out.append
    p("# 悬浮窗闪烁取证报告")
    p("")
    p(f"- 证据目录: `{run}`")
    p("")
    p("## 0. 数据源")
    p("")
    p("| 源 | 行数 | 说明 |")
    p("|---|---|---|")
    p(f"| layers.csv (`--list`) | {len(layers)} | 层对象是否存在（最高频） |")
    p(f"| frames.csv (完整 SF) | {len(frames)} | 合成真值：有没有进合成 / 区域 / 透明度 / 复用 |")
    p(f"| window.csv (WMS) | {len(win)} | 应用自述：View 可见性 / frame / surface |")
    p(f"| input.csv | {len(inp)} | 可达性：touchableRegion / NOT_TOUCHABLE / 点上窗口栈 |")
    p(f"| recframes.csv | {len(rec)} | 视觉真值：悬浮窗区域 ink_ratio |")
    p(f"| logcat.txt | {len(logl)} | 事件时钟（hide/show 的 reason） |")
    p("")
    if not (layers or frames or win):
        p("**没有任何采样数据** —— 先确认悬浮窗在场、root 可用。")
        print("\n".join(out)); return

    # ── 统一时间轴
    ts_all = sorted({num(r["ts_ms"]) for r in layers + frames + win + inp if num(r.get("ts_ms"))})
    prev_disp = None
    verdicts = Counter()
    events = []      # (ts, verdict, detail)
    for ts in ts_all:
        L, F, W, I = state_at(layers, ts), state_at(frames, ts), state_at(win, ts), state_at(inp, ts)
        v, why = None, []
        if L is not None and L.get("exists") == "0":
            v = "GONE"; why.append("layers.exists=0")
        if W is not None and W.get("found") == "0":
            v = "GONE"; why.append(f"window.found=0({W.get('name','')})")
        if F is not None:
            if F.get("seen") == "0" and (L is None or L.get("exists") == "1"):
                v = v or "NO_CONTENT"; why.append("frames.seen=0（层在 --list，但未进合成）")
            if F.get("comp_vis") == "visible" and F.get("nt_cnt") == "0":
                v = v or "NO_CONTENT"; why.append("visibleNonTransparentRegion 空")
            if F.get("input_reason"):
                v = v or "NO_CONTENT"; why.append(f"input list reason={F['input_reason']}")
            d = bbox(F.get("dispframe"))
            if d and prev_disp and d != prev_disp:
                v = v or "MOVED"; why.append(f"dispframe {prev_disp}->{d}")
            if d:
                prev_disp = d
            if F.get("reused_client") == "true":
                v = v or "STALE"; why.append("reusedClientComposition=true")
        if W is not None and W.get("frame") and W.get("frame") != W.get("last_frame"):
            v = v or "MOVED"; why.append("window.frame≠last_frame")
        # 「点不到」独立标注
        touch = ""
        if I is not None:
            if I.get("not_touchable") == "1":
                touch = "NOT_TOUCHABLE"
            elif I.get("not_visible") == "1":
                touch = "NOT_VISIBLE"
            elif I.get("covers_point") == "0":
                touch = "region 不含诊断点"
            elif I.get("stack_at_point"):
                first = I["stack_at_point"].split(";")[0]
                if first and "com.termux" not in first:
                    touch = f"落在更高层: {first[:40]}"
        if v:
            verdicts[v] += 1
            events.append((ts, v, "; ".join(why), touch))
    ok_n = len(ts_all) - len(events)
    verdicts["OK"] = ok_n

    p("## 1. 判定汇总")
    p("")
    p("| 结论 | 采样点数 | 含义 |")
    p("|---|---|---|")
    p(f"| OK | {ok_n} | 窗口在、内容在、位置稳定、无复用 |")
    p(f"| (a) GONE | {verdicts['GONE']} | 层/窗口真的被摘掉了 |")
    p(f"| (b) NO_CONTENT | {verdicts['NO_CONTENT']} | 层在但没画出来（无 buffer / 全透明 / hidden by parent） |")
    p(f"| (c) MOVED | {verdicts['MOVED']} | 内容在但位置/尺寸跳变 |")
    p(f"| (d) STALE | {verdicts['STALE']} | 合成器复用了上一帧（陈旧帧） |")
    p("")

    if events:
        p("## 2. 异常时间线（只列异常点，相邻同类已折叠）")
        p("")
        p("| ts_ms | 判定 | 证据 | 可达性 |")
        p("|---|---|---|---|")
        last = None
        for ts, v, why, touch in events:
            if last == v:
                continue
            last = v
            p(f"| {int(ts)} | {v} | {why[:120]} | {touch or '-'} |")
        p("")
        # 折叠后的连续段
        p("## 3. 闪烁片段（连续异常段）")
        p("")
        spans = []
        cur = None
        for ts, v, why, touch in events:
            if cur and cur[1] == v and ts - cur[2] < 400:
                cur[2] = ts; cur[3] += 1
            else:
                if cur:
                    spans.append(cur)
                cur = [ts, v, ts, 1]
        if cur:
            spans.append(cur)
        p("| 起(ms) | 止(ms) | 持续(ms) | 判定 | 采样数 |")
        p("|---|---|---|---|---|")
        for a, v, b, n in spans:
            p(f"| {int(a)} | {int(b)} | {int(b - a)} | {v} | {n} |")
        p("")
        p("> 判读：持续 <150ms 的段 ≈ 1~2 帧的「闪」；持续 >500ms 更可能是真的隐藏期（capture avoid / ops avoid）。"
          " 结合 logcat 里 `hide(reason)`/`show(reason)` 的时间戳，可以区分「设计内隐藏」与「意外闪烁」。")
        p("")

    # ── logcat 事件
    hid = [l for l in logl if "hide(" in l or "show(" in l or "capture avoid" in l or "torn down" in l or "overlay hidden" in l]
    if hid:
        p("## 4. logcat 事件（Tier0 时钟）")
        p("")
        p("```")
        for l in hid[:60]:
            p(l)
        if len(hid) > 60:
            p(f"... ({len(hid) - 60} more)")
        p("```")
        p("")

    # ── 录像
    if rec:
        inks = [num(r.get("ink_ratio"), -1) for r in rec]
        p("## 5. 视觉真值（每帧悬浮窗区域 ink_ratio）")
        p("")
        p(f"- 帧数 {len(rec)}；ink_ratio min={min(inks):.3f} max={max(inks):.3f}")
        drops = [(i, inks[i - 1], inks[i]) for i in range(1, len(inks)) if inks[i - 1] > 0.3 and inks[i] < 0.1]
        p(f"- 区域内「有墨→空白」跳变 {len(drops)} 次: {drops[:8]}")
        p("")
        p("> ink_ratio 由 ~0.3+ 掉到 <0.1 = 那一帧悬浮窗区域变回背景（人眼看到「闪一下」）。"
          " 若整帧 mean_all≈0，先怀疑 screenrecord 被 DRM/隐私黑屏，而不是窗口消失。")

    print("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    main(sys.argv[1])
