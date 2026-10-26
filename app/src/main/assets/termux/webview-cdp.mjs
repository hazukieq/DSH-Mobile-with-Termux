#!/usr/bin/env node
// DSH Mobile helper (v1.3.22) — WebView CDP bridge, AgentBridge client, app info.
// No npm dependencies: plain Node built-ins only.
import fs from 'node:fs';
import net from 'node:net';
import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const PREFIX = process.env.PREFIX || '/data/data/com.termux/files/usr';
const FILES = path.resolve(PREFIX, '..');
const DSH_HOME = process.env.DSH_HOME || path.join(FILES, 'dsh-home');
const FLAG = process.env.DSH_WEBVIEW_DEBUG_FLAG || '/sdcard/Android/data/com.termux/files/webview-debug';
/** v1.3.61：CDP 默认开启；这个文件用于**关闭**。 */
const OFF_FLAG = process.env.DSH_WEBVIEW_DEBUG_OFF_FLAG || '/sdcard/Android/data/com.termux/files/webview-debug-off';
const BRIDGE_PORT = Number(process.env.DSH_BRIDGE_PORT || 3083);
const DOC = path.join(DSH_HOME, 'DSH-MOBILE.md');

function die(msg, code = 1) { console.error(msg); process.exit(code); }

// ---------- device introspection ----------
// /proc/net/unix is NOT readable from Android's untrusted_app SELinux domain
// (live device: EACCES), so socket discovery must degrade gracefully:
//   1) DSH_WEBVIEW_SOCKET env
//   2) GET /process on the app's own AgentBridge (authoritative: app pid + socket name)
//   3) /proc/net/unix scan (works where SELinux allows it, e.g. desktop)
//   4) abstract-socket probe scan over the pid range (slow but always works)
const PROC_UNIX = '/proc/net/unix';
let procState = { ok: false, error: 'not read yet', sockets: new Map() };

function readProcSockets() {
  if (process.env.DSH_WEBVIEW_NO_PROC === '1') {
    procState = { ok: false, error: 'disabled by DSH_WEBVIEW_NO_PROC', sockets: new Map() };
    return procState;
  }
  try {
    const text = fs.readFileSync(PROC_UNIX, 'utf8');
    const sockets = new Map();
    for (const line of text.split('\n')) {
      const m = line.match(/@?(webview_devtools_remote_(\d+))/);
      if (m) sockets.set(m[1], Number(m[2]));
    }
    procState = { ok: true, error: null, sockets };
  } catch (e) {
    procState = { ok: false, error: (e.code || e.message || 'unreadable'), sockets: new Map() };
  }
  return procState;
}

function cmdline(pid) {
  try { return fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').replace(/\0/g, ' ').trim(); } catch { return ''; }
}

function probeAbstract(name, timeoutMs = 150) {
  return new Promise((resolve) => {
    const sock = net.connect({ path: '\0' + name });
    let settled = false;
    const done = (ok) => {
      if (settled) return;
      settled = true;
      try { sock.destroy(); } catch {}
      resolve(ok ? name : null);
    };
    sock.setTimeout(timeoutMs);
    sock.once('connect', () => done(true));
    sock.once('error', () => done(false));
    sock.once('timeout', () => done(false));
  });
}

async function scanAbstractSockets(maxPid = 40000, conc = 400) {
  const t0 = Date.now();
  const hits = [];
  for (let base = 1; base <= maxPid; base += conc) {
    const batch = [];
    for (let pid = base; pid < base + conc && pid <= maxPid; pid++) {
      batch.push(probeAbstract('webview_devtools_remote_' + pid));
    }
    for (const hit of await Promise.all(batch)) if (hit) hits.push(hit);
    if (hits.length) break;
  }
  return { hits, ms: Date.now() - t0 };
}

/** Ask the app itself: GET /process returns app_pid + devtools_socket. */
async function bridgeProcess() {
  try {
    const res = await bridge('GET', '/process');
    if (res.status >= 400) return { ok: false, error: 'bridge /process HTTP ' + res.status };
    const info = JSON.parse(res.body);
    return { ok: true, info };
  } catch (e) {
    return { ok: false, error: (e && e.message) || 'bridge unreachable' };
  }
}

/**
 * Resolve the devtools socket. Async because tiers 2 and 4 are async.
 * Returns { name, pid, via } or null; fills discovery info for status/doctor.
 */
let lastDiscovery = { via: null, proc: null, bridge: null, scan: null };
async function findSocket({ verbose = false } = {}) {
  const log = verbose ? (m) => console.error('[discover] ' + m) : () => {};
  lastDiscovery = { via: null, proc: null, bridge: null, scan: null };

  // 1) explicit environment override
  if (process.env.DSH_WEBVIEW_SOCKET) {
    lastDiscovery.via = 'env';
    log('env override: ' + process.env.DSH_WEBVIEW_SOCKET);
    return { name: process.env.DSH_WEBVIEW_SOCKET, pid: null, via: 'env' };
  }

  // 2) authoritative answer from the app process
  const br = await bridgeProcess();
  lastDiscovery.bridge = br;
  if (br.ok && br.info && br.info.devtools_socket) {
    const name = br.info.devtools_socket;
    lastDiscovery.via = 'bridge';
    log('bridge says ' + name + ' (pid ' + br.info.app_pid + ')');
    return { name, pid: br.info.app_pid, via: 'bridge' };
  }
  log('bridge tier: ' + (br.ok ? 'no devtools_socket (flag absent?)' : br.error));

  // 3) /proc enumeration (fast where permitted)
  const proc = readProcSockets();
  lastDiscovery.proc = proc;
  if (proc.ok) {
    let pick = null;
    const ownPid = br.ok && br.info ? br.info.app_pid : null;
    for (const [name, pid] of proc.sockets) {
      if (ownPid && pid === ownPid) { pick = { name, pid }; break; }
    }
    if (!pick) {
      for (const [name, pid] of proc.sockets) {
        const cl = cmdline(pid);
        if (cl === 'com.termux' || cl.startsWith('com.termux ')) { pick = { name, pid }; break; }
      }
    }
    if (!pick) {
      const first = proc.sockets.entries().next();
      if (!first.done) pick = { name: first.value[0], pid: first.value[1] };
    }
    if (pick) { lastDiscovery.via = 'proc'; return { ...pick, via: 'proc' }; }
    log('/proc readable but no devtools socket found');
  } else {
    log('/proc/net/unix unreadable: ' + proc.error);
  }

  // 4) brute-force abstract socket probe (works without /proc)
  const scan = await scanAbstractSockets();
  lastDiscovery.scan = scan;
  if (scan.hits.length) {
    lastDiscovery.via = 'scan';
    log('scan hit ' + scan.hits[0] + ' in ' + scan.ms + 'ms');
    return { name: scan.hits[0], pid: null, via: 'scan' };
  }
  log('scan found nothing in ' + scan.ms + 'ms');
  return null;
}

// ---------- raw HTTP over the abstract devtools socket ----------
function socketRequest(name, rawPath, { timeoutMs = 4000 } = {}) {
  return new Promise((resolve, reject) => {
    const sock = net.connect({ path: '\0' + name });
    let buf = '';
    let done = false;
    const finish = (err, val) => {
      if (done) return;
      done = true;
      try { sock.destroy(); } catch {}
      err ? reject(err) : resolve(val);
    };
    const timer = setTimeout(() => finish(new Error('timeout talking to ' + name)), timeoutMs);
    sock.on('connect', () => {
      sock.write(`GET ${rawPath} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n`);
    });
    sock.on('data', (d) => {
      buf += d.toString('utf8');
      // devtools may keep the connection open; stop once the JSON body looks complete
      const idx = buf.indexOf('\r\n\r\n');
      if (idx >= 0) {
        const body = buf.slice(idx + 4);
        if (body.trim().endsWith('}') || body.trim().endsWith(']')) {
          clearTimeout(timer);
          finish(null, buf);
        }
      }
    });
    sock.on('error', (e) => { clearTimeout(timer); finish(e); });
    sock.on('end', () => { clearTimeout(timer); finish(null, buf); });
  });
}

function bodyOf(resp) {
  const i = resp.indexOf('\r\n\r\n');
  return i >= 0 ? resp.slice(i + 4) : resp;
}

async function listTargets(name) {
  const raw = await socketRequest(name, '/json/list');
  try { return JSON.parse(bodyOf(raw)); } catch { throw new Error('bad /json/list response: ' + bodyOf(raw).slice(0, 120)); }
}

// ---------- minimal WebSocket client (for Runtime.evaluate / Page.reload) ----------
function wsConnect(name, wsPath, hostHeader, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const sock = net.connect({ path: '\0' + name });
    const key = crypto.randomBytes(16).toString('base64');
    let buf = Buffer.alloc(0);
    let upgraded = false;
    const timer = setTimeout(() => { try { sock.destroy(); } catch {} ; reject(new Error('websocket upgrade timeout')); }, timeoutMs);
    sock.on('connect', () => {
      sock.write(
        `GET ${wsPath} HTTP/1.1\r\nHost: ${hostHeader}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`
      );
    });
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (!upgraded) {
        const i = buf.indexOf('\r\n\r\n');
        if (i < 0) return;
        const head = buf.slice(0, i).toString('latin1');
        if (!/^HTTP\/1\.1 101/.test(head)) { clearTimeout(timer); reject(new Error('upgrade failed: ' + head.split('\r\n')[0])); return; }
        upgraded = true;
        buf = buf.slice(i + 4);
        clearTimeout(timer);
        resolve({ sock, state: { buf } });
        return;
      }
      // frames handled by pump
      state.onData && state.onData(d);
    });
    const state = resolve.state = { onData: null };
    sock.on('error', (e) => reject(e));
  });
}

