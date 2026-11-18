"""Offline regression tests; never read or modify a phone's engine."""
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
GATE = REPO / 'app/src/main/assets/gates/dsh-update'
ABI = {'x86_64': 'x86_64', 'aarch64': 'aarch64', 'arm64': 'aarch64'}.get(platform.machine(), 'arm')
DEFAULT = 'https://github.com/hazukieq/DSH-Mobile-with-Termux/releases/download/dsh-engine-latest/engine-manifest.json'
API = 'https://api.github.com/repos/hazukieq/DSH-Mobile-with-Termux/releases?per_page=30'


def package(root, name, version='1.0.0', **extra):
    directory = root / name
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'package.json').write_text(json.dumps(dict(name=name, version=version, **extra)))
    return directory


class UpgradeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsh upgrade ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.prefix = self.root / 'prefix'
        (self.prefix / 'bin').mkdir(parents=True)
        (self.prefix / 'bin/node').symlink_to(shutil.which('node'))
        package(self.prefix / 'lib/node_modules', '@deepseek-ai/dsh', '0.1.0')
        (self.prefix / '.dsh-engine-version').write_text('0.1.0+old\n')
        self.map = self.root / 'responses.json'
        self.map.write_text('{}')
        self.requests = self.root / 'requests'
        mock = self.root / 'mockbin'
        mock.mkdir()
        curl = mock / 'curl'
        curl.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
