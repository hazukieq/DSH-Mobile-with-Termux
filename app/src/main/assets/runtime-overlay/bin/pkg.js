#!/usr/bin/env node
'use strict';

/**
 * [dsh-android] 真·包管理器前端：Termux 官方仓库 + 交叉编译的 dpkg + proot 路径映射。
 *
 * ## 为什么长这样
 *
 * Termux 的 .deb 内部路径全是 `/data/data/com.termux/files/usr/...`，而本 App 的前缀是
 * `/data/data/app.dsh.mobile/files/engine`（不同包名，Android 不允许我们创建别人的目录）。
 * 直接 dpkg -i 会把文件铺到错误位置。解法是 **proot 的 bind**：
 *
 *   proot -r / -b <engine>:/data/data/com.termux/files/usr  <engine>/bin/dpkg ...
 *
 * 于是在 dpkg 与维护脚本眼里，Termux 前缀真实存在且**就是我们的引擎目录**：
 *   · 包里 `/data/data/com.termux/files/usr/bin/vim` → 实际写到 `<engine>/bin/vim`
 *   · 维护脚本 shebang `#!/data/data/com.termux/files/usr/bin/bash` → `<engine>/bin/bash`
 * 而 dpkg 二进制本身是按**我们前缀**重编的，它的 admindir（`<prefix>/var/lib/dpkg`）、
 * `PREFIX`、`dpkg-deb` 路径都天然正确 —— proot 只负责把 Termux 那半边路径接上。
 *
 * 仓库索引用 `Packages.gz`（Termux 同时提供 .gz，node 的 zlib 直接解），依赖用
 * dpkg 兼容的版本比较做递归解析；安装交给真 dpkg（--unpack 后 --configure -a），
 * 所以状态数据库、卸载、文件清单都是真的。
 *
 * 子命令：update / install <pkg...> / remove <pkg...> / list / search <kw> / info <pkg>
 */

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const https = require('node:https');
const http = require('node:http');
const { spawnSync } = require('node:child_process');

const ROOT = process.env.PREFIX || '/data/data/app.dsh.mobile/files/engine';
const HOME = process.env.HOME || path.join(ROOT, '..', 'dsh-home');
const TMPDIR = process.env.TMPDIR || path.join(ROOT, '..', 'tmp');
const CACHE = path.join(ROOT, 'var', 'cache', 'pkg');
const DPKG_ADMIN = path.join(ROOT, 'var', 'lib', 'dpkg');
const DPKG_STATUS = path.join(DPKG_ADMIN, 'status');
const TERMUX_PREFIX = '/data/data/com.termux/files/usr';
const TERMUX_HOME = '/data/data/com.termux/files/home';

const MIRRORS = [
  'https://packages.termux.dev/apt/termux-main',
  'https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main',
  'https://mirrors.ustc.edu.cn/termux/apt/termux-main',
];

/** 这些是 Termux App 自己的包：装进本 App 既没用也可能干扰，装依赖时跳过 */
const SKIP_DEPS = new Set([
  'termux-am', 'termux-am-socket', 'termux-tools', 'termux-keyring', 'termux-licenses',
  'termux-exec', 'termux-core', 'termux-elf-cleaner',
]);

function archName() {
  if (process.arch === 'arm64') return 'aarch64';
  if (process.arch === 'x64') return 'x86_64';
  if (process.arch === 'arm') return 'arm';
  if (process.arch === 'ia32') return 'i686';
  return process.arch;
}
const ARCH = archName();

// ── 基础工具 ────────────────────────────────────────────────────────────────

function die(msg) {
  process.stderr.write('pkg: ' + msg + '\n');
  process.exit(1);
}

function log(msg) {
  process.stdout.write(msg + '\n');
}

function fetchBuffer(url, timeoutMs = 60000, redirects = 0) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https:') ? https : http;
    const req = mod.get(url, { timeout: timeoutMs, headers: { 'User-Agent': 'dsh-android-pkg/1' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 5) {
        res.resume();
        const next = new URL(res.headers.location, url).toString();
        resolve(fetchBuffer(next, timeoutMs, redirects + 1));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error('HTTP ' + res.statusCode + ' ' + url));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
    });
    req.on('timeout', () => req.destroy(new Error('timeout ' + url)));
    req.on('error', reject);
  });
}

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

