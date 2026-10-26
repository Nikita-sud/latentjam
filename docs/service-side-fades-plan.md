# Pause and manual-change fades for every Android source — design

Status: APPROVED 2026-10-26 — revision 4, review round 4 READY (the owner delegated approval to adversarial review rounds).
IMPLEMENTED 2026-10-26 (`SessionTransportFades.kt`, host tests `SessionTransportFadesTest`). Not yet
heard on a phone.

Deviations found while implementing:
- **A pause from the app or from nobody during a pending pause** completes it at once (recovery
  parking on a failed row, the service stopping); only a deferrable second pause is a no-op. Branch: on top of `feat/manual-change-fades`
(7bade8e1). Follow-up to limit 1 in `docs/manual-change-fades-plan.md`.

## What is instant today

The envelope (`TransportFadeState` + `ManualChangeFades`) lives in the app's
`AndroidPlaybackController` and rides `player.volume` through its MediaController. Commands that
reach the service's player without passing that controller run at once:

| Source | Path into the service | Fades today |
|---|---|---|
| In-app buttons, cover swipe, queue rows, seek bar | app controller | yes |
| Home-screen widget | `PlaybackWidgetActionReceiver` → app controller | **yes already** |
| Quick-settings tile | its own short-lived MediaController | no |
| Media notification, lock screen | media button / platform session → legacy stub | no |
| Headset and Bluetooth buttons | media button → session | no |
| Android Auto | MediaBrowserCompat → legacy stub | no |

So the widget needs nothing. The tile, the notification, the buttons and Auto do.

## What Media3 1.10.1 does with a deferred command (checked in the sources)

- `MediaSessionStub` runs every player command as `sendSessionResultSuccess(player -> …)`: it calls
  the session player's method, sends `RESULT_SUCCESS` at once, and then calls
  `triggerPlayerInfoUpdate()` "to end any masking states in the controllers".
- `MediaControllerImplBase` masks a player command locally (the new index or position at once), and
  holds incoming PlayerInfo in `pendingPlayerInfo` only while the command's sequence number is
  pending. The result removes it; the next PlayerInfo ends the mask.
- If the session player delays the real change by 150 ms, that next PlayerInfo still carries the old
  index or position. **A Media3 MediaController would show the new track, jump back to the old one
  for ~150 ms, then show the new one again.** Delaying the acknowledgement is not possible for
  player commands (the stub sends it right after the call returns).
- Only Media3 MediaControllers mask. The notification and lock screen (platform session), Auto
  (MediaBrowserCompat) and media buttons go through `MediaSessionLegacyStub` or straight to the
  player; they simply see the change when it happens, 150 ms later. No flicker.
- Both stubs set `MediaSession.getControllerForCurrentRequest()` around each player call, so the
  session player can tell who asked.

The only masking controller that matters is the app's own. So the app stops sending its fading
commands as player commands.

## Design (revision 4, after review rounds 1–3)

**One owner: the service.** A new `SessionTransportFades` (androidMain, next to
`LoadedQueueKeeper`) holds the `TransportFadeState` and a `ManualChangeFades`, drives
`exoPlayer.volume = baseVolume × gain` with the same 20 ms loop, and listens to the ExoPlayer for
`isPlaying` / `playWhenReady` as the app's listener does today (`onPlaybackStarted`, reset when
`playWhenReady` turns false). It cancels its jobs in `onDestroy` before the player is released. The
app's copy of the envelope and its gain loop are deleted, so nothing else writes `player.volume`.
Delayed actions call the ExoPlayer (or `super`) directly, never the overrides, so they are not
routed twice.

**Who asked.** `MediaSession.getControllerForCurrentRequest()` names the caller on every user path
(Media3 command queue, legacy stub, media buttons incl. the deferred double-click PLAY_PAUSE,
resumption). Three kinds:
- *the app's own controller* — tagged with a connection hint `latentjam.app-controller` (not the
  package name: Media3's notification controller and the tile share our package);
- *anyone else* — the notification, lock screen, buttons, Auto, the tile, third-party apps;
- *nobody* (`null`) — Media3 or service housekeeping (`pauseAllPlayersAndStopSelf`): runs at once,
  no fade.

**The rule that avoids flicker:** only a *deferred* command can flicker a masking controller, and
only the app's controller masks in practice. So the envelope applies to every caller, but a command
from the app's own controller is never deferred by the overrides; the app asks for a deferred change
through an ordered op instead (below).

**Session player overrides** (`LoadedQueueKeeper` subclass in `PlaybackService`):

