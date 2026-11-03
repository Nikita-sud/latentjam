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
| S1 | SMART | med | Ms: a pack ISO language ("de") is compared with the script fallback ("en" for any Latin), so a known German artist pays the cross-language penalty against an unknown German one | first **by design** (rule S was evaluated); confirmed again by a second reviewer in round 2 with a failing test → **fixed in round 2** (rule K, see R2-S1) |
| S2 | SMART | med | GenreSets maps "UK Garage", "Progressive House" → rock, "Happy Hardcore" → punk, "New Romantic" → classical; overlap then gives the full same-genre bonus against Rock | **fixed**: phrases for those electronic styles (and "chamber pop", "psytrance") before the single words; `GenreSetsTest`. The stand has two such tags ("Progressive House", "New Romantic"): parity with the B arm is now 31/32 seeds, the other seed reorders 3 House picks (positions 31–33 of 40) |
| S3 | SMART | med | X v5: excluding the band Live / Mono hid every "… - Live" / "… - Mono" title | **fixed**: a name that is a version word matches the credit only; `CreditSegmentsTest`. The bare "by" clause ("Killed by Death") stays: it is the measured X v5 leg (171/171 labelled true) |
| S4 | SMART | med | per-track language sets on every snapshot row (~3 MB at 10k) | **fixed**: interned per combination (`sharedLanguages`, engine `languageSets`) |
| S5 | SMART | med | exclusion matching (pack load, a credit parse per track) ran on the main thread in composition | **fixed**: `produceState` on `Dispatchers.Default` for the eligible list; For You and the library sync filter on Default |
| S6 | SMART | low | with `backgroundHub` the first press of a process plans with a zero hub penalty | rated medium in round 2 → **fixed in round 2** (warm-up, R2-S2) |
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

Round 1 commits: 9dcff830 (S2), e8affabd (S3), 21f79177 (S4, S8, I1 comments), 7e838aac (S5), 59e43aa9 (U1, U2, A1, A3),
62e76ccf (P1, P2), b7167db6 (R1, R2, R4).

