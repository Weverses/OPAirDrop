#!/usr/bin/env python3
"""Check renamed source files and shell syntax without touching a device."""
from pathlib import Path
import ast
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SKIP = {'.git', 'node_modules', 'dist', 'build', '.gradle', '__pycache__', 'local-inputs'}
errors = []
count = 0
for path in ROOT.rglob('*'):
    relative = path.relative_to(ROOT)
    if not path.is_file() or any(part in SKIP for part in relative.parts):
        continue
    if relative.as_posix() == 'docs/migration.json':
        continue
    count += 1
    # The legacy pattern is split so this checker also checks itself.
    pattern = r'(?:' + 'op' + '13|' + 'oneplus' + '13)'
    try:
        text = path.read_text()
    except UnicodeDecodeError:
        continue
    if re.search(pattern, str(relative) + '\n' + text, re.I):
        errors.append(f'Legacy name: {relative}')
    if path.suffix == '.py':
        ast.parse(text, filename=str(path))
    if path.suffix == '.sh':
        shell = 'bash' if 'bash' in text.splitlines()[0] else 'sh'
        result = subprocess.run([shell, '-n', str(path)], capture_output=True, text=True)
        if result.returncode:
            errors.append(f'{relative}: {result.stderr}')
for relative, row in json.loads((ROOT / 'kernel/source-map.json').read_text()).items():
    import hashlib
    if hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != row['sha256']:
        errors.append(f'Kernel source hash: {relative}')
    if row.get('kernel_path') == '':
        errors.append(f'Unresolved kernel path: {relative}')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'Source namespace, Python syntax, shell syntax and kernel source hashes passed ({count} files).')
