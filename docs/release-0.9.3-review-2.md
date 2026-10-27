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
