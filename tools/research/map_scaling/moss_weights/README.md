# Blind three-weight MOSS comparison

Research only: exact Kotlin 25/50/75% audio fusion on the saved 1,107-track library,
followed by a frozen, anonymous direct-audio MOSS evaluation. No mobile defaults changed.

The [experiment report](../../../../docs/map-moss-weight-comparison-2026-10-13.md)
and `results-2026-10-13.json` record the complete outcome. The judge failed its
qualification controls; its raw scores must not be used to select a weight.

- `prepare_comparison.py`: exact region/layout replay, frozen local selection and anonymous WAV tasks.
- `judge_audio.py`: pinned model, multi-audio inference, resume checks and encoder trace.
- `judge_montage.py`: bounded alternate-input diagnostic, introduced after primary controls failed.
- `analyze_results.py`: local-only unblinding, repeated-position averages, order checks and qualification.
- `verify_comparison.py`: source allocation, waveform hashes, metadata stripping and true neighbor ranks.
- `render_comparison.py`: actual map comparison with fixed baseline colors.
- `build_report.py`: aggregate report and private Russian region dossier.

The unchanged `caption_blind.py` supplies the model/source identities and hashing helpers
used in the executed run. Java probes call the actual compiled Kotlin implementation.
Use the sibling `moss/bootstrap.sh`, pinned requirements and `moss/source/hf-model.json`
to prepare an explicitly authorized GPU host; these scripts do not provision cloud compute.
Only `upload/` and the inference code belong on that host. Keep the private plan, identities,
region memberships and metadata local. All raw material for this run remains in the local
diagnostic archive, outside Git. The temporary pod was deleted after verified download.