function encodeFrame(text) {
  const payload = Buffer.from(text, 'utf8');
  const len = payload.length;
  let header;
  if (len < 126) header = Buffer.from([0x81, 0x80 | len]);
  else if (len < 65536) { header = Buffer.from([0x81, 0x80 | 126, len >> 8 & 0xff, len & 0xff]); }
  else { header = Buffer.alloc(10); header[0] = 0x81; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(len), 2); }
  const mask = crypto.randomBytes(4);
  const masked = Buffer.alloc(len);
  for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i & 3];
  return Buffer.concat([header, mask, masked]);
}

function decodeFrames(buf) {
  const frames = [];
  let off = 0;
  while (off + 2 <= buf.length) {
    const b0 = buf[off], b1 = buf[off + 1];
    const opcode = b0 & 0x0f;
    const masked = (b1 & 0x80) !== 0;
    let len = b1 & 0x7f;
    let p = off + 2;
    if (len === 126) { if (p + 2 > buf.length) break; len = buf.readUInt16BE(p); p += 2; }
    else if (len === 127) { if (p + 8 > buf.length) break; len = Number(buf.readBigUInt64BE(p)); p += 8; }
    let mask = null;
    if (masked) { if (p + 4 > buf.length) break; mask = buf.slice(p, p + 4); p += 4; }
    if (p + len > buf.length) break;
    let payload = buf.slice(p, p + len);
    if (mask) { const out = Buffer.alloc(len); for (let i = 0; i < len; i++) out[i] = payload[i] ^ mask[i & 3]; payload = out; }
    frames.push({ opcode, payload });
    off = p + len;
  }
  return { frames, rest: buf.slice(off) };
}

async function cdpCall(name, target, method, params = {}, timeoutMs = 8000) {
  const wsUrl = new URL(target.webSocketDebuggerUrl);
  const { sock, state } = await wsConnect(name, wsUrl.pathname + wsUrl.search, '127.0.0.1:' + wsUrl.port);
  let buf = Buffer.alloc(0);
  let nextId = 1;
  const pending = new Map();
  const send = (obj) => sock.write(encodeFrame(JSON.stringify(obj)));
  sock.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    const { frames, rest } = decodeFrames(buf);
    buf = rest;
    for (const f of frames) {
      if (f.opcode === 0x1) {
        let msg; try { msg = JSON.parse(f.payload.toString('utf8')); } catch { continue; }
        if (msg.id && pending.has(msg.id)) { const { resolve } = pending.get(msg.id); pending.delete(msg.id); resolve(msg); }
      } else if (f.opcode === 0x9) {
        // ping -> pong
        sock.write(Buffer.from([0x8a, 0x80, 0, 0, 0, 0]));
      } else if (f.opcode === 0x8) {
        pending.forEach(({ reject }) => reject(new Error('websocket closed')));
      }
    }
  });
  try {
    const msg = await new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      send({ id, method, params });
      setTimeout(() => { if (pending.has(id)) { pending.delete(id); reject(new Error('cdp timeout: ' + method)); } }, timeoutMs);
    });
    if (msg.error) throw new Error(msg.error.message || JSON.stringify(msg.error));
    return msg.result;
  } finally {
    try { sock.destroy(); } catch {}
  }
}

function firstPage(targets) {
  if (!Array.isArray(targets) || targets.length === 0) throw new Error('no devtools targets (is the WebView running?)');
  return targets.find(t => t.type === 'page' && /3080/.test(t.url)) || targets.find(t => t.type === 'page') || targets[0];
}

// ---------- AgentBridge ----------
function bridgeToken() {
  if (process.env.DSH_BRIDGE_TOKEN) return process.env.DSH_BRIDGE_TOKEN.trim();
  for (const p of [path.join(PREFIX, '.bridge-token'), path.join(FILES, 'usr', '.bridge-token'), path.join(FILES, 'dsh-home', '.bridge-token')]) {
    try { const t = fs.readFileSync(p, 'utf8').trim(); if (t) return t; } catch {}
  }
  return '';
}

