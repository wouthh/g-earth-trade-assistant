"""Regression coverage for publication identities; no account or network access."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('check_public', Path(__file__).with_name('check-public.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class CommitIdentityTest(unittest.TestCase):
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
