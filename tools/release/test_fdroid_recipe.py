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


if __name__ == "__main__":
    unittest.main()