url = args[-1]
with open(os.environ['REQUESTS'], 'a') as f: f.write(url + '\\n')
p = json.load(open(os.environ['RESPONSES'])).get(url)
if not p: sys.exit(22)
b = Path(p).read_bytes()
if '-o' in args: Path(args[args.index('-o') + 1]).write_bytes(b)
else: sys.stdout.buffer.write(b)
''')
        curl.chmod(0o755)
        self.env = dict(os.environ, PREFIX=str(self.prefix), _DSH_REEXEC='1',
                        PATH=str(mock) + os.pathsep + os.environ['PATH'],
                        REQUESTS=str(self.requests), RESPONSES=str(self.map))
        self.env.pop('DSH_ENGINE_MANIFEST', None)
        self.artifact = self.root / 'engine.tar.gz'
        with tarfile.open(self.artifact, 'w:gz') as tar:
            for name, data in {'.dsh-engine-version': b'0.2.0+test\n',
                               'lib/node_modules/@deepseek-ai/dsh/lib/bin.js': b'// entry\n'}.items():
                info = tarfile.TarInfo(name); info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        self.url = 'https://test.invalid/engine.tar.gz'
        self.feed = dict(engine_version='0.2.0+test', dsh_version='0.2.0', assets={ABI: {
            'sha256': hashlib.sha256(self.artifact.read_bytes()).hexdigest(), 'url': self.url}})
        self.manifest = self.root / 'feed.json'
        self.manifest.write_text(json.dumps(self.feed, indent=2))
        self.responses = {self.url: str(self.artifact)}

    def run_gate(self, *args, ok=True):
        self.map.write_text(json.dumps(self.responses))
        result = subprocess.run(['bash', str(GATE), *args], env=self.env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
        if ok:
            self.assertEqual(result.returncode, 0, result.stdout)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result.stdout

    def test_pull_env_feed_stages_formatted_json_and_does_not_apply(self):
        self.env['DSH_ENGINE_MANIFEST'] = str(self.manifest)
        self.assertIn('stage: ok', self.run_gate('pull', '0.2.0'))
        self.assertEqual((self.prefix / '.engine-staged/.dsh-engine-version').read_text().strip(), '0.2.0+test')
        self.assertEqual((self.prefix / '.dsh-engine-version').read_text().strip(), '0.1.0+old')
        self.assertEqual(self.requests.read_text().splitlines(), [self.url])

    def test_explicit_manifest_overrides_environment(self):
        self.env['DSH_ENGINE_MANIFEST'] = str(self.root / 'absent')
        self.assertIn('update: available', self.run_gate('check', '--manifest', str(self.manifest)))

    def test_apk_provenance_is_rejected_without_network_fallback(self):
        self.manifest.write_text(json.dumps({'engine_version': '0.2.0', 'apk': {}, 'payloads': {}}))
        output = self.run_gate('check', '--manifest', str(self.manifest), ok=False)
        self.assertIn('APK provenance', output)
        self.assertFalse(self.requests.exists())

    def test_invalid_feeds_are_rejected_before_download(self):
        for change in ('checksum', 'abi', 'schema', 'version'):
            with self.subTest(change=change):
                feed = json.loads(json.dumps(self.feed))
                if change == 'checksum': del feed['assets'][ABI]['sha256']
                if change == 'abi': feed['assets'] = {}
                if change == 'schema': feed['schema_version'] = 2
                if change == 'version': feed['dsh_version'] = 5
                self.manifest.write_text(json.dumps(feed))
                self.run_gate('pull', '--manifest', str(self.manifest), ok=False)
        self.assertFalse(self.requests.exists())

    def test_requested_version_must_match_feed(self):
        self.assertIn('does not match', self.run_gate('pull', '9.9.9', '--manifest', str(self.manifest), ok=False))
        self.assertFalse(self.requests.exists())

    def test_release_discovery_skips_apk_manifest(self):
        app = self.root / 'apk-manifest.json'; app.write_text('{"apk": {}}')
        releases = self.root / 'releases.json'
        releases.write_text(json.dumps([
            {'assets': [{'name': 'engine-manifest.json', 'browser_download_url': 'https://test.invalid/app'}]},
            {'assets': [{'name': 'engine-feed.json', 'browser_download_url': 'https://test.invalid/feed'}]},
        ]))
        self.responses.update({API: str(releases), 'https://test.invalid/app': str(app), 'https://test.invalid/feed': str(self.manifest)})
        self.assertIn('stage: ok', self.run_gate('pull'))
        self.assertEqual(self.requests.read_text().splitlines(), [DEFAULT, API, 'https://test.invalid/app', 'https://test.invalid/feed', self.url])

    def test_bad_checksum_preserves_previous_staged_engine(self):
        self.run_gate('stage', str(self.artifact))
        self.feed['assets'][ABI]['sha256'] = '0' * 64
        self.manifest.write_text(json.dumps(self.feed))
        self.assertIn('sha256 mismatch', self.run_gate('pull', '--manifest', str(self.manifest), ok=False))
        self.assertTrue((self.prefix / '.engine-staged/lib/node_modules/@deepseek-ai/dsh/lib/bin.js').is_file())

    def test_invalid_new_tree_cannot_borrow_previous_staged_entry(self):
        self.run_gate('stage', str(self.artifact))
        bad = self.root / 'bad.tar.gz'
        with tarfile.open(bad, 'w:gz') as tar:
            info = tarfile.TarInfo('.dsh-engine-version'); info.size = 4
            tar.addfile(info, io.BytesIO(b'bad\n'))
        self.run_gate('stage', str(bad), ok=False)
        self.assertEqual((self.prefix / '.engine-staged/.dsh-engine-version').read_text().strip(), '0.2.0+test')

    def test_howto_is_not_empty(self):
        self.assertIn('ENGINE-BUILD.md', self.run_gate('howto'))


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dsh build ' ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_script(self, script, *args, ok=True, env=None):
        result = subprocess.run([str(script), *map(str, args)] if script.suffix == '.py' else ['bash', str(script), *map(str, args)],
                                env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        if ok: self.assertEqual(result.returncode, 0, result.stdout)
        else: self.assertNotEqual(result.returncode, 0, result.stdout)
        return result.stdout

    def test_closure_build_honors_tmpdir_and_emits_feed(self):
        skeleton = self.root / 'skeleton'; skeleton.mkdir()
        closure = self.root / 'node_modules'
        package(closure, '@deepseek-ai/dsh', '0.2.0')
        package(closure, '@deepseek-ai/cosmokit')
        cordis = package(closure, '@deepseek-ai/cordis')
        (cordis / 'lib').mkdir(); (cordis / 'lib/index.js').write_text('module.exports = {}')
        temp = self.root / 'temporary files'; temp.mkdir()
        output = self.root / 'output/engine test.tar.gz'
        self.run_script(REPO / 'scripts/build-engine-closure.sh', '--engine', skeleton,
                        '--closure', closure, '--version', '0.2.0+test', '--out', output,
                        '--skip-patch', '--download-base', 'https://test.invalid/release',
                        env=dict(os.environ, TMPDIR=str(temp)))
        self.assertEqual(list(temp.iterdir()), [])
        feed = json.loads(Path(str(output) + '.manifest.json').read_text())
        asset = feed['assets']['aarch64']
        self.assertEqual(asset['sha256'], hashlib.sha256(output.read_bytes()).hexdigest())
        self.assertEqual(asset['url'], 'https://test.invalid/release/engine%20test.tar.gz')

    def test_dependency_gate_runs_without_execute_permission_and_failure_stops_build(self):
        scripts = self.root / 'scripts'; scripts.mkdir()
        shutil.copy(REPO / 'scripts/build-engine-closure.sh', scripts)
        gate = scripts / 'check-engine-deps.sh'
        gate.write_text('#!/not/an/android/interpreter\necho dependency-gate-ran\nexit 42\n')
        gate.chmod(0o644)
        skeleton = self.root / 'skeleton'; skeleton.mkdir()
        closure = self.root / 'node_modules'
        package(closure, '@deepseek-ai/dsh')
        package(closure, '@deepseek-ai/cosmokit')
        cordis = package(closure, '@deepseek-ai/cordis')
        (cordis / 'lib').mkdir(); (cordis / 'lib/index.js').write_text('')
        output = self.root / 'engine.tar.gz'
        result = self.run_script(scripts / 'build-engine-closure.sh', '--engine', skeleton,
                                '--closure', closure, '--version', 'test', '--out', output, '--skip-patch', ok=False)
        self.assertIn('dependency-gate-ran', result)
        self.assertFalse(output.exists())
        gate.unlink()
        result = self.run_script(scripts / 'build-engine-closure.sh', '--engine', skeleton,
                                '--closure', closure, '--version', 'test', '--out', output, '--skip-patch', ok=False)
        self.assertIn('missing dependency gate', result)

    def test_runtime_collection_requires_local_pack_consumer_before_download(self):
        env = dict(os.environ)
        env.pop('DSH_SOURCE_DIR', None)
        output = self.run_script(REPO / 'scripts/collect-termux-runtime.sh', self.root / 'runtime.zip', ok=False, env=env)
        self.assertIn('DSH_SOURCE_DIR', output)
        self.assertFalse((self.root / 'runtime.zip').exists())

    def test_patch_injects_native_ripgrep_instead_of_relative_wrapper(self):
        root = self.root / 'engine'
        (root / 'bin').mkdir(parents=True)
        for name in ('bash', 'rg'):
            path = root / 'bin' / name
            path.write_text('#!/bin/sh\nexit 99\n'); path.chmod(0o755)
        native = root / 'lib/dsh-engine/rg-bin'
        native.parent.mkdir(parents=True)
        native.write_text('#!/bin/sh\necho native-rg\n'); native.chmod(0o755)
        libs = root / 'lib/dsh-engine/lib'; libs.mkdir()
        for name in ('libreadline.so.8', 'libncursesw.so.6', 'libncurses.so.6', 'libiconv.so', 'libpcre2-8.so'):
            (libs / name).write_bytes(b'fixture')
        nm = root / 'lib/node_modules'
        for name, body in {
            'dsh-attachment-local': '/* isAndroidUnsyncableLevel constants.COPYFILE_EXCL "EACCES" "EPERM" "EINVAL" "EROFS" */',
            'dsh-sandbox-local': '// synthetic sandbox source',
            'dsh-fs-local': 'await linkFile(tempPath, absolutePath);',
        }.items():
            directory = package(nm, '@deepseek-ai/' + name) / 'lib'
            directory.mkdir(); (directory / 'index.js').write_text(body)
        self.run_script(REPO / 'scripts/patch-engine-closure.sh', env=dict(os.environ, ROOT=str(root), ARCH='aarch64'))
        injected = nm / '@vscode/ripgrep-android-arm64/bin/rg'
        self.assertEqual(injected.read_bytes(), native.read_bytes())
        self.assertEqual(subprocess.check_output([str(injected)], text=True).strip(), 'native-rg')

    @unittest.skipUnless(shutil.which('npm'), 'host npm CLI required')
    def test_local_tarball_consumer_resolves_transitive_dependencies_offline(self):
        pack = self.root / 'pack'; pack.mkdir()
        for name, dependencies, body in [
            ('dsh', {'@deepseek-ai/cordis': '1.0.0'}, "module.exports = require('@deepseek-ai/cordis');"),
            ('cordis', {'@deepseek-ai/cosmokit': '1.0.0'}, "module.exports = require('@deepseek-ai/cosmokit');"),
            ('cosmokit', {}, "module.exports = 'consumer-ok';"),
        ]:
            manifest = dict(name='@deepseek-ai/' + name, version='1.0.0', dependencies=dependencies, main='index.js')
            with tarfile.open(pack / (name + '.tgz'), 'w:gz') as tar:
                for path, content in {'package/package.json': json.dumps(manifest), 'package/index.js': body}.items():
                    data = content.encode(); info = tarfile.TarInfo(path); info.size = len(data)
                    tar.addfile(info, io.BytesIO(data))
        consumer = self.root / 'consumer'
        subprocess.run(['python3', str(REPO / 'scripts/prepare-engine-consumer.py'), '--pack', str(pack), '--out', str(consumer)], check=True, capture_output=True)
        result = subprocess.run(['npm', 'install', '--offline', '--ignore-scripts', '--no-audit', '--no-fund', '--omit=optional', '--package-lock=false'], cwd=consumer,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        value = subprocess.check_output(['node', '-p', "require('./node_modules/@deepseek-ai/dsh')"], cwd=consumer, text=True)
        self.assertEqual(value.strip(), 'consumer-ok')

    def test_consumer_uses_manifest_names_and_host_platform(self):
        pack = self.root / 'pack'; pack.mkdir()
        manifests = [dict(name='@deepseek-ai/dsh', version='1.0.0'),
                     dict(name='@deepseek-ai/native-arm', version='1.0.0', os=['linux'], cpu=['arm64']),
                     dict(name='@deepseek-ai/no-linux', version='1.0.0', os=['!linux'])]
        for index, manifest in enumerate(manifests):
            data = json.dumps(manifest).encode()
            with tarfile.open(pack / f'package-{index}.tgz', 'w:gz') as tar:
                info = tarfile.TarInfo('package/package.json'); info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        out = self.root / 'consumer'
        subprocess.run(['python3', str(REPO / 'scripts/prepare-engine-consumer.py'), '--pack', str(pack), '--out', str(out), '--os', 'linux', '--cpu', 'arm64'], check=True, capture_output=True)
        consumer = json.loads((out / 'package.json').read_text())
        self.assertIn('@deepseek-ai/native-arm', consumer['dependencies'])
        self.assertIn('@deepseek-ai/no-linux', consumer['optionalDependencies'])
        self.assertTrue(all(v.startswith('file:/') for v in consumer['dependencies'].values()))


if __name__ == '__main__':
    unittest.main()
