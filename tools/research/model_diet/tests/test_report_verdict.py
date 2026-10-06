"""Regression guard for the external benchmark's interpretation of confidence intervals."""

import ast
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from paths import WORK


@pytest.mark.parametrize(
    "low,high,expected",
    [
        (-0.01, 0.02, "inconclusive"),
        (0.001, 0.02, "better"),
        (-0.02, -0.01, "FAIL"),
        (0, 0, "same on this test"),
    ],
)
def test_crossing_zero_does_not_establish_equivalence(low, high, expected):
    report = WORK / "report/report.py"
    if not report.exists():
        pytest.skip("External model-diet benchmark is not installed")
    tree = ast.parse(report.read_text())
    verdict = next(
        n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "verdict"
    )
    namespace = dict(
        summarize=lambda x: x,
        ci=str,
        pooled=lambda s, m: s[("mpd", "cold")],
        MPD=["mpd"],
        LISTENER="owner",
        HEAD_MARGIN=0.005,
        pct=str,
        pooled_head=lambda *args: dict(
            genre=1.0, genre_confident=1.0, mood=1.0, Electronic=1.0, **{"Hip-Hop": 1.0}
        ),
    )
    exec(
        compile(ast.Module(body=[verdict], type_ignores=[]), str(report), "exec"),
        namespace,
    )
    measured = {("mpd", "cold"): {"dp@10": [(low + high) / 2, [low, high]], "n": 100}}
    result = namespace["verdict"]([], {}, {}, measured, [])
    assert result[0][1] == expected
