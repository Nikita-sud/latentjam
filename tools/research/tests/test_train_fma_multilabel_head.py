from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd


SCRIPT = Path(__file__).resolve().parents[1] / "train_fma_multilabel_head.py"
SPEC = importlib.util.spec_from_file_location("train_fma_multilabel_head", SCRIPT)
assert SPEC and SPEC.loader
training = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = training
SPEC.loader.exec_module(training)


def label(index: int, kind: str = "broad", positives: int = 5) -> training.LabelSpec:
    return training.LabelSpec(
        index=index, genre_id=index + 1, name=("Rock", "Pop", "Punk")[index],
        kind=kind, parent_index=0 if kind == "child" else index,
        parent_genre_id=1 if kind == "child" else index + 1,
        parent_name="Rock" if kind == "child" else ("Rock", "Pop")[index],
        train_positives=positives, validation_positives=2,
    )


class RightsFilteringTests(unittest.TestCase):
    def test_regularization_plan_is_bound_to_exact_sources_and_broad_labels(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, plan_path = root / "input", root / "plan.json"
            source.write_text("synthetic source")
            plan = {
                "kind": "training_artist_cv", "selected_c": {"Rock": .1, "Pop": 1},
                "source_hashes": {"store": training.sha256_file(source)},
            }
            plan_path.write_text(json.dumps(plan))
            self.assertEqual(training.load_c_plan(plan_path, ["Rock", "Pop"], {"store": source}), {"Rock": .1, "Pop": 1.})
            with self.assertRaisesRegex(ValueError, "exactly the broad labels"):
                training.load_c_plan(plan_path, ["Rock"], {"store": source})
            tracks = root / "tracks.csv"
            tracks.write_text("synthetic labels and splits")
            with self.assertRaisesRegex(ValueError, "source hashes"):
                training.load_c_plan(plan_path, ["Rock", "Pop"], {"store": source, "tracks": tracks})
            source.write_text("changed input")
            with self.assertRaisesRegex(ValueError, "source hashes"):
                training.load_c_plan(plan_path, ["Rock", "Pop"], {"store": source})
            plan["source_hashes"]["store"] = training.sha256_file(source)
            for value in (0, -1, float("inf"), float("nan"), True):
                plan["selected_c"]["Rock"] = value
                plan_path.write_text(json.dumps(plan))
                with self.assertRaisesRegex(ValueError, "finite positive"):
                    training.load_c_plan(plan_path, ["Rock", "Pop"], {"store": source})

    def test_private_outputs_cannot_escape_or_enter_a_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            private = root / "private"
            with patch.object(training, "PRIVATE_ROOT", private):
                training.require_private_output(private / "head-v2")
                with self.assertRaisesRegex(ValueError, "Library-derived"):
                    training.require_private_output(root / "elsewhere")
                private.mkdir()
                (private / ".git").mkdir()
                with self.assertRaisesRegex(ValueError, "Library-derived"):
                    training.require_private_output(private / "head-v2")

    def test_license_families_exclude_nc_nd_and_other_licenses(self) -> None:
        for value, expected in (
            ("Attribution 1.0 Finland", "CC BY"),
            ("Attribution-Share Alike 3.0 United States", "CC BY-SA"),
            ("https://creativecommons.org/licenses/by-sa/4.0/", "CC BY-SA"),
            ("CC0 1.0 Universal", "CC0"),
            ("Public Domain Mark 1.0", "Public Domain"),
            ("Creative Commons Attribution-NonCommercial-NoDerivatives 4.0", "restricted NC"),
            ("Attribution-No Derivative Works 3.0 United States", "restricted ND"),
            ("CC BY-NC 4.0", "restricted NC"),
            ("CC BY-ND 4.0", "restricted ND"),
            ("Free Art", "other or unknown"),
            (None, "other or unknown"),
        ):
            with self.subTest(value=value):
                self.assertEqual(training.license_family(value), expected)

    def test_clean_filter_applies_to_training_validation_but_never_test(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store = root / "store.parquet"
            tracks = root / "tracks.csv"
            pd.DataFrame({
                "track_id": range(1, 7),
                "embedding": [[1.0, 0.0]] * 6,
                "model_version": ["test-version"] * 6,
            }).to_parquet(store)
            pd.DataFrame({
                ("set", "split"): ["training", "validation", "test", "training", "validation", "test"],
                ("set", "subset"): ["small"] * 3 + ["medium"] * 3,
                ("track", "genre_top"): ["Rock"] * 6,
                ("track", "genres_all"): ["[12]"] * 6,
                ("artist", "id"): range(1, 7),
                ("track", "license"): ["Attribution-Noncommercial"] * 3 + ["Attribution"] * 3,
            }, index=range(1, 7)).to_csv(tracks)

            legacy, dimension, version = training.load_joined_data(store, tracks)
            self.assertEqual(legacy["track_id"].tolist(), [1, 2, 3])
            self.assertEqual((dimension, version), (2, "test-version"))
            clean, _, _ = training.load_joined_data(
                store, tracks, subsets=("small", "medium"), license_allow=True,
            )
            self.assertEqual(clean["track_id"].tolist(), [3, 4, 5, 6])
            self.assertEqual(clean.loc[clean["split"] != "test", "license_family"].unique().tolist(), ["CC BY"])


class MaskingTests(unittest.TestCase):
    def test_unknown_children_do_not_change_fit_calibration_or_thresholds(self) -> None:
        rng = np.random.default_rng(23)
        matrix = rng.normal(size=(24, 3)).astype(np.float32)
        targets = (matrix[:, :1] > 0).astype(np.int64)
        mask = np.ones_like(targets, dtype=bool)
        baseline = training.fit_label(
            matrix[:16], targets[:16, 0], matrix[16:], targets[16:, 0],
            c_grid=[0.1, 1.0], max_iter=1000, seed=0,
            target_precision=0.8, min_threshold_predictions=1,
        )
        augmented_matrix = np.concatenate([matrix, np.full((10, 3), 1000.0, dtype=np.float32)])
        augmented_targets = np.concatenate([targets, np.ones((10, 1), dtype=np.int64)])
        augmented_mask = np.concatenate([mask, np.zeros((10, 1), dtype=bool)])
        train_indices = list(range(16)) + list(range(24, 29))
        validation_indices = list(range(16, 24)) + list(range(29, 34))
        x_train, y_train = training.label_training_rows(
            augmented_matrix[train_indices], augmented_targets[train_indices],
            augmented_mask[train_indices], 0,
        )
        x_validation, y_validation = training.label_training_rows(
            augmented_matrix[validation_indices], augmented_targets[validation_indices],
            augmented_mask[validation_indices], 0,
        )
        actual = training.fit_label(
            x_train, y_train, x_validation, y_validation,
            c_grid=[0.1, 1.0], max_iter=1000, seed=0,
            target_precision=0.8, min_threshold_predictions=1,
        )
        np.testing.assert_array_equal(actual.weight, baseline.weight)
        for name in ("bias", "selected_c", "calibration_scale", "calibration_bias", "threshold", "validation_ap"):
            self.assertEqual(getattr(actual, name), getattr(baseline, name))

    def test_unknown_child_predictions_are_not_scored_as_negative(self) -> None:
        specs = [label(0), label(1), label(2, "child")]
        frame = pd.DataFrame({
            "genre_top": ["Rock", "Pop", "Pop"],
            "genres_all": [(3,), (), ()],
            "children_known": [True, True, False],
        })
        targets = training.make_targets(frame, specs)
        mask = training.make_label_mask(frame, specs)
        np.testing.assert_array_equal(mask, [[True, True, True], [True, True, True], [True, True, False]])
        probabilities = np.asarray([[0.9, 0.1, 0.8], [0.1, 0.9, 0.2], [0.1, 0.9, 0.99]])
        fits = [training.FittedLabel(
            weight=np.zeros(2), bias=0.0, calibration_scale=1.0, calibration_bias=0.0,
            selected_c=1.0, validation_ap=1.0, threshold=0.5,
            threshold_target_precision=0.8, threshold_met_target=True,
            threshold_validation_predictions=1, threshold_validation_precision=1.0,
            threshold_validation_recall=1.0, best_f1_threshold=0.5, best_f1=1.0,
        ) for _ in specs]
        result = training.evaluate(targets, probabilities, probabilities, specs, fits, mask)
        child = result["per_label"][2]
        self.assertEqual(child["known_examples"], 2)
        self.assertEqual(child["unknown_examples"], 1)
        self.assertEqual(child["average_precision"], 1.0)
        self.assertAlmostEqual(child["brier"], 0.04)
        self.assertEqual(child["precision"], 1.0)
        self.assertEqual(result["exported_thresholds"]["micro_precision"], 1.0)

    def test_child_support_ignores_unknown_rows(self) -> None:
        frame = pd.DataFrame({
            "split": ["training", "training", "validation", "validation"],
            "genre_top": ["Rock"] * 4,
            "genres_all": [(), (3,), (), (3,)],
            "children_known": [True, False, True, False],
        })
        taxonomy = pd.DataFrame({"title": ["Rock", "Punk"], "parent": [0, 1], "top_level": [1, 1]}, index=[1, 3])
        specs = training.build_label_specs(frame, taxonomy, 1, 1)
        self.assertEqual([item.name for item in specs], ["Rock"])

    def test_balanced_default_and_rare_broad_cutoff(self) -> None:
        self.assertEqual(training.classifier_class_weight(label(0, positives=1000), "balanced"), "balanced")
        self.assertEqual(training.classifier_class_weight(label(0, positives=299), "rare"), "balanced")
        self.assertIsNone(training.classifier_class_weight(label(0, positives=300), "rare"))
        self.assertEqual(training.classifier_class_weight(label(2, "child", 1000), "rare"), "balanced")


class PrivateSourceTests(unittest.TestCase):
    def test_metadata_is_aggregate_only(self) -> None:
        frame = pd.DataFrame({
            "source": ["maintainer library", "FMA"],
            "split": ["training", "test"],
            "license_family": ["maintainer-owned", "restricted NC"],
            "track_id": ["private-track-id", 123],
            "artist_group": ["private-artist", "fma:2"],
            "title": ["private-title", "public-title"],
        })
        result = training.source_summary(frame)
        serialized = json.dumps(result)
        self.assertNotIn("private-track-id", serialized)
        self.assertNotIn("private-artist", serialized)
        self.assertNotIn("private-title", serialized)
        self.assertEqual(sum(item["total"] for item in result), 2)

    def test_extra_loader_rejects_artist_overlap_and_excludes_unmapped(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store_path, labels_path = root / "extra.parquet", root / "labels.parquet"
            pd.DataFrame({
                "track_id": ["a", "b", "c"], "embedding": [[1.0, 0.0]] * 3,
                "model_version": ["v1"] * 3,
            }).to_parquet(store_path)
            labels = pd.DataFrame({
                "track_id": ["a", "b", "c"], "broad_genre": ["Rock", "Pop", None],
                "split": ["training", "test", "validation"],
                "artist_group": ["same", "same", "unknown"],
                "child_labels_known": [False] * 3, "eligible": [True, True, False],
                "genre_tags": [["Rock"], ["Pop"], ["unmapped"]],
            })
            labels.to_parquet(labels_path)
            with self.assertRaisesRegex(ValueError, "artist-disjoint"):
                training.load_extra_data(store_path, labels_path, 2, "v1")
            labels.loc[1, "artist_group"] = "different"
            labels.to_parquet(labels_path)
            frame = training.load_extra_data(store_path, labels_path, 2, "v1")
            self.assertEqual(frame["track_id"].tolist(), ["a", "b"])
            self.assertFalse(frame["children_known"].any())
            self.assertNotIn("genre_tags", frame.columns)
            with self.assertRaisesRegex(ValueError, "same dimension and model_version"):
                training.load_extra_data(store_path, labels_path, 2, "old-version")


class MultigenreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.taxonomy = pd.DataFrame({
            "title": ["Rock", "Pop", "Punk", "Jazz"],
            "parent": [0, 0, 1, 0], "top_level": [1, 2, 1, 4],
        }, index=[1, 2, 3, 4])

    def test_all_roots_are_positive_and_unknown_roots_are_rejected(self) -> None:
        self.assertEqual(training.taxonomy_broad_genres((1, 2, 3), self.taxonomy), ("Pop", "Rock"))
        self.assertEqual(training.taxonomy_broad_genres((1, 4), self.taxonomy), ())
        self.assertEqual(training.taxonomy_broad_genres((1, 99), self.taxonomy), ())
        self.assertEqual(training.taxonomy_broad_genres((1, 4), self.taxonomy, allow_mixed_roots=True), ("Rock",))
        self.assertEqual(training.taxonomy_broad_genres((4,), self.taxonomy, allow_mixed_roots=True), ())
        self.assertEqual(training.taxonomy_broad_genres((), self.taxonomy, allow_mixed_roots=True), ())
        self.assertEqual(training.taxonomy_broad_genres((1, 99), self.taxonomy, allow_mixed_roots=True), ())
        frame = pd.DataFrame({
            "genre_top": [None, "Rock", "Pop"],
            "broad_genres": [("Pop", "Rock"), ("Rock",), ("Pop",)],
            "split": ["training", "validation", "validation"],
            "genres_all": [(1, 2, 3), (1, 3), (2,)],
            "children_known": [True] * 3,
        })
        specs = training.build_label_specs(frame, self.taxonomy, 1, 1)
        self.assertEqual([item.name for item in specs], ["Rock", "Pop", "Punk"])
        self.assertEqual([item.train_positives for item in specs], [1, 1, 1])
        np.testing.assert_array_equal(training.make_targets(frame, specs), [[1, 1, 1], [1, 0, 1], [0, 1, 0]])

    def test_additional_rows_never_enter_calibration_or_test(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store, tracks = root / "store.parquet", root / "tracks.csv"
            pd.DataFrame({
                "track_id": range(1, 7), "embedding": [[1.0, 0.0]] * 6,
                "model_version": ["v1"] * 6,
            }).to_parquet(store)
            pd.DataFrame({
                ("set", "split"): ["training", "validation", "test", "training", "training", "training"],
                ("set", "subset"): ["large"] * 6,
                ("track", "genre_top"): [None] * 5 + ["Rock"],
                ("track", "genres_all"): ["[1,2]", "[1,2]", "[1,2]", "[1,2]", "[1,4]", "[1]"],
                ("artist", "id"): range(1, 7),
                ("track", "license"): ["Attribution"] * 3 + ["Attribution-NonCommercial", "Attribution", "Attribution"],
            }, index=range(1, 7)).to_csv(tracks)
            frame, counts = training.load_multigenre_training_data(store, tracks, self.taxonomy, 2, "v1", [])
            self.assertEqual(frame["track_id"].tolist(), [1])
            self.assertEqual(counts, {
                "input_store_rows": 6, "selected_training": 1, "excluded_validation": 1,
                "excluded_test": 1, "excluded_single_genre_training": 1,
                "excluded_by_metadata_or_training_validation_rights": 2,
                "excluded_required_only_multigenre_training": 0,
            })
            self.assertEqual(frame["broad_genres"].tolist(), [("Pop", "Rock")])
            with self.assertRaisesRegex(ValueError, "overlaps"):
                training.load_multigenre_training_data(store, tracks, self.taxonomy, 2, "v1", [1])
            with self.assertRaisesRegex(ValueError, "model_version"):
                training.load_multigenre_training_data(store, tracks, self.taxonomy, 2, "old", [])
            mixed, mixed_counts = training.load_multigenre_training_data(
                store, tracks, self.taxonomy, 2, "v1", [], mixed_roots_only=True,
            )
            self.assertEqual(mixed["track_id"].tolist(), [5])
            self.assertEqual(mixed["broad_genres"].tolist(), [("Rock",)])
            self.assertTrue(mixed["mixed_roots"].all())
            self.assertTrue(mixed["multigenre"].all())
            self.assertEqual(mixed_counts["excluded_required_only_multigenre_training"], 1)
            np.testing.assert_array_equal(training.make_targets(mixed, [label(0), label(1)]), [[1, 0]])

    def test_weights_have_explicit_source_and_validation_only_meaning(self) -> None:
        frame = pd.DataFrame({
            "source": ["FMA"] * 3 + ["maintainer library"],
            "artist_group": ["a", "a", "b", "c"],
            "multigenre": [False, False, True, False],
            "genre_top": ["Rock", "Rock", "Rock", "Pop"],
        })
        np.testing.assert_array_equal(training.sample_training_weights(frame), np.ones(4))
        weighted = training.sample_training_weights(frame, library_weight=.25, multigenre_weight=.5, artist_power=1)
        np.testing.assert_allclose(weighted, [.75, .75, .75, .25])
        frame["mixed_roots"] = [False, False, True, False]
        weighted = training.sample_training_weights(frame, library_weight=.25, multigenre_weight=.5, mixed_root_weight=.1)
        np.testing.assert_allclose(weighted, [1., 1., .1, .25])
        prior_weights = training.calibration_weights(frame, power=1)
        self.assertAlmostEqual(prior_weights[:3].sum(), prior_weights[3])
        self.assertAlmostEqual(prior_weights.mean(), 1)
        frame["broad_genres"] = [("Rock",), ("Rock",), ("Rock", "Pop"), ("Pop",)]
        with self.assertRaisesRegex(ValueError, "single-genre"):
            training.calibration_weights(frame, power=.5)

    def test_weighted_platt_fallback_uses_weighted_prevalence(self) -> None:
        target = np.asarray([0, 0, 0, 1])
        logits = np.zeros(4)
        scale, bias = training.fit_platt_scaling(logits, target, 42, sample_weight=np.asarray([1., 1., 1., 3.]))
        self.assertEqual(scale, 1.0)
        self.assertAlmostEqual(bias, 0.0)

    def test_positive_only_masks_preserve_documented_roots_and_children(self) -> None:
        frame = pd.DataFrame({
            "genre_top": [None, None, "Pop", "Pop"],
            "broad_genres": [("Rock",), ("Rock", "Pop"), ("Pop",), ("Pop",)],
            "genres_all": [(1, 3), (1, 2), (2,), ()],
            "children_known": [True, True, True, False],
            "positive_only": [True, True, False, False],
        })
        specs = [label(0), label(1), label(2, "child")]
        target = training.make_targets(frame, specs)
        mask = training.make_label_mask(frame, specs)
        np.testing.assert_array_equal(target, [[1, 0, 1], [1, 1, 0], [0, 1, 0], [0, 1, 0]])
        np.testing.assert_array_equal(mask, [[True, False, True], [True, True, False], [True, True, True], [True, True, False]])
        matrix = np.arange(8).reshape(4, 2)
        child_matrix, child_targets = training.label_training_rows(matrix, target, mask, 2)
        np.testing.assert_array_equal(child_matrix, matrix[[0, 2]])
        np.testing.assert_array_equal(child_targets, [1, 0])


if __name__ == "__main__":
    unittest.main()
