#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Small fixtures: reject stale models, modified artifacts and a stock runtime packaged by mistake."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("compact_release", Path(__file__).with_name("build_compact_release.py"))
compact = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compact)


class CompactReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text('onnxruntime = "1.26.0"\n')
        models = self.root / "androidApp/src/main/assets/ml"
        models.mkdir(parents=True)
        (models / "graph.onnx").write_bytes(b"model")
        self.repo = self.root / "maven"
        version = "1.26.0-latentjam-arm64"
        self.candidate = self.repo / f"com/microsoft/onnxruntime/onnxruntime-android/{version}/onnxruntime-android-{version}.aar"
        self.candidate.parent.mkdir(parents=True)
        self.stock = self.root / "stock.aar"
        self.stock_entries = {f"jni/{abi}/{lib}": b"stock" * 400 for abi in (*compact.ABIS, "x86")
                              for lib in ("libonnxruntime.so", "libonnxruntime4j_jni.so")}
        self.reduced_entries = dict(self.stock_entries)
        self.reduced_entries["jni/arm64-v8a/libonnxruntime.so"] = b"reduced" * 100
        self.reduced_entries["jni/arm64-v8a/libonnxruntime4j_jni.so"] = b"reduced-jni"
        self.write_zip(self.stock, self.stock_entries)
        self.write_zip(self.candidate, self.reduced_entries)
        self.manifest = {"ort_version": "1.26.0", "candidate_version": version,
                         "models": {"graph.onnx": compact.sha(models / "graph.onnx")},
                         "stock_aar_sha256": compact.sha(self.stock), "candidate_aar_sha256": compact.sha(self.candidate),
                         "custom_abis": ["arm64-v8a"], "retained_stock_abis": ["armeabi-v7a", "x86", "x86_64"],
                         "native_bytes": {name: len(value) for name, value in self.reduced_entries.items()
                                          if name.startswith("jni/arm64-v8a/")}}
        self.save_manifest()

    @staticmethod
    def write_zip(path, entries):
        with zipfile.ZipFile(path, "w") as archive:
            for name, value in entries.items():
                archive.writestr(name, value)

    def save_manifest(self):
        (self.repo / "manifest.json").write_text(json.dumps(self.manifest))

    def validate(self):
        return compact.validate_candidate(self.root, self.repo, self.stock)

    def test_valid_input_and_exact_packaged_libraries(self):
        checked = self.validate()
        for abi in compact.ABIS:
            apk = self.root / f"{abi}.apk"
            entries = {name.replace("jni/", "lib/", 1): value for name, value in self.reduced_entries.items()
                       if name.startswith(f"jni/{abi}/")}
            self.write_zip(apk, entries)
            compact.verify_runtime(apk, abi, checked["native"])

    def test_packaging_stock_instead_of_checked_candidate_fails(self):
        checked = self.validate()
        apk = self.root / "wrong.apk"
        self.write_zip(apk, {name.replace("jni/", "lib/", 1): value for name, value in self.stock_entries.items()
                             if name.startswith("jni/arm64-v8a/")})
        with self.assertRaisesRegex(ValueError, "Packaged ORT does not match"):
            compact.verify_runtime(apk, "arm64-v8a", checked["native"])

    def test_removed_or_added_models_are_not_silently_accepted(self):
        self.manifest["models"]["removed.onnx"] = "a" * 64
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "exact shipped model set"):
            self.validate()

    def test_modified_aar_fails_before_build(self):
        self.candidate.write_bytes(self.candidate.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "differs from the manifest"):
            self.validate()

    def test_stock_runtime_masquerading_as_reduced_fails(self):
        self.candidate.write_bytes(self.stock.read_bytes())
        self.manifest["candidate_aar_sha256"] = compact.sha(self.candidate)
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "not smaller than stock"):
            self.validate()

    def test_other_abis_cannot_change(self):
        self.reduced_entries["jni/x86_64/libonnxruntime.so"] = b"modified"
        self.write_zip(self.candidate, self.reduced_entries)
        self.manifest["candidate_aar_sha256"] = compact.sha(self.candidate)
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "Non-ARM64 runtime must be stock"):
            self.validate()

    def test_dirty_source_is_rejected_unless_explicitly_requested(self):
        compact.validate_source({"status": ""})
        with self.assertRaisesRegex(ValueError, "local changes"):
            compact.validate_source({"status": "?? unexpected.py\n"})
        compact.validate_source({"status": " M settings.gradle.kts\n"}, allow_dirty=True)

    def test_missing_or_duplicate_packaged_runtime_fails(self):
        checked = self.validate()
        apk = self.root / "incomplete.apk"
        self.write_zip(apk, {"lib/arm64-v8a/libonnxruntime.so": b"reduced" * 100})
        with self.assertRaisesRegex(ValueError, "Packaged ORT does not match"):
            compact.verify_runtime(apk, "arm64-v8a", checked["native"])
        with zipfile.ZipFile(apk, "a") as archive:
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr("lib/arm64-v8a/libonnxruntime.so", b"second")
        with self.assertRaisesRegex(ValueError, "Duplicate APK"):
            compact.verify_runtime(apk, "arm64-v8a", checked["native"])

    def test_wrong_packaged_abi_fails(self):
        checked = self.validate()
        apk = self.root / "wrong-abi.apk"
        self.write_zip(apk, {"lib/x86/libonnxruntime.so": b"wrong"})
        with self.assertRaisesRegex(ValueError, "Expected one"):
            compact.verify_runtime(apk, "arm64-v8a", checked["native"])

    def test_strip_tool_must_come_from_matching_ndk(self):
        for name in ("audio-dsp", "map-native", "ort-ops"):
            directory = self.root / "core" / name
            directory.mkdir(parents=True)
            (directory / "build.gradle.kts").write_text('ndkVersion = "28.2.13676358"')
        ndk = self.root / "ndk"
        tool = ndk / "bin/llvm-strip"
        tool.parent.mkdir(parents=True)
        tool.touch()
        properties = ndk / "source.properties"
        properties.write_text("Pkg.Revision = 27.0.0\n")
        with self.assertRaisesRegex(ValueError, "app's NDK"):
            compact.validate_strip_tool(self.root, tool)
        properties.write_text("Pkg.Revision = 28.2.13676358\n")
        self.assertEqual("28.2.13676358", compact.validate_strip_tool(self.root, tool))

    def test_packaged_hash_uses_stripped_bytes_without_changing_aar(self):
        before = compact.sha(self.candidate)
        tool = self.root / "strip"
        tool.write_bytes(b"strip fixture")
        def strip(argv, **kwargs):
            p = Path(argv[-1])
            p.write_bytes(p.read_bytes() + b"stripped")
        with patch.object(compact.subprocess, "check_output", return_value="fixture"), patch.object(compact.subprocess, "run", side_effect=strip):
            result = compact.packaged_native(self.candidate, tool)
        self.assertEqual(before, compact.sha(self.candidate))
        self.assertEqual(len(self.reduced_entries), len(result["commands"]))
        for name, value in self.reduced_entries.items():
            self.assertEqual(compact.hashlib.sha256(value + b"stripped").hexdigest(), result["native"][name]["sha256"])


if __name__ == "__main__":
    unittest.main()
