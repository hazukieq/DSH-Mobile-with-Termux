/**
 * full-check-deps — pre-release dependency audit for the Android engine tree.
 *
 * Catches the bug class that shipped a broken `read_image`: a dependency is
 * *installed* but cannot load on Android (bionic) because its native binary is
 * glibc-only / wrong-arch / missing, with no platform candidate and no wasm
 * fallback bundled.
 *
 * Checks
 *   1 missing-deps   declared dependencies resolve (exports / subpath aware)
 *   2 native-addons  every *.node: ELF arch + bionic vs glibc linkage
 *   3 platform       native-ish packages: candidate for target or wasm fallback
 *   4 probes         (device only) require()/import() each native-ish package
 *   5 capability     (device only) sharp/read_image workflow + backend detection
 *   6 engine         (device only) which sharp backend the live engine mapped
 *   7 sharp-static   loader-aware check for the sharp hotspot (any host)
 *
 * Usage
 *   node full-check-deps.mjs [--root <node_modules>] [--prefix <termux-prefix>]
 *        [--target android-arm64] [--json] [--strict] [--no-runtime] [--quiet]
 *        [--allow pkg1,pkg2]
 *
 *   --strict   warnings also fail the run (release gate)
 *   --allow    comma list of packages whose probe failure is a known/intentional
 *              Android exclusion (downgraded to warn)
 *
 * Defaults
 *   root   : $PREFIX/lib/node_modules when present, else ./node_modules
 *   prefix : PREFIX env, else root/../.. when root ends with /lib/node_modules
 *   target : android-<host arch> on Android, else android-arm64
 *
 * Exit codes: 0 pass (warnings allowed) | 1 fail | 2 usage
 *
 * Device:   full-check-deps
 * CI:       node full-check-deps.mjs --root <dist>/node_modules --strict
 */

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';

const VERSION = '0.2.0';
const HOST_ANDROID = process.platform === 'android';

// ------------------------------------------------------------------ args
function usage(code = 0) {
	process.stdout.write(`full-check-deps ${VERSION}

Usage: full-check-deps [options]

  --root <dir>     node_modules directory to audit (default: auto)
  --prefix <dir>   Termux prefix for LD_LIBRARY_PATH (default: auto)
  --target <p-a>   target platform (default: android-<host arch>)
  --allow <list>   comma-separated packages whose probe failure is expected
  --json           machine-readable report
  --strict         warnings fail the run
  --no-runtime     skip runtime probes / live-engine inspection
  --quiet          only findings + summary
  -h, --help       this help

Severity: FAIL = core capability definitely broken on target (sharp backend,
wrong-arch or glibc native addon, missing engine dependency). WARN = optional
feature/package that cannot load (review, then --allow or fix). Use --strict in
CI to make warnings fail the build.
`);
	process.exit(code);
}

function parseArgs(argv) {
	const a = { root: '', prefix: '', target: '', allow: [], json: false, strict: false, runtime: true, quiet: false };
	for (let i = 0; i < argv.length; i++) {
		const s = argv[i];
		const eq = s.indexOf('=');
		const key = eq > 0 ? s.slice(0, eq) : s;
		const val = () => (eq > 0 ? s.slice(eq + 1) : argv[++i] ?? '');
		if (key === '-h' || key === '--help') usage(0);
		else if (key === '--root') a.root = val();
		else if (key === '--prefix') a.prefix = val();
		else if (key === '--target') a.target = val();
		else if (key === '--allow') a.allow = val().split(',').map((x) => x.trim()).filter(Boolean);
		else if (key === '--json') a.json = true;
		else if (key === '--strict') a.strict = true;
		else if (key === '--no-runtime') a.runtime = false;
		else if (key === '--quiet') a.quiet = true;
		else { process.stderr.write(`full-check-deps: unknown argument ${s}\n`); usage(2); }
	}
	return a;
}

const args = parseArgs(process.argv.slice(2));
const PREFIX =
	args.prefix ||
	process.env.PREFIX ||
	((args.root || '').match(/^(.*)\/lib\/node_modules$/) || [])[1] ||
	'';
