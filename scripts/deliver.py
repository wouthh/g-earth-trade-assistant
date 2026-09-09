#!/usr/bin/env python3
"""Verify a release ZIP and exchange one explicitly mapped stopped extension.

Linux renameat2 exchanges complete directories atomically. A private receipt
binds both inventories, allowing retries to identify either side of an interrupted
exchange. Application state lives elsewhere and is never opened here.
"""
import argparse
import contextlib
import ctypes
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import zipfile
import zlib

FILES = frozenset({'command.txt', 'README.md', 'LICENSE', 'THIRD-PARTY-NOTICES.md',
                   'extension/G-Earth-Trade-Assistant.jar'})
DIGEST = re.compile(r'[0-9a-f]{64}\Z')
REVISION = re.compile(r'[0-9a-f]{40}\Z')
SEMVER = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z')
LIMIT = 64 * 1024 * 1024


class Refused(RuntimeError):
    pass


def check(ok, message):
    if not ok:
        raise Refused(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()


def regular(path, private=False):
    path = Path(path)
    check(path.is_absolute() and '..' not in path.parts, 'absolute non-traversing path required')
    for current in [path, *path.parents]:
        if current.exists() or current.is_symlink():
            check(not current.is_symlink(), 'symlink path refused')
    if private:
        info = path.stat()
        check(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
              and info.st_mode & 0o077 == 0, 'private regular file required')
    return path


def load_private(path):
    path = regular(path, True)
    check(path.stat().st_size <= 32768, 'private receipt budget exceeded')
    value = json.loads(path.read_bytes())
    check(isinstance(value, dict), 'object receipt required')
    return value


def sync_dir(path):
    fd = os.open(path, os.O_DIRECTORY | os.O_RDONLY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def save(path, value):
    fd, temporary = tempfile.mkstemp(prefix='.receipt-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(json_bytes(value)); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path); sync_dir(path.parent)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def inventory(root, partial=False):
    root = regular(root)
    check(root.is_dir(), 'mapped extension directory missing')
    values = {}; size = 0
    for file in root.rglob('*'):
        name = file.relative_to(root).as_posix()
        check(not file.is_symlink(), 'extension symlink refused')
        if file.is_dir():
            check(name == 'extension', 'unknown extension directory')
            continue
        check(name in FILES and file.is_file(), 'unknown or non-regular extension file')
        size += file.stat().st_size; check(size <= LIMIT, 'extension size limit')
        values[name] = sha(file.read_bytes())
    check(partial or set(values) == FILES, 'incomplete extension inventory')
    return values


def read_package(path, checksum, revision):
    path = regular(path)
    check(isinstance(checksum, str) and DIGEST.fullmatch(checksum)
          and isinstance(revision, str) and REVISION.fullmatch(revision), 'full package/source identities required')
    check(path.is_file() and path.stat().st_size <= LIMIT, 'package unavailable or oversized')
    data = path.read_bytes(); check(sha(data) == checksum, 'package checksum differs')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        check(len(entries) <= 7 and sum(e.file_size for e in entries) <= LIMIT, 'package expansion limit')
        check(all(e.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) for e in entries), 'unsupported package compression')
        names = [e.filename.rstrip('/') for e in entries]
        check(len(set(n.casefold() for n in names)) == len(names), 'ambiguous package paths')
        top = names[0].split('/')[0] if names else ''
        prefix = 'G-Earth-Trade-Assistant-'; version = top.removeprefix(prefix)
        check(top.startswith(prefix) and SEMVER.fullmatch(version), 'extension version identity missing')
        payload = {}
        for entry in entries:
            canonical = PurePosixPath(entry.filename)
            check(canonical.as_posix() == entry.filename.rstrip('/'), 'noncanonical package path')
            parts = canonical.parts
            check(parts and parts[0] == top and '..' not in parts and '\\' not in entry.filename,
                  'package path escapes extension')
            kind = stat.S_IFMT(entry.external_attr >> 16)
            check(kind in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG), 'non-regular archive member')
            if entry.is_dir():
                check(entry.filename in (top + '/', top + '/extension/'), 'unknown package directory')
            else:
                relative = '/'.join(parts[1:]); check(relative in FILES, 'unexpected package member')
                payload[relative] = archive.read(entry)
    check(set(payload) == FILES, 'incomplete package')
    command = json.loads(payload['command.txt'])
    check(isinstance(command, list) and len(command) == 9 and isinstance(command[0], str)
          and command[1:] == ['-jar', 'G-Earth-Trade-Assistant.jar', '-p', '{port}', '-f', '{filename}', '-c', '{cookie}'],
          'launcher placeholders or layout differ')
    with zipfile.ZipFile(io.BytesIO(payload['extension/G-Earth-Trade-Assistant.jar'])) as jar:
        check(len(jar.infolist()) <= 10000 and sum(e.file_size for e in jar.infolist()) <= LIMIT, 'JAR expansion limit')
        check(all(e.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) for e in jar.infolist()), 'unsupported JAR compression')
        fields = {}; last = None
        for line in jar.read('META-INF/MANIFEST.MF').decode().splitlines():
            if not line: break
            if line.startswith(' '):
                check(last is not None, 'invalid JAR continuation'); fields[last] += line[1:]
            else:
                key, sep, value = line.partition(': '); key = key.lower()
                check(sep and key not in fields, 'ambiguous JAR manifest'); fields[key] = value; last = key
        check(fields.get('main-class') == 'io.github.wouthh.tradeassistant.protocol.TradeAssistantExtension', 'wrong JAR entry point')
        check('io/github/wouthh/tradeassistant/protocol/TradeAssistantExtension.class' in jar.namelist(), 'JAR entrypoint class missing')
        props = jar.read('META-INF/maven/io.github.wouthh/g-earth-trade-assistant/pom.properties').decode().splitlines()
        check('version=' + version in props, 'JAR/package version differs')
        check(jar.testzip() is None, 'corrupt JAR')
    return {'schema': 1, 'component': 'g-earth-trade-assistant', 'source_revision': revision,
            'version': version, 'package_sha256': checksum,
            'files': {name: sha(data) for name, data in payload.items()}}, payload