### Round 2 (HEAD 4a83ef10)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R2-U1 | UI | **high** | the eligible list produced off Main (round-1 S5 fix) started empty and the library effect handed it to playback: after an activity recreation every planned and queued SMART row was removed | **fixed** (b6893a44): `SmartEligible` carries its inputs; both effects wait for a list made for the current tracks and exclusions |
| R2-U2 | UI | low | while "Pausing…" is shown there is no Continue | **deferred** (whether an un-pause mid-pause is honoured depends on the gate's watcher; low) |
| R2-S1 | SMART | med | same as S1, with a failing test (known Ukrainian vs unknown Cyrillic) | **fixed** (3d905ec3): rule K, either side without a sung pack language compares by script. Round-6 stand: 20/32 seeds identical to B, mean top-15 overlap with B 14.62/15 (0.4 picks per queue move; B vs 0.9.3 is 11.1/15). **Owner: Ms now differs from what round 6 played; one line reverts it** |
| R2-S2 | SMART | med | first press per process without hub penalty (S6) | **fixed** (c7a23d45): `SimilarityEngine.warmSmart()` from the existing warm-up starts the background pass with a throwaway snapshot; test `a warm-up before the first press gives that press the exact hub…` |
| R2-S3 | SMART | low | "by Love" in a title hides songs when the band Love is excluded | **by design**: the measured X v5 leg (171/171 true on the labelled set) |
| R2-S4 | SMART | low | `EntityAliases` key sharing depends on which spelling is seen first | **deferred**: the evaluated hook has the same order dependence; a deterministic alias needs the pack's canonical names (0.9.5) |
| R2-S5 | SMART | low | track menu artist toggle is display-only (= S7) | **deferred** |
| R2-S6 | SMART | low | snapshot builds parsed each credit four times and kept an album map | **fixed** (c7a23d45): one parse per row, album map only when a row has no artist, dropped after the build |
| R2-S7 | SMART | low | "Nightcore", "Frenchcore" read as punk/rock through the "-core" head | **fixed** (c7a23d45) |
| R2-A1 | analysis | low | a replacement pass cancels the old run before opening its own notifier session | **deferred** (needs a busy Default pool; the in-body `cancelAndJoin` covers the common case) |
| R2-A2 | analysis | low | the retry ledger ignored the file revision | **fixed** (a950fb18) |
| R2-I1 | iOS | low | the first segmented save deleted the Caches copy promised to a 0.9.3 downgrade | **fixed** (fcf3a55e): `IndexStore.retire()`; `IosIndexRetireTest` |
| R2-P1 | playback | low | QS tile does nothing when the saved track is gone (not a regression: 0.9.3 played an empty player) | **deferred** |
| R2-R1 | release | low | `LATENTJAM_ORT_CACHE=0` without a work dir always failed | **fixed** (f4457fde) |
| R2-R2 | release | low | spaces: javac argfile in `ort_source_build.sh`, `--resign` glob | **fixed** for `--resign`; the ORT argfile is **deferred** (frozen F-Droid input, default paths have no spaces) |
| R2-R3 | release | low | app CI never runs the fdroidserver-backed recipe tests (9 skipped) | **deferred** (owner: install pinned fdroidserver in app.yml) |
| R2-R4 | release | low | CI port does not run fdroiddata `tools/check-fastlane.py` | **deferred** (owner) |
| R2-R5 | release | low | `.gitignore` did not cover `*.p12` | **fixed** (f4457fde) |

Round 2 had confirmed findings of medium or higher (R2-U1 high, R2-S1, R2-S2), so round 3 runs.

### Round 3 (HEAD 1564d809)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R3-S1 / R3-A1 | SMART, analysis (both) | **high** | the round-2 warm-up built a snapshot outside the press's `catch (Error)`: a heap too small for the snapshot crashed the app ~5 s after every launch | **fixed** (e73b7db7): gated on analysed rows like a press, catches `Error`, stops for the process |
| R3-S2 / R3-A2 | SMART, analysis (both) | med | the warm-up did nothing once any hub existed, so the first press after new analysis used a stand-in | **fixed** (e73b7db7): a cached snapshot answers it, a new one starts the pass; test after a change of rows |
| R3-S3 | SMART | med | the K placeholder repeat key used the bare file name: untagged `01.mp3` in two folders blocked each other | **fixed** (e73b7db7): a file-name identity includes the folder; test. Stand unchanged (32/32 vs round 2) |
| R3-S4 | SMART | low | "by Death" in a title (= R2-S3) | **by design** (measured X v5 leg); third report, owner may drop the bare "by" |
| R3-U1 / R3-P1 | UI, playback | low | presses read the eligible list without checking it is current | **fixed** (c72138d1): `eligibleFor()` |
| R3-U2 | UI | low | track menu toggle display-only (= S7) | **deferred** |
| R3-P2 | playback | low | two overlapping exclusion changes: the older list could land last | **fixed** (c72138d1) |
| R3-I1 | iOS | low | iOS never sees a single file a downgraded build wrote | doc **fixed** (Android-only note); behaviour deferred |
| R3-I2 | iOS | low | a failed move made `retire()` delete the 0.9.3 file | **fixed** (c8aa96dd) |
| R3-A3 | analysis | low | the text index is still one file, refused above ~41k tracks (as in 0.9.3) | **deferred** (0.9.5: segment it like audio) |
| R3-R1 | release | low | a failed clone removed a user-named ORT checkout | **fixed** (6568e495) |
| R3-R2 | release | low | two worktrees with one ORT key share work dir and repo | comment **fixed**; lock deferred |
| R3-R3 | release | low | `fdroid-mr` reset an MR branch with hand commits | **fixed** (6568e495): refuses when the branch holds commits it did not make |

Round 3 had confirmed findings of medium or higher (all in the round-2 warm-up and the K placeholder key), so round 4 runs.

### Round 4 (HEAD 427ca30a)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R4-A1 | analysis | med | presses during a long analysis (each chunk kills the background hub pass) and before the warm-up plan with a zero or stale hub | **fixed** (d5dafb00), and with it the whole class (S6, A4, R2-S2, R3-S2, R4-S4): a build reuses a kept hub only for exactly its rows and otherwise computes it inline as 0.9.3 did. **Trade-off for the owner:** SCALE-01's faster presses remain only for rebuilds over unchanged audio rows; the first press after an audio change pays the hub as in 0.9.3 (≈2 s at 20k on the emulator) |
| R4-S1 | SMART | med | X v5 title legs: "Killed by Death" hidden by excluding Death, Bach "… - Air" by excluding Air | **fixed** (0fa7f0a9): bare "by" only in brackets; a credited track's later " - " parts only when the pack knows them. The 11 X3 units still pass |
| R4-S2 | SMART | low | `EntityAliases` order dependence (= R2-S4) | **deferred** |
| R4-S3 | SMART | low | the warm-up rebuilt a snapshot when the hub was already current | **fixed** (d5dafb00) |
| R4-S4 | SMART | low | early presses before the warm-up | **fixed** with R4-A1 |
| R4-S5 | SMART | low | hygiene's length unit ignores the seed's own length (a 12-min classical seed pushes away from long tracks) | **by design** (CH as evaluated); owner may condition it on the seed |
| R4-A2 | analysis | low | Android decoded the whole loudness cache every pass for iOS-only roots (OOM risk at 50k/48 MB) | **fixed** (899502f6): `AnalysisControlStore.keepsContainerRoots` |
| R4-P1 | playback | low | an exclusion change finishing after a rescan put the older library back | **fixed** (899502f6) |
| R4-P2 | playback | low | QS tile does nothing when no saved track resolves (= R2-P1) | **deferred** |
| R4-I1 | iOS | low | a failed Caches → Application Support copy left its files behind, every launch | **fixed** (308b7b7a) |
| R4-R1 | release | low | release/** pushes did not run app.yml (unit tests) | **fixed** (55ed9c14) |
| R4-R2 | release | low | `release.py bump` not re-runnable (burnt a versionCode) | **fixed** (55ed9c14): computed from the last tag |
| R4-U1 | UI | low | "Paused by you · 0 of N" for seconds after a cold start while paused | **deferred** |

Round 4 had confirmed medium findings (R4-A1, R4-S1), so round 5 runs.

### Round 5 (HEAD 1de71e53)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R5-R1 | release | med | app.yml now ran on release branches but nothing required it: a red unit-test run could still be tagged and signed | **fixed**: `dry_run_problems` requires a green app.yml run for the release commit |
| R5-S1 | SMART | med | "(prod. by X)" never matched an excluded producer (the prod. alternative captured "by X") | **fixed**, test |
| R5-S2 | SMART | med | track menu toggle still display-only (S7, R2-S5, R3-U2): a hidden track offered exclusion again, and could never be included | **fixed**: `artistsHiding()` off Main; include removes every hiding name |
| R5-S3 | SMART | low | a credited "- Air" / "- Adagio" hid movements when the pack knows a band of that name | **fixed**: movement vocabulary, test |
| R5-A1 | analysis | low | a run opened during the old run's teardown after the 6 h cap stays in Lost (= A2) | **deferred** |
| R5-A2 | analysis | low | KDoc still described the unreachable background pass | **fixed** (doc); the dead `startHub`/`runHub` code stays for a later return |
| R5-P1 | playback | low | tile shows active and ignores taps 20 s when a press cannot start playback (a call, an unreadable queue) | **deferred** |
| R5-R2 | release | low | preflight does not verify that the `release` environment requires the owner's approval | **deferred** (owner: check the GitHub environment settings once) |
| R5-R3 | release | low | NDK pin check does not compare the blocks' `ndk: r28c` | **deferred** |
| R5-R4 | release | low | SBOM omits Microsoft's ORT for armv7/x86_64 (= R3) | **deferred** |
| R5-I1 | iOS | low | a clear done in 0.9.3 after a downgrade is undone on re-upgrade | **deferred** |
| R5-I2 | iOS | low | a move that keeps failing on a full disk retries each launch | **deferred** |
| R5-U1 | UI | low | "Paused by you · 0 of N" after a cold start (= R4-U1) | **deferred** (needs a new string in 22 languages) |
| R5-U2 | UI | low | "Only retry" while paused clears the rows but analyses nothing until Continue | **deferred** (wording or behaviour: owner) |

Round 5 had confirmed medium findings, so round 6 runs.

### Round 6 (HEAD dd199149)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R6-S1 | SMART | med | "Hardcore Hip-Hop", "Emo Rap", "Horrorcore" read as punk/rock (full same-genre bonus with Rock) | **fixed** (be7e12b4), test |
| R6-S2 | SMART | med | hygiene (2 spreads) on top of QueueClasses' substring matches: "Memento Mori" as meme, "Post-Rock" as ost paid 3 spreads | **fixed** (be7e12b4): the five short class words whole-word (PLAN_FIXN2 Q, radio never before edit/mix/version), test |
| R6-S3 | SMART | med | hygiene's length unit after a long (or very short) seed (= R4-S5, second report) | **fixed** (be7e12b4): applies only after a seed of ordinary length, test |
| R6-S4 / R6-A3 | SMART, analysis | med | the warm-up built and dropped a full snapshot at every launch, also without SMART use, under the engine lock | **fixed** (e533d486): warm-up call removed (0.9.3 behaviour; the first press computes the hub) |
| R6-A1 | analysis | low | run opened after the 6 h cap stays without service (A2, R5-A1; third report, with a test) | **fixed** (340b3572): sessions opened after the loss start the service, test |
| R6-A2 | analysis | low | a D2-held track is not retried after its hour within one process | **deferred** |
| R6-U1 | UI | low | "Paused by you · 0 of N" (third report) | **deferred** (new string in 22 languages) |
| R6-U2 | UI | low | track menu artist toggle: a tap before the async answer did the wrong action | **fixed** (eba110c5): decided on the tap |
| R6-I1 | iOS | low | a clear left a killed write's `.tmp` | **fixed** (91e7fd9d) |
| R6-P1 | playback | low | tile active 20 s after a press that cannot start (= R5-P1) | **deferred** |
| R6-R1 | release | low | `ndk:` lines not checked against `ndk_version` (= R5-R3) | **deferred** |
| R6-R2 | release | low | an interrupted default ORT clone makes the next run fail once | **deferred** |

Stand after round 6 (32 seeds, 40 picks, `LJ_STAND_KEEP_SHORT=1`): 17/32 seeds identical to the evaluated B arm; mean
top-15 overlap with B 14.19/15 (B against 0.9.3: 11.12/15). The moves come from R2-S1 (rule K), R1-S2 and R6-S1
(genre phrases), R6-S2 (whole class words) and R6-S3 (seed-aware length). Round 6 had confirmed medium findings, so
round 7 runs.

### Round 7 (HEAD 80800974)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R7-S1 | SMART | med | "birthday" as a kids text word now cost 3 spreads (The Birthday Massacre, The Birthday Party, "Birthday") | **fixed** (f47949d1): "happy birthday" only, test |
| R7-S2 | SMART | low | modifier words: "Symphonic Metal" ⊇ classical, "Dub Techno" ⊇ reggae | **fixed** (f47949d1): phrases, test |
| R7-S3 | SMART | low | config doc still described the warm-up | **fixed** (f47949d1) |
| R7-S4 | SMART | low | length hygiene in classical/prog libraries after an ordinary seed (a 13-min movement after a 7-min one) | **by design** (CH as evaluated; owner may lower the unit or exempt shared album/work) |
| R7-A1 | analysis | med | a failed segmented save (full disk) left its segments; retries piled more up | **fixed** (1e7c32de): attempt's segments removed before the manifest is written, test |
| R7-A2 | analysis | med | API 30–34: the 0.85 default under a HAL's resting headroom held analysis in an endless heat wait (regression: 0.9.3 had no gate) | **fixed** (6cdec3c3): without the device threshold headroom counts only with status ≥ LIGHT, status NONE ends the wait, tests |
| R7-P1 | playback | med | API 34 tile fallback: the app's launch restore could replace and pause the queue Media3's resumption had just started | **fixed** (b598e35b): a restore yields to a playing platform queue |
| R7-U1 | UI | low | an M3U opened before the media permission was used up against an empty scan | **fixed** (b5dbda19) |
| R7-U2 | UI | low | "Paused by you · 0 of N" (fourth report) | **fixed** (b5dbda19): the count is shown once known, no new string |
| R7-R1 | release | low | build.sh removed a pre-existing ORT directory when its clone failed | **fixed** (26b09216) |
| R7-R2 | release | low | `ndk:` not checked (= R5-R3) | **deferred** |
| R7-R3 | release | low | app CI skips the 9 fdroidserver tests (= R2-R3) | **deferred** |
| R7-I | iOS | — | no findings | — |

Stand after round 7: 15/32 seeds identical to B (the R7-S1 class word moved 4 more seeds). Round 7 had confirmed
medium findings, so round 8 runs.

### Round 8 (HEAD 99319083)

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R8-U1 | UI | med | the round-7 M3U gate opened while the catalog was still the old empty one being rebuilt after the permission grant | **fixed**: the catalog records its source list, the gate needs it to be the current tracks |
| R8-S1 | SMART | low | modifier/compound genre spellings: "Alternative R&B", "Baroque Pop", "Synth-pop" vs "Synthpop", "New Wave", "Rhythm & Blues" | **deferred**: a fix moved 9 of 32 round-6 stand seeds ("New Wave", "Synth-pop" are common there), too large a drift from the queues the owner heard for a low finding; 0.9.5 with a new owner check |
| R8-S2 | SMART | low | "Carol of the Bells" as spoken word, 3 spreads with hygiene | **fixed** (stand unchanged) |
| R8-S3 | SMART | low | `EntityAliases` first-spelling order, and a race between the exclusion filter and a snapshot build (= R2-S4) | **deferred** (0.9.5: alias to the pack's canonical name) |
| R8-A1 | analysis | low | a run replaced while joining its predecessor ended at once; the newest run could start beside the old run's cleanup | **fixed**: joins the predecessor NonCancellable before closing |
| R8-A2 | analysis | low | Android cold start reads each audio segment twice under the engine lock | **deferred** (0.9.5: hash during the delivering read) |
| R8-A3 | analysis | low | each checkpoint still does O(N·dim) work under the lock (as 0.9.3) | **deferred** |
| R8-P1 | playback | low | tile with an unresolvable saved queue (= R2-P1) | **deferred** |
| R8-P2 | playback | low | the exported MainActivity accepts the Continue action and tile-play extra from any app (no data exposure; analysis resumes or the saved queue plays) | **deferred** (0.9.5: trampoline activity) |
| R8-R1 | release | low | `ndk:` lines (= R5-R3) | **deferred** |
| R8-R2 | release | low | the app.yml gate could be met by a pull-request merge run | **fixed**: `--event push` |
| R8-R3 | release | low | `LATENTJAM_ORT_WORK` set to a broad directory deletes generic sibling folders there | **deferred** (owner sets it only in CI) |
| R8-R4 | release | low | `fdroid-mr` would open a second MR for 0.9.5 while 0.9.4's is open | **deferred** |
| R8-I | iOS | — | no findings | — |

Round 8 had one confirmed medium (R8-U1, introduced by the round-7 fix), so round 9 runs.

### Round 9 (HEAD 71570d22): no finding of medium or higher

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| R9-S1 | SMART | low | `EntityAliases` first-spelling order (= R2-S4) | **deferred** |
| R9-S2 | SMART | low | an uncredited track's repeat key guesses the album artist from the album title alone (two "Greatest Hits") | **deferred** |
| R9-A | analysis | — | no findings | — |
| R9-P1 | playback | low | the restore guard read the controller's possibly lagging view | **fixed** (ece8cf05): also yields to a resumption the service announced |
| R9-I1 | iOS | low | in the Caches fallback the stale single file is decoded on each launch | **deferred** |
| R9-U1 | UI | low | an M3U opened without media access waits silently until access is granted | **deferred** |
| R9-U2 | UI | low | leaving the page during Rebuild's join skipped the clear and restart | **fixed** (a689f98e): `rebuildAnalysis` runs NonCancellable |
| R9-R1 | release | low | `ndk:` lines (= R5-R3) | **deferred** |
| R9-R2 | release | low | a Mac-built IPA was not tied to a commit | **fixed** (d7be2f9a): `build.sh ios` refuses a dirty tree |
| R9-R3 | release | low | a partly failed `draft` job cannot be re-run | **deferred** (owner: delete the draft by hand) |
| R9-R4 | release | low | `fdroid-mr` rendered the working tree's version against HEAD | **fixed** (d7be2f9a) |

**Stop criterion met:** six fresh reviewers found no confirmed finding of medium or higher. The few low fixes
made after it were checked by one more fresh reviewer on `71570d22..HEAD` (below).

## Owner report: widget corners after a reboot

«Иногда после перезагрузки телефона, слева острые углы и скругление теряется» (screenshot: Pulse Strip widget, the
left part with the artwork and the purple cloud has square corners, the dark right part stays round).

- **Cause.** The song-coloured cloud (`widget_cloud`) is a rectangular bitmap stretched over the card; only the
  root's `android:clipToOutline` rounded it. Outline clipping needs a hardware-accelerated canvas. A launcher that
  shows a software-drawn snapshot of its widgets, as One UI does while it restores the home screen after a reboot,
  skips that clip: the card's own shape drawable stays round (right side) and the cloud's square corners show (left
  side). The 0.9.3 widget has the same cloud, so the report is not a 0.9.4 regression.
- **Fix** (e15ff6ae). The cloud bitmap is clipped to a rounded rectangle whose radii, after the `fitXY` stretch over
  the widget's own size (`getAppWidgetOptions`), are the card's 24 dp. It no longer depends on the clip at all, on any
  API from 29 to 37 and in any launcher.
- **Check.** `WidgetCloudCornersTest`: the stretched corners equal 24 dp for Pulse sizes 280×72, 360×96 and
  412×110 and for Deck; a tiny widget never gets more than half its side. Not reproduced on an emulator: the
  emulator's Launcher3 draws widgets with hardware acceleration and has no software snapshot step. The owner's
  phone after a reboot is the real check.

## After round 9: the restore guard, checked twice

| # | area | sev | finding | verdict |
|---|---|---|---|---|
| P9-1 | playback | medium | the guard (ece8cf05) was read after `controller()` had adopted the announced resumption, so it missed it on a cold connect | **fixed** (a3ab2aad): also yields to `anticipatedResumption` |
| P9-2 | playback | medium | **regression of a3ab2aad**: a widget button on an empty session announced the resumption it then restored itself; the restore yielded to it and the player stayed empty under the saved track (Next and Previous did nothing; Play still worked through Media3) | **fixed** (dc56fb3e) |
| P9-3 | playback | medium | **also in 0.9.3**: System UI's boot-time "recent media" query (`onPlaybackResumption` with `isForPlayback = false`) counted as a real resumption; the launch restore then never ran and a stale shuffle mode waited for the next queue | **fixed** (dc56fb3e) |

dc56fb3e: `MediaBrowseRegistry.resumption` takes `forPlayback`. Only a queue the platform installs and plays (a play
press, Media3's resumption with `isForPlayback`) is announced and sets the pending shuffle mode. The widget restores
without announcing, and System UI's card only reads. The verifier confirmed the rest: a normal launcher cold start
cannot skip the restore (nothing is announced in a fresh process, a failed resumption announces nothing, an expired
entry clears after 60 s), and a Media3 resumption wins in both orders.

A fresh reviewer then checked dc56fb3e alone against Media3 1.10.1's bytecode, and found no high or medium issues.
Every real install still passes `isForPlayback = true` and is announced: a headset or Bluetooth press, the API 34
tile, Auto, a voice request, and a tap on the boot card. The widget hydrates through its own sync. Two lows are
**deferred**: the boot query may still set a null `queueSource`, and no host test covers `resumption(false)`.

## issue #1: SMART never stops

Owner: «Аккуратно с заканчиванием очереди… smart просто останавливался. Смотри чтобы не было регрессии.»

**What keeps a SMART queue going.** The playback layer (`PlaybackController.android.kt` top-up, `SmartQueueExhaustion.kt`,
`PlaybackPendingWork.kt`) tops up on each transition from the SMART-eligible library less every queued and removed
track. An empty plan or an engine exception (`awaitPlaybackRecommendation` turns it into null) falls back to a
continuation (`EngineNextTrackChooser.continuation`: the least recently heard candidate), at most
`SMART_CONTINUATION_BATCH` = 3 ahead. After the queue ran dry, `smartResumeIndexAfterExhaustion` restarts playback
on the new rows. The queue ends only when no eligible track is left unqueued. **None of these files changed from
v0.9.3 to 0.9.4** except fade-entry plumbing (flag off) and the restore guard (above).

**0.9.4 changes against "never stops":**

| change | can it starve the queue? | evidence |
|---|---|---|
| CH hygiene | no: soft spreads on the score, never a filter | `QueueHygieneTest`; mixed-library test below |
| K placeholder dedup | no: a repeat key spreads, it does not remove; the stand's 32 seeds all fill | stand runs; `SmartNeverStopsTest` |
| X v5 exclusions | shrinks the eligible library by design; refills until the pool is spent | pool-shrunk test below |
| Gt / Ms | scoring only | stand runs |
| kept hub / starvation guard | the press computes the hub inline when it is not kept; an out-of-memory snapshot plans from metadata | `SmartPressFootprintTest`, `SmartQueueAvailabilityTest` |
| beat fades | flag off; transitions unchanged | `FadeEntryTest` |
| Continue / restore | the restore never yields to a resumption nobody installs (P9-2, P9-3) | verifier trace |
| lockscreen / tile | start or resume only; the top-up path is the same | — |
| LIFE-03 segmented rows not ready | unanalysed tracks are skipped by the engine; the continuation still plays them | partly analysed test below |

**Regression tests** (`SmartNeverStopsTest`, 77c5f023). They use the engine as `AppGraph` configures it: judged
scoring, artist sets, queue classes 1.0, theme weights, kept hub. The library has 3,000 tracks with mixed metadata:
features, unknown artists, birthday, interview and meme titles, clips of 8 s and 45 s, 15-minute tracks, seven genres
and four languages. Each run tops up 12 at a time from the queue's last track, as playback does.

- mixed library: refills through its last track, about 250 top-ups;
- pool shrunk by "Don't recommend artist" to 3 of 40 artists: refills until the pool is spent;
- 20 % not analysed: refills until no analysed track is left, more than 200 top-ups (the continuation layer plays the rest);
- all but 8 of 600 tracks heard in the last hours: a full plan, then the last 8 are all planned;
- engine rebuilt from its stores (kill or reboot): continues the restored queue with 12 new tracks.

All pass: 29 s on the JVM host, and on the iOS simulator the 3,000-track runs take 2 to 9 minutes in a Kotlin/Native
debug build (timeouts 20 minutes).

**Emulator session played to the end** (dc56fb3e debug, arm64, LJ-api37-lock, muted, `-no-audio`). The library was
42 tracks: 40 synthetic 25 s files and two 3-minute silences, all analysed. Started with "SMART from here" and played
41 advances (39 natural track ends, 2 Next): every step PLAYING, 42 distinct tracks, no repeats. SMART planned
12, 12, 12, then the last 5 unqueued tracks; the queue grew from 21 to the whole library. After the 42nd track the
player stopped on the last row, which is the 0.9.3 behaviour once no eligible track is left unqueued. Log:
`~/Documents/LJ/integ094-stand/dc56-smart-never-stops.txt`.

**Widget after a force-stop** (same build): Pulse Strip, queue of 5 paused on track 0, `am force-stop`. On API 37
the launcher first shows a stopped app's widget as a placeholder; after it redrew, Next gave queue 5, item 1. A cold
launcher start restored queue 5, and a tap on a queue row played it.

## Final checks

- JVM host tests (all modules), iOS simulator tests for `core:smart` and `core:playback`, and
  `checkNativeIdentifiers` on 7149894d: pass.
- Android debug and release builds, the iOS simulator build, the F-Droid recipe (arm64 and armeabi-v7a), the R8
  register gate (max 243) on d7be2f9a: pass. The commits after it touch only playback services, the widget, tests
  and docs, not the `App()` lambda; the debug build of dc56fb3e builds.
- Release launch on emulators (d7be2f9a, versionCode 442): API 30 and API 37 start, no VerifyError or FATAL.
- 10k songs on LJ-api30 (d7be2f9a): no out-of-memory at 48m or 96m. Idle 17.2 MB (1564d809: 20.0), press 28.9 MB.
  The first plan after analysis is 6.1 s at 48m and 4.6 s at 96m, because the hub is computed inline (1564d809:
  3.7 s and 3.5 s). Top-ups take 0.3–0.5 s. Details: `~/Documents/LJ/integ094-stand/emulator-checks-final.md`.
