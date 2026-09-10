#!/usr/bin/env python3
"""Verify a release ZIP and exchange one explicitly mapped stopped extension.

Linux renameat2 exchanges complete directories atomically. A private receipt
binds both inventories, allowing retries to identify either side of an interrupted
exchange. Application state lives elsewhere and is never opened here.
"""
# Only builtins run before isolation; recipe-local modules/bytecode cannot shadow imports.
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.dont_write_bytecode):
    _native_os = __import__('posix' if 'posix' in sys.builtin_module_names else 'nt')
    _native_os.execv(sys.executable, [sys.executable, '-I', '-B', __file__, *sys.argv[1:]])

import argparse
import contextlib
import ctypes
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import stat
import struct
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


def read_temporary(path, candidates):
    regular(path, True); info = path.stat()
    check(info.st_size <= min(LIMIT, max(map(len, candidates))), 'pending write budget exceeded')
    data = path.read_bytes()
    if info.st_nlink != 1:
        binding = path.parent / '.scope.json'
        check(path.name.startswith('.scope-') and info.st_nlink == 2 and binding.exists(), 'pending hardlink refused')
        regular(binding, True); other = binding.stat()
        check((info.st_dev, info.st_ino) == (other.st_dev, other.st_ino)
              and data in candidates, 'pending scope link pair differs')
    check(any(candidate.startswith(data) for candidate in candidates), 'pending write contents differ')
    return data


def write_pending(path, data, temporary):
    regular(path); regular(temporary)
    prefix = b""
    if temporary.exists(): prefix = read_temporary(temporary, [data])
    check(data.startswith(prefix), 'pending write contents differ')
    with temporary.open('ab' if temporary.exists() else 'xb') as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write(data[len(prefix):]); stream.flush(); os.fsync(stream.fileno())
    if path.name == '.scope.json':
        try: os.link(temporary, path)
        except FileExistsError:
            check(load_private(path) == json.loads(data), 'scope changed during publication')
        sync_dir(path.parent)
        temporary.unlink()
    else: os.replace(temporary, path)
    sync_dir(path.parent)
    if temporary.parent != path.parent: sync_dir(temporary.parent)


def save(path, value):
    check(len(json_bytes(value)) <= 32768, "generated receipt size budget exceeded")
    write_pending(path, json_bytes(value), path.with_name(path.name + '.pending'))


def scope_temporary(descriptor):
    return '.scope-' + sha(json_bytes(state_scope(descriptor))) + '.pending'


def member_temporary(slot, name):
    return slot.parent / (slot.name + '.member-' + sha(name.encode()) + '.pending')


def state_scope(descriptor):
    return {key: (sorted(descriptor[key]) if key == 'locks' else descriptor[key])
            for key in ('target', 'host_executable', 'locks')}


