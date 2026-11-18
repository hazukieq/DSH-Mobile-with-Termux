#!/usr/bin/env python3
"""Generate a downloadable engine feed, distinct from APK provenance metadata."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import quote, urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--arch', choices=('aarch64', 'x86_64', 'arm'), required=True)
    parser.add_argument('--dsh-version', required=True)
    parser.add_argument('--engine-version', required=True)
    parser.add_argument('--download-base', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    base = urlsplit(args.download_base)
    if base.scheme not in ('http', 'https') or not base.netloc or base.query or base.fragment:
        parser.error('--download-base must be an HTTP(S) directory URL without query or fragment')
    if not args.dsh_version.strip() or not args.engine_version.strip():
        parser.error('version strings must not be empty')
    digest = hashlib.sha256()
    with args.artifact.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    feed = {
        'kind': 'dsh-engine-feed', 'schema_version': 1,
        'dsh_version': args.dsh_version, 'engine_version': args.engine_version,
        'assets': {args.arch: {
            'url': args.download_base.rstrip('/') + '/' + quote(args.artifact.name),
            'sha256': digest.hexdigest(), 'size': args.artifact.stat().st_size,
        }},
    }
    args.out.write_text(json.dumps(feed, ensure_ascii=False, indent=2) + '\n')
    print(f'engine feed: {args.out} (upload with {args.artifact.name} before using pull)')


if __name__ == '__main__':
    main()