| Call | Sounding, from anyone else | Not sounding, or from the app, or caller null |
|---|---|---|
| `pause()`, `setPlayWhenReady(false)` | flush queued changes, fade 160 ms, then pause | a pause already pending: nothing (a double PAUSE from a car must not cut the fade). Else flush, pause at once, reset |
| `play()`, `setPlayWhenReady(true)` | ExoPlayer `playWhenReady && !pausePending`: nothing (a redundant PLAY from a head unit or a voice request must not dip). Otherwise see **Start or resume** below | same |
| `seekToNext()`, `seekToNextMediaItem()` | TRACK fade if there is a next row (or a change already queued); the overlap landing is computed at the silence. No next row and nothing queued: at once, no dip | at once (overlap landing as today) |
| `seekToPrevious()` | TRACK or SEEK (see **Decisions**) | at once |
| `seekToPreviousMediaItem()`, `seekTo(index, pos)`, `seekToDefaultPosition(index)` | TRACK | at once |
| `seekTo(pos)`, `seekBack()`, `seekForward()`, `seekToDefaultPosition()` | SEEK | at once |
| `setMediaItem(s)` (not `KeepLoadedQueue`, which is skipped before anything else), `stop()` | flush, then reset the envelope | same |
| `setVolume`, `getVolume`, `mute`, `unmute` | the base volume; the ExoPlayer gets base × gain | same |

"Sounding" = the ExoPlayer `isPlaying` and no pause pending — today's `playing` predicate.

- **Flush that is not followed by a takeover** (a pass-through seek, a plain app command arriving
  while an external change is queued) runs the queued changes and then fades in (`endChange`),
  never leaves the change's silence standing. Pause, resume and reset are takeovers.
- **Decisions.** Queued actions run in order at the silence and each reads the player as the actions
  before it left it, as the app's queue does today: two quick Nexts skip two rows, a second quick
  Previous after a restart (position now 0) goes to the row before, and Next then Previous comes
  back to the original row (outside an overlap: a landing mid-way into the incoming track past 3 s
  makes the Previous a restart, as the service does today). The one exception is a Previous that is the batch's *first* action: it
  is resolved at the press, with the position at the press against the 3 s threshold, into a pick of
  the pressed row (restart) or of the row before, by mediaId — so the fade-out cannot push it from
  "row before" to "restart", and it survives an automatic advance like any pick. A pick (`seekTo(index, …)`,
  `seekToDefaultPosition(index)`, `T_PICK`) captures the row's mediaId at the press and resolves it
  to an index at the silence (nearest match to the pressed index), so a queue edit in the window does
  not shift it.
- **Automatic advance during the fade-out.** If an AUTO transition (a gapless or crossfade advance)
  lands while a batch waits (a Next pressed in the last 150 ms of a track or of an overlap), the
  queued Next/Previous and seeks of that batch are dropped and the sound simply comes back: the
  listener already got the new track, and a seek meant for the old one would land in the wrong song.
  Picks (including a resolved first Previous) still run. A REPEAT transition (repeat-one restarting
  the row) drops nothing: the row is the same, Next still leaves it, a seek is still valid.
- **Start or resume** is decided by where the row stands, not by a flag (round 3). A `play()` that
  finds a pause pending reverses it from the gain reached (`beginResume`). Otherwise, if the player's
  position is at the start of its row (≤ 500 ms) the envelope resets and the start plays at full
  level — a new queue, a pick, an Auto `playFromMediaId`, a row restarted while paused, as today's
  new queue and pick from silence. Mid-row, it is a resume and rises over 220 ms once sounding — after
  a pause from any source, after becoming-noisy or a focus loss (ExoPlayer pauses on its own), after a
  restore or playback resumption at the saved position, as today's resume after launch. Two small
  departures from today, both acceptable: Next or Previous while paused and then Play starts the new
  row at full (today it rose); a same-row pick while paused plays at full (as today).
- **A queue installed during a pending pause** (`setMediaItem(s)` from an external `prepareFrom…`)
  completes the pause at once (`exo.pause()`) before the reset, so the new queue does not start
  playing by itself.
- **Error recovery and the shuffle fallback** in the app keep a pending pause: they call `play()`
  only when `playWhenReady && !AndroidTransportFade.pausePending`.
- **A picked row removed during the window** (no mediaId match left) drops the pick.
- **"At once" is synchronous.** Media3 calls the session player in sequences that expect each call to
  have taken effect (`Util.handlePlayButtonAction`: `seekToDefaultPosition()` then `play()`;
  `setMediaItems` then `prepare`/`play`). The overrides run every at-once path and every flush inline,
  without a dispatch: the service drives `ManualChangeFades` through a non-suspending entry point or
  an undispatched start, never a posted coroutine.
