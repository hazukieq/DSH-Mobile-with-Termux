#!/usr/bin/env node
// DSH USB bridge: 让手机端 agent 通过 `adb reverse` 把任务派给电脑端 dsh（headless profile）。
// 监听 127.0.0.1:9100（只经 adb reverse 暴露给手机 loopback），单并发排队执行。
// API:
//   GET  /health                     → {ok,version,tasks}
//   POST /task    {task,sessionId?,cwd?,json?} → {id,state:"queued"}
//   GET  /tasks                      → 最近任务摘要
//   GET  /task/:id                   → 状态 + 结果全文
//   POST /task/:id/cancel            → 取消排队/执行
//   GET  /task/:id/stream            → 纯文本事件流（SSE 简化：轮询 chunk）
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { homedir } from "node:os";

const DIR = join(homedir(), ".dsh", "usb-bridge");
const RESULTS = join(DIR, "results");
mkdirSync(RESULTS, { recursive: true });
const PORT = Number(process.env.DSH_BRIDGE_PORT || 9100);
const DSH = process.env.DSH_BIN || "dsh";
const MAX_LOG = 400_000; // 保留的 stdout/stderr 上限

/** @type {Map<string, any>} */
const tasks = new Map();
const order = [];
let running = null;

function save(t) {
  try { writeFileSync(join(RESULTS, `${t.id}.json`), JSON.stringify(t, null, 2)); } catch {}
}
function summary(t) {
  return { id: t.id, state: t.state, task: t.task.slice(0, 200), startedAt: t.startedAt, finishedAt: t.finishedAt, ms: t.ms, exit: t.exit, bytes: (t.stdout || "").length };
}
function enqueue(t) { order.push(t.id); tasks.set(t.id, t); save(t); pump(); }
function pump() {
  if (running) return;
  const id = order.shift();
  if (!id) return;
  const t = tasks.get(id);
  if (!t || t.state === "cancelled") { pump(); return; }
  running = t;
  t.state = "running";
  t.startedAt = Date.now();
  save(t);
  const args = ["--profile", "headless"];
  if (t.sessionId) args.push("--session-id", t.sessionId);
  if (t.json) args.push("--json");
  args.push(t.task);
  const child = spawn(DSH, args, {
    cwd: t.cwd || homedir(),
    env: { ...process.env, DSH_BRIDGE_TASK: t.id },
  });
  t.pid = child.pid;
  const onData = (buf, key) => {
    t[key] = (t[key] || "") + buf.toString();
    if (t[key].length > MAX_LOG) t[key] = t[key].slice(-MAX_LOG);
  };
  child.stdout.on("data", (b) => onData(b, "stdout"));
  child.stderr.on("data", (b) => onData(b, "stderr"));
  const finish = (code, signal) => {
    t.state = code === 0 ? "done" : "failed";
    t.exit = code; t.signal = signal;
    t.finishedAt = Date.now(); t.ms = t.startedAt ? t.finishedAt - t.startedAt : null;
    // 结果落盘一份纯文本，方便手机端/人直接看
    try { writeFileSync(join(RESULTS, `${t.id}.md`), t.stdout || t.stderr || ""); } catch {}
    save(t); running = null; pump();
  };
  child.on("exit", finish);
  child.on("error", (e) => { t.state = "failed"; t.stderr = (t.stderr || "") + String(e); finish(-1, null); });
  t.child = child;
}

function json(res, code, obj) {
  const body = JSON.stringify(obj, null, 2);
  res.writeHead(code, { "content-type": "application/json; charset=utf-8" });
  res.end(body);
}

const server = createServer((req, res) => {
  const u = new URL(req.url || "/", "http://127.0.0.1");
  const path = u.pathname.replace(/\/+$/, "") || "/";
  const send = (code, obj) => json(res, code, obj);
  if (req.method === "GET" && path === "/health") {
    return send(200, { ok: true, service: "dsh-usb-bridge", port: PORT, running: running?.id || null,
      queued: order.length, tasks: tasks.size, results: RESULTS });
  }
  if (req.method === "GET" && path === "/tasks") {
    return send(200, { ok: true, running: running?.id || null, queued: order.length,
      tasks: [...order.map((id) => summary(tasks.get(id))), ...(running ? [summary(running)] : []),
        ...[...tasks.values()].filter(t => t.state !== "queued" && t.state !== "running").slice(-20).map(summary)]
        .filter(Boolean) });
  }
  let m;
  if (req.method === "POST" && path === "/task") {
    let body = "";
    req.on("data", (c) => { body += c; if (body.length > 200_000) req.destroy(); });
    req.on("end", () => {
      let p = {};
      try { p = JSON.parse(body || "{}"); } catch { return send(400, { ok: false, error: "body must be JSON" }); }
      const task = (p.task || p.goal || p.text || "").toString().trim();
      if (!task) return send(400, { ok: false, error: "missing task" });
      if (task.length > 100_000) return send(400, { ok: false, error: "task too long" });
      const t = { id: randomUUID(), state: "queued", task, sessionId: p.sessionId || null,
        cwd: p.cwd || null, json: !!p.json, queuedAt: Date.now(), stdout: "", stderr: "" };
      enqueue(t);
      return send(202, { ok: true, id: t.id, state: "queued", hint: `GET /task/${t.id}` });
    });
    return;
  }
  if ((m = path.match(/^\/task\/([\w-]+)$/))) {
    const t = tasks.get(m[1]) || (existsSync(join(RESULTS, `${m[1]}.json`)) && JSON.parse(readFileSync(join(RESULTS, `${m[1]}.json`), "utf8")));
    if (!t) return send(404, { ok: false, error: "unknown task" });
    return send(200, { ok: true, id: t.id, state: t.state, sessionId: t.sessionId,
      startedAt: t.startedAt, finishedAt: t.finishedAt, ms: t.ms, exit: t.exit,
      signal: t.signal, stdout: t.stdout || "", stderr: t.stderr || "" });
  }
  if ((m = path.match(/^\/task\/([\w-]+)\/cancel$/)) && req.method === "POST") {
    const t = tasks.get(m[1]);
    if (!t) return send(404, { ok: false, error: "unknown task" });
    if (t.state === "queued") { t.state = "cancelled"; save(t); return send(200, { ok: true, state: "cancelled" }); }
    if (t.state === "running" && t.child) { t.state = "cancelled"; t.child.kill("SIGKILL"); return send(200, { ok: true, state: "cancelled" }); }
    return send(409, { ok: false, error: `cannot cancel state=${t.state}` });
  }
  send(404, { ok: false, error: "not found" });
});
server.listen(PORT, "127.0.0.1", () => {
  console.log(`[dsh-usb-bridge] listening 127.0.0.1:${PORT} (queue=1, results=${RESULTS})`);
});