const ROOT = path.resolve(
	args.root ||
		(PREFIX && fs.existsSync(path.join(PREFIX, 'lib/node_modules'))
			? path.join(PREFIX, 'lib/node_modules')
			: 'node_modules'),
);
const TARGET = args.target || (HOST_ANDROID ? `android-${process.arch}` : 'android-arm64');
const [TARGET_OS, TARGET_ARCH] = TARGET.split('-');
const LD = [PREFIX && path.join(PREFIX, 'lib'), PREFIX && path.join(PREFIX, 'lib/dsh-engine/lib'), process.env.LD_LIBRARY_PATH]
	.filter(Boolean)
	.join(':');

// ------------------------------------------------------------------ helpers
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const isDir = (p) => { try { return fs.statSync(p).isDirectory(); } catch { return false; } };
const readJson = (p) => { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return null; } };
const rel = (p) => path.relative(ROOT, p) || '.';

// Does a package's shipped JS reference this module specifier? Used to tell a
// genuinely missing runtime dependency from a types-only / dev declaration.
function referencedByJs(pkgDir, spec) {
	const needle = [new RegExp(`from\\s+['"]${escapeRe(spec)}['"]`), new RegExp(`require\\(\\s*['"]${escapeRe(spec)}['"]`), new RegExp(`import\\(\\s*['"]${escapeRe(spec)}['"]`)];
	const roots = ['dist', 'lib', 'src', 'build', 'index.js', 'index.cjs', 'index.mjs'];
	const files = [];
	const walk = (p, depth) => {
		if (depth > 4) return;
		let st;
		try { st = fs.statSync(p); } catch { return; }
		if (st.isFile()) { if (/\.(c|m)?js$/.test(p)) files.push(p); return; }
		if (!st.isDirectory()) return;
		let entries;
		try { entries = fs.readdirSync(p, { withFileTypes: true }); } catch { return; }
		for (const e of entries) {
			if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
			walk(path.join(p, e.name), depth + 1);
		}
	};
	for (const r of roots) walk(path.join(pkgDir, r), 0);
	for (const f of files.slice(0, 200)) {
		let text = '';
		try { text = fs.readFileSync(f, 'utf8').slice(0, 200000); } catch { continue; }
		if (needle.some((n) => n.test(text))) return true;
	}
	return false;
}

// ------------------------------------------------------------------ discovery
const PKGS = (() => {
	const out = [];
	const seen = new Set();
	const walk = (dir, depth) => {
		if (depth > 5 || !isDir(dir)) return;
		let entries;
		try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
		const add = (pkgDir) => {
			if (seen.has(pkgDir)) return;
			seen.add(pkgDir);
			const pkg = readJson(path.join(pkgDir, 'package.json'));
			if (!pkg || !pkg.name) return;
			out.push({ dir: pkgDir, pkg });
			const nested = path.join(pkgDir, 'node_modules');
			if (isDir(nested)) walk(nested, depth + 1);
		};
		for (const e of entries) {
			if (!e.isDirectory() || e.name === 'node_modules' || e.name.startsWith('.')) continue;
			const full = path.join(dir, e.name);
			if (e.name.startsWith('@')) {
				let scoped;
				try { scoped = fs.readdirSync(full, { withFileTypes: true }); } catch { continue; }
				for (const s of scoped) if (s.isDirectory() && !s.name.startsWith('.')) add(path.join(full, s.name));
			} else add(full);
		}
	};
	walk(ROOT, 0);
	return out;
})();
const BY_NAME = new Map(PKGS.map((p) => [p.pkg.name, p]));

// ------------------------------------------------------------------ native-ish classification
const PLATFORM_KEY_RE = /(?:^|[-_/])(darwin|linux|linuxmusl|win32|android|freebsd|openbsd)[-_]?(arm64|x64|arm|ia32|ppc64|s390x|riscv64|wasm32)$/i;
const NATIVEISH = [];
for (const p of PKGS) {
	const scripts = [p.pkg.scripts?.install, p.pkg.scripts?.preinstall, p.pkg.scripts?.postinstall].filter(Boolean).join(' ');
	const hasGyp = fs.existsSync(path.join(p.dir, 'binding.gyp')) || p.pkg.gypfile === true;
	const builds = /(node-gyp|prebuild-install|node-pre-gyp|cmake-js)/.test(scripts) || hasGyp;
	const platDeps = Object.keys(p.pkg.optionalDependencies || {}).filter((k) => PLATFORM_KEY_RE.test(k));
	const isPlatformPkg = PLATFORM_KEY_RE.test(p.pkg.name);
	if ((builds || platDeps.length) && !isPlatformPkg) {
		const base = p.pkg.name.split('/').pop();
		const wasmSibling = PKGS.some((x) => x !== p && /wasm32/i.test(x.pkg.name) && x.pkg.name.includes(base));
		NATIVEISH.push({ ...p, builds, platDeps, wasmSibling });
	}
}
const NATIVEISH_BY_NAME = new Map(NATIVEISH.map((p) => [p.pkg.name, p]));
const ALLOW = new Set(args.allow);

