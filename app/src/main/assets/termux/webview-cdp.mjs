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
    console.log('webview_dbg: ' + (i.webview_debug ? 'enabled (flag read at process start)' : 'disabled'));
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
      (br.info.webview_debug ? '' : ' → flag was created after the process started; restart the app'));
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
  fs.mkdirSync(path.dirname(FLAG), { recursive: true });
  fs.writeFileSync(FLAG, '');
  console.log('webview-debug flag created: ' + FLAG);
  console.log('Now restart the app so `WebView.setWebContentsDebuggingEnabled(true)` runs.');
  console.log('Ask the user to reopen the app (force-stopping it also kills the engine you are running in).');
  console.log('After the restart: dsh-mobile cdp status');
}

function cmdOff() {
  try { fs.unlinkSync(FLAG); console.log('flag removed: ' + FLAG); } catch { console.log('flag was already absent'); }
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
        automation: 'CDP: dsh-mobile cdp serve 9222 + eval/Playwright. Deep links are client-side only; /settings is not a route.',
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
try {
  switch (cmd) {
    case 'status': await cmdStatus(); break;
    case 'on': case 'enable': cmdOn(); break;
    case 'off': case 'disable': cmdOff(); break;
    case 'list': await cmdList(); break;
    case 'eval': await cmdEval(rest.join(' ')); break;
    case 'reload': await cmdReload(); break;
    case 'serve': await cmdServe(rest[0]); break;
    case 'appshot': await cmdAppShot(rest[0], rest[1]); break;
    case 'bridge': await cmdBridge(rest); break;
    case 'doctor': await cmdDoctor(); break;
    case 'ui': cmdUi(rest); break;
    case 'info': cmdInfo(); break;
    case 'doc': console.log(fs.readFileSync(DOC, 'utf8')); break;
    default:
      console.log(`dsh-mobile — WebView CDP bridge, AgentBridge client, app info

usage (CDP subcommands also accept the shorter "dsh-mobile <sub>" form):
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
