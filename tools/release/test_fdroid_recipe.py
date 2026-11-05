# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""fdroid_recipe.py against fdroidserver's own scanner and metadata code, on trees generated here (never committed:
a committed binary would itself need a scan entry). Skipped without fdroidserver
(~/.cache/latentjam/fdroidserver-venv/bin/python -m unittest ...)."""
import copy
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
try:
    import fdroidserver  # noqa: F401
    spec = importlib.util.spec_from_file_location("fdroid_recipe", HERE / "fdroid_recipe.py")
    recipe_tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(recipe_tool)
except ImportError:
    recipe_tool = None

ELF = b"\x7fELF\x02\x01\x01" + bytes(200)


def tree_with_flagged_files(root):
    """A tree where every scan entry of the recipe holds one file the scanner flags."""
    files = {
        "androidApp/src/main/assets/ml/table.bin": ELF,
        "androidApp/src/main/assets/ml/model.onnx": ELF,
        "tools/fuzz/corpus/seed": ELF,
        "core/smart/src/commonTest/resources/fixture.bin": ELF,
        "core/smart/src/androidHostTest/resources/golden.tsv.gz": b"\x1f\x8b" + bytes(20),
        "iosApp/iosApp/ml/table.bin": ELF,
        "androidApp/build.gradle.kts": b'android {\n    versionCode = 45\n    versionName = "0.9.4"\n}\n',
    }
    for name, data in files.items():
        path = Path(root, name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


@unittest.skipIf(recipe_tool is None, "needs fdroidserver")
class ScanTest(unittest.TestCase):
    def setUp(self):
        recipe_tool.common.options = __import__("argparse").Namespace(verbose=False, json=False)
        self.recipe = recipe_tool.load_recipe()
        self.blocks = recipe_tool.recipe_builds(self.recipe)

    def scan(self, mutate=None, block=1):
        with tempfile.TemporaryDirectory() as tmp:
            tree_with_flagged_files(tmp)
            if mutate:
                mutate(Path(tmp))
            recipe_tool.model_prebuild(tmp, self.blocks[block])
            count, _errors = recipe_tool.scan_problems(tmp, self.blocks[block])
            return count

    def test_every_block_passes_on_a_conforming_tree(self):
        for block in range(4):
            with self.subTest(block=block):
                self.assertEqual(self.scan(block=block), 0)

    def test_binary_outside_the_entries_fails(self):
        self.assertGreater(self.scan(lambda t: Path(t, "core/newlib/libx.so").parent.mkdir(parents=True)
                                     or Path(t, "core/newlib/libx.so").write_bytes(ELF)), 0)

    def test_code_in_the_asset_directory_is_not_covered_by_the_bin_glob(self):
        self.assertGreater(self.scan(lambda t: Path(t, "androidApp/src/main/assets/ml/code.jar").write_bytes(ELF)), 0)

    def test_an_entry_left_without_flagged_files_fails(self):
        # "Unused scandelete path" is fatal in fdroidserver: an emptied directory needs an MR.
        self.assertGreater(self.scan(lambda t: Path(t, "tools/fuzz/corpus/seed").write_text("text")), 0)

    def test_a_missing_entry_fails(self):
        import shutil
        self.assertGreater(self.scan(lambda t: shutil.rmtree(Path(t, "iosApp"))), 0)


@unittest.skipIf(recipe_tool is None, "needs fdroidserver")
class BotCopyTest(unittest.TestCase):
    """What fdroidserver's checkupdates does with the rendered recipe for the next tag (checkupdates_app)."""

    def test_bot_copy_for_next_release_equals_a_fresh_render(self):
        recipe = recipe_tool.load_recipe()
        current = recipe_tool.recipe_builds(recipe, "0.9.4", 45, "a" * 40)
        codes = sorted(recipe_tool.common.calculate_math_string(op.replace("%c", "46"))
                       for op in recipe["app"]["VercodeOperation"])
        copied = copy.deepcopy(current[-len(codes):])
        for build, code in zip(copied, codes):
            build.versionCode, build.versionName, build.commit = code, "0.9.5", "b" * 40
        fresh = recipe_tool.recipe_builds(recipe, "0.9.5", 46, "b" * 40)
        self.assertEqual([dict(b) for b in copied], [dict(b) for b in fresh])

    def test_render_compared_with_itself_needs_no_mr(self):
        recipe = recipe_tool.load_recipe()
        text = recipe_tool.dump_yaml({"Builds": recipe_tool.builds_for(recipe, "0.9.4", 45, "a" * 40),
                                      **recipe["app"]})
        self.assertEqual(recipe_tool.recipe_vs_master(recipe, text), [])
        changed = copy.deepcopy(recipe)
        changed["builds"][1]["prebuild"] += " --other"
        self.assertTrue(recipe_tool.recipe_vs_master(changed, text))

    def test_pins_must_match_the_tree(self):
        recipe = copy.deepcopy(recipe_tool.load_recipe())
        self.assertEqual(recipe_tool.pin_problems(recipe, recipe_tool.ROOT), [])
        recipe["ndk_version"] = "27.0.0"
        recipe["builds"][1]["srclibs"] = ["onnxruntime@v1.0.0"]
        self.assertEqual(len(recipe_tool.pin_problems(recipe, recipe_tool.ROOT)), 4)


