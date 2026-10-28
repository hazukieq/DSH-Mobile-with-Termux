#!/usr/bin/env python3
"""断言 assets/bundled-plugins.zip 里的 pnpm 是完整 npm 包。

pnpm 10 起 add/install 在 worker 线程里跑，缺 dist/worker.js 会直接
「Worker pnpm#1 exited with code 1」。历史上我们为了省体积裁过这个包
（12 文件 / 8.4MB），导致插件安装全挂，所以这里做打包期断言。
"""
import sys, zipfile

ZIP = "app/src/main/assets/bundled-plugins.zip"
NEED = [
    "node_modules/pnpm/bin/pnpm.cjs",
    "node_modules/pnpm/dist/worker.js",
    "node_modules/pnpm/dist/node_modules/",
    "node_modules/pnpm/dist/vendor/",
]
MIN_FILES = 800

def main() -> int:
    with zipfile.ZipFile(ZIP) as z:
        names = z.namelist()
        pnpm = [n for n in names if n.startswith("node_modules/pnpm/")]
        missing = [n for n in NEED if not any(x == n or x.startswith(n) for x in names)]
        print(f"pnpm entries: {len(pnpm)}, total entries: {len(names)}")
        bad = False
        if missing:
            print("MISSING: " + ", ".join(missing)); bad = True
        if len(pnpm) < MIN_FILES:
            print(f"MISSING: only {len(pnpm)} pnpm files (< {MIN_FILES}) — payload looks trimmed"); bad = True
        w = z.getinfo("node_modules/pnpm/dist/worker.js").file_size if "node_modules/pnpm/dist/worker.js" in names else 0
        print(f"dist/worker.js: {w} bytes")
        if w < 100_000:
            print("MISSING: dist/worker.js too small"); bad = True
        print("FAIL" if bad else "OK")
        return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