function bridge(method, apiPath, body) {
  return new Promise((resolve, reject) => {
    const data = body == null ? null : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body));
    const req = http.request({
      host: '127.0.0.1', port: BRIDGE_PORT, path: apiPath, method: method.toUpperCase(),
      headers: Object.assign(
        { 'X-DSH-Token': bridgeToken(), Connection: 'close' },
        data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {}
      ),
      timeout: 15000,
    }, (res) => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('timeout', () => { req.destroy(new Error('bridge timeout')); });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// ---------- commands ----------
function flagState() {
  try { return fs.existsSync(FLAG) ? 'present' : 'absent'; } catch { return 'unknown'; }
}

async function cmdStatus() {
  const br = await bridgeProcess();
  const proc = readProcSockets();
  console.log('flag       : ' + FLAG + '  [' + flagState() + ']');
  console.log('bridge     : 127.0.0.1:' + BRIDGE_PORT + ' token=' + (bridgeToken() ? 'yes' : 'NO'));
  if (br.ok) {
    const i = br.info || {};
    console.log('app        : pid ' + i.app_pid + ' uid ' + i.app_uid + ' v' + (i.version_name || '?') + ' sdk ' + i.sdk + ' (' + (i.model || '?') + ')');
    console.log('webview_dbg: ' + (i.webview_debug ? 'enabled (default since v1.3.61)' : 'disabled (webview-debug-off present)'));
  } else {
    console.log('app        : bridge unavailable (' + br.error + ') — cannot ask the app for its pid');
  }
  console.log('proc/unix  : ' + (proc.ok ? 'readable (' + proc.sockets.size + ' devtools socket(s))' : 'unreadable (' + proc.error + ') — falling back to bridge/scan'));
  const pick = await findSocket();
  if (!pick) {
    console.log('picked     : none');
    console.log('hint       : dsh-mobile cdp on → reopen the app → dsh-mobile cdp doctor');
    return;
  }
  console.log('picked     : ' + pick.name + '  (via ' + pick.via + ')');
  try {
    const targets = await listTargets(pick.name);
    console.log('targets    : ' + targets.map(t => t.type + ':' + t.url).join(' | ').slice(0, 400));
  } catch (e) { console.log('targets    : ' + e.message); }
}

/** Layered diagnostics: flag → process/bridge → socket discovery → devtools HTTP → WS/CDP. */
async function cmdDoctor() {
  const ok = (v) => (v ? 'OK  ' : 'FAIL');
  console.log('== dsh-mobile cdp doctor ==');
  console.log(ok(fs.existsSync(FLAG)) + ' 1. debug flag ' + FLAG + ' [' + flagState() + ']');

  const br = await bridgeProcess();
  console.log(ok(br.ok) + ' 2. bridge /process ' + (br.ok ? JSON.stringify(br.info) : '(' + br.error + ')'));
  if (br.ok && br.info) {
    console.log(ok(!!br.info.webview_debug) + '    app sees webview_debug=' + br.info.webview_debug +
      (br.info.webview_debug ? '' : ' → webview-debug-off is present; delete it and restart the app'));
  }

  const proc = readProcSockets();
  console.log('     /proc/net/unix: ' + (proc.ok ? 'readable' : 'unreadable (' + proc.error + ')'));

  const pick = await findSocket({ verbose: true });
  console.log(ok(!!pick) + ' 3. socket discovery' + (pick ? ' → ' + pick.name + ' via ' + pick.via : ' → none'));

  if (!pick) {
    console.log('');
    console.log('Next steps: dsh-mobile cdp on → ask the user to reopen the app → dsh-mobile cdp doctor');
    return;
  }

  let targets = null;
  try {
    targets = await listTargets(pick.name);
    console.log(ok(true) + ' 4. devtools HTTP /json/list (' + targets.length + ' target(s))');
    for (const t of targets) console.log('       ' + t.type + ' ' + t.url + '  ws=' + t.webSocketDebuggerUrl);
  } catch (e) {
    console.log(ok(false) + ' 4. devtools HTTP: ' + e.message);
    return;
  }

  const target = firstPage(targets);
  try {
    const r = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: 'document.title', returnByValue: true }, 5000);
    console.log(ok(true) + ' 5. Runtime.evaluate → document.title = ' + JSON.stringify(r.result && r.result.value));
  } catch (e) {
    console.log(ok(false) + ' 5. Runtime.evaluate: ' + e.message);
  }
  console.log('');
  console.log('serve: dsh-mobile cdp serve 9222   (curl 127.0.0.1:9222/json/version)');
}

function cmdOn() {
  try { fs.unlinkSync(OFF_FLAG); console.log('opt-out removed: ' + OFF_FLAG); } catch { /* not present */ }
  try { fs.unlinkSync(FLAG); } catch { /* legacy flag no longer needed */ }
  console.log('WebView debugging is ON by default since v1.3.61 - no flag needed.');
  console.log('If you removed the opt-out just now, restart the app so the devtools socket exists.');
  console.log('Then: dsh-mobile cdp status   /   dsh-mobile cdp serve 9222');
}

function cmdOff() {
  fs.mkdirSync(path.dirname(OFF_FLAG), { recursive: true });
  fs.writeFileSync(OFF_FLAG, '');
  console.log('opt-out created: ' + OFF_FLAG);
  console.log('Restart the app to close the devtools socket.');
}

async function cmdList() {
  const pick = await findSocket() || die('no devtools socket; run `dsh-mobile cdp on`, restart the app, then retry');
  const targets = await listTargets(pick.name);
  console.log(JSON.stringify(targets, null, 2));
}

async function cmdEval(expr) {
  if (!expr) die('usage: dsh-mobile cdp eval <javascript expression>');
  const pick = await findSocket() || die('no devtools socket; enable debugging and restart the app first');
  const target = firstPage(await listTargets(pick.name));
  const res = await cdpCall(pick.name, target, 'Runtime.evaluate', {
    expression: expr, returnByValue: true, awaitPromise: true, userGesture: true,
  });
  if (res.exceptionDetails) {
    console.error('exception: ' + (res.exceptionDetails.exception?.description || res.exceptionDetails.text));
    process.exit(2);
  }
  const v = res.result?.value;
  console.log(typeof v === 'string' ? v : JSON.stringify(v));
}

