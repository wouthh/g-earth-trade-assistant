"""Committed-source provenance checks use disposable repositories only."""
import subprocess
import tempfile
from pathlib import Path
import os
import py_compile
import sys
import unittest
import build


class BuildIdentityTest(unittest.TestCase):
    def test_replaced_commit_cannot_attest_different_worktree_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args],
                                               stderr=subprocess.PIPE)
            git('init')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', '1+fixture@users.noreply.github.com')
            path = root / 'source.txt'
            path.write_text('original committed bytes')
            git('add', '.')
            git('commit', '-m', 'Original')
            original = git('rev-parse', 'HEAD').decode().strip()
            path.write_text('replacement committed bytes')
            git('add', '.')
            git('commit', '-m', 'Replacement')
            replacement = git('rev-parse', 'HEAD').decode().strip()
            # Only this disposable repository is redirected; preserve its index/tree.
            git('update-ref', 'HEAD', original)
            git('replace', original, replacement)
            self.assertEqual(b'', git('status', '--porcelain'))
            index = (root / '.git/index').read_bytes()
            refs = git('for-each-ref')
            with self.assertRaisesRegex(ValueError, 'Commit the intended source'):
                build.clean_revision(root)
            self.assertEqual(index, (root / '.git/index').read_bytes())
            self.assertEqual(refs, git('for-each-ref'))
            self.assertEqual('replacement committed bytes', path.read_text())

    @unittest.skipUnless(os.name == 'posix', 'fixture hook uses a POSIX shell')
    def test_stale_fsmonitor_cannot_hide_modified_source_or_execute_in_preflight(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '--no-optional-locks', '-C',
                                                str(root), *args], stderr=subprocess.PIPE)
            git('init')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', '1+fixture@users.noreply.github.com')
            path = root / 'source.txt'
            path.write_text('committed source')
            git('add', 'source.txt')
            git('commit', '-m', 'Fixture committed source')
            hook = root / '.git' / 'stale-fsmonitor'
            hook.write_text('#!/bin/sh\n: > "${0}.called"\nprintf "fixture-token\\000"\n')
            hook.chmod(0o700)
            git('config', 'core.fsmonitor', str(hook))
            git('config', 'core.fsmonitorHookVersion', '2')
            git('update-index', '--fsmonitor')
            git('update-index', '--fsmonitor-valid', '--', 'source.txt')
            path.write_text('modified source concealed by stale monitor')
            self.assertEqual(b'H source.txt\0', git('ls-files', '-v', '-z'))
            self.assertEqual(b'', git('status', '--porcelain', '--untracked-files=all'))
            marker = Path(str(hook) + '.called')
            self.assertTrue(marker.exists())
            marker.unlink()  # The fixture's own hook marker.
            index = (root / '.git' / 'index').read_bytes()
            config = (root / '.git' / 'config').read_bytes()
            with self.assertRaisesRegex(ValueError, 'Commit the intended source'):
                build.clean_revision(root)
            self.assertFalse(marker.exists())
            self.assertEqual(index, (root / '.git' / 'index').read_bytes())
            self.assertEqual(config, (root / '.git' / 'config').read_bytes())
            self.assertEqual('modified source concealed by stale monitor', path.read_text())

    def test_cli_isolates_ignored_source_and_legacy_bytecode_before_imports(self):
        for suffix in ('.py', '.pyc'):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                scripts = root / 'scripts'
                scripts.mkdir()
                cache = root / '.build'
                cache.mkdir()
                # A corrupt cached archive stops bootstrap before any network/build action.
                (cache / 'gearth-source.tar.gz').write_bytes(b'synthetic invalid archive')
                for name in ('build.py', 'bootstrap.py'):
                    (scripts / name).write_bytes((Path(__file__).parent / name).read_bytes())
                (root / '.gitignore').write_text(
                    '.build/\nscripts/subprocess.py\n*.pyc\n__pycache__/\n')
                def git(*args):
                    return subprocess.check_output(['git', '-C', str(root), *args],
                                                   stderr=subprocess.PIPE)
                git('init')
                git('config', 'user.name', 'Fixture')
                git('config', 'user.email', '1+fixture@users.noreply.github.com')
                git('add', '.')
                git('commit', '-m', 'Fixture trusted scripts')
                marker = cache / 'payload-executed'
                payload = ('open(' + repr(str(marker)) + ', "w").write("executed")\n'
                           'raise RuntimeError("ignored module executed")\n')
                shadow = scripts / ('subprocess' + suffix)
                if suffix == '.py':
                    shadow.write_text(payload)
                else:
                    source = cache / 'payload-source.py'
                    source.write_text(payload)
                    py_compile.compile(str(source), cfile=str(shadow), doraise=True)
                original = shadow.read_bytes()
                self.assertEqual(b'', git('status', '--porcelain', '--untracked-files=all'))
                for script, expected in (
                        ('build.py', 'Ignored build inputs'),
                        ('bootstrap.py', 'Cached API archive checksum mismatch')):
                    with self.subTest(script=script):
                        marker.unlink(missing_ok=True)  # This test's own payload marker.
                        # Exercise the ordinary caller command, including a hostile search path.
                        env = dict(os.environ, PYTHONPATH=str(scripts),
                                   PYTHONDONTWRITEBYTECODE='1')
                        result = subprocess.run([sys.executable, str(scripts / script)],
                                                cwd=root, env=env, text=True,
                                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                                timeout=15)
                        self.assertNotEqual(0, result.returncode)
                        self.assertFalse(marker.exists(), result.stderr)
                        self.assertIn(expected, result.stderr)
                        self.assertEqual(original, shadow.read_bytes())

    def test_hidden_tracked_inputs_cannot_attest_a_clean_index(self):
        for flag in ('--skip-worktree', '--assume-unchanged'):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                def git(*args):
                    return subprocess.check_output(['git', '-C', str(root), *args],
                                                   stderr=subprocess.PIPE)
                git('init')
                git('config', 'user.name', 'Fixture')
                git('config', 'user.email', '1+fixture@users.noreply.github.com')
                name = 'src/main/resources/META-INF/tradeassistant-build.properties'
                path = root / name
                path.parent.mkdir(parents=True)
                path.write_text('committed provenance input')
                git('add', name)
                git('commit', '-m', 'Fixture committed input')
                git('update-index', flag, name)
                path.unlink()  # This test owns the temporary checkout.
                self.assertEqual(b'', git('status', '--porcelain', '--untracked-files=all'))
                index_before = git('ls-files', '-v', '-z')
                with self.assertRaisesRegex(ValueError, 'Hidden tracked inputs'):
                    build.clean_revision(root)
                self.assertFalse(path.exists())
                self.assertEqual(index_before, git('ls-files', '-v', '-z'))

    def test_ignored_maven_inputs_are_preserved_and_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args],
                                               stderr=subprocess.PIPE)
            git('init')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', '1+fixture@users.noreply.github.com')
            (root / '.gitignore').write_text('*.log\n*.jar\n*.pyc\n.build/\ntarget/\n')
            git('add', '.gitignore')
            git('commit', '-m', 'Fixture ignore rules')
            expected = git('rev-parse', 'HEAD').decode().strip()
            for name in ('.build/dependency.jar', 'target/previous.jar'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('generated cache or output')
            self.assertEqual(expected, build.clean_revision(root))
            for name in ('src/main/resources/private.log', 'src/main/resources/payload.jar',
                         'src/test/resources/private.log', '.mvn/private.log',
                         'build-support/private.log', 'packaging/private.log',
                         'scripts/private.log', 'scripts/subprocess.pyc',
                         'scripts/__pycache__/build.cpython-312.pyc'):
                with self.subTest(name=name):
                    path = root / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('synthetic private input')
                    self.assertEqual(b'', git('status', '--porcelain', '--untracked-files=all'))
                    with self.assertRaisesRegex(ValueError, 'Ignored build inputs'):
                        build.clean_revision(root)
                    self.assertEqual('synthetic private input', path.read_text())
                    path.unlink()  # Only this test's own fixture.

    def test_only_clean_committed_source_is_attested(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(['git', '-C', str(root), *args], check=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            git('init')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', '1+fixture@users.noreply.github.com')
            (root / 'source.txt').write_text('committed')
            git('add', 'source.txt')
            git('commit', '-m', 'Fixture')
            expected = git('rev-parse', 'HEAD').stdout.decode().strip()
            self.assertEqual(expected, build.clean_revision(root))
            (root / 'source.txt').write_text('modified')
            with self.assertRaises(ValueError): build.clean_revision(root)
            git('add', 'source.txt')
            with self.assertRaises(ValueError): build.clean_revision(root)
            git('commit', '-m', 'Fixture change')
            (root / 'untracked.txt').write_text('unreviewed')
            with self.assertRaises(ValueError): build.clean_revision(root)


if __name__ == '__main__': unittest.main()
