#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Incomplete native payloads must not pass a release inventory check."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("apk_inventory", Path(__file__).with_name("apk_inventory.py"))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class InventoryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apk = self.root / "test.apk"

    def write(self, libraries):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for library in sorted(libraries):
                archive.writestr("lib/x86/" + library, b"inventory fixture")

    def test_complete_x86_inventory_passes(self):
        self.write(inventory.REQUIRED_LIBRARIES)
        self.assertEqual(["x86"], inventory.inspect(self.apk)["abis"])

    def test_missing_runtime_does_not_hide_other_incomplete_libraries(self):
        self.write({"libljq4.so"})
        with self.assertRaisesRegex(ValueError, "lacks"):
            inventory.inspect(self.apk)

    def test_missing_audio_dsp_fails(self):
        self.write(inventory.REQUIRED_LIBRARIES - {"liblj_equalizer.so"})
        with self.assertRaisesRegex(ValueError, "liblj_equalizer.so"):
            inventory.inspect(self.apk)

    def test_duplicate_member_fails(self):
        self.write(inventory.REQUIRED_LIBRARIES)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as archive:
                archive.writestr("lib/x86/libljq4.so", b"shadow copy")
        with self.assertRaisesRegex(ValueError, "duplicate ZIP"):
            inventory.inspect(self.apk)

    def test_empty_native_payload_fails(self):
        self.write(set())
        with self.assertRaisesRegex(ValueError, "no native libraries"):
            inventory.inspect(self.apk)

    def test_missing_entire_abi_fails_before_report_is_written(self):
        self.write(inventory.REQUIRED_LIBRARIES)
        output = self.root / "report.json"
        result = subprocess.run([sys.executable, str(Path(inventory.__file__)), str(self.root),
                                 "--output", str(output), "--abis", "x86", "arm64-v8a"],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ABI set differs", result.stderr)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