async function cdpTarget() {
  const pick = await findSocket() || die('no devtools socket; enable debugging and restart the app first');
  const target = firstPage(await listTargets(pick.name));
  // 每条命令都落一行 target 摘要：WebView 重载后如果挂到了旧 page，
  // 点击会「成功但页面没反应」，只靠坐标没法归因。
  try {
    process.stderr.write('[cdp] target ' + (target.id || '?') + ' ' + String(target.url || '').slice(0, 48) + '\n');
  } catch {}
  return { pick, target };
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/**
 * 移动优先的点击通道（2026-10-27 修）。
 *
 * 背景：Android WebView 里页面是**触摸**语义的（移动 UI 的抽屉/会话行来自 React，真实手指
 * 能导航，而纯鼠标事件只带 hover 高亮），所以 click 默认走触摸，按可靠度依次降级：
 *   ① Input.dispatchTouchEvent（touchStart/touchEnd，带 id —— 缺 id 或上一次手势没收尾会报
 *      "Must send a TouchStart first to start a new touch"）
 *   ② Input.emulateTouchFromMouseEvent（DevTools 设备模拟用的老通道，WebView 支持）
 *   ③ 纯鼠标事件（桌面站点/只想 hover 时用 --mouse 显式指定）
 * 返回实际用到的通道名，回执里的 `input` 字段可用来判断"点了没反应"是不是通道问题。
 * 每步都落一行 stderr，便于把"点不动"归因到具体动作（bug report 建议 7）。
 */
async function dispatchTap(pick, target, x, y) {
  const cx = Math.round(x), cy = Math.round(y);
  const log = (channel, extra) => {
    try { process.stderr.write('[cdp] tap ' + cx + ',' + cy + ' via ' + channel + (extra ? ' (' + extra + ')' : '') + '\n'); } catch {}
  };
  // 顺序很重要（2026-10-27 真机实测）：Android WebView 的 dispatchTouchEvent 常被拒
  // （"Must send a TouchStart first to start a new touch."，touchStart 也可能是半成功），
  // 而 emulateTouchFromMouseEvent 稳定产生 pointerType=touch，抽屉/会话行会真正导航。
  // 所以先用它，失败再试 dispatchTouchEvent，最后退纯鼠标。
  try {
    await cdpCall(pick.name, target, 'Input.emulateTouchFromMouseEvent', {
      type: 'mousePressed', x: cx, y: cy, button: 'left', clickCount: 1, deltaX: 0, deltaY: 0,
    });
    await sleep(50);
    await cdpCall(pick.name, target, 'Input.emulateTouchFromMouseEvent', {
      type: 'mouseReleased', x: cx, y: cy, button: 'left', clickCount: 1, deltaX: 0, deltaY: 0,
    });
    log('emulated-touch');
    return 'emulated-touch';
  } catch (e) {
    try {
      await cdpCall(pick.name, target, 'Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [{ x: cx, y: cy, id: 1, radiusX: 2, radiusY: 2, force: 1 }],
      });
      await sleep(50);
      await cdpCall(pick.name, target, 'Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      log('touch', e.message);
      return 'touch';
    } catch (e2) {
      try { await cdpCall(pick.name, target, 'Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] }); } catch {}
      await cdpCall(pick.name, target, 'Input.dispatchMouseEvent', { type: 'mousePressed', x: cx, y: cy, button: 'left', clickCount: 1 });
      await cdpCall(pick.name, target, 'Input.dispatchMouseEvent', { type: 'mouseReleased', x: cx, y: cy, button: 'left', clickCount: 1 });
      log('mouse', (e.message + ' / ' + e2.message).slice(0, 120));
      return 'mouse';
    }
  }
}

/** 纯鼠标点击（hover 菜单、桌面模式页面用；移动抽屉不响应鼠标的导航）。 */
async function mouseOnlyClick(pick, target, x, y) {
  const cx = Math.round(x), cy = Math.round(y);
  await cdpCall(pick.name, target, 'Input.dispatchMouseEvent', { type: 'mouseMoved', x: cx, y: cy, button: 'none' });
  await cdpCall(pick.name, target, 'Input.dispatchMouseEvent', { type: 'mousePressed', x: cx, y: cy, button: 'left', clickCount: 1 });
  await cdpCall(pick.name, target, 'Input.dispatchMouseEvent', { type: 'mouseReleased', x: cx, y: cy, button: 'left', clickCount: 1 });
}

/** 点击入口：默认触摸（移动 UI），mouse=true 时退回纯鼠标。 */
async function clickAt(pick, target, x, y, { mouse = false } = {}) {
  if (mouse) { await mouseOnlyClick(pick, target, x, y); return 'mouse'; }
  return await dispatchTap(pick, target, x, y);
}


const VISIBLE_JS = `(el) => {
  if (!el || !el.isConnected) return null;
  if (el.closest && el.closest('[aria-hidden="true"]')) return null;
  const cs = getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) return null;
  if (el.closest && el.closest('#dshm-scrim, #dshm-info-scrim')) return null;
  const r = el.getBoundingClientRect();
  if (r.width < 2 || r.height < 2) return null;
  const vw = window.innerWidth, vh = window.innerHeight;
  // 必须在视口内：否则坐标可能像 y=-3294 这样落在屏幕外，点了等于没点
  if (r.bottom <= 0 || r.right <= 0 || r.top >= vh || r.left >= vw) return null;
  return r;
}`;

const elementProbe = (selector) => `(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return null;
  const visible = (${VISIBLE_JS});
  const r = visible(el);
  if (!r) return { visible: false };
  const cx = Math.min(Math.max(r.x + r.width / 2, 0), window.innerWidth - 1);
  const cy = Math.min(Math.max(r.y + r.height / 2, 0), window.innerHeight - 1);
  return { visible: true, x: cx, y: cy,
           tag: el.tagName, label: el.getAttribute('aria-label') || '', text: (el.textContent || '').trim().slice(0, 40) };
})()`;

async function cmdClick(selector, mouse, { touch = false } = {}) {
  if (!selector) die('usage: dsh-mobile cdp click <css-selector> [--mouse] [--touch]');
  const { pick, target } = await cdpTarget();
  const hit = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: elementProbe(selector), returnByValue: true });
  const info = hit.result && hit.result.value;
  if (!info) die('click: no element matches ' + selector);
  if (!info.visible) die('click: element is not visible: ' + selector);
  // 默认 DOM click（2026-10-27 真机实测）：App 内的 CDP Input 通道会「回执成功但页面收不到
  // 事件」——同一段代码从宿主直连同坐标却正常，抽屉点不动就是这么来的。DOM click 交给
  // React 自己处理，确定性高；需要真手势（地图/画布/自定义 pointer 逻辑）时显式 --touch。
  let input;
  if (!touch && !mouse) {
    const dexpr = `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el || !el.click) return false; el.click(); return true; })()`;
    const r = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: dexpr, returnByValue: true });
    input = (r.result && r.result.value) ? 'dom-click' : await clickAt(pick, target, info.x, info.y, { mouse });
  } else {
    input = await clickAt(pick, target, info.x, info.y, { mouse });
  }
  console.log(JSON.stringify({ ok: true, clicked: selector, at: [Math.round(info.x), Math.round(info.y)], input, tag: info.tag, label: info.label, text: info.text, instant: true, verify: 'none', do_not: ['screenshot_immediately', 'retry_immediately'] }));
}

