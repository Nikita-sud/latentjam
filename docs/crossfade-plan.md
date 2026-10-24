# Real crossfade (issue #23) — design

Status: APPROVED by the owner 2026-10-22 (single-player mix, metadata switches at the end of the
overlap, normalisation in the PCM chain, iOS in the same round) and IMPLEMENTED on
claude/crossfade-23-ba5c6f. Verified: host tests, iOS simulator tests, and
`CrossfadeOverlapDeviceTest` on an API 30 emulator (two WAVs through the production chain: every
frame of the incoming track exactly once, sum correct mid-overlap, position 2022 ms right after a
2 s overlap). Not yet verified: listening on a phone; iOS overlap at runtime (the simulator test
host cannot start AVAudioEngine). iOS deviates from the plan in one detail: decks are ramped from
the controller every 20 ms (not sample-scheduled) — inaudible for two different songs.

## Today

- Android: "Smooth transitions" (0–12 s) is a volume envelope that the app-side controller writes to
  `MediaController.volume` (`applyEffectiveVolume` in `PlaybackController.android.kt`): fade out over
  the last N s, fade in over the first N s, gapless join in between, no overlap. The same gain stage
  carries loudness normalisation (`trackVolumes`) and the pause/resume fade. `continuesAlbum` keeps
  album seams played in order unfaded.
- iOS: `setCrossfadeSeconds` is the interface's no-op. The slider is stored (`AppSettings.ios.kt`) but
  changes nothing.

## Android — chosen approach: overlap inside the one player's PCM chain

One ExoPlayer, one AudioTrack, one MediaSession, as today. The next track's opening is decoded ahead
of time and mixed sample-exactly into the last N seconds of the current track; when the player reaches
the next track by itself, the part already heard is skipped.

1. **Pre-roll.** About N + 10 s before the end of track A, the service decodes the first N + 2 s of the
   next track B with a short-lived second ExoPlayer that never touches the speaker: same renderers,
   `DefaultAudioSink` with the same PCM16 conversion and gapless trimming, and a capture
   `AudioOutputProvider` that consumes as fast as the decoder runs. So the captured PCM is bit-identical
   to what the main player will later produce for B. The capture player is released as soon as the
   buffer is full (1–2 s of work). PCM lives in a memory-mapped scratch file, unlinked once
   mapped, so it is file cache, not Java heap (on ART `allocateDirect` IS heap): 12 s + 2 s of
   44.1 kHz stereo ≈ 2.5 MB; hard cap 8 MiB, above it no overlap. No capture either when the heap has
   under 12 MB free; the seam then gets the plain fades.
2. **Mix.** A new `CrossfadeAudioProcessor` sits before the EQ in the main sink. From N s before A's end
   it outputs `A·out(t) + B_intro·in(t)` (equal-power curves, pure function in commonMain, unit-tested).
   It counts how many intro frames M it actually mixed.
3. **Join.** A subclassed `MediaCodecAudioRenderer` knows which media item each stream belongs to
   (`onStreamChanged` + `getTimeline()`, signalled at `onProcessedStreamChange`). When B's first frames
   arrive and B is the pre-rolled item, the processor drops exactly M frames and reports them through
   the `AudioProcessorChain` skipped-frame count (the mechanism silence skipping uses), so B's position
   reads correctly. If A's real end differs from its duration estimate, the envelope is time-based and
   the extra 2 s of intro absorb the difference — the join stays sample-exact.
4. **Fallback = today's fade** (volume envelope, no overlap) whenever an overlap cannot be exact:
   different sample rate or channel count between A and B, unknown duration, intro not ready in time,
   B fails to decode, too large for the cap.

Rejected: **two ExoPlayers** (a tail player takes over A while the session player jumps to B). The
hand-over of the same track between two AudioTracks cannot be aligned to the sample; the misalignment
is tens of ms and is heard as a stutter at full volume, exactly where the fade begins. Also adds a
second AudioTrack, a SEEK transition to re-label, and audio focus/ducking for two players.