/** dpkg 版本比较（epoch:upstream-revision，`~` 排在任何字符前面） */
function verrevcmp(a, b) {
  const order = (c) => {
    if (c === '~') return -1;
    if (c === undefined) return 0;
    if (/[0-9]/.test(c)) return 0;
    if (/[a-zA-Z]/.test(c)) return c.charCodeAt(0);
    return c.charCodeAt(0) + 256;
  };
  let i = 0, j = 0;
  while (i < a.length || j < b.length) {
    let firstDiff = 0;
    while ((i < a.length && !/[0-9]/.test(a[i])) || (j < b.length && !/[0-9]/.test(b[j]))) {
      const ac = order(a[i]);
      const bc = order(b[j]);
      if (ac !== bc) return ac - bc;
      i++; j++;
    }
    while (a[i] === '0') i++;
    while (b[j] === '0') j++;
    let firstDigit = 0;
    while (i < a.length && /[0-9]/.test(a[i]) && j < b.length && /[0-9]/.test(b[j])) {
      if (!firstDiff) firstDiff = a.charCodeAt(i) - b.charCodeAt(j);
      i++; j++;
    }
    if (i < a.length && /[0-9]/.test(a[i])) return 1;
    if (j < b.length && /[0-9]/.test(b[j])) return -1;
    if (firstDiff) return firstDiff;
    void firstDigit;
  }
  return 0;
}

function parseVersion(v) {
  const s = String(v || '').trim();
  const c = s.indexOf(':');
  const epoch = c >= 0 ? Number(s.slice(0, c)) : 0;
  const rest = c >= 0 ? s.slice(c + 1) : s;
  const r = rest.lastIndexOf('-');
  return { epoch, upstream: r >= 0 ? rest.slice(0, r) : rest, revision: r >= 0 ? rest.slice(r + 1) : '' };
}

function versionCompare(a, b) {
  const x = parseVersion(a), y = parseVersion(b);
  if (x.epoch !== y.epoch) return x.epoch - y.epoch;
  let r = verrevcmp(x.upstream, y.upstream);
  if (r === 0) r = verrevcmp(x.revision, y.revision);
  return r < 0 ? -1 : r > 0 ? 1 : 0;
}

// ── 索引 / 已装数据库 ────────────────────────────────────────────────────────

function parseStanzas(text) {
  const out = [];
  let cur = null;
  for (const line of text.split('\n')) {
    if (line.trim() === '') {
      if (cur) out.push(cur);
      cur = null;
      continue;
    }
    if (/^[ \t]/.test(line)) {
      // 续行（Description 等）
      if (cur) {
        const last = Object.keys(cur).pop();
        cur[last] += '\n' + line.trim();
      }
      continue;
    }
    const i = line.indexOf(':');
    if (i < 0) continue;
    cur = cur || {};
    cur[line.slice(0, i)] = line.slice(i + 1).trim();
  }
  if (cur) out.push(cur);
  return out;
}

function ensureCacheDirs() {
  for (const d of [CACHE, DPKG_ADMIN, path.join(DPKG_ADMIN, 'info'), path.join(DPKG_ADMIN, 'updates'), path.join(DPKG_ADMIN, 'triggers'), TMPDIR]) {
    fs.mkdirSync(d, { recursive: true });
  }
  if (!fs.existsSync(DPKG_STATUS)) fs.writeFileSync(DPKG_STATUS, '');
}

function indexPath(mirror) {
  return mirror + '/dists/stable/main/binary-' + ARCH + '/Packages.gz';
}

async function loadIndex(force) {
  ensureCacheDirs();
  const cache = path.join(CACHE, 'Packages.gz');
  if (force || !fs.existsSync(cache)) {
    let lastErr = null;
    for (const m of MIRRORS) {
      try {
        process.stdout.write('update: ' + indexPath(m) + ' ... ');
        const buf = await fetchBuffer(indexPath(m), 90000);
        const text = zlib.gunzipSync(buf).toString('utf8');
        fs.writeFileSync(cache, zlib.gzipSync(Buffer.from(text, 'utf8')));
        fs.writeFileSync(path.join(CACHE, 'mirror.txt'), m);
        log('ok (' + (text.length / 1024 / 1024).toFixed(1) + ' MB)');
        return { text, mirror: m };
      } catch (e) {
        log('fail: ' + e.message);
        lastErr = e;
      }
    }
    throw lastErr || new Error('no mirror reachable');
  }
  const mirror = (fs.existsSync(path.join(CACHE, 'mirror.txt'))
    ? fs.readFileSync(path.join(CACHE, 'mirror.txt'), 'utf8').trim()
    : MIRRORS[0]);
  const text = zlib.gunzipSync(fs.readFileSync(cache)).toString('utf8');
  return { text, mirror };
}

