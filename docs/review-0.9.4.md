# 0.9.4 integration and adversarial review log

Branch `claude/integ-094-28bff4`, cut from `dev/0.9.4` (1730aa24). Nothing here is pushed.

## Phase 1: integration

### Merges (full refs, in this order)

| merge | branch | content | conflicts |
|---|---|---|---|
| 6bf4b2ff | `refs/heads/claude/analysis-094-939dfe` (59) | analysis speed and reliability, AUD-02, SCALE-01, LIFE-03, iOS container identities | none |
| 22bf9f44 | `refs/heads/claude/lockscreen-snackbar-094-4970f3` (7) | QS tile cold start #29, Android 14 fixes, lock-screen SAW ask, swipe snackbars | `MainActivity.kt`: analysis Continue intent and tile play request both kept |
| 425ee743 | `refs/heads/claude/entry-points-094` (4) | widget permission gate, M3U import | `MainActivity.kt`: MIME type for the opener, widget gate reopen, Continue and tile all kept |
| 075036db | `refs/heads/claude/beats-experiment-094-47adc9` (4) | beat-aligned fade entry, flag `FADE_ENTRIES_ON` stays OFF | `AppGraph.kt`: legacy container roots before the normalizer, fade entry after it |
| ffa5292f | `refs/heads/claude/release-build-094-cd5256` (16) | frozen F-Droid recipe, reduced ORT, CI signing behind approval, `tools/release/release.py` | none |

### Stand identity before the SMART port

Stand: `~/Documents/LJ/phone-state/stand/night-input-fresh`, the owner round 6 pipeline (CORE, press 0,
`LJ_STAND_SEMANTICS=1`, artist sets), 32 seeds (`smart-research/seq/owner_round/seeds`). Runs in
`~/Documents/LJ/integ094-stand`.

- The merged branch differed from the v0.9.3 stand on 26 of 32 seeds. `git bisect` found 8c2adbad (AUD-02: a
  short window is repeated instead of zero-padded). One-window tracks (unknown or at most 10 s) get the new audio
  identity `audio-v1-repeat`. The phone analyses them again; the stand has no audio, so its six short clips
  (memes and sound effects) dropped out of the library. Dropping them changes the library from 1108 rows to
  1102, and the hub anchor sample (1024 of n rows) with it.
- The runner switch `LJ_STAND_KEEP_SHORT=1` (test code only) keeps those six stored vectors under the new
  identity. With it, the merged branch equals v0.9.3 on **32/32** seeds (`base_keep`). This is a stand
  artifact, not an app regression: on the phone those six clips get new vectors anyway.

### SMART port (owner round 6 pieces, all ON)

Source: the research hook `research/smart-combo-094` (32001954, worktree `latentjam-combo`), switches
`LJ_STAND_CHAIN_HYGIENE=2 LJ_EXT_KEY_UNION=1 LJ_EXT_GENRE_SETS=1 LJ_STAND_THEME=<theme_gt> LJ_STAND_LANG=pack
LJ_STAND_LANG_SITES=mult LJ_STAND_HUB_BETA=0.25`, and X v5 from `review_ext/FIXES_NIGHT4.md`.

| piece | app code |
|---|---|
| CH hygiene | `ChainConfig.CHAIN_HYGIENE = 2` spreads, `ChainTuning.hygiene`: jarring kind (QueueClasses) and length outside 60 s–600 s, one unit each |
| K | `EntityAliases` (one key space, thread-safe), `TrackMeta.dedupKey` from the album's most credited artist / album artist / album / file |
| Gt | `Genres.families` = `GenreSets.families`, soft genre multiplier by overlap, `SMART_THEME_WEIGHTS` refit (`fit_theme_gt.py`, FMA only) |
| Ms | `TrackMeta.artistLanguages` / `languages` / `sharesLanguageWith`; main artists' pack languages via `ArtistKnowledge.entityFacts` |
| hub β | `ChainConfig.HUB_PENALTY_BETA = 0.25` |
| X v5 | `SimilarityEngine.artistExclusion`, `CreditSegments`, `SmartExclusionState.matcher`, `hidesFromSmart()` in App.kt |

Found while porting: the hook's global `Genres.families` switch also changed the Map's `LibraryWorld.supportsName`,
which compares against world names built in the old naming vocabulary ("rap", "dance", "anime ost"). With set
families a cover could never be refreshed (LibraryWorldsTest failed). The Map now uses `Genres.nameFamilies`,
its 0.9.3 behaviour.

**Parity.** On the round-6 stand (32 seeds, CORE, press 0, 40 picks per seed, `LJ_STAND_KEEP_SHORT=1`), the app
equals the hook's B arm on **32/32** seeds, every pick (`port1`, re-run on the committed HEAD as `port2`).

**Goldens.** `SmartPressFootprintTest.RECORDED_QUEUES` (common: JVM and iOS): the judged start changed through hub β
(β = 1.0 alone restores it); the judged top-up changed through hub β plus the pack languages (both reverted restore
it). Hygiene, genre sets and the key union do not move this fixture. The plain chain's three queues and both
snapshot digests are unchanged. `GenresTest` and `MetadataRerankTest` now assert the set families on purpose.

