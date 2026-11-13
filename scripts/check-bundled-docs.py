#!/usr/bin/env python3
"""检查开发文档隔离，以及产物文档未遗漏、未残留、与仓库版本一致。"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/docs"
DEV_ONLY = ["ANDROID-CLI.md", "refactor_guide.md", "code_review.md", "AI_CONTEXT.md"]
REPO_ONLY = {"CREDITS.md", "ADB-DEBUG-CHANNEL.md"}

leaked = [n for n in DEV_ONLY if (ASSETS / n).exists()]
if leaked:
    print(f"[FAIL] 开发文档被误打包进 assets/docs：{', '.join(leaked)}")
    print("       这些是开发本项目用的，不是给 App 内 agent 的产物文档，请删除副本。")
    sys.exit(1)
print(f"[OK] 开发文档未随包（{ASSETS}/ 不含 {', '.join(DEV_ONLY)}）")

sources = {p.name: p for p in (ROOT / "docs").glob("*.md")
           if p.name not in set(DEV_ONLY) | REPO_ONLY}
bundled = {p.name: p for p in ASSETS.glob("*.md")}
missing = sources.keys() - bundled.keys()
stale = bundled.keys() - sources.keys()
drift = [name for name in sources.keys() & bundled.keys()
         if sources[name].read_bytes() != bundled[name].read_bytes()]
for label, names in (("缺少产物文档", missing), ("随包残留文档", stale), ("文档副本不一致", drift)):
    if names:
        print(f"[FAIL] {label}：{', '.join(sorted(names))}")
if missing or stale or drift:
    sys.exit(1)
print(f"[OK] {len(sources)} 篇产物文档齐全且与 docs/ 一致")
