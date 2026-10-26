# 0.9.3 release review

Adversarial review of everything that ships in 0.9.3: `git diff v0.9.2..fb4aa942` (`release/0.9.3-full` plus the
five track-change perf commits), fixed on branch `claude/release-093-review-72d588`.

Method, per round: one fresh reviewer subagent per area (eight areas), then every finding goes to a second,
independent subagent that verifies it against the code (CONFIRMED / REJECTED with file:line). Only confirmed findings
count. Fixes land as small commits with tests where the logic is testable. A round with no confirmed findings ends the
review (cap: six rounds).

Areas: 1 playback (crossfade, resampler, silent edges, adaptive crossfade, transport fades, iOS overlap) · 2 SMART
(LW2 ranker, artist variety, habit slots, chain wiring) · 3 Map (deferred rebuild, region identity, indexing status) ·
4 lock-screen player · 5 ListenBrainz import · 6 UI · 7 perf commits · 8 release hygiene (R8, manifest, debug-only
code, logs, strings, iOS plist).

## Round 1

26 findings reported: 24 confirmed (some downgraded; A6-1 only in a narrower form), 2 release-gate notes out of scope
for this branch (A8). Two confirmed items change SMART queues and are left as proposals (A2-2b, A2-5).

### Area 1, playback (all iOS; Android traced clean)

| id | finding | verdict | fix |
|---|---|---|---|
| A1-1 | A new queue / restore / planned Next arriving while a Next waits out its short fade: `flush()` never ends the change, `changePending` stays true, the mixer gain freezes (then goes to 0) | CONFIRMED, medium | 1c54050b |
| A1-2 | Overlap into a track shorter than overlap + the outgoing trail: the standby deck ends before promotion, its completion is dropped, the queue stalls in silence | CONFIRMED, medium (rare) | 1c54050b |
| A1-3 | A natural end or promotion during a Next's fade: the deferred Next then skips the new track too (false SKIP_NEXT) | CONFIRMED, low | 1c54050b |
| A1-4 | Next during an overlap hard-stops the outgoing deck mid-waveform (click) | CONFIRMED, low | 1c54050b (150 ms fade, then promote) |

### Area 2, SMART

| id | finding | verdict | fix |
|---|---|---|---|
| A2-1 | First ranker load (manifest SHA-256 over ~32 MB, boxed per byte) runs on Main in a top-up before the 5 s warm-up | CONFIRMED, medium | e7bb6236 |
| A2-2 | `smart_presses.txt` (hex folder + file name of every SMART seed) survives "Clear listening history" | CONFIRMED, medium (privacy) | fdb41220 |
| A2-2b | …and is written while "Save listening history" is off | CONFIRMED | **proposal** (changes press counts across restarts for those listeners) |
| A2-3 | semChain copies every 384-float text vector of the library on every plan (~16 MB at 10k, ~80 MB at 50k) | CONFIRMED, medium | f04581df (batched, same order, bit-identical) |
| A2-4 | The 0.9.2 `smart_index.bin` (up to 64 MiB) is never deleted after the encoder change | CONFIRMED, low–medium | 0ec57479 |
| A2-5 | Artist variety "Off" does not scale the newer per-queue artist caps | CONFIRMED as a wiring gap, low | **proposal** (changes Off/Low/High/Max queues) |

### Area 3, Map

| id | finding | verdict | fix |
|---|---|---|---|
| A3-1 | Background rebuild in a fresh process counts remembered decode failures as missing audio → NOT_READY forever (iOS: every protected Music.app row) | CONFIRMED, medium | 3e6fbea2 |
| A3-2 | The 24 h in-app fallback's request time is in memory and reset at every process start, so it almost never comes due | CONFIRMED, low–medium | 3e6fbea2 |
| A3-3 | The fallback runs in the Map page's keyed effect and cancels itself when the rebuild changes the keys | CONFIRMED, low ("doubled peak" claim rejected: `mapWorkGate` serialises) | 3e6fbea2 |
| A3-4 | iOS expiration handler only cancels; the task completes only when the job notices | CONFIRMED, low | 9286802b |
| A3-5 | Empty scan (permission pending/revoked) returns NOT_READY and retries forever | CONFIRMED, low (fix changed: do not clear the stores) | 3e6fbea2 |

### Area 4, lock-screen player (no lock bypass, no exported attack surface, no new permission)

| id | finding | verdict | fix |
|---|---|---|---|
| A4-1 | The player stays over the keyguard on every wake after playback stopped on its own (sleep timer, end of queue, focus loss) | CONFIRMED, medium (opt-in feature) | 526aa121 |
| A4-2 | No keyguard (lock "None", or woken inside the lock grace period): the player flashes or stays as a normal full-screen activity | CONFIRMED, low–medium | 526aa121 |

