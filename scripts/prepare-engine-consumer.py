#!/usr/bin/env python3
"""Create an npm consumer of upstream pack.ts tarballs for the current build host."""
import argparse
import json
import platform
import sys
import tarfile
from pathlib import Path


def matches(values, target):
    if isinstance(values, str):
        values = [values]
    if '!' + target in values:
        return False
    positive = [v for v in values if not v.startswith('!')]
    return not positive or target in positive or 'any' in positive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    # Termux Node reports linux; allow explicit values for cross-host preparation.
    parser.add_argument('--os', default={'darwin': 'darwin', 'win32': 'win32'}.get(sys.platform, 'linux'))
    parser.add_argument('--cpu', default={'x86_64': 'x64', 'aarch64': 'arm64', 'amd64': 'x64'}.get(platform.machine().lower(), platform.machine()))
    args = parser.parse_args()
    deps, optional = {}, {}
    for path in sorted(args.pack.glob('*.tgz')):
        with tarfile.open(path, 'r:gz') as archive:
            stream = archive.extractfile('package/package.json')
            if stream is None:
                parser.error(f'missing package/package.json: {path}')
            manifest = json.load(stream)
        name = manifest['name']
        if name in deps or name in optional:
            parser.error(f'duplicate package: {name}')
        compatible = matches(manifest.get('os') or [], args.os) and matches(manifest.get('cpu') or [], args.cpu)
        (deps if compatible else optional)[name] = 'file:' + str(path.resolve())
    if '@deepseek-ai/dsh' not in deps:
        parser.error('pack directory has no compatible @deepseek-ai/dsh tarball')
    args.out.mkdir(parents=True, exist_ok=True)
    if (args.out / 'node_modules').exists():
        parser.error('consumer already has node_modules; use a fresh output directory')
    (args.out / 'package.json').write_text(json.dumps({
        'name': 'dsh-release-consumer', 'private': True,
        'dependencies': deps, 'optionalDependencies': optional,
    }, indent=2) + '\n')
    print(f'{len(deps)} dependencies, {len(optional)} platform-optional packages ({args.os}/{args.cpu})')


if __name__ == '__main__':
    main()
