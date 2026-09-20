#!/usr/bin/env python3
"""Compare a replacement semantic head with the shipped head on fixed gates.

FMA metrics use the eight calibrated broad probabilities inside each graph.
In particular, the hybrid ``content.instrumental`` output is not substituted
for the FMA Instrumental probability. Library agreement uses the eight broad
product outputs, including that hybrid output, and all 27 product scores are
audited for drift. Model weights are never changed by this evaluator.

All output, including the identifying disagreement list, must be written below
the private work directory. Reports contain aggregate results only; personal
rows appear exclusively in the private disagreement CSV.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from functools import lru_cache
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from export_universal_semantic_head import EMBEDDING_DIM, OUTPUT_SPECS


BROAD_NAMES = (
    "Electronic", "Experimental", "Folk", "Hip-Hop", "Instrumental",
    "International", "Pop", "Rock",
)
PROTECTED_GENRES = ("Pop", "International", "Folk")
MODEL_VERSION = "mnv4-960-retrieval-distill-v1"
OUTPUT_IDS = tuple(spec.id for spec in OUTPUT_SPECS)
BROAD_OUTPUT_INDICES = tuple(
    next(i for i, spec in enumerate(OUTPUT_SPECS) if spec.fma_labels == (name,))
    for name in BROAD_NAMES
)
EPSILON = 1e-12
PRIVATE_WORK_ROOT = Path.home() / "Documents/LJ/semantic-head-clean"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_private_output(path: Path) -> Path:
    """Do not allow private reports inside a repository or outside their root."""
    path = path.expanduser().resolve()
    private_root = PRIVATE_WORK_ROOT.resolve()
    if not path.is_relative_to(private_root):
        raise ValueError("output directory must be below the private work directory")
    if any((parent / ".git").exists() for parent in (path, *path.parents)):
        raise ValueError("evaluation output must not be written inside a repository")
    return path


class HeadAudit:
    """Inspect routing, then expose the existing FMA tensor without re-exporting."""

    def __init__(self, path: Path, fma_metadata: Path | None = None) -> None:
        import onnx
        from onnx import TensorProto, helper, numpy_helper

        self.graph = onnx.load(str(path))
        onnx.checker.check_model(self.graph)
        properties = {item.key: item.value for item in self.graph.metadata_props}
        if ("semantic_output_ids" in properties
                and tuple(json.loads(properties["semantic_output_ids"])) != OUTPUT_IDS):
            raise ValueError("ONNX semantic output order disagrees with the app contract")
        graph = self.graph.graph
        self.initializers = {
            tensor.name: numpy_helper.to_array(tensor) for tensor in graph.initializer
        }
        self.nodes = {output: node for node in graph.node for output in node.output}
        self.constants = dict(self.initializers)
        for node in graph.node:
            if node.op_type == "Constant":
                attributes = {item.name: helper.get_attribute_value(item) for item in node.attribute}
                if "value" in attributes:
                    self.constants[node.output[0]] = numpy_helper.to_array(attributes["value"])

        for values, name, width in (
            (graph.input, "embedding", EMBEDDING_DIM),
            (graph.output, "semantic_scores", len(OUTPUT_IDS)),
        ):
            if len(values) != 1 or values[0].name != name:
                raise ValueError(f"expected exactly one {name} tensor")
            tensor = values[0].type.tensor_type
            dimensions = tensor.shape.dim
            if (tensor.elem_type != TensorProto.FLOAT or len(dimensions) != 2
                    or dimensions[0].HasField("dim_value")
                    or dimensions[1].dim_value != width):
                raise ValueError(f"{name} must be float32 [batch, {width}]")

        @lru_cache(maxsize=None)
        def ancestors(value: str) -> frozenset[str]:
            node = self.nodes.get(value)
            return frozenset((value,)).union(
                *(ancestors(item) for item in node.input)
            ) if node else frozenset((value,))

        self.ancestors = ancestors
        fma_sigmoids = [
            node for node in graph.node
            if node.op_type == "Sigmoid" and "fma_weights" in ancestors(node.output[0])
        ]
        if len(fma_sigmoids) != 1:
            raise ValueError("cannot uniquely identify the calibrated FMA sigmoid")
        self.fma_raw = fma_sigmoids[0].output[0]
        hierarchical = [
            node for node in graph.node
            if node.op_type == "Where" and self.fma_raw in node.input
        ]
        if len(hierarchical) != 1:
            raise ValueError("cannot uniquely identify hierarchical FMA probabilities")
        self.fma_probability = hierarchical[0].output[0]
        child_mask = self.constants[hierarchical[0].input[0]].reshape(-1).astype(bool)
        audio_sigmoids = [
            node for node in graph.node
            if node.op_type == "Sigmoid" and "audio_second_weight" in ancestors(node.output[0])
        ]
        if len(audio_sigmoids) != 1:
            raise ValueError("cannot uniquely identify AudioSet probabilities")
        self.audio_probability = audio_sigmoids[0].output[0]

        output = self.nodes["semantic_scores"]
        while output.op_type in {"Cast", "Identity"}:
            output = self.nodes[output.input[0]]
        if output.op_type != "Concat" or len(output.input) != len(OUTPUT_IDS):
            raise ValueError("semantic output must concatenate 27 ordered scores")
        if next((a.i for a in output.attribute if a.name == "axis"), None) != 1:
            raise ValueError("semantic output must concatenate along its label dimension")
        self.output_values = tuple(output.input)
        self.broad_indices = []
        for name, output_index in zip(BROAD_NAMES, BROAD_OUTPUT_INDICES):
            gathers = [
                self.nodes[value] for value in ancestors(output.input[output_index])
                if value in self.nodes and self.nodes[value].op_type == "Gather"
                and self.nodes[value].input[0] == self.fma_probability
            ]
            if len(gathers) != 1:
                raise ValueError(f"expected one FMA source for broad output {name}")
            index = int(self.constants[gathers[0].input[1]].item())
            if child_mask[index] or int(self.initializers["fma_parent_indices"][index]) != index:
                raise ValueError(f"{name} is routed from a child label")
            self.broad_indices.append(index)
        if len(set(self.broad_indices)) != len(BROAD_NAMES):
            raise ValueError("broad outputs must use eight distinct FMA rows")
        self.index_to_name = dict(zip(self.broad_indices, BROAD_NAMES))
        if fma_metadata is not None:
            labels = json.loads(fma_metadata.read_text(encoding="utf-8"))["labels"]
            for index, name in self.index_to_name.items():
                if labels[index]["name"] != name or labels[index]["kind"] != "broad":
                    raise ValueError(f"FMA metadata disagrees with output routing for {name}")
        self.routing = tuple(self.routing_signature(value) for value in self.output_values)
        self.audioset_ancestry = self.ancestry_signature(self.audio_probability)
        self.sha256 = sha256_file(path)

        # Keep the original graph's internal precision and original weights.
        augmented = copy.deepcopy(self.graph)
        augmented.graph.node.append(helper.make_node(
            "Cast", [self.fma_raw], ["audit_fma_probabilities"],
            name="audit_fma_probabilities_to_float32", to=TensorProto.FLOAT,
        ))
        augmented.graph.output.append(helper.make_tensor_value_info(
            "audit_fma_probabilities", TensorProto.FLOAT,
            ["batch", len(self.initializers["fma_biases"])],
        ))
        onnx.checker.check_model(augmented)
        self.runtime_bytes = augmented.SerializeToString()

    def routing_signature(self, value: str) -> Any:
        """Compare formulas and source columns independently of trained weights."""
        from onnx import helper

        if value == self.fma_probability:
            return ("fma_probabilities",)
        if value == self.audio_probability:
            return ("audioset_probabilities",)
        if value in self.constants:
            array = self.constants[value]
            return ("constant", tuple(array.shape), tuple(array.reshape(-1).tolist()))
        node = self.nodes[value]
        if node.op_type == "Gather" and node.input[0] == self.fma_probability:
            index = int(self.constants[node.input[1]].item())
            return ("fma_label", self.index_to_name[index])
        if node.op_type in {"Cast", "Identity"}:
            return self.routing_signature(node.input[0])
        attributes = tuple(sorted(
            (attribute.name, repr(helper.get_attribute_value(attribute)))
            for attribute in node.attribute
        ))
        return (node.op_type, attributes, tuple(self.routing_signature(item) for item in node.input))

    def ancestry_signature(self, value: str) -> str:
        """Hash every upstream operation and value without depending on node names."""
        from onnx import AttributeProto, helper, numpy_helper

        def array_signature(array: np.ndarray) -> tuple[str, tuple[int, ...], str]:
            array = np.ascontiguousarray(array)
            return array.dtype.str, tuple(array.shape), hashlib.sha256(array.tobytes()).hexdigest()

        def attribute_signature(attribute: Any) -> Any:
            if attribute.type == AttributeProto.TENSOR:
                return array_signature(numpy_helper.to_array(attribute.t))
            if attribute.type in (AttributeProto.GRAPH, AttributeProto.GRAPHS):
                raise ValueError("AudioSet ancestry contains unsupported control flow")
            value = helper.get_attribute_value(attribute)
            if isinstance(value, bytes):
                return ("bytes", value.hex())
            if isinstance(value, list):
                return tuple(value)
            return value

        graph_inputs = {item.name: item for item in self.graph.graph.input}
        opsets = {item.domain: item.version for item in self.graph.opset_import}

        @lru_cache(maxsize=None)
        def visit(tensor: str) -> str:
            if tensor in self.constants:
                description = ("constant", array_signature(self.constants[tensor]))
            elif tensor in graph_inputs:
                item = graph_inputs[tensor].type.tensor_type
                shape = tuple(d.dim_value if d.HasField("dim_value") else "dynamic" for d in item.shape.dim)
                description = ("input", tensor, item.elem_type, shape)
            elif tensor == "":
                description = ("omitted_optional_input",)
            else:
                node = self.nodes[tensor]
                attributes = tuple(sorted(
                    (item.name, attribute_signature(item)) for item in node.attribute
                ))
                description = (
                    "operation", node.domain, opsets.get(node.domain), node.op_type,
                    attributes, tuple(visit(item) for item in node.input),
                    list(node.output).index(tensor),
                )
            return hashlib.sha256(json.dumps(description, sort_keys=True).encode("utf-8")).hexdigest()

        return visit(value)

    def predict(self, embeddings: np.ndarray, batch_size: int = 256) -> tuple[np.ndarray, np.ndarray]:
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        session = ort.InferenceSession(
            self.runtime_bytes, sess_options=options, providers=["CPUExecutionProvider"]
        )
        outputs, broad = [], []
        for start in range(0, len(embeddings), batch_size):
            scores, fma = session.run(None, {"embedding": embeddings[start:start + batch_size]})
            if (scores.shape[1] != len(OUTPUT_IDS) or not np.isfinite(scores).all()
                    or not np.isfinite(fma).all() or np.min(scores) < 0 or np.max(scores) > 1):
                raise ValueError("head emitted invalid probabilities")
            outputs.append(scores)
            broad.append(fma[:, self.broad_indices])
        return np.concatenate(outputs), np.concatenate(broad)


def load_store(path: Path) -> pd.DataFrame:
    frame = pd.read_parquet(path)
    if not {"track_id", "embedding", "model_version"}.issubset(frame.columns):
        raise ValueError("embedding store is missing required columns")
    if frame.empty or frame["track_id"].duplicated().any():
        raise ValueError("embedding store must be nonempty with unique track IDs")
    if frame["model_version"].isna().any() or set(frame["model_version"].astype(str)) != {MODEL_VERSION}:
        raise ValueError("embedding store has an unexpected encoder model version")
    matrix = np.stack(frame["embedding"].map(lambda item: np.asarray(item, dtype=np.float32)))
    if matrix.shape != (len(frame), EMBEDDING_DIM) or not np.isfinite(matrix).all():
        raise ValueError("embedding store contains invalid vectors")
    norms = np.linalg.norm(matrix, axis=1)
    if not np.allclose(norms, 1.0, atol=1e-4):
        raise ValueError("embedding store must contain L2-normalized track embeddings")
    return frame


def license_is_clean(value: Any) -> bool:
    text = str(value).lower()
    forbidden = ("noncommercial", "non commercial", "non-commercial", "noderiv", "no derivative")
    if any(token in text for token in forbidden):
        return False
    return any(token in text for token in ("attribution", "public domain", "cc0", "zero"))


def fma_test_data(store: pd.DataFrame, tracks: pd.DataFrame, *, clean: bool) -> pd.DataFrame:
    metadata = pd.DataFrame({
        "track_id": tracks.index.astype(int),
        "split": tracks[("set", "split")].to_numpy(),
        "subset": tracks[("set", "subset")].to_numpy(),
        "genre_top": tracks[("track", "genre_top")].to_numpy(),
        "license": tracks[("track", "license")].to_numpy(),
    })
    selected = metadata["split"].eq("test") & metadata["genre_top"].isin(BROAD_NAMES)
    if clean:
        selected &= metadata["subset"].isin(("small", "medium")) & metadata["license"].map(license_is_clean)
    else:
        selected &= metadata["subset"].eq("small")
    expected = metadata.loc[selected]
    prepared = store[["track_id", "embedding"]].copy()
    prepared["track_id"] = pd.to_numeric(prepared["track_id"], errors="raise").astype(int)
    aligned = expected.merge(prepared, on="track_id", how="inner", validate="one_to_one")
    if len(aligned) != len(expected):
        raise ValueError(f"FMA evaluation store is incomplete: expected {len(expected)}, found {len(aligned)}")
    if aligned.empty:
        raise ValueError("FMA evaluation set is empty")
    return aligned.sort_values("track_id").reset_index(drop=True)


def classification_metrics(
    labels: np.ndarray, scores: np.ndarray, *, allow_missing_classes: bool = False,
) -> dict[str, Any]:
    from sklearn.metrics import average_precision_score

    labels = np.asarray(labels)
    if scores.shape != (len(labels), len(BROAD_NAMES)):
        raise ValueError("broad score matrix shape disagrees with labels")
    ap, counts = {}, {}
    for index, name in enumerate(BROAD_NAMES):
        truth = labels == name
        counts[name] = int(truth.sum())
        if not truth.any() or truth.all():
            if not allow_missing_classes:
                raise ValueError(f"FMA evaluation requires positive and negative examples for {name}")
            ap[name] = None
            continue
        ap[name] = float(average_precision_score(truth, scores[:, index]))
    supported = [value for value in ap.values() if value is not None]
    if not supported:
        raise ValueError("FMA evaluation requires at least one evaluable class")
    return {
        "count": int(len(labels)),
        "class_counts": counts,
        "top1_accuracy": float(np.mean(np.asarray(BROAD_NAMES)[scores.argmax(axis=1)] == labels)),
        "macro_average_precision": float(np.mean(supported)),
        "macro_average_precision_class_count": len(supported),
        "macro_average_precision_classes": [name for name, value in ap.items() if value is not None],
        "unavailable_average_precision_classes": [name for name, value in ap.items() if value is None],
        "per_genre_average_precision": ap,
    }


def fma_comparison(old: dict[str, Any], new: dict[str, Any]) -> dict[str, Any]:
    gates = {}
    for metric in ("top1_accuracy", "macro_average_precision"):
        delta = new[metric] - old[metric]
        gates[metric] = {"delta_percentage_points": 100 * delta,
                         "maximum_regression_percentage_points": 1.0,
                         "passed": bool(delta >= -0.01 - EPSILON)}
    for name in PROTECTED_GENRES:
        old_ap = old["per_genre_average_precision"][name]
        new_ap = new["per_genre_average_precision"][name]
        if old_ap is None or new_ap is None:
            gates[f"average_precision_{name}"] = {
                "delta_percentage_points": None,
                "maximum_regression_percentage_points": 3.0,
                "passed": None,
                "status": "unavailable: test split lacks positive or negative examples",
            }
            continue
        delta = new_ap - old_ap
        gates[f"average_precision_{name}"] = {
            "delta_percentage_points": 100 * delta,
            "maximum_regression_percentage_points": 3.0,
            "passed": bool(delta >= -0.03 - EPSILON),
        }
    evaluated = [item["passed"] for item in gates.values() if item["passed"] is not None]
    return {"old": old, "new": new, "gates": gates,
            "passed": all(evaluated),
            "unavailable_gates": [name for name, item in gates.items() if item["passed"] is None]}


def library_comparison(old: np.ndarray, new: np.ndarray) -> tuple[dict[str, Any], np.ndarray]:
    if old.shape != new.shape or old.ndim != 2 or old.shape[1] != len(OUTPUT_IDS) or not len(old):
        raise ValueError("library score matrices must have equal nonempty [tracks, 27] shapes")
    if not np.isfinite(old).all() or not np.isfinite(new).all():
        raise ValueError("library scores must be finite")
    old_broad = old[:, BROAD_OUTPUT_INDICES].argmax(axis=1)
    new_broad = new[:, BROAD_OUTPUT_INDICES].argmax(axis=1)
    disagreement = old_broad != new_broad
    agreement = float(np.mean(~disagreement))
    old64, new64 = old.astype(np.float64), new.astype(np.float64)
    absolute = np.abs(new64 - old64).mean(axis=0)
    shift = new64.mean(axis=0) - old64.mean(axis=0)
    per_output = {
        name: {"old_mean": float(old64[:, index].mean()),
               "new_mean": float(new64[:, index].mean()),
               "mean_absolute_difference": float(absolute[index]),
               "mean_shift": float(shift[index]),
               "difference_passed": bool(absolute[index] <= 0.05 + EPSILON),
               "shift_passed": bool(abs(shift[index]) <= 0.10 + EPSILON)}
        for index, name in enumerate(OUTPUT_IDS)
    }
    gates = {
        "broad_top1_agreement": {"value": agreement, "minimum": 0.90,
                                  "passed": bool(agreement >= 0.90 - EPSILON)},
        "per_output_mean_absolute_difference": {"maximum": float(absolute.max()), "limit": 0.05,
                                               "passed": bool(np.all(absolute <= 0.05 + EPSILON))},
        "per_output_absolute_mean_shift": {"maximum": float(np.abs(shift).max()), "limit": 0.10,
                                          "passed": bool(np.all(np.abs(shift) <= 0.10 + EPSILON))},
    }
    return {
        "count": len(old), "disagreement_count": int(disagreement.sum()),
        "top1_definition": "Eight broad product outputs, including hybrid content.instrumental",
        "per_output": per_output, "gates": gates,
        "passed": all(item["passed"] for item in gates.values()),
    }, disagreement


def report_markdown(report: dict[str, Any]) -> str:
    result = "PASS" if report["preinstallation_gates_passed"] else "FAIL — do not ship"
    lines = ["# Semantic head quality gates", "", f"Preinstallation result: **{result}**.", "",
             "These gates do not authorize release until the app and device gates also pass.", "",
             "The full official FMA-small test split includes NC/ND audio for evaluation only. "
             "Evaluation is use, not redistribution; no evaluation audio is copied to deliverables. "
             "Training, validation, calibration, and threshold selection must exclude that restricted audio.", "",
             "FMA metrics use the raw calibrated eight-genre branch inside each ONNX graph. "
             "Library top-genre agreement uses the corresponding product outputs, including hybrid Instrumental.", ""]
    for key, title in (("fma_small_test", "Full FMA-small test"), ("fma_clean_test", "Clean FMA test")):
        item = report[key]
        lines.extend([f"## {title} ({item['old']['count']} tracks)", "",
                      "| Metric | Old | New | Change (points) | Gate |",
                      "|---|---:|---:|---:|---|"])
        for metric, name in (("top1_accuracy", "Broad top-1 accuracy"),
                             ("macro_average_precision", "Macro average precision")):
            if metric == "macro_average_precision":
                name += f" ({item['old']['macro_average_precision_class_count']} supported genres)"
            gate = item["gates"][metric]
            lines.append(f"| {name} | {100 * item['old'][metric]:.3f}% | {100 * item['new'][metric]:.3f}% | "
                         f"{gate['delta_percentage_points']:+.3f} | {'PASS' if gate['passed'] else 'FAIL'} |")
        for name in BROAD_NAMES:
            old = item["old"]["per_genre_average_precision"][name]
            new = item["new"]["per_genre_average_precision"][name]
            gate = item["gates"].get(f"average_precision_{name}")
            if old is None or new is None:
                lines.append(f"| {name} AP | n/a | n/a | n/a | unavailable |")
                continue
            status = ("PASS" if gate["passed"] else "FAIL") if gate else "reported"
            lines.append(f"| {name} AP | {100 * old:.3f}% | {100 * new:.3f}% | {100 * (new-old):+.3f} | {status} |")
        lines.append("")
        if key == "fma_clean_test":
            lines.extend(["Clean-test threshold comparisons are diagnostic; the fixed FMA release "
                          "gates apply to the full 800-track test split.", ""])
        for name in item["old"]["unavailable_average_precision_classes"]:
            positives = item["old"]["class_counts"][name]
            negatives = item["old"]["count"] - positives
            lines.extend([f"{name} AP is unavailable: {positives} positive and {negatives} negative "
                          "examples. It is excluded from the reported macro AP.", ""])
    library = report["library"]
    lines.extend([f"## Library ({library['count']} tracks)", "",
                  f"Broad top-1 agreement: {100 * library['gates']['broad_top1_agreement']['value']:.3f}% "
                  f"(required ≥90%); {library['disagreement_count']} disagreements.", "",
                  f"Raw FMA-branch top-1 agreement (diagnostic): "
                  f"{100 * library['raw_fma_broad_top1_agreement']:.3f}%.", "",
                  "The identifying disagreement list is `disagreements.csv` beside this private report.", "",
                  "| Output | Old mean | New mean | Mean absolute difference | Mean shift | Gate |",
                  "|---|---:|---:|---:|---:|---|"])
    for name, item in library["per_output"].items():
        status = "PASS" if item["difference_passed"] and item["shift_passed"] else "FAIL"
        lines.append(f"| {name} | {item['old_mean']:.6f} | {item['new_mean']:.6f} | "
                     f"{item['mean_absolute_difference']:.6f} | {item['mean_shift']:+.6f} | {status} |")
    lines.extend(["", "## Contract and pending gates", "",
                  "Tensor contract, all 27 output routing formulas, and unmodified AudioSet "
                  "weights and computation: "
                  + ("PASS" if report["contract"]["passed"] else "FAIL") + ".", "",
                  "App builds/tests: NOT RUN by this evaluator. Device screenshots, title-set comparison, "
                  "and crash check: NOT RUN by this evaluator.", "",
                  "## Asset hashes", "",
                  f"- Old semantic head SHA-256: `{report['hashes']['old_semantic_head']}`",
                  f"- Candidate semantic head SHA-256: `{report['hashes']['new_semantic_head']}`", ""])
    if not report["preinstallation_gates_passed"]:
        lines.extend(["The candidate must not be copied into app assets. Next step: obtain more clean "
                      "examples for the regressing genres, or review training weighting on validation "
                      "data while retaining the existing shipped head. Do not relax a gate.", ""])
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-head", type=Path, required=True)
    parser.add_argument("--new-head", type=Path, required=True)
    parser.add_argument("--new-fma-metadata", type=Path)
    parser.add_argument("--fma-small-store", type=Path, required=True)
    parser.add_argument("--fma-clean-store", type=Path, required=True)
    parser.add_argument("--tracks", type=Path, required=True)
    parser.add_argument("--library-store", type=Path, required=True)
    parser.add_argument("--library-labels", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-small-test-count", type=int, default=800)
    parser.add_argument("--expected-clean-test-count", type=int, default=218)
    parser.add_argument("--expected-library-count", type=int, default=870)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_dir = ensure_private_output(args.output_dir)
    output_paths = {
        name: ensure_private_output(output_dir / name)
        for name in ("disagreements.csv", "quality-gates.json", "quality-gates.md")
    }
    old = HeadAudit(args.old_head)
    new = HeadAudit(args.new_head, args.new_fma_metadata)
    same_routing = old.routing == new.routing
    audio_names = {name for name in old.initializers if name.startswith("audio_")}
    audio_names |= {name for name in new.initializers if name.startswith("audio_")}
    # The first projection is constant-folded to a MatMul weight by this exporter.
    for audit in (old, new):
        for node in audit.graph.graph.node:
            if node.op_type == "MatMul" and node.input[1] in audit.initializers:
                if node.output[0] in audit.ancestors(audit.audio_probability):
                    audit.initializers["audit_audio_first_weight"] = audit.initializers[node.input[1]]
    audio_names.add("audit_audio_first_weight")
    same_audio = all(
        name in old.initializers and name in new.initializers
        and np.array_equal(old.initializers[name], new.initializers[name]) for name in audio_names
    )
    same_audio_computation = old.audioset_ancestry == new.audioset_ancestry
    report: dict[str, Any] = {
        "schema_version": 1,
        "contract": {"passed": same_routing and same_audio and same_audio_computation, "tensor_contract_passed": True,
                     "output_routing_passed": same_routing, "audioset_weights_unchanged": same_audio,
                     "audioset_computation_unchanged": same_audio_computation,
                     "output_ids": list(OUTPUT_IDS),
                     "old_broad_indices": dict(zip(BROAD_NAMES, old.broad_indices)),
                     "new_broad_indices": dict(zip(BROAD_NAMES, new.broad_indices))},
        "hashes": {"old_semantic_head": old.sha256, "new_semantic_head": new.sha256},
    }
    tracks = pd.read_csv(args.tracks, header=[0, 1], index_col=0)
    for key, path, clean, expected_count in (
        ("fma_small_test", args.fma_small_store, False, args.expected_small_test_count),
        ("fma_clean_test", args.fma_clean_store, True, args.expected_clean_test_count),
    ):
        frame = fma_test_data(load_store(path), tracks, clean=clean)
        if len(frame) != expected_count:
            raise ValueError(f"{key} expected {expected_count} tracks, found {len(frame)}")
        embeddings = np.stack(frame["embedding"]).astype(np.float32)
        labels = frame["genre_top"].to_numpy()
        old_scores = old.predict(embeddings)[1]
        new_scores = new.predict(embeddings)[1]
        report[key] = fma_comparison(
            classification_metrics(labels, old_scores, allow_missing_classes=clean),
            classification_metrics(labels, new_scores, allow_missing_classes=clean),
        )

    library = load_store(args.library_store)
    if len(library) != args.expected_library_count:
        raise ValueError(f"library expected {args.expected_library_count} tracks, found {len(library)}")
    labels = pd.read_parquet(args.library_labels)
    if not {"track_id", "relative_path"}.issubset(labels.columns) or labels["track_id"].duplicated().any():
        raise ValueError("private library labels need unique track_id and relative_path columns")
    library = library.merge(labels[["track_id", "relative_path"]], on="track_id", how="left", validate="one_to_one")
    if library["relative_path"].isna().any():
        raise ValueError("private library labels do not identify every embedded track")
    embeddings = np.stack(library["embedding"]).astype(np.float32)
    old_scores, old_raw = old.predict(embeddings)
    new_scores, new_raw = new.predict(embeddings)
    report["library"], disagreement = library_comparison(old_scores, new_scores)
    report["library"]["raw_fma_broad_top1_agreement"] = float(
        np.mean(old_raw.argmax(axis=1) == new_raw.argmax(axis=1))
    )
    report["fma_clean_test"]["release_gate"] = False
    report["fma_small_test"]["release_gate"] = True
    report["preinstallation_gates_passed"] = all(
        report[key]["passed"] for key in ("contract", "fma_small_test", "library")
    )
    report["app_gate"] = {"status": "not_run"}
    report["device_gate"] = {"status": "not_run"}
    old_top = np.asarray(BROAD_NAMES)[old_scores[:, BROAD_OUTPUT_INDICES].argmax(axis=1)]
    new_top = np.asarray(BROAD_NAMES)[new_scores[:, BROAD_OUTPUT_INDICES].argmax(axis=1)]
    disagreements = library.loc[disagreement, ["track_id", "relative_path"]].copy()
    disagreements["old_broad_genre"] = old_top[disagreement]
    disagreements["new_broad_genre"] = new_top[disagreement]
    disagreements["maximum_output_difference"] = np.abs(new_scores - old_scores).max(axis=1)[disagreement]
    output_dir.mkdir(parents=True, exist_ok=True)
    disagreements.to_csv(output_paths["disagreements.csv"], index=False)
    output_paths["quality-gates.json"].write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    output_paths["quality-gates.md"].write_text(report_markdown(report), encoding="utf-8")
    print(json.dumps({"preinstallation_gates_passed": report["preinstallation_gates_passed"],
                      "fma_small_test_passed": report["fma_small_test"]["passed"],
                      "fma_clean_test_passed": report["fma_clean_test"]["passed"],
                      "library_passed": report["library"]["passed"]}, sort_keys=True))
    return 0 if report["preinstallation_gates_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
