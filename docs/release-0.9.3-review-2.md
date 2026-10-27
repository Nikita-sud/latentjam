# 0.9.3 release review, second loop

Second adversarial review of what ships in 0.9.3 (`git diff v0.9.2..HEAD`), with priority on what landed after the
first loop (`git log 292077cb..a80cb464`, first log: `docs/release-0.9.3-review.md`): the SMART candidate merge
(entity fix, keep together in the ranker, PLAN_NOVELTY / PLAN_093 keys), the index-restore fix and its merge with the
#27 per-track deadline, the R8 register fix, the Plays sort (#28), Recently played by real listens, the duplicates
marquees, the lock-player layout fix and the crossfade seek-into-window fix. Fixed on branch
`claude/release-093-review-2-0ccad2`, on top of `a80cb464`.

Method, per round: one fresh reviewer subagent per area, then every finding goes to a second, independent subagent
that verifies it against the code (CONFIRMED / REJECTED with file:line). Only confirmed findings count. Fixes land as
small commits with tests that fail before and pass after. SMART findings that would change queues are written up as
proposals, not applied. A round with no confirmed findings ends the review (cap: six rounds).

Baseline at `a80cb464`: all checks green; `check-dex-registers.py` most registers 249 (`SmartRanker.planLocked`,
limit 255).

## Round 1

Four reviewers: S SMART candidate + register fix · I index restore + #27 deadline · X crossfade (Android + iOS) ·
U Recently played, Plays sort, UI, release hygiene, R8, `App()` size. 15 findings, all confirmed (most narrower than
reported); 3 are SMART proposals, 3 left as notes.

### S, SMART candidate (all queue-changing → proposals)

PLAN_NOVELTY / PLAN_093 are off by default: every parsing default leaves plans unchanged, and the shipped
`smart_ranker.json` / `smart_ranker_full.json` are byte-identical on Android and iOS, unchanged since 292077cb, with no
`softNovelty`, `ownExtra` or `transitionKappa` key. Keep together: no marks → no bonus, no RK retrieval, identical plan;
bounded work; no crash on a marked seed, deleted tracks or duplicate groups. The `QueueExtras` refactor of
`RankerQueue.build` moved four inputs verbatim (no side effects in the arguments). cb194c2f's out-of-line helpers
(`semChainFor*`, `hubPenalties*`, `retrieve*`) match the keep rules.

