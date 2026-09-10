#!/usr/bin/env python3
"""Build only the public pinned API. Never operates on installed G-Earth."""
# Only builtins run before isolation; recipe-local modules/bytecode cannot shadow imports.
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.dont_write_bytecode):
    _native_os = __import__('posix' if 'posix' in sys.builtin_module_names else 'nt')
    _native_os.execv(sys.executable, [sys.executable, '-I', '-B', __file__, *sys.argv[1:]])

import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PIN = 'b993d5ba0b23ab5644633abb8074229d1cb53b71'
SHA256 = '3a5fa509635420b8554d32ced03a33c3d4a07be2a4f497b05eb6598ae9fc39fa'
CACHE = ROOT / '.build'
CACHE.mkdir(exist_ok=True)
archive = CACHE / 'gearth-source.tar.gz'
if not archive.exists():
    data = urllib.request.urlopen('https://codeload.github.com/G-Realm/G-Earth/tar.gz/' + PIN, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit('Public API archive checksum mismatch')
    archive.write_bytes(data)
if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
    raise SystemExit('Cached API archive checksum mismatch')
# Fresh extraction to a new owned directory prevents stale edited source from being built.
import tempfile
with tempfile.TemporaryDirectory(prefix='api-', dir=CACHE) as work:
    with tarfile.open(fileobj=io.BytesIO(archive.read_bytes())) as source:
        source.extractall(work, filter='data')
    api = Path(work) / ('G-Earth-' + PIN) / 'G-Earth-Api'
    # Pinned upstream spins on partial-frame EOF. Keep the bundled extension API's
    # localhost transport bounded; this does not patch or replace the host installation.
    extension = api / 'src/main/java/gearth/extensions/Extension.java'
    source = extension.read_text()
    old = '''                int amountRead = 0;

                while (amountRead < length) {
                    amountRead += dIn.read(headerandbody, 4 + amountRead, Math.min(dIn.available(), length - amountRead));
                }'''
    if source.count(old) != 1:
        raise SystemExit('Pinned upstream read-loop precondition failed')
    source = source.replace(old, '                dIn.readFully(headerandbody, 4, length);')
    old_length = '                byte[] headerandbody = new byte[length + 4];'
    if source.count(old_length) != 1:
        raise SystemExit('Pinned upstream frame-bound precondition failed')
    source = source.replace(old_length, '                if (length < 2 || length > 4_000_000) throw new IOException("Invalid host frame length");\n' + old_length)
    extension.write_text(source)
    env = dict(os.environ, MAVEN_USER_HOME=str(CACHE / 'maven-user-home'))
    wrapper = ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    subprocess.run([str(wrapper), '-B', '-q', '-f', str(ROOT / 'build-support/api-pom.xml'),
        '-Dmaven.repo.local=' + str(CACHE / 'm2'), '-Dapi.source=' + str(api), 'clean', 'install'],
        cwd=ROOT, env=env, check=True)
print('Pinned public G-Earth API built: ' + PIN)
