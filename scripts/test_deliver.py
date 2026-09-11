"""Offline release and atomic update fixtures; no installed host or account input."""
import io
from contextlib import nullcontext
import json
import os
import struct
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import deliver as d

SOURCE = 'a' * 40
VERSION = '0.1.0'
TOP = 'G-Earth-Trade-Assistant-' + VERSION
COMMAND = ['java21', '-jar', 'G-Earth-Trade-Assistant.jar', '-p', '{port}', '-f', '{filename}', '-c', '{cookie}']


def synthetic_jar(version=VERSION, source=SOURCE):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as jar:
        jar.writestr('io/github/wouthh/tradeassistant/protocol/TradeAssistantExtension.class', b'synthetic class')
        modern = tuple(map(int, version.split('.'))) >= (0, 1, 2)
        main = 'runtime.SnapshotClassLoader' if modern else 'protocol.TradeAssistantExtension'
        jar.writestr('META-INF/MANIFEST.MF', 'Main-Class: io.github.wouthh.tradeassistant.' + main + '\n')
        if modern:
            jar.writestr('io/github/wouthh/tradeassistant/runtime/SnapshotClassLoader.class', b'synthetic bootstrap')
        jar.writestr('META-INF/maven/io.github.wouthh/g-earth-trade-assistant/pom.properties', 'version=' + version + '\n')
        if version != '0.1.0':
            jar.writestr('META-INF/tradeassistant-build.properties', 'source=' + source + '\nversion=' + version + '\n')
    return buffer.getvalue()


