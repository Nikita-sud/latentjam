from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
import pandas as pd


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import evaluate_semantic_head_clean as evaluation
import export_universal_semantic_head as semantic


def synthetic_parameters() -> tuple[semantic.FmaHeadParameters, semantic.AudioSetHeadParameters]:
    names = semantic.required_fma_labels()
    count = len(names)
    fma = semantic.FmaHeadParameters(
        weights=np.zeros((count, 960), dtype=np.float32),
        biases=np.zeros(count, dtype=np.float32),
        calibration_scales=np.ones(count, dtype=np.float32),
        calibration_biases=np.zeros(count, dtype=np.float32),
        parent_indices=np.arange(count, dtype=np.int64),
        child_mask=np.zeros(count, dtype=bool), label_names=names,
        label_metadata=tuple({"name": name, "kind": "broad"} for name in names),
    )
    audio_names = semantic.required_audioset_labels()
    audio = semantic.AudioSetHeadParameters(
        first_weight=np.zeros((4, 960), dtype=np.float32),
        first_bias=np.zeros(4, dtype=np.float32),
        second_weight=np.zeros((len(audio_names), 4), dtype=np.float32),
        second_bias=np.asarray([
            2.0 if name in {"Music", "Musical instrument"} else -2.0 for name in audio_names
        ], dtype=np.float32), label_names=audio_names,
    )
    return fma, audio


class GateTests(unittest.TestCase):
    @staticmethod
    def metrics(value: float) -> dict:
        return {"top1_accuracy": value, "macro_average_precision": value,
                "per_genre_average_precision": {name: value for name in evaluation.BROAD_NAMES}}

    def test_fma_regression_limits_are_inclusive_and_one_sided(self) -> None:
        old, new = self.metrics(0.7), self.metrics(0.69)
        for name in evaluation.PROTECTED_GENRES:
            new["per_genre_average_precision"][name] = 0.67
        self.assertTrue(evaluation.fma_comparison(old, new)["passed"])
        self.assertTrue(evaluation.fma_comparison(old, self.metrics(0.95))["passed"])
        new["top1_accuracy"] = 0.6899
        self.assertFalse(evaluation.fma_comparison(old, new)["passed"])

    def test_protected_genre_regression_alone_fails(self) -> None:
        old, new = self.metrics(0.7), self.metrics(0.7)
        new["per_genre_average_precision"]["Folk"] = 0.6699
        result = evaluation.fma_comparison(old, new)
        self.assertFalse(result["passed"])
        self.assertTrue(result["gates"]["top1_accuracy"]["passed"])

    def test_clean_diagnostic_can_report_absent_folk_without_fabricating_ap(self) -> None:
        names = [name for name in evaluation.BROAD_NAMES if name != "Folk"]
        labels = np.asarray(names * 2)
        scores = np.zeros((len(labels), len(evaluation.BROAD_NAMES)), dtype=np.float32)
        for index, name in enumerate(labels):
            scores[index, evaluation.BROAD_NAMES.index(name)] = 1.0
        with self.assertRaisesRegex(ValueError, "positive and negative examples for Folk"):
            evaluation.classification_metrics(labels, scores)
        metrics = evaluation.classification_metrics(labels, scores, allow_missing_classes=True)
        self.assertIsNone(metrics["per_genre_average_precision"]["Folk"])
        self.assertEqual(metrics["macro_average_precision_class_count"], 7)
        self.assertEqual(metrics["macro_average_precision"], 1.0)
        self.assertEqual(metrics["class_counts"]["Folk"], 0)
        self.assertEqual(metrics["unavailable_average_precision_classes"], ["Folk"])
        comparison = evaluation.fma_comparison(metrics, metrics)
        self.assertIsNone(comparison["gates"]["average_precision_Folk"]["passed"])
        self.assertIsNone(comparison["gates"]["average_precision_Folk"]["delta_percentage_points"])
        self.assertEqual(comparison["unavailable_gates"], ["average_precision_Folk"])

    def test_library_checks_each_output_not_global_average(self) -> None:
        old = np.full((10, 27), 0.3, dtype=np.float64)
        old[:, evaluation.BROAD_OUTPUT_INDICES[0]] = 0.8
        new = old.copy()
        new[:, 8] += 0.050001
        result, disagree = evaluation.library_comparison(old, new)
        self.assertFalse(result["passed"])
        self.assertEqual(int(disagree.sum()), 0)
        self.assertFalse(result["per_output"][evaluation.OUTPUT_IDS[8]]["difference_passed"])

    def test_library_exact_90_percent_agreement_passes(self) -> None:
        old = np.full((10, 27), 0.3, dtype=np.float64)
        old[:, evaluation.BROAD_OUTPUT_INDICES[0]] = 0.4
        new = old.copy()
        new[0, evaluation.BROAD_OUTPUT_INDICES[1]] = 0.41
        result, disagree = evaluation.library_comparison(old, new)
        self.assertTrue(result["passed"])
        self.assertEqual(result["gates"]["broad_top1_agreement"]["value"], 0.9)
        self.assertEqual(np.flatnonzero(disagree).tolist(), [0])
        new[1, evaluation.BROAD_OUTPUT_INDICES[1]] = 0.41
        self.assertFalse(evaluation.library_comparison(old, new)[0]["passed"])

    def test_library_mae_detects_cancelling_positive_negative_changes(self) -> None:
        old = np.full((10, 27), 0.3)
        new = old.copy()
        new[:5, 0] += 0.06
        new[5:, 0] -= 0.06
        result = evaluation.library_comparison(old, new)[0]
        self.assertFalse(result["gates"]["per_output_mean_absolute_difference"]["passed"])
        self.assertTrue(result["gates"]["per_output_absolute_mean_shift"]["passed"])


