#!/usr/bin/env python3
"""开发文档不得随包：保证 DEV_ONLY_DOCS 不出现在 app/src/main/assets/docs/。

本项目有两类文档，别混：
  · 开发文档（docs/ 下，给改代码的 agent/人看）——例如 ANDROID-CLI.md、refactor_guide.md
  · 产物文档（app/src/main/assets/docs/，随 APK 发布，供 App 内 agent 与用户查阅）
开发文档一旦进了 assets/docs 就会被装进用户手机，属于泄露开发内幕 + 白占体积。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/docs"
DEV_ONLY = ["ANDROID-CLI.md", "refactor_guide.md", "code_review.md", "AI_CONTEXT.md"]

leaked = [n for n in DEV_ONLY if (ASSETS / n).exists()]
if leaked:
    print(f"[FAIL] 开发文档被误打包进 assets/docs：{', '.join(leaked)}")
    print("       这些是开发本项目用的，不是给 App 内 agent 的产物文档，请删除副本。")
    sys.exit(1)
print(f"[OK] 开发文档未随包（{ASSETS}/ 不含 {', '.join(DEV_ONLY)}）")