function installedPackages() {
  const map = new Map();
  if (!fs.existsSync(DPKG_STATUS)) return map;
  const text = fs.readFileSync(DPKG_STATUS, 'utf8');
  const lines = text.split('\n');
  let cur = null;
  const flush = () => {
    if (cur && cur.Package) map.set(cur.Package, cur);
  };
  for (const line of lines) {
    if (line.trim() === '') { flush(); cur = null; continue; }
    const i = line.indexOf(':');
    if (i < 0) continue;
    cur = cur || {};
    cur[line.slice(0, i)] = line.slice(i + 1).trim();
  }
  flush();
  return map;
}

// ── 依赖解析 ────────────────────────────────────────────────────────────────

function parseDeps(field) {
  if (!field) return [];
  return field.split(',').map((g) => g.trim()).filter(Boolean).map((g) => {
    // "a (>= 1) | b"  → 候选列表（暂不校验版本约束，安装后由 dpkg --configure 兜底）
    return g.split('|').map((alt) => {
      const m = alt.trim().match(/^([^\s:()]+)/);
      return m ? m[1] : null;
    }).filter(Boolean);
  });
}

function resolve(roots, index, installed, skip) {
  const plan = [];
  const seen = new Set();
  const queue = roots.map((r) => r);
  while (queue.length) {
    const name = queue.shift();
    if (!name || seen.has(name)) continue;
    seen.add(name);
    if (SKIP_DEPS.has(name)) continue;
    const cur = installed.get(name);
    if (cur && cur.Status && /install ok installed/.test(cur.Status)) continue;
    const entry = index.get(name);
    if (!entry) {
      if (roots.includes(name)) throw new Error('package not found in repo: ' + name);
      continue; // 间接依赖缺失：多半是 virtual package，交给 dpkg 报错
    }
    plan.push(entry);
    for (const alts of parseDeps(entry['Pre-Depends']).concat(parseDeps(entry.Depends))) {
      const chosen = alts.find((a) => index.has(a)) || alts.find((a) => installed.has(a));
      if (chosen) queue.push(chosen);
    }
  }
  return plan;
}

// ── proot + dpkg ────────────────────────────────────────────────────────────

function findProot() {
  const candidates = [
    path.join(ROOT, 'bin', 'proot'),
    path.join(ROOT, '..', 'linux', 'proot', 'bin', 'proot'),
  ];
  for (const c of candidates) {
    try { fs.accessSync(c, fs.constants.X_OK); return c; } catch (_) { /* next */ }
  }
  return null;
}

/**
 * proot 需要一个「假 rootfs」：guest 侧的 `/data/data/com.termux/files/usr` 必须在
 * rootfs 里**存在**（proot 不会为 -b 自动创建目标，缺失时会 sanitize 掉该绑定，
 * 维护脚本和 .deb 内的 Termux 路径就全废了）。所以这里在 <files>/pkgroot 下搭一个
 * 骨架，把需要的宿主机目录 bind 进去，再把引擎同时绑到「Termux 前缀」和「本 App
 * 前缀」两个路径 —— dpkg 自己按 app 前缀找 admindir，包内路径按 Termux 前缀落盘。
 */
const PKGROOT = path.join(ROOT, '..', 'pkgroot');
// App 的 filesDir 在 /data/user/0/...，但内核与 dpkg 会把它规范化成 /data/data/...
// 维护脚本路径是按规范化后的绝对路径报的，所以两个前缀都要在 guest 里存在。
const ROOT_REAL = (() => {
  // /data/user/0 是 bind mount，realpath() 不会把它解析成 /data/data；
  // 而 dpkg 是按包名编译的，rpath/admindir 都是 /data/data/... 形式。
  // 这里直接用字符串换算出规范路径（同时保留 realpath 兜底）。
  if (ROOT.startsWith('/data/user/0/')) return ROOT.replace('/data/user/0/', '/data/data/');
  try { return fs.realpathSync(ROOT); } catch (_) { return ROOT; }
})();
const PKGTMP = path.join(ROOT, '..', 'pkgtmp');