### Area 5, ListenBrainz import

| id | finding | verdict | fix |
|---|---|---|---|
| A5-1 | Import fills the history exactly to 500k; the next play breaks backup export and re-import ("invalid file") | CONFIRMED, low (downgraded: needs ~500k matched listens) | 9267f2af (no-op merge never fails) + 42f9f072 (1/50 headroom) |
| A5-2 | History rewrite builds every line plus one String of the whole log: 300–450 B per listen, imports OOM on 48/96 MB heaps | CONFIRMED, medium | 9267f2af (streamed write, capacity reserved first) |
| A5-3 | De-dup per library track: a re-import credits an old listen to a newly added copy of the song | CONFIRMED, low | 42f9f072 (per song group) |
| A5-4 | A lone candidate skips the duration check (extended mix / live credited to the studio track) | CONFIRMED, low | 42f9f072 (refused only > 30 s and > 25 % apart) |
| A5-5 | A wrongly picked large non-export file is scanned to the end with no progress and no cancel | CONFIRMED, low | 42f9f072 (first-byte check) |

### Area 6, UI

| id | finding | verdict | fix |
|---|---|---|---|
| A6-1 | Failed import shows its snackbar inside NonCancellable before releasing the file / clearing progress; with no host the Import row stays disabled | REJECTED as reported (the app-level snackbar state has a second host), CONFIRMED narrower: after an activity recreation (locale/font change, task swiped while playing), low | 3ffdbf9b |
| A6-2 | Comments call the learned ranker a test-build default and describe withdrawn habit slots | CONFIRMED, trivial (defaults intended: 9416a37d, 64e6e1ef) | 21fdb586 (comments only); `docs/smart-ranker.md` still says "test build" in places — left for the release notes pass |

### Area 7, perf commits

| id | finding | verdict | fix |
|---|---|---|---|
| A7-1 | Since queue rows keep composition (92673faa), a track swiped out and queued again comes back swiped away (saveable swipe state restored by key) | CONFIRMED, medium (regression vs 0.9.2) | ddfba61f |

Noted, not a defect: the shell still reads the accent in composition for the browse pages other than Tracks, so those
pages still recompose during the accent fade.

### Area 8, release hygiene (R8 rules, permissions, debug gating, logs, 22 locales, iOS plist all clean)

| id | finding | verdict | fix |
|---|---|---|---|
| A8-1 | `versionName "0.9.3-lw2-map-lock2-xf2"` / `versionCode 38` are test-build labels | out of scope here: the version bump is the main session's | — |
| A8-2 | `SmartRanker.planLocked` is at 250 dex registers (limit 255) | release-gate note | checked on the final APK below |

### Round 1 checks

After the round-1 fixes: `:composeApp:testAndroidHostTest`, `:core:{smart,playback,history,library}:testAndroidHostTest`,
`:core:playback:iosSimulatorArm64Test`, `:composeApp:compileKotlinIosSimulatorArm64` and `:androidApp:assembleRelease`
green; `check-dex-registers.py` on the arm64 release APK: most registers 250 (limit 255, `SmartRanker.planLocked`);
the release APK installs and launches on emulator-5556 with no crash.

## Round 2

Fresh reviewers saw the round-1 log and reviewed the round-1 fix commits first, then their areas again. 16 findings
reported: 14 confirmed (all low except two medium), 1 confirmed as an intended, documented limitation (owner
question), 1 partly confirmed.

