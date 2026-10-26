# Short fades on manual track changes and seeks — design

Status: IMPLEMENTED 2026-10-26 as approved (150/150 and 60/100 ms; Android: in-app commands only).
Branch `feat/manual-change-fades` from `fix/crossfade-early-end`. Not yet heard on a phone.

Deviations found while implementing:
- **Next at the end of the queue,** while SMART plans the next row, runs at once as before. Under
  the fade it would hold a silence for as long as the planning takes.
- **iOS Next during an overlap** keeps its seamless promotion with no dip: the next track is already
  playing on its own deck, so nothing is cut. Android's landing is a seek and gets the dip.
- **iOS:** a load or reset inside the change does not restore full gain; the silence holds until the
  change's own arrival.

## Problem

A manual Next/Previous, a tap on another queue row, a seek-bar tap or release, and Previous
restarting the track all cut the sound at once. The new audio starts with the 5 ms anti-click
ramp the equalizer applies after every reposition, so there is no click, but the cut is abrupt.
Pause and resume already fade (160 ms out, 220 ms in, `TransportFadeState`). Poweramp gives manual
changes and seeks their own short fades (defaults 400 ms and 100 ms); we have no equivalent.

## Why the fade cannot live in the PCM chain

The PCM chain runs 250–750 ms ahead of the speaker (the AudioTrack buffer), and a seek flushes
that buffer. A fade-out written into the PCM now would be thrown away by the seek before anyone
hears it, or the seek would have to wait out the whole buffer. The transport fade already solves
this with the player's volume (AudioTrack volume, which takes effect within a mix cycle). The
manual fades reuse that envelope.

## Behaviour

| Command (while playing) | Out | Then | In |
|---|---|---|---|
| Next / Previous to another row, a tap on a queue row, a swipe on the cover | 150 ms | the change | 150 ms |
| Seek-bar tap or release, Previous restarting the track, a resume-point jump | 60 ms | the seek | 100 ms |

- **Paused or buffering:** no fade; the command runs at once, as today.
- **Equal-power curves**, the same as the pause fade (`transportFadeFactor`).
- **Quick repeats:** commands that arrive while the sound is leaving are queued and all run at the
  moment of silence, followed by one fade-in. Two quick Nexts still skip two tracks; seeks
  coalesce to the last target.
- **Pause during a change:** the queued commands run at once and the pause fade takes over from
  the gain reached. **Resume:** unchanged.
- **Next during an overlap:** it still lands on the point of the next track already heard
  (`CrossfadeCoordinator.nextFromOverlap`), now with the short dip instead of a hard cut of the
  mix.
- **Untouched:** automatic advance (crossfade or gapless), album seams, the sleep timer's end of
  track, and the 5 ms anti-click ramp after a reposition, which still runs under the fade-in.
- **Always on**, like the pause fades: no new setting, independent of the Smooth transitions
  slider. At 0 the slider keeps hard track boundaries at *automatic* seams; a manual cut is a
  different event.

## Where it lives

- **Common:** `TransportFadeState` gains a "change" mode: fade out over a given length, hold
  silence until the controller reports the change done, then fade in over a given length. Pure,
  unit-tested on the JVM, like the pause envelope. `transportFadeFactor` is reused unchanged.
- **Android (`PlaybackController.android.kt`):** `next`, `previous`, `seekTo` and `playAt` go
  through one `fadeThen(kind) { … }` helper. It starts the out-fade on `player.volume` (the same
  20 ms gain loop as the pause fade), delays the actual Media3 call until silence, and starts the
  fade-in once the player reports playing again. Listening-history announcements move with the
  call, so they still describe what actually happened.
- **iOS (`PlaybackController.ios.kt`):** the same helper on `audioEngine.setTransportGain`. On
  iOS every source — app, lock screen, Control Center, headphones, CarPlay — goes through this
  controller, so all of them get the fades.

## Limits and choices for the owner

1. **Android sources.** *Resolved 2026-10-26 by `docs/service-side-fades-plan.md`: the envelope
   moved into the service and every source fades.* As with pause today, only commands from inside the app fade. Notification,
   headset, Android Auto and widget commands go straight to the service's player and stay instant.
   Covering them means doing the fade in the service's session player instead. That is a larger
   change: the session acknowledges a command before the delayed change happens, so the
   controller's view must be kept from flickering back for 150 ms. Proposed as a follow-up, for
   pause and changes together.
2. **Latency.** A change lands 150 ms after the tap, and a seek 60 ms after. The cover and seek bar
   already move at once, because the UI animates on the gesture, so only the sound waits.
3. **Lengths.** 150/150 and 60/100 ms are starting points to tune by ear on the phone. Poweramp's
   defaults are 400 ms for a manual change and 100 ms for a seek.

## Tests

- `TransportFadeState` change mode: envelope shape, holding silence until done, quick repeats,
  a pause during a change, a change during a resume fade.
- Android controller: commands are delayed to silence and run in order, no delay while paused,
  and history is announced once per real change. This uses the existing fake-player host tests
  where they exist; where they do not, the timing logic is factored into a small pure scheduler.
- iOS: the common tests, plus a compile check. Runtime only by a silent simulator check (no
  audible output on the Mac) and later on a device.
