"""Regression coverage for publication identities; no account or network access."""
import importlib.util
from pathlib import Path
import unittest
import contextlib
import io
import subprocess
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('check_public', Path(__file__).with_name('check-public.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class CommitIdentityTest(unittest.TestCase):
    def test_replacement_does_not_hide_original_published_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args],
                                               stderr=subprocess.PIPE)
            git('init')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            (root / 'source.txt').write_text('synthetic source')
            git('add', '.')
            git('commit', '-m', 'Original private identity')
            original = git('rev-parse', 'HEAD').decode().strip()
            git('config', 'user.email', '1+fixture@users.noreply.github.com')
            tree = git('rev-parse', 'HEAD^{tree}').decode().strip()
            replacement = git('commit-tree', tree, '-m', 'Public replacement').decode().strip()
            git('replace', original, replacement)
            self.assertIn(b'users.noreply.github.com', git('show', '-s', '--format=%ae', original))
            before = git('for-each-ref')
            output = io.StringIO()
            with patch.object(audit, 'root', root), contextlib.redirect_stdout(output):
                with self.assertRaises(SystemExit) as result:
                    audit.main()
            self.assertEqual(1, result.exception.code)
            self.assertIn(original + ' non-public commit email', output.getvalue())
            self.assertNotIn('fixture@example.invalid', output.getvalue())
            self.assertEqual(before, git('for-each-ref'))

    def test_author_and_committer_boundaries(self):
        public = ('Contributor', '123+contributor@users.noreply.github.com')
        github = ('GitHub', 'noreply@github.com')
        private = ('Contributor', 'contributor@example.invalid')
        for author, committer, expected in (
            (public, public, True),
            (public, github, True),
            (github, github, False),
            (private, public, False),
            (private, github, False),
            (public, private, False),
            (public, ('Other', github[1]), False),
            (public, ('GitHub', private[1]), False),
        ):
            with self.subTest(author=author, committer=committer):
                self.assertEqual(expected, audit.public_commit_identity((*author, *committer)))


if __name__ == '__main__':
    unittest.main()