| id | finding | verdict | fix |
|---|---|---|---|
| R2-A1-1 | iOS: a row picked from a SMART queue awaited the top-up (up to 20 choices, a cold ranker load) inside its change, so the picked song played silently for that long (from the original change-fade commit 7bade8e1) | CONFIRMED, medium | 18a27949 |
| R2-A1-2 | iOS: a drained overlap's end is posted; the crossfade tick runs first and late-starts the following track part-way in | CONFIRMED, low (the reviewer's first fix would not work; the second was taken) | 18a27949 |
| R2-A1-3 | iOS: a change ending with nothing on the file graph (Music.app item, a call) leaves the fade-in loop waking every 20 ms | CONFIRMED, low | 18a27949 (`sounding = { true }`) |
| R2-A2-1 | "Keep together in SMART" is ignored by the learned ranker, now the default | CONFIRMED but intended and documented (docs/smart-ranker.md "not a ranker feature yet") | **owner question**: hide/annotate the action while the ranker is on, route marked seeds to the chain (changes queues), or say so in the release notes |
| R2-A2-2 | Ranker turned off then on mid-session: the next top-up continues an earlier, unrelated seed | CONFIRMED, low | b1053179 (chain branch clears the ranker session; chain and untoggled ranker queues unchanged) |
| R2-A3-1 | The fallback, now in appScope, no longer stops | PARTLY CONFIRMED, very low (the old one stopped only on page leave and could hardly finish) | 86517a74 (stops when external power ends) |
| R2-A3-2 | A request arriving during a run is cleared with it (and dropped by KEEP while the worker runs) | CONFIRMED, very low | 86517a74 (request counter, `AGAIN` → retry / reschedule) |
| R2-A3-3 | A full layout build in the page restarts the fallback's day while regions still wait for identity | CONFIRMED, low | 86517a74 |
| R2-A4-1 | Lock player leaves when a SMART queue runs dry with the screen off though SMART resumes (STATE_ENDED until the next rows are planned) | CONFIRMED, low (rare: lookahead 20) | 4c6c035d (a stop while asleep counts after 5 s, cancellable; empty player still closes at once) |
| R2-A5-1 | After a big import, the next cold load (all lines + one id string per event, ~170 B/listen) fails on 48 MB heaps at ~100k; `record()` then drops every listen | CONFIRMED, medium (old load path, new trigger) | 9b0c843d (line-by-line load, one id string per song) |
| R2-A5-2 | `writeAll` still allocated after the file write; an OOM there left memory empty beside a merged file, and a retried import would rewrite the log from it | CONFIRMED, low | 9b0c843d (build, write, then swap) |
| R2-A6-1 | Regression from 3ffdbf9b: a failure after the section left composition reported nothing | CONFIRMED, low | d3bae864 (process-wide failure state, like the report) |
| R2-A6-2 | Quick Settings tile: a tap that resumes a fading pause showed the tile paused | CONFIRMED, low | c08e4c3e |

Round-1 fixes re-reviewed and held: e7bb6236 (same `healthy`, no deadlock), fdb41220 (same Koin single, both under
the presses mutex), 0ec57479 (each base name keeps its own legacy file; a failed save never deletes it), f04581df
(bit-identical: same ids, same rows, same accumulation order), 3e6fbea2, 9286802b, 9267f2af, 42f9f072 (song groups
drop no real listens in realistic cases), ddfba61f, 21fdb586, 526aa121, 1c54050b (thread confinement, double
completion, repeat modes, sleep timer). Release hygiene: nothing new (no reflective uses, permissions, strings or
plist changes from the fixes; no Kotlin/Native-hostile test names).

## Round 3

Six fresh reviewers (playback; SMART; Map; ListenBrainz + history; UI + perf; lock player + release hygiene). SMART,
UI + perf, lock player and release hygiene: **no confirmed findings** (round-1/2 fixes re-traced and held, including
b1053179: `learned` is never null while the ranker setting is on, so the chain branch runs only with it off). 7
findings elsewhere, all confirmed low; one is a release-note limitation.

| id | finding | verdict | fix |
|---|---|---|---|
| R3-A1-1 | A Next queued behind a change's fade decided its branch at the press and was dropped at the tail (pick the last row + Next; two quick Nexts at n-2; repeat ALL on iOS did not wrap). iOS made likelier by 18a27949; Android has the same drop since the service-side fades | CONFIRMED, low (regression vs 0.9.2) | 2f528ba1 (iOS decides when it runs and waits for planning / wraps; Android's deferred app Next waits ≤ 15 s for the next planned row) |
| R3-A1-2 | A system pause (unplug, call, focus loss) during a pick's fade was overridden: the pick then played, on the speaker | CONFIRMED, low | 2f528ba1 (Android plays only if `playWhenReady`; iOS loads without autoplay after a system pause in the change) |
| R3-A3-1 | `resumePending` in a worker-started process raced the run's request snapshot → spurious AGAIN, one wasted scan per idle charge | CONFIRMED (Android), low; iOS mostly rejected (no scene in a BG launch) | f048106a (a start-up re-read is not new work) |
| R3-A3-2 | The fallback's power check (86517a74) read UIDevice off Main | CONFIRMED (API contract), low | f048106a (polled on Main, read from a state flow) |
| R3-A5-1 | `ensureLoaded` published events, then allocated (`putAll`) before `loaded = true`; a failure there doubled the log on the next call | CONFIRMED, low (old lines, new trigger) | 094c1b19 (adopt, no allocation in between) |
| R3-A5-2 | SmartPresses / FavoriteTimes cached an empty map after a failed or cancelled read; the next write wiped every other count / heart time | CONFIRMED, low (new in 0.9.3) | 32105c7c |
| R3-A5-3 | Local backup builds / reads the whole file as one String: after a big import, export fails ("Backup failed") from ~100k listens on 48 MB heaps (~350–400k on iOS, 64 Mi-char cap) | CONFIRMED, pre-existing code, new trigger; no data risk | **release note** for 0.9.3; streamed encode/decode for 0.9.4 |

### Round 3 checks