function ensureProotRoot() {
  fs.mkdirSync(PKGTMP, { recursive: true });
  // 运行时安装器会把 libexec/proot/loader 铺成 0600；proot 要 exec 这个 loader，
  // 没有 +x 会表现为 "execve(...): Permission denied"（排查过一轮）。
  // overlay 部署的文件（tar 过滤器）也要可执行，否则 dpkg-deb 调不到。
  for (const f of ['tar', 'sh']) {
    try { fs.chmodSync(path.join(ROOT, 'bin', f), 0o755); } catch (_) { /* 可选 */ }
  }
  for (const f of ['loader', 'loader32']) {
    try { fs.chmodSync(path.join(ROOT, 'libexec', 'proot', f), 0o755); } catch (_) { /* 32 位可能不存在 */ }
  }
  const bindTargets = [
    '/data/data/com.termux/files/usr',
    '/data/data/com.termux/files/home',
    ROOT,
    ROOT_REAL,
    '/system', '/vendor', '/apex', '/dev', '/proc', '/sys', '/etc', '/data',
  ];
  for (const t of bindTargets) fs.mkdirSync(path.join(PKGROOT, t), { recursive: true });
}

function prootArgv(proot) {
  ensureProotRoot();
  return [
    proot, '--link2symlink', '-0', '-r', PKGROOT,
    '-b', '/system:/system', '-b', '/vendor:/vendor', '-b', '/apex:/apex',
    '-b', '/dev:/dev', '-b', '/proc:/proc', '-b', '/sys:/sys', '-b', '/etc:/etc',
    // ⚠️ 不要再 bind /data：那会把 pkgroot 里造好的 /data/data/... 目标目录整个盖掉，
    // 后面 Termux 前缀的绑定就找不到落点（proot 直接报 dpkg not found）。
    '-b', TMPDIR + ':/tmp',
    // 引擎树在 guest 里出现两次：一次是 Termux 前缀（.deb 路径/脚本），
    // 一次是它自己的真实路径（dpkg 的 admindir、prefix 编译常量）。
    // 引擎树在 guest 里出现三次：Termux 前缀（.deb 路径/脚本）、用户路径、规范路径。
    // dpkg 的 admindir/rpath 是 /data/data/... 规范路径，维护脚本路径也是它；
    // 包内路径与脚本 shebang 则是 com.termux 前缀。
    '-b', ROOT + ':' + TERMUX_PREFIX,
    '-b', ROOT + ':' + ROOT,
    '-b', ROOT_REAL + ':' + ROOT_REAL,
    '-b', HOME + ':' + TERMUX_HOME,
    '-w', '/',
  ];
}

function dpkgPath() {
  const host = path.join(ROOT, 'bin', 'dpkg');
  try { fs.accessSync(host, fs.constants.X_OK); } catch (_) {
    die('dpkg is not installed in this runtime yet (expected ' + host + ').\n' +
        '     Update the runtime (dsh-update pull) or reinstall the APK that ships dpkg.');
  }
  // ⚠️ 必须用**guest 侧路径**：proot 会把 TERMUX_PREFIX bind 到 <engine>，并且会对
  // 落在 bind 宿主机一侧的路径做反向改写 —— 直接 exec <engine>/bin/dpkg 会被解成
  // /data/data/com.termux/files/usr/bin/dpkg 再去解析一遍，反而找不到。
  return path.join(TERMUX_PREFIX, 'bin', 'dpkg');
}

