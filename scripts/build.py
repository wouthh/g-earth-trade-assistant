#!/usr/bin/env python3
"""Package an exact clean commit with embedded, non-self-referential provenance."""
# Only builtins run before isolation; recipe-local modules/bytecode cannot shadow imports.
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.dont_write_bytecode):
    _native_os = __import__('posix' if 'posix' in sys.builtin_module_names else 'nt')
    _native_os.execv(sys.executable, [sys.executable, '-I', '-B', __file__, *sys.argv[1:]])

from pathlib import Path
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent
# Maven's source/resource trees, wrapper configuration, API recipe and assembly
# inputs plus Python recipes. Root notices/README/POM are tracked; root caches
# and target are not inputs. Recipe bytecode is refused, never imported by a CLI.
MAVEN_INPUT_TREES = ('src', '.mvn', 'build-support', 'packaging', 'scripts')


def clean_revision(root):
    def git(*args):
        # Rescan tracked files instead of trusting a stale fsmonitor result, and
        # do not execute its configured hook or refresh the caller's index.
        return subprocess.check_output(
            ['git', '--no-replace-objects', '--no-optional-locks',
             '-c', 'core.fsmonitor=false', *args],
            cwd=root, text=True).strip()
    if git('status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Commit the intended source before producing a delivery build')
    # -v marks assume-unchanged entries in lowercase; S marks skip-worktree.
    # Both can hide missing or changed tracked files from the status above.
    entries = git('ls-files', '-v', '-z').split('\0')
    if any(entry[:1] == 'S' or entry[:1].islower() for entry in entries):
        raise ValueError('Hidden tracked inputs cannot attest a complete committed checkout')
    if git('ls-files', '--others', '--ignored', '--exclude-standard', '-z',
           '--', *MAVEN_INPUT_TREES):
        raise ValueError('Ignored build inputs must be reviewed outside the delivery checkout')
    revision = git('rev-parse', 'HEAD')
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('A full committed source identity is required')
    return revision


def main():
    revision = clean_revision(ROOT)
    wrapper = ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    subprocess.run([str(wrapper), '-B', '--no-transfer-progress',
                    '-Dtradeassistant.sourceRevision=' + revision, 'clean', 'verify'],
                   cwd=ROOT, check=True)
    if clean_revision(ROOT) != revision:
        raise ValueError('Source changed during packaging; do not deliver these artifacts')
    print('Verified delivery build source: ' + revision)


if __name__ == '__main__':
    main()
