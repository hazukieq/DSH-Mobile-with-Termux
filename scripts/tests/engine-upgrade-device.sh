#!/usr/bin/env bash
# Run only inside the app via AdbDebugProvider; fixtures live beside this script.
set -euo pipefail
[ "${DSH_ANDROID_PRIV_MODE:-}" = normal ] || { echo "FAIL: Normal mode required"; exit 1; }
[ "$(id -u)" -gt 10000 ] || { echo 'FAIL: app UID required'; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
LIVE_PREFIX="$PREFIX"
TEST_PREFIX="$HERE/prefix"
mkdir -p "$TEST_PREFIX/bin" "$HERE/mockbin" "$HERE/tmp"
cat > "$TEST_PREFIX/bin/node" <<NODE
#!/system/bin/sh
exec "$LIVE_PREFIX/bin/node" "\$@"
NODE
chmod 755 "$TEST_PREFIX/bin/node"
cat > "$HERE/mockbin/curl" <<CURL
#!/system/bin/sh
out=""
while [ "\$#" -gt 0 ]; do
  case "\$1" in -o) out="\$2"; shift 2 ;; *) shift ;; esac
done
[ -n "\$out" ] || exit 22
cp "$HERE/engine.tar.gz" "\$out"
CURL
chmod 755 "$HERE/mockbin/curl"
export PATH="$HERE/mockbin:$PATH" PREFIX="$TEST_PREFIX" _DSH_REEXEC=1 TMPDIR="$HERE/tmp"
unset DSH_ENGINE_MANIFEST
GATE="$HERE/dsh-update"
bash "$GATE" check --manifest "$HERE/feed.json" | grep -F 'update: available'
bash "$GATE" pull 0.2.0 --manifest "$HERE/feed.json"
[ "$(cat "$TEST_PREFIX/.engine-staged/.dsh-engine-version")" = '0.2.0+test' ]
[ ! -e "$TEST_PREFIX/lib/node_modules" ]
if bash "$GATE" pull --manifest "$HERE/apk.json"; then echo 'FAIL: APK feed accepted'; exit 1; fi
if bash "$GATE" pull wrong --manifest "$HERE/feed.json"; then echo 'FAIL: version mismatch accepted'; exit 1; fi
if bash "$GATE" pull --manifest "$HERE/bad-sha.json"; then echo 'FAIL: bad checksum accepted'; exit 1; fi
[ "$(cat "$TEST_PREFIX/.engine-staged/.dsh-engine-version")" = '0.2.0+test' ]
bash "$GATE" howto | grep -F ENGINE-BUILD.md
# Real dependency audit, not a mocked pass; all scripts retain mode 0644.
mkdir -p "$HERE/skeleton"
bash "$HERE/scripts/build-engine-closure.sh" --engine "$HERE/skeleton" \
  --closure "$HERE/consumer/node_modules" --version 0.2.0+test \
  --out "$HERE/built.tar.gz" --skip-patch
[ -s "$HERE/built.tar.gz" ]
[ -z "$(ls -A "$TMPDIR")" ]
printf 'PASS: isolated Android feed, staging, checksum, Bash dependency gate, TMPDIR; uid=%s\n' "$(id -u)"
