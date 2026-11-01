# Slice D: iOS background analysis, design v4: STOPPED, not built

**Status (2026-11-02 night): stopped after four review rounds, none READY; nothing of D-a or D-b is built.** See
"Why stopped" at the end. Part of docs/analysis-094-plan.md (§4 1e-ios). That section was reviewed as part of the whole plan; the plan requires
its own review round before Slice D is built. This file is what that round reviews (round log at the end).
Already built, not in scope here: `beginBackgroundTask` on every move to the background (B1,
`IosIndexingContinuation`), the index move to Application Support (36a6d2d5).

## Facts this design rests on (read from the code at b5a9491b, or measured)

- F1. The analysis work list is built only inside Compose (`App.kt` `scanLibrary`): `library.scan()` →
  `GenreEnrichment.apply` → `withoutFilesUnderRepair` (tag-write journal) → `AppGraph.ensureAutomaticIndexing`
  with `request.tracks` (the playable list, not `smartEligibleTracks`).
- F2. A `BGProcessingTask` launch on iOS creates no scene, so Compose never runs and nothing calls
  `ensureAutomaticIndexing`.
- F3. The Map's deferred rebuild already runs from a `BGProcessingTask` (`IosMapRebuildTask.kt`, identifier
  `io.github.nikitasud.latentjam.map-rebuild`, `requiresExternalPower = true`, registered in `iOSApp.init`). Its
  handler starts `AppGraph`, reads the library headlessly (`AppGraph.library.scan()` + `GenreEnrichment.apply`) and
  returns `NOT_READY` while any track is missing from the index. So a library that is not fully analysed never gets
  its map rebuilt in the background either.
- F4. Text and audio rows are a function of the descriptors and the files; given equal descriptors a pass stores the
  same rows in the same order whoever started it. That covers rows only: a cold background pass skips the tagger
  backfill and ranker warm-up (`smartLibraryTracks` is empty there), which the next foreground run does.
- F5. **Measured 2026-11-01 on the iOS 27.2 simulator:** `submitTaskRequest` fails with
  `BGTaskSchedulerErrorDomain` 1 "BGTaskScheduler is not available on this platform", and
  `_simulateLaunchForTaskWithIdentifier` then does nothing. No BGTaskScheduler path can be exercised on the
  simulator.
- F6. Every file the app keeps (index, defaults, tag journal, imported Documents files) is protection class C or the
  default (C); `Complete` is forbidden by the plan. Before the first unlock after a reboot none of them is readable,
  and NSUserDefaults reads as empty, so `userPaused`, `stopReason` and the D2 ledger would read as defaults.

## D-a. Analysis in the existing background processing task (v4)

### Shared request
- The Map's task becomes "background maintenance": same identifier (unchanged, no Info.plist change), same
  `BGProcessingTaskRequest` (`requiresExternalPower = true`, no network). A resubmission replaces the pending one.
- The completion function no longer resubmits on every failure (today `complete(false)` always calls
  `scheduleMapRebuild()`): step 5's rules are the only resubmission from a handler run.

### A background pass is its own kind
- `AutomaticIndexingRequest` gains `background: Boolean`, so a BACKGROUND key never equals a UI key: the UI's first
  call always replaces a handler's job with its own authoritative pass (analysed tracks are skipped; the cancel
  persists the chunk). A BACKGROUND call does **not** set `lastAnalysisCall`: `onAppForeground`, Continue and Rebuild
  only ever relaunch the UI's list.
- Writes of a BACKGROUND pass, each decided:
  - suppressed: `lastPassComplete`, `userPaused` (never cleared), `stopReason` (also not EXCEPTION: a throwing
    background pass is counted in memory only; two in one process and the handler starts no more), `restartRules`;
  - not called: `resumeMapRebuild()` (the handler runs the Map itself at step 4);
  - kept: the D2 ledger counts and `rememberAudioFailures` (a timeout is the file's, whoever ran it; the ledger is
    still never pruned against a non-authoritative list, `ledgerKeeps`), the engine's rows (`pruneMissing = false`:
    it only adds), `automaticIndexing` progress (the in-memory state the handler and status page read).
