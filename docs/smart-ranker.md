# SMART learned ranker — test build notes

Test build, 19 October 2026 (branch `claude/test-ranker-1019`, from `claude/test-combined-1019`: Statistics and For
You fixes kept). Not a release. The research is PLAN_BASE.md / `proto_ranker.py` on `claude/smart-stage0-eval-f2a727`;
a v2 (hop-by-hop queues, LightGBM) decides the variant and the weights. Until then the app ships **v1 CORE** weights.

## What it does

Setting: *Settings → SMART engine → Recommendations → Learned ranker (test)* (`smart_ranker`), **on by default** in this
build, all locales. Off = SMART's usual chain. The "Learn from my habits" slots are withdrawn (wired off, toggle removed).

When SMART plans (a new queue or a top-up), `SmartRanker` plans 12 tracks hop by hop: every candidate the queue may take
(SMART's library minus queued and removed tracks, minus the seed and minus what was heard in the last 24 hours or
earlier in this session — proto_ranker's novelty rule) is scored with a conditional-logit linear score over the CORE
features relative to the hop's anchor, and the best is picked after the artist-run penalty
score − p · runLength · sd(hop scores) (SmartChain's artistRunPenalty shape: candidates of the artist the queue ends
with, seed included). A top-up plans from the queue's last track, so each 12-track plan is re-anchored there.

## Weights asset — `ml/smart_ranker.json`

One JSON file: `features`, `mean`, `sd`, `weights` (score = Σ w·(x − mean)/sd), `runPenalty` (p), `anchor`
(`seed` | `previous`). Swapping weights = replacing this file. An unknown feature, a bad anchor or a non-positive sd
refuses the asset and SMART keeps its chain.

v1 = `proto_ranker.json` CORE (a5747d63, FIT 60/40 listener split, λ = 10) **in raw units with mean 0 and sd 1**: the
research file stores standardised and raw-unit weights but not the means; raw units give the same ranking and the
same hop spread (a constant shift). `runPenalty = 0` and `anchor = seed` — proto_ranker evaluated one static ranking
from the seed, so v1 is that ranking; v2 chooses p and the anchor.

## Features (RankerFeatures.kt = proto_ranker.py `lib_features`, CORE)

cos, cos_z (unknown → the known mean, population z over the pool, unknown rows 0), desc_known, same_family,
family_known, same_lang, lang_known, same_artist (engine artist key), decade_dist (|Δ decade| / 10), decade_known,
logpop, logpop_z, colisten (the anchor artist's co-listening similarity q/255 to the candidate's artist).

App-side sources and deviations:
- Descriptor, language, decade, entity: the shipped artist knowledge pack, resolved by artist name (the research used
  the MusicBrainz entity of each track). Adapter guesses are not used.
- **Genre family**: the alias family (Genres' table) of the track's own first genre tag value; the research used the
  teacher's genres, which the app does not ship.
- **Popularity**: no per-track table ships, so logpop = logpop_z = 0 (FIT weight ≈ 0.01; a constant, no effect).
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

## Tests

- `RankerGoldenTest`: 8 FIT queries (4,052 candidates) exported by `tools/research/ranker/export_golden.py` from
  proto_ranker.py's own feature code — all 13 features within 4e-6 (float32), top 10 identical under the shipped
  weights, ties by track key; 234 candidates carry co-listening.
- `ArtistColistenAssetTest`: the shipped table reads back the npz lists; ABBA, Eminem, Кино, Ramin Djawadi and Daft
  Punk resolve to an entity with a list.
- `SmartRankerTest`, `RankerChooserTest`: ordering, exclusions, run penalty, previous-pick anchor, asset refusal,
  chooser wiring (engine not called, 24-hour rule).
