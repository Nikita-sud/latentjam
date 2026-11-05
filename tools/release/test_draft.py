# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""tools/release/draft.sh, the release workflow's draft step, on trees and repositories made here (v0.9.4's draft
failed on the IPA's directory and lost the notes' headings; neither step ran in a dry run)."""
import hashlib
import importlib.util
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
DRAFT = HERE / "draft.sh"
spec = importlib.util.spec_from_file_location("release_tool", HERE / "release.py")
release_tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_tool)


def run(*args, cwd=None, env=None, check=True):
    return subprocess.run(["bash", str(DRAFT), *args], cwd=cwd, env=env, capture_output=True, text=True, check=check)


class AssetsTest(unittest.TestCase):
    def test_the_ipa_one_directory_deeper_is_flattened_and_summed(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, android = Path(tmp, "assets"), Path(tmp, "android")
            (assets / "0.9.4").mkdir(parents=True)
            (assets / "0.9.4" / "LatentJam-v0.9.4-unsigned.ipa").write_bytes(b"ipa")
            (assets / "LatentJam-v0.9.4-arm64.apk").write_bytes(b"apk")
            (android / "0.9.4").mkdir(parents=True)
            (android / "0.9.4" / "LatentJam-v0.9.4-sbom.cdx.json").write_bytes(b"{}")
            run("assets", str(assets), str(android))
            names = sorted(p.name for p in assets.iterdir())
            self.assertEqual(["LatentJam-v0.9.4-arm64.apk", "LatentJam-v0.9.4-sbom.cdx.json",
                              "LatentJam-v0.9.4-unsigned.ipa", "SHA256SUMS"], names)
            self.assertTrue(all(p.is_file() for p in assets.iterdir()))
            sums = dict(reversed(line.split()) for line in (assets / "SHA256SUMS").read_text().splitlines())
            self.assertEqual(hashlib.sha256(b"ipa").hexdigest(), sums["LatentJam-v0.9.4-unsigned.ipa"])
            self.assertEqual(3, len(sums))

    def test_assets_without_an_apk_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets, android = Path(tmp, "assets"), Path(tmp, "android")
            assets.mkdir()
            android.mkdir()
            (assets / "LatentJam-v0.9.4-unsigned.ipa").write_bytes(b"ipa")
            self.assertNotEqual(0, run("assets", str(assets), str(android), check=False).returncode)


class NotesTest(unittest.TestCase):
    def repo(self, tmp):
        git = lambda *a: subprocess.run(["git", *a], cwd=tmp, check=True, capture_output=True)  # noqa: E731
        git("init", "-q")
        git("config", "user.email", "t@t")
        git("config", "user.name", "t")
        changelog = Path(tmp, "fastlane/metadata/android/en-US/changelogs/45.txt")
        changelog.parent.mkdir(parents=True)
        changelog.write_text("Many fixes.\n")
        git("add", ".")
        git("commit", "-qm", "c")
        message = Path(tmp, "tag-message.md")
        message.write_text("LatentJam 0.9.4\n\nA summary.\n\n### New\n- CSV import\n")
        return git, message

    def test_the_owners_tag_command_keeps_the_headings(self):
        with tempfile.TemporaryDirectory() as tmp:
            git, message = self.repo(tmp)
            command = release_tool.tag_command("0.9.4", message, "HEAD").split()
            self.assertIn("--cleanup=verbatim", command)
            git(*command[1:])
            out = run("notes", "v0.9.4", "45", "success", cwd=tmp)
            self.assertIn("### New", out.stdout)
            self.assertIn("### Changelog\nMany fixes.", out.stdout)
            self.assertNotIn("warning", out.stderr)

    def test_a_tag_that_lost_its_headings_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            git, message = self.repo(tmp)
            git("tag", "-a", "v0.9.4", "-F", str(message), "HEAD")  # default cleanup, as v0.9.4 was made
            out = run("notes", "v0.9.4", "45", "success", cwd=tmp)
            self.assertNotIn("### New", out.stdout)
            self.assertIn("::warning::", out.stderr)


class PublishTest(unittest.TestCase):
    def publish(self, state):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp, "gh.log")
            fake = Path(tmp, "bin", "gh")
            fake.parent.mkdir()
            view = "exit 1" if state is None else f"echo {state}"
            fake.write_text(f'#!/bin/sh\necho "$*" >> {log}\ncase "$1 $2" in "release view") {view};; esac\n')
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            assets = Path(tmp, "assets")
            assets.mkdir()
            (assets / "a.apk").write_bytes(b"a")
            notes = Path(tmp, "notes.md")
            notes.write_text("n")
            env = {**os.environ, "PATH": f"{fake.parent}:{os.environ['PATH']}"}
            result = run("publish", "v0.9.4", "0.9.4", str(assets), str(notes), env=env, check=False)
            return result.returncode, log.read_text().splitlines()

    def test_no_release_yet_creates_the_draft(self):
        code, calls = self.publish(None)
        self.assertEqual(0, code)
        self.assertTrue(any(c.startswith("release create v0.9.4 --draft") for c in calls))

    def test_a_draft_left_by_a_failed_run_is_completed(self):
        code, calls = self.publish("true")
        self.assertEqual(0, code)
        self.assertTrue(any(c.startswith("release upload v0.9.4 --clobber") for c in calls))
        self.assertTrue(any(c.startswith("release edit v0.9.4 --notes-file") for c in calls))
        self.assertFalse(any(c.startswith("release create") for c in calls))

    def test_a_published_release_is_never_touched(self):
        code, calls = self.publish("false")
        self.assertNotEqual(0, code)
        self.assertFalse(any(c.startswith(("release create", "release upload", "release edit")) for c in calls))


if __name__ == "__main__":
    unittest.main()
