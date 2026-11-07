#!/usr/bin/env python3
"""闸门：禁止「按路径决定悬浮窗可见性」——即路径级避让表复发。

为什么需要它
------------
2.9.72 之前 `POST /act` 无差别 `beginOpsAvoid()`，2.9.90 之前 `/overlay`
（agent 的**思考进度推送**，每 ~250ms 一条）也在同一张表里 —— 两次都表现为
悬浮窗 3–4Hz 闪烁 + 期间点不到，真机日志：30s 内 1154 次 `ops avoid: /overlay`。

两次都不是机制坏了，而是「把不该避让的端点塞进了避让表」。所以这个闸门不看
端点名字，只看**形状**：凡是用「path/method 分支 + 隐藏调用」组合出来的避让，
一律报错。合法避让只有两种，都不含路径表：

  1. 手势层：`DshAccessibilityService.dispatchGestureAvoided` →
     `StatusOverlay.beginOpsAvoidIfHit(bounds)`（按几何相交判定）；
  2. 显式 opt-in：请求体 `"avoid": true` / `/act` 的 `"hideOverlay": true`。

规则（扫 app/src/main/java/**/*.kt）
----------------------------------
  R1 PATH_SET_DECL   名字带 avoid/hide/ops/overlay 的集合字面量里放 "/..." 路径串
  R2 PATH_GATED_HIDE `path in/==/!=/startsWith` 条件附近 3 行内出现隐藏调用
  R3 METHOD_GATED_HIDE `method == "POST"` 条件附近 3 行内出现隐藏调用

允许例外：在命中行或其上一行写 `// overlay-gate: allow <理由>`。

用法
----
    python3 scripts/check-overlay-avoid.py            # 门禁（0 = 通过，1 = 有命中）
    python3 scripts/check-overlay-avoid.py --selftest # 自检：fixture 必须被正确判定
    python3 scripts/check-overlay-avoid.py -v         # 同时列出豁免项

权威说明见 docs/OVERLAY.md §3.p。
"""
from __future__ import annotations

import glob
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

HIDE_CALL = re.compile(
    r"(beginOpsAvoid\s*\(|beginOpsAvoidIfHit\s*\(|hideNow\s*\(|hideInternal\s*\(|StatusOverlay\.hide\s*\()"
)
SET_DECL = re.compile(
    r"\b(?:private\s+|internal\s+|public\s+|protected\s+)?(?:val|var)\s+([A-Za-z0-9_]+)\s*(?::\s*[^=]+)?=\s*"
    r"(setOf|mutableSetOf|hashSetOf|listOf|mutableListOf|arrayOf)\s*\("
)
NAME_HINT = re.compile(r"(?i)(avoid|hide|hidden|ops|overlay)")
PATH_COND = re.compile(r"\bpath\b\s*(?:\.\s*(?:startsWith|endsWith|contains|matches)\s*\(|\bin\b|==|!=)")
METHOD_COND = re.compile(r'\bmethod\s*==\s*"POST"')
ALLOW = "overlay-gate: allow"


def is_comment(line: str) -> bool:
    s = line.strip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*")


def scan_text(text: str, name: str = "<inline>"):
    """返回 [(line_no, rule, snippet)]。"""
    lines = text.splitlines()
    findings = []

    def allowed(i: int) -> bool:
        if ALLOW in lines[i]:
            return True
        return i > 0 and ALLOW in lines[i - 1]

    # R1：路径集合声明（可能跨多行，往下续到右括号平衡）
    for i, line in enumerate(lines):
        if is_comment(line):
            continue
        m = SET_DECL.search(line)
        if not m or not NAME_HINT.search(m.group(1)):
            continue
        if allowed(i):
            continue
        depth, buf, j = 0, [], i
        while j < len(lines):
            buf.append(lines[j])
            depth += lines[j].count("(") - lines[j].count(")")
            if depth <= 0:
                break
            j += 1
        body = "\n".join(buf)
        if re.search(r'"\s*/', body):
            findings.append((i + 1, "R1 PATH_SET_DECL", line.strip()[:120]))

    # R2 / R3：条件 + 3 行内隐藏调用
    for i, line in enumerate(lines):
        if is_comment(line):
            continue
        rule = None
        if PATH_COND.search(line):
            rule = "R2 PATH_GATED_HIDE"
        elif METHOD_COND.search(line):
            rule = "R3 METHOD_GATED_HIDE"
        if not rule or allowed(i):
            continue
        window = "\n".join(lines[i : i + 4])
        if HIDE_CALL.search(window):
            findings.append((i + 1, rule, line.strip()[:120]))
    return findings


def scan(**kw):
    files = sorted(glob.glob(str(SRC / "**/*.kt"), recursive=True))
    out = []
    for f in files:
        text = Path(f).read_text(encoding="utf-8", errors="replace")
        for line_no, rule, snippet in scan_text(text):
            out.append((str(Path(f).relative_to(ROOT)), line_no, rule, snippet))
    return out, len(files)


SELFTEST_CASES = [
    ("good_explicit.kt", '''
internal fun explicitAvoid(method: String, body: String) =
    method == "POST" && body.contains("\\"avoid\\":true")
''', 0),
    ("good_gesture.kt", '''
fun beginOpsAvoidIfHit(b: Rect): Boolean {
    if (r == null) return false
    if (!Rect.intersects(r, b)) return false
    beginOpsAvoid()
    return true
}
''', 0),
    ("bad_set.kt", '''
private val OPS_AVOID_PATHS = setOf("/overlay", "/open/file")
''', 1),
    ("bad_path_cond.kt", '''
val avoid = method == "POST" && path in OPS_AVOID_PATHS
if (avoid) runCatching { StatusOverlay.beginOpsAvoid() }
''', 1),
    ("bad_method_cond.kt", '''
if (method == "POST") {
    StatusOverlay.beginOpsAvoid()
}
''', 1),
    ("allowed.kt", '''
// overlay-gate: allow 明确知道这批动作要整段藏（用户手动触发）
private val FORCE_HIDE_PATHS = setOf("/my/one/off")
''', 0),
]


def selftest() -> int:
    ok = True
    for name, text, want in SELFTEST_CASES:
        got = len(scan_text(text))
        flag = "ok " if got == want else "FAIL"
        if got != want:
            ok = False
        print(f"  {flag} {name}: findings={got} want={want}")
    print("SELFTEST " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main(argv) -> int:
    if "--selftest" in argv:
        return selftest()
    verbose = "-v" in argv
    hits, n = scan()
    if not hits:
        print(f"OK: {n} 个 Kotlin 文件，0 处路径级避让（规则见 docs/OVERLAY.md §3.p）")
        return 0
    print(f"[x] 发现 {len(hits)} 处「路径/方法 → 隐藏悬浮窗」的写法（禁止）：", file=sys.stderr)
    for f, line_no, rule, snippet in hits:
        print(f"  {f}:{line_no} [{rule}] {snippet}", file=sys.stderr)
    print("\n合法避让只有：手势层几何相交（beginOpsAvoidIfHit）、请求体 \"avoid\":true。", file=sys.stderr)
    print("确属例外请加 `// overlay-gate: allow <理由>`，并在 docs/OVERLAY.md §3.p 记录原因。", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