async function cmdClickText(text, exact, mouse, { any = false, waitMs = 1500, touch = false } = {}) {
  if (!text) die('usage: dsh-mobile cdp click-text <text> [--exact] [--any] [--wait-ms N] [--touch]');
  const { pick, target } = await cdpTarget();
  const expr = `(() => {
    const wanted = ${JSON.stringify(text)}; const exact = ${exact ? 'true' : 'false'};
    const ANY = ${any ? 'true' : 'false'};
    const visible = (${VISIBLE_JS});
    const CLICKABLE = 'button,[role=menuitem],[role=menuitemradio],[role=option],[role=tab],[role=button],a';
    const els = [...document.querySelectorAll(CLICKABLE + ',span,div,label,li')];
    let best = null;
    for (const el of els) {
      const t = (el.textContent || '').trim();
      if (!t) continue;
      const ok = exact ? t === wanted : t.includes(wanted);
      if (!ok) continue;
      // 默认只接受「像按钮标签」的短文本：聊天气泡里引用同样的词时，textContent 会很长
      // （实测 133 字），会把导航项挤掉。--any 关闭这个限制（要点正文里的一段话时用）。
      if (!ANY && t.length > wanted.length + 60) continue;
      const r = visible(el);
      if (!r) continue;                       // 屏幕外 / 隐藏 / 被 scrim 盖住的一律跳过
      // 优先点「可点击祖先」而不是里面那个 span：抽屉行就是 <button><span>文字</span></button>
      const clickableEl = (el.closest && el.closest(CLICKABLE)) || el;
      const cr = visible(clickableEl) || r;
      const area = cr.width * cr.height;
      // 打分（2026-10-27 修）：先说 aria-label 完全一致的（抽屉行的 aria-label 就是条目名），
      // 再说文字长度贴近 wanted 的（避免匹配到含关键词的整段聊天气泡），最后才是面积。
      const ariaExact = (clickableEl.getAttribute && clickableEl.getAttribute('aria-label') === wanted)
        || (el.getAttribute && el.getAttribute('aria-label') === wanted);
      const lenPenalty = Math.max(0, t.length - wanted.length) * 50;
      const score = (ariaExact ? 0 : 100000) + lenPenalty + area / 100 + (clickableEl === el ? 500 : 0);
      if (!best || score < best.score) {
        best = { score, x: Math.min(Math.max(cr.x + cr.width / 2, 0), window.innerWidth - 1),
                 y: Math.min(Math.max(cr.y + cr.height / 2, 0), window.innerHeight - 1),
                 tag: clickableEl.tagName, text: t.slice(0, 60) };
      }
    }
    if (best) delete best.score;
    return best;
  })()`;
  const deadline = Date.now() + Math.max(0, waitMs);
  let info = null;
  for (;;) {
    const hit = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: expr, returnByValue: true });
    info = hit.result && hit.result.value;
    if (info || Date.now() >= deadline) break;
    await sleep(120);   // 抽屉/面板可能是动画中或刚被流式渲染推翻，短暂重试而不是立刻放弃
  }
  if (!info) die('click-text: nothing visible contains ' + JSON.stringify(text));
  // 默认 DOM click（理由同 cmdClick）：命中的是 React 按钮，真点一次 clic() 最稳。
  let input;
  if (!touch && !mouse) {
    const dexpr = `(() => {
      const wanted = ${JSON.stringify(text)}; const exact = ${exact ? 'true' : 'false'};
      const ANY2 = ${any ? 'true' : 'false'};
      const visible = (${VISIBLE_JS});
      const CLICKABLE = 'button,[role=menuitem],[role=menuitemradio],[role=option],[role=tab],[role=button],a';
      const els = [...document.querySelectorAll(CLICKABLE + ',span,div,label,li')];
      let best = null;
      for (const el of els) {
        const t = (el.textContent || '').trim(); if (!t) continue;
        const ok = exact ? t === wanted : t.includes(wanted); if (!ok) continue;
        if (!ANY2 && t.length > wanted.length + 60) continue;
        const ce = (el.closest && el.closest(CLICKABLE)) || el;
        const r = ce.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) continue;
        const aria = ce.getAttribute && ce.getAttribute('aria-label') === wanted;
        const score = (aria ? 0 : 100000) + Math.max(0, t.length - wanted.length) * 50 + (r.width * r.height) / 100;
        if (!best || score < best.score) best = { score, el: ce };
      }
      if (!best || !best.el.click) return false;
      best.el.click();
      return true;
    })()`;
    const r = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: dexpr, returnByValue: true });
    input = (r.result && r.result.value) ? 'dom-click' : await clickAt(pick, target, info.x, info.y, { mouse });
  } else {
    input = await clickAt(pick, target, info.x, info.y, { mouse });
  }
  console.log(JSON.stringify({ ok: true, clickedText: info.text, at: [Math.round(info.x), Math.round(info.y)], input, tag: info.tag, instant: true, verify: 'none', do_not: ['screenshot_immediately', 'retry_immediately'] }));
}

/**
 * 打开抽屉（幂等）：`cdp click 'button[aria-label="菜单"]'` 是 **toggle** —— 抽屉已经开着时
 * 再点一下就关掉，随后 click-text 只能匹配到页面正文（常见于连续两轮自动化）。
 * 这里先看"收起侧边栏"按钮在不在，开着就直接返回 already_open。
 */
