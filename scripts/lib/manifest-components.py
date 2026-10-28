#!/usr/bin/env python3
"""列出 APK manifest 里的组件与对外暴露情况（供 scripts/audit-apk.sh 调用）。

用法：  aapt2 dump xmltree --file AndroidManifest.xml app.apk | python3 manifest-components.py
或：    python3 manifest-components.py <apk> [aapt2路径]
"""
import re
import subprocess
import sys


def iter_lines(src):
    for line in src:
        yield line.rstrip("\n")


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1].endswith(".apk"):
        apk = sys.argv[1]
        aapt2 = sys.argv[2] if len(sys.argv) > 2 else "aapt2"
        out = subprocess.run(
            [aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
            capture_output=True, text=True, check=False,
        ).stdout.splitlines()
        lines = iter_lines(out)
    else:
        lines = iter_lines(sys.stdin)

    comp = None
    rows = []
    for line in lines:
        m = re.search(r"E: (activity|activity-alias|service|receiver|provider)(?:\s|$)", line)
        if m:
            if comp:
                rows.append(comp)
            comp = {"kind": m.group(1), "name": None, "exported": None,
                    "perm": None, "filter": False}
            continue
        if comp is None:
            continue
        if "E: intent-filter" in line:
            comp["filter"] = True
            continue
        m = re.search(r'android:name\(\w+\)="([^"]+)"', line)
        if m and comp["name"] is None:
            comp["name"] = m.group(1)
            continue
        m = re.search(r"android:exported\(\w+\)=(?:\(type 0x12\))?(0x[0-9a-f]+|true|false)", line)
        if m:
            raw = m.group(1)
            comp["exported"] = (int(raw, 16) != 0) if raw.startswith("0x") else (raw == "true")
            continue
        m = re.search(r'android:permission\(\w+\)="([^"]+)"', line)
        if m:
            comp["perm"] = m.group(1)
    if comp:
        rows.append(comp)

    if not rows:
        print("  (未解析到组件，检查 aapt2 输出格式)")
        return 1

    risk = []
    for c in rows:
        exposed = c["exported"] if c["exported"] is not None else bool(c["filter"])
        if exposed:
            risk.append(c)
        print("  %-14s %-50s exported=%-5s perm=%s"
              % (c["kind"], (c["name"] or "?")[-50:], exposed, c["perm"] or "-"))

    print()
    if risk:
        print("  exported=true 的组件 %d 个 —— 逐个确认带 permission 或有其它校验：" % len(risk))
        for c in risk:
            print("    - %s %s (perm=%s)" % (c["kind"], c["name"], c["perm"] or "无"))
    else:
        print("  没有 exported=true 的组件。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
