# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import subprocess
import unittest

spec = importlib.util.spec_from_file_location("taglib_verify", Path(__file__).with_name("verify.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class ManifestTest(unittest.TestCase):
    def test_empty_and_truncated_exports_fail(self):
        for text in ("", "\n", "case\ttrack\tmp3", "case\ttrack"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                verify.parse_manifest(text)

    def test_malformed_and_duplicate_rows_fail(self):
        valid = "case\ttrack\tmp3\nedit\ttrack\tTITLE\t4f6c64\n"
        for suffix in ("case\ttrack\tmp3", "edit\ttrack\tTITLE\t", "edit\tmissing\tTITLE\t",
                       "edit\ttrack\tARTIST\tzz", "edit\ttrack\tARTIST\tff", "header\tx"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                verify.parse_manifest(valid + suffix)

    def test_valid_deletion_and_unicode_are_preserved(self):
        value = "Музыка 🎧"
        cases = verify.parse_manifest(f"case\ttrack\tmp3\nedit\ttrack\tTITLE\t\nedit\ttrack\tARTIST\t{value.encode().hex()}\n")
        self.assertEqual({"TITLE": "", "ARTIST": value}, cases["track"]["edits"])


class OracleTest(unittest.TestCase):
    def setUp(self):
        self.cases = {extension: {"extension": extension, "edits": {"TITLE": ""}}
                      for extension in verify.FORMATS}

    def read(self, path):
        return {"TITLE": ["Old"], "CUSTOM": ["keep"]} if ".before." in path.name else {"CUSTOM": ["keep"]}

    def test_actual_deletions_with_preserved_unknown_fields_pass(self):
        self.assertEqual([], verify.verify_cases(self.cases, Path("fixtures"), self.read))

    def test_an_absent_original_value_cannot_prove_deletion(self):
        failures = verify.verify_cases(self.cases, Path("fixtures"), lambda _: {})
        self.assertTrue(any("no original value" in failure for failure in failures))

    def test_missing_container_and_deletion_coverage_fail(self):
        cases = {"single": {"extension": "mp3", "edits": {"TITLE": "New"}}}
        failures = verify.verify_cases(cases, Path("fixtures"), lambda _: {"TITLE": ["New"]})
        self.assertTrue(any("missing format coverage" in failure for failure in failures))
        self.assertIn("missing field-deletion coverage", failures)

    def test_nonzero_oracle_exit_is_a_failed_case(self):
        def fail(_):
            raise subprocess.CalledProcessError(1, "oracle")
        self.assertEqual(len(self.cases), len(verify.verify_cases(self.cases, Path("fixtures"), fail)))

    def test_untouched_property_loss_is_detected(self):
        def read(path):
            return self.read(path) if ".before." in path.name else {}
        failures = verify.verify_cases(self.cases, Path("fixtures"), read)
        self.assertTrue(any("untouched CUSTOM changed" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