- **Volume seen by controllers.** `getVolume()` returns the base, but Media3 publishes the raw
  `onVolumeChanged` value into PlayerInfo, so controllers see base × gain while a fade moves (as they
  do today with the app's loop). Nothing read-modify-writes the player volume.
- **A second PLAY_PAUSE within the 160 ms** can only come from the notification or System UI (Media3
  holds a headset PLAY_PAUSE for the double-tap timeout, and a second tap there becomes Next). It
  reads the session's `playWhenReady` (still true) and pauses again, a no-op matching the Pause
  button those surfaces still show. The app's own button and the tile read the registry and resume. `getPlayWhenReady()` is *not* faked: the platform session
  builds its state from the getters on a timer, and a fake could leave the system controls showing
  Paused over playing music (round 2).

**The app's deferred changes go as ordered ops.** Player commands from a MediaController are
queued and flushed by a posted message, while a custom session command runs inline, ahead of them,
so a custom command would overtake a `addMediaItem` sent just before it (review round 1). The ops
therefore ride the channel that already exists for this reason: the `setPlaylistMetadata` marker
(`AndroidQueueCommand`), itself a player command in the same queue. New operations: `T_PAUSE`,
`T_NEXT`, `T_PREVIOUS`, `T_SEEK(position)`, `T_PICK(index, mediaId)`. The service evaluates each at execution
time against the ExoPlayer (sounding or not, has a next row, row-before vs restart, overlap landing)
and does the side steps the app did: `prepare()` when IDLE for Next/Previous/Pick; Pick also plays
and, when no change is pending, starts at full level. The app's controller does not mask playlist
metadata into anything visible, and the service never publishes the marker.

- **When the app sends an op:** only when its controller says sounding (`isPlaying` and no pending
  pause). This covers `pause()` too (the sleep timer calls it), not only the toggle. Not sounding, the app keeps sending plain player commands, masked as today, so a paused
  seek or pick moves the UI in the same frame; the override runs them at once.
- **Resume / toggle from the app:** a plain `play()` (not deferred, so no flicker) — the override
  gives the rise, or reverses a pending pause. Toggle: `T_PAUSE` when the app would pause, else the
  ENDED/IDLE pre-steps and `play()` as today, all in the same ordered queue.

**History.** The announcement (`AndroidPlaybackStarts.announce`) moves into the service's action for
the app's ops, at the moment of the change against the ExoPlayer itself, so two quick Nexts still
name two rows. External Next and Previous keep being read from the seek as today.

**Paused at once in the UI.** The service publishes its pending pause
(`AndroidTransportFade.pausePending: StateFlow<Boolean>`, a process registry like
`AndroidShuffleModeRegistry`; service and app share the main looper). A pending pause raises no
player event, so the app collects the flow and calls `pushState`, and the service collects it and
republishes the widget snapshot (`publishWidgetSnapshot`, today `pausePending = false`). The tile
(`PlaybackTileService`, same process) reads it too, so its tap during the fade resumes. The service
clears it in `onDestroy`. A pause from any source shows at once in the app, the widget and the
tile.

**Envelope resets** stay where they are today: a new queue from the app (`setMediaItems`) and a pick
from silence. A recovery seek (`seekToDefaultPosition(replacement)` after an error) does not reset,
so a pending pause survives an error as it does today.

**SMART planning stays in the app.** Next at the end of the queue plans first; the op goes into the
same ordered queue after the planned `addMediaItem`, so it finds the new row. If the music is not
sounding, the app sends a plain `seekToNextMediaItem()` after the add, also in order.

## What changes for the listener

- The tile, notification, lock screen, headset and Bluetooth buttons and Android Auto now fade on
  pause, resume, Next, Previous and seeks, with the same lengths as the app.
- On those surfaces the change shows 150 ms (seek 60 ms, pause 160 ms) after the press, because
  their state follows the player. A notification seek bar may sit at the old point for 60 ms after
  release.
- In the app nothing changes, except that a pause from outside shows at once.

## Limits

- A third-party app using a Media3 MediaController (rare; the system surfaces and Auto do not) will
  see the flicker described above on Next, seeks and a deferred pause (its play/pause button). Its
  commands still work.
- Rapid Nexts past the rows SMART has already planned: the app sends `T_NEXT` from its unmoved
  controller and the service finds no next row, so the extra Next does nothing (today the app
  planned first). Only with a lookahead of 1 or a nearly empty queue; the default is 20.
- After an automatic advance in the window, a resolved "row before" pick is logged as a pick rather
  than a skip back. Cosmetic.
- iOS is unchanged: every source already goes through the controller.

## Tests (host, `core:playback` androidHostTest)

- `SessionTransportFades` with a fake player and a test clock: external pause waits for silence and
  sets volume to 0 first; resume rises; Next / Previous / seek wait for silence and run in order;
  two quick Nexts skip two rows; a pause during a change runs the change first; paused commands run
  at once; base volume × gain.
- Session player routing: an external `seekToNext` is deferred, an app-controller one passes
  through after a flush; a set of items resets the envelope.
- The app's ops: each op reaches the owner in order after earlier player commands (an add then
  `T_NEXT` while paused finds the new row); history is announced at the change.
