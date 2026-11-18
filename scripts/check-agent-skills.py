#!/usr/bin/env python3
"""Check bundled discipline assets and their deployable references before APK packaging."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'app/src/main/assets'
registry = (ROOT / 'app/src/main/java/app/dsh/mobile/engine/BundledAgentSkills.kt').read_text()
match = re.search(r'val names = listOf\(([^)]+)\)', registry)
if not match:
    sys.exit('FAIL: bundled skill registry missing')
names = re.findall(r'"([a-z0-9-]+)"', match[1])
errors = []
for name in names:
    skill = ASSETS / f'skills/{name}/SKILL.md'
    if not skill.is_file():
        errors.append(f'missing skill: {name}')
        continue
    text = skill.read_text()
    header = re.match(r'\A---\n(.*?)\n---\n', text, re.S)
    if not header:
        errors.append(f'invalid frontmatter: {name}')
        continue
    if not re.search(rf'^name: {re.escape(name)}$', header[1], re.M):
        errors.append(f'name differs from directory: {name}')
    if not re.search(r'^description: \S.+$', header[1], re.M):
        errors.append(f'missing description: {name}')
    for target in re.findall(r'\]\((references/[^)]+)\)', text):
        path = skill.parent / target
        if not path.is_file() or not path.resolve().is_relative_to(skill.parent.resolve()):
            errors.append(f'invalid reference: {name}/{target}')
asset_names = {p.parent.name for p in (ASSETS / 'skills').glob('*/SKILL.md')}
if set(names) != asset_names or len(names) != len(set(names)):
    errors.append('registry and bundled skill directories differ')
reminder = ASSETS / 'skills/task-execution-discipline/references/step-reminder.md'
if not reminder.is_file() or not 0 < reminder.stat().st_size <= 4096:
    errors.append('periodic reminder must be nonempty and at most 4096 bytes')
if not (ASSETS / 'android-plugins/discipline.mjs').is_file():
    errors.append('missing discipline plugin')
if errors:
    sys.exit('\n'.join('FAIL: ' + error for error in errors))
print(f'PASS: {len(names)} bundled discipline skills, references and periodic reminder ({reminder.stat().st_size} bytes)')