### How everything else keeps working (Android)

| Area | Behaviour |
|---|---|
| Gapless albums | `continuesAlbum` keeps its meaning: no overlap (and no fade) between consecutive tracks of one album played in order. Decided per queue row (by player index on Android), so an album track queued again elsewhere neither breaks the album's seams nor inherits them. |
| Start-cause ledger | Untouched: B still begins with an AUTO transition at A's natural end → `AUTO_ADVANCE`, or `QUEUED` if queued by hand. Logged once. |
| History of A | A plays to its real end → counted as completed, as today. B's session opens at position ≈ N s (its first N s were heard during the overlap). |
| SMART top-ups | Unchanged; pre-roll takes whatever is next when it starts and re-checks before mixing. |
| Queue edit during pre-roll/overlap | Next track changed → intro discarded; if already mixing, the intro fades out in 300 ms (Android) or stops at once (iOS, the standby player is stopped) and the new next track starts normally. |
| Normalisation | Moves from `player.volume` into the PCM chain, per stream, so A and B each keep their own gain during the overlap and the change at the join is sample-exact (today it snaps on a main-thread callback). Mid-track re-measurements still ramp 400 ms dB-linear. `player.volume` keeps only the pause/resume fade. |
| EQ / limiter | One EQ on the summed signal; the existing limiter catches peaks of the sum. |
| Session, notification, widget, Android Auto | Single player → unchanged. Trade-off: the metadata switches to B when A actually ends, i.e. at the END of the overlap, not at its start (Spotify switches earlier). |
| Pause / resume | Both songs pause and resume together (one stream), with the existing transport fade. |
| Seek during overlap | Inside A: overlap re-arms at the right offset if the intro is still valid, otherwise cancels. |
| Next during overlap | Goes to B at the point already heard (no restart of B's opening), from any controller: the app, the notification, a headset, the lock screen, Android Auto (the session player handles it). The point is read from the player's position, i.e. what reached the speaker, until the join is heard; B's rise carries on along the overlap curve. Ledger `SKIP_NEXT` as today. |
| Previous during overlap | As today. |
| Repeat one, or the same track next | Never overlaps a track with its own opening (repeat-one, a one-row queue on repeat-all, Play next on the playing track): its next play would start seconds in, which the sleep timer's wrap check and the listening log's restart check (both expect ~0) miss. Those seams keep the plain fades. |
| Short tracks | An overlap is at most a third of either track (`overlapLengthMs`), so a track overlapped at both ends still has a third of itself alone. |
| End of queue | No next → plain fade-out as today. |
| Sleep timer "end of track" | While armed, the timer tells playback (`setFinishingCurrentTrack`): no overlap is prepared and one in progress lets the next track go in 300 ms, so the track fades out on its own and the pause lands at its end, not seconds into the next track. |
| Audio focus, becoming noisy, ducking | One player → unchanged. |
| 48 MB heaps | Intro PCM in a mapped file (not heap); capture player lives 1–2 s with a 1 MB load buffer, and is not started with under 12 MB of heap free. |

## iOS — second player node in the existing AVAudioEngine graph

Graph becomes `deck A ─┐ mixer → EQ Audio Unit → output`, `deck B ─┘`. At A's end − N the controller
schedules B on the idle deck at an exact sample time, ramps both deck volumes (normalisation × curve),
and when A's segment completes the decks swap roles and the controller advances to B without reloading
it — the same "switch at the end of the overlap" semantics and the same ledger/history behaviour as
Android. Apple Music (MPMusicPlayerController) items cannot be mixed: no overlap there (plain switch).
This also makes the slider do something on iOS for the first time.

## Prior art (searched 2026-10-22, at the owner's request)

Nothing below was copied; GPL projects informed ideas only.

| Where | Approach | Got right | Pitfalls | Licence |
|---|---|---|---|---|
| Media3 itself — [androidx/media#2](https://github.com/androidx/media/issues/2), dup of [google/ExoPlayer#3438](https://github.com/google/ExoPlayer/issues/3438) | None built in; open since 2017, "no current plans". A Media3 engineer sketched a [processor design](https://github.com/google/ExoPlayer/issues/3438#issuecomment-970557233): fade like gapless trimming, crossfade by mixing the end buffer with the next track's start inside the pipeline | Same family as ours: one player, one timeline | Their variant buffers the outgoing END, so the position jumps to the next track when the overlap begins; maintainers warned a multi-renderer hack gives a "messy Timeline" | Apache-2.0 |
| Same thread, Tolriq (Symfonium) | Tried fades in an AudioProcessor | — | The processor runs ahead of the speaker, so a fade for pause or skip cannot be done there; asked for VolumeShaper access to the AudioTrack | — |
| [react-native-track-player fork](https://github.com/lovegaoshi/react-native-track-player/pull/38) | Two ExoPlayers that rotate behind a copied 1 220-line ForwardingPlayer; both kept on the same queue; iOS: two AVPlayers, delegate swapped on rotation | Works with any format; metadata flips at the start | Author: "a whole spaghetti of player control problems"; both queues must be kept in sync; the app must call prepare/crossfade itself | Apache-2.0 (reusable, but the approach is what we rejected) |
| [SimpMusic](https://simpmusic.org/docs/dev/playback-engine) | One ExoPlayer per track behind a forwarding player with its own playlist; a processor sweeps low-/high-pass filters across the transition (DJ-style) | The filter sweep makes a busy overlap less muddy | Owns its queue outside ExoPlayer; streams excluded | GPL-3.0 (ideas only) |
| RetroMusic `CrossFadePlayer` | Two framework MediaPlayers, the next started N s before the end with volume animators | Simple | No gapless, coarse timing, no album rule | GPL-3.0 (ideas only) |
| Namida | Overlap N s before the end; also on manual skips; crossfade beats gapless | Crossfade on Next as an option | Notes extra memory for buffering both tracks | custom/unclear (ideas only) |
| Auxio, Gramophone, Metrolist, InnerTune | No overlapping crossfade found (Auxio closed its request) | — | — | GPL-3.0 |
| iOS: Apple AVAEMixerSample; dev-forum threads | Two AVAudioPlayerNodes into one mixer, per-node volume | The standard AVAudioEngine shape — ours | Two nodes can drift if started separately; schedule both on one engine | Apple sample licence |

**Verdict: the design does not change.** The only design the Media3 team has sketched is ours (mix inside one
player's pipeline). Ours pre-decodes the incoming opening instead of holding back the outgoing end, so the position
stays on the playing track and flips at the end. Every two-player implementation found has to own the queue
outside ExoPlayer (a forwarding player, two synced queues). For us that would rewire the SMART top-ups, the ledger
and Android Auto. The Tolriq pitfall is already handled: pause/resume stays on player volume, not in the
processor.

Ideas worth offering later, not in this round:
- a SimpMusic-style filter sweep during the overlap;
- crossfade on manual Next, as in Namida (today Next during an overlap carries on, otherwise it cuts);
- sample-accurate `AVAudioTime` start of the second node on iOS (today a 20 ms ticker).

## Tests

- commonTest: curves, overlap-window planning (when to pre-roll, when to fall back), album rule.
- androidHostTest: `CrossfadeAudioProcessor` on synthetic PCM (sum, frame-exact skip, M ≠ N, cancel,
  flush), stream-identity bookkeeping, normalisation gains per stream.
- androidDeviceTest/emulator: two real files overlap audibly; position of B after the join; ledger.
- `./gradlew --no-daemon --max-workers=4 testAndroidHostTest` + `compileTestKotlinIosSimulatorArm64`.

## Open questions for the owner

1. Approve the single-player PCM-mix approach (and the fallback to today's fade)?
2. Metadata switching at the end of the overlap — OK, or must it switch at the start?
3. Moving normalisation into the PCM chain for everyone (needed for correct overlap loudness) — OK?
4. iOS in this round too, or Android first?
