# SMART learned ranker — app notes

Branch `claude/test-ranker-1019` (from `claude/test-combined-1019`: Statistics and For You kept), 19 October 2026.
Spec: PLAN_FINAL.md §1, §7 (READY) and §8 (draft, under review) on `claude/smart-stage0-eval-f2a727`; the app-side plan
and its status are in `docs/smart-final-app-plan.md`. Not a release; the owner build comes after Python ↔ Kotlin
parity on the trained model (§7: no interim builds).

## Settings

*Settings → SMART engine → Recommendations*:
- **Learned ranker (test)** (`smart_ranker`, on by default in this build): off = SMART's usual chain, at once for the
  next queue — switching back needs no reinstall.
- **Learn from my habits** (`learn_from_habits`, on by default; enabled with the ranker): on = the FULL model (the
  listener's own history as features and retrievers), off = CORE. The earlier habit slots are withdrawn.

## One plan (`SmartRanker.plan`, called by `EngineNextTrackChooser` under its lock)

1. **Exclusions** (§7.2): the seed, ringtone / notification / alarm / recording folders (MediaStore's IS_MUSIC is
   applied upstream), unplayable or removed tracks (not among the controller's candidates), durations ≤ 0 or over a
   day read as unknown.
2. **Candidates** (§1.1): libraries up to `serving.wholeLibraryUpTo` (600) eligible tracks are scored whole (as the
   research scored them); larger ones score the union of the retrievers' quotas — R1 audio neighbours, R2
   co-listening, R4 same artist set or album, R5 same genre family, and with FULL the listener's transitions and
   ACT-R favourites — topped up with a salted sample if they return fewer than 2 × length. What was heard in the last
   24 hours or earlier in this session is left out while anything else remains.
3. **Knowledge per track**, read once per library list: pack language, decade and entity, the artist set (§1.4, the
   port of artist_sets.py), the song-version key, the sanitised duration; the descriptor and the LightFM vector on
   first use. **Imputation** (§1.3): a track without a descriptor or a genre family gets them from the audio tagger
   (FMA-trained head on its A0 embedding), flagged `desc_imputed` / `family_imputed`.
4. **Decoder** (§8): conditional-logit scores over the model's features; hop k > 1 scores against the seed and the
   previous pick, mixed 2 : 1; the artist-run penalty p · runLength · sd(hop scores) (seed in the run); the rows within
   ε = 0.01 · sd of the best form the near-best set, ordered by the UTF-8 bytes of the track id, and hop h takes the
   pick from the h-th SplitMix64 output of SHA-256("salt|content key|press|0"). The **press** — how many SMART starts
   from that seed came before — is persisted (`smart_presses.txt`, keyed by §7.2b's content key), so a second start
   gives a fresh queue and a restart keeps the count. **Song-version dedup**: no version of the previous pick or of
   the last min(50, n / 2) picks. **Budget**: 150 ms per hop; past it the plan stops and the ladder fills.
5. **Ladder** (§7.2), one track per step until the plan is full: audio neighbours of the last pick → descriptor
   neighbours (a salted 2,000-track sample) → the same genre family (salted) → the least recently played track
   outside the recency window (log order; never-played first, salted), relaxing the dedup window to 1, then the
   recency window to 1; a version of the previous pick only when nothing else exists.
6. **Rule-only path** (§7.2b): RULE_SET weights in code, R4 (100) + a 500-track salted sample, the same decoder and
   ladder. Used when the assets fail their manifest, when the heap headroom is below 2 × the press's memory + 8 MB
   (checked up front), and for the rest of a queue after 3 failures or spent budgets (circuit breaker). If a plan still
   throws, the chooser's labelled continuation keeps the music going.

## Assets (`ml/`, mirrored in `iosApp/iosApp/ml`; checked by `smart_manifest.json`, sizes + SHA-256, hashed by streaming)

| File | Bytes | Content | Licence |
|---|---|---|---|
| `smart_ranker.json` | ≈ 2.6 KB | CORE+LFM (proto_ranker2 6e2fd6ce): standardised weights, mean / sd recomputed from its training rows (refit = published, max diff 0.0), p 2.129, anchor mix 2 : 1, ε 0.01, serving block | Apache-2.0 |
| `smart_ranker_full.json` | ≈ 1.5 KB | FULL (proto_ranker a5747d63 raw weights, proto_ranker2's FULL p 1.9015), anchor mix, ε 0.01 | Apache-2.0 |
| `artist_colisten.bin` | 7,947,962 | pop_k10 co-listening, 179,585 artists, pack entity ids | CC0-1.0 |
| `artist_lfm.bin` | 14,369,964 | LightFM artist vectors, 111,902 entity-keyed, float32 (int8 changed 24 of 300 FIT top-10s, float16 7; credit-keyed artists left out) | Apache-2.0 |
| `artist_known.bin` | 6,404,144 | artist_sets.py's known list as FNV-1a keys: 335,457 canonical, 310,039 aliases (MBID rows not shipped) | CC0-1.0 |
| `audio_tagger.json` / `.bin` | 3,260 / 396,920 | the §1.3 tagger head, int8 | Apache-2.0 |

## Parity (golden tests against the research code)

- `RankerGoldenTest`: proto_ranker CORE and PERSONAL features on 8 FIT queries (4,052 candidates) within 4e-6
  (float32), top 10 identical.
- `RankerQueueGoldenTest`: proto_ranker2's CORE+LFM queues, 6 queries × 20 hops, teacher-forced; differences only at
  8 exact ties the reference split by one ulp.
- `ArtistSetsGoldenTest` / `ArtistSetsTest`: 47 hashed hard cases, 3,000 real FIT credits on the shipped list, 31
  normalisation strings (cases and strings also on the iOS simulator).
- `AudioTaggerTest`: the head's graph, probabilities within 1e-9.
- `SaltedKeyTest`: SHA-256 vectors, the seed and SplitMix64 outputs against Python.
- Still to come: the decoder's salted near-best pick and the retrievers against the Python serving reference the
  research side is writing for the revised §1.6 / §8; FULL's queues.

## Deviations (app vs research)

- Genre family from the track's own first genre tag (the research used the teacher's genres, not shipped).
- No popularity table yet: logpop = logpop_z = 0 (CORE+LFM's standardised weights 0.078 / −0.019 are lost) — an
  entity-keyed artist popularity table is agreed and pending.
- LightFM: entity-keyed vectors only (78 % of the emulator library's tracks have one); the rest read lfm_cos = 0,
  which the model treats as dissimilar.
- The device reads no artist MBID tags (the parser's MBID rules never fire) and no composer tag (classical cap key =
  the primary artist).
- FULL's personal transitions count only the listener's own picks (Addendum A's rule); the research counted every
  consecutive pair.
- The audio term follows §1.3's rule, w_audio = max(0, raw weight of the descriptor cosine): CORE+LFM's is negative, so
  the term is 0 with today's weights. Audio still feeds R1, the ladder and imputation.
- A small library's SMART queue ends when every track is queued once (the app's queues hold a track once; repeat-all
  loops), where §7.2b lets n = 2 alternate.
- Marked playlists ("keep together in SMART") are not a ranker feature yet; the old chain still honours them.

## Tests (app)

`RankerScenarioTest` (20 scenarios of §7.1 with §7.3's invariants, JVM + iOS simulator), `RankerLargeLibraryTest`
(10k tracks with the shipped assets: 200 ms first press / 24 ms next on the host), `SmartRankerAssetsTest`
(manifest pass; a damaged or missing asset → rule-only, still a full queue), `RankerResilienceTest` (presses survive
a restart; an unhealthy manifest and a throwing audio index still fill the queue), `SmartPressesTest`,
`RankerDecoderTest`, `SmartRankerTest`, `RankerChooserTest`.
