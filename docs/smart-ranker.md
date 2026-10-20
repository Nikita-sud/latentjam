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
- **Use favourites** (`smart_use_favourites`, on by default, nested under habits): on = FULL plans see the listener's
  hearts (`is_favourite`, `favourited_days_ago`). It is greyed out and ignored while habits are off; off or ignored,
  both columns read 0. Hearts are logged locally either way (docs/smart-impression-log.md).

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
   (FMA-trained head on its A0 embedding), flagged `desc_imputed` / `family_imputed`. The seed is imputed before the
   retrievers run, so R5 retrieves by an imputed family on the first plan; the pool follows.
4. **Decoder** (§8): conditional-logit scores over the model's features; hop k > 1 scores against the seed and the
   previous pick, mixed 2 : 1; the artist-run penalty p · runLength · sd(hop scores) (seed in the run); the rows within
   ε = 0.01 · sd of the best form the near-best set, ordered by the UTF-8 bytes of the track id, and hop h takes the
   pick from the h-th SplitMix64 output of SHA-256("salt|content key|press|0"). The **press** — how many SMART starts
   from that seed came before — is persisted (`smart_presses.txt`, keyed by §7.2b's content key), so a second start
   gives a fresh queue and a restart keeps the count. **Song-version dedup**: no version of the previous pick or of
   the last min(50, n / 2) picks. **Budget**: 150 ms per hop; past it the plan stops and the ladder fills.
5. **Ladder** (§7.2, §7.2b), one track per step until the plan is full: audio neighbours of the last pick →
   descriptor neighbours (a salted 2,000-track sample) → the same genre family (salted) → the least recently played
   track outside the recency window (log order; never-played first, salted). These rungs keep the decoder's
   third-in-a-row cap as a hard rule (the seed in the run); the last rung relaxes the cap first, then the dedup
   window to 1, then the recency window to the previous pick, then takes any unqueued track that is not a version of
   the previous pick. When every eligible track is queued the plan **repeats** — the least recent of the log, the
   queue before the plan and the plan, outside the recency window, never a version of the previous pick while another
   song exists: n = 1 repeats its track, n = 2 alternates, n = 3 cycles, and a plan is never short while n ≥ 1.
6. **Rule-only path** (§7.2b): RULE_SET weights in code, R4 (100) + a 500-track salted sample, the same decoder and
   ladder; with no features (every seed score ties) the decoder is skipped and the ladder plans the queue. Used when the assets fail their manifest, when the heap headroom is below 2 × the press's memory + 8 MB
   (checked up front), and for the rest of a queue after 3 failures or spent budgets (circuit breaker). If a plan still
   throws, the chooser's labelled continuation keeps the music going.

## Assets (`ml/`, mirrored in `iosApp/iosApp/ml`; checked by `smart_manifest.json`, sizes + SHA-256, hashed by streaming)

| File | Bytes | Content | Licence |
|---|---|---|---|
| `smart_ranker.json` | ≈ 2.6 KB | CORE+LFM (proto_ranker2 6e2fd6ce): standardised weights, mean / sd recomputed from its training rows (refit = published, max diff 0.0), p 2.129, anchor mix 2 : 1, ε 0.01, serving block | Apache-2.0 |
| `smart_ranker_full.json` | ≈ 1.9 KB | FULL (proto_ranker a5747d63 raw weights, proto_ranker2's FULL p 1.9015), anchor mix, ε 0.01; `is_favourite` / `favourited_days_ago` at weight 0 until the research sets them | Apache-2.0 |
| `artist_colisten.bin` | 7,947,962 | pop_k10 co-listening, 179,585 artists, pack entity ids | CC0-1.0 |
| `artist_lfm.bin` | 14,369,964 | LightFM artist vectors, 111,902 entity-keyed, float32 (int8 changed 24 of 300 FIT top-10s, float16 7; credit-keyed artists left out) | Apache-2.0 |
| `artist_known.bin` | 6,404,144 | artist_sets.py's known list as FNV-1a keys: 335,457 canonical, 310,039 aliases (MBID rows not shipped) | CC0-1.0 |
| `audio_tagger.json` / `.bin` | 3,260 / 396,920 | the §1.3 tagger head, int8 | Apache-2.0 |
| `artist_pop.bin` | 742,692 | LJPOPS1 artist popularity by pack entity, ln(1 + FIT listeners), 174,044 artists (c48ee032); read only when a model's `inputs.logpop` is `artist_pop` (the final models; not today's shipped ones) | CC0-1.0 |
| `artist_family.bin` | 465,176 | LJFAMS1 genre family per pack entity, 104,665 artists (4ba49c24): a resolved entity's family wins over the genre tag's (§9) | Apache-2.0 |

## Parity (golden tests against the research code)

- `RankerGoldenTest`: proto_ranker CORE and PERSONAL features on 8 FIT queries (4,052 candidates) within 4e-6
  (float32), top 10 identical.
- `RankerQueueGoldenTest`: proto_ranker2's CORE+LFM queues, 6 queries × 20 hops, teacher-forced; differences only at
  8 exact ties the reference split by one ulp.
- `ArtistSetsGoldenTest` / `ArtistSetsTest`: 47 hashed hard cases, 3,000 real FIT credits on the shipped list, 31
  normalisation strings (cases and strings also on the iOS simulator).
- `AudioTaggerTest`: the head's graph, probabilities within 1e-9.
- `SaltedKeyTest`: SHA-256 vectors, the seed and SplitMix64 outputs against Python.
- `DecoderD0GoldenTest`: proto_ranker4's D0 decode loop, 12 queries × 20 hops identical.
- `ServingReferenceGoldenTest`: the whole plan path against the Python serving reference
  (tools/research/listenbrainz_eval/serving/reference.py, research commit 0ec198cb): 19 fixtures, 36 requests, each on
  a new SmartRanker fed the fixture's resolved records (the golden seam: `SmartRanker.resolver`, `tracer`) — ids and
  counters identical, all 35 feature columns, scores, sds and floors within 1e-9. It includes FULL with listens and
  favourites (fixture 18: the PERSONAL block, R6 hearts first, is_favourite, favourited_days_ago) and CORE with partly
  indexed audio (fixture 19: the missing-vector mean fill). `export_serving_fixtures.py` copies the research
  goldens in; appended columns the goldens may lack must read 0 (pop_known: agree with logpop).
- `RankerArithmeticTest` (JVM and iOS simulator): the float32 cosine and the float64 score on cases where a fused
  multiply-add gives other bits — Kotlin/Native does not fuse them, so parity is exact on both.
- Still to come: FULL's queues against the reference (it plans CORE only); the tagger on on-device 4-bit A0 vectors.

## Deviations (app vs research)

- Genre family (§9): the pack entity's family from `artist_family.bin` when the artist resolves, else the track's first
  genre tag's; the artist-set parser keeps the tag's family for its classical-composer rule.
- Popularity: the LJPOPS1 reader is in (`ArtistPopularity`, `inputs.logpop`), the table is not shipped; with today's
  models (`logpop = none`) logpop = logpop_z = 0. `popularityKnown` is computed beside the value (not a model column).
- LightFM: entity-keyed vectors only (78 % of the emulator library's tracks have one); the rest read lfm_cos = 0,
  which the model treats as dissimilar.
- The device reads no artist MBID tags (the parser's MBID rules never fire) and no composer tag (classical cap key =
  the primary artist).
- FULL's personal transitions count only the listener's own picks (Addendum A's rule); the research counted every
  consecutive pair.
- audio_cos of a candidate without an audio vector = the mean of the pool's known audio cosines (0 when none):
  an unindexed track is neither buried nor lifted by the uncentred audio term.
- The audio term follows §1.3's rule, w_audio = max(0, raw weight of the descriptor cosine): CORE+LFM's is negative, so
  the term is 0 with today's weights. Audio still feeds R1, the ladder and imputation.
- The ranker plans §7.2b's repeats, but the app's queues hold a track once: the chooser serves only tracks not yet
  queued, so a small library's SMART queue still ends when every track is queued (repeat-all loops). Letting the
  controllers queue a track again is a playback change of its own.
- Marked playlists ("keep together in SMART") are not a ranker feature yet; the old chain still honours them.
- Columns after the first 32 (reference.py's TAIL, so earlier indexes never move): `pop_known`; `is_favourite`, set
  only in FULL plans ("Learn from my habits") from the listener's hearts; `favourited_days_ago`, days since the heart
  from `favorite_times.txt` (0 when the time is unknown: hearts from before this build, or after the seed). The
  reference has the first two; `favourited_days_ago` is the app's addition, which research still has to add.

## Tests (app)

`RankerScenarioTest` (20 scenarios of §7.1 with §7.3's invariants, JVM + iOS simulator), `RankerLargeLibraryTest`
(10k tracks with the shipped assets: 200 ms first press / 24 ms next on the host), `SmartRankerAssetsTest`
(manifest pass; a damaged or missing asset → rule-only, still a full queue), `RankerResilienceTest` (presses survive
a restart; an unhealthy manifest and a throwing audio index still fill the queue), `SmartPressesTest`,
`RankerDecoderTest`, `SmartRankerTest`, `RankerChooserTest`.

## Device checks (19 October, evening)

- **v2 on the owner's phone** (7f9b8701, reduced ORT, 57.0 MiB, sha256 230507aa…): installed 18:47 as an update, launch
  OK. Copy: `~/Documents/LJ/releases/test-builds/2026-10-19/ranker-v2-coreplfm-7f9b8701-arm64.apk`.
- **Pixel_7_Pro emulator** (read-only), current build: SMART plans 20 from the model; process death and resume and a
  media-button play work. A plan made during indexing used to spend its budget waiting on the engine's lock — fixed
  with per-read audio limits (858ceb54).
- **48 MB heap, 10,021 tracks** (rootable LJ-api30, `setprop dalvik.vm.heapgrowthlimit 48m`, 10,000 generated tagged
  MP3s): the app launches (Dalvik heap 39 MB), but the first play is killed under GC pressure — **the released 0.9.1
  dies the same way**, before any SMART planning: the known low-memory problem (fix branch fix/low-memory-launch,
  unmerged), not the ranker. At 96 MB the app plays (heap 91 of 96 MB before SMART). A ranker press at 96 MB was not
  observed: the app's log lines are pruned by API 30's chatty under indexing load.
- **Open, found on API 30**: a media-button event from the shell (`cmd media_session dispatch`, empty package name)
  crashes the app inside Media3's legacy session stub (`IllegalArgumentException: packageName should be nonempty`, in
  MediaSessionLegacyStub → MediaSessionManager.RemoteUserInfo). v2 and the current build both; 0.9.1 survived the
  same command, likely because it was not the active session then. Real controllers carry a package name; to be
  checked separately (not in the ranker).
- **Open**: parity of the audio tagger on on-device (4-bit) A0 vectors waits for a debuggable build.

## Final models on the phone (20 October, PRE-E product check)

- a6e01704 ships model_core_final.json (f9db1ec8) as smart_ranker.json and model_full_final.json (54fd0a4b) as
  smart_ranker_full.json, with artist_pop.bin and artist_family.bin. This is not the formal hand-off; the E gates
  are running.
- Installed on the owner's phone 13:30 as an update (APK d5c56529…; notes beside the APK in
  ~/Documents/LJ/releases/test-builds/2026-10-20/). Launch OK.
- The first plan was FULL final: 20 picks from the model, a 184-track pool from 1,106 candidates, 1,196 ms
  including the one-time asset load and manifest hashing.

## 48 MB with 10k tracks (follow-up, 19 October late evening)

- The low-memory fixes asked for (fix/low-memory-launch, fix/smart-press-memory) are **already in this line**, rebased
  before 0.9.1: 49b1aaeb, 1e49115c, 07812305, fcb7f921, c22c47f6, fe24e12c, e3f9220d (the memory note calling them
  unmerged is stale).
- With them, at 48 MB and 10,021 tracks the app still runs out of heap **at idle, before any SMART press**, and
  restarts (OutOfMemoryError on whatever allocates next). A heap dump at the ceiling: 20,059 TrackDescriptor (two
  copies of the library list), 90k TrackId, 316k String, 10 MB of byte[]; 35k native cleaners with 3.7k ICU charset
  decoders and 7k char buffers — the tag-enrichment pass (GenreEnrichment, TrackIdentityTags) reading 10k files. At
  64 MB it idles stably but is killed when playback starts. 0.9.1 behaves the same. This is the app's library and tag
  loading, not the ranker; it needs its own item.
- The ranker's own share, measured on the host for 10,000 tracks with the shipped assets warm (they are mapped on the
  device): 5.6 MB retained → **2.75 MB (282 B per track)** after compacting its per-track cache (67071d19); a press on
  a known 10k library takes 22 ms on the host, the first 160 ms. The up-front rule (headroom ≥ 2 × press + 8 MB, press ≈
  C × 32 features × 24 B + 1 MB ≈ 1.5 MB at C = 600) asks for ≈ 11 MB of headroom; at the 48 MB ceiling seen above the
  app has none, so the rule would send the press to rule-only — not observed on the device, because the app died first.