@unittest.skipIf(recipe_tool is None, "needs fdroidserver")
class RealCheckupdatesTest(unittest.TestCase):
    """fdroidserver's own `checkupdates --auto` on the rendered recipe, against a local repository tagged v0.9.4 and
    v0.9.5: the bot must produce exactly what a fresh render for 0.9.5 would (goal 2 of the plan)."""

    def test_bot_ships_the_next_tag_unchanged(self):
        import os
        import subprocess
        fdroid = Path(sys.executable).parent / "fdroid"
        if not fdroid.exists():
            self.skipTest("no fdroid command next to this python")
        appid = recipe_tool.load_recipe()["appid"]
        gradle = (recipe_tool.ROOT / "androidApp/build.gradle.kts").read_text()
        with tempfile.TemporaryDirectory() as tmp:
            up, data = Path(tmp, "up"), Path(tmp, "data")
            git = lambda cwd, *a: subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", *a], cwd=cwd,
                                                 check=True, capture_output=True, text=True).stdout.strip()
            (up / "androidApp").mkdir(parents=True)
            git(up, "init", "-q")
            commits = {}
            for name, code in (("0.9.4", 45), ("0.9.5", 46)):
                text = __import__("re").sub(r"versionCode = \d+", f"versionCode = {code}", gradle)
                text = __import__("re").sub(r'versionName = "[^"]+"', f'versionName = "{name}"', text)
                (up / "androidApp/build.gradle.kts").write_text(text)
                git(up, "add", "-A")
                git(up, "commit", "-qm", name)
                git(up, "tag", "-a", f"v{name}", "-m", name)
                commits[name] = git(up, "rev-parse", "HEAD")
            base = Path(tmp, "base.yml")
            base.write_text(f"Categories:\n  - Local Media Player\nLicense: Apache-2.0\nRepoType: git\nRepo: {up}\n"
                            "AutoName: LatentJam\n")
            (data / "config").mkdir(parents=True)
            Path(data, "config.yml").write_text("")
            # render reads the version from this tree; pin it to 0.9.4 / 45 through a copy of the recipe tool's view.
            builds = recipe_tool.recipe_builds(recipe_tool.load_recipe(), "0.9.4", 45, commits["0.9.4"])
            app = recipe_tool.metadata.parse_metadata(base)
            app["Builds"] = builds
            for key, value in recipe_tool.load_recipe()["app"].items():
                app[key] = value
            app["CurrentVersion"], app["CurrentVersionCode"] = "0.9.4", 454
            (data / "metadata").mkdir()
            recipe_tool.metadata.write_metadata(data / "metadata" / f"{appid}.yml", app)
            git(data, "init", "-q")
            git(data, "add", "-A")
            git(data, "commit", "-qm", "base")
            result = subprocess.run([str(fdroid), "checkupdates", "--auto", "--allow-dirty", appid], cwd=data,
                                    capture_output=True, text=True, env=dict(os.environ, PYTHONPATH=""))
            self.assertEqual(result.returncode, 0, result.stderr[-2000:])
            after = recipe_tool.metadata.parse_metadata(data / "metadata" / f"{appid}.yml")
            fresh = recipe_tool.recipe_builds(recipe_tool.load_recipe(), "0.9.5", 46, commits["0.9.5"])
            self.assertEqual([dict(b) for b in after["Builds"][-4:]], [dict(b) for b in fresh])
            self.assertEqual((after.CurrentVersion, after.CurrentVersionCode), ("0.9.5", 464))


if __name__ == "__main__":
    unittest.main()


@unittest.skipIf(recipe_tool is None, "needs fdroidserver")
class CiYamlTest(unittest.TestCase):
    """What render writes is what fdroiddata CI's `fdroid rewritemeta` writes: same ruamel.yaml, a no-op rewrite."""

    def test_the_venv_runs_fdroiddata_cis_ruamel_yaml(self):
        import ruamel.yaml
        self.assertEqual(ruamel.yaml.__version__, recipe_tool.CI_RUAMEL_YAML,
                         "recreate the venv with tools/release/fdroid-venv.sh")

    def test_rewritemeta_leaves_a_rendered_file_unchanged(self):
        recipe_tool.common.options = __import__("argparse").Namespace(verbose=False, json=False)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp, "tree")
            (root / "androidApp").mkdir(parents=True)
            (root / "androidApp/build.gradle.kts").write_text(
                'android {\n    versionCode = 45\n    versionName = "0.9.4"\n}\n')
            base = Path(tmp, "base.yml")
            base.write_text("Categories:\n  - Multimedia\nLicense: Apache-2.0\n\nAutoName: LatentJam\n\n"
                            "RepoType: git\nRepo: https://github.com/Nikita-sud/latentjam.git\n")
            out = Path(tmp, "out")
            target = recipe_tool.render(root, "0" * 40, base, out)
            rendered = target.read_text()
            # fdroid rewritemeta: parse with fdroidserver, write with its writer.
            recipe_tool.metadata.write_metadata(target, recipe_tool.metadata.parse_metadata(target))
            self.assertEqual(rendered, target.read_text())
            # The line that broke !55889 stays whole, as CI's writer keeps it.
            self.assertIn("fdroid-prebuild arm64-v8a $$onnxruntime$$", rendered)