// ------------------------------------------------------------------ native ELF scan
const GLIBC_SYMS = new Set([
	'libc.so.6', 'ld-linux-aarch64.so.1', 'ld-linux-armhf.so.3', 'ld-linux.so.2',
	'libpthread.so.0', 'libstdc++.so.6', 'libm.so.6', 'libdl.so.2', 'libresolv.so.2', 'librt.so.1',
]);
const ARCH_OF = { AArch64: 'arm64', ARM: 'arm', 'Advanced Micro Devices X86-64': 'x64', 'Intel 80386': 'x86' };
const NATIVE_FILES = [];
{
	const walk = (dir, depth) => {
		if (depth > 8) return;
		let entries;
		try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
		for (const e of entries) {
			const full = path.join(dir, e.name);
			if (e.isDirectory()) {
				if (e.name === 'node_modules' || e.name.startsWith('.') || e.name === 'test' || e.name === 'tests') continue;
				walk(full, depth + 1);
			} else if (e.isFile() && e.name.endsWith('.node')) NATIVE_FILES.push(full);
		}
	};
	walk(ROOT, 0);
}
const NATIVE_REPORT = NATIVE_FILES.map((file) => {
	const item = { rel: rel(file), arch: '?', needed: [], glibc: [], kind: 'unknown', notes: [] };
	let head;
	try { head = fs.readFileSync(file).subarray(0, 4).toString('hex'); } catch { item.notes.push('unreadable'); return item; }
	if (head !== '7f454c46') { item.kind = 'not-elf'; return item; }
	const hdr = spawnSync('readelf', ['-h', file], { encoding: 'utf8' });
	const dyn = spawnSync('readelf', ['-d', file], { encoding: 'utf8' });
	if (hdr.error || dyn.error) { item.notes.push('readelf unavailable'); return item; }
	item.arch = ARCH_OF[(hdr.stdout.match(/Machine:\s+(.+)/) || [])[1]?.trim()] || '?';
	item.needed = [...(dyn.stdout || '').matchAll(/\(NEEDED\)\s+Shared library: \[([^\]]+)\]/g)].map((m) => m[1]);
	item.glibc = item.needed.filter((n) => GLIBC_SYMS.has(n));
	item.kind = item.glibc.length ? 'glibc' : 'bionic';
	if (PREFIX) {
		for (const lib of item.needed) {
			if (GLIBC_SYMS.has(lib) || /^lib(c|m|dl|log|android|z|stdc\+\+|c\+\+_shared)\.so/.test(lib)) continue;
			if (!fs.existsSync(path.join(PREFIX, 'lib', lib))) item.notes.push(`missing runtime lib: ${lib}`);
		}
	}
	return item;
});

// ------------------------------------------------------------------ runtime probes (device only)
const PROBES = new Map();

	const PROBE_JS = [
		'(async () => {',
		'  const name = process.env.FCP_PKG;',
		'  const load = async (spec) => {',
		'    try { require(spec); return { ok: true, via: "require" }; }',
		'    catch (e1) {',
		'      try { await import(spec); return { ok: true, via: "import" }; }',
		'      catch (e2) { return { ok: false, error: String((e2 && e2.message) || (e1 && e1.message) || e1).split("\\n")[0], code: (e2 && e2.code) || (e1 && e1.code) || null }; }',
		'    }',
		'  };',
		'  let r = await load(name);',
		'  if (!r.ok && (r.code === "ERR_PACKAGE_PATH_NOT_EXPORTED" || /exports/.test(r.error || ""))) {',
		'    let subpaths = [];',
		'    try { const pj = require(name + "/package.json"); subpaths = Object.keys(pj.exports || {}).filter((k) => k !== "." && k !== "./package.json"); } catch (e) {}',
		'    for (const sp of subpaths.slice(0, 4)) {',
		'      const spec = name + "/" + sp.replace(/^\\.\\//, "");',
		'      const rr = await load(spec);',
		'      if (rr.ok) { rr.via = "subpath " + spec; r = rr; break; }',
		'    }',
		'  }',
		'  console.log(JSON.stringify(r));',
		'})();',
	].join('\n');
