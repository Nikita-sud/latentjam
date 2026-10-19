# SMART final model — app-side plan (PLAN_FINAL.md §1 + §7)

2026-10-19, branch `claude/test-ranker-1019` (Statistics and For You kept). The spec is PLAN_FINAL.md v4 + §7 on
`claude/smart-stage0-eval-f2a727` (1cf09466). One final owner build after Kotlin parity (§7: no interim builds; the v2
CORE+LFM install is dropped). Every weight, quota, T, p, cap and normalisation constant comes from one parameterised
JSON asset, so the trained CORE / FULL models drop in.

## Kept from the test-ranker work
- `ArtistNeighbours` + `ml/artist_colisten.bin` (R2 retrieval and the co-listening feature; ids are pack entity ids).
- `RankerModel` JSON reader (extended to the v3 schema below), `RankerFeatures` (CORE content features), the
  golden-export tooling pattern (reference values exported from the research code, hop-by-hop teacher forcing).
- Setting plumbing, chooser wiring, the 24-hour novelty rule, the iOS/Android asset loaders.
- Dropped from the shipped path: `ArtistVectors` / `artist_lfm.bin` (LightFM is cut from the W6 path, §5) and the
  greedy `RankerQueue` (replaced by the §1.6 decoder). Both stay in git.

## Work packages (app, core:smart unless noted)

| # | Package | Spec | Days |
|---|---------|------|------|
| P0 | Asset schema v3 + versioned, checksummed manifest; load-time verification; kill switch (hidden setting) | §1.7, §7.2 | 0.5 |
| P1 | §1.6 decoder: version dedup window min(50, ⌊n/2⌋), −8 cap on the third consecutive primary artist (composer for classical), float64 softmax with max-subtraction and T, w = round(q·2³²) as ULong, stable sort by w desc then UTF-8 id bytes, top-k 20 + integer top-p (ceil, inclusive, top item kept), SplitMix64 from big-endian SHA-256("salt\|query\|press\|0"), h-th output per hop, unsigned remainder. Common-code SHA-256 (no platform crypto on Native). Press counter persisted with the history log, keyed by the §7.2b content key; old logs start at press 0 | §1.6, §7.2b | 2 |
| P2 | Artist sets: port of `artist_sets.py` (normalize a–g incl. NFKC / NFD via expect/actual, Mn-after-<U+0400 rule, FEAT/COND/TITLE on the raw string, fewest-segments DP, placeholders, classical composer, FNV-1a 64 keys) + the `artist_known` asset as sorted u64 pairs; the hashed parser cases as tests; Unicode-table differences listed as parity exceptions | §1.4 | 3 |
| P3 | Retrieval with fixed quotas: R1 audio neighbours (engine index, partial index allowed), R2 co-listening item-kNN, R4 same artist set / album, R5 genre-popular; FULL: R6 ACT-R favourites, R7 own transitions, V-SKNN. Candidates and every reduction sorted by id bytes (metamorphic) | §1.1, §7.2b | 2.5 |
| P4 | Features: descriptor cosine to previous and to the session; family / language match; decade distance; duration (log, short-track flag, sanitised); audio cosine to previous with w_audio = max(0, β_desc_prev) and the "imputed" flag path for the future tagger; co-listening (primary, featured ×0.5, max over pairs); log popularity; artist-set overlap (primary-weighted); context (primary-artist counts in last 5 / 20, run length, family / language share of last 5, already-in-session); availability flags; FULL: decayed transitions (90-day half-life, age clamped ≥ 0), V-SKNN, ACT-R, heard. Per-group normalisation over id-sorted candidates | §1.2, §1.3 | 3 |
| P5 | Graceful degradation: exclusions (versions, unplayable, non-music: IS_MUSIC + Ringtones / Notifications / Recordings folders, absurd durations), ladder (nucleus → audio → descriptor → same family → least-recently-played; relaxation order), rule-only path (R4 quota 100 + 500-track salted sample, RULE_SET constants in code), lookahead buffer 2–3 hops with 150 ms deadline and per-rung budgets, circuit breaker (3 failures), heap-aware up-front bound, n = 0 / 1 / 2 / 3 rules, external-intent seeds, repeat-one / shuffle rules, playback-error stop after 3 consecutive errors and removed-volume exclusion (core:playback, Android + iOS) | §7.2, §7.2b | 3.5 |
| P6 | CORE and FULL behind "Learn from my habits" (off = CORE, on = FULL when its weights exist) | §1, §7 | 0.5 |
| P7 | Tests for every §7.1 scenario (below), property / metamorphic / fuzz / fault-injection tests, heap-limit emulator run at 48 MB with a 10k library, emulator product checks (Android Auto / external controller, process death, rescan, SD removal), iOS compile + iOS tests | §7.3 | 3.5 |
| P8 | Golden parity vs the Python reference per the pre-registered criterion (features / scores 1e-9 rel or 1e-12 abs, identical sampled queues; documented last-bit cases ≤ 1 % of hops), JVM and Native; release build (reduced ORT), register check, install | §1.6, §1.7 | 1.5 |