`:composeApp:testAndroidHostTest`, `:core:{smart,playback,history,library}:testAndroidHostTest`,
`:core:{playback,history}:iosSimulatorArm64Test`, `:composeApp:compileKotlinIosSimulatorArm64` and
`:androidApp:assembleRelease` green at 9b3ee3e4; most dex registers 250 (limit 255).

## Round 4

Six fresh reviewers. Map, ListenBrainz + history, lock player and release hygiene: **no confirmed findings**. Three
confirmed (all low), one rejected.

| id | finding | verdict | fix |
|---|---|---|---|
| R4-A1-1 | Regression from 2f528ba1: Android's tail wait armed outside SMART and fired on the listener's own edits (Play next, Add to queue, a move, a shuffle switch within 15 s), cutting the playing song | CONFIRMED, low | 7c20b7c2 (arms only in SMART; dropped by listener queue edits, direct moves, shuffle changes, or a mode no longer SMART) |
| R4-A2-1 | "Clear listening history" left SmartLogRecorder's outcome tracker and queued writes: pre-clear queue ids / content hashes were written back into the emptied impression log, and pre-clear tracks re-marked engaged | CONFIRMED, low (privacy; new in 0.9.3) | 8e23ad14 (`forgetAll`: new tracker, queue refs reset, presses and log cleared again through the write queue) |
| R4-A6-1 | Lock player's LatentJam pill is a 32 dp touch target | REJECTED: Compose widens pointer hit-testing to 48 dp and the 64 dp top bar leaves room; only the semantics bounds are 32 dp | — |
| R4-A6-2 | The lock-screen cover is announced as a button with no action (role kept when the handlers became optional) | CONFIRMED, low (lock screen only) | 831197af |

## Round 5 — clean

Four fresh reviewers (playback; SMART; Map + ListenBrainz/history; lock player + UI + perf + release hygiene)
re-reviewed every fix commit and their areas from scratch. **No confirmed findings.**

| id | finding | verdict |
|---|---|---|
| R5-A3-1 | iOS background Map rebuild ignores `LibraryScan.complete`: a Music.app query that comes back empty lets it rebuild regions for the Documents-only part of the library | NOT CONFIRMED as a defect: the foreground regions path already publishes and rediscovers on incomplete scans (`App.scanLibrary` uses `complete` only for pruning), so the background run is no worse; the trigger (query still loading) is a foreground moment after a grant. The suggested `!complete → NOT_READY` would be harmful: `complete` is false for good for users who denied Music.app or have no Music.app songs, so their rebuild would never run. Follow-up idea for 0.9.4: return NOT_READY only when snapshot ids absent from the scan are not proven deleted. |

Checks at 88aa303b (no code change after it): `:composeApp:testAndroidHostTest`,
`:core:{smart,playback,history,library}:testAndroidHostTest`, `:core:{playback,history}:iosSimulatorArm64Test`,
`:composeApp:compileKotlinIosSimulatorArm64`, `:androidApp:assembleRelease` all green; `check-dex-registers.py` on the
arm64 release APK: ok, most registers 250 (limit 255).

## Outcome

Five rounds; round 5 clean. 44 confirmed findings fixed (round 1: 22, round 2: 13, round 3: 6, round 4: 3) in 27
fix commits on top of fb4aa942 (`git log --oneline fb4aa942..HEAD`, plus the log commits). Nothing here changes SMART queue output except b1053179 (only after the ranker setting is toggled
mid-session) and the bit-identical f04581df.

Left for the owner / main session:

- **Proposals (change SMART queues, not applied):** A2-2b presses counted to disk while "Save listening history" is
  off (keep them in memory only?); A2-5 artist variety Off/Low/High/Max does not scale the per-queue artist caps.
- **Owner question:** R2-A2-1 "Keep together in SMART" is ignored by the learned ranker (documented in
  docs/smart-ranker.md): hide or annotate the action while the ranker is on, route marked seeds to the chain, or say
  so in the release notes.
- **Release notes:** R3-A5-3 local backup export fails ("Backup failed") for very large histories after a ListenBrainz
  import (~100k listens on 48 MB heaps, ~350–400k on iOS); nothing is lost. Streamed backup encode/decode for 0.9.4.
- **Release gate:** A8-1 versionName/versionCode are still test labels (`0.9.3-lw2-map-lock2-xf2` / 38);
  `docs/smart-ranker.md` still says "test build" in places; re-run `check-dex-registers.py` on the final APK
  (`SmartRanker.planLocked` at 250 of 255).
- **Not verified on a device:** the iOS playback fixes (1c54050b, 18a27949, 2f528ba1) and the lock player lifecycle
  fixes (526aa121, 4c6c035d) are traced and unit-tested where testable, not heard or watched on hardware.