Commits: 2e295a77 (stand switch), 28f84679 (CH, K, Gt, Ms, hub β), 2a025857 (X v5).

## Phase 2: adversarial review rounds

Each round: six fresh reviewers (SMART engine, analysis/storage, playback/service/lockscreen, iOS, release/build/F-Droid,
UI/strings/i18n). Each got the code, `git diff v0.9.3..HEAD` for its area and a fixed brief (proof by trace, test or
read-only command; read only, no builds), never earlier findings or fixes. Every finding was checked against the code
before a verdict. Verdicts: **fixed** (real, changed with a test where a seam exists), **by design** (the evaluated or
documented behaviour; for the owner), **deferred** (real, low, logged for later).

### Round 1 (HEAD 2a025857)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| S1 | SMART | med | Ms: a pack ISO language ("de") is compared with the script fallback ("en" for any Latin), so a known German artist pays the cross-language penalty against an unknown German one | **by design**: this is rule S, the variant owner round 6 evaluated; the research also has rule K (a side without a pack language compares by script), which would fix it but is unevaluated. Owner decision |
| S2 | SMART | med | GenreSets maps "UK Garage", "Progressive House" → rock, "Happy Hardcore" → punk, "New Romantic" → classical; overlap then gives the full same-genre bonus against Rock | **fixed**: phrases for those electronic styles (and "chamber pop", "psytrance") before the single words; `GenreSetsTest`. The stand has two such tags ("Progressive House", "New Romantic"): parity with the B arm is now 31/32 seeds, the other seed reorders 3 House picks (positions 31–33 of 40) |
| S3 | SMART | med | X v5: excluding the band Live / Mono hid every "… - Live" / "… - Mono" title | **fixed**: a name that is a version word matches the credit only; `CreditSegmentsTest`. The bare "by" clause ("Killed by Death") stays: it is the measured X v5 leg (171/171 labelled true) |
| S4 | SMART | med | per-track language sets on every snapshot row (~3 MB at 10k) | **fixed**: interned per combination (`sharedLanguages`, engine `languageSets`) |
| S5 | SMART | med | exclusion matching (pack load, a credit parse per track) ran on the main thread in composition | **fixed**: `produceState` on `Dispatchers.Default` for the eligible list; For You and the library sync filter on Default |
| S6 | SMART | low | with `backgroundHub` the first press of a process plans with a zero hub penalty | **by design** (SCALE-01 trade-off, β is 0.25); see A4 |
| S7 | SMART | low | the track menu's "Don't recommend artist" state still matches the display credit only | **deferred** (UI follow-up) |
| S8 | SMART | low | the personal cluster guard's "primary family" changed meaning with set families | **fixed**: the guard reads `TrackMeta.guardFamilies` (the naming families PLAN_PERSONAL v6 was tuned on); no round-6 stand queue moves (32/32 against the run without it) |
| A1 | analysis | low | a stop posted before any run named the channel posted a foreground notification on a missing channel (fresh install crash, narrow race; 0.9.3 created it) | **fixed**: a blank title still creates the channel, named after the app |
| A2 | analysis | low | a run opened while the foreground gate is Lost runs without service and notice | **deferred** (narrow; Android 15 6 h cap during teardown) |
| A3 | analysis | low | `continueAfterStop` called `ensureAutomaticIndexing` from Default, racing the main-thread callers | **fixed**: hops to `Dispatchers.Main` |
| A4 | analysis | low | during the first analysis of a large library every chunk restarts the hub pass, so presses keep a zero hub penalty | **deferred**, with S6: let a started pass publish as a stand-in (0.9.5) |
| P1 | playback | low | a relaunch from Recents replayed the widget's access request and an opened file / M3U | **fixed**: the whole launch intent is consumed once (`fromHistory`) |
| P2 | playback | low | widgets kept "open the app" controls after access was granted in system settings | **fixed**: a cold activity with access refreshes the widgets |
| I1 | iOS | med | 0.9.3 → 0.9.4 on iOS with default settings (empty loudness cache) and a moved container re-analyses imported files once | **by design**, documented in 5c5c5c0a: nothing records the old root. The comments promised more; corrected. Release-notes line for the owner |
| R1 | release | low | Mac ORT cache key ignored the `.onnx` models | **fixed**: models in the key |
| R2 | release | low | `build.sh sign DIR --resign` signed nothing for Gradle-named phone builds, silently | **fixed**: takes `*-release.apk` with `--resign`, fails when nothing was signed |
| R3 | release | low | SBOM omits Microsoft's stock onnxruntime-android 1.26.0 that armv7/x86_64 APKs ship | **deferred** (owner: add to `sbom_assets.py`) |
| R4 | release | (note) | the 255-register gate ran for `assemble` but not `bundleRelease` (the Play AAB) | **fixed**: bundle waits for the gate |
| U1 | UI | low | Continue tapped while a paused pass is still preparing kept "Paused by you" until the first chunk | **fixed**: the live-pass Continue clears the shown pause |
| U2 | UI | low | Rebuild subtitle said "Pause to rebuild" while already pausing | **fixed** |