class DatasetTests(unittest.TestCase):
    def test_clean_licenses_exclude_nc_nd_and_other_families(self) -> None:
        for value in ("Attribution 4.0", "Attribution-ShareAlike 3.0", "CC0 1.0", "Public Domain"):
            self.assertTrue(evaluation.license_is_clean(value))
        for value in ("Attribution-NonCommercial 4.0", "Attribution-NoDerivatives 4.0", "Free Art", None):
            self.assertFalse(evaluation.license_is_clean(value))

    def test_full_fma_test_includes_nc_but_never_validation(self) -> None:
        columns = pd.MultiIndex.from_tuples([
            ("set", "split"), ("set", "subset"), ("track", "genre_top"), ("track", "license")
        ])
        tracks = pd.DataFrame([
            ["test", "small", "Rock", "Attribution-NonCommercial 4.0"],
            ["validation", "small", "Rock", "Attribution 4.0"],
            ["test", "medium", "Pop", "Attribution 4.0"],
        ], index=[1, 2, 3], columns=columns)
        store = pd.DataFrame({"track_id": [1, 2, 3], "embedding": [np.zeros(960)] * 3})
        full = evaluation.fma_test_data(store, tracks, clean=False)
        clean = evaluation.fma_test_data(store, tracks, clean=True)
        self.assertEqual(full["track_id"].tolist(), [1])
        self.assertEqual(clean["track_id"].tolist(), [3])
        with self.assertRaisesRegex(ValueError, "incomplete"):
            evaluation.fma_test_data(store.iloc[:2], tracks, clean=True)

    def test_output_cannot_escape_private_root_or_enter_a_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with mock.patch.object(evaluation, "PRIVATE_WORK_ROOT", root):
                self.assertEqual(evaluation.ensure_private_output(root / "report"), root / "report")
                with self.assertRaisesRegex(ValueError, "private work directory"):
                    evaluation.ensure_private_output(root.parent / "elsewhere")
                (root / "escaping-link").symlink_to(root.parent, target_is_directory=True)
                with self.assertRaisesRegex(ValueError, "private work directory"):
                    evaluation.ensure_private_output(root / "escaping-link" / "report")
                (root / ".git").mkdir()
                with self.assertRaisesRegex(ValueError, "repository"):
                    evaluation.ensure_private_output(root / "report")


class GraphAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.fma, cls.audio = synthetic_parameters()
        cls.model = semantic.build_hybrid_model(cls.fma, cls.audio)
        cls.path = cls.root / "synthetic.onnx"
        semantic.export_onnx(cls.model, cls.path, opset=17, fp16=True)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def test_fma_instrumental_is_not_confused_with_hybrid_product_output(self) -> None:
        audit = evaluation.HeadAudit(self.path)
        embeddings = np.zeros((3, 960), dtype=np.float32)
        embeddings[:, 0] = 1.0
        product, broad = audit.predict(embeddings)
        instrumental = evaluation.BROAD_NAMES.index("Instrumental")
        np.testing.assert_allclose(broad[:, instrumental], 0.5)
        self.assertTrue(np.all(product[:, evaluation.BROAD_OUTPUT_INDICES[instrumental]] > 0.7))
        self.assertEqual(len(audit.routing), 27)

    def test_metadata_label_mismatch_is_rejected(self) -> None:
        metadata = self.root / "metadata.json"
        labels = list(self.fma.label_metadata)
        labels[0] = {"name": "Wrong label", "kind": "broad"}
        metadata.write_text(json.dumps({"labels": labels}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "metadata disagrees"):
            evaluation.HeadAudit(self.path, metadata)

    def test_swapped_audioset_output_order_is_detected(self) -> None:
        import onnx

        changed = onnx.load(str(self.path))
        output = next(node for node in changed.graph.node if "semantic_scores_fp16" in node.output)
        output.input[7], output.input[9] = output.input[9], output.input[7]
        changed_path = self.root / "swapped.onnx"
        onnx.save(changed, str(changed_path))
        old, new = evaluation.HeadAudit(self.path), evaluation.HeadAudit(changed_path)
        self.assertNotEqual(old.routing, new.routing)

    def test_declared_output_order_is_checked(self) -> None:
        import onnx

        changed = onnx.load(str(self.path))
        order = list(evaluation.OUTPUT_IDS)
        order[7], order[9] = order[9], order[7]
        onnx.helper.set_model_props(changed, {"semantic_output_ids": json.dumps(order)})
        changed_path = self.root / "declared-swapped.onnx"
        onnx.save(changed, str(changed_path))
        with self.assertRaisesRegex(ValueError, "output order disagrees"):
            evaluation.HeadAudit(changed_path)

    def test_changed_feature_scale_changes_full_audioset_ancestry(self) -> None:
        changed_model = semantic.build_hybrid_model(self.fma, self.audio, feature_scale=3.0)
        changed_path = self.root / "changed-scale.onnx"
        semantic.export_onnx(changed_model, changed_path, opset=17, fp16=True)
        old, new = evaluation.HeadAudit(self.path), evaluation.HeadAudit(changed_path)
        self.assertEqual(old.routing, new.routing)
        for name in ("audio_first_bias", "audio_second_weight", "audio_second_bias"):
            np.testing.assert_array_equal(old.initializers[name], new.initializers[name])
        self.assertNotEqual(old.audioset_ancestry, new.audioset_ancestry)

    def test_audioset_ancestry_ignores_node_and_internal_tensor_names(self) -> None:
        import onnx

        changed = onnx.load(str(self.path))
        renamed = {tensor.name: "renamed_" + tensor.name for tensor in changed.graph.initializer
                   if not tensor.name.startswith(("fma_", "audio_"))}
        for tensor in changed.graph.initializer:
            if tensor.name in renamed:
                tensor.name = renamed[tensor.name]
        for node in changed.graph.node:
            node.name = "renamed_" + node.name
            for output in node.output:
                if output != "semantic_scores":
                    renamed[output] = "renamed_" + output
        for node in changed.graph.node:
            for i, value in enumerate(node.input):
                node.input[i] = renamed.get(value, value)
            for i, value in enumerate(node.output):
                node.output[i] = renamed.get(value, value)
        changed_path = self.root / "renamed.onnx"
        onnx.save(changed, str(changed_path))
        old, new = evaluation.HeadAudit(self.path), evaluation.HeadAudit(changed_path)
        self.assertEqual(old.audioset_ancestry, new.audioset_ancestry)


if __name__ == "__main__":
    unittest.main()
