# MOSS-Music diagnostic pilot

Research scripts only. They do not change app models, grouping weights, names or dependencies.
The [map research report](../../../../docs/map-scaling-analysis-2026-10-13.md) records findings
and limitations. Audio, captions, private identities, group membership and source paths are
kept outside Git. The aggregate JSON contains no recording or playlist identities.

## Executed model and environment

- [MOSS-Music official source](https://github.com/OpenMOSS/MOSS-Music), commit
  `ad107c7ddaa06de168a0dfbc18d3e1e6a40c0e5e`.
- [MOSS-Music-8B-Instruct](https://huggingface.co/OpenMOSS-Team/MOSS-Music-8B-Instruct), revision
  `fce7f8304e96cc2d3398b8106456cbb2ecec3139`; four shard hashes in `source/hf-model.json`.
- Python 3.12, PyTorch/torchaudio 2.9.1+cu128, Transformers 4.57.1, BF16, greedy batch size 1,
  NVIDIA A40. Direct dependencies are pinned; the private execution archive also contains
  the complete environment freeze and original logs.
- This used the documented Transformers route. The authors recommend their SGLang fork for
  best generation quality; backend equivalence has not been established here.

`bootstrap.sh AUDIT_ROOT` installs an isolated environment, downloads the pinned public source
and weights, verifies shard hashes and imports the explicit source classes. It requires an
already provisioned CUDA machine and network access. It does not provision or stop cloud compute.
Do not run it as part of the app build. The observed run used about 17.10 GiB peak allocated
CUDA memory for these short clips; this is not a requirement for arbitrary audio lengths.

## Blind audio descriptions

`caption_blind.py` requires local, finite mono 16 kHz WAV excerpts and a JSONL manifest containing
only `clip_id` and `path`, for example `{"clip_id":"c0001","path":"c0001.wav"}`.
Pass `--repo-dir`, `--weights-dir`, `--model-api-json`, `--clips-root`, `--manifest` and `--output`.
Start with `--max-clips 8 --max-minutes 15`, inspect the raw answers, then use the same output
with `--resume --max-clips 149 --max-minutes 90` for this pilot. Never replace prior outputs to
hide failed checks. The script checks provenance, source state, audio hashes and output identity;
it locks and flushes each completed record. Its time budget is checked between clips and does
not stop the pod; manage an external process timeout and cloud cleanup separately.

The executed sample contained 75 tracks from 15 baseline regions, five per region: two central,
two boundary and one seeded random available member. Seventy-one tracks supplied two excerpts;
four short tracks supplied one. Three additional opaque controls were silence and two exact
duplicates. Private source matching and sampling scripts are preserved with the data archive.
The sample favors diagnosis of the existing map and is not an unbiased weight benchmark.

## Caption-based analysis

`aggregate_captions.py` expects the private manifest, mapping, controls and fixed pair plan beside
the script. `--prepare-only` produces an anonymous feature worklist. A separate reviewer must
inspect the caption text and retain explicit evidence and unknowns before `--features` is used.
The executed review was by an isolated AI agent, not human listening or verification of audio.
`caption_features.py` supplies the seven-field rubric and validation. Four jointly observed
fields and every expected cross-excerpt comparison are required for a track-pair score.
The deliberately strict missing-evidence rule left only 503/2775 track pairs evaluable.
Lexical similarity is a secondary wording diagnostic, not musical accuracy.

`prepare_blind_groups.py freeze` fixes a 30-group allocation before full captions are available.
Its `prepare` mode consumes frozen captions locally and exports only anonymous descriptions.
Fourteen real MUSIC groups are compared with a permutation of the same 70 tracks, matched by
excerpt count. NOVELTY is separate; its five-track control checks presentation order only.
The original private plan and all preprocessing decisions remain in the execution archive.

`judge_groups_v2.py` accepts `--repo-dir`, `--weights-dir`, `--model-api-json`, `--groups` and
`--output`. Use `--max-groups 2`, then the identical command with `--resume --max-groups 30`.
This is text-only assessment by the same model, using only instruments, vocals and energy;
an encoder hook fails if audio is unexpectedly processed. Original genre, rhythm/BPM and
production/spatial fields are excluded. Original answers, format failures and timings are saved.

The first group prompt produced incompatible nested JSON. Version 2 changed only response
format and ran into separate files; the coherence rubric and frozen input stayed unchanged.
Some v2 answers still return numeric scores as strings or exceed a sentence limit. Preserve
these failures. More importantly, some judgments contradict supplied vocals or infer absent
vocals from unknown input. Valid JSON or a numerical score is not evidence of a valid judgment.
Inspect every proposed conclusion against its source captions before using it.

## Interpretation

MOSS provided useful candidate commonalities and boundary examples, but also unsupported
details. Its labels and derived distances are fallible model outputs, not listening ground truth.
The pilot does not establish a region accuracy percentage or justify changing the 25/75 default.
The temporary pod was deleted after the final results were downloaded and hash-checked.