async function cmdDrawer() {
  const { pick, target } = await cdpTarget();
  const probe = `(() => {
    const openBtn = document.querySelector('button[aria-label="收起侧边栏"]');
    const visible = (${VISIBLE_JS});
    if (openBtn && visible(openBtn)) return { open: true };
    const m = document.querySelector('button[aria-label="菜单"]');
    const r = m && visible(m);
    return r ? { open: false, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) } : { open: false };
  })()`;
  const res = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: probe, returnByValue: true });
  const info = res.result && res.result.value;
  if (!info) die('drawer: probe failed');
  if (info.open) {
    console.log(JSON.stringify({ ok: true, drawer: 'already_open', verify: 'none' }));
    return;
  }
  if (info.x === undefined) die('drawer: 菜单 button not found/visible');
  const input = await clickAt(pick, target, info.x, info.y);
  // 自证：等抽屉真的出现再报 opened。之前「点了」就报 opened，抽屉没开也照样说成功，
  // 下游 click-text 会在关着的抽屉里找条目（真机 01:02 现场）。
  const check = `(() => {
    const b = document.querySelector('button[aria-label="收起侧边栏"]');
    const r = b && b.getBoundingClientRect();
    const ok = !!(b && r.width >= 2 && r.height >= 2 && r.right > 0 && r.left < innerWidth);
    return { ok, ready: document.readyState, url: location.href.slice(0, 60) };
  })()`;
  let verify = null;
  for (let i = 0; i < 10; i++) {
    await sleep(80);
    const r = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: check, returnByValue: true });
    verify = r.result && r.result.value;
    if (verify && verify.ok) break;
  }
  if (!verify || !verify.ok) {
    // 兜底：CDP 的 Input 域在真机上偶尔「调用成功但页面收不到」（01:02 现场）。
    // 直接派发 DOM click 给 React 按钮，抽屉一样会开——先保证导航可用，再谈事件真实性。
    const domClick = `(() => {
      const m = document.querySelector('button[aria-label="菜单"]');
      if (!m) return { ok: false, why: 'no menu button' };
      m.click();
      return { ok: true };
    })()`;
    try {
      await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: domClick, returnByValue: true });
      for (let i = 0; i < 10; i++) {
        await sleep(80);
        const r = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: check, returnByValue: true });
        verify = r.result && r.result.value;
        if (verify && verify.ok) {
          console.log(JSON.stringify({ ok: true, drawer: 'opened', at: [info.x, info.y], input: input + '+dom-click', verified: true,
            note: 'CDP 触摸没生效，已用 DOM click 兜底' }));
          return;
        }
      }
    } catch (e) {}
    console.log(JSON.stringify({ ok: false, drawer: 'not_opened', at: [info.x, info.y], input,
      page: { url: (verify && verify.url) || null, ready: (verify && verify.ready) || null },
      hint: '触摸与 DOM click 都没打开抽屉：确认 App 在前台、WebView 没在重载' }));
    process.exit(1);
  }
  console.log(JSON.stringify({ ok: true, drawer: 'opened', at: [info.x, info.y], input, verified: true }));
}

/** 关掉抽屉/侧栏/信息浮层：逐个候选目标试触摸，命中即报。 */
async function cmdClose() {
  const { pick, target } = await cdpTarget();
  const candidates = ['button[aria-label="收起侧边栏"]', '#dshm-scrim', '.dshm-info-scrim'];
  const tried = [];
  for (const sel of candidates) {
    const probe = `(() => {
      const el = document.querySelector(${JSON.stringify(sel)});
      if (!el) return null;
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      if (r.width < 2 || r.height < 2) return null;
      const hit = cs.pointerEvents !== 'none' && cs.display !== 'none' && cs.visibility !== 'hidden';
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, hit };
    })()`;
    const r = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: probe, returnByValue: true });
    const info = r.result && r.result.value;
    tried.push({ selector: sel, found: !!info, clickable: !!(info && info.hit) });
    if (info && info.hit) {
      const input = await clickAt(pick, target, info.x, info.y);
      console.log(JSON.stringify({ ok: true, closed: sel, at: [Math.round(info.x), Math.round(info.y)], input, tried, verify: 'none' }));
      return;
    }
  }
  console.log(JSON.stringify({ ok: true, closed: null, nothing_open: true, tried, hint: 'drawer/info overlay is already closed (its scrims are pointer-events:none only when idle)' }));
}

async function cmdTap(x, y) {
  const px = Number(x), py = Number(y);
  if (!Number.isFinite(px) || !Number.isFinite(py)) die('usage: dsh-mobile cdp tap <x> <y>');
  const { pick, target } = await cdpTarget();
  // tap 就是"按屏幕坐标点一下"：一律真实触摸，失败自动降级（见 dispatchTap）。
  const input = await dispatchTap(pick, target, px, py);
  console.log(JSON.stringify({ ok: true, tapped: [Math.round(px), Math.round(py)], input, instant: true, verify: 'none', do_not: ['screenshot_immediately', 'retry_immediately'] }));
}

async function cmdType(text, selector) {
  if (text === undefined) die('usage: dsh-mobile cdp type <text> [--selector css]');
  const { pick, target } = await cdpTarget();
  if (selector) {
    const hit = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: elementProbe(selector), returnByValue: true });
    const info = hit.result && hit.result.value;
    if (!info || !info.visible) die('type: selector not visible: ' + selector);
    await clickAt(pick, target, info.x, info.y);
  }
  await cdpCall(pick.name, target, 'Input.insertText', { text });
  console.log(JSON.stringify({ ok: true, typed: text.length, selector: selector || null, instant: true, verify: 'none', do_not: ['screenshot_immediately'] }));
}

const KEYMAP = {
  Enter: { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 },
  Tab: { key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 },
  Escape: { key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 },
  Backspace: { key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 },
  ArrowUp: { key: 'ArrowUp', code: 'ArrowUp', windowsVirtualKeyCode: 38 },
  ArrowDown: { key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40 },
  ArrowLeft: { key: 'ArrowLeft', code: 'ArrowLeft', windowsVirtualKeyCode: 37 },
  ArrowRight: { key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 },
};
async function cmdKey(name) {
  const k = KEYMAP[name] || KEYMAP[name && name[0].toUpperCase() + name.slice(1)];
  if (!k) die('usage: dsh-mobile cdp key <Enter|Tab|Escape|Backspace|ArrowUp|ArrowDown|ArrowLeft|ArrowRight>');
  const { pick, target } = await cdpTarget();
  await cdpCall(pick.name, target, 'Input.dispatchKeyEvent', { type: 'keyDown', key: k.key, code: k.code, windowsVirtualKeyCode: k.windowsVirtualKeyCode, nativeVirtualKeyCode: k.windowsVirtualKeyCode });
  await cdpCall(pick.name, target, 'Input.dispatchKeyEvent', { type: 'keyUp', key: k.key, code: k.code, windowsVirtualKeyCode: k.windowsVirtualKeyCode, nativeVirtualKeyCode: k.windowsVirtualKeyCode });
  console.log(JSON.stringify({ ok: true, key: k.key, instant: true, verify: 'none', do_not: ['screenshot_immediately'] }));
}

async function cmdScroll(dy) {
  const delta = Number(dy || 600);
  const { pick, target } = await cdpTarget();
  await cdpCall(pick.name, target, 'Input.dispatchMouseEvent', { type: 'mouseWheel', x: 200, y: 400, deltaX: 0, deltaY: delta });
  console.log(JSON.stringify({ ok: true, scrolled: delta, instant: true, verify: 'none', do_not: ['screenshot_immediately'] }));
}

