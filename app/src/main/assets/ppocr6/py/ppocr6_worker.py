"""ppocr6 persistent worker (P2-8): load models once, serve JSON lines over stdin/stdout.

Usage: python3 ppocr6_worker.py /path/to/ppocr6
Request:  {"image":"/path/x.png","tier":"tiny","min_conf":0.35}
Response: {"ok":true,"result":{...same as --json...}} or {"ok":false,"error":"..."}
"""
import importlib.util
import json
import os
import sys


def load_module(path):
    # ppocr6 has no .py suffix -> spec_from_file_location() yields None loader.
    from importlib.machinery import SourceFileLoader
    return SourceFileLoader("ppocr6_mod", path).load_module()


def main():
    if len(sys.argv) < 2:
        sys.stderr.write("usage: ppocr6_worker.py <ppocr6 script>\n")
        return 2
    mod = load_module(os.path.abspath(sys.argv[1]))
    # 预热 tiny（第一次请求就不用付模型加载成本）
    try:
        mod.session("tiny", "det")
        mod.session("tiny", "rec")
    except Exception as e:
        sys.stderr.write("warmup failed: %s\n" % e)
        sys.stderr.flush()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            r = mod.run(
                req["image"], req.get("tier", "tiny"), 0,
                req.get("min_conf", 0.35), 0, None, False,
            )
            out = {"ok": True, "result": r}
        except Exception as e:
            out = {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