def retention_preflight(directory, descriptor, identity, pending_values=None, *, reserve=True):
    if not directory.exists(): return
    pending_values = pending_values or {}
    names = []
    with os.scandir(directory) as children:
        for child in children:
            names.append(child.name)
            check(len(names) <= 18, 'retained state entry capacity exceeded')
    check(sum(name in pending_values for name in names) <= 1, "multiple pending writes refused")
    for name in list(names):
        if name in pending_values:
            read_temporary(directory / name, pending_values[name])
            names.remove(name)
    if names:
        check('.scope.json' in names and load_private(directory / '.scope.json') == state_scope(descriptor),
              'retained state scope or host locks differ')
    generations = {}
    for name in names:
        if name == '.scope.json': continue
        match = re.fullmatch(r'([0-9a-f]{64})(\.json|\.retained)', name)
        check(match is not None, 'unknown retained state entry')
        generations.setdefault(match[1], set()).add(match[2])
    check(len(set(generations) | ({identity} if reserve else set())) <= 8,
          'eight retained generations reached; preserve and reconcile before another update')
    for generation, suffixes in generations.items():
        check('.json' in suffixes, 'orphan retained generation')
        receipt = load_private(directory / (generation + '.json'))
        check(receipt.get('identity') == generation and receipt.get('state') in ('preparing', 'installed', 'rolling_back', 'rolled_back'),
              'invalid retained generation receipt')
        check(isinstance(receipt.get('descriptor'), dict)
              and sha(json_bytes([receipt['descriptor'], receipt.get('manifest'), receipt.get('before'), receipt.get('after')])) == generation
              and receipt.get('before') == receipt['descriptor'].get('expected_files')
              and state_scope(receipt['descriptor']) == state_scope(descriptor), 'retained generation identity differs')
        manifest = receipt.get('manifest')
        check(isinstance(manifest, dict) and manifest.get('schema') == 1 and manifest.get('component') == 'g-earth-trade-assistant'
              and isinstance(manifest.get('source_revision'), str) and REVISION.fullmatch(manifest['source_revision'])
              and isinstance(manifest.get('version'), str) and SEMVER.fullmatch(manifest['version'])
              and isinstance(manifest.get('package_sha256'), str) and DIGEST.fullmatch(manifest['package_sha256']),
              'invalid retained generation manifest')
        for values in (receipt.get('before'), receipt.get('after'), manifest.get('files')):
            check(isinstance(values, dict) and set(values) == FILES
                  and all(isinstance(x, str) and DIGEST.fullmatch(x) for x in values.values()), 'invalid retained inventory')
        check(generation == identity or receipt['state'] in ('installed', 'rolled_back'), 'another retained operation is incomplete')
        if receipt['state'] != 'preparing': check('.retained' in suffixes, 'retained generation image missing')
        if '.retained' not in suffixes: continue
        actual = inventory(directory / (generation + '.retained'), partial=True)
        if receipt['state'] == 'preparing':
            valid = actual == receipt['before'] or all(receipt['after'].get(k) == v for k, v in actual.items())
        elif receipt['state'] == 'installed': valid = actual == receipt['before']
        elif receipt['state'] == 'rolled_back': valid = actual == receipt['after']
        else: valid = actual in (receipt['before'], receipt['after'])
        check(valid, 'retained generation contents changed')


def bind_retention_scope(directory, descriptor):
    path = directory / '.scope.json'
    if not path.exists() or (directory / scope_temporary(descriptor)).exists():
        write_pending(path, json_bytes(state_scope(descriptor)), directory / scope_temporary(descriptor))
    check(load_private(path) == state_scope(descriptor), 'retained state scope or host locks differ')
    with path.open('rb') as stream: os.fsync(stream.fileno())
    sync_dir(directory)


def inventory(root, partial=False):
    root = regular(root)
    check(root.is_dir() and root.stat().st_uid == os.getuid(), 'mapped extension missing or owned by another user')
    values = {}; size = 0
    for file in root.rglob('*'):
        name = file.relative_to(root).as_posix()
        check(file.lstat().st_uid == os.getuid(), 'extension entry owned by another user')
        check(not file.is_symlink(), 'extension symlink refused')
        if file.is_dir():
            check(name == 'extension', 'unknown extension directory')
            continue
        check(name in FILES and file.is_file(), 'unknown or non-regular extension file')
        size += file.stat().st_size; check(size <= LIMIT, 'extension size limit')
        values[name] = sha(file.read_bytes())
    check(partial or set(values) == FILES, 'incomplete extension inventory')
    return values


def verify_local_headers(archive, data):
    for member in archive.infolist():
        offset = member.header_offset
        check(0 <= offset <= len(data) - 30 and data[offset:offset + 4] == b'PK\x03\x04', 'invalid local ZIP header')
        flags, method = struct.unpack_from('<HH', data, offset + 6)
        check(method == member.compress_type and flags == member.flag_bits,
              'local and central ZIP metadata differ')


