from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch


SCRIPT = Path(__file__).resolve().parents[1] / "export_universal_semantic_head.py"
SPEC = importlib.util.spec_from_file_location("export_universal_semantic_head", SCRIPT)
assert SPEC and SPEC.loader
semantic = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = semantic
SPEC.loader.exec_module(semantic)


EXPECTED_OUTPUT_IDS = [
    "content.music",
    "content.speech",
    "content.sound_effects",
    "content.instrumental",
    "content.novelty_proxy",
    "energy.low",
    "energy.high",
    "mood.happy",
    "mood.funny",
    "mood.sad",
    "mood.tender",
    "mood.exciting",
    "mood.angry",
    "mood.scary",
    "genre.international",
    "genre.pop",
    "genre.rock",
    "genre.electronic",
    "genre.folk",
    "genre.hip_hop",
    "genre.experimental",
    "genre.metal",
    "genre.jazz_blues",
    "genre.classical",
    "genre.reggae",
    "genre.country",
    "genre.ambient_soundtrack",
]


def fake_parameters() -> tuple[
    semantic.FmaHeadParameters,
    semantic.AudioSetHeadParameters,
]:
    fma_names = tuple(
        dict.fromkeys(
            [
                *semantic.required_fma_labels(),
                "Soundtrack",
                "Ambient",
            ]
        )
    )
    fma_count = len(fma_names)
    fma = semantic.FmaHeadParameters(
        weights=np.zeros((fma_count, semantic.EMBEDDING_DIM), dtype=np.float32),
        biases=np.zeros(fma_count, dtype=np.float32),
        calibration_scales=np.ones(fma_count, dtype=np.float32),
        calibration_biases=np.zeros(fma_count, dtype=np.float32),
        parent_indices=np.arange(fma_count, dtype=np.int64),
        child_mask=np.zeros(fma_count, dtype=np.bool_),
        label_names=fma_names,
        label_metadata=tuple(
            {
                "name": name,
                "kind": "broad",
                "abstention": {
                    "threshold": 0.4,
                    "target_precision": 0.8,
                    "met_target_on_validation": name != "Pop",
                    "validation_precision": 0.81,
                    "validation_recall": 0.5,
                },
            }
            for name in fma_names
        ),
    )

    audio_names = semantic.required_audioset_labels()
    hidden = 4
    audio = semantic.AudioSetHeadParameters(
        first_weight=np.zeros(
            (hidden, semantic.EMBEDDING_DIM), dtype=np.float32
        ),
        first_bias=np.zeros(hidden, dtype=np.float32),
        second_weight=np.zeros((len(audio_names), hidden), dtype=np.float32),
        second_bias=np.asarray(
            [
                {
                    "Music": 2.0,
                    "Speech": 1.0,
                    "Sound effect": 0.5,
                    "Funny music": 0.25,
                }.get(name, -2.0)
                for name in audio_names
            ],
            dtype=np.float32,
        ),
        label_names=audio_names,
    )
    return fma, audio


class ContractTests(unittest.TestCase):
    def test_output_order_is_stable(self) -> None:
        self.assertEqual(
            [spec.id for spec in semantic.OUTPUT_SPECS],
            EXPECTED_OUTPUT_IDS,
        )
        self.assertEqual(len(semantic.OUTPUT_SPECS), 27)

    def test_novelty_is_explicitly_a_proxy(self) -> None:
        novelty = next(
            spec
            for spec in semantic.OUTPUT_SPECS
            if spec.id == "content.novelty_proxy"
        )
        self.assertIn("not a meme", novelty.description.lower())
        self.assertNotIn("Video game music", novelty.audioset_labels)

    def test_missing_source_label_is_rejected(self) -> None:
        fma, audio = fake_parameters()
        broken = semantic.AudioSetHeadParameters(
            first_weight=audio.first_weight,
            first_bias=audio.first_bias,
            second_weight=audio.second_weight[:-1],
            second_bias=audio.second_bias[:-1],
            label_names=audio.label_names[:-1],
        )
        with self.assertRaisesRegex(ValueError, "source labels are missing"):
            semantic.build_hybrid_model(fma, broken)


class ModelTests(unittest.TestCase):
    def test_hybrid_scores_are_finite_bounded_and_follow_contract(self) -> None:
        fma, audio = fake_parameters()
        model = semantic.build_hybrid_model(fma, audio)
        embedding = torch.zeros((3, semantic.EMBEDDING_DIM), dtype=torch.float32)
        embedding[1, 0] = 1.0
        embedding[2, 1] = -1.0
        with torch.no_grad():
            output = model(embedding).numpy()
        self.assertEqual(output.shape, (3, 27))
        self.assertTrue(np.isfinite(output).all())
        self.assertTrue((output >= 0.0).all())
        self.assertTrue((output <= 1.0).all())

        music_index = EXPECTED_OUTPUT_IDS.index("content.music")
        expected_music = 1.0 / (1.0 + np.exp(-2.0))
        np.testing.assert_allclose(
            output[:, music_index],
            expected_music,
            rtol=0.0,
            atol=1e-6,
        )
        # Every synthetic calibrated FMA broad output is sigmoid(0) = 0.5.
        rock_index = EXPECTED_OUTPUT_IDS.index("genre.rock")
        np.testing.assert_allclose(
            output[:, rock_index],
            0.5,
            rtol=0.0,
            atol=1e-6,
        )

    def test_only_exact_fma_outputs_inherit_a_threshold(self) -> None:
        fma, _ = fake_parameters()
        labels = semantic.build_label_metadata(fma)
        by_id = {item["id"]: item for item in labels}
        self.assertEqual(
            by_id["genre.rock"]["threshold"]["aggregate_threshold"],
            0.4,
        )
        self.assertIsNone(
            by_id["genre.pop"]["threshold"]["aggregate_threshold"]
        )
        self.assertEqual(
            by_id["genre.pop"]["threshold"]["status"],
            "abstain",
        )
        self.assertIsNone(
            by_id["content.novelty_proxy"]["threshold"]["aggregate_threshold"]
        )
        self.assertEqual(
            by_id["content.novelty_proxy"]["score_semantics"],
            "routing_score",
        )

    def test_end_to_end_fp32_onnx_parity(self) -> None:
        fma, audio = fake_parameters()
        model = semantic.build_hybrid_model(fma, audio)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "semantic.onnx"
            report = semantic.export_onnx(
                model,
                output,
                opset=17,
                fp16=False,
            )
            self.assertTrue(output.is_file())
            self.assertLess(
                report["onnx_parity_maximum_absolute_error"],
                1e-5,
            )

    def test_fp16_conversion_preserves_nonzero_floors(self) -> None:
        values = np.asarray([0.0, 1e-12, -1e-12, 0.5], dtype=np.float32)
        converted = semantic.fp16_without_zero_underflow(values)
        self.assertEqual(float(converted[0]), 0.0)
        self.assertNotEqual(float(converted[1]), 0.0)
        self.assertNotEqual(float(converted[2]), 0.0)
        self.assertGreater(float(converted[1]), 0.0)
        self.assertLess(float(converted[2]), 0.0)


if __name__ == "__main__":
    unittest.main()
