#!/usr/bin/env python3
"""Conservative checked-in file/history audit; prints paths/reasons, never matching data."""
# Only builtins run before isolation; recipe-local modules/bytecode cannot shadow imports.
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.dont_write_bytecode):
    _native_os = __import__('posix' if 'posix' in sys.builtin_module_names else 'nt')
    _native_os.execv(sys.executable, [sys.executable, '-I', '-B', __file__, *sys.argv[1:]])

from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
def git(*args):
    return subprocess.check_output(
        ['git', '--no-replace-objects', '--no-optional-locks',
         '-c', 'core.fsmonitor=false', *args], cwd=root)
def public_commit_identity(identity):
    """Accept public author emails and GitHub's server-side merge committer."""
    author_name, author_email, committer_name, committer_email = identity
    public_author = author_email.endswith('@users.noreply.github.com')
    public_committer = committer_email.endswith('@users.noreply.github.com')
    github_merge = (committer_name, committer_email) == ('GitHub', 'noreply@github.com')
    return public_author and (public_committer or github_merge)


def main():
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
        if not public_commit_identity(identity): bad.append((commit, 'non-public commit email'))
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


if __name__ == '__main__':
    main()