def verify_java_runtime(descriptor, host):
    command = descriptor['java_executable']
    check(isinstance(command, str) and command and not any(c in command for c in '\r\n\0{}'), 'invalid Java command')
    if command.startswith('/'):
        executable = Path(command)
        check(executable.name == 'java' and '..' not in executable.parts, 'absolute canonical Java executable required')
        check(str(executable) == command and executable.parent.name == 'bin', 'canonical Java bin layout required')
        signature = b'\x7fELF'
    else:
        windows = PureWindowsPath(command)
        check(windows.is_absolute() and windows.drive.upper() == 'C:' and windows.name.lower() == 'java.exe'
              and '..' not in windows.parts and '/' not in command, 'absolute supported Wine Java executable required')
        raw = command.split('\\')
        check(raw[0].upper() == 'C:' and len(raw) >= 3 and raw[-2].lower() == 'bin'
              and all(part and part not in ('.', '..') and part[-1] not in '. '
                      and not any(ord(c) < 32 or c in ':*?"<>|' for c in part)
                      and not re.fullmatch(r'(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])', part.split('.')[0], re.IGNORECASE)
                      for part in raw[1:]), 'ambiguous Windows Java path refused')
        drive = next((parent for parent in host.parents if parent.name == 'drive_c'), None)
        check(drive is not None, 'Wine Java drive is not mapped by the host')
        executable = drive
        for part in raw[1:]:
            selected = None
            with os.scandir(executable) as children:
                for child in children:
                    if child.name.casefold() == part.casefold():
                        check(selected is None, 'ambiguous Wine Java path component')
                        selected = executable / child.name
            check(selected is not None, 'missing Wine Java path component')
            executable = selected

        signature = b'MZ'
    release = executable.parent.parent / 'release'
    protected = [Path(descriptor[key]) for key in ('target', 'state_directory', 'receipt_directory') if key in descriptor]
    check(all(not path.is_relative_to(root) for path in (executable, release) for root in protected),
          'Java runtime overlaps a managed image')
    owners = []; snapshots = {}; identities = {}
    def identity(info):
        return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_mode, info.st_uid)
    for path, key, budget in [(executable, 'java_sha256', 16 * 1024 * 1024),
                              (release, 'java_release_sha256', 32768)]:
        regular(path)
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
            info = os.fstat(stream.fileno())
            check(stat.S_ISREG(info.st_mode) and info.st_uid in (0, os.getuid()) and not info.st_mode & 0o022
                    and info.st_size <= budget, 'untrusted or oversized Java runtime input')
            data = stream.read(budget + 1)
            check(len(data) == info.st_size and identity(os.fstat(stream.fileno())) == identity(info),
                    'Java runtime changed during snapshot')
        check(identity(path.stat(follow_symlinks=False)) == identity(info), 'Java runtime path changed during snapshot')
        owners.append(info.st_uid); snapshots[key] = data; identities[path] = identity(info)
        expected = descriptor.get(key)
        check(isinstance(expected, str) and re.fullmatch(r'[0-9a-f]{64}', expected)
              and sha(data) == expected, 'Java runtime attestation differs')
    binary = snapshots['java_sha256']
    check(binary.startswith(signature), 'Java executable format differs')
    if signature == b'MZ':
        check(len(binary) >= 64, 'Java PE header is truncated')
        pe_offset = struct.unpack_from('<I', binary, 60)[0]
        check(64 <= pe_offset <= len(binary) - 4 and binary[pe_offset:pe_offset + 4] == b'PE\0\0',
                'Java PE signature differs')
    if signature == b'\x7fELF': check(os.access(executable, os.X_OK), 'Java executable is not executable')

    check(owners[0] == owners[1] and (signature != b'MZ' or owners[0] == os.getuid()),
          'Java runtime ownership differs')
    release_text = snapshots['java_release_sha256'].decode().replace('\r\n', '\n').replace('\r', '\n')
    check(len(re.findall(r'^\s*JAVA_VERSION\s*=', release_text, re.MULTILINE)) == 1,
          'ambiguous Java release version assignment')
    versions = re.findall(r'^JAVA_VERSION="([^"\r\n]+)"$', release_text, re.MULTILINE)
    check(len(versions) == 1 and re.fullmatch(r'21(?:\.[0-9]+)*(?:[+_-][A-Za-z0-9.+_-]+)?', versions[0]),
          'attested runtime is not Java 21')
    check(all(identity(path.stat(follow_symlinks=False)) == value for path, value in identities.items()),
            'Java runtime path changed after snapshot')
    return command


def verify_managed_launcher(data, descriptor):
    expected = [descriptor['java_executable'], '-jar', 'G-Earth-Trade-Assistant.jar', '-p', '{port}', '-f', '{filename}', '-c', '{cookie}']
    check(json.loads(data) == expected, 'managed launcher does not match attested Java and placeholders')


