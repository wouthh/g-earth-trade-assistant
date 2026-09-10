"""Committed-source provenance checks use disposable repositories only."""
import subprocess
import tempfile
from pathlib import Path
import unittest
import build


class BuildIdentityTest(unittest.TestCase):
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
            (root / '.gitignore').write_text('*.log\n*.jar\n.build/\ntarget/\n')
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
                         'build-support/private.log', 'packaging/private.log'):
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
