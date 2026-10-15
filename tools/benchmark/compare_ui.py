#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Summarize paired UI runs without treating pooled frames as independent experiments."""
import argparse
import json
from pathlib import Path
import statistics


def percentile(values, q):
    values = sorted(values)
    pos = (len(values) - 1) * q
    lo = int(pos)
    hi = min(lo + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def load(directory):
    run = json.loads((directory / "run.json").read_text())
    if not run.get("passed"):
        raise ValueError(f"Run did not pass: {directory}")
    reports = list((directory / "results").rglob("*benchmarkData.json"))
    if len(reports) != 1:
        raise ValueError(f"Expected exactly one benchmarkData.json: {directory}")
    data = json.loads(reports[0].read_text())
    result = {}
    for benchmark in data["benchmarks"]:
        metrics = {}
        for name, metric in benchmark.get("sampledMetrics", {}).items():
            runs = [values for values in metric["runs"] if values]
            p95 = [percentile(values, .95) for values in runs]
            metrics[name] = {
                "p50": metric["P50"], "p95": metric["P95"], "p99": metric["P99"],
                "iteration_p95_median": statistics.median(p95),
                "iteration_p95_range": [min(p95), max(p95)],
                "samples": sum(map(len, runs)),
            }
            if name == "frameOverrunMs":
                late = [100 * sum(v > 0 for v in values) / len(values) for values in runs]
                metrics[name]["late_frame_percent_median"] = statistics.median(late)
                metrics[name]["late_frame_percent_range"] = [min(late), max(late)]
        for name, metric in benchmark.get("metrics", {}).items():
            metrics[name] = {key: metric[key] for key in ("minimum", "median", "maximum")}
        result[benchmark["name"]] = {
            "iterations": benchmark["repeatIterations"],
            "thermal_throttle_sleep_seconds": benchmark.get("thermalThrottleSleepSeconds"),
            "metrics": metrics,
        }
    return run, result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    before, before_metrics = load(args.before)
    after, after_metrics = load(args.after)
    checks = ("device", "display", "density", "peak_refresh_rate", "min_refresh_rate",
              "fixture", "tracks", "compilation", "iterations", "scenarios", "harness_sha256", "fixture_media")
    mismatches = [key for key in checks if before[key] != after[key]]
    if mismatches:
        raise ValueError(f"Incomparable runs: {', '.join(mismatches)}")
    if set(before_metrics) != set(after_metrics):
        raise ValueError("Benchmark method sets differ")
    result = {
        "before": before, "after": after,
        "note": "One paired run is descriptive evidence, not a cross-device speed guarantee. "
                "Repeat in reverse order. P95 ranges are per-iteration spread, not confidence intervals. "
                "TraceSection durations include UIAutomator synchronization; they are not sort CPU time.",
        "benchmarks": {name: {"before": before_metrics[name], "after": after_metrics[name]}
                       for name in before_metrics},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