| id | finding | verdict | outcome |
|---|---|---|---|
| R1-S-1 | The foreign-entity rule stops at the ranker: the text string (`DefaultSimilarityEngine.textString` via `TextLanguage.word`) and `HabitFlow.known()` still take the rejected pack entity's language, so "Базовый минимум" by "Sabi & …" still embeds as "english"; with the pack descriptor dropped, the seed's semantic term is now text-only | CONFIRMED-narrower, low–medium (only tracks without a language tag; not a regression in that code) | **proposal**: apply `foreignEntity` in `textString()` and `HabitFlow.known()` (affected tracks re-embed); measure on the stand first |
| R1-S-2 | Uzbek / Azerbaijani (and rarely Moldovan "ro", Bosnian "hr") entities are rejected on their own Cyrillic titles: "uz", "az", "ro", "hr" are in the Latin-language set | CONFIRMED-narrower, low (new in 86b89b9c) | **proposal**: per-language allowed scripts, or exempt uz/az when the other script is Cyrillic |
| R1-S-3 | Letter counting weighs one CJK character like one Latin letter; NFD Hangul counts per jamo; CJK Ext-A/B and half-width katakana count as neither | CONFIRMED-narrower, low (a missed rejection is the 0.9.2 behaviour; the reviewer's "feat." example does not reach the rule, since the entity comes from the artist credit) | **proposal** (optional): weight CJK/Hangul characters, NFC-normalise titles |

Noted, config-only: `keepTogether.share` = 0 would make the bonus NaN; no shipped asset sets it.

### I, index restore + #27 deadline

Merge 5d98d990 lost nothing from either parent; retry flags change only under the engine mutex; damaged vs unreadable
files are classified correctly; `analysing` is cleared in `finally`; cancellation propagates; model load is not counted
against the deadline; the 2 min stall notice sits above the 90 s budget; logs carry no track ids, titles or paths.

| id | finding | verdict | fix |
|---|---|---|---|
| R1-I-1 | A stored index that stays unreadable (OOM on a 48 MB heap, an I/O error) leaves every track "could not load its bundled models", and "Rebuild analysis" does nothing this session: `clearAnalysis` never reset `audioRestoreFailed` / `audioRestoreRetried` / `textRestoreFailed` | CONFIRMED, medium-low | c87a41e1 (reset after both deletions succeed, and in `release()`; no string change) |
| R1-I-2 | The one retry is skipped when a SMART seed was embedded first (`restorePersistedIndex` returned at `index.size > 0`): no analysis for the rest of the session | CONFIRMED-narrower, low | df1b8b83 (the retry still reads and adopts only ids not in memory; a failed read still blocks every save) |
| R1-I-4 | On 1-worker (low-RAM) Android, a re-decode / head retry of the current track queued behind a hung look-ahead on the same pool, so a healthy track timed out and was remembered as failed | CONFIRMED-narrower, low | d506e74a (re-decodes on their own one-thread pool, abandoned with the main one) |
| R1-I-5 | A pass cancelled while a decode hung never abandoned the stuck worker; on 1-worker devices the next pass's first healthy track timed out | CONFIRMED-narrower, low | 6b62a52f (abandon when cancelled work has not completed; the `ExecutorDecodeWorkers` comment about dropped work corrected) |
| R1-I-3 | A retry that succeeds after the launch sync keeps stale rows of changed or deleted tracks | CONFIRMED-narrower, very low (needs the launch read to fail and the retry to succeed) | note for 0.9.4 |
| R1-I-6 | The 90 s budget is "3 × the 30 s window guard" in its KDoc, but re-decodes and the head retry count against it too; a slow-but-working track can become a remembered "timed out" | CONFIRMED-narrower, low (much slower than any measured track) | note for 0.9.4 (remember only a decode that never returned, or widen the budget) |
| R1-I-7 | iOS: a timed-out native call keeps one of 64 shared I/O threads (`abandon()` is a no-op); `IosFileIndexStore` still turns read errors into "no snapshot" (unchanged since v0.9.2; the index lives in purgeable Caches) | CONFIRMED, info / pre-existing | note for 0.9.4 (parity: throw `IndexRestoreFailure` when the file exists but cannot be read) |

### X, crossfade

Traced clean on Android: repeat-one and the same track next, album continuation, sleep timer, slider changed or set to
0 during the wait, Next during a late wait and after a late mix, a seek back out of the window, two seeks within it, a
seek to exactly `mixStartUs`, a seek past the end, pause during the wait, stale state into the next track. iOS cues the
next track at once after a seek (opening a file is synchronous) and overlaps from where the overlap has got to: no
parity gap.

| id | finding | verdict | fix |
|---|---|---|---|
| R1-X-1 | Regression from a80cb464: a seek into the last moments of a track waited for the opening past the end, so with the opening not ready the track ended at full level (before: faded) | CONFIRMED, low | 274bc160 (the seek wait stops 400 ms before the end, so the fade can reach silence; a80cb464's own test still passes) |
| R1-X-2 | Regression from a80cb464: the after-seek wait was bounded by the slider, not the seam's overlap; with a shorter overlap (adaptive, a short next track) the next track cut in ~0.95 gain well into its opening | CONFIRMED-narrower, low (only when the overlap is shorter than the slider) | 274bc160 (bounded by `min(3 s, overlap / 2)` like a late opening without a seek) |

### U, Recently played, Plays sort, UI, release hygiene

Clean: Recently played / Rediscover by `lastListenedAtMs` (never-listened, cleared history, imported listens, skipped
starts); Plays sort persistence (unknown names fall back per page, ties keep stored order, label in all 22 locales);
lock-player button and output chip; duplicates marquees (style, Reduce Motion); debug rows gated by
`isDebuggableBuild()`; Map refresh on by default on purpose (9416a37d); `values-in` == `values-id`, `values-iw` ==
`values-he` (bodies), no `%%`; WorkManager's merged permissions and components already declared or guarded; keep rules
match real names; by-name enum reads in `runCatching`; no new logs with titles or paths; `App()` itself got smaller.

| id | finding | verdict | fix |
|---|---|---|---|
| R1-U-1 | Android Auto listed a playlist sorted by Plays in its Custom order (`tracksInChosenOrder` without play counts) | CONFIRMED-narrower, low (the car plays the tapped row; the visible problem is the list order) | a991dee6 (counts read only when a playlist is sorted by plays) |
| R1-U-2 | A resume that rebuilds a Plays-sorted playlist (source not saved: > 10,000 rows, or an unreadable queue save) used Custom order after the resumed track | CONFIRMED-narrower, very low | a991dee6 (`playsForResume`, both resume paths) |
| R1-U-3 | An open Favorites page sorted by Plays re-sorted after every listen (a playlist page does not), moving the song just heard and parting the page from its queue | CONFIRMED-narrower, low | de3e213d (kept while the hearts are unchanged) |

### Round 1 checks

At 00d45c46: `:core:{smart,history,playback,library}:allTests`, `:composeApp:testAndroidHostTest`,
`:composeApp:compileKotlinIosSimulatorArm64`, `:androidApp:assembleRelease` green; most dex registers 249 (limit 255).

## Round 2

Four fresh reviewers saw both logs and reviewed the round-1 fix commits first, then their areas again. SMART: **no
confirmed findings** (two notes below). 5 findings elsewhere, all confirmed; three were introduced by round-1 fixes.

| id | finding | verdict | fix |
|---|---|---|---|
| R2-I-1 | From d506e74a: the separate re-decode thread also ran on low-RAM devices, kept to one decode worker for memory, so a re-decode ran beside the next look-ahead (+1 decode window, ~1–3.5 MB heap, and a second codec) | CONFIRMED-narrower, low (an OOM inside the re-decode is caught; no new crash path) | e0387249 (separate thread only with more than one worker; low-RAM devices back to one decode at a time, R1-I-4 reopened only there, as in 0.9.2) |
| R2-X-1 | Since f140acb6 (not in 0.9.2): the plain wait for an opening (no seek) had no end guard; a track under ~6.8 s with a slider ≥ 6 s whose opening stalls (or a skip into it before its decode finished) ended at full level | CONFIRMED-narrower, very low (the opening's decode starts at the track's start for tracks under 22 s) | 4f06182b (every wait stops in time for the 400 ms fade) |
| R2-U-1 | From a991dee6: the new play-count read was bare; a history that cannot load (I/O, OOM after a large import) would crash the launch restore of a Plays-sorted, unsaved playlist source on every launch and empty Android Auto's browse tree | CONFIRMED-narrower, low (needs all three conditions); the claimed new race was already there (`setShuffleMode` suspends) | 3c9cba1d (best-effort, falls back to the stored order) |
| R2-U-2 | From de3e213d: a Favorites page sorted by plays opened before the first history read was sorted with every count at 0 and then frozen | CONFIRMED (Favorites; a playlist page sorted by plays has the same gap since 1692b5c5) | 30fb951c (keeps its order only after a refresh with loaded counts ran to its end) |
| R2-U-3 | The #27 stalled-analysis sentence put "Title — Artist" in unwrapped (every other title in a sentence is `bidiWrap`ped): RTL reordering | CONFIRMED, cosmetic (new in 0.9.3) | 6c047ffe |

Round-1 fixes re-reviewed and held: c87a41e1 (flags reset under the engine mutex, kept on a partial clear failure),
df1b8b83 (this session's rows, identities and failures win; a second failed read keeps saves blocked), 6b62a52f (a
pass that ends normally does not abandon; no RejectedExecutionException: pools are recreated lazily), 274bc160 (normal
seeks with overlap == slider unchanged; a plan ready at the seek still mixes; no gain step: the 400 ms slew is what
the guard leaves), a991dee6 / de3e213d (no deadlock; Auto reads in a service coroutine; tag edits still refresh an
open page; sort changes still re-sort; the App.kt edit is inside a `LaunchedEffect` lambda, not `App()`/`AppShell`).

Notes, not defects:
- R2-S-n1 (changes queues, info): rule-only plans (breaker tripped, assets unhealthy, ranker not fitting the heap)
  also get the keep-together bonus (`RULE_SET` takes `KeepTogether.DEFAULT`); rule scores span ~0–3, so marked
  co-members dominate those plans until they fill 5 of the last 10 rows. Fits the feature's intent; not measured on
  the stand.
- R2-S-n2 (debug only): the debug replay in `SmartDiagnostics.kt` leaves `companionGroups` and the prefix out of
  `PlanRequest`, so replays of queues with marked playlists do not match the logged queue.
- A playlist page sorted by Plays is sorted only when it opens (R2-U-2's pre-existing half): opened before the first
  history read, it shows Custom order until reopened. Left as is (a page's order is fixed while open by design).
- `seamLog` prints only opaque track ids and is silent unless `log.tag.LJCrossfade` is set to DEBUG.

### Round 2 checks

At 18d22f18: all check tasks green; most dex registers 249 (limit 255).

## Round 3

Three fresh reviewers: P crossfade fixes + decode workers · U app-layer fixes + release hygiene over everything after
292077cb · S SMART candidate / Map interaction / #27 / Recently played / Plays / lock player / duplicates. 5 findings,
all confirmed (one moot after a revert). Crossfade (274bc160 + 4f06182b together), release hygiene, the SMART
candidate, Recently played, Plays sort, lock player and duplicates marquees: clean.

| id | finding | verdict | fix |
|---|---|---|---|
| R3-P-1 | From d506e74a (kept by e0387249 on multi-worker devices): a window that came back Unavailable (codec contention) was retried on the extra thread beside every look-ahead decode, one codec more than the worker count, so the retry was likelier to fail again and the track stored with fewer windows | moot: d506e74a and e0387249 reverted (6db6af61, 0011bd01) | revert: the separate thread fixed a rare, pre-existing wait (R1-I-4) and caused R2-I-1 and R3-P-1; back to 0.9.2's one pool, R1-I-4 a 0.9.4 note |
| R3-P-2 | KDoc and a host test still described the separate thread on low-RAM devices | moot (reverted) | — |
| R3-U-1 | From 30fb951c: an empty first history read (no listens yet) equals the initial counts and re-runs no refresh, so the flag stayed unset and the first listen re-sorted an open Favorites page sorted by plays once | CONFIRMED-narrower, very low (start on Playlists, no other key change before the first listen); the reviewer's fix would not work, the verifier's was taken | f78639ed (an empty first read settles it; a refresh from before the read can no longer unsettle it) |
| R3-S-1 | Android: while the stored audio index stays unreadable (R1-I-1's session), the pass still ends `complete`, so the Map / For You turned region identity on with metadata-only vectors and saved fresh region identities over the stored ones (lost on the next good launch) | CONFIRMED-narrower, low (the layout save is 0.9.2 behaviour; iOS never latches) | 68fd797f (`SimilarityEngine.audioIndexUnreadable`; identity and the in-page refresh stay off while it holds) |
| R3-S-2 | iOS (from ecc90062, not in 0.9.2): a track being analysed when iOS suspends the app timed out on return (the 90 s clock runs on while suspended) and was remembered as "Analysis timed out" until Retry | CONFIRMED, low-medium (a first analysis, the app left for > 90 s) | 4a507538 (a timeout across a background/foreground transition, or firing while still in the background, is the retryable `AudioUnavailable`) |

### Round 3 checks

At 705dc331: all check tasks green; most dex registers 249 (limit 255).

## Round 4

Two fresh reviewers over everything this loop touched (I analysis / index / Map / iOS deadline; U playlists, crossfade,
settings, release hygiene over `292077cb..HEAD`). Playlists / Favorites lifecycle (cold start on each tab, activity
recreation, history cleared or off), crossfade, `joinBidiWrapped`, the reverts (complete: nothing named
`redecodeWorkers` left; 6b62a52f does not rely on the second pool) and release hygiene: clean. 3 findings, all
confirmed.

| id | finding | verdict | fix |
|---|---|---|---|
| R4-I-1 | Android (flag order from d3e3613f, the hole re-opened past 68fd797f): the retry of an unreadable index was counted before its read, so a pass cancelled mid-read (a new indexing request, the foreground-service timeout) left analysis blocked for the session with `audioIndexUnreadable` false, and the Map could save metadata-only region identities again | CONFIRMED, low (a cancel during a seconds-long read) | 862e99d8 (counted once the read returned; a cancelled read is tried again by the next pass) |
| R4-I-2 / R4-U-1 | iOS, from 4a507538: with the `audio` background mode the app is not suspended while music plays, so a decode that really hangs timed out in the background and was retried by every pass, each leaving a stuck IO thread (`abandon()` is a no-op on iOS) | CONFIRMED, low (passes are rare; IO threads not used by playback) | 3e0c7b51 (a track is let off once per run, then remembered) |
| R4-I-3 | iOS, from 4a507538: `IosAppTransitions` started observing at the first analysis, so leaving the app before it was missed and a suspension in that window was still remembered as a timeout | CONFIRMED-narrower, very low | 3e0c7b51 (observed from `smartEngineBackendModule()`, at start-up on the main thread) |

0.9.4 idea from the verifier: a suspension makes the deadline fire late (on resume), so "elapsed > budget + a few
seconds" on the same monotonic clock is a cleaner suspension test than app transitions; needs a device check.

### Round 4 checks

At 629a2c1f: all check tasks green; most dex registers 249 (limit 255). The release APK installs on emulator-5556
(`adb install -r`) and launches to `MainActivity` with no crash.

## Round 5

Two fresh reviewers: I analysis / index / Map / iOS deadline at HEAD, round-4 fixes first; U a sweep of everything else
after 292077cb (SMART candidate, playlists / Favorites / Auto / resume, crossfade, UI, release hygiene). The sweep:
**no findings** (note: `fullFollowers()` in `HabitSlotsPlanner` runs on every plan while PLAN_NOVELTY is off, result
unused, negligible cost). 862e99d8 and 3e0c7b51 held (no overlapping reads: the read holds the engine mutex; one full
read per cancelled pass at most; `forgivenTimeouts` only touched under the inference mutex; the module touch runs on
main via `AppGraph.start`). 1 finding, confirmed.

| id | finding | verdict | fix |
|---|---|---|---|
| R5-I-1 | Android, from d3e3613f: an unreadable stored metadata (text) index latched `textRestoreFailed` for the session with no retry: no metadata vectors, SMART without its text term and semantic search, and the Map fused source fell to AUDIO with identity / refresh on, saving regions from an audio-only space (0.9.2 re-encoded) | CONFIRMED, low | fbafd1d1 (encoded again this session, as 0.9.2 and a fresh install do; the audio index is still never overwritten) |

### Round 5 checks

At 4c921f75: all check tasks green; most dex registers 249 (limit 255).

## Round 6 (clean)

Two fresh reviewers again: fbafd1d1 and the index / iOS fixes first, then a sweep of the rest. No confirmed problems.
One info item: R6-I-1, the `IndexStore` KDoc still described the old latch for the metadata index (doc only, fixed in
d53ee00e). Notes, not defects: History rows keep `text = null` after a re-encode session with a non-authoritative
scan (0.9.2 behaviour); 3e0c7b51 trades a second suspension of the same track for no stuck threads; the sweep found
the day-one upgrade path clean.

## Round 7 (clean, requested short round over fbafd1d1 and d53ee00e only)

One fresh reviewer: **CLEAN**. `textRestoreFailed` is gone from the code. The audio index protection is unchanged (the
save still guards on `!audioRestoreFailed`; flags reset only after both deletions succeed and in `release()`).
`CancellationException` is still rethrown. The metadata file is written only when vectors are added or rows are
pruned, so an unavailable text encoder never saves an empty snapshot. The test fails on the pre-change code.
Low notes: a pass killed after a checkpoint saves a partial metadata snapshot (re-encode time only, by design); the
test's key assertion alone would also pass untouched (the `saveCalls > 0` assertion is the one that pins it).

### Final checks

At d53ee00e: every check task green (EXIT 0); the release APK's most dex registers are 249 (limit 255, `planLocked`).
The arm64 release APK installs on emulator-5556 (LJ-fold, `adb install -r`) and launches to `MainActivity`
(`Status: ok`, 494 ms) with the process alive and no crash in the crash buffer.

## Outcome

Rounds 6 and 7 found no confirmed problems; the loop stops.

Fix commits, in order: a991dee6, de3e213d, 274bc160, c87a41e1, df1b8b83, 6b62a52f, 4f06182b, 3c9cba1d, 6c047ffe,
30fb951c, f78639ed, 68fd797f, 4a507538, 862e99d8, 3e0c7b51, fbafd1d1, d53ee00e. d506e74a and e0387249 (re-decode
pool) are reverted by 0011bd01 and 6db6af61: they caused R2-I-1 and R3-P-1.

Left for the owner:
- Proposals (queue-changing, not applied): R1-S-1, R1-S-2, R1-S-3.
- Notes: R1-I-3, R1-I-6, R1-I-7; R1-I-4 (low-RAM decode pool) for 0.9.4; R2-S-n1, R2-S-n2; the playlist page's own
  Plays order gap; the elapsed-overshoot suspension test idea; `fullFollowers()` computed while PLAN_NOVELTY is off.
- Release gate A8-1 (version labels) is still open.
- The iOS fixes 4a507538 and 3e0c7b51 compile and pass host tests but are not verified on a device.