def read_package(path, checksum, revision):
    path = regular(path)
    check(isinstance(checksum, str) and DIGEST.fullmatch(checksum)
          and isinstance(revision, str) and REVISION.fullmatch(revision), 'full package/source identities required')
    check(path.is_file() and path.stat().st_size <= LIMIT, 'package unavailable or oversized')
    data = path.read_bytes(); check(sha(data) == checksum, 'package checksum differs')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        verify_local_headers(archive, data)
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
    verify_jar(payload['extension/G-Earth-Trade-Assistant.jar'], version, revision)
    return {'schema': 1, 'component': 'g-earth-trade-assistant', 'source_revision': revision,
            'version': version, 'package_sha256': checksum,
            'files': {name: sha(data) for name, data in payload.items()}}, payload


def verify_jar(data, version, revision=None):
    check(len(data) <= LIMIT, 'JAR size limit')
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        verify_local_headers(jar, data)
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
        check([line for line in props if line.startswith('version=')] == ['version=' + version], 'JAR version differs from expected version')
        if tuple(map(int, version.split('.'))) >= (0, 1, 1):
            name = 'META-INF/tradeassistant-build.properties'
            check(jar.namelist().count(name) == 1, 'JAR build provenance missing or ambiguous')
            entry = jar.getinfo(name); check(entry.file_size <= 4096, 'JAR build provenance too large')
            provenance = jar.read(name).decode().splitlines()
            source = [line.removeprefix('source=') for line in provenance if line.startswith('source=')]
            check(len(source) == 1 and REVISION.fullmatch(source[0])
                  and (revision is None or source[0] == revision), 'JAR committed source differs or is unverified')
            check([line for line in provenance if line.startswith('version=')] == ['version=' + version], 'JAR build version differs')
        check(jar.testzip() is None, 'corrupt JAR')


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
    check(sys.platform.startswith("linux"), "managed installation requires Linux")
    check(os.getuid() == os.geteuid(), "real and effective installation user must match")
    descriptor = load_private(descriptor_path)
    check(descriptor.get('schema') == 1 and descriptor.get('component') == 'g-earth-trade-assistant', 'wrong descriptor component')
    target = regular(descriptor['target']); state = regular(descriptor['receipt_directory'])
    check(target.parent.name == 'Extensions' and re.fullmatch(r'G-Earth-Trade-Assistant-\d+\.\d+\.\d+', target.name), 'wrong mapped target')
    check(not state.is_relative_to(target.parent) and not target.is_relative_to(state), 'receipt/target overlap')
    host = regular(descriptor['host_executable'])
    check(host.is_file() and host.stat().st_size <= LIMIT and host.parent == target.parent.parent and sha(host.read_bytes()) == descriptor['host_sha256'], 'host identity differs')
    check(all(p.stat().st_uid == os.getuid() for p in (host, host.parent, target.parent, target)), 'host and target must belong to the inspected user')
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
    java = verify_java_runtime(descriptor, host); check(isinstance(java, str) and java and not any(c in java for c in '\0\r\n{}'), 'invalid explicit Java executable')
    command = json.loads(payload['command.txt'])
    if command[0] != java:
        command[0] = java; payload['command.txt'] = json_bytes(command)
    after = {name: sha(data) for name, data in payload.items()}
    identity = sha(json_bytes([descriptor, manifest, before, after]))
    receipt_path = state / (identity + '.json'); slot = state / (identity + '.retained')
    with writer_locks(locks):
        stopped(); check(load_private(descriptor_path) == descriptor, 'descriptor changed under lock')
        regular(host)
        check(sha(host.read_bytes()) == descriptor['host_sha256'], 'host changed under lock')
        check(verify_java_runtime(descriptor, host) == java, 'Java runtime changed under lock')
        ancestor = state
        while not ancestor.exists(): ancestor = ancestor.parent
        check(ancestor.stat().st_dev == target.parent.stat().st_dev, 'exchange requires the same filesystem')
        check(os.access(ancestor, os.W_OK | os.X_OK), 'receipt directory ancestor is not writable')
        check(target.parent.is_dir() and os.access(target.parent, os.W_OK | os.X_OK), 'target parent is not writable')
        if state.exists(): check(state.is_dir() and state.stat().st_uid == os.getuid()
                                 and not state.stat().st_mode & 0o077 and state.stat().st_mode & 0o700 == 0o700,
                                 'writable private receipt directory required')
        receipt = load_private(receipt_path) if receipt_path.exists() else None
        if receipt is None:
            check(not undo and not slot.exists() and inventory(target) == before, 'unowned or changed installation')
            verify_jar((target / 'extension/G-Earth-Trade-Assistant.jar').read_bytes(), old_version)
            verify_managed_launcher((target / 'command.txt').read_bytes(), descriptor)
            receipt = {'identity': identity, 'descriptor': descriptor, 'manifest': manifest, 'before': before, 'after': after, 'state': 'preparing'}
        check(receipt.get('identity') == identity and receipt.get('descriptor') == descriptor and receipt.get('manifest') == manifest
              and receipt.get('before') == before and receipt.get('after') == after
              and receipt.get('state') in ('preparing', 'installed', 'rolling_back', 'rolled_back'), 'receipt mismatch')
        pending = {scope_temporary(descriptor): [json_bytes(state_scope(descriptor))],
                   identity + '.json.pending': [json_bytes(dict(receipt, state=value))
                       for value in ('preparing', 'installed', 'rolling_back', 'rolled_back')]}
        pending.update({member_temporary(slot, name).name: [data] for name, data in payload.items()})
        unchanged = not receipt_path.exists() and before == after
        check(unchanged or all(len(value) <= 32768 for value in pending[identity + '.json.pending']),
              'generated receipt size budget exceeded')
        retention_preflight(state, descriptor, identity, pending, reserve=not unchanged)
        if unchanged:
            return {'state': 'preview' if preview else 'unchanged', 'manifest': manifest,
                    'installed_files': after, 'loaded': False, 'rollback': 'existing installation was not changed'}
        current = inventory(target); retained = inventory(slot, partial=True) if slot.exists() else None
        original = target if current == before else slot
        check(inventory(original) == before, 'original installation identity differs')
        verify_jar((original / 'extension/G-Earth-Trade-Assistant.jar').read_bytes(), old_version)
        verify_managed_launcher((original / 'command.txt').read_bytes(), descriptor)
        if undo:
            check(receipt['state'] != 'preparing', 'install must finish before rollback')
            restored = receipt['state'] in ('rolling_back', 'rolled_back') and current == before and retained == after
            check(restored or (receipt['state'] in ('installed', 'rolling_back') and current == after and retained == before), 'rollback identities differ')
            if preview: return {'state': 'preview', 'action': 'rollback', 'loaded': False}
            if not restored:
                receipt['state'] = 'rolling_back'; save(receipt_path, receipt)
                stopped(); check(inventory(target) == after and inventory(slot) == before, 'rollback inputs drifted')
                verify_java_runtime(descriptor, host)
                verify_managed_launcher((slot / "command.txt").read_bytes(), descriptor)
                exchange(target, slot)
            check(inventory(target) == before and inventory(slot) == after, 'rollback verification failed')
            sync_dir(target.parent); sync_dir(state)
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
        bind_retention_scope(state, descriptor)
        if not receipt_path.exists(): save(receipt_path, receipt)
        if not done:
            slot.mkdir(mode=0o700, exist_ok=True); (slot / 'extension').mkdir(mode=0o700, exist_ok=True)
            for name, data in payload.items():
                path = slot / name
                if path.exists(): continue
                write_pending(path, data, member_temporary(slot, name))
            sync_dir(slot / 'extension'); sync_dir(slot); sync_dir(state)
            stopped(); check(inventory(target) == before and inventory(slot) == after, 'activation inputs drifted')
            verify_java_runtime(descriptor, host)
            verify_managed_launcher((slot / "command.txt").read_bytes(), descriptor)
            exchange(target, slot)
        check(inventory(target) == after and inventory(slot) == before, 'installed verification failed')
        sync_dir(target.parent); sync_dir(state)
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
