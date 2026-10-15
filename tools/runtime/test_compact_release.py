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


if __name__ == "__main__":
    unittest.main()
