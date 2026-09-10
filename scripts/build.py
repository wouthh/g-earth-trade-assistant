#!/usr/bin/env python3
"""Package an exact clean commit with embedded, non-self-referential provenance."""
from pathlib import Path
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent
# Maven's source/resource trees, wrapper configuration, API recipe and assembly
# inputs. Root notices/README/POM are tracked; caches and target are not inputs.
MAVEN_INPUT_TREES = ('src', '.mvn', 'build-support', 'packaging')


def clean_revision(root):
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
    if git('status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Commit the intended source before producing a delivery build')
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