function runDpkg(args, opts = {}) {
  const proot = findProot();
  if (!proot) {
    die('proot not found (needed to map the Termux prefix).\n' +
        '     Install any Linux distro once from the Linux page, or update the runtime.');
  }
  // ⚠️ 必须给 dpkg 显式 LD_LIBRARY_PATH：dpkg 的 RUNPATH 是 app 前缀的绝对路径，
  // 但 proot 的路径翻译对链接器在 guest 里查 rpath 的场景不可靠（实测报
  // `library "libmd.so" not found`，同一个文件在 guest 里 ls 得到、显式
  // LD_LIBRARY_PATH 就正常）。用 sh 包一层，只让 dpkg 及其子进程继承它，
  // proot 自身不带（否则系统库会误加载引擎里的 liblzma，报 Xzs_Construct 缺失）。
  const guestLibs = ROOT + '/lib:' + TERMUX_PREFIX + '/lib';
  const inner = 'LD_LIBRARY_PATH=' + JSON.stringify(guestLibs) + '; export LD_LIBRARY_PATH; exec ' +
    JSON.stringify(dpkgPath()) + ' "$@"';
  const argv = prootArgv(proot).concat(['/system/bin/sh', '-c', inner, 'dpkg'], args);
  const env = Object.assign({}, process.env, {
    // ⚠️ 必须剥掉继承来的 LD_LIBRARY_PATH：引擎环境里它指向 <engine>/lib，
    // 而那里现在有 Termux 版 liblzma；系统库（如 libunwindstack）一旦被按这个
    // 路径解析就会找不到 Xzs_* 符号（proot/dpkg 起不来）。它们自己的依赖靠
    // 构建时写入的 RUNPATH（<prefix>/lib）解析，不需要 LD_LIBRARY_PATH。
    PROOT_NO_SECCOMP: '1',
    PROOT_TMP_DIR: PKGTMP,
    PREFIX: TERMUX_PREFIX,
    HOME: TERMUX_HOME,
    PATH: ROOT + '/bin:/system/bin:/system/xbin',
    DEBIAN_FRONTEND: 'noninteractive',
  });
  delete env.LD_LIBRARY_PATH;
  const r = spawnSync(argv[0], argv.slice(1), { stdio: 'inherit', env, cwd: '/' });
  return r.status === null ? 1 : r.status;
}

// ── 下载 ────────────────────────────────────────────────────────────────────

async function downloadOne(entry, mirror, force) {
  const file = path.join(CACHE, path.basename(entry.Filename));
  if (!force && fs.existsSync(file)) {
    const have = sha256(fs.readFileSync(file));
    if (!entry.SHA256 || have === entry.SHA256) return file;
  }
  const url = mirror + '/' + entry.Filename;
  process.stdout.write('get: ' + entry.Package + ' ' + entry.Version + ' ... ');
  const buf = await fetchBuffer(url, 300000);
  if (entry.SHA256 && sha256(buf) !== entry.SHA256) {
    log('sha256 mismatch');
    throw new Error('sha256 mismatch for ' + entry.Package);
  }
  fs.writeFileSync(file, buf);
  log('ok (' + (buf.length / 1024 / 1024).toFixed(1) + ' MB)');
  return file;
}

async function downloadAll(entries, mirror, force) {
  const files = [];
  for (const e of entries) files.push(await downloadOne(e, mirror, force));
  return files;
}

// ── 安装后修复（硬编码 com.termux 的运行时路径） ──────────────────────────────

/**
 * update-alternatives / 维护脚本会在 guest 里创建**绝对路径**符号链接，指向
 * `/data/data/com.termux/files/usr/...`。proot 里那是 bind 后的引擎目录，出了 proot
 * 就是悬空链接（`bin/vim` 直接 inaccessible）。这里把这类链接改写成**相对链接**
 * 指向引擎内的真实文件，这样 proot 内外都能用。
 */
function rewriteTermuxLinks() {
  let fixed = 0;
  const visit = (dir, depth) => {
    if (depth > 4) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (_) { return; }
    for (const e of entries) {
      const p = path.join(dir, e.name);
      let st;
      try { st = fs.lstatSync(p); } catch (_) { continue; }
      if (st.isSymbolicLink()) {
        let tgt;
        try { tgt = fs.readlinkSync(p); } catch (_) { continue; }
        let realTarget = null;
        if (tgt.startsWith(TERMUX_PREFIX)) {
          realTarget = path.join(ROOT, tgt.slice(TERMUX_PREFIX.length));
        } else if (tgt.startsWith(ROOT_REAL) || tgt.startsWith(ROOT)) {
          realTarget = tgt;
        }
        if (!realTarget || !fs.existsSync(realTarget)) continue;
        try {
          fs.unlinkSync(p);
          fs.symlinkSync(path.relative(path.dirname(p), realTarget), p);
          fixed++;
        } catch (_) { /* 留原样 */ }
      } else if (st.isDirectory() && e.name !== 'node_modules') {
        visit(p, depth + 1);
      }
    }
  };
  for (const d of ['bin', 'libexec', 'lib', 'etc']) visit(path.join(ROOT, d), 0);
  if (fixed) log('fixup: rewrote ' + fixed + ' Termux-prefix symlink(s)');
  return fixed;
}

