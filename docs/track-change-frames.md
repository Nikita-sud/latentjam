# Track-change frames (2026-10-26)

Owner report: on the S24 Ultra (120 Hz, release, 1,161 tracks, player open) every way of changing
track costs 2–3 frames of 25–50 ms, all of it main-thread time before the frame. This round cuts the
main-thread work a change causes. The phone measurement is the main session's; the numbers here are
from the emulator.

## What a change cost, and why

A profileable release under simpleperf (main thread only, R8 mapping applied) showed:

1. **The accent's cross-fade recomposed the whole app.** `rememberTrackAccent` animates the track
   colour for `Motion.APPEAR_MS` (220 ms) and returned plain colours, read at the top of `AppShell`.
   Shortly after a change, when the cover colour resolves, every frame of the fade
   (≈26 frames at 120 Hz) recomposed the shell, `PlayerSheet`, `NowPlayingScreen`, every visible
   queue row and the Tracks page behind the player (sort header, the list's item DSL, visible rows).
   In the profiles this was the second wave of 30–60 ms bursts after each skip.
2. **The library page read the current track itself.** The Tracks page passed `currentTrack?.id`,
   the playing flag and the accent ink as values, so each change (and each fade frame) recomposed
   the page and every visible row, though the full player covers them.
3. **Widget publishing did binder calls on the main thread.** Each published snapshot is broadcast to
   all three widget classes; each one called `getAppWidgetIds` for all three classes on the main
   thread: nine binder calls per publish, several publishes per skip. Every publish also asked the
   settings provider for the boot count.
4. **The covered mini player** followed the fade on every frame too, and the player sheet got a new
   pill-colour lambda on every shell recomposition.

## Commits

| Commit | What it removes |
| --- | --- |
| `perf(player): read the track accent where it paints, not in the shell` | `rememberTrackAccentState` returns the accent as a state. The player cloud, the browse cloud, the sheet's pill colour and the current queue row's tint read it while drawing; only the current queue row reads it in composition (its ink). `NowPlayingScreen` takes `accent: () -> TrackAccent`. The shell no longer recomposes per fade frame. |
| `perf(widget): look widgets up off the main thread, once per burst` | The widget id lookup moves to the widget executor and one lookup serves every change that arrived before it ran (rendering already reads the newest snapshot). The boot count is read once per process. |
| `perf(library): rows read the current track themselves` | `SectionedSongsList` takes the current track, playing flag and accent ink as reads; each row derives `isCurrent`, so a change recomposes the two rows it concerns and the fade only the current row. The For You hero reads the accent itself. |
| `perf(player): the covered mini player stops following the accent's fade` | While the full player covers it, the pill takes the accent unobserved; uncovering it recomposes it with the current colour. The pill colour lambda is remembered. |

Behaviour is unchanged: same colours, same 220 ms fade (now drawn rather than recomposed), same
highlights. Checked by eye on the emulator (player, pill, list highlight across skips).

## Measurement

Emulator `emulator-5556` (AVD LJ-fold, arm64, 4 vCPU), window resized to phone size
(`wm size 1080x2400`, `wm density 420`), media volume 0. The AVD's 44-track library was too small to
reproduce anything (a skip cost ~2 ms over idle), so 1,120 silent 40-second tagged MP3s with covers
(`tools/perf/track-change/gen_library.py`) were pushed to `/sdcard/Music/Perf`: 1,164 tracks,
indexed before measuring. Each build: install as an update, profile-compile (`speed-profile`),
relaunch, start the in-order Tracks queue at its second row in **SMART** mode, open the full
player, then 12 media-key skips 1.5 s apart under
`simpleperf record --app … -g -e cpu-clock -f 4000`. Builds were interleaved (A, B, A, B …), two
measurements per install, three rounds per comparison, nothing else building on the host.

The emulator's speed drifts between sessions by 15–20 % (the fixed-rate cloud animation is used as a
speed check); background SMART planning and GC also slow its main thread. Compare within a table, not
across sessions. Plain in-order playback (no SMART appends) cost far less on the emulator (~13 ms of
long bursts per skip) and showed the same direction.

