#!/usr/bin/env python3
"""Validate the reminder hook with the phone engine's real message factory, without installing an APK or calling a model."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.device]

    def app(command):
        result = subprocess.run(adb + ['shell', 'content', 'call', '--uri', 'content://com.termux.adb',
                                      '--method', 'exec', '--arg', shlex.quote(command)],
                                text=True, capture_output=True, timeout=45)
        if result.returncode or 'ok=true' not in result.stdout or 'exit=0' not in result.stdout:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    probe = app('id -u; printf "mode=%s\\n" "$DSH_ANDROID_PRIV_MODE"')
    uid = re.search(r'out=(\d+)\s', probe)
    if not uid or int(uid[1]) <= 10000 or 'mode=normal' not in probe:
        raise RuntimeError('Normal mode and non-root app UID required: ' + probe)
    unique = uuid.uuid4().hex
    remote = f'/sdcard/Download/dsh-discipline-test-{unique}.tar.gz'
    work = f'"$TMPDIR/dsh-discipline-test-{unique}"'
    script = r'''
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const live = path.join(process.env.PREFIX, 'lib/node_modules/@deepseek-ai/dsh-llm/lib/index.js');
const bridge = path.join(here, 'lib/node_modules/@deepseek-ai/dsh-llm/lib');
fs.mkdirSync(bridge, { recursive: true });
fs.writeFileSync(path.join(bridge, '../package.json'), '{"type":"module"}');
fs.writeFileSync(path.join(bridge, 'index.js'), `export { createUserMessage } from ${JSON.stringify(pathToFileURL(live).href)};`);
const plugin = await import('./android-plugins/discipline.mjs');
let hook;
await plugin.apply({ on(event, callback) { assert.equal(event, 'agent/pre-step'); hook = callback; } });
assert.equal(typeof hook, 'function', 'plugin did not register against the live engine factory');
const { createUserMessage } = await import(pathToFileURL(live).href);
const user = createUserMessage({ content: [{ type: 'text', text: '只打开页面给我，不要继续操作' }], source: { kind: 'user' } });
const agent = { inject() { assert.fail('must not queue context'); }, steer() { assert.fail('must not steer'); } };
const entered = { kind: 'enter', messages: [user] };
const invoke = (turn, step, decision=entered) => hook({ agent, turn, step, signal: new AbortController().signal }, async () => decision);
let result = await invoke(1, 1);
assert.equal(result.messages[0], user);
assert.equal(result.messages[1].role, 'user');
assert.equal(result.messages[1].source.kind, 'android-discipline');
assert.equal(result.messages[1].source.form, 'instructions');
assert.ok(Object.isFrozen(result.messages[1]));
assert.ok(result.messages[1].id);
assert.equal((await invoke(1, 1, result)).messages.length, 2);
for (let step = 2; step <= 6; step++) assert.equal(await invoke(1, step), entered);
assert.equal((await invoke(1, 7)).messages.length, 2);
assert.equal((await invoke(2, 1)).messages.length, 2);
const steering = createUserMessage({ content: [{ type: 'text', text: '别测了，先读文档' }], source: { kind: 'user' } });
const changed = await invoke(2, 3, { kind: 'enter', messages: [steering] });
assert.equal(changed.messages[0], steering);
assert.equal(changed.messages.length, 2);
const rejected = { kind: 'reject' };
assert.equal(await invoke(3, 1, rejected), rejected);
const empty = { kind: 'enter', messages: [] };
assert.equal(await invoke(3, 1, empty), empty);
console.log(`PASS: real engine createUserMessage; initial/periodic/new-turn/steering reminder; no duplicate or rejected/empty-step work; uid=${process.getuid()}`);
'''
    try:
        with tempfile.TemporaryDirectory(prefix='dsh-discipline-fixtures-') as temporary:
            directory = Path(temporary)
            (directory / 'test.mjs').write_text(script)
            archive = directory / 'test.tar.gz'
            with tarfile.open(archive, 'w:gz') as tar:
                tar.add(directory / 'test.mjs', arcname='test.mjs')
                tar.add(ROOT / 'app/src/main/assets/android-plugins/discipline.mjs', arcname='android-plugins/discipline.mjs')
                tar.add(ROOT / 'app/src/main/assets/skills/task-execution-discipline/references/step-reminder.md', arcname='android-plugins/discipline-reminder.md')
            subprocess.run(adb + ['push', str(archive), remote], check=True, capture_output=True)
            print(app(f'mkdir -p {work} && tar -xzf {shlex.quote(remote)} -C {work} && node {work}/test.mjs'))
    finally:
        try: app(f'rm -rf {work}')
        finally: subprocess.run(adb + ['shell', 'rm', '-f', remote], capture_output=True, check=False)


if __name__ == '__main__':
    main()
