from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np
import pandas as pd
import torch


SCRIPT = Path(__file__).resolve().parents[1] / "train_audio_retrieval_student.py"
SPEC = importlib.util.spec_from_file_location("train_audio_retrieval_student", SCRIPT)
assert SPEC and SPEC.loader
trainer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = trainer
SPEC.loader.exec_module(trainer)


def group_for_split(split: str, seed: int = 42) -> str:
    for index in range(100_000):
        value = f"group-{index}"
        if trainer.grouped_split(
            [value],
            seed=seed,
            validation_fraction=0.1,
            test_fraction=0.1,
        )[0] == split:
            return value
    raise AssertionError(f"could not find a synthetic {split} group")


class SplitTests(unittest.TestCase):
    def test_group_split_is_stable_and_group_disjoint(self) -> None:
        groups = ["a", "a", "b", "c", "b"]
        first = trainer.grouped_split(
            groups,
            seed=7,
            validation_fraction=0.2,
            test_fraction=0.2,
        )
        second = trainer.grouped_split(
            groups,
            seed=7,
            validation_fraction=0.2,
            test_fraction=0.2,
        )
        self.assertEqual(first, second)
        self.assertEqual(first[0], first[1])
        self.assertEqual(first[2], first[4])

    def test_mpd_pairs_require_session_and_both_artists_in_split(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            train_group = group_for_split("training")
            validation_group = group_for_split("validation")
            train_session = group_for_split("training") + "-session"
            while trainer.grouped_split(
                [train_session],
                seed=42,
                validation_fraction=0.1,
                test_fraction=0.1,
            )[0] != "training":
                train_session += "x"
            validation_session = group_for_split("validation") + "-session"
            while trainer.grouped_split(
                [validation_session],
                seed=42,
                validation_fraction=0.1,
                test_fraction=0.1,
            )[0] != "validation":
                validation_session += "x"

            audio_paths: dict[str, str] = {}
            for track_id in ("ta", "tb", "va", "vb"):
                path = root / f"{track_id}.wav"
                path.touch()
                audio_paths[track_id] = str(path)
            store = pd.DataFrame(
                {
                    "track_id": ["ta", "tb", "va", "vb"],
                    "path": [audio_paths[key] for key in ("ta", "tb", "va", "vb")],
                    "artist": [
                        train_group,
                        train_group + "-2",
                        validation_group,
                        validation_group + "-2",
                    ],
                    "title": ["track a", "track b", "track c", "track d"],
                }
            )
            # Ensure the suffixed groups still hash to their intended split.
            for index in (1, 3):
                target = "training" if index == 1 else "validation"
                value = store.loc[index, "artist"]
                while trainer.grouped_split(
                    [value],
                    seed=42,
                    validation_fraction=0.1,
                    test_fraction=0.1,
                )[0] != target:
                    value += "x"
                store.loc[index, "artist"] = value
            store_path = root / "store.parquet"
            store.to_parquet(store_path, index=False)

            events = pd.DataFrame(
                [
                    # Valid training pair.
                    (train_session, 1, "ta"),
                    (train_session, 2, "tb"),
                    # Training playlist but cross-artist-split target: must be dropped.
                    (train_session + "-cross", 1, "ta"),
                    (train_session + "-cross", 2, "va"),
                    # Valid validation pair.
                    (validation_session, 1, "va"),
                    (validation_session, 2, "vb"),
                ],
                columns=["session_id", "ts_unix_ms", "track_id"],
            )
            # Force the cross session itself into training so artist filtering,
            # rather than a lucky session hash, is what rejects the pair.
            cross = str(events.loc[2, "session_id"])
            while trainer.grouped_split(
                [cross],
                seed=42,
                validation_fraction=0.1,
                test_fraction=0.1,
            )[0] != "training":
                cross += "x"
            events.loc[2:3, "session_id"] = cross
            events_path = root / "events.parquet"
            events.to_parquet(events_path, index=False)

            train_pairs = trainer.load_mpd_pairs(
                events_path=events_path,
                store_path=store_path,
                research_root=root,
                split="training",
                seed=42,
                validation_fraction=0.1,
                test_fraction=0.1,
                max_pairs=0,
            )
            validation_pairs = trainer.load_mpd_pairs(
                events_path=events_path,
                store_path=store_path,
                research_root=root,
                split="validation",
                seed=42,
                validation_fraction=0.1,
                test_fraction=0.1,
                max_pairs=0,
            )
            self.assertEqual(
                [(pair.anchor_id, pair.positive_id) for pair in train_pairs],
                [("ta", "tb")],
            )
            self.assertEqual(
                [(pair.anchor_id, pair.positive_id) for pair in validation_pairs],
                [("va", "vb")],
            )

    def test_limited_catalog_is_source_balanced_deterministic_and_disjoint(
        self,
    ) -> None:
        # Keep every FMA row first to reproduce the source-order bias caused by
        # slicing the combined catalog directly.
        catalog = pd.DataFrame(
            [
                *[
                    {"track_id": f"fma-train-{index}", "source": "fma", "split": "training"}
                    for index in range(8)
                ],
                *[
                    {
                        "track_id": f"fma-validation-{index}",
                        "source": "fma",
                        "split": "validation",
                    }
                    for index in range(6)
                ],
                *[
                    {
                        "track_id": f"itunes-train-{index}",
                        "source": "itunes",
                        "split": "training",
                    }
                    for index in range(5)
                ],
                *[
                    {
                        "track_id": f"itunes-validation-{index}",
                        "source": "itunes",
                        "split": "validation",
                    }
                    for index in range(4)
                ],
            ]
        )

        train = trainer.source_stratified_catalog_subset(
            catalog, split="training", limit=6, seed=23
        )
        train_from_reordered_input = trainer.source_stratified_catalog_subset(
            catalog.iloc[::-1], split="training", limit=6, seed=23
        )
        validation = trainer.source_stratified_catalog_subset(
            catalog, split="validation", limit=4, seed=23
        )

        self.assertEqual(
            trainer.catalog_source_counts(train),
            {"fma": 3, "itunes": 3},
        )
        self.assertEqual(
            trainer.catalog_source_counts(validation),
            {"fma": 2, "itunes": 2},
        )
        self.assertEqual(
            train["track_id"].tolist(),
            train_from_reordered_input["track_id"].tolist(),
        )
        self.assertEqual(set(train["split"]), {"training"})
        self.assertEqual(set(validation["split"]), {"validation"})
        self.assertTrue(
            set(train["track_id"]).isdisjoint(set(validation["track_id"]))
        )

    def test_zero_limit_preserves_full_split_order(self) -> None:
        catalog = pd.DataFrame(
            [
                {"track_id": "fma-1", "source": "fma", "split": "training"},
                {"track_id": "itunes-1", "source": "itunes", "split": "training"},
                {"track_id": "fma-v", "source": "fma", "split": "validation"},
                {"track_id": "fma-2", "source": "fma", "split": "training"},
            ]
        )
        selected = trainer.source_stratified_catalog_subset(
            catalog, split="training", limit=0, seed=999
        )
        self.assertEqual(
            selected["track_id"].tolist(),
            ["fma-1", "itunes-1", "fma-2"],
        )


class LossTests(unittest.TestCase):
    def test_relational_loss_is_dimension_independent(self) -> None:
        student = torch.tensor([[1.0, 0.0], [0.0, 1.0], [1.0, 1.0]])
        teacher = torch.tensor(
            [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [1.0, 1.0, 0.0]]
        )
        loss = trainer.relational_similarity_loss(student, teacher)
        self.assertLess(float(loss), 1e-10)

    def test_symmetric_info_nce_prefers_aligned_pairs(self) -> None:
        aligned = torch.eye(4)
        shuffled = aligned[[1, 0, 3, 2]]
        metadata = tuple((f"a-{index}", f"p-{index}") for index in range(4))
        sessions = tuple(f"s-{index}" for index in range(4))
        good = trainer.symmetric_info_nce(
            aligned,
            aligned,
            temperature=0.1,
            track_ids=metadata,
            session_ids=sessions,
        )
        bad = trainer.symmetric_info_nce(
            aligned,
            shuffled,
            temperature=0.1,
            track_ids=metadata,
            session_ids=sessions,
        )
        self.assertLess(float(good), float(bad))

    def test_retrieval_ranks_perfect_diagonal(self) -> None:
        embeddings = np.eye(5, dtype=np.float32)
        ranks = trainer.retrieval_ranks(embeddings, embeddings)
        np.testing.assert_array_equal(ranks, np.ones(5, dtype=np.int64))

    def test_retrieval_ranks_use_average_tie_rank(self) -> None:
        embeddings = np.asarray([[1.0, 0.0], [1.0, 0.0]], dtype=np.float32)
        ranks = trainer.retrieval_ranks(embeddings, embeddings)
        np.testing.assert_array_equal(ranks, np.asarray([1.5, 1.5]))


class BatchNormFreezeTests(unittest.TestCase):
    def test_freeze_preserves_buffers_and_trains_affine_parameters(self) -> None:
        torch.manual_seed(7)
        model = torch.nn.Sequential(
            torch.nn.Linear(4, 4, bias=False),
            torch.nn.BatchNorm1d(4),
            torch.nn.Tanh(),
            torch.nn.Linear(4, 1, bias=False),
        )
        batch_norm = model[1]
        optimizer = torch.optim.SGD(model.parameters(), lr=0.1)

        model.train()
        frozen_count = trainer.freeze_batch_norm_running_stats(model)
        self.assertTrue(model.training)
        self.assertEqual(frozen_count, 1)
        self.assertFalse(batch_norm.training)
        self.assertTrue(batch_norm.weight.requires_grad)
        self.assertTrue(batch_norm.bias.requires_grad)

        running_mean = batch_norm.running_mean.detach().clone()
        running_var = batch_norm.running_var.detach().clone()
        batches_tracked = batch_norm.num_batches_tracked.detach().clone()
        affine_before = (
            batch_norm.weight.detach().clone(),
            batch_norm.bias.detach().clone(),
        )

        inputs = torch.randn(16, 4) * 3.0 + 8.0
        loss = model(inputs).square().mean()
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        self.assertIsNotNone(batch_norm.weight.grad)
        self.assertIsNotNone(batch_norm.bias.grad)
        self.assertGreater(float(batch_norm.weight.grad.abs().sum()), 0.0)
        self.assertGreater(float(batch_norm.bias.grad.abs().sum()), 0.0)
        optimizer.step()

        torch.testing.assert_close(batch_norm.running_mean, running_mean)
        torch.testing.assert_close(batch_norm.running_var, running_var)
        torch.testing.assert_close(batch_norm.num_batches_tracked, batches_tracked)
        self.assertFalse(
            torch.equal(batch_norm.weight.detach(), affine_before[0])
            and torch.equal(batch_norm.bias.detach(), affine_before[1])
        )

    def test_cli_freezes_batch_norm_stats_by_default_with_opt_out(self) -> None:
        parser = trainer.build_parser()
        default_args = parser.parse_args(
            ["train", "--research-root", "/tmp/research"]
        )
        opt_out_args = parser.parse_args(
            [
                "train",
                "--research-root",
                "/tmp/research",
                "--no-freeze-batch-norm-stats",
            ]
        )
        self.assertTrue(default_args.freeze_batch_norm_stats)
        self.assertFalse(opt_out_args.freeze_batch_norm_stats)


class ExportPrecisionTests(unittest.TestCase):
    def test_fp16_export_preserves_nonzero_numerical_floors(self) -> None:
        values = np.asarray(
            [0.0, 1e-12, -1e-12, 1e-4, -1e-4],
            dtype=np.float32,
        )
        converted = trainer.fp16_without_zero_underflow(values)

        self.assertEqual(converted.dtype, np.float16)
        self.assertEqual(float(converted[0]), 0.0)
        self.assertGreater(float(converted[1]), 0.0)
        self.assertLess(float(converted[2]), 0.0)
        self.assertNotEqual(float(converted[3]), 0.0)
        self.assertNotEqual(float(converted[4]), 0.0)


class AudioPreflightTests(unittest.TestCase):
    @staticmethod
    def write_wave(path: Path, samples: np.ndarray) -> None:
        with wave.open(str(path), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(trainer.SAMPLE_RATE)
            output.writeframes(
                np.clip(samples * 32767.0, -32768, 32767)
                .astype("<i2")
                .tobytes()
            )

    def test_preflight_excludes_silent_and_corrupt_without_zero_fill(self) -> None:
        trainer.DEFAULT_OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=trainer.DEFAULT_OUTPUT_ROOT) as temporary:
            root = Path(temporary)
            time_axis = np.arange(trainer.SAMPLE_RATE, dtype=np.float32)
            valid_wave = 0.1 * np.sin(2 * np.pi * 440.0 * time_axis / trainer.SAMPLE_RATE)
            valid = root / "valid.wav"
            silent = root / "silent.wav"
            corrupt = root / "corrupt.wav"
            self.write_wave(valid, valid_wave)
            self.write_wave(silent, np.zeros_like(valid_wave))
            corrupt.write_bytes(b"not audio")
            cache = root / "preflight.parquet"

            valid_paths, report = trainer.preflight_audio_paths(
                [str(valid), str(silent), str(corrupt)],
                cache_path=cache,
                workers=1,
            )
            self.assertEqual(valid_paths, {str(valid.resolve())})
            self.assertEqual(report["invalid_paths"], 2)
            self.assertEqual(report["zero_substitutions"], 0)

            _, cached_report = trainer.preflight_audio_paths(
                [str(valid), str(silent), str(corrupt)],
                cache_path=cache,
                workers=1,
            )
            self.assertEqual(cached_report["newly_probed"], 0)
            self.assertEqual(cached_report["reused_cache_entries"], 3)


if __name__ == "__main__":
    unittest.main()
