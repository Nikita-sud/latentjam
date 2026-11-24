# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""The F-Droid prebuild lines are frozen into every future build block: they must keep working with build.sh."""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def frozen_prebuild_lines():
    return re.findall(r"^\s+prebuild: (.+)$", (HERE / "fdroid/recipe.yml").read_text(), re.M)


def run_line(line, home):
    # fdroidserver substitutes $$name$$ with absolute paths and runs the line with bash -e in the recipe's subdir.
    line = line.replace("$$onnxruntime-latentjam$$", "/build/srclib/onnxruntime-latentjam")
    line = line.replace("$$NDK$$", "/sdk/ndk/28.2.13676358").replace("$$SDK$$", "/sdk")
    env = dict(os.environ, LATENTJAM_DRY_RUN="1", HOME=str(home))
    return subprocess.run(["bash", "-e", "-c", line], cwd=ROOT / "androidApp", env=env, capture_output=True, text=True)


class FrozenPrebuildTest(unittest.TestCase):
    def test_four_lines_one_per_abi(self):
        lines = frozen_prebuild_lines()
        self.assertEqual([re.search(r"fdroid-prebuild (\S+)", l).group(1) for l in lines],
                         ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"])

    def test_every_frozen_line_runs(self):
        for line in frozen_prebuild_lines():
            with self.subTest(line=line):
                result = run_line(line, "/home/vagrant")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("sdkmanager cmake;3.22.1", result.stderr)
                arm64 = "arm64-v8a" in line
                self.assertEqual("ort_source_build.sh /build/srclib/onnxruntime-latentjam /sdk/ndk/28.2.13676358 /sdk"
                                 in result.stderr, arm64)
                if arm64:
                    # The ORT work dir lives on disk outside the scanned tree, and is removed afterwards.
                    self.assertIn("rm -rf /home/vagrant/.cache/latentjam-ort", result.stderr)

    def test_unknown_abi_and_wrong_arguments_fail(self):
        for line in ["bash ../tools/release/build.sh fdroid-prebuild mips",
                     "bash ../tools/release/build.sh fdroid-prebuild arm64-v8a",
                     "bash ../tools/release/build.sh fdroid-prebuild x86 /extra"]:
            with self.subTest(line=line):
                self.assertNotEqual(run_line(line, "/tmp").returncode, 0)


def build_sh_constant(name):
    return re.search(rf"^{name}=(\S+)$", (HERE / "build.sh").read_text(), re.M).group(1)


def build_sh(*arguments):
    env = dict(os.environ, LATENTJAM_DRY_RUN="1")
    return subprocess.run(["bash", str(HERE / "build.sh"), *arguments], cwd=ROOT, env=env, capture_output=True,
                          text=True)


class RuntimeSourceTest(unittest.TestCase):
    """Where the reduced runtime's source comes from, and that the commit decides, not the address."""

    def test_the_pin_is_for_the_version_the_app_uses(self):
        toml = (ROOT / "gradle/libs.versions.toml").read_text()
        self.assertEqual(build_sh_constant("ORT_SOURCE_VERSION"), re.search(r'^onnxruntime = "(.+)"', toml, re.M).group(1))
        self.assertRegex(build_sh_constant("ORT_SOURCE_COMMIT"), r"^[0-9a-f]{40}$")
        # The commit the vendored C API headers were taken from: one source for both.
        self.assertIn(build_sh_constant("ORT_SOURCE_COMMIT"),
                      (ROOT / "core/ort-ops/src/main/cpp/vendor/README.md").read_text())

    def test_fdroid_is_given_the_same_address(self):
        recipe = (HERE / "fdroid/recipe.yml").read_text()
        self.assertEqual(re.search(r"^    Repo: (\S+)$", recipe, re.M).group(1), build_sh_constant("ORT_SOURCE_URL"))

    def test_ort_source_clones_the_pinned_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = build_sh("ort-source", str(Path(tmp, "source")))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(f"--branch v{build_sh_constant('ORT_SOURCE_VERSION')} --recursive --shallow-submodules "
                          f"{build_sh_constant('ORT_SOURCE_URL')} {Path(tmp, 'source')}", result.stderr)
            # A directory that is already there is never cloned over.
            self.assertNotEqual(build_sh("ort-source", tmp).returncode, 0)

    def test_a_checkout_at_another_commit_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            git = ["git", "-C", tmp, "-c", "user.name=t", "-c", "user.email=t@localhost", "-c", "commit.gpgsign=false"]
            subprocess.run([*git, "init", "--quiet"], check=True)
            subprocess.run([*git, "commit", "--quiet", "--allow-empty", "-m", "another"], check=True)
            result = build_sh("fdroid-prebuild", "arm64-v8a", tmp, "/sdk/ndk/28.2.13676358", "/sdk")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(build_sh_constant("ORT_SOURCE_COMMIT"), result.stderr)
            self.assertNotIn("ort_source_build.sh", result.stderr)

    def test_the_pinned_checkout_is_accepted(self):
        cache = Path.home() / f".cache/latentjam/onnxruntime-v{build_sh_constant('ORT_SOURCE_VERSION')}"
        if not (cache / ".git").exists():
            self.skipTest(f"no checkout at {cache}")
        result = build_sh("fdroid-prebuild", "arm64-v8a", str(cache), "/sdk/ndk/28.2.13676358", "/sdk")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"ort_source_build.sh {cache} ", result.stderr)


if __name__ == "__main__":
    unittest.main()