async function cmdWait(what, timeoutArg) {
  if (!what) die('usage: dsh-mobile cdp wait <css-selector|text> [timeoutMs]');
  const timeout = Number(timeoutArg || 8000);
  const { pick, target } = await cdpTarget();
  const expr = `(() => {
    const sel = ${JSON.stringify(what)};
    const bySelector = document.querySelector(sel);
    if (bySelector) { const r = bySelector.getBoundingClientRect(); if (r.width > 2 && r.height > 2) return 'selector'; }
    if ((document.body.innerText || '').includes(sel)) return 'text';
    return null;
  })()`;
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < timeout) {
    const res = await cdpCall(pick.name, target, 'Runtime.evaluate', { expression: expr, returnByValue: true });
    last = res.result && res.result.value;
    if (last) { console.log(JSON.stringify({ ok: true, matched: last, waitedMs: Date.now() - t0 })); return; }
    await new Promise(r => setTimeout(r, 250));
  }
  die('wait: timed out after ' + timeout + 'ms waiting for ' + JSON.stringify(what));
}

async function cmdReload() {
  const pick = await findSocket() || die('no devtools socket; enable debugging and restart the app first');
  const target = firstPage(await listTargets(pick.name));
  await cdpCall(pick.name, target, 'Page.reload', { ignoreCache: false });
  console.log('reloaded: ' + target.url);
}

async function cmdServe(portArg) {
  const pick = await findSocket() || die('no devtools socket; run `dsh-mobile cdp on`, restart the app, then retry');
  const port = Number(portArg || 9222);
  const server = net.createServer((client) => {
    const up = net.connect({ path: '\0' + pick.name });
    client.pipe(up).pipe(client);
    const kill = () => { try { up.destroy(); } catch {} ; try { client.destroy(); } catch {} };
    up.on('error', kill); client.on('error', kill);
  });
  server.listen(port, '127.0.0.1', () => {
    console.log(`CDP bridge ready: http://127.0.0.1:${port}  ->  ${pick.name}`);
    console.log('  curl -s 127.0.0.1:' + port + '/json/list');
    console.log('  Playwright: connect_over_cdp("http://127.0.0.1:' + port + '")');
    console.log('Ctrl-C to stop.');
  });
  process.on('SIGINT', () => { console.log('\nstopped'); process.exit(0); });
}

async function cmdAppShot(outArg, maxArg) {
  const max = Number(maxArg || 1280);
  const res = await bridge('GET', `/app-screenshot?max=${max}`);
  if (res.status >= 400) die(res.body.trim());
  let info = {};
  try { info = JSON.parse(res.body); } catch {}
  if (outArg && info.path) {
    fs.copyFileSync(info.path, outArg);
    info.path = outArg;
  }
  console.log(JSON.stringify(info));
}

async function cmdBridge(args) {
  const [method, p, body] = args;
  if (!method || !p) die('usage: dsh-mobile bridge <GET|POST> <path> [json-body]');
  const res = await bridge(method, p, body);
  const out = res.body.trim();
  console.log(out.length > 20000 ? out.slice(0, 20000) + '\n… (truncated)' : out);
  if (res.status >= 400) process.exit(2);
}

function parseSetPairs(pairs) {
  const out = {};
  for (const kv of pairs) {
    const i = kv.indexOf('=');
    if (i <= 0) die('want key=value, got ' + JSON.stringify(kv));
    const k = kv.slice(0, i);
    let v = kv.slice(i + 1);
    if (v === 'true' || v === 'false') out[k] = (v === 'true');
    else if (/^-?\d+$/.test(v)) out[k] = parseInt(v, 10);
    else out[k] = v;
  }
  return out;
}

async function cmdApp(args) {
  const sub = args[0];
  const rest = args.slice(1);
  if (!sub) die('usage: dsh-mobile app status|net|settings [set k=v...]|open settings|terminal|main');
  const call = async (method, path, body) => {
    let res;
    try {
      res = await bridge(method, path, body);
    } catch (e) {
      die('app: AgentBridge unreachable (' + ((e && e.message) || e) + '); is the engine running?');
    }
    const text = res.body.trim();
    if (res.status >= 400) die(text);
    try { console.log(JSON.stringify(JSON.parse(text), null, 2)); } catch { console.log(text); }
    return text;
  };
  switch (sub) {
    case 'status': return call('GET', '/status');
    case 'net': {
      const res = await bridge('GET', '/status');
      if (res.status >= 400) die(res.body.trim());
      const o = JSON.parse(res.body);
      console.log(JSON.stringify({ net: o.net, battery: o.battery, screen: o.screen, app: { foreground: o.app && o.app.foreground, resumed_activity: o.app && o.app.resumed_activity } }, null, 2));
      return;
    }
    case 'settings': {
      if (rest[0] === 'set' || rest[0] === 'write') {
        const set = parseSetPairs(rest.slice(1));
        return call('POST', '/settings', { set });
      }
      return call('GET', '/settings');
    }
    case 'open': {
      const notify = rest.includes('--notify');
      const name = rest.filter(a => a !== '--notify')[0] || 'settings';
      return call('POST', '/activity', notify ? { name, notifyIfBlocked: true } : { name });
    }
    default: {
      // allow `dsh-mobile app GET /status` style passthrough
      if (/^(GET|POST|PUT|DELETE)$/i.test(sub)) return call(sub.toUpperCase(), rest[0] || '/status', rest[1]);
      die('usage: dsh-mobile app status|net|settings [set k=v...]|open settings|terminal|main');
    }
  }
}

function cmdUi(args) {
  const json = args.includes('--json');
  const ui = {
    layers: {
      native: {
        what: 'Android activities + the native top-bar overlay (engine status / restart / hide / gear)',
        entries: ['top-bar gear -> SettingsActivity (permission centre, plugin centre, engine restart, font, mobile-UI rollback)', 'top-bar hide -> capsule handle (drag it, tap to restore the toolbar)', 'terminal activity (native terminal)'],
        automation: 'accessibility taps / dsh-mobile appshot. CDP cannot see this layer. Do NOT `am start` the app\'s own activities: Android may block the background start and still exit 0.',
      },
      web: {
        what: 'the React WebUI at http://127.0.0.1:3080 (WebView)',
        entries: ['drawer (sidebar / files / sessions)', 'account menu -> 设置 (Settings): a full-screen dialog overlay; the URL stays "/", it is NOT a /settings route', 'right pane tabs (document preview / engine terminal)'],
        automation: 'CDP: dsh-mobile cdp click \'button[aria-label="菜单"]\' opens the drawer; click-text 设置 opens the settings overlay; use click/click-text/tap/type/key (real Input events), never element.click().',
      },
      terminal: {
        what: 'Termux shell inside the engine and the native terminal activity',
        entries: ['native terminal activity (own tabs / extra keys)', 'engine sidebar terminal (PTY owned by the engine)'],
        automation: 'just run commands; rm on /sdcard goes to dsh-trash; exit closes the tab.',
      },
    },
    ambiguous: {
      '设置': ['web: account menu -> 设置 (overlay, URL unchanged)', 'native: top-bar gear -> Android SettingsActivity'],
      '插件': ['web: drawer -> 插件 / 插件市场', 'native: Settings -> plugin centre'],
      '终端': ['native terminal activity', 'engine right-pane terminal (PTY)'],
    },
    rule: 'If the layer is ambiguous (native / web / terminal), ask the user which one before trying more than two steps; never assume `am start` or GET /settings works.',
  };
  if (json) { console.log(JSON.stringify(ui, null, 2)); return; }
  console.log('DSH Mobile UI layers');
  for (const [k, v] of Object.entries(ui.layers)) {
    console.log('\n[' + k + '] ' + v.what);
    for (const e of v.entries) console.log('  - ' + e);
    console.log('  automation: ' + v.automation);
  }
  console.log('\nambiguous words:');
  for (const [k, v] of Object.entries(ui.ambiguous)) console.log('  ' + k + ': ' + v.join(' | '));
  console.log('\nrule: ' + ui.rule);
}

