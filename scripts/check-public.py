#!/usr/bin/env python3
"""Conservative checked-in file/history audit; prints paths/reasons, never matching data."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
def git(*args):
    return subprocess.check_output(['git', *args], cwd=root)
paths = git('ls-files', '-z').decode().split('\0')
bad = []
private_path = re.compile(rb'/(?:home|Users)/[A-Za-z0-9_.-]+/')
secret = re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)')
for name in filter(None, paths):
    p = root / name
    if name.startswith(('.build/', 'target/', 'evidence/', 'diagnostics/')) or p.suffix in {'.jar', '.zip', '.log'}:
        bad.append((name, 'generated/private path'))
    if p.is_file():
        data = p.read_bytes()
        if private_path.search(data) or secret.search(data): bad.append((name, 'private path or credential pattern'))
for commit in git('rev-list', '--all').decode().splitlines():
    identity = git('show', '-s', '--format=%an%n%ae%n%cn%n%ce', commit).decode().splitlines()
    if any(not x.endswith('@users.noreply.github.com') for x in identity[1::2]): bad.append((commit, 'non-public commit email'))
    tree = git('ls-tree', '-r', '--name-only', commit).decode().splitlines()
    for name in tree:
        if name.startswith(('.build/', 'target/', 'evidence/', 'diagnostics/')) or Path(name).suffix in {'.jar', '.zip', '.log'}:
            bad.append((commit, 'private/generated historical file'))
        data = git('show', f'{commit}:{name}')
        if private_path.search(data) or secret.search(data): bad.append((commit, 'historical private path or credential pattern'))
if bad:
    for path, reason in bad: print(path, reason)
    raise SystemExit(1)
print('Public source/history audit passed (heuristic; also inspect the complete diff).')