- A redundant `play()` while sounding leaves gain at 1 and leaves a pending change alone; a `play()`
  that resumes flushes queued changes first;
  paused + `setMediaItems` + `play()` keeps gain 1; a paused row resumed mid-track rises (also after
  a restore and after an ExoPlayer-internal pause); a queue installed during a pending pause pauses;
  repeat-one + Next in the last 150 ms still leaves the row; Next then Previous returns (no overlap); a second pause during
  a pending pause is a no-op; two quick Nexts skip two rows; two quick Previous presses past 3 s go
  restart then row before; a Next with no next row does not dip; a pick resolves by mediaId after an
  insert; an auto-advance during the fade-out drops the queued Next; null caller runs at once.
- Existing `TransportFadeState` / `ManualChangeFades` tests stay as they are (iOS uses them).

## Review log

- **Round 1 (fresh subagent, 2026-10-26): REVISE.** Blocking: (1) a custom session command runs
  inline while the app's earlier player commands wait for the controller's flush message, so an op
  could overtake `addMediaItem` and lose a Next — ops now ride the ordered `setPlaylistMetadata`
  marker and are evaluated at execution; (2) `play()` while playing dipped to silence and cut a
  pending change short — now a no-op while sounding, and flushes before `beginResume`. Non-blocking,
  all taken: flush must fade in when no takeover follows; volume/mute overrides; `setPlayWhenReady`
  routed; null caller at once; jobs cancelled in `onDestroy`; actions call `super`; decisions
  captured at the press (auto-advance race, Previous threshold); `getPlayWhenReady` false during a
  pending pause; widget reads the registry; resets no broader than today; side steps of Pick and
  Previous listed; plain player commands while not sounding so the UI moves in the same frame.
  Confirmed by the round: masking analysis, caller identification on every path, no flicker from
  the tile or Media3's notification controller.
- **Round 2 (fresh subagent, 2026-10-26): REVISE.** Confirmed the round 1 fixes in Media3: the
  marker rides the same per-controller command queue as `addMediaItem`, its masking is invisible
  (`MediaMetadata.equals` ignores extras), legacy and media-button paths set the caller. Blocking:
  (1) capturing the row at the press and dropping on mismatch broke two quick Nexts and the double
  Previous — now each queued action reads the state the earlier ones left, only the first Previous
  keeps the press position, and only an AUTO/REPEAT transition drops queued Next/Previous/seeks;
  (2) `play()` rose from silence on every fresh start — now a `startsAtFull` flag keeps new queues
  and picks from silence at full level. Non-blocking, taken: `getPlayWhenReady` override dropped
  (stale platform state); second pause during a pending pause is a no-op; tile reads the registry;
  the registry is collected and pushed, cleared in `onDestroy`; no dip for a Next with no next row;
  picks resolved by mediaId; app `pause()` (sleep timer) sends `T_PAUSE` when sounding.
- **Round 3 (fresh subagent, 2026-10-26): REVISE.** Blocking: (1) the `startsAtFull` flag's clear
  rule broke the rise after a restore or after an ExoPlayer-internal pause — replaced by the
  position rule (start of row = full, mid-row = rise); (2) dropping on REPEAT lost a Next under
  repeat-one — only AUTO drops now. Non-blocking, taken: the press-position rule only for a Previous
  that is the batch's first action, resolved into a pick by mediaId so it survives AUTO; recovery
  keeps a pending pause; a queue installed during a pending pause completes the pause; at-once paths
  synchronous; volume note; double-tap text corrected; `T_PICK` carries the mediaId. Confirmed: the
  overlap landing and `pendingRise`, the second Next during an overlap, registry pushes, sleep timer,
  Auto and resumption paths.
- **Round 4 (fresh subagent, 2026-10-26): READY.** Verified the position rule against ExoPlayer's
  synchronous seek masking, `Util.handlePlayButtonAction`, crossfade joins (the player's position
  stays the track's own) and restore/resumption; the resolved first Previous against Media3's
  `seekToPrevious`; synchronous at-once paths on `Main.immediate`. Non-blocking, taken: the shuffle
  fallback gated like recovery; test text matches the routing table; "Next then Previous" narrowed
  to outside an overlap; limits list rapid Nexts past planned rows and the play/pause flicker for
  third-party Media3 controllers; a removed picked row drops the pick; the history label note.