- Ownership is per job: the handler keeps the job it launched in an atomic, and it is its own while
  `automaticIndexingJob` is still that job (a UI call always replaces it, so "no UI call since" follows).

### When analysis asks for the task (`wantsBackgroundAnalysis`)
- True when the app is not active, the UI has called in this process (a `lastAnalysisCall` exists) or this process's
  handler stored rows, the user has not paused, `stopReason != EXCEPTION`, and the pass is not complete with work
  left beyond the D2-held tracks.
- Evaluated by an iOS observer of `automaticIndexing` and of an app-state atomic. The observers (state atomic,
  `DidEnterBackground`/`DidBecomeActive`, the sentinel write) are registered from `registerMapRebuildTask`
  (App.init), and the atomic's start value is read from `applicationState` on Main there and again at step 0
  (a real background launch posts neither notification). Submitted at most once per background stretch.

### The handler
0. **Protected data first**, before `AppGraph.start()` and before anything is read or written: on Main,
   `protectedDataAvailable` true → available (write the class-C sentinel). Otherwise read the sentinel
   (`Library/Application Support/.class-c-probe`); missing → try to write and read it back. Unreadable →
   `complete(false)`, resubmit, return. Nothing on the launch path before this writes.
1. `AppGraph.start()`.
2. **Analysis**, decided on Main:
   - a live `automaticIndexingJob` → follow it;
   - else, a UI `lastAnalysisCall` exists (warm process) → `onAppForeground()` (an AUTOMATIC relaunch of the UI's
     list under `restartRules`; that pass is the UI's, not the handler's own) and follow;
   - else, if the stored pause/stop allow it and fewer than two BACKGROUND passes threw in this process: build the
     headless list and start a BACKGROUND pass. The list: `scanWithoutPrompt()` (never `requestAuthorization`; no
     completeness gate for analysis, since a background pass only adds rows), `GenreEnrichment.apply`, then the tag
     journal read-only without the coordinator (`TagRecovery(store).pending()` and the checkpoint files under
     `TagWriteStoreLock`): any open record or checkpointed batch, or a failed read, means no pass. No pass either when
     `missingFromIndexOf(list) − held == 0`. Right before the call, on Main: still no live job, no UI call, not
     expired; else drop the list and follow.
3. **Wait** until no job is live and none is coming. "Coming" = `AppGraph.relaunching > 0`: incremented
   synchronously before a stopping job ends (in the pass body before `launch { continueAfterStop }`, in
   `continueAutomaticIndexing` before its launch, in `rebuildAnalysis` before its `cancelAndJoin`), decremented in a
   `finally` on every exit of those paths. A replaced job is followed to its successor.
4. **Map:** its background trigger uses `scanWithoutPrompt()` and a new outcome `SKIPPED_PARTIAL` when the scan saw
   less than a foreground scan would: Music.app enabled with `notDetermined`, or authorised with an empty answer, or
   Documents unreadable. `SKIPPED_PARTIAL` changes no request state (no `nothingWaiting()`, the 24 h fallback clock
   untouched) and does not resubmit. Denied/Restricted Music.app or the source turned off is a full view: the map is
   rebuilt over the imports, as today.
5. `setTaskCompleted(success)` exactly once. success = analysis done and map `DONE`/`NOT_NEEDED`. Resubmit when the
   map says `AGAIN`/`FAILED`, or when `wantsBackgroundAnalysis` holds and this run made progress: the engine's stored
   audio or text rows rose (measured from the index, not visited tracks or in-memory stages).

