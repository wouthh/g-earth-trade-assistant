"""Committed-source provenance checks use disposable repositories only."""
import subprocess
import tempfile
from pathlib import Path
import unittest
import build


class BuildIdentityTest(unittest.TestCase):
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