if (HOST_ANDROID && args.runtime) {
	for (const p of NATIVEISH) {
		const code = PROBE_JS;
		const r = spawnSync(process.execPath, ['-e', code], {
			cwd: p.dir,
			encoding: 'utf8',
			timeout: 90000,
			env: { ...process.env, FCP_PKG: p.pkg.name, LD_LIBRARY_PATH: LD },
		});
		let res;
		if (r.error) res = { ok: false, error: String(r.error.message) };
		else if (r.status !== 0) res = { ok: false, error: `probe exited ${r.status}: ${(r.stderr || '').trim().split('\n').pop() || ''}` };
		else {
			const line = (r.stdout || '').trim().split('\n').filter(Boolean).pop() || '{}';
			try { res = JSON.parse(line); } catch { res = { ok: false, error: `unparseable output: ${line.slice(0, 120)}` }; }
		}
		PROBES.set(p.pkg.name, res);
	}
}

// ------------------------------------------------------------------ capability smoke (sharp)
let CAPABILITY = null;
if (HOST_ANDROID && args.runtime) {
	const consumer = PKGS.find((p) => (p.pkg.dependencies || {}).sharp) || BY_NAME.get('sharp');
	if (consumer) {
		const code = `
			const fs = require('node:fs');
			(async () => {
				const sharp = require('sharp');
				const jpeg = await sharp({ create: { width: 32, height: 32, channels: 3, background: { r: 1, g: 2, b: 3 } } }).jpeg().toBuffer();
				const meta = await sharp(jpeg).metadata();
				const webp = await sharp(jpeg).resize({ width: 16 }).webp().toBuffer();
				let backend = 'unknown';
				try {
					const maps = fs.readFileSync('/proc/self/maps', 'utf8');
					if (/sharp-android[^\\s]*\\.node/.test(maps)) backend = 'native';
					else if (/sharp-wasm32/.test(maps)) backend = 'wasm';
				} catch {}
				console.log(JSON.stringify({ ok: true, backend, vips: (sharp.versions || {}).vips, jpegBytes: jpeg.length, webpBytes: webp.length, meta: meta.format + ' ' + meta.width + 'x' + meta.height }));
			})().catch((e) => console.log(JSON.stringify({ ok: false, error: String((e && e.message) || e).split('\\n')[0] })));`;
		const r = spawnSync(process.execPath, ['-e', code], {
			cwd: consumer.dir,
			encoding: 'utf8',
			timeout: 120000,
			env: { ...process.env, LD_LIBRARY_PATH: LD },
		});
		const line = (r.stdout || '').trim().split('\n').filter(Boolean).pop() || '{}';
		try { CAPABILITY = JSON.parse(line); } catch { CAPABILITY = { ok: false, error: `unparseable output: ${line.slice(0, 160)}` }; }
	}
}

// ------------------------------------------------------------------ live engine (device only)
const ENGINE = [];
if (HOST_ANDROID && args.runtime) {
	try {
		for (const name of fs.readdirSync('/proc')) {
			if (!/^\d+$/.test(name)) continue;
			let cmd = '';
			try { cmd = fs.readFileSync(`/proc/${name}/cmdline`, 'utf8').replace(/\0/g, ' '); } catch { continue; }
			if (!cmd.includes('dsh/lib/bin.js')) continue;
			let maps = '';
			try { maps = fs.readFileSync(`/proc/${name}/maps`, 'utf8'); } catch { ENGINE.push({ pid: Number(name), error: 'maps unreadable' }); continue; }
			const native = (maps.match(/sharp-android[^\s]*\.node/) || [])[0] || null;
			ENGINE.push({ pid: Number(name), native, wasm: /sharp-wasm32/.test(maps) });
		}
	} catch { /* /proc unreadable */ }
}