### Expiry
- Set `expired`. If the handler's own job is still current (the atomic) and the app is not active (the atomic, never
  `applicationState` off Main): take a short `beginBackgroundTask` with an end-once guard (ended by its own
  expiration or the job's `invokeOnCompletion`), cancel the job. Then `complete(false)` synchronously; resubmit by
  step 5's rule. A zero or refused grant is acceptable: snapshot writes are atomic renames and persisted chunks are
  the contract (≤ one checkpoint window lost). A device check.
- A job the UI owns is left to iOS's ordinary rules. `stopReason` stays null.

### Other changes
- "Playing" for the run-control gate comes from a holder set when the player is first built (`flatMapLatest`), so a
  background launch never builds the player and a later player is seen (common test).
- The headless call's strings come from Compose Resources `getString` outside composition.
- `IosIndexingContinuation`'s KDoc ("would not be honest here") is replaced.
- No UI text about background analysis: it never runs after a force-quit or in Low Power Mode, and nothing says
  otherwise.
- Accepted as today: `AppGraph.start()` and the cached `MPMediaQuery` run on Main in a background launch.

### Vectors
- Unchanged code path; given F4 the rows equal a foreground pass's over the same descriptors.

## D-b. "Keep analysing in the background" (`BGContinuedProcessingTask`): deferred

Not built tonight. Everything about it is device-only (F5): whether submission is permitted (the identifier must use
the bundle id `io.github.nikitasud.latentjam.kmp` as its prefix, unverified), the system progress UI, Cancel versus
system expiry, and how the system judges progress. Review round 1 also fixed its semantics for when it is built:
expiry stops like D-a's expiry and never becomes a user Pause; it follows the newest job; a refused `.fail`
submission brings the button back with a short "couldn't start" line; progress counts steps 1–3 too.

## Not in Slice D now, with reasons

- **Backgrounded-with-audio duty cycle.** Needs the iPhone measurement the plan names (§2.3).
- **QoS.** K/N `Dispatchers.IO` has no QoS knob; any effect is device-only.
- **2e on iOS.** iOS already analyses one track at a time with no look-ahead, so the slow pacing has nothing to slow;
  `setAnalysisPacing` stays a no-op there.

## What can be proven here, and what only a device can

- Here: the handler body through a debug launch argument (`-LJBackgroundMaintenance cold|warm`, debug builds only),
  run under the debugger (a backgrounded simulator app is otherwise suspended within seconds). `cold`: `ContentView`
  shows a blank view and never calls `MainViewController()` (no composition, player, prompting scan or coordinator);
  the device-music source is turned off in the app's settings for the run (the simulator has no media library); once
  the app is in the background (another app launched) the exact function the `registerForTaskWithIdentifier` closure
  calls runs with a stand-in task. Asserted: rows equal gate1's on the parity corpus; no `PlaybackController` built
  (a log line at its construction). `warm`: the same after a normal UI launch, checking the UI's pass is followed.
- Unit-test only: protected-data refusal, the sentinel's missing/unreadable paths, the app-state start value.
- Device only: LLDB `_simulateLaunchForTaskWithIdentifier` / `_simulateExpirationForTaskWithIdentifier`, a real
  launch without a scene, before first unlock, the grant after expiry, whether iOS grants the task at all, timing.

## Tests

- commonTest: `BackgroundMaintenance` on a fake host (protected-data refusal touches nothing; live job followed;
  UI call or expiry while the list is built wins; incomplete scan or open journal starts nothing; relaunch windows
  waited out; newest job followed; progress-gated resubmit; empty library done); `wantsBackgroundAnalysis`;
  `ledgerKeeps`; the BACKGROUND pass leaving the run-control store untouched; the playing holder seeing a player
  built later.
- iOS simulator: the existing suites; the debug cold run above.

## Review log

- Round 1 (v1, two fresh reviewers, iOS platform and app state): NOT READY, no blocker. Majors: continued-task
  identifier prefix; Cancel mapped onto a persisted Pause; expiry cancelling a pass the UI or a continued task kept
  alive; NSUserDefaults and every class-C file unreadable before first unlock, not only the index; the simulator
  cannot run BGTaskScheduler (confirmed, F5); the UI's first call racing the handler's. Minors: prompting scan,
  resubmit loops, ledger pruning on a non-authoritative list, player construction, journal accessor and active save,
  wrong claim about B1's expiry, progress and `.fail` semantics. v2 answers each above; D-b is deferred.
- Round 2 (v2, two fresh reviewers): NOT READY, no blocker. Majors: `lastAnalysisCall` is set by the handler's own
  call too, so "the UI called" cannot be read from it (a warm second night follows a dead job and stalls); the debug
  stand-in still ran with a composition; a prompt-free Map scan that misses Music.app would rebuild the map over a
  subset and overwrite it; a background pass completing over a subset would write `lastPassComplete` and clear a
  pause. Minors: expiry assertion order and main-thread state read, expiry racing the call, the coordinator's side
  effects in a headless launch, relaunch windows, undefined progress, a missing sentinel, the predicate only checked
  on leaving, the Map's NOT_READY loop, strings and force-quit wording. (One round-2 note, that `ledgerKeeps` already
  existed at b5a9491b, was the reviewer reading the uncommitted working tree.) v3 answers each above.

