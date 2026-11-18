#!/usr/bin/env python3
"""Run isolated upgrade fixtures as the app UID, without su or engine changes.

Android CLI has no ContentProvider exec/file-transfer command; adb is used here.
Requires an installed app exposing com.termux.adb, with its Termux runtime ready.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile
import time
import uuid

REPO = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    parser.add_argument('--require-live-engine', action='store_true')
    args = parser.parse_args()
    adb = ['adb', '-s', args.device]

    def exec_app(command, timeout=60):
        result = subprocess.run(adb + ['shell', 'content', 'call', '--uri', 'content://com.termux.adb',
                                      '--method', 'exec', '--arg', shlex.quote(command)],
                                text=True, capture_output=True, timeout=timeout)
        if result.returncode or 'ok=true' not in result.stdout or 'exit=0' not in result.stdout:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    probe = exec_app('id -u')
    match = re.search(r'out=(\d+)\s', probe)
    if not match or int(match[1]) <= 10000:
        raise RuntimeError('refusing to run: expected non-root app UID: ' + probe)
    mode = exec_app('printf "mode=%s\\n" "$DSH_ANDROID_PRIV_MODE"')
    if 'out=mode=normal' not in mode:
        raise RuntimeError('refusing to run: Normal mode required')
    if args.require_live_engine:
        for _ in range(30):
            health = exec_app('printf "engine_http="; curl -s -m 2 -o /dev/null -w "%{http_code}" http://127.0.0.1:3080/ || true')
            if 'engine_http=401' in health:
                break
            time.sleep(1)
        else:
            raise RuntimeError('live engine health check failed: ' + health)
    token = uuid.uuid4().hex
    remote = f'/sdcard/Download/dsh-upgrade-test-{token}.tar.gz'
    work = f'"$TMPDIR/dsh-upgrade-test-{token}"'
    try:
        with tempfile.TemporaryDirectory(prefix='dsh-device-fixtures-') as directory:
            root = Path(directory)
            payload = root / 'engine.tar.gz'
            with tarfile.open(payload, 'w:gz') as tar:
                for name, data in {'.dsh-engine-version': b'0.2.0+test\n',
                                   'lib/node_modules/@deepseek-ai/dsh/lib/bin.js': b'// test\n'}.items():
                    info = tarfile.TarInfo(name); info.size = len(data)
                    tar.addfile(info, io.BytesIO(data))
            sha = hashlib.sha256(payload.read_bytes()).hexdigest()
            feed = {'dsh_version': '0.2.0', 'engine_version': '0.2.0+test', 'assets': {
                arch: {'url': 'https://fixture.invalid/engine.tar.gz', 'sha256': sha}
                for arch in ('aarch64', 'x86_64', 'arm')}}
            (root / 'feed.json').write_text(json.dumps(feed, indent=2))
            for asset in feed['assets'].values(): asset['sha256'] = '0' * 64
            (root / 'bad-sha.json').write_text(json.dumps(feed))
            (root / 'apk.json').write_text('{"apk":{},"payloads":{}}')
            for name in ('dsh', 'cosmokit', 'cordis'):
                package = root / f'consumer/node_modules/@deepseek-ai/{name}'
                (package / 'lib').mkdir(parents=True)
                (package / 'package.json').write_text(json.dumps({'name': '@deepseek-ai/' + name, 'version': '0.2.0'}))
                (package / 'lib/index.js').write_text('module.exports = {};')
            archive = root / 'fixtures.tar.gz'
            with tarfile.open(archive, 'w:gz') as tar:
                for name in ('engine.tar.gz', 'feed.json', 'bad-sha.json', 'apk.json', 'consumer'):
                    tar.add(root / name, arcname=name)
                for path, dest in [
                    ('app/src/main/assets/gates/dsh-update', 'dsh-update'),
                    ('scripts/tests/engine-upgrade-device.sh', 'test.sh'),
                    *[(f'scripts/{name}', f'scripts/{name}') for name in
                      ('build-engine-closure.sh', 'check-engine-deps.sh', 'full-check-deps.mjs', 'check-engine-deps.allow')],
                ]:
                    info = tar.gettarinfo(str(REPO / path), arcname=dest)
                    info.mode = 0o644
                    with (REPO / path).open('rb') as stream: tar.addfile(info, stream)
            subprocess.run(adb + ['push', str(archive), remote], check=True, capture_output=True)
            command = f'mkdir -p {work} && tar -xzf {shlex.quote(remote)} -C {work} && bash {work}/test.sh'
            print(exec_app(command, timeout=120))
    finally:
        try: exec_app(f'rm -rf {work}')
        finally: subprocess.run(adb + ['shell', 'rm', '-f', remote], check=False, capture_output=True)


if __name__ == '__main__':
    main()