**Total ≈ 20 working days of app work, about 3–4 calendar weeks.** P0–P5 can start now against the spec; P8 needs
the trained model. This fits PLAN_FINAL's own timeline (Kotlin 10-24..11-05, freeze 11-07); the owner build comes after
parity, i.e. early November at the earliest.

## Scenario tests (each a host test unless marked)
Libraries: empty, 1, 5, 20 tracks; 10k+ at a 48 MB heap (emulator heapgrowthlimit recipe); one artist > 50 %; all
unresolved; no tags; mojibake; one genre; classical with composers; audiobooks / podcasts / very long; skits; zero /
corrupt / absurd durations; many versions of one song; duplicate files; mixed languages, CJK, RTL / Arabic credits
(NFKC, Turkish i); multi-artist / featured credits; non-music audio; marked playlists of 5k+. Histories: fresh; one day;
heavy 10k; habits off / on; upgraded log without press counter; clock / timezone change. States: partial audio index;
queued track deleted; volume unmounted mid-queue; rescan mid-queue (content-key identity); repeat-one; shuffle on; fast
skipping; Android Auto / external controller start (emulator); external file intent seed; process death and resume
(emulator); seeds with no neighbours, an unresolved file, only versions of itself. Invariants on all of them: no crash,
no empty queue while a playable track exists, same-song adjacency 0 unless forced, non-music never picked, determinism
for fixed keys, library-order invariance, latency ≤ 150 ms per hop and ≤ 2 MB extra per press.

## Needed from the research side (blocking only where marked)
1. **Blocking for P8:** the trained CORE (and later FULL) JSON — weights, normalisation stats (unlike proto_ranker2.json,
   which kept none), T, p, k, cap, quotas, RULE_SET constants, salt.
2. **Blocking for P8:** a Python serving reference runnable on fixtures: retrievers with quotas, features, per-group
   normalisation, the decoder with keys, the ladder and the rule-only path (the parity criterion compares against it).
   I can write the decoder reference from §1.6 if that helps; the features must come from the research code.
3. A device-side popularity table: FIT popularity is per recording, and the app cannot key tracks to recordings
   without MBID tags. Artist-level popularity (entity ids) is the feasible form; until it exists the feature reads
   "unknown" through its availability flag.
4. `artist_sets.py` frozen with its `artist_known` export (sha) — P2 can start on the current file.
5. The audio tagger (§1.3) after W6: until then the "imputed" path is wired and tested with a stub.

## Status (19 October 2026, evening; after PLAN_FINAL §8's draft)

| # | Package | Status |
|---|---------|--------|
| P0 | Asset schema + manifest | Done: serving block, `smart_manifest.json` (sizes + SHA-256, streamed), rule-only on mismatch. No separate kill-switch UI: the "Learned ranker" toggle returns to the old chain, rule-only is automatic |
| P1 | Decoder | Done per §8: argmax + run penalty, 2 : 1 mix, ε-near-best salted pick (SHA-256 → SplitMix64, h-th output), press persisted by content key. Softmax / quantisation / top-p not built (retired) |
| P2 | Artist sets | Done: port of artist_sets.py (230a6214), 6.4 MB key table; parity on 47 cases, 3,000 FIT credits, 31 strings; iOS simulator |
| P3 | Retrievers | Done: R1 audio, R2 co-listening, R4 artist set / album, R5 family, FULL R6 / R7; whole pool up to 600 eligible tracks. Python parity pending the serving reference |
| P4 | Features | Done: CORE + LFM + PERSONAL (parity on FIT) + duration (weight 0) + availability + imputed + audio term (rule) + artist overlap. Context features not built (dropped by §8). Tagger: real imputation with the FMA head (parity 1e-9) |
| P5 | Degradation | Done in the ranker and chooser: exclusions, dedup windows, ladder with relaxation, rule-only path, per-hop budget, circuit breaker (3), heap bound. Not built: playback-error stop after 3 errors and volume exclusion in the controllers (app layer outside the hashed engine; next) |
| P6 | CORE / FULL | Done: "Learn from my habits" selects FULL (proto_ranker FULL weights, p 1.9015) |
| P7 | Tests | Done for the ranker (20 scenarios + invariants, JVM and iOS; large library; assets; resilience). Emulator product checks (Android Auto start, process death, 48 MB heap with 10k) run with the build |
| P8 | Parity + build | Waits for the trained model and the Python serving reference; the LightFM int8 question is open (float32 ships: int8 changed FIT top-10s) |