- Round 3 (v3, two fresh reviewers): NOT READY, no blocker. Majors: the complete-scan rule left import-only users
  (and the simulator) with no background work and, for the Map, `NOT_NEEDED` cleared the pending rebuild and reset
  the 24 h fallback; a BACKGROUND pass still called `resumeMapRebuild` (the nightly loop); a BACKGROUND key equal to a
  UI key kept a UI-taken job non-authoritative for the process; "progress" counted visited tracks and in-memory
  stages. Minors: the write list was inexact, `lastAnalysisCall` set by the BACKGROUND call, process-wide ownership,
  the unconditional resubmit in today's completion, relaunch-count edges, app-state start value and observer
  registration, the expiry assertion's end-once guard, a throwing BACKGROUND pass, what the stand-in can show.
  v4 answers each above.
- Round 4 (v4, two fresh reviewers): NOT READY, no blocker. Majors: `AppGraph.start()` itself builds the player
  (normaliser, crossfade, history and resume collectors), so "a background launch never builds the player" is false;
  the expiry's resubmit needs a suspend engine read inside a synchronous expiration handler (an atomic count of
  persisted rows is needed instead), and an expired map run with work left would no longer be resubmitted; F2 ("no
  scene") is unmeasured, and if iOS connects the scene or prewarms before first unlock, `MainViewController()` runs
  against empty NSUserDefaults (the protected-data test must guard it too); on a cold background launch
  `IosAppTransitions.background` starts false, so no timeout is forgiven as a suspension and a throttled file could
  reach the D2 cap and be remembered as failed. Minors: `pendingContinue` consumption, `lastPassComplete` suppressed
  the wrong way round, "only adds" wording, the relaunch counter leaking, the handler coroutine outliving expiry, an
  off-Main read of the job, sentinel traps, a followed job held by the gate keeping the task until expiry, no carry
  from a finished background pass.

## Why stopped

Each round fixed the last round's majors and found new ones, and the round-4 majors rest on facts only a device can
give: whether iOS creates or prewarms a scene in a background launch before first unlock, what grant follows an
expiry, how background CPU throttling affects the 90 s budget, and whether BGTaskScheduler launches the task at all
(the simulator refuses it, F5). Building a nightly background path whose failure modes include overwriting the
user's preferences with empty defaults or remembering good files as failed, without one device run, is the wrong
trade. Next step when a device is available: measure F2 and the post-expiry grant with LLDB
`_simulateLaunchForTaskWithIdentifier` / `_simulateExpirationForTaskWithIdentifier`, then write v5 from round 4's list.
Draft code (the handler on a fake host with 10 tests, the class-C probe, `scanWithoutPrompt`, the playing holder) is
kept on branch `wip/slice-d-ios-background`; not reviewed as code. Landed from this work: 5a5e9241 (the D2 ledger is
pruned only against an authoritative library).

