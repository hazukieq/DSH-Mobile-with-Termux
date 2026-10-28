#!/usr/bin/env bash
# 引擎依赖审计门（每次编译引擎层都必须过这一关）。
#
# 本体是 scripts/full-check-deps.mjs（0.2.0，7 项检查：缺失依赖 / 原生模块架构与
# bionic-glibc / 平台候选与 wasm 回退 / 真机 require / sharp 全链路 / 运行中引擎后端 /
# sharp 静态兜底）。本脚本负责：
#   1. 统一 `LC_ALL=C`（否则中文 locale 下 readelf 输出被本地化，架构判定变成 "built for ?"）
#   2. 读 scripts/check-engine-deps.allow 里的「已知可接受」条目（package + missing dep + 理由）
#   3. 有非豁免 FAIL → 退出码 1；只有 WARN → 按 --strict 决定
#
# 用法：
#   scripts/check-engine-deps.sh --root <node_modules>            # 静态（PC 可用）
#   scripts/check-engine-deps.sh --root <node_modules> --runtime  # 设备上做真加载
#   scripts/check-engine-deps.sh --root ... --strict              # 发布门禁：WARN 也失败
#   scripts/check-engine-deps.sh --root ... --json                # 机读
set -uo pipefail

ROOT="" RUNTIME=0 STRICT=0 JSON=0
while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="$2"; shift 2 ;;
    --runtime) RUNTIME=1; shift ;;
    --strict) STRICT=1; shift ;;
    --json) JSON=1; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$ROOT" ] || { echo "usage: $0 --root <node_modules> [--runtime] [--strict] [--json]" >&2; exit 2; }
[ -d "$ROOT" ] || { echo "no such dir: $ROOT" >&2; exit 2; }

HERE="$(cd "$(dirname "$0")" && pwd)"
ALLOW_FILE="$HERE/check-engine-deps.allow"
NODE_BIN="${NODE_BIN:-node}"

# --allow 只对「运行期探针失败」生效（full-check-deps 的语义），这里把 allow 文件里
# 第 1 列是包名的行汇总成 --allow 列表（用于设备上已知不可加载的包）。
allow_pkgs=()
if [ -f "$ALLOW_FILE" ]; then
  while IFS=$'\t' read -r pkg dep reason; do
    case "${pkg:-}" in ''|\#*) continue ;; esac
    case "$dep" in
      '') allow_pkgs+=("$pkg") ;;   # 只有包名 → 运行期豁免
    esac
  done < "$ALLOW_FILE"
fi
allow_arg=""
[ ${#allow_pkgs[@]} -gt 0 ] && allow_arg="--allow $(IFS=,; echo "${allow_pkgs[*]}")"

args=(--root "$ROOT" --json)
[ "$RUNTIME" = 1 ] || args+=(--no-runtime)
[ -n "$allow_arg" ] && args+=($allow_arg)

export LC_ALL=C
report="$("$NODE_BIN" "$HERE/full-check-deps.mjs" "${args[@]}" 2>&1)"
rc=$?
if [ "$JSON" = 1 ]; then
  printf '%s\n' "$report"
  [ "$rc" -eq 0 ] && exit 0
fi

# 解析 JSON，剔掉 allow 文件里登记的 (package, missing dep) 组合
report_file="$(mktemp)"
printf '%s' "$report" > "$report_file"
"$NODE_BIN" - "$report_file" "$ALLOW_FILE" "$STRICT" "$RUNTIME" "$JSON" <<'NODE'
const fs = require('node:fs');
const reportFile = process.argv[2];
const allowFile = process.argv[3];
const strict = process.argv[4] === '1';
const runtime = process.argv[5] === '1';
const asJson = process.argv[6] === '1';
const raw = fs.readFileSync(reportFile, 'utf8');
let j;
try { j = JSON.parse(raw); } catch {
  process.stdout.write(raw.trimEnd() + '\n');
  process.exit(1);
}
// allow 文件：<pkg>\t<missing-dep>\t<reason>  （pkg 独行 = 运行期豁免）
const exempt = new Set();
if (fs.existsSync(allowFile)) {
  for (const line of fs.readFileSync(allowFile, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const [pkg, dep] = line.split('\t');
    if (pkg && dep) exempt.add(`${pkg} → missing "${dep}"`);
  }
}
const kept = [];
for (const c of j.checks) {
  for (const f of c.findings) {
    if (f.level !== 'fail') continue;
    if (c.id === 'missing-deps' &&
        exempt.has(f.msg.replace(/^FAIL\s+/, ''))) {
      f.level = 'warn';
      f.msg += ' (allow-listed)';
      continue;
    }
    kept.push(`${c.id}: ${f.msg}`);
  }
}
const fails = kept.length;
const warns = j.checks.reduce((n, c) => n + c.findings.filter((f) => f.level === 'warn').length, 0);
const ok = fails === 0 && !(strict && warns > 0);
if (asJson) {
  j.ok = ok; j.summary.fail = fails;
  process.stdout.write(JSON.stringify(j, null, 2) + '\n');
  process.exit(ok ? 0 : 1);
}
const ICON = { pass: 'PASS', warn: 'WARN', fail: 'FAIL', info: 'INFO' };
process.stdout.write(`engine dependency gate — root: ${j.root}\n`);
process.stdout.write(`target: ${j.target} | host: ${j.host.platform}/${j.host.arch} node ${j.host.node}\n`);
process.stdout.write(`runtime probes: ${runtime ? 'on' : 'off (static only)'}\n\n`);
for (const c of j.checks) {
  const bad = c.findings.some((f) => f.level === 'fail');
  const warn = c.findings.some((f) => f.level === 'warn');
  if (!bad && !warn) continue;
  process.stdout.write(`[${ICON[bad ? 'fail' : 'warn']}] ${c.title}\n`);
  for (const f of c.findings) {
    if (f.level === 'fail' || f.level === 'warn') {
      process.stdout.write(`        → ${f.level.toUpperCase()} ${f.msg}\n`);
    }
  }
}
process.stdout.write(`\nresult: ${ok ? 'PASS' : 'FAIL'}  (${fails} fail, ${warns} warn)\n`);
process.exit(ok ? 0 : 1);
NODE
gate_rc=$?
rm -f "$report_file"
exit $gate_rc
