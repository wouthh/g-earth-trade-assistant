"""Committed-source provenance checks use disposable repositories only."""
import subprocess
import tempfile
from pathlib import Path
import unittest
import build


class BuildIdentityTest(unittest.TestCase):
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
