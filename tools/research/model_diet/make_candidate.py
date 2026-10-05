"""Assembles a candidate bundle: a base bundle's files, with assets replaced from QAT runs or files.

    python make_candidate.py NAME [--base DIR] RUN_OR_FILE ...

RUN_OR_FILE is a run folder (its one model file replaces the asset of the same name, and its
sizes.json joins the candidate's SIZES.json) or a file whose name is an asset name. Unchanged
assets are symlinks into the base; the result lands in ../candidates/NAME.
"""
import argparse
import json
import os
import shutil
from pathlib import Path

from paths import WORK as ROOT
ASSETS = {"music_entities_250k.bin", "mnv4_audio.onnx", "artist_knowledge.bin", "predictor_scorer_n100.onnx",
          "text_encoder.onnx", "predictor_state.onnx", "universal_semantic_head.onnx", "artist_adapter.bin",
          "text_vocab.txt"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("parts", nargs="+")
    ap.add_argument("--base", default=str(ROOT / "baseline" / "ml"))
    args = ap.parse_args()
    base = Path(args.base).resolve()
    out = ROOT / "candidates" / args.name
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    sizes = json.loads((base / "SIZES.json").read_text()) if (base / "SIZES.json").exists() else {}
    for f in base.iterdir():
        if f.name == "SIZES.json":
            continue
        target = f.resolve()
        (out / f.name).symlink_to(os.path.relpath(target, out))
    for part in map(Path, args.parts):
        files = [part] if part.is_file() else [f for f in part.iterdir() if f.name in ASSETS]
        for f in files:
            (out / f.name).unlink(missing_ok=True)
            shutil.copy(f, out / f.name)
            sizes.pop(f.name, None)
            print(f"{f.name} <- {f}")
        if part.is_dir() and (part / "sizes.json").exists():
            sizes.update(json.loads((part / "sizes.json").read_text()))
    if sizes:
        (out / "SIZES.json").write_text(json.dumps(sizes, indent=1))
    print(f"-> {out}")


if __name__ == "__main__":
    main()