function cmdInfo() {
  console.log('dsh-mobile helper');
  console.log('  node       : ' + process.version);
  console.log('  prefix     : ' + PREFIX);
  console.log('  dsh home   : ' + DSH_HOME);
  console.log('  doc        : ' + DOC + (fs.existsSync(DOC) ? '' : ' (missing)'));
  console.log('  bridge     : 127.0.0.1:' + BRIDGE_PORT + ' token=' + (bridgeToken() ? 'yes' : 'NO'));
  const proc = readProcSockets();
  console.log('  devtools   : ' + (proc.ok ? (proc.sockets.size ? [...proc.sockets.keys()].join(', ') : 'none (proc readable)') : 'proc unreadable — discovery uses bridge/scan'));
  console.log('  debug flag : ' + FLAG + ' [' + (fs.existsSync(FLAG) ? 'present' : 'absent') + ']');
}

const argv = process.argv.slice(2);
// Support both `dsh-mobile cdp <sub>` (documented form) and the bare `dsh-mobile <sub>`.
let cmd = argv[0];
let rest = argv.slice(1);
if (cmd === 'cdp') {
  cmd = rest[0] || 'status';
  rest = rest.slice(1);
}
if (cmd === 'app') {
  cmd = 'app';
}
try {
  switch (cmd) {
    case 'status': await cmdStatus(); break;
    case 'on': case 'enable': cmdOn(); break;
    case 'off': case 'disable': cmdOff(); break;
    case 'list': await cmdList(); break;
    case 'eval': await cmdEval(rest.join(' ')); break;
    case 'reload': await cmdReload(); break;
    case 'click': await cmdClick(rest.filter(a => !a.startsWith('--')).join(' ') || rest[0], rest.includes('--mouse'), { touch: rest.includes('--touch') }); break;
    case 'click-text': {
      const wi = rest.indexOf('--wait-ms');
      const waitMs = wi >= 0 ? Number(rest[wi + 1]) : 1500;
      await cmdClickText(rest.filter((a, i) => !a.startsWith('--') && !(wi >= 0 && i === wi + 1)).join(' '),
        rest.includes('--exact'), rest.includes('--mouse'), { any: rest.includes('--any'), waitMs, touch: rest.includes('--touch') });
      break;
    }
    case 'tap': await cmdTap(rest[0], rest[1]); break;
    case 'close': await cmdClose(); break;
    case 'drawer': await cmdDrawer(); break;
    case 'type': {
      const selIdx = rest.indexOf('--selector');
      const sel = selIdx >= 0 ? rest[selIdx + 1] : null;
      const text = (selIdx >= 0 ? rest.slice(0, selIdx) : rest).join(' ');
      await cmdType(text, sel); break;
    }
    case 'key': await cmdKey(rest[0]); break;
    case 'scroll': await cmdScroll(rest[0]); break;
    case 'wait': await cmdWait(rest[0], rest[1]); break;
    case 'serve': await cmdServe(rest[0]); break;
    case 'appshot': await cmdAppShot(rest[0], rest[1]); break;
    case 'bridge': await cmdBridge(rest); break;
    case 'doctor': await cmdDoctor(); break;
    case 'app': await cmdApp(rest); break;
    case 'ui': cmdUi(rest); break;
    case 'info': cmdInfo(); break;
    case 'doc': console.log(fs.readFileSync(DOC, 'utf8')); break;
    default:
      console.log(`dsh-mobile — WebView CDP bridge, AgentBridge client, app info

usage (CDP subcommands also accept the shorter "dsh-mobile <sub>" form):
  dsh-mobile app status                 app/engine/net/battery/memory/storage/permissions snapshot
  dsh-mobile app net                    just network + battery + screen
  dsh-mobile app settings [set k=v...]  read or write native settings
  dsh-mobile app open <settings|terminal|main>   open a native page (reports resumed activity)
  dsh-mobile ui [--json]                layer map: native / web / terminal, ambiguous words
  dsh-mobile info                       paths, node, sockets, bridge token state
  dsh-mobile doc                        print $DSH_HOME/DSH-MOBILE.md
  dsh-mobile appshot [out.png] [max]    native screenshot of the app window (PixelCopy)
  dsh-mobile cdp on|off                 toggle the webview-debug flag (restart app afterwards)
  dsh-mobile cdp status                 flag/socket/target diagnostics
  dsh-mobile cdp doctor                 layered end-to-end diagnostics (flag→bridge→socket→CDP)
  dsh-mobile cdp list                   /json/list targets
  dsh-mobile cdp serve [port]           bridge abstract devtools socket -> 127.0.0.1:port (default 9222)
  dsh-mobile cdp eval <js>              Runtime.evaluate in the WebView page, prints the value
  dsh-mobile cdp click <css>            real mouse click (Input.dispatchMouseEvent) on a selector
  dsh-mobile cdp click-text <text>      real click on the smallest visible element containing <text>
  dsh-mobile cdp tap <x> <y>            real touch tap at viewport coordinates
  dsh-mobile cdp type <text> [--selector css]   focus (optional) then Input.insertText
  dsh-mobile cdp key <Enter|Tab|Escape|Backspace|Arrow*>   dispatch a real key event
  dsh-mobile cdp scroll <dy>            mouse-wheel scroll
  dsh-mobile cdp wait <css|text> [ms]   poll until a selector is visible or text appears
  dsh-mobile cdp reload                 Page.reload
  dsh-mobile bridge <GET|POST> <path> [json]   call the AgentBridge API (token handled)

CDP quick start:
  dsh-mobile cdp on && (ask the user to reopen the app)
  nohup dsh-mobile cdp serve 9222 >/tmp/cdp.log 2>&1 &
  curl -s 127.0.0.1:9222/json/list
  dsh-mobile cdp eval 'document.title'`);
      if (cmd) process.exit(1);
  }
} catch (e) {
  die(String(e && e.stack || e));
}
