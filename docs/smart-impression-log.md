# SMART impression and outcome log

Branch `claude/test-ranker-1019`, 20 October 2026. On-device only: never uploaded, and nothing in ranking reads it yet.
It exists so later rankers can be evaluated and trained on the listener's own device with exposure data
(counterfactual / propensity-weighted), and so a skip-based soft penalty can be added later behind its own gate.

## What is written

`smart_impressions.log` beside the listening log (Android `files/`, iOS Application Support, excluded from backups).
One line per record, ASCII:

```
si1|atMs|queue|plan|hop|track|rank|score|probability|tie|source|model     one offered track
so1|atMs|queue|plan|hop|track|OUTCOME|elapsedMs|fraction                 what became of it
sf1|atMs|track|1 / 0                                                      a heart added / removed
```

- **queue**: `<12 hex of the seed's content-key hash>.<press>.<start ms>` (`c` instead of the press for the old
  chain). **plan**: 0 for the plan from the seed, then 1, 2, … for top-ups. **hop**: the row's place in its plan.
- **track**: `SaltedKey.contentHash` — the first 8 bytes of SHA-256 of the §7.2b content key, hex. It stays the same
  across rescans; the library maps it back to a track.
- **rank**: the row in the queue, where the seed is row 0.
- **score**: the decoder's penalised score at that hop. It is `NaN` for ladder picks and the chain.
- **probability**: the propensity, 1 / the size of the hop's near-best set. The §8 draw is uniform over that set; a
  sole best or a ladder pick is 1. **tie** is 1 when the set had more than one row.
- **source**: which retriever proposed the track (`R1` audio, `R2` co-listening, `R4` artist set / album, `R5` family,
  `R6` favourites, `R7` own transitions, `topup`, `whole` when the whole library is scored, `rule-R4` / `rule-sample`),
  `ladder:<rung>` for ladder picks, or `chain` for the old chain.
- **OUTCOME** (`SmartOutcomeTracker`):
  - `PLAYED` / `SKIPPED` come from the track's own listen, using the history's skip rule. `elapsedMs` is the time
    listened and `fraction` is the share of the track reached.
  - `REMOVED`: the row was in the queue, then gone before it played.
  - `REPLACED`: the listener started other music before the row played.
  - `UNREACHED`: a newer plan or a new queue superseded the row before it was served.
  - A row that started but produced no listen gets no line, because history was off or the process died.

Outcomes are classified from the playback snapshots the history recorder sees. That is the same service-side state
(`NowPlaying.playbackStart`, queue) the v4 origin logging uses, never a MediaController's view.

## Hearts

`launchFavoriteRecorder` diffs the favourites list on every durable change: the heart, the notification, bulk edits,
duplicate merges and a backup restore. Each change appends an `sf1` line (only while history is on) and updates
`favorite_times.txt` (`ft1|hex id|ms`, one line per current favourite).

The times feed the FULL ranker's `is_favourite` and `favourited_days_ago`, only with "Learn from my habits" on:
- Their weights live in `smart_ranker_full.json`, at 0 until the research side sets them.
- Hearts from before this build have no time, so they read `favourited_days_ago` = 0.
- A restored backup dates its hearts to the restore.

## Limits and safety

- The log is capped at 4 MiB. When it passes the cap, the oldest lines are dropped down to 3 MiB. The trim streams
  the kept tail into a replacement file in 64 KB chunks, then renames it, so the log is never loaded onto the heap.
- At about 110 bytes per line, 3–4 MiB holds roughly 30k–40k lines, or about 1,500 twenty-row plans with their
  outcomes.
- Writes are best effort and go through a single background writer, so a full or failing disk costs log lines,
  never a queue. Playback never waits on the disk.
- Nothing is logged while *Save listening history* is off. *Clear listening history* clears this log too.
- Debug export (product checks only): *Settings → SMART engine → Export SMART log (test)*. It saves the log as a text
  file to a location the listener picks, through the same system picker as the local backup.

## Tests

- `SmartImpressionLogTest` and `SmartOutcomeTrackerTest` (core:history, common: JVM and iOS) cover the round trip,
  sanitising, the cap, a failing store, and every outcome rule.
- `FileSmartLogStoreTest` (Android file) and `IosFileSmartLogStoreTest` (simulator) cover torn tails, the streamed
  trim and the cap.
- `RankerChooserTest` checks that plans reach the log with one row per track. `SmartLogRecorderTest` covers
  plan → snapshot → listen → lines, and that nothing is logged while history is off.
- `RankerScenarioTest` checks that every plan carries one pick record per id, with a propensity in (0, 1] and a
  matching tie flag.

## Proposed PRIVACY.md wording (for the owner; PRIVACY.md is unchanged)

Under "What the app reads on your device", after *Listening history*:

> - **What the recommendations offered** — for each queue the recommendations build, the tracks they proposed, their
>   place in the queue, how strongly each was scored and the chance it had of being picked, and what became of it
>   (played, skipped and when, removed, replaced by music you chose, or never reached), and when you heart or un-heart
>   a track. Only the most recent few
>   megabytes are kept. It is recorded only while *Save listening history* is on and is cleared with the history.
>   Nothing uses it to choose music yet; it lets future versions of the recommendations be checked on your device.

Under "Things that happen only when you ask":

> - **Export SMART log** (a test feature in the SMART engine settings) writes that record as a text file to the
>   location you choose.
