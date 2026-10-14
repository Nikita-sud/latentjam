# Region algorithm / weight search

Research-only, frozen 1,107-track owner library. See `docs/map-algorithm-search-2026-10-14.md` for recommendation and limitations. Private sources and outputs are outside Git. No production behavior changes.

Public: scripts, protocol, aggregate results, executed `analysis.ipynb`. The notebook reads adjacent `public-results.json` and can be run without private music files. It is an aggregate companion, not a substitute for rerunning the underlying audio experiment.

To rerun full analysis with the private archive, copy this directory's scripts into `$OUT/src`; put protocol files in `$OUT`; use the original snapshot (regions and snapshot subdirectories), its compiled Kotlin classpath, the previous exact baseline report, the existing private clip manifest and clips, and the locally cached CLAP checkpoint. Java helpers currently use the installed JDK at `/opt/homebrew/opt/openjdk@21/bin`.

```sh
python src/clap_audit.py --root "$OUT" --clips "$CLIPS" --model-path "$CLAP_MODEL"
python src/search.py "$OUT" --snapshot "$SNAPSHOT" --baseline "$BASELINE_JSON"
python src/refine.py "$OUT" --snapshot "$SNAPSHOT"
python src/evaluate.py "$OUT" --clips "$CLIPS"
python src/evaluate.py "$OUT" --clips "$CLIPS" --holdout
python src/stability.py "$OUT" --snapshot "$SNAPSHOT"
python src/matched_audit.py "$OUT" --clips "$CLIPS"
python src/build_report.py "$OUT" --coordinates "$FIXED_MAP_F32"
python src/notebook.py "$OUT"
```

The first run requires an empty output directory and an unchanged development/holdout protocol. The final report builder preserves the actual exploratory recommendation (average linkage 75/25), not a rule that automatically promotes future outcomes. `selection.json` always retains the original development choice (DMS 75/25). To reproduce the published numbers, use the pinned snapshot and observed dependency versions.

`DmsRegionProbe.java` reuses exact production fusion/centering/semantic routing/naming and has a noise-aware adapter. `directional.py` is the previously verified independent von Mises update. The compact implementation uses uniform update weights inside a cap, corresponding to an Epanechnikov density profile. HDBSCAN uses sklearn 1.8 (min_samples includes the point itself).

Public files must not contain row assignments, track titles, paths to music, waveform clips or input vectors. The private HTML region browser contains the requested compositions and stays local.