class PackageTests(unittest.TestCase):
    def test_modern_bootstrap_and_legacy_identity_are_both_verified(self):
        for version in ('0.1.0', '0.1.1', '0.1.2'):
            d.verify_jar(synthetic_jar(version), version, SOURCE if version != '0.1.0' else None)
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(synthetic_jar('0.1.2'))) as original, zipfile.ZipFile(output, 'w') as changed:
            for name in original.namelist():
                if not name.endswith('/SnapshotClassLoader.class'):
                    changed.writestr(name, original.read(name))
        with self.assertRaisesRegex(d.Refused, 'JAR bootstrap class missing'):
            d.verify_jar(output.getvalue(), '0.1.2', SOURCE)

    def snapshot_jar(self, entries=(), attributes=""):
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(synthetic_jar('0.1.3'))) as original, zipfile.ZipFile(output, 'w') as changed:
            for entry in original.infolist():
                data = original.read(entry)
                if entry.filename == 'META-INF/MANIFEST.MF':
                    data += attributes.encode()
                changed.writestr(entry, data)
            for name, data in entries:
                changed.writestr(name, data)
        return output.getvalue()

    def test_snapshot_package_rejects_loader_invalid_entries(self):
        d.verify_jar(self.snapshot_jar([('assets/', b''), ('assets/value', b'ok')]), '0.1.3', SOURCE)
        cases = [[('dup', b'one'), ('dup', b'two')], [('/absolute', b'x')],
                 [('a/../value', b'x')], [('a\\value', b'x')], [('assets/', b'payload')]]
        for entries in cases:
            with self.subTest(entries=entries), self.assertWarns(UserWarning) if len(entries) == 2 else nullcontext():
                data = self.snapshot_jar(entries)
                with self.assertRaises(d.Refused):
                    d.verify_jar(data, '0.1.3', SOURCE)

    def test_snapshot_package_rejects_loader_unsupported_manifest_attributes(self):
        for attributes in ('Class-Path: external.jar\n', 'cLaSs-PaTh: \n',
                           'Multi-Release: true\n', 'Multi-Release: false\n', 'MULTI-RELEASE: \n'):
            with self.subTest(attributes=attributes), self.assertRaises(d.Refused):
                d.verify_jar(self.snapshot_jar(attributes=attributes), '0.1.3', SOURCE)

    def test_provenance_rejects_properties_aliases_duplicates_and_unknown_fields(self):
        canonical = 'source=' + SOURCE + '\nversion=0.1.1\n'
        for extra in ('vers\\u0069on=9.9.9\n', 'source : ' + 'b' * 40 + '\n',
                      'version=0.1.1\n', 'unknown=value\n', ' version=9.9.9\n'):
            with self.subTest(extra=extra):
                output = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(synthetic_jar('0.1.1'))) as jar, \
                        zipfile.ZipFile(output, 'w') as changed:
                    for name in jar.namelist():
                        changed.writestr(name, canonical + extra if name.endswith(
                            'tradeassistant-build.properties') else jar.read(name))
                with self.assertRaises(d.Refused):
                    d.verify_jar(output.getvalue(), '0.1.1', SOURCE)

    def test_new_runtime_requires_exact_committed_source_provenance(self):
        d.verify_jar(synthetic_jar('0.1.1'), '0.1.1', SOURCE)
        for source in ('unverified', 'b' * 40):
            with self.subTest(source=source), self.assertRaises(d.Refused):
                d.verify_jar(synthetic_jar('0.1.1', source), '0.1.1', SOURCE)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.package = self.root / 'package.zip'
        self.files = {name: ('synthetic ' + name).encode() for name in d.FILES}
        self.files['command.txt'] = json.dumps(COMMAND).encode()
        self.files['extension/G-Earth-Trade-Assistant.jar'] = synthetic_jar()
        self.make_package()

    def make_package(self, extra=None):
        with zipfile.ZipFile(self.package, 'w') as archive:
            for name, data in self.files.items(): archive.writestr(TOP + '/' + name, data)
            if extra: archive.writestr(extra, b'unsafe extra')
        self.digest = d.sha(self.package.read_bytes())

    def test_local_only_compression_disagreement_is_refused(self):
        data = bytearray(self.package.read_bytes()); struct.pack_into('<H', data, 8, 99)
        bad = self.root / 'local-method.zip'; bad.write_bytes(data)
        with self.assertRaisesRegex(d.Refused, 'local and central'): d.read_package(bad, d.sha(data), SOURCE)
        jar = bytearray(synthetic_jar()); struct.pack_into('<H', jar, 8, 99)
        with self.assertRaisesRegex(d.Refused, 'local and central'): d.verify_jar(jar, VERSION)

    def test_complete_manifest(self):
        manifest, files = d.read_package(self.package, self.digest, SOURCE)
        self.assertEqual(files, self.files)
        self.assertEqual(manifest['source_revision'], SOURCE)
        self.assertEqual(manifest['version'], VERSION)
        self.assertEqual(set(manifest['files']), d.FILES)

    def test_corrupt_identity_and_path(self):
        with self.assertRaises(d.Refused): d.read_package(self.package, '0' * 64, SOURCE)
        with self.assertRaises(d.Refused): d.read_package(self.package, self.digest, 'main')
        for extra in ['../outside', TOP + '/../outside', TOP + '/extra', '/' + TOP + '/escape', TOP + '/./command.txt']:
            with self.subTest(extra=extra):
                self.make_package(extra)
                with self.assertRaises(d.Refused): d.read_package(self.package, self.digest, SOURCE)

    def test_missing_entrypoint_class(self):
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(synthetic_jar())) as jar, zipfile.ZipFile(output, 'w') as changed:
            for name in jar.namelist():
                if not name.endswith('.class'): changed.writestr(name, jar.read(name))
        self.files['extension/G-Earth-Trade-Assistant.jar'] = output.getvalue(); self.make_package()
        with self.assertRaises(d.Refused): d.read_package(self.package, self.digest, SOURCE)

    def test_unsupported_zip_cli_failure_is_sanitized(self):
        for codec in (zipfile.ZIP_BZIP2, zipfile.ZIP_LZMA, 99):
            with self.subTest(codec=codec):
                data = bytearray(self.package.read_bytes())
                for signature, offset in [(b'PK\x03\x04', 8), (b'PK\x01\x02', 10)]:
                    position = data.index(signature); struct.pack_into('<H', data, position + offset, codec)
                bad = self.root / 'unsupported.zip'; bad.write_bytes(data)
                result = subprocess.run([sys.executable, d.__file__, 'verify', '--package', str(bad),
                                         '--sha256', d.sha(data), '--source', SOURCE], capture_output=True, text=True)
                self.assertEqual(result.returncode, 1)
                self.assertTrue(result.stderr.startswith('Delivery refused:'))
                self.assertNotIn('Traceback', result.stderr); self.assertNotIn(str(self.root), result.stderr)

    def test_launcher_and_version_guard(self):
        original = self.files['command.txt']
        self.files['command.txt'] = json.dumps(['java', '-jar', 'wrong.jar']).encode(); self.make_package()
        with self.assertRaises(d.Refused): d.read_package(self.package, self.digest, SOURCE)
        self.files['command.txt'] = original
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(synthetic_jar())) as jar, zipfile.ZipFile(output, 'w') as changed:
            for name in jar.namelist():
                changed.writestr(name, b'version=9.0.0\n' if name.endswith('pom.properties') else jar.read(name))
        self.files['extension/G-Earth-Trade-Assistant.jar'] = output.getvalue(); self.make_package()
        with self.assertRaises(d.Refused): d.read_package(self.package, self.digest, SOURCE)


