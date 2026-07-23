#!/usr/bin/env python3
"""Export one compact, provenance-preserving semantic head for LatentJam.

The shipped 960-dimensional audio encoder preserves the pre-classifier
representation of EfficientAT MN10.  Two useful heads already exist for that
representation:

* a calibrated, artist-disjoint FMA genre head; and
* EfficientAT's public AudioSet classifier.

This exporter combines those heads without pretending that the local
music-only corpora can supervise speech, sound effects, or memes.  The FMA
branch is copied exactly.  The AudioSet branch retains the MN10 hidden
projection but prunes its final layer to the source classes required by the
stable product taxonomy below.  Aggregate outputs are routing scores, not
newly calibrated probabilities.

In particular, ``content.novelty_proxy`` is deliberately named a proxy.  It
combines funny-music, speech-synthesizer, laughter, jingle, and sound-effect
evidence.  It is not a learned "meme" label and must be corroborated by
metadata before a mix is named "Meme & Viral Audio".

No personal-library examples are used to create weights, labels, thresholds,
or aggregation rules.  An optional phone embedding store is distributionally
audited only after the ONNX graph has been frozen.

Example:

    /path/to/python tools/research/export_universal_semantic_head.py \
      --fma-head /private/tmp/latentjam-fma-tagger-v1/mnv4_fma_hierarchical_v1.onnx \
      --fma-metadata /private/tmp/latentjam-fma-tagger-v1/mnv4_fma_hierarchical_v1.metadata.json \
      --efficientat-checkpoint /path/to/models/efficientat/mn10_as_mAP_471.pt \
      --audioset-labels /path/to/EfficientAT/metadata/class_labels_indices.csv \
      --output /private/tmp/latentjam-universal-semantic-v1.onnx \
      --phone-validation-store /path/to/personal_mnv4_distilled.parquet
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import platform
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

import numpy as np


EMBEDDING_DIM = 960
DEFAULT_FEATURE_SCALE = 2.5
DEFAULT_MODEL_NAME = "mnv4_universal_semantic_hybrid_v1"


@dataclass(frozen=True)
class SemanticOutputSpec:
    """One stable output and its auditable source components."""

    id: str
    display_name: str
    family: str
    formula: str = "max"
    fma_labels: tuple[str, ...] = ()
    audioset_labels: tuple[str, ...] = ()
    description: str = ""


# This order is an app-facing contract. Append-only changes require a new model
# and schema version.
OUTPUT_SPECS: tuple[SemanticOutputSpec, ...] = (
    SemanticOutputSpec(
        "content.music",
        "Music",
        "content",
        audioset_labels=("Music",),
        description="AudioSet music score.",
    ),
    SemanticOutputSpec(
        "content.speech",
        "Speech",
        "content",
        audioset_labels=(
            "Speech",
            "Conversation",
            "Narration, monologue",
            "Speech synthesizer",
        ),
        description="Maximum score across general speech and speech-form classes.",
    ),
    SemanticOutputSpec(
        "content.sound_effects",
        "Sound Effects",
        "content",
        audioset_labels=(
            "Beep, bleep",
            "Ping",
            "Ding",
            "Clang",
            "Squeal",
            "Creak",
            "Whir",
            "Clatter",
            "Clicking",
            "Rumble",
            "Plop",
            "Jingle, tinkle",
            "Zing",
            "Boing",
            "Crunch",
            "Sound effect",
            "Noise",
            "Environmental noise",
        ),
        description="Maximum score across explicit effects, effect-like transients, and noise.",
    ),
    SemanticOutputSpec(
        "content.instrumental",
        "Instrumental",
        "content",
        formula="instrumental",
        fma_labels=("Instrumental",),
        audioset_labels=(
            "Music",
            "Musical instrument",
            "Singing",
            "Male singing",
            "Female singing",
            "Child singing",
            "Synthetic singing",
            "Rapping",
            "Vocal music",
        ),
        description=(
            "Maximum of the calibrated FMA Instrumental score and an AudioSet "
            "music/instrument score attenuated by vocal evidence."
        ),
    ),
    SemanticOutputSpec(
        "content.novelty_proxy",
        "Novelty Proxy",
        "content",
        audioset_labels=(
            "Funny music",
            "Speech synthesizer",
            "Laughter",
            "Jingle (music)",
            "Sound effect",
        ),
        description=(
            "Weak acoustic novelty evidence. This is not a meme or viral-audio "
            "classifier and requires metadata corroboration."
        ),
    ),
    SemanticOutputSpec(
        "energy.low",
        "Low Energy",
        "energy",
        audioset_labels=(
            "Tender music",
            "Sad music",
            "Ambient music",
            "New-age music",
            "Lullaby",
        ),
        description="Proxy from low-arousal AudioSet music classes.",
    ),
    SemanticOutputSpec(
        "energy.high",
        "High Energy",
        "energy",
        audioset_labels=(
            "Exciting music",
            "Angry music",
            "Dance music",
            "Electronic dance music",
            "Drum and bass",
            "Heavy metal",
        ),
        description="Proxy from high-arousal AudioSet music classes.",
    ),
    SemanticOutputSpec(
        "mood.happy",
        "Happy",
        "mood",
        audioset_labels=("Happy music",),
    ),
    SemanticOutputSpec(
        "mood.funny",
        "Funny",
        "mood",
        audioset_labels=("Funny music",),
    ),
    SemanticOutputSpec(
        "mood.sad",
        "Sad",
        "mood",
        audioset_labels=("Sad music",),
    ),
    SemanticOutputSpec(
        "mood.tender",
        "Tender",
        "mood",
        audioset_labels=("Tender music",),
    ),
    SemanticOutputSpec(
        "mood.exciting",
        "Exciting",
        "mood",
        audioset_labels=("Exciting music",),
    ),
    SemanticOutputSpec(
        "mood.angry",
        "Angry",
        "mood",
        audioset_labels=("Angry music",),
    ),
    SemanticOutputSpec(
        "mood.scary",
        "Scary",
        "mood",
        audioset_labels=("Scary music",),
    ),
    SemanticOutputSpec(
        "genre.international",
        "International",
        "genre",
        fma_labels=("International",),
        description="Calibrated FMA broad-genre probability.",
    ),
    SemanticOutputSpec(
        "genre.pop",
        "Pop",
        "genre",
        fma_labels=("Pop",),
        description="Calibrated FMA broad-genre probability.",
    ),
    SemanticOutputSpec(
        "genre.rock",
        "Rock",
        "genre",
        fma_labels=("Rock",),
        description="Calibrated FMA broad-genre probability.",
    ),
    SemanticOutputSpec(
        "genre.electronic",
        "Electronic",
        "genre",
        fma_labels=("Electronic",),
        description="Calibrated FMA broad-genre probability.",
    ),
    SemanticOutputSpec(
        "genre.folk",
        "Folk",
        "genre",
        fma_labels=("Folk",),
        description="Calibrated FMA broad-genre probability.",
    ),
    SemanticOutputSpec(
        "genre.hip_hop",
        "Hip-Hop",
        "genre",
        fma_labels=("Hip-Hop",),
        description="Calibrated FMA broad-genre probability.",
    ),
    SemanticOutputSpec(
        "genre.experimental",
        "Experimental",
        "genre",
        fma_labels=("Experimental",),
        description="Calibrated FMA broad-genre probability.",
    ),
    SemanticOutputSpec(
        "genre.metal",
        "Metal",
        "genre",
        audioset_labels=("Heavy metal",),
    ),
    SemanticOutputSpec(
        "genre.jazz_blues",
        "Jazz & Blues",
        "genre",
        audioset_labels=("Jazz", "Blues", "Swing music"),
    ),
    SemanticOutputSpec(
        "genre.classical",
        "Classical",
        "genre",
        audioset_labels=("Classical music", "Opera", "Orchestra"),
    ),
    SemanticOutputSpec(
        "genre.reggae",
        "Reggae",
        "genre",
        audioset_labels=("Reggae",),
    ),
    SemanticOutputSpec(
        "genre.country",
        "Country",
        "genre",
        audioset_labels=("Country",),
    ),
    SemanticOutputSpec(
        "genre.ambient_soundtrack",
        "Ambient & Soundtrack",
        "genre",
        audioset_labels=(
            "Ambient music",
            "New-age music",
            "Background music",
            "Theme music",
            "Soundtrack music",
            "Video game music",
        ),
    ),
)


@dataclass(frozen=True)
class FmaHeadParameters:
    weights: np.ndarray
    biases: np.ndarray
    calibration_scales: np.ndarray
    calibration_biases: np.ndarray
    parent_indices: np.ndarray
    child_mask: np.ndarray
    label_names: tuple[str, ...]
    label_metadata: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class AudioSetHeadParameters:
    first_weight: np.ndarray
    first_bias: np.ndarray
    second_weight: np.ndarray
    second_bias: np.ndarray
    label_names: tuple[str, ...]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fma-head", type=Path, required=True)
    parser.add_argument("--fma-metadata", type=Path, required=True)
    parser.add_argument("--efficientat-checkpoint", type=Path, required=True)
    parser.add_argument("--audioset-labels", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata-output", type=Path)
    parser.add_argument("--phone-validation-store", type=Path)
    parser.add_argument("--model-name", default=DEFAULT_MODEL_NAME)
    parser.add_argument("--feature-scale", type=float, default=DEFAULT_FEATURE_SCALE)
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument(
        "--fp16",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Use FP16 internal tensors while retaining float32 model I/O.",
    )
    return parser.parse_args()


def require_file(path: Path, description: str) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise FileNotFoundError(f"{description} does not exist: {resolved}")
    return resolved


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_safe(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return float(value)
    if isinstance(value, Path):
        return str(value)
    return value


def load_fma_head(model_path: Path, metadata_path: Path) -> tuple[FmaHeadParameters, dict[str, Any]]:
    import onnx
    from onnx import numpy_helper

    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    expected_hash = metadata.get("model", {}).get("sha256")
    actual_hash = sha256_file(model_path)
    if expected_hash and str(expected_hash) != actual_hash:
        raise ValueError(
            "FMA metadata/model hash mismatch: "
            f"expected {expected_hash}, found {actual_hash}"
        )

    graph = onnx.load(str(model_path))
    initializers = {
        item.name: numpy_helper.to_array(item) for item in graph.graph.initializer
    }
    required = {
        "weights",
        "biases",
        "calibration_scales",
        "calibration_biases",
        "parent_indices",
    }
    missing = sorted(required.difference(initializers))
    if missing:
        raise ValueError(f"FMA ONNX head is missing initializers: {missing}")

    labels = tuple(metadata.get("labels", ()))
    label_names = tuple(str(item["name"]) for item in labels)
    if len(label_names) != int(initializers["weights"].shape[0]):
        raise ValueError("FMA metadata label count does not match ONNX output width")
    if int(initializers["weights"].shape[1]) != EMBEDDING_DIM:
        raise ValueError(
            f"FMA head expects {initializers['weights'].shape[1]} dimensions, "
            f"not {EMBEDDING_DIM}"
        )
    child_mask = np.asarray(
        [str(item.get("kind")) == "child" for item in labels],
        dtype=np.bool_,
    )
    parameters = FmaHeadParameters(
        weights=np.array(initializers["weights"], dtype=np.float32, copy=True),
        biases=np.array(initializers["biases"], dtype=np.float32, copy=True),
        calibration_scales=np.array(
            initializers["calibration_scales"], dtype=np.float32, copy=True
        ),
        calibration_biases=np.array(
            initializers["calibration_biases"], dtype=np.float32, copy=True
        ),
        parent_indices=np.array(
            initializers["parent_indices"], dtype=np.int64, copy=True
        ),
        child_mask=child_mask,
        label_names=label_names,
        label_metadata=labels,
    )
    return parameters, metadata


def load_audioset_labels(path: Path) -> tuple[str, ...]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows or "index" not in rows[0] or "display_name" not in rows[0]:
        raise ValueError("AudioSet label CSV needs index and display_name columns")
    indexed = sorted(
        ((int(row["index"]), str(row["display_name"])) for row in rows),
        key=lambda item: item[0],
    )
    expected = list(range(len(indexed)))
    observed = [index for index, _ in indexed]
    if observed != expected:
        raise ValueError("AudioSet label indices must be contiguous and zero-based")
    names = tuple(name for _, name in indexed)
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise ValueError(f"AudioSet display names are not unique: {duplicates}")
    return names


def load_audioset_head(checkpoint_path: Path, labels_path: Path) -> AudioSetHeadParameters:
    import torch

    labels = load_audioset_labels(labels_path)
    state = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    keys = (
        "classifier.2.weight",
        "classifier.2.bias",
        "classifier.5.weight",
        "classifier.5.bias",
    )
    missing = [key for key in keys if key not in state]
    if missing:
        raise ValueError(f"EfficientAT checkpoint is missing tensors: {missing}")
    arrays = tuple(state[key].detach().cpu().float().numpy() for key in keys)
    first_weight, first_bias, second_weight, second_bias = arrays
    if tuple(first_weight.shape[1:]) != (EMBEDDING_DIM,):
        raise ValueError(
            f"EfficientAT classifier expects {first_weight.shape[1]} dimensions, "
            f"not {EMBEDDING_DIM}"
        )
    if second_weight.shape[0] != len(labels):
        raise ValueError(
            "EfficientAT classifier output width does not match AudioSet labels"
        )
    if second_weight.shape[1] != first_weight.shape[0]:
        raise ValueError("EfficientAT classifier hidden dimensions do not match")
    return AudioSetHeadParameters(
        first_weight=np.asarray(first_weight, dtype=np.float32),
        first_bias=np.asarray(first_bias, dtype=np.float32),
        second_weight=np.asarray(second_weight, dtype=np.float32),
        second_bias=np.asarray(second_bias, dtype=np.float32),
        label_names=labels,
    )


def required_fma_labels(specs: Sequence[SemanticOutputSpec] = OUTPUT_SPECS) -> tuple[str, ...]:
    return tuple(
        sorted({name for spec in specs for name in spec.fma_labels})
    )


def required_audioset_labels(
    specs: Sequence[SemanticOutputSpec] = OUTPUT_SPECS,
) -> tuple[str, ...]:
    return tuple(
        sorted({name for spec in specs for name in spec.audioset_labels})
    )


def validate_source_labels(
    fma: FmaHeadParameters,
    audioset: AudioSetHeadParameters,
    specs: Sequence[SemanticOutputSpec] = OUTPUT_SPECS,
) -> None:
    missing_fma = sorted(set(required_fma_labels(specs)).difference(fma.label_names))
    missing_audio = sorted(
        set(required_audioset_labels(specs)).difference(audioset.label_names)
    )
    if missing_fma or missing_audio:
        messages = []
        if missing_fma:
            messages.append(f"FMA={missing_fma}")
        if missing_audio:
            messages.append(f"AudioSet={missing_audio}")
        raise ValueError("semantic taxonomy source labels are missing: " + ", ".join(messages))


def build_hybrid_model(
    fma: FmaHeadParameters,
    audioset: AudioSetHeadParameters,
    *,
    feature_scale: float = DEFAULT_FEATURE_SCALE,
    specs: Sequence[SemanticOutputSpec] = OUTPUT_SPECS,
) -> Any:
    """Build the exact hybrid Torch module used for ONNX export."""

    import torch
    import torch.nn.functional as torch_functional

    if not math.isfinite(feature_scale) or feature_scale <= 0.0:
        raise ValueError("feature_scale must be finite and positive")
    validate_source_labels(fma, audioset, specs)

    fma_index = {name: index for index, name in enumerate(fma.label_names)}
    audio_full_index = {
        name: index for index, name in enumerate(audioset.label_names)
    }
    selected_audio_names = tuple(
        sorted(required_audioset_labels(specs), key=audio_full_index.__getitem__)
    )
    selected_audio_rows = np.asarray(
        [audio_full_index[name] for name in selected_audio_names], dtype=np.int64
    )
    audio_index = {
        name: index for index, name in enumerate(selected_audio_names)
    }

    class UniversalSemanticHybrid(torch.nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.feature_scale = float(feature_scale)
            self.register_buffer("fma_weights", torch.from_numpy(fma.weights))
            self.register_buffer("fma_biases", torch.from_numpy(fma.biases))
            self.register_buffer(
                "fma_calibration_scales",
                torch.from_numpy(fma.calibration_scales),
            )
            self.register_buffer(
                "fma_calibration_biases",
                torch.from_numpy(fma.calibration_biases),
            )
            self.register_buffer(
                "fma_parent_indices",
                torch.from_numpy(fma.parent_indices),
            )
            self.register_buffer(
                "fma_child_mask",
                torch.from_numpy(fma.child_mask),
            )
            self.register_buffer(
                "audio_first_weight",
                torch.from_numpy(audioset.first_weight),
            )
            self.register_buffer(
                "audio_first_bias",
                torch.from_numpy(audioset.first_bias),
            )
            self.register_buffer(
                "audio_second_weight",
                torch.from_numpy(audioset.second_weight[selected_audio_rows]),
            )
            self.register_buffer(
                "audio_second_bias",
                torch.from_numpy(audioset.second_bias[selected_audio_rows]),
            )

        @staticmethod
        def maximum_columns(matrix: Any, positions: Sequence[int]) -> Any:
            columns = [matrix[:, int(position)] for position in positions]
            if len(columns) == 1:
                return columns[0]
            return torch.amax(torch.stack(columns, dim=1), dim=1)

        def forward(self, embedding: Any) -> Any:
            normalized = torch_functional.normalize(
                embedding, p=2.0, dim=1, eps=1e-12
            )

            fma_logits = torch_functional.linear(
                normalized, self.fma_weights, self.fma_biases
            )
            fma_probability = torch.sigmoid(
                fma_logits * self.fma_calibration_scales
                + self.fma_calibration_biases
            )
            fma_parent_probability = torch.index_select(
                fma_probability, 1, self.fma_parent_indices
            )
            fma_probability = torch.where(
                self.fma_child_mask.unsqueeze(0),
                torch.minimum(fma_probability, fma_parent_probability),
                fma_probability,
            )

            # MN10 was trained with a non-unit feature norm. LatentJam stores
            # normalized embeddings, and the fixed scale below was selected on
            # public FMA validation data by benchmark_audio_tag_head.py.
            projected = torch_functional.linear(
                normalized, self.audio_first_weight, None
            )
            hidden = torch_functional.hardswish(
                projected * self.feature_scale + self.audio_first_bias
            )
            audio_probability = torch.sigmoid(
                torch_functional.linear(
                    hidden,
                    self.audio_second_weight,
                    self.audio_second_bias,
                )
            )

            outputs = []
            for spec in specs:
                fma_positions = tuple(fma_index[name] for name in spec.fma_labels)
                audio_positions = tuple(
                    audio_index[name] for name in spec.audioset_labels
                )
                if spec.formula == "instrumental":
                    music = audio_probability[:, audio_index["Music"]]
                    instrument = audio_probability[:, audio_index["Musical instrument"]]
                    vocal = self.maximum_columns(
                        audio_probability,
                        tuple(
                            audio_index[name]
                            for name in (
                                "Singing",
                                "Male singing",
                                "Female singing",
                                "Child singing",
                                "Synthetic singing",
                                "Rapping",
                                "Vocal music",
                            )
                        ),
                    )
                    audio_instrumental = (
                        torch.minimum(music, instrument) * (1.0 - vocal)
                    )
                    candidates = [audio_instrumental]
                    candidates.extend(
                        fma_probability[:, position] for position in fma_positions
                    )
                    score = (
                        candidates[0]
                        if len(candidates) == 1
                        else torch.amax(torch.stack(candidates, dim=1), dim=1)
                    )
                elif spec.formula == "max":
                    candidates = [
                        *(fma_probability[:, position] for position in fma_positions),
                        *(
                            audio_probability[:, position]
                            for position in audio_positions
                        ),
                    ]
                    if not candidates:
                        raise RuntimeError(f"output {spec.id} has no source components")
                    score = (
                        candidates[0]
                        if len(candidates) == 1
                        else torch.amax(torch.stack(candidates, dim=1), dim=1)
                    )
                else:
                    raise RuntimeError(
                        f"unsupported semantic aggregation formula: {spec.formula}"
                    )
                outputs.append(score)
            return torch.stack(outputs, dim=1)

    return UniversalSemanticHybrid().eval()


def fp16_without_zero_underflow(values: np.ndarray) -> np.ndarray:
    """Cast to FP16 without turning a non-zero numerical floor into zero."""

    converted = values.astype(np.float16)
    underflowed = (values != 0) & (converted == 0)
    if np.any(underflowed):
        floor = np.float16(1e-7)
        converted = np.where(
            underflowed,
            np.copysign(floor, values),
            converted,
        ).astype(np.float16)
    return converted


def convert_onnx_initializers_to_fp16(model: Any) -> Any:
    """Convert graph internals to FP16 while preserving float32 public I/O."""

    import onnx
    from onnx import TensorProto, numpy_helper

    for index, initializer in enumerate(model.graph.initializer):
        if initializer.data_type == TensorProto.FLOAT:
            values = fp16_without_zero_underflow(
                numpy_helper.to_array(initializer)
            )
            model.graph.initializer[index].CopyFrom(
                numpy_helper.from_array(values, initializer.name)
            )
    for node in model.graph.node:
        for attribute in node.attribute:
            if (
                attribute.type == onnx.AttributeProto.TENSOR
                and attribute.t.data_type == TensorProto.FLOAT
            ):
                values = fp16_without_zero_underflow(
                    numpy_helper.to_array(attribute.t)
                )
                attribute.t.CopyFrom(
                    numpy_helper.from_array(values, attribute.t.name)
                )

    original_input = model.graph.input[0].name
    internal_input = original_input + "_fp16"
    for node in model.graph.node:
        for index, name in enumerate(node.input):
            if name == original_input:
                node.input[index] = internal_input
    model.graph.node.insert(
        0,
        onnx.helper.make_node(
            "Cast",
            [original_input],
            [internal_input],
            name="cast_embedding_to_fp16",
            to=TensorProto.FLOAT16,
        ),
    )

    original_output = model.graph.output[0].name
    internal_output = original_output + "_fp16"
    producer_found = False
    for node in model.graph.node:
        for index, name in enumerate(node.output):
            if name == original_output:
                node.output[index] = internal_output
                producer_found = True
    if not producer_found:
        raise ValueError(f"could not find ONNX producer for {original_output}")
    model.graph.node.append(
        onnx.helper.make_node(
            "Cast",
            [internal_output],
            [original_output],
            name="cast_semantic_scores_to_fp32",
            to=TensorProto.FLOAT,
        )
    )
    model.graph.output[0].CopyFrom(
        onnx.helper.make_tensor_value_info(
            original_output,
            TensorProto.FLOAT,
            ["batch", len(OUTPUT_SPECS)],
        )
    )
    return model


def export_onnx(
    model: Any,
    output_path: Path,
    *,
    opset: int,
    fp16: bool,
) -> dict[str, Any]:
    import onnx
    import onnxruntime as ort
    import torch

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = (
        output_path.with_name(output_path.stem + ".fp32.onnx")
        if fp16
        else output_path
    )
    rng = np.random.default_rng(20260723)
    parity_input = rng.normal(size=(17, EMBEDDING_DIM)).astype(np.float32)
    parity_input[0] = 0.0
    with torch.no_grad():
        torch_output = model(torch.from_numpy(parity_input)).cpu().numpy()
    if not np.isfinite(torch_output).all():
        raise RuntimeError("Torch semantic head emitted non-finite values")
    torch.onnx.export(
        model,
        torch.from_numpy(parity_input[:2]),
        str(temporary),
        input_names=["embedding"],
        output_names=["semantic_scores"],
        dynamic_axes={
            "embedding": {0: "batch"},
            "semantic_scores": {0: "batch"},
        },
        opset_version=opset,
        do_constant_folding=True,
        dynamo=False,
    )
    if fp16:
        graph = onnx.load(str(temporary))
        graph = convert_onnx_initializers_to_fp16(graph)
        onnx.checker.check_model(graph)
        onnx.save(graph, str(output_path), save_as_external_data=False)
        temporary.unlink()

    graph = onnx.load(str(output_path))
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(
        str(output_path), providers=["CPUExecutionProvider"]
    )
    onnx_output = session.run(
        ["semantic_scores"], {"embedding": parity_input}
    )[0]
    if not np.isfinite(onnx_output).all():
        raise RuntimeError("ONNX semantic head emitted non-finite values")
    maximum_error = float(np.max(np.abs(torch_output - onnx_output)))
    tolerance = 5e-3 if fp16 else 1e-5
    if maximum_error > tolerance:
        raise RuntimeError(
            "ONNX parity check failed: "
            f"maximum error {maximum_error:.6g} exceeds {tolerance}"
        )
    if onnx_output.shape != (len(parity_input), len(OUTPUT_SPECS)):
        raise RuntimeError(
            f"unexpected ONNX output shape: {onnx_output.shape}"
        )
    return {
        "onnx_parity_maximum_absolute_error": maximum_error,
        "onnx_parity_tolerance": tolerance,
        "fp16_internal": fp16,
    }


def fma_threshold_metadata(
    spec: SemanticOutputSpec,
    fma: FmaHeadParameters,
) -> dict[str, Any]:
    if len(spec.fma_labels) != 1 or spec.audioset_labels or spec.formula != "max":
        return {
            "aggregate_threshold": None,
            "status": "requires_product_validation",
            "reason": (
                "Aggregate AudioSet-derived routing scores are not calibrated "
                "decision probabilities."
            ),
        }
    name = spec.fma_labels[0]
    row = fma.label_metadata[fma.label_names.index(name)]
    abstention = dict(row.get("abstention", {}))
    enabled = bool(abstention.get("met_target_on_validation", False))
    return {
        "aggregate_threshold": (
            float(abstention["threshold"])
            if enabled and "threshold" in abstention
            else None
        ),
        "status": "enabled" if enabled else "abstain",
        "source": "FMA official validation split",
        "target_precision": abstention.get("target_precision"),
        "validation_precision": abstention.get("validation_precision"),
        "validation_recall": abstention.get("validation_recall"),
        "warning": (
            "Threshold was calibrated for the frozen FMA head. Revalidate after "
            "any encoder change and use cluster-level support before naming."
        ),
    }


def build_label_metadata(
    fma: FmaHeadParameters,
    specs: Sequence[SemanticOutputSpec] = OUTPUT_SPECS,
) -> list[dict[str, Any]]:
    labels = []
    for index, spec in enumerate(specs):
        exact_fma_probability = (
            len(spec.fma_labels) == 1
            and not spec.audioset_labels
            and spec.formula == "max"
        )
        labels.append(
            {
                "index": index,
                "id": spec.id,
                "display_name": spec.display_name,
                "family": spec.family,
                "formula": spec.formula,
                "score_semantics": (
                    "calibrated_probability"
                    if exact_fma_probability
                    else "routing_score"
                ),
                "description": spec.description,
                "sources": {
                    "fma_labels": list(spec.fma_labels),
                    "audioset_labels": list(spec.audioset_labels),
                },
                "threshold": fma_threshold_metadata(spec, fma),
            }
        )
    return labels


def validate_phone_store(
    store_path: Path,
    model_path: Path,
    labels: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    """Audit a phone store after export without returning personal rows."""

    import onnxruntime as ort
    import pandas as pd

    frame = pd.read_parquet(store_path)
    if "embedding" not in frame:
        raise ValueError("phone validation store is missing embedding")
    valid_vectors = []
    invalid = 0
    for item in frame["embedding"]:
        vector = np.asarray(item, dtype=np.float32)
        if (
            vector.shape != (EMBEDDING_DIM,)
            or not np.isfinite(vector).all()
            or float(np.linalg.norm(vector)) <= 1e-12
        ):
            invalid += 1
            continue
        valid_vectors.append(vector)
    if not valid_vectors:
        raise ValueError("phone validation store contains no valid 960-d embeddings")
    matrix = np.stack(valid_vectors)
    session = ort.InferenceSession(
        str(model_path), providers=["CPUExecutionProvider"]
    )
    batches = []
    for start in range(0, len(matrix), 256):
        batches.append(
            session.run(
                ["semantic_scores"],
                {"embedding": matrix[start : start + 256]},
            )[0]
        )
    scores = np.concatenate(batches, axis=0)
    distributions = {}
    for label in labels:
        column = scores[:, int(label["index"])]
        distributions[str(label["id"])] = {
            "mean": float(np.mean(column)),
            "p50": float(np.quantile(column, 0.50)),
            "p90": float(np.quantile(column, 0.90)),
            "p99": float(np.quantile(column, 0.99)),
            "maximum": float(np.max(column)),
        }
    versions = (
        sorted(str(item) for item in frame["model_version"].dropna().unique())
        if "model_version" in frame
        else []
    )
    return {
        "purpose": "post_export_distribution_audit_only",
        "used_for_training": False,
        "used_for_label_or_threshold_selection": False,
        "store_sha256": sha256_file(store_path),
        "rows": int(len(frame)),
        "valid_embeddings": int(len(matrix)),
        "invalid_embeddings": invalid,
        "embedding_model_versions": versions,
        "score_distributions": distributions,
        "personal_track_identifiers_exported": False,
    }


def build_metadata(
    *,
    args: argparse.Namespace,
    output_path: Path,
    fma_path: Path,
    fma_metadata_path: Path,
    efficientat_path: Path,
    labels_path: Path,
    fma: FmaHeadParameters,
    fma_source_metadata: dict[str, Any],
    audioset: AudioSetHeadParameters,
    export_report: dict[str, Any],
    phone_validation: dict[str, Any] | None,
    elapsed: float,
) -> dict[str, Any]:
    selected_audio = required_audioset_labels()
    hidden_width = int(audioset.first_weight.shape[0])
    retained_parameters = (
        fma.weights.size
        + fma.biases.size
        + fma.calibration_scales.size
        + fma.calibration_biases.size
        + audioset.first_weight.size
        + audioset.first_bias.size
        + len(selected_audio) * hidden_width
        + len(selected_audio)
    )
    return {
        "schema_version": 1,
        "model_name": args.model_name,
        "intended_use": "research_and_on_device_product_validation",
        "shipping_status": "requires_training_data_provenance_review",
        "created_utc_unix_seconds": int(time.time()),
        "elapsed_seconds": elapsed,
        "model": {
            "format": "onnx",
            "path": str(output_path),
            "sha256": sha256_file(output_path),
            "size_bytes": output_path.stat().st_size,
            "opset": args.opset,
            "input": {
                "name": "embedding",
                "dtype": "float32",
                "shape": ["batch", EMBEDDING_DIM],
            },
            "output": {
                "name": "semantic_scores",
                "dtype": "float32",
                "shape": ["batch", len(OUTPUT_SPECS)],
            },
            "internal_preprocessing": "row-wise L2 normalization, epsilon=1e-12",
            "feature_scale": args.feature_scale,
            "retained_parameter_count": int(retained_parameters),
            **export_report,
        },
        "architecture": {
            "type": "exact_hybrid_subheads",
            "training_performed": False,
            "reason_not_distilled_further": (
                "Available frozen embedding stores are overwhelmingly music. "
                "A smaller student trained on them would not credibly preserve "
                "speech and sound-effect behavior. The universal MN10 hidden "
                "projection is therefore retained and only its unused final "
                "AudioSet rows are pruned."
            ),
            "fma_branch": (
                "Exact calibrated linear head with exported hierarchy constraints."
            ),
            "audioset_branch": (
                "Exact MN10 classifier hidden projection; final classifier pruned "
                f"from {len(audioset.label_names)} to {len(selected_audio)} classes."
            ),
            "personal_data_used_for_weights_or_taxonomy": False,
        },
        "labels": build_label_metadata(fma),
        "warnings": [
            (
                "content.novelty_proxy is not a meme classifier. Require "
                "metadata/text corroboration before generating novelty names."
            ),
            (
                "AudioSet-derived aggregates are routing scores, not calibrated "
                "probabilities; no decision threshold is claimed for them."
            ),
            (
                "FMA audio has track-level licenses, many with noncommercial or "
                "no-derivatives restrictions. Complete a derived-model rights "
                "review before public distribution."
            ),
            (
                "EfficientAT code/checkpoint licensing and AudioSet training-data "
                "provenance must both be recorded in release notices."
            ),
        ],
        "provenance": {
            "fma_head": {
                "path": str(fma_path),
                "sha256": sha256_file(fma_path),
                "metadata_path": str(fma_metadata_path),
                "metadata_sha256": sha256_file(fma_metadata_path),
                "source_model_name": fma_source_metadata.get("model_name"),
                "source_intended_use": fma_source_metadata.get("intended_use"),
                "source_warning": fma_source_metadata.get("provenance_warning"),
            },
            "efficientat_checkpoint": {
                "path": str(efficientat_path),
                "sha256": sha256_file(efficientat_path),
                "model": "EfficientAT MN10 AudioSet mAP 47.1",
                "source_code_license": "MIT (verify release artifact notices)",
            },
            "audioset_labels": {
                "path": str(labels_path),
                "sha256": sha256_file(labels_path),
                "source_class_count": len(audioset.label_names),
                "retained_classes": list(selected_audio),
            },
            "feature_scale_selection": {
                "value": args.feature_scale,
                "source": "tools/research/benchmark_audio_tag_head.py",
                "selection_data": "official FMA validation split",
                "phone_holdout_used_for_selection": False,
            },
        },
        "phone_validation": phone_validation,
        "runtime": {
            "python": platform.python_version(),
            "platform": platform.platform(),
            "numpy": np.__version__,
        },
    }


def main() -> int:
    started = time.perf_counter()
    args = parse_args()
    fma_path = require_file(args.fma_head, "FMA ONNX head")
    fma_metadata_path = require_file(args.fma_metadata, "FMA head metadata")
    efficientat_path = require_file(
        args.efficientat_checkpoint, "EfficientAT checkpoint"
    )
    labels_path = require_file(args.audioset_labels, "AudioSet labels")
    output_path = args.output.expanduser().resolve()
    metadata_path = (
        args.metadata_output.expanduser().resolve()
        if args.metadata_output
        else output_path.with_suffix(".metadata.json")
    )
    if not math.isfinite(args.feature_scale) or args.feature_scale <= 0.0:
        raise ValueError("--feature-scale must be finite and positive")

    print("Loading calibrated FMA and EfficientAT AudioSet heads...")
    fma, fma_source_metadata = load_fma_head(fma_path, fma_metadata_path)
    audioset = load_audioset_head(efficientat_path, labels_path)
    model = build_hybrid_model(
        fma,
        audioset,
        feature_scale=args.feature_scale,
    )

    print(f"Exporting {output_path}...")
    export_report = export_onnx(
        model,
        output_path,
        opset=args.opset,
        fp16=args.fp16,
    )
    labels = build_label_metadata(fma)
    phone_validation = None
    if args.phone_validation_store is not None:
        phone_store = require_file(
            args.phone_validation_store, "phone validation store"
        )
        print("Running post-export phone distribution audit...")
        phone_validation = validate_phone_store(
            phone_store,
            output_path,
            labels,
        )

    elapsed = time.perf_counter() - started
    metadata = build_metadata(
        args=args,
        output_path=output_path,
        fma_path=fma_path,
        fma_metadata_path=fma_metadata_path,
        efficientat_path=efficientat_path,
        labels_path=labels_path,
        fma=fma,
        fma_source_metadata=fma_source_metadata,
        audioset=audioset,
        export_report=export_report,
        phone_validation=phone_validation,
        elapsed=elapsed,
    )
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(
        json.dumps(json_safe(metadata), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    print(f"  labels: {len(OUTPUT_SPECS)}")
    print(f"  ONNX size: {output_path.stat().st_size / 1024.0 / 1024.0:.2f} MiB")
    print(
        "  ONNX parity max abs error: "
        f"{export_report['onnx_parity_maximum_absolute_error']:.6g}"
    )
    print(f"  metadata: {metadata_path}")
    print(f"  elapsed: {elapsed:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