function writeFixups() {
  const dir = path.join(ROOT, 'etc', 'profile.d');
  fs.mkdirSync(dir, { recursive: true });
  const lines = [
    '# generated by pkg (dsh-android)',
    '# Termux 仓库里的二进制把 PREFIX 编译成了 /data/data/com.termux/files/usr；',
    '# 本 App 的前缀不同，这里把最常见的“运行时数据目录”指向真实位置。',
    'export PREFIX="' + ROOT + '"',
    'export TERMUX_PREFIX="' + ROOT + '"',
    '[ -d "' + ROOT + '/share/vim" ] && export VIMRUNTIME="$(ls -d ' + ROOT + '/share/vim/vim[0-9]* 2>/dev/null | tail -1)"',
    '[ -d "' + ROOT + '/lib/python3.12" ] && export PYTHONHOME="' + ROOT + '"',
    '[ -d "' + ROOT + '/lib/git-core" ] && export GIT_EXEC_PATH="' + ROOT + '/lib/git-core"',
    '[ -f "' + ROOT + '/etc/tls/openssl.cnf" ] && export OPENSSL_CONF="' + ROOT + '/etc/tls/openssl.cnf"',
    '',
  ];
  fs.writeFileSync(path.join(dir, '10-termux-paths.sh'), lines.join('\n'));
  // 让交互式 shell 自动加载（引擎的 bash.bashrc 不一定 source profile.d）
  for (const rc of ['bash.bashrc', 'bashrc']) {
    const f = path.join(ROOT, 'etc', rc);
    try {
      const body = fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : '';
      const hook = '[ -f "' + path.join(dir, '10-termux-paths.sh') + '" ] && . "' + path.join(dir, '10-termux-paths.sh') + '"';
      if (!body.includes('10-termux-paths.sh')) {
        fs.writeFileSync(f, body + '\n# [dsh-android pkg] Termux 包运行时路径\n' + hook + '\n');
      }
    } catch (_) { /* 忽略 */ }
  }
  // 让“当前这个 shell”也立即生效的提示（父进程无法被修改环境）
  log('');
  log('note: new shells pick up ' + path.join(dir, '10-termux-paths.sh'));
  log('      current shell:  . ' + path.join(dir, '10-termux-paths.sh'));
}

// ── 子命令 ──────────────────────────────────────────────────────────────────

async function cmdUpdate() {
  await loadIndex(true);
  log('update: done');
}

async function cmdInstall(names, opts) {
  if (!names.length) die('usage: pkg install <package...>');
  ensureCacheDirs();
  // 默认用缓存索引（同一次会话里连续安装不必每次重下 1.8MB；网络抖动时会失败），
  // 只有缓存缺失或显式 --refresh 才重新拉。
  const { text, mirror } = await loadIndex(!!opts.refresh);
  const entries = parseStanzas(text);
  const index = new Map();
  for (const e of entries) if (e.Package) index.set(e.Package, e);
  const installed = installedPackages();
  log('repo: ' + entries.length + ' packages via ' + mirror);
  const plan = resolve(names, index, installed, false);
  if (!plan.length) { log('nothing to do'); return; }
  log('plan: ' + plan.map((p) => p.Package + '=' + p.Version).join(' '));
  const files = await downloadAll(plan, mirror, !!opts.force);
  log('unpack: ' + files.length + ' package(s)');
  let rc = runDpkg(['--unpack'].concat(files));
  if (rc !== 0) die('dpkg --unpack failed (exit ' + rc + ')');
  log('configure:');
  rc = runDpkg(['--configure', '-a']);
  if (rc !== 0) {
    die('dpkg --configure failed (exit ' + rc + '); run "pkg list" to inspect state');
  }
  rewriteTermuxLinks();
  writeFixups();
  log('install: ok');
}

async function cmdRemove(names) {
  if (!names.length) die('usage: pkg remove <package...>');
  ensureCacheDirs();
  const rc = runDpkg(['--remove'].concat(names));
  if (rc !== 0) die('dpkg --remove failed (exit ' + rc + ')');
  log('remove: ok');
}

