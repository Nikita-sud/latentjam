# SMART learned ranker — test build notes

Test build, 19 October 2026 (branch `claude/test-ranker-1019`, from `claude/test-combined-1019`: Statistics and For
You fixes kept). Not a release. The research is PLAN_BASE.md / `proto_ranker.py` on `claude/smart-stage0-eval-f2a727`;
a v2 (hop-by-hop queues, LightGBM) decides the variant and the weights. Until then the app ships **v1 CORE** weights.

## What it does

Setting: *Settings → SMART engine → Recommendations → Learned ranker (test)* (`smart_ranker`), **on by default** in this
build, all locales. Off = SMART's usual chain, at once for the next queue: switching back needs no reinstall.
The "Learn from my habits" slots are withdrawn (wired off, toggle removed).

**v2 (current): CORE+LFM**, proto_ranker2.py's winner (6e2fd6ce; FIT vs the v0.9.1 engine: hits +0.022
[+0.014, +0.030], same-artist adjacency 0.23 vs 0.41, flow no worse). When SMART plans (a new queue or a top-up),
`RankerQueue` builds **20** tracks (the research's queue length) hop by hop, exactly as `Query.queue`: candidates =
SMART's library minus queued/removed tracks, minus the seed and what was heard in the last 24 hours or earlier in this
session (the novelty rule the model trained with). Hop 1 scores every candidate against the seed; hop k > 1 scores it
against the seed and against the previous pick with the same model and mixes them 2 : 1. Pick = argmax of
score − p · runLength · sd(open rows' scores) for rows of the artist the queue ends with (the seed counts),
p = 2.129 (calibrated on FIT train listeners to people's Balanced adjacency 0.238). Ties go to the earlier candidate
(the research: the lower track key). A top-up plans from the queue's last track, so each 20-row plan is re-anchored there.

## Weights asset — `ml/smart_ranker.json`

One JSON file: `features`, `mean`, `sd`, `weights` (score = Σ w·(x − mean)/sd), `runPenalty` (p), `anchor`
(`seed` | `previous` | `mix` with `seedWeight` / `previousWeight`). Swapping weights = replacing this file in
`androidApp/.../ml` and `iosApp/iosApp/ml` (the bundle test keeps them equal). An unknown feature, a bad anchor or a
non-positive sd refuses the asset and SMART keeps its chain.

v2 = `proto_ranker2.json` `core_lfm.weights_standardised` with **mean/sd recomputed** by proto_ranker2's own training-row
pass (the JSON did not keep them; the refit from those rows reproduces the published weights exactly, max diff 0.0),
`runPenalty 2.129`, `anchor mix 2 : 1`. v1 (the first test build) was proto_ranker CORE in raw units, p 0.5, anchor seed;
its weights stay as a test resource (`ranker_core_v1.json`).

## Features (RankerFeatures.kt = proto_ranker.py `lib_features`, CORE)

cos, cos_z (unknown → the known mean, population z over the pool, unknown rows 0), desc_known, same_family,
family_known, same_lang, lang_known, same_artist (engine artist key), decade_dist (|Δ decade| / 10), decade_known,
logpop, logpop_z, colisten (the anchor artist's co-listening similarity q/255 to the candidate's artist), and from
proto_ranker2 lfm_cos / lfm_known (LightFM artist-vector cosine to the anchor, 0 / false when either has no vector).

App-side sources and deviations:
- Descriptor, language, decade, entity: the shipped artist knowledge pack, resolved by artist name (the research used
  the MusicBrainz entity of each track). Adapter guesses are not used.
- **Genre family**: the alias family (Genres' table) of the track's own first genre tag value; the research used the
  teacher's genres, which the app does not ship.
- **Popularity**: no per-track table ships, so logpop = logpop_z = 0 for every track. In CORE+LFM their standardised
  weights are 0.078 and −0.019 (vs lfm_cos 0.58, cos −0.47): the research's popular-track lift is missing.
- **LightFM coverage**: in training every track had a vector (`lfm_known` always 1). The app ships only entity-keyed
  vectors, so an artist that does not resolve to one of them reads lfm_cos = 0, which the model scores like a
  dissimilar artist: such tracks are picked less often than the research would.
- **No audio term yet.**

## Co-listening asset — `ml/artist_colisten.bin`

`tools/research/ranker/pack_colisten.py` writes the research's `v6/pop_k10.npz` (ListenBrainz CC0, FIT listeners
with the fitsample listeners left out, 10 neighbours per artist, sha256 bcd40735…) in the `LJNBRS1` layout read by
`ArtistNeighbours.kt` (from the Stage 1 branch). Entity ids are already the pack's own: **no remapping needed**.
179,585 artists with a list, 1,795,776 entries, **7,947,962 B** raw (6.8 MB gzip; stored uncompressed in the APK and
mapped, like the pack; the arm64 reduced-ORT APK grows from 35.7 to 43.3 MB). Match rate: on FIT libraries 72.1 % of tracks have a MusicBrainz entity and 99.6 % of those
have a list (71.8 % of tracks). In the app the rate depends on name resolution; the log line
"SMART: ranker … library tracks whose artist has an entity N, a co-listening list M" reports it per library (no names).
On the read-only Pixel_7_Pro emulator library: 679 of 852 tracks (80 %) resolve to an entity, 669 (79 %) to one with
a list.

## Speed

Emulator (852 tracks): first plan 141 ms including the per-track knowledge read, a top-up 91 ms. A 10k library was
not measured; the knowledge read is linear in the library and happens once per library list, a plan is 12 hops
over the pool (with `anchor = seed` the scores are computed once).

## LightFM asset — `ml/artist_lfm.bin`

`tools/research/ranker/pack_lfm.py` writes the research's `proto_ranker/lfm_artist.npz` (32-d LightFM WARP item
vectors of the FIT library artists, trained on ListenBrainz CC0 listeners disjoint from the FIT queries; LightFM run
offline only) in the `LJVECS1` layout (`ArtistVectors.kt`): the **111,902 entity-keyed** artists, L2-normalised,
**float32** — **14,369,964 B** (11.1 MB gzip). Left out: 522,641 credit-keyed artists (`c:<credit>`; the app maps
artists to pack entities; they would add ≈ 19 MB). Precision: on 300 FIT queues int8 changed 24 top-10s and float16 7,
so neither ships (`export_golden2.py`, LFM_STORAGE). Match rate: in the research every FIT library track had a vector
(72 % by entity, 28 % by credit); the app gets only the entity part — the log line reports the library's own rate.

## Tests

- `RankerGoldenTest`: 8 FIT queries (4,052 candidates) exported by `tools/research/ranker/export_golden.py` from
  proto_ranker.py's own feature code — all 13 features within 4e-6 (float32), top 10 identical under the shipped
  weights, ties by track key; 234 candidates carry co-listening.
- `ArtistColistenAssetTest`: the shipped table reads back the npz lists; ABBA, Eminem, Кино, Ramin Djawadi and Daft
  Punk resolve to an entity with a list.
- `RankerQueueGoldenTest`: 6 FIT queries, 120 hops of proto_ranker2's CORE+LFM queue (p 2.129) from its own float32
  feature values (`tools/research/ranker/export_golden2.py`), the shipped asset's weights and standardisation, checked
  hop by hop with the reference's earlier picks forced. The app's pick equals the reference's at every hop except 8
  exact ties: rows with identical features that the reference's BLAS dot product split by one ulp (2.2e-16) and the
  app breaks by candidate order; no hop had a real margin under 1e-6.
- `ArtistLfmAssetTest`: the shipped vectors read back the npz values (cosine to 1e-6).
- `SmartRankerTest`, `RankerChooserTest`: ordering, exclusions, run penalty, previous-pick anchor, asset refusal,
  chooser wiring (engine not called, 24-hour rule).