// ------------------------------------------------------------------ checks (display order)
const checks = [];
const addCheck = (obj) => { checks.push(obj); return obj; };
const finalize = (c) => {
	if (c.findings.some((f) => f.level === 'fail')) c.status = 'fail';
	else if (c.findings.length) c.status = 'warn';
	else if (c.details.some((d) => String(d).startsWith('SKIP:'))) c.status = 'info';
	else c.status = 'pass';
	return c;
};

// 1. missing deps
(() => {
	const c = addCheck({ id: 'missing-deps', title: 'missing dependencies (declared dependency resolution)', status: 'pass', details: [], findings: [] });
	let specs = 0;
	for (const { dir, pkg } of PKGS) {
		const deps = Object.keys(pkg.dependencies || {});
		if (!deps.length) continue;
		const req = createRequire(path.join(dir, 'package.json'));
		for (const dep of deps) {
			specs++;
			try { req.resolve(dep); continue; } catch (e) {
				const code = e && e.code;
				if (code === 'ERR_PACKAGE_PATH_NOT_EXPORTED' || code === 'ERR_INVALID_PACKAGE_TARGET' || code === 'ERR_INVALID_PACKAGE_CONFIG') {
					let installed = BY_NAME.has(dep);
					if (!installed) { try { req.resolve(`${dep}/package.json`); installed = true; } catch { /* stays false */ } }
					if (installed) continue;
				}
				if (code !== 'MODULE_NOT_FOUND') { c.details.push(`resolve ${code} for "${dep}" (required by ${pkg.name})`); continue; }
				if (dep.startsWith('@types/') || pkg.name.startsWith('@types/')) {
					c.details.push(`types-only dep absent: ${dep} (required by ${pkg.name}, no runtime effect)`);
					continue;
				}
				const enginePkg = pkg.name.startsWith('@deepseek-ai/');
				const msg = `${pkg.name} → missing "${dep}"`;
				if (enginePkg) { c.findings.push({ level: 'fail', msg }); continue; }
				if (!referencedByJs(dir, dep)) {
					c.details.push(`declared-but-unreferenced dep absent: ${dep} (required by ${pkg.name})`);
					continue;
				}
				c.findings.push({ level: 'warn', msg: `${msg} (non-engine package, runtime-referenced; may be dev/unused)` });
			}
		}
	}
	c.details.unshift(`${PKGS.length} packages, ${specs} dependency specs checked`);
	finalize(c);
})();

// 2. native addons
(() => {
	const c = addCheck({ id: 'native-addons', title: `native addons loadable on ${TARGET}`, status: 'pass', details: [], findings: [] });
	if (!NATIVE_FILES.length) c.details.push('no *.node binaries in tree');
	for (const n of NATIVE_REPORT) {
		if (n.kind === 'not-elf') c.findings.push({ level: 'warn', msg: `${n.rel}: not an ELF file (host-only or corrupt)` });
		else if (n.kind === 'unknown') c.findings.push({ level: 'warn', msg: `${n.rel}: cannot inspect (${n.notes.join(', ') || 'unknown'})` });
		else if (TARGET_OS === 'android' && n.arch !== TARGET_ARCH) c.findings.push({ level: 'fail', msg: `${n.rel}: built for ${n.arch}, target is ${TARGET_ARCH}` });
		else if (TARGET_OS === 'android' && n.glibc.length) c.findings.push({ level: 'fail', msg: `${n.rel}: glibc-linked (${n.glibc.join(', ')}) — cannot load on Android/bionic` });
		else if (n.glibc.length) c.findings.push({ level: 'warn', msg: `${n.rel}: glibc-linked — fine on glibc, not on Android` });
		else c.details.push(`${n.rel}: ELF ${n.arch}, bionic-compatible`);
		for (const note of n.notes) c.findings.push({ level: 'warn', msg: `${n.rel}: ${note}` });
	}
	if (NATIVE_FILES.length) c.details.push(`${NATIVE_FILES.length} addon file(s)`);
	finalize(c);
})();