async function cmdList() {
  const installed = installedPackages();
  if (installed.size === 0) { log('no packages installed via pkg (dpkg database is empty)'); return; }
  const rows = [...installed.values()]
    .filter((p) => p.Status && /install ok installed/.test(p.Status))
    .sort((a, b) => a.Package.localeCompare(b.Package));
  for (const p of rows) log(p.Package.padEnd(24) + ' ' + (p.Version || '') + (p.Description ? '  ' + String(p.Description).split('\n')[0] : ''));
  log('');
  log(rows.length + ' package(s) installed');
}

async function cmdSearch(kw) {
  const { text } = await loadIndex(false);
  const entries = parseStanzas(text);
  const q = String(kw || '').toLowerCase();
  if (!q) die('usage: pkg search <keyword>');
  let n = 0;
  for (const e of entries) {
    const hay = (e.Package + ' ' + (e.Description || '')).toLowerCase();
    if (!hay.includes(q)) continue;
    const desc = (e.Description || '').split('\n')[0];
    log(e.Package.padEnd(26) + ' ' + (e.Version || '').padEnd(22) + ' ' + desc.slice(0, 70));
    if (++n >= 60) { log('… (more matches omitted)'); break; }
  }
  if (!n) log('no match for: ' + kw);
}

async function cmdInfo(name) {
  if (!name) die('usage: pkg info <package>');
  const { text } = await loadIndex(false);
  const e = parseStanzas(text).find((x) => x.Package === name);
  if (!e) die('unknown package: ' + name);
  for (const k of ['Package', 'Version', 'Installed-Size', 'Size', 'Depends', 'Pre-Depends', 'Section', 'Description']) {
    if (e[k] !== undefined) log(k + ': ' + e[k]);
  }
}

function usage() {
  process.stdout.write([
    'usage: pkg <command> [args]',
    '',
    '  update                 refresh the Termux repository index',
    '  install <pkg...>       install packages (dependencies resolved)',
    '  remove <pkg...>        remove packages',
    '  list                   installed packages (real dpkg database)',
    '  search <keyword>       search the repository',
    '  info <pkg>             show repository metadata',
    '',
    'This is a real package manager: Termux repo + cross-compiled dpkg, with the',
    'Termux prefix mapped onto this app\'s runtime prefix via proot.',
    'App extensions (python/git/...) remain available through the Extensions page.',
    '',
  ].join('\n'));
}

async function main() {
  const [sub, ...rest] = process.argv.slice(2);
  const positional = rest.filter((a) => !a.startsWith('-'));
  const opts = { force: rest.includes('--force') || rest.includes('-f'), refresh: rest.includes('--refresh') };
  try {
    switch (sub) {
      case 'update': return await cmdUpdate();
      case 'install':
      case 'i': return await cmdInstall(positional, opts);
      case 'remove':
      case 'rm':
      case 'uninstall': return await cmdRemove(positional);
      case 'list':
      case 'ls': return await cmdList();
      case 'search': return await cmdSearch(positional[0]);
      case 'info': return await cmdInfo(positional[0]);
      case 'fixup': { rewriteTermuxLinks(); writeFixups(); return; }
      case 'debug': {
        // 调试用：在 pkg 的 proot 环境里跑一条命令
        ensureCacheDirs(); ensureProotRoot();
        const proot = findProot();
        if (!proot) die('proot not found');
        const dbg = 'LD_LIBRARY_PATH=' + JSON.stringify(ROOT + '/lib:' + TERMUX_PREFIX + '/lib') + '; export LD_LIBRARY_PATH; ' + (positional[0] || 'echo hi');
        const argv = prootArgv(proot).concat(['/system/bin/sh', '-c', dbg]);
        const env = Object.assign({}, process.env, { PROOT_NO_SECCOMP: '1', PROOT_TMP_DIR: PKGTMP });
        delete env.LD_LIBRARY_PATH;
        env.DSH_PKG_LIBS = ROOT + '/lib';
        const r = spawnSync(argv[0], argv.slice(1), { stdio: 'inherit', env });
        process.exit(r.status === null ? 1 : r.status);
      }
      case 'help':
      case '-h':
      case '--help':
      case undefined: usage(); return;
      default:
        die('unknown command: ' + sub + ' (try pkg help)');
    }
  } catch (e) {
    die(e && e.message ? e.message : String(e));
  }
}

main();