def no_java():
    check(sys.platform.startswith('linux'), 'managed exchange requires Linux')
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit(): continue
        try:
            check(proc.stat().st_uid != os.getuid() or (proc / 'comm').read_text().strip().lower()
                  not in ('java', 'java.exe', 'javaw.exe'), 'Java host is active; delivery deferred')
        except (FileNotFoundError, ProcessLookupError): pass


def exchange(left, right):
    libc = ctypes.CDLL(None, use_errno=True)
    call = libc.renameat2
    call.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    call.restype = ctypes.c_int
    if call(-100, os.fsencode(left), -100, os.fsencode(right), 2):
        code = ctypes.get_errno(); raise OSError(code, os.strerror(code))
    sync_dir(left.parent); sync_dir(right.parent)


@contextlib.contextmanager
def writer_locks(paths):
    import fcntl
    with contextlib.ExitStack() as resources:
        for path in sorted(paths):
            regular(path); expected = os.stat(path)
            fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW); resources.callback(os.close, fd)
            actual = os.fstat(fd)
            check((expected.st_dev, expected.st_ino) == (actual.st_dev, actual.st_ino)
                  and stat.S_ISREG(actual.st_mode) and actual.st_uid == os.getuid()
                  and not actual.st_mode & 0o022 and actual.st_size == 0, 'lock identity changed')
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def deliver(descriptor_path, package, checksum, revision, *, preview=False, undo=False, stopped=no_java):
    descriptor = load_private(descriptor_path)
    check(descriptor.get('schema') == 1 and descriptor.get('component') == 'g-earth-trade-assistant', 'wrong descriptor component')
    target = regular(descriptor['target']); state = regular(descriptor['receipt_directory'])
    check(target.parent.name == 'Extensions' and re.fullmatch(r'G-Earth-Trade-Assistant-\d+\.\d+\.\d+', target.name), 'wrong mapped target')
    check(not state.is_relative_to(target.parent) and not target.is_relative_to(state), 'receipt/target overlap')
    host = regular(descriptor['host_executable'])
    check(host.is_file() and host.stat().st_size <= LIMIT and host.parent == target.parent.parent and sha(host.read_bytes()) == descriptor['host_sha256'], 'host identity differs')
    locks = descriptor['locks']
    check(isinstance(locks, list) and 1 <= len(locks) <= 4 and len(set(locks)) == len(locks), 'existing host locks required')
    check(all(not Path(p).is_relative_to(target) for p in [descriptor_path, package, *locks]),
          'delivery dependencies must be outside the replaced target')
    before = descriptor['expected_files']
    check(isinstance(before, dict) and set(before) == FILES
          and all(isinstance(x, str) and DIGEST.fullmatch(x) for x in before.values()), 'exact managed before inventory required')
    manifest, payload = read_package(package, checksum, revision)
    old_version = descriptor['expected_version']; check(isinstance(old_version, str) and SEMVER.fullmatch(old_version), 'invalid expected version')
    check(tuple(map(int, manifest['version'].split('.'))) >= tuple(map(int, old_version.split('.'))), 'older version refused')
    java = descriptor['java_executable']; check(isinstance(java, str) and java and not any(c in java for c in '\0\r\n{}'), 'invalid explicit Java executable')
    command = json.loads(payload['command.txt'])
    if command[0] != java:
        command[0] = java; payload['command.txt'] = json_bytes(command)
    after = {name: sha(data) for name, data in payload.items()}
    identity = sha(json_bytes([descriptor, manifest]))
    receipt_path = state / (identity + '.json'); slot = state / (identity + '.retained')
    with writer_locks(locks):
        stopped(); check(load_private(descriptor_path) == descriptor, 'descriptor changed under lock')
        regular(host)
        check(sha(host.read_bytes()) == descriptor['host_sha256'], 'host changed under lock')
        ancestor = state
        while not ancestor.exists(): ancestor = ancestor.parent
        check(ancestor.stat().st_dev == target.parent.stat().st_dev, 'exchange requires the same filesystem')
        check(os.access(ancestor, os.W_OK | os.X_OK), 'receipt directory ancestor is not writable')
        if state.exists(): check(state.is_dir() and state.stat().st_uid == os.getuid()
                                 and not state.stat().st_mode & 0o077 and state.stat().st_mode & 0o700 == 0o700,
                                 'writable private receipt directory required')
        receipt = load_private(receipt_path) if receipt_path.exists() else None
        if receipt is None:
            check(not undo and not slot.exists() and inventory(target) == before, 'unowned or changed installation')
            if before == after:
                return {'state': 'preview' if preview else 'unchanged', 'manifest': manifest,
                        'installed_files': after, 'loaded': False, 'rollback': 'existing installation was not changed'}
            receipt = {'identity': identity, 'manifest': manifest, 'before': before, 'after': after, 'state': 'preparing'}
        check(receipt.get('identity') == identity and receipt.get('manifest') == manifest
              and receipt.get('before') == before and receipt.get('after') == after
              and receipt.get('state') in ('preparing', 'installed', 'rolling_back', 'rolled_back'), 'receipt mismatch')
        current = inventory(target); retained = inventory(slot, partial=True) if slot.exists() else None
        if undo:
            check(receipt['state'] != 'preparing', 'install must finish before rollback')
            restored = receipt['state'] in ('rolling_back', 'rolled_back') and current == before and retained == after
            check(restored or (receipt['state'] in ('installed', 'rolling_back') and current == after and retained == before), 'rollback identities differ')
            if preview: return {'state': 'preview', 'action': 'rollback', 'loaded': False}
            if not restored:
                receipt['state'] = 'rolling_back'; save(receipt_path, receipt)
                stopped(); check(inventory(target) == after and inventory(slot) == before, 'rollback inputs drifted')
                exchange(target, slot)
            check(inventory(target) == before and inventory(slot) == after, 'rollback verification failed')
            receipt['state'] = 'rolled_back'; save(receipt_path, receipt)
            return {'state': 'rolled_back', 'loaded': False}
        check(receipt['state'] in ('preparing', 'installed'), 'rollback completed or pending; reconcile before reinstall')
        done = current == after and retained == before
        check(done or (receipt['state'] == 'preparing' and current == before
              and (retained is None or all(after.get(n) == value for n, value in retained.items()))), 'installation inputs drifted')
        if preview: return {'state': 'preview', 'action': 'install', 'loaded': False}
        missing = []; directory = state
        while not directory.exists(): missing.append(directory); directory = directory.parent
        for directory in reversed(missing):
            directory.mkdir(mode=0o700)
        # A previous attempt may have created these links but failed their flush.
        # Revalidate durability on retries, including already existing ancestors.
        for directory in [state, *state.parents]:
            sync_dir(directory)
        if not receipt_path.exists(): save(receipt_path, receipt)
        if not done:
            slot.mkdir(mode=0o700, exist_ok=True); (slot / 'extension').mkdir(mode=0o700, exist_ok=True)
            for name, data in payload.items():
                path = slot / name
                if path.exists(): continue
                fd, temporary = tempfile.mkstemp(prefix='.member-', dir=path.parent)
                try:
                    with os.fdopen(fd, 'wb') as stream:
                        stream.write(data); stream.flush(); os.fsync(stream.fileno())
                    os.rename(temporary, path); sync_dir(path.parent)
                finally:
                    if os.path.exists(temporary): os.unlink(temporary)
            sync_dir(slot); sync_dir(state)
            stopped(); check(inventory(target) == before and inventory(slot) == after, 'activation inputs drifted')
            exchange(target, slot)
        check(inventory(target) == after and inventory(slot) == before, 'installed verification failed')
        receipt['state'] = 'installed'; save(receipt_path, receipt)
        return {'state': 'unchanged' if done else 'installed', 'manifest': manifest,
                'installed_files': after, 'receipt': str(receipt_path), 'loaded': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['verify', 'install', 'rollback'])
    parser.add_argument('--package', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--descriptor', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args(); os.umask(0o077)
    if args.action == 'verify': result = read_package(args.package, args.sha256, args.source)[0]
    else:
        check(args.descriptor is not None, 'private descriptor required')
        result = deliver(args.descriptor, args.package, args.sha256, args.source,
                         preview=args.dry_run, undo=args.action == 'rollback')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    try: main()
    except (RuntimeError, OSError, ValueError, TypeError, KeyError, EOFError, zlib.error, zipfile.BadZipFile) as error:
        print('Delivery refused: ' + (str(error) if isinstance(error, Refused) else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)