// 3. platform candidates
(() => {
	const c = addCheck({ id: 'platform', title: `platform candidates for ${TARGET}`, status: 'pass', details: [], findings: [] });
	if (!NATIVEISH.length) c.details.push('no native-ish packages found');
	const installed = new Set(PKGS.map((x) => x.pkg.name));
	for (const p of NATIVEISH) {
		const probed = PROBES.get(p.pkg.name);
		if (probed && probed.ok) { c.details.push(`${p.pkg.name}: loads at runtime (probe)`); continue; }
		if (!p.platDeps.length) continue;
		const present = p.platDeps.filter((k) => installed.has(k));
		const forTarget = present.filter((k) => k.includes(`${TARGET_OS}-${TARGET_ARCH}`) || /android/i.test(k));
		const wasm = present.filter((k) => /wasm32/i.test(k));
		if (forTarget.length) c.details.push(`${p.pkg.name}: ${forTarget.join(', ')}`);
		else if (wasm.length) c.details.push(`${p.pkg.name}: ${wasm.join(', ')} (wasm)`);
		else if (p.wasmSibling) c.details.push(`${p.pkg.name}: wasm sibling package present`);
		else {
			const msg = `${p.pkg.name}: no ${TARGET} candidate and no wasm fallback (installed optional deps: ${present.join(', ') || 'none'})`;
			c.findings.push({ level: 'warn', msg });
		}
	}
	finalize(c);
})();

// 4. probes
(() => {
	const c = addCheck({ id: 'probes', title: 'runtime load probes (native-ish packages)', status: 'pass', details: [], findings: [] });
	if (!HOST_ANDROID) c.details.push(`SKIP: host is ${process.platform}/${process.arch}; run on device for authoritative probes`);
	else if (!args.runtime) c.details.push('SKIP: --no-runtime');
	else if (!NATIVEISH.length) c.details.push('no native-ish packages to probe');
	else {
		for (const p of NATIVEISH) {
			const r = PROBES.get(p.pkg.name);
			if (!r) continue;
			if (r.ok) { c.details.push(`${p.pkg.name}: loads (${r.via || 'require'})`); continue; }
			if (ALLOW.has(p.pkg.name)) { c.findings.push({ level: 'warn', msg: `${p.pkg.name}: cannot load but allow-listed — ${r.error}` }); continue; }
			c.findings.push({ level: 'warn', msg: `${p.pkg.name}: cannot load on ${TARGET} — ${r.error} (feature breaks if used; fix, or --allow / --strict to gate)` });
		}
	}
	finalize(c);
})();

// 5. capability
(() => {
	const c = addCheck({ id: 'capability', title: 'read_image pipeline (sharp create/resize/encode)', status: 'pass', details: [], findings: [] });
	if (!HOST_ANDROID) c.details.push(`SKIP: host is ${process.platform}/${process.arch}; run on device`);
	else if (!args.runtime) c.details.push('SKIP: --no-runtime');
	else if (!CAPABILITY) c.details.push('SKIP: sharp consumer not found');
	else if (!CAPABILITY.ok) c.findings.push({ level: 'fail', msg: `sharp pipeline failed: ${CAPABILITY.error}` });
	else {
		c.details.push(`backend=${CAPABILITY.backend} vips=${CAPABILITY.vips} jpeg=${CAPABILITY.jpegBytes}B webp=${CAPABILITY.webpBytes}B ${CAPABILITY.meta}`);
		if (CAPABILITY.backend === 'unknown') c.findings.push({ level: 'warn', msg: 'sharp works but backend could not be detected' });
		if (CAPABILITY.backend === 'wasm') c.findings.push({ level: 'warn', msg: 'on WebAssembly fallback — works, ~1.6-1.7x slower than native' });
	}
	finalize(c);
})();