@unittest.skipUnless(sys.platform.startswith('linux'), 'Linux atomic directory exchange lane')
class ExchangeTests(PackageTests):
    def setUp(self):
        super().setUp()
        self.target = self.root / 'host/Extensions' / TOP
        (self.target / 'extension').mkdir(parents=True)
        for name in d.FILES: (self.target / name).write_bytes(b'old ' + name.encode())
        (self.target / 'extension/G-Earth-Trade-Assistant.jar').write_bytes(synthetic_jar())
        self.other = self.target.parent / 'OtherPlugin/settings.json'
        self.other.parent.mkdir(); self.other.write_bytes(b'unrelated data')
        self.host = self.root / 'host/G-Earth.exe'; self.host.write_bytes(b'synthetic verified host')
        self.lock = self.root / 'content.lock'; self.lock.touch(mode=0o600)
        self.state = self.root / 'receipts'; self.descriptor = self.root / 'descriptor.json'
        self.before = d.inventory(self.target)
        self.desc = {'schema': 1, 'component': 'g-earth-trade-assistant', 'target': str(self.target),
                     'receipt_directory': str(self.state), 'host_executable': str(self.host),
                     'host_sha256': d.sha(self.host.read_bytes()), 'locks': [str(self.lock)],
                     'expected_files': self.before, 'expected_version': VERSION, 'java_executable': COMMAND[0]}
        self.java = self.root / "jdk/bin/java"; self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b"\x7fELFsynthetic Java 21 executable; never executed"); self.java.chmod(0o700)
        self.release = self.java.parent.parent / "release"; self.release.write_text('JAVA_VERSION="21.0.11"\n'); self.release.chmod(0o600)
        self.desc.update({"java_executable": str(self.java), "java_sha256": d.sha(self.java.read_bytes()),
                          "java_release_sha256": d.sha(self.release.read_bytes())})
        launcher = list(d.LAUNCH if hasattr(d, 'LAUNCH') else COMMAND); launcher[0] = self.desc['java_executable']
        (self.target / 'command.txt').write_bytes(d.json_bytes(launcher))
        self.before = d.inventory(self.target); self.desc['expected_files'] = self.before
        self.write_descriptor()

    def write_descriptor(self):
        self.descriptor.write_bytes(d.json_bytes(self.desc)); self.descriptor.chmod(0o600)

    def run_delivery(self, **options):
        return d.deliver(self.descriptor, self.package, self.digest, SOURCE, stopped=lambda: None, **options)


    def test_stale_descriptor_cannot_hide_newer_installed_version(self):
        (self.target / 'extension/G-Earth-Trade-Assistant.jar').write_bytes(synthetic_jar('0.2.0'))
        self.desc['expected_files'] = d.inventory(self.target); self.write_descriptor()
        for preview in (False, True):
            with self.assertRaisesRegex(d.Refused, 'JAR version differs'): self.run_delivery(preview=preview)
        self.assertFalse(self.state.exists())

    @unittest.skipIf(hasattr(os, "getuid") and os.getuid() == 0, "root bypasses discretionary mode denials")
    def test_unwritable_target_parent_rejects_preview_and_execution(self):
        self.target.parent.chmod(0o500)
        try:
            for preview in (True, False):
                with self.assertRaisesRegex(d.Refused, 'target parent is not writable'): self.run_delivery(preview=preview)
            self.assertFalse(self.state.exists())
        finally: self.target.parent.chmod(0o700)

    def test_completed_retry_reflushes_both_exchange_parents(self):
        original_sync = d.sync_dir
        for undo in (False, True):
            def fail_final(path):
                final = (d.inventory(self.target) == self.before) == undo
                if Path(path) == self.target.parent and final: raise OSError('synthetic target-parent flush failure')
                original_sync(path)
            with patch.object(d, 'sync_dir', side_effect=fail_final):
                for retry in range(2):
                    with self.assertRaises(OSError): self.run_delivery(undo=undo)
            receipt = json.loads(next(self.state.glob('[0-9a-f]*.json')).read_text())
            self.assertNotEqual(receipt['state'], 'rolled_back' if undo else 'installed')
            self.run_delivery(undo=undo)
        self.assertEqual(d.inventory(self.target), self.before)

    def test_retry_flushes_existing_staged_jar_parent_before_exchange(self):
        original_sync = d.sync_dir
        def fail_nested(path):
            if Path(path).name == 'extension' and Path(path).parent.parent == self.state:
                raise OSError('synthetic nested-directory flush failure')
            original_sync(path)
        with patch.object(d, 'sync_dir', side_effect=fail_nested):
            for retry in range(2):
                with self.assertRaises(OSError): self.run_delivery()
                self.assertEqual(d.inventory(self.target), self.before)
        self.run_delivery()

    def test_scope_binding_file_flush_is_retried_before_activation(self):
        real_fsync = os.fsync
        def fail_binding(fd):
            link = os.readlink(f'/proc/self/fd/{fd}')
            if link.endswith('/.scope.json'): raise OSError('synthetic binding flush failure')
            real_fsync(fd)
        with patch.object(d.os, 'fsync', side_effect=fail_binding):
            for retry in range(2):
                with self.assertRaises(OSError): self.run_delivery()
                self.assertEqual(d.inventory(self.target), self.before)
        self.run_delivery()

    def test_retention_cap_preserves_retry_rollback_and_scope(self):
        descriptors = []
        for number in range(8):
            self.desc['expected_files'] = d.inventory(self.target); self.write_descriptor()
            descriptors.append(dict(self.desc))
            self.files['README.md'] = f'synthetic generation {number}'.encode(); self.make_package()
            self.run_delivery()
        self.desc['expected_files'] = d.inventory(self.target); self.write_descriptor()
        self.assertEqual(self.run_delivery()['state'], 'unchanged')
        self.assertEqual(len(list(self.state.glob('[0-9a-f]*.json'))), 8)
        retained_package = self.package.read_bytes(); retained_digest = self.digest
        self.files['README.md'] = b'synthetic ninth generation'; self.make_package()
        with self.assertRaisesRegex(d.Refused, 'eight retained generations'): self.run_delivery()
        # Retry the exact retained artifact; rebuilding ZIP metadata changes its identity.
        self.package.write_bytes(retained_package); self.digest = retained_digest
        self.desc = descriptors[-1]; self.write_descriptor()
        self.run_delivery(); self.run_delivery(undo=True)
        self.desc['locks'] = [str(self.root / 'different.lock')]
        Path(self.desc['locks'][0]).touch(mode=0o600); self.write_descriptor()
        with self.assertRaisesRegex(d.Refused, 'scope or host locks'): self.run_delivery(preview=True)


    def test_orphan_and_malformed_retained_receipts_are_preserved_and_refused(self):
        self.run_delivery()
        orphan = self.state / ('f' * 64 + '.retained'); orphan.mkdir(mode=0o700)
        with self.assertRaisesRegex(d.Refused, 'orphan retained generation'): self.run_delivery(preview=True)
        self.assertTrue(orphan.exists()); orphan.rmdir()
        malformed = self.state / ('f' * 64 + '.json'); malformed.write_text('{}'); malformed.chmod(0o600)
        with self.assertRaisesRegex(d.Refused, 'invalid retained generation receipt'): self.run_delivery(preview=True)
        self.assertEqual(malformed.read_text(), '{}')

    def test_new_rollback_ancestors_are_durable_before_exchange(self):
        self.state = self.root / 'new-private-parent' / 'receipts'
        self.desc['receipt_directory'] = str(self.state); self.write_descriptor()
        flushed = []; real_sync = d.sync_dir; real_exchange = d.exchange
        def sync(path):
            real_sync(path); flushed.append(Path(path))
        def exchange(left, right):
            self.assertIn(self.root, flushed)
            self.assertIn(self.state.parent, flushed)
            self.assertIn(self.state, flushed)
            real_exchange(left, right)
        with patch.object(d, 'sync_dir', side_effect=sync), patch.object(d, 'exchange', side_effect=exchange):
            self.run_delivery()

    def test_update_retry_and_rollback(self):
        self.assertEqual(self.run_delivery(preview=True)['state'], 'preview'); self.assertFalse(self.state.exists())
        result = self.run_delivery(); self.assertEqual(result['state'], 'installed'); self.assertFalse(result['loaded'])
        self.assertEqual(self.run_delivery()['state'], 'unchanged')
        self.assertEqual(self.run_delivery(undo=True, preview=True)['action'], 'rollback')
        self.assertEqual(self.run_delivery(undo=True)['state'], 'rolled_back')
        self.assertEqual(self.run_delivery(undo=True)['state'], 'rolled_back')
        self.assertEqual(d.inventory(self.target), self.before)
        with self.assertRaises(d.Refused): self.run_delivery(preview=True)
        self.assertEqual(self.other.read_bytes(), b'unrelated data')

    def test_target_local_locks_and_packages_are_refused(self):
        self.desc['locks'] = [str(self.target / 'README.md')]; self.write_descriptor()
        for preview in (True, False):
            with self.assertRaisesRegex(d.Refused, 'outside the replaced target'): self.run_delivery(preview=preview)
        self.desc['locks'] = [str(self.lock)]; self.write_descriptor()
        for preview in (True, False):
            with self.assertRaisesRegex(d.Refused, 'outside the replaced target'):
                d.deliver(self.descriptor, self.target / 'README.md', self.digest, SOURCE, preview=preview, stopped=lambda: None)
        self.assertEqual(d.inventory(self.target), self.before); self.assertFalse(self.state.exists())

    def test_unusable_receipt_directory_is_refused_in_preview(self):
        self.state.write_bytes(b'preserved'); self.state.chmod(0o600)
        for preview in (True, False):
            with self.assertRaises(d.Refused): self.run_delivery(preview=preview)
        self.assertEqual(self.state.read_bytes(), b'preserved')
        self.state.unlink(); self.state.mkdir(mode=0o500)
        for preview in (True, False):
            with self.assertRaises(d.Refused): self.run_delivery(preview=preview)
        self.state.chmod(0o700)
        self.assertEqual(d.inventory(self.target), self.before)

    def test_retry_flushes_preexisting_receipt_ancestors(self):
        self.state = self.root / 'new-private-parent' / 'receipts'
        self.desc['receipt_directory'] = str(self.state); self.write_descriptor()
        real_sync = d.sync_dir
        def interrupted(path):
            if Path(path) == self.state.parent: raise OSError('synthetic parent flush interruption')
            real_sync(path)
        with patch.object(d, 'sync_dir', side_effect=interrupted):
            with self.assertRaises(OSError): self.run_delivery()
        self.assertTrue(self.state.exists())
        self.assertEqual(d.inventory(self.target), self.before)
        flushed = []; real_exchange = d.exchange
        def sync(path):
            real_sync(path); flushed.append(Path(path))
        def exchange(left, right):
            for parent in [self.state, *self.state.parents]: self.assertIn(parent, flushed)
            return real_exchange(left, right)
        with patch.object(d, 'sync_dir', side_effect=sync), patch.object(d, 'exchange', side_effect=exchange):
            self.run_delivery()

    def test_already_current_is_nonmutating(self):
        for name, data in self.files.items(): (self.target / name).write_bytes(data)
        command = list(COMMAND); command[0] = self.desc['java_executable']
        (self.target / 'command.txt').write_bytes(d.json_bytes(command))
        self.desc['expected_files'] = d.inventory(self.target); self.write_descriptor()
        self.assertEqual(self.run_delivery()['state'], 'unchanged')
        self.assertFalse(self.state.exists())
        self.assertEqual(d.inventory(self.target), self.desc['expected_files'])

    def test_foreign_files_symlinks_and_lock(self):
        foreign = self.target / 'journal.json'; foreign.write_text('private fixture')
        with self.assertRaises(d.Refused): self.run_delivery()
        self.assertEqual(foreign.read_text(), 'private fixture'); foreign.unlink()
        (self.target / 'unknown-directory').mkdir()
        with self.assertRaises(d.Refused): self.run_delivery()
        (self.target / 'unknown-directory').rmdir()
        (self.target / 'README.md').unlink(); (self.target / 'README.md').symlink_to(self.other)
        with self.assertRaises(d.Refused): self.run_delivery()
        (self.target / 'README.md').unlink(); (self.target / 'README.md').write_bytes(b'old README.md')
        import fcntl
        with self.lock.open('r+') as file:
            fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError): self.run_delivery()
        self.assertEqual(d.inventory(self.target), self.before)

    def test_wrong_host_older_version_and_collision(self):
        self.host.write_bytes(b'changed')
        with self.assertRaises(d.Refused): self.run_delivery()
        self.host.write_bytes(b'synthetic verified host')
        self.desc['expected_version'] = '0.2.0'; self.write_descriptor()
        with self.assertRaises(d.Refused): self.run_delivery()
        self.desc['expected_version'] = VERSION; self.desc['receipt_directory'] = str(self.target / 'state'); self.write_descriptor()
        with self.assertRaises(d.Refused): self.run_delivery()

    def test_existing_readable_empty_host_lock_is_preserved(self):
        self.lock.chmod(0o644)
        self.run_delivery(preview=True)
        self.assertEqual(self.lock.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.lock.read_bytes(), b'')
        self.lock.chmod(0o666)
        with self.assertRaises(d.Refused): self.run_delivery(preview=True)
        self.lock.chmod(0o600); self.lock.write_bytes(b'unexpected lock data')
        with self.assertRaises(d.Refused): self.run_delivery(preview=True)
        self.assertFalse(self.state.exists())

    def test_busy_and_drifted_activation(self):
        def busy(): raise d.Refused('synthetic active host')
        with self.assertRaises(d.Refused): d.deliver(self.descriptor, self.package, self.digest, SOURCE, stopped=busy)
        calls = 0
        def drift():
            nonlocal calls
            calls += 1
            if calls == 2: (self.target / 'README.md').write_bytes(b'other writer')
        with self.assertRaises(d.Refused): d.deliver(self.descriptor, self.package, self.digest, SOURCE, stopped=drift)
        self.assertEqual((self.target / 'README.md').read_bytes(), b'other writer')

    def test_crash_after_each_exchange_resumes(self):
        original = d.exchange
        def after_exchange(left, right):
            original(left, right); raise OSError('synthetic crash after atomic exchange')
        with patch.object(d, 'exchange', side_effect=after_exchange), self.assertRaises(OSError): self.run_delivery()
        self.assertEqual(self.run_delivery()['state'], 'unchanged')
        with patch.object(d, 'exchange', side_effect=after_exchange), self.assertRaises(OSError): self.run_delivery(undo=True)
        self.assertEqual(self.run_delivery(undo=True)['state'], 'rolled_back')
        self.assertEqual(d.inventory(self.target), self.before)

    def test_retained_tamper_refuses_real_and_preview(self):
        result = self.run_delivery(); receipt = json.loads(Path(result['receipt']).read_text())
        slot = self.state / (receipt['identity'] + '.retained')
        (slot / 'README.md').write_bytes(b'tampered')
        current = d.inventory(self.target)
        for preview in [True, False]:
            with self.assertRaises(d.Refused): self.run_delivery(undo=True, preview=preview)
        self.assertEqual(d.inventory(self.target), current)

    def test_interrupted_named_writes_resume_without_deleting_evidence(self):
        real = d.write_pending
        # Each interruption leaves a real partial file, as an abrupt process exit would.
        for category in ('scope', 'initial', 'member', 'final'):
            hit = []
            def interrupt(path, data, temporary):
                is_scope = path.name == '.scope.json'
                is_receipt = path.suffix == '.json' and not is_scope
                value = json.loads(data) if is_receipt else {}
                matches = ((category == 'scope' and is_scope) or
                    (category == 'initial' and is_receipt and value.get('state') in ('prepared', 'preparing')) or
                    (category == 'member' and '.member-' in temporary.name) or
                    (category == 'final' and is_receipt and value.get('state') in ('original_retained', 'installed')))
                if matches and not hit:
                    temporary.write_bytes(data[:max(1, len(data)//2)]); temporary.chmod(0o600)
                    hit.append(temporary)
                    raise OSError('synthetic abrupt write interruption')
                return real(path, data, temporary)
            with patch.object(d, 'write_pending', side_effect=interrupt):
                with self.assertRaises(OSError): self.run_delivery()
            self.assertTrue(hit[0].exists())
            # Continue until the next selected boundary; each category uses the same operation.
            if category == 'final': self.run_delivery()
        self.assertFalse(list(self.state.glob('*.pending')))

    def test_foreign_pending_bytes_are_preserved(self):
        self.state.mkdir(mode=0o700)
        name = d.scope_temporary(self.desc)
        pending = self.state / name; pending.write_bytes(b'foreign bytes'); pending.chmod(0o600)
        with self.assertRaises(d.Refused): self.run_delivery()
        self.assertEqual(pending.read_bytes(), b'foreign bytes')
        self.assertEqual(d.inventory(self.target), self.before)

    def test_noncurrent_retained_inputs_cannot_change_behind_identity(self):
        result = self.run_delivery(); path = Path(result['receipt'])
        original = json.loads(path.read_text())
        for field in ('source_revision', 'package_sha256'):
            changed = json.loads(json.dumps(original))
            changed['manifest'][field] = 'e' * (40 if field == 'source_revision' else 64)
            path.write_bytes(d.json_bytes(changed))
            with self.assertRaisesRegex(d.Refused, 'identity differs'):
                d.retention_preflight(self.state, self.desc, 'f' * 64)
        path.write_bytes(d.json_bytes(original))

    def test_pending_hardlink_does_not_modify_unrelated_file(self):
        self.state.mkdir(mode=0o700)
        unrelated = self.root / 'unrelated-empty'; unrelated.touch(mode=0o600)
        pending = self.state / d.scope_temporary(self.desc); os.link(unrelated, pending)
        with self.assertRaisesRegex(d.Refused, 'hardlink refused'): self.run_delivery()
        self.assertEqual(unrelated.read_bytes(), b'')
        self.assertEqual(pending.stat().st_ino, unrelated.stat().st_ino)
    def test_java_version_and_wine_path_are_unambiguous(self):
        for version in ['JAVA_VERSION="21.0.11"\nJAVA_VERSION=17\n', 'JAVA_VERSION="210"\n',
                        'JAVA_VERSION="21.0.11"\n JAVA_VERSION = "17"\n']:
            self.release.write_text(version)
            desc = dict(self.desc, java_release_sha256=d.sha(self.release.read_bytes()))
            with self.assertRaises(d.Refused): d.verify_java_runtime(desc, self.host)
        drive = self.root / 'wine/drive_c'; binary = drive / 'JRE/bin/java.exe'
        binary.parent.mkdir(parents=True); release = binary.parent.parent / 'release'
        pe = bytearray(128); pe[:2] = b'MZ'; struct.pack_into('<I', pe, 60, 64); pe[64:68] = b'PE\0\0'
        binary.write_bytes(pe); binary.chmod(0o700)
        release.write_text('JAVA_VERSION="21.0.11+9"\n'); release.chmod(0o600)
        desc = dict(self.desc, java_executable=r'C:\JRE\bin\java.exe', java_sha256=d.sha(pe),
                    java_release_sha256=d.sha(release.read_bytes()))
        host = drive / 'host/host.jar'
        self.assertEqual(d.verify_java_runtime(desc, host), desc['java_executable'])
        mixed = dict(desc, java_executable=r'c:\jre\BIN\JAVA.EXE')
        self.assertEqual(d.verify_java_runtime(mixed, host), mixed['java_executable'])
        for command in [r'C:\JRE\.\bin\java.exe', r'C:\JRE\\bin\java.exe', r'C:\JRE.\bin\java.exe',
                        r'C:\JRE\bin\java.exe:stream', r'C:\CON\bin\java.exe', r'C:JRE\bin\java.exe']:
            with self.assertRaises(d.Refused): d.verify_java_runtime(dict(desc, java_executable=command), host)
        import contextlib
        from unittest.mock import patch as patch_scan
        real_scan = d.os.scandir
        @contextlib.contextmanager
        def bounded_scan(path):
            if path == drive:
                class Entry:
                    def __init__(self, name): self.name = name
                def entries():
                    yield Entry('JRE'); yield Entry('jre')
                    raise AssertionError('scan advanced beyond the second match')
                yield entries()
            else:
                with real_scan(path) as children: yield children
        with patch_scan.object(d.os, 'scandir', bounded_scan):
            with self.assertRaisesRegex(d.Refused, 'ambiguous'): d.verify_java_runtime(desc, host)
        (drive / 'jre').mkdir()
        with self.assertRaisesRegex(d.Refused, 'ambiguous'): d.verify_java_runtime(desc, host)
        (drive / 'jre').rmdir()
        binary.write_bytes(b'MZinvalid'); desc['java_sha256'] = d.sha(binary.read_bytes())
        with self.assertRaisesRegex(d.Refused, 'PE header'): d.verify_java_runtime(desc, host)

    def test_unattested_retained_launcher_is_refused_before_install(self):
        launcher = list(d.LAUNCH if hasattr(d, 'LAUNCH') else COMMAND); launcher[0] = 'old-java'
        (self.target / 'command.txt').write_bytes(d.json_bytes(launcher))
        self.desc['expected_files'] = d.inventory(self.target); self.write_descriptor()
        with self.assertRaisesRegex(d.Refused, 'managed launcher'): self.run_delivery()
        self.assertFalse(self.state.exists())
        self.assertEqual(d.inventory(self.target), self.desc['expected_files'])

    def test_attested_windows_release_line_endings_are_accepted(self):
        self.release.write_bytes(b'JAVA_VERSION="21.0.11"\r\n')
        self.desc['java_release_sha256'] = d.sha(self.release.read_bytes()); self.write_descriptor()
        self.run_delivery()

    def test_atomic_runtime_replacement_after_hash_is_refused(self):
        for path in (self.java, self.release):
            with self.subTest(path=path.name):
                original = path.read_bytes(); original_mode = path.stat().st_mode & 0o777
                real_hash = d.sha; replaced = False
                def replace_after_hash(data):
                    nonlocal replaced
                    value = real_hash(data)
                    if data == original and not replaced:
                        replaced = True
                        temporary = path.with_name(path.name + '.replacement')
                        temporary.write_bytes(original + b'\n'); temporary.chmod(original_mode)
                        os.replace(temporary, path)
                    return value
                with patch.object(d, 'sha', replace_after_hash):
                    with self.assertRaisesRegex(d.Refused, 'runtime path changed'): self.run_delivery()
                self.assertTrue(replaced); self.assertFalse(self.state.exists())
                path.write_bytes(original); path.chmod(original_mode)

    def test_java_runtime_mapping_and_attestation_are_required(self):
        original = dict(self.desc)
        for field, value in [("java_executable", "java21"), ("java_executable", str(self.root / "missing/java")),
                             ("java_sha256", "e" * 64), ("java_release_sha256", "e" * 64)]:
            self.desc = dict(original, **{field: value}); self.write_descriptor()
            with self.assertRaises((d.Refused, OSError)): self.run_delivery()
            self.assertFalse(self.state.exists())
        self.desc = original
        self.release.write_text('JAVA_VERSION="17.0.16"\n')
        self.desc["java_release_sha256"] = d.sha(self.release.read_bytes()); self.write_descriptor()
        with self.assertRaisesRegex(d.Refused, "not Java 21"): self.run_delivery()
        self.assertFalse(self.state.exists())

    def test_generated_receipt_budget_is_checked_before_installation(self):
        self.desc['extra'] = 'x' * (32000 - len(d.json_bytes(self.desc)))
        self.write_descriptor()
        self.assertLess(self.descriptor.stat().st_size, 32768)
        with self.assertRaisesRegex(d.Refused, 'generated receipt size'): self.run_delivery()
        self.assertFalse(self.state.exists())
        self.assertEqual(d.inventory(self.target), self.before)



if __name__ == '__main__': unittest.main()
