#!/usr/bin/env python3
"""Verify that literal `/tmp` writes work inside the app (LD_PRELOAD path map).

Runs as the app UID in Normal mode via the `com.termux.adb` ContentProvider — no su,
no Shizuku, no engine restart. Checks bash/coreutils, node and python all agree on
the same `/tmp` file, and that it physically lands under `$TMPDIR`.
"""
import argparse
import re
import shlex
import subprocess

PROBE = 'dsh-pathmap-probe'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.device]

    def exec_app(command: str, timeout: int = 60) -> str:
        result = subprocess.run(
            adb + ['shell', 'content', 'call', '--uri', 'content://com.termux.adb',
                   '--method', 'exec', '--arg', shlex.quote(command)],
            text=True, capture_output=True, timeout=timeout)
        if result.returncode or 'ok=true' not in result.stdout or 'exit=0' not in result.stdout:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    uid = re.search(r'out=(\d+)\s', exec_app('id -u'))
    if not uid or int(uid[1]) <= 10000:
        raise RuntimeError('refusing to run: expected non-root app UID')
    mode = exec_app('printf "mode=%s\\n" "$DSH_ANDROID_PRIV_MODE"')
    if 'out=mode=normal' not in mode:
        raise RuntimeError('refusing to run: Normal mode required')
    if not exec_app('printf "tmpdir=%s\\n" "$DSH_TMPDIR"').strip():
        raise RuntimeError('DSH_TMPDIR is not exported')

    script = f'''set -e
printf 'pm-ok' > /tmp/{PROBE}.txt
[ "$(cat /tmp/{PROBE}.txt)" = pm-ok ]
[ -f "$TMPDIR/{PROBE}.txt" ]
node -e 'const fs=require("fs"); fs.writeFileSync("/tmp/{PROBE}-node.txt","node-ok"); process.stdout.write(fs.readFileSync("/tmp/{PROBE}-node.txt","utf8"))'
python3 -c 'open("/tmp/{PROBE}-py.txt","w").write("py-ok"); print(open("/tmp/{PROBE}-py.txt").read())'
mkdir -p /tmp/{PROBE}-dir/sub
printf 'nested' > /tmp/{PROBE}-dir/sub/f
[ "$(cat /tmp/{PROBE}-dir/sub/f)" = nested ]
rm -rf /tmp/{PROBE}.txt /tmp/{PROBE}-node.txt /tmp/{PROBE}-py.txt /tmp/{PROBE}-dir
printf 'PASS uid=%s tmpdir=%s\\n' "$(id -u)" "$TMPDIR"
'''
    out = exec_app(script, timeout=120)
    if 'PASS uid=' not in out:
        raise RuntimeError('pathmap verification produced no PASS line: ' + out)
    for line in out.splitlines():
        if line.startswith('PASS uid='):
            print(line)
    print('PASS pathmap: /tmp is writable through $TMPDIR')


if __name__ == '__main__':
    main()