// 6. engine
(() => {
	const c = addCheck({ id: 'engine', title: 'live engine sharp backend', status: 'pass', details: [], findings: [] });
	if (!HOST_ANDROID) c.details.push('SKIP: host is not Android');
	else if (!args.runtime) c.details.push('SKIP: --no-runtime');
	else if (!ENGINE.length) c.details.push('SKIP: no running engine process found');
	else {
		const hasAndroidAddon = NATIVE_REPORT.some((n) => n.rel.includes('sharp-android'));
		for (const e of ENGINE) {
			if (e.error) { c.details.push(`pid ${e.pid}: ${e.error}`); continue; }
			if (e.native) c.details.push(`pid ${e.pid}: native backend (${path.basename(e.native)})`);
			else if (e.wasm) {
				if (hasAndroidAddon) c.findings.push({ level: 'warn', msg: `pid ${e.pid}: wasm backend while native addon exists — restart engine to pick it up` });
				else c.details.push(`pid ${e.pid}: wasm backend (only backend available)`);
			} else c.details.push(`pid ${e.pid}: sharp not loaded yet`);
		}
	}
	finalize(c);
})();

// 7. sharp static (host independent)
(() => {
	const c = addCheck({ id: 'sharp-static', title: 'sharp backend available for android-arm64', status: 'pass', details: [], findings: [] });
	const sharp = BY_NAME.get('sharp');
	if (!sharp) c.details.push('SKIP: sharp not installed');
	else {
		let androidAddon = null;
		try {
			androidAddon = fs.readdirSync(path.join(sharp.dir, 'src/build/Release')).find((f) => f.endsWith('.node') && f.includes('android'));
		} catch { /* no build dir */ }
		const wasmPkg = PKGS.find((p) => /(^|\/)sharp-wasm32$/.test(p.pkg.name));
		const emnapi = PKGS.find((p) => /@emnapi\/runtime/.test(p.pkg.name));
		if (androidAddon) c.details.push(`android addon present: ${androidAddon}`);
		else if (wasmPkg) {
			c.details.push(`wasm fallback present: ${wasmPkg.pkg.name}@${wasmPkg.pkg.version}${emnapi ? ` + @emnapi/runtime@${emnapi.pkg.version}` : ''}`);
			if (!emnapi) c.findings.push({ level: 'warn', msg: 'wasm fallback present but @emnapi/runtime missing — wasm addon cannot load' });
		} else {
			c.findings.push({
				level: 'fail',
				msg: `sharp@${sharp.pkg.version}: no android-arm64 addon and no @img/sharp-wasm32 fallback — read_image will fail with "Could not load the sharp module using the android-arm64 runtime"`,
			});
		}
	}
	finalize(c);
})();

// ------------------------------------------------------------------ report
const summary = { pass: 0, warn: 0, fail: 0, info: 0 };
for (const c of checks) summary[c.status] = (summary[c.status] || 0) + 1;
const exitCode = summary.fail > 0 || (args.strict && summary.warn > 0) ? 1 : 0;
const meta = {
	version: VERSION,
	host: { platform: process.platform, arch: process.arch, node: process.version },
	target: TARGET,
	root: ROOT,
	prefix: PREFIX || null,
	allow: [...ALLOW],
};

if (args.json) {
	process.stdout.write(JSON.stringify({ ...meta, ok: exitCode === 0, summary, checks }, null, 2) + '\n');
} else {
	const ICON = { pass: 'PASS', warn: 'WARN', fail: 'FAIL', info: 'INFO' };
	if (!args.quiet) {
		process.stdout.write(`full-check-deps ${VERSION}\n`);
		process.stdout.write(`target: ${TARGET} | host: ${process.platform}/${process.arch} node ${process.version}\n`);
		process.stdout.write(`root:   ${ROOT}\n`);
		process.stdout.write(`prefix: ${PREFIX || '(none)'}\n\n`);
	}
	for (const c of checks) {
		if (args.quiet && c.status === 'pass') continue;
		process.stdout.write(`[${ICON[c.status] || c.status}] ${c.title}\n`);
		for (const d of c.details) process.stdout.write(`        ${d}\n`);
		for (const f of c.findings) process.stdout.write(`        → ${f.level.toUpperCase()} ${f.msg}\n`);
	}
	process.stdout.write(`\nresult: ${exitCode ? 'FAIL' : 'PASS'}  (${summary.fail} fail, ${summary.warn} warn, ${summary.pass} pass)  exit ${exitCode}\n`);
	if (summary.fail) process.stdout.write('hint: these break the release; fix them or --allow known exclusions\n');
}
process.exit(exitCode);
