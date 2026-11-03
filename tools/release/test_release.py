# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""release.py's checks that need no network, phone or GitHub."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parent / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class VersionCodeTest(unittest.TestCase):
    def test_split_and_base_codes_are_compared_with_their_own_kind(self):
        # 0.9.4 = base 45: splits 451..454. A phone test build 432 (split) and a debug build 44 (base) are fine.
        self.assertEqual(release.version_code_problems(45, {"a.apk": 432, "b.apk": 44}), [])
        self.assertEqual(len(release.version_code_problems(45, {"phone": 451})), 1)
        self.assertEqual(len(release.version_code_problems(45, {"debug.apk": 45})), 1)
        self.assertEqual(len(release.version_code_problems(45, {}, play_code=45)), 1)


class ChangelogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        release.CHANGELOGS = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_base_file_is_copied_and_checked(self):
        Path(self.tmp.name, "45.txt").write_text("Fixes.\n")
        self.assertTrue(release.changelog_problems(45))          # ABI files missing
        release.sync_changelogs(45)
        self.assertEqual(release.changelog_problems(45), [])
        self.assertEqual(Path(self.tmp.name, "453.txt").read_text(), "Fixes.\n")

    def test_todo_and_length(self):
        Path(self.tmp.name, "45.txt").write_text("TODO\n")
        release.sync_changelogs(45)
        self.assertTrue(release.changelog_problems(45))
        Path(self.tmp.name, "45.txt").write_text("x" * 501)
        release.sync_changelogs(45)
        self.assertTrue(release.changelog_problems(45))


class VersionTest(unittest.TestCase):
    def test_reads_the_gradle_file(self):
        self.assertEqual(release.app_version('        versionCode = 44\n        versionName = "0.9.3"\n'), ("0.9.3", 44))


if __name__ == "__main__":
    unittest.main()
