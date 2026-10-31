#!/usr/bin/env python3
"""校验文档里提到的 AgentBridge 端点 / dsh-mobile 子命令是否真实存在。

背景：文档多次写了不存在的端点（例如 GET /notify —— 真实的是 GET /notif），
会让 agent 照着调然后 404。这个脚本把「实现」当唯一真源：

  * 端点：解析 AgentBridge.kt 里 `method == "X" && path == "Y"` 的 when 分支；
  * 子命令：解析 assets/termux/webview-cdp.mjs 里的 `case 'xxx'`；
  * 文档：扫描 assets/agents/*.md、assets/gates/native 里 `GET|POST /path` 与
    `dsh-mobile <sub>` / `dsh-mobile cdp <sub>` 的写法。

发现「文档写了但没有」或「实现有但拼错」就 exit 1。构建前跑一次：
    python3 scripts/check-doc-endpoints.py
"""
import glob
import re
import sys

ROOT = "app/src/main"
# 路由实现已从 AgentBridge 拆到 bridge/Router.kt（Phase 2），两处都要扫：
BRIDGE_FILES = [
    f"{ROOT}/java/app/dsh/mobile/engine/AgentBridge.kt",
    f"{ROOT}/java/app/dsh/mobile/bridge/Router.kt",
]
HELPER = f"{ROOT}/assets/termux/webview-cdp.mjs"
DOCS = glob.glob(f"{ROOT}/assets/agents/*.md") + [f"{ROOT}/assets/gates/native"]


def routes():
    """收集所有 bridge 实现文件里的 (method, path) 分支。"""
    found = set()
    for path in BRIDGE_FILES:
        try:
            text = open(path, encoding="utf-8").read()
        except FileNotFoundError:
            continue
        found |= {(m, p) for m, p in
                  re.findall(r'method == "([A-Z]+)" && path == "([^"]+)"', text)}
    return found


def helper_subcommands():
    text = open(HELPER, encoding="utf-8").read()
    return set(re.findall(r"case '([a-z][a-z-]+)'", text))


def main():
    known_routes = routes()
    known_subs = helper_subcommands()
    problems = []
    checked = 0

    for path in DOCS:
        try:
            text = open(path, encoding="utf-8").read()
        except OSError:
            continue
        for m in re.finditer(r"\b(GET|POST) (/[a-z0-9][a-z0-9/-]*)", text):
            method, route = m.group(1), m.group(2).rstrip("/")
            checked += 1
            if (method, route) not in known_routes:
                line = text[: m.start()].count("\n") + 1
                problems.append(f"{path}:{line}: unknown endpoint {method} {route}")
        if not path.endswith(".md"):
            continue  # gate 脚本里的 "dsh-mobile rm trash-cli" 是文件清单，不是子命令调用
        for m in re.finditer(r"dsh-mobile (?:cdp )?([a-z][a-z-]+)", text):
            sub = m.group(1)
            checked += 1
            if sub not in known_subs:
                line = text[: m.start()].count("\n") + 1
                problems.append(f"{path}:{line}: unknown dsh-mobile subcommand '{sub}'")

    if problems:
        print("doc/endpoint check FAILED:")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"doc/endpoint check ok ({checked} mentions, {len(known_routes)} routes, {len(known_subs)} subcommands)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