### simpleperf, main thread, per skip (median of 12 runs × 12 skips; IQR in brackets)

| State | Recomposition | Long bursts (≥10 ms) | Bursts >20 ms | Bursts >33 ms | Worst burst (median per run) |
| --- | --- | --- | --- | --- | --- |
| Baseline (`5583c664`) | 92 ms [91–108] | 209 ms [196–247] | 3.5 | 2.0 | 141 ms |
| + accent, widget | 62 ms [60–70] | 154 ms [130–190] | 3.0 | 1.1 | 67 ms |
| + library rows, covered pill (final) | 50 ms [43–57] | 137 ms [111–178] | 2.5 | 0.9 | 61 ms |

Recomposition per skip −46 %, main-thread time in long bursts −34 %, bursts over two frames at
60 Hz −54 %, worst burst −57 %.

Before the fix the accent wave dominated: `AppShell` (self), the queue rows and the library list were
the top recomposed scopes, ~40 % of long-burst time was composition. After it the remaining
recomposition is what a change genuinely touches: the transport time, the player screen, the queue
rows SMART appends to, the next-up row and the new artwork.

### gfxinfo framestats (20 SMART skips per build, interleaved)

| | UI-thread time in frames (`SyncQueued − HandleInputStart`, sum) | Worst frames |
| --- | --- | --- |
| Baseline | 479 ms / 10 skips | 210, 203, 139 ms |
| Final | 300 ms / 10 skips | 112, 103, 102 ms |

Frame counts over 16.7 ms do not move on this emulator: every frame, idle included, waits 30–100 ms
on its emulated GPU pipeline, so the frame totals measure the host's GPU, not the app. Judge the
phone by its own framestats.

## What is left (not changed here)

- **Media3 at the change itself.** `seekToNext` → `ExoPlayerImpl.updatePlaybackInfo` → every
  listener (session, controller, ours), and on each timeline update `MediaSessionLegacyStub` sends the
  whole queue to the platform session (`MediaSession.setQueue`, ~5 ms per update with a 1,164-row
  queue). Inherent to Media3's legacy session; cheap for a SMART queue of ~30 rows.
- **Fade volume steps.** `SessionTransportFades` sets `Player.volume` every 20 ms during a change's
  fade, and each call flushes Media3 listeners on the main thread (~1 ms per skip in total). Moving
  the gain into an audio processor would remove it; it is a playback change, not done here.
- **`AppShell` still recomposes 2–3 times per skip**: the new track, the cover colour resolving, and
  the listening-history revision a skip records. Each is ~1 ms on the emulator.
- **Other pages still read the accent at page level** (Map, Playlists, Folders, Statistics,
  collection detail, search, settings): behind the player they recompose with the fade when they
  are the page underneath. Same fix as the Tracks list if one of them shows up on the phone.
- `AndroidPlaybackController.rebuildQueueSnapshot` also runs on Media3's source-update timeline
  changes, where the rows cannot have changed (~0.7 ms per skip with a 1,164-row plain queue,
  negligible in SMART). Left alone: the controller's queue invariants are subtle for that gain.

## Reproducing

Scripts in `tools/perf/track-change/` (paths and tap coordinates are for emulator-5556 at
1080×2400/420 dpi; they write `runs/` next to themselves):

- `gen_library.py` builds the synthetic library (needs ffmpeg; push the `Perf` folder to
  `/sdcard/Music/` and scan).
- `setup.sh <apk>` installs and prepares one build (refuses to continue unless media volume is 0).
- `ab.sh <rounds> name:dir …` interleaves builds; `absum.py name …` prints the per-skip table.
- `an.py`, `an3.py`, `phase.py` break a `report_sample.py` dump down by app function, by
  recomposed scope and by frame phase; `framestats.sh` collects gfxinfo per skip.

A profileable build needs `<profileable android:shell="true" />` inside `<application>` in
`androidApp/src/main/AndroidManifest.xml` (never committed).
