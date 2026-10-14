# MOSS qualification for algorithm comparison — 2026-10-14

The blind algorithm comparison was **prepared but not run**. None of the four judge/annotation configurations passed its frozen qualification gate. No algorithm ranking, similarity accuracy, or clustering-quality conclusion is supported by these results.

`control-observations.json` contains 88 identity-free observations; `analyze_controls.py` recomputes the qualification results without audio, network access, credentials or model downloads. Raw replies (including Thinking traces), source paths, track names and audio remain outside Git.

```sh
python3 tools/research/map_scaling/moss_algorithms/analyze_controls.py
```

`qualification.ipynb` is an executed companion. Run it from this directory with Python, NumPy, pandas and Matplotlib. `protocol.json` records the original 733-task plan; only its first 19 controls ran. The two montage pilots each ran 27 tasks (21 hard controls plus three musical pairs and their reversals). `attributes-protocol.json` records a separate 737-task annotation plan; only its 15 controls ran. Later protocols were frozen before their outputs, explicitly after retaining earlier failures.

The original candidates were legacy k-means 25% audio, installed average linkage 75%, directional mean-shift 75%, compact mean-shift 50%, and selected k-means 60%. There is no result for their relative quality in this experiment.

## Runtime

- MOSS-Music source: `ad107c7ddaa06de168a0dfbc18d3e1e6a40c0e5e`.
- OpenMOSS SGLang `moss-audio`: `c28a945853c7fee357f55d976b8abce51874bd94`.
- Instruct checkpoint: `fce7f8304e96cc2d3398b8106456cbb2ecec3139`.
- Thinking checkpoint: `2ce899988b94b8ecc5dd0dacbc5ce1874d3500e3`.
- NVIDIA A40, BF16; torch 2.9.1+cu128, transformers 4.57.1, cuDNN 9.16.0.29 per official usage guide. The cuDNN version deliberately differs from torch's exact package dependency; the pip-check warning is retained privately.
- Greedy decoding. Thinking pair budget 256 / output limit 768; single-recording attribute budget 128 / output limit 512. No returned answer hit its output limit.
- The official SGLang audio loader and Whisper feature extraction retained all 392,000 samples / 2,450 mel frames for the 24.5-second montages. The silent half remained exactly zero in PCM.

Runners accept anonymous task manifests and a local SGLang endpoint. They do not contain algorithm assignments or track identities. Use absolute audio-root paths: relative paths were rejected by the server before inference; those transport errors are retained privately and are not counted among the 88 returned answers.

## Interpretation

The multi-file Instruct test failed all six exact-match-versus-silence questions. Instruct montages also failed semantic checks after normalizing its `secondMusic` key for diagnosis; order changes shifted scores by 1, 2 and 3 points on a 0–4 scale. Thinking montages passed the small order test but hallucinated music in all six trailing-silence controls. Single-recording descriptions were more promising: all music/silence decisions were correct, and five of six byte-identical repeats had identical attributes; the remaining repeat changed acoustic drums to electronic drums. That last failure is narrow and does not establish that single-track descriptions are generally useless. It does prevent promoting this small pilot into the predeclared validated comparison.

The controls are small, diagnostic and different between pilots. Their fractions are **not comparable model accuracy estimates**. There are no human-labelled acoustic features, no general MOSS benchmark and no statistical evidence that any clustering algorithm won. Do not interpret a stopped experiment as support for the currently installed algorithm.
