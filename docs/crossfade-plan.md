# Real crossfade (issue #23) — design

Status: APPROVED by the owner 2026-10-22 (single-player mix, metadata switches at the end of the
overlap, normalisation in the PCM chain, iOS in the same round) and IMPLEMENTED in release/0.9.3
(through its review rounds).

Verified on Android: host tests, and `CrossfadeOverlapDeviceTest` on an API 30 emulator (two WAVs
through the production chain: every frame of the incoming track exactly once, sum correct
mid-overlap, position ≈1690 ms right after the 1666 ms overlap — a third of the 5 s incoming
track — plus repeat, sleep-timer, slider-drag, real-seek Next, short-track and heap checks).

Verified on iOS: simulator tests, and the app itself on an iPhone 17 simulator (scripted, measured
by a tap, silent): overlap on equal-power curves at a steady level, a clean promotion, pause and
resume inside an overlap, Next landing on the heard point (also while paused), a 22.05 kHz mono
neighbour refused with plain fades, repeat-one without overlap, the queue running to its end, a
steady level around a short overlap, seeks re-applying the levels and a late start joining in step.
The first run found the setting had never reached iOS playback (the capability flag was off) —
fixed. iOS deviates from the plan in one detail: decks are ramped from the controller every 20 ms
(not sample-scheduled) — inaudible for two different songs.

Not yet verified: listening on a phone.

## Before this work

- Android: "Smooth transitions" (0–12 s) was a volume envelope that the app-side controller wrote to
  `MediaController.volume` (`applyEffectiveVolume` in `PlaybackController.android.kt`): fade out over
  the last N s, fade in over the first N s, gapless join in between, no overlap. The same gain stage
  carried loudness normalisation (`trackVolumes`) and the pause/resume fade. `continuesAlbum` kept
  album seams played in order unfaded.
- iOS: `setCrossfadeSeconds` was the interface's no-op and the slider was hidden;
  the setting changed nothing.

## Android — chosen approach: overlap inside the one player's PCM chain

One ExoPlayer, one AudioTrack, one MediaSession, as today. The next track's opening is decoded ahead
of time and mixed sample-exactly into the last N seconds of the current track; when the player reaches
the next track by itself, the part already heard is skipped.

1. **Pre-roll.** About N + 10 s before the end of track A, the service decodes the first N + 2 s of the
   next track B with a short-lived second ExoPlayer that never touches the speaker: same renderers,
   `DefaultAudioSink` with the same PCM16 conversion and gapless trimming, and a capture
   `AudioOutputProvider` that consumes as fast as the decoder runs. So the captured PCM is bit-identical
   to what the main player will later produce for B. The capture player is released as soon as the
   buffer is full (1–2 s of work). PCM lives in one of two reused scratch slots
   (`IntroScratch`): memory-mapped files, unlinked once mapped, so file cache and not Java heap (on
   ART `allocateDirect` IS heap: measured 6.1 MB vs 7 KB for a 14 s opening at 96 kHz). A slot is
   zero-filled through the file before it is mapped, so a full disk means "no capture" rather than a
   SIGBUS on the first write, and grown only when an opening needs more; a new capture takes the
   slot the mixer is not reading. 12 s + 2 s of 44.1 kHz stereo ≈ 2.5 MB; hard cap 8 MiB, above it
   no overlap. No capture either when the heap has under 12 MB free; the seam then gets the plain
   fades. A next track shorter than the request is captured whole.
2. **Mix.** A new `CrossfadeAudioProcessor` sits before the EQ in the main sink. From N s before A's end
   it outputs `A·out(t) + B_intro·in(t)` (equal-power curves, pure function in commonMain, unit-tested).
   It counts how many intro frames M it actually mixed.
3. **Join.** A subclassed `MediaCodecAudioRenderer` knows which media item each stream belongs to
   (`onStreamChanged` + `getTimeline()`, signalled at `onProcessedStreamChange`). When B's first frames
   arrive and B is the pre-rolled item, the processor drops exactly M frames and reports them through
   `CrossfadeProcessorChain.getMediaDuration` (the sink maps playout since its checkpoint at the join
   through it, adding the frames skipped), so B's position
   reads correctly. If A's real end differs from its duration estimate, the envelope is time-based and
   the extra 2 s of intro absorb the difference — the join stays sample-exact.
   Dropping alone starved the sink (owner's phone, 0.9.3 test build: a short pause at every seam). In
   Media3 1.10 the audio renderer is ready only while the sink holds unplayed frames, so while the
   decoder worked through B's opening the player went BUFFERING and paused the AudioTrack. Now B plays
   on from the decoded intro (frames M… of it) at the join, while the decoder's copy of the whole intro
   is dropped. The intro frames go out in proportion to the frames dropped, so every buffer in still
   puts sound out.
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
| Gapless albums | `continuesAlbum` keeps its meaning: no overlap (and no fade) between consecutive tracks of one album played in order. Decided per queue row (by player index on Android), so an album track queued again elsewhere neither breaks the album's seams nor inherits them. Accepted: the controller publishes these by its copy of the player indices, so for one IPC round trip after a reorder an index may name a row's old place; the envelope's slew makes that a glide. |
| Start-cause ledger | Untouched: B still begins with an AUTO transition at A's natural end → `AUTO_ADVANCE`, or `QUEUED` if queued by hand. Logged once. |
| History of A | A plays to its real end → counted as completed, as today. B's session opens at position ≈ N s (its first N s were heard during the overlap). |
| SMART top-ups | Unchanged; pre-roll takes whatever is next when it starts and re-checks before mixing. |
| Queue edit during pre-roll/overlap | Next track changed → intro discarded; if already mixing, the intro fades out in 300 ms while the current track keeps its out-curve (both platforms), and the new next track starts normally. |
| Normalisation | Moves from `player.volume` into the PCM chain, per stream, so A and B each keep their own gain during the overlap and the change at the join is sample-exact (today it snaps on a main-thread callback). Mid-track re-measurements still ramp 400 ms dB-linear. `player.volume` keeps only the pause/resume fade. |
| EQ / limiter | One EQ on the summed signal; the existing limiter catches peaks of the sum. |
| Session, notification, widget, Android Auto | Single player → unchanged. Trade-off: the metadata switches to B when A actually ends, i.e. at the END of the overlap, not at its start (Spotify switches earlier). |
| Pause / resume | Both songs pause and resume together (one stream), with the existing transport fade. |
| Seek during overlap | Inside A: overlap re-arms at the right offset if the intro is still valid, otherwise cancels. iOS likewise: an overlap begun more than 100 ms late (`LATE_OVERLAP_START_MS`: a seek into the window, the slider raised inside it) re-cues the next track at the overlap's offset, both curves on one clock, opening with a 20 ms fade written into its first samples against a click; one begun on time plays the segment cued ahead from its first frame. Every seek sets the deck levels before the graph restarts and re-arms the loop at once. |
| Next during overlap | Goes to B at the point already heard (no restart of B's opening), from any controller: the app, the notification, a headset, the lock screen, Android Auto (the session player handles it). The point is read from the player's position, i.e. what reached the speaker, until the join is heard; B's rise carries on along the overlap curve: the coordinator records it before seeking, because the seek disables the renderer and flushes the chain more than once before B plays (measured on a real seek: gain 0.63 against 0.59 on the curve, 0.33 for the fallback fade). The first track to play after that seek spends the recorded rise, and only B at the landing point takes it, so a quicker second Next or a new pick lets it lapse. A rise in progress runs to its end even if the slider goes to 0. Ledger `SKIP_NEXT` as today. |
| Previous during overlap | As today. |
| Repeat one, or the same track next | Never overlaps a track with its own opening (repeat-one, a one-row queue on repeat-all, Play next on the playing track): its next play would start seconds in, which the sleep timer's wrap check and the listening log's restart check (both expect ~0) miss. Those seams keep the plain fades. |
| Short tracks | An overlap is at most a third of either track (`overlapLengthMs`), so a track overlapped at both ends still has a third of itself alone. |
| End of queue | No next → plain fade-out as today. |
| Sleep timer "end of track" | While armed, the timer tells playback (`setFinishingCurrentTrack`): the coordinator hears it at once (not on its 500 ms tick), so no overlap is prepared and one in progress lets the next track go within about one audio buffer plus 300 ms, so the track fades out on its own and the pause lands at its end, not seconds into the next track. |
| Audio focus, becoming noisy, ducking | One player → unchanged. |
| 48 MB heaps | Intro PCM in a mapped file (not heap); capture player lives 1–2 s with a 1 MB load buffer, and is not started with under 12 MB of heap free. |

## iOS — second player node in the existing AVAudioEngine graph

Graph becomes `deck A ─┐ mixer → EQ Audio Unit → output`, `deck B ─┘`. At A's end − N the controller
starts B on the idle deck from its 20 ms transition loop (not sample-scheduled: inaudible for two
different songs), ramps both deck volumes (normalisation × curve),
and when A's segment completes the decks swap roles and the controller advances to B without reloading
it — the same "switch at the end of the overlap" semantics and the same ledger/history behaviour as
Android. Apple Music (MPMusicPlayerController) items cannot be mixed: no overlap there (plain switch).
This also makes the slider do something on iOS for the first time. Like Android, a next track of
another sample rate or channel count does not overlap: the graph after the submixer runs in the
current file's format, so it keeps the plain fades and loads, reconfiguring the graph for itself.

## Sound edges (2026-10-25)

An overlap anchored to the file's end runs partly over a track's trailing silence: the outgoing
music has already stopped while the next track is still rising, which sounds like a dip or a pause.
Seams now meet the tracks' sound instead.

- **Measured once, in the loudness pass.** `SoundSpanTracker` sees the PCM the R128 scan already
  decodes (Android `AndroidIntegratedLoudnessMeter`, iOS `IosIntegratedLoudnessMeter`). The span runs
  from the first to the last 50 ms window above −50 dBFS (mean square), so a quiet passage or a hidden
  bonus track after a long gap stays inside. It is stored next to the loudness (cache line
  `r128-v2`; `r128-v1` rows are still read). The scan now also runs while only smooth transitions are
  on, without changing any volume. A row from before sound edges keeps its loudness and is measured
  once more while transitions are on.
- **Anchors (`seamAnchors`).** The overlap ends at the outgoing track's last sound and the incoming
  track starts at its first. A 100 ms guard stays on the sound's side. An edge shorter than 300 ms is
  left alone, and one longer than 30 s, or an end past the file, is not trusted. Unmeasured tracks
  keep the files' edges. The overlap length rules apply to the audible lengths. Album seams get no
  overlap, as before.
- **Android.** The opening capture also covers the incoming lead and the outgoing trail. The mixer
  plays the incoming track alone, at full level, over the outgoing track's trailing frames, so the
  join and its bridge stay seamless and no frame is dropped without output. A capture that cannot
  cover that (the size cap, a short track) is decoded again for the files' own edges.
- **iOS.** The standby deck is cued at the incoming track's first sound. The outgoing curve ends at
  the last sound, and that deck is silent over its trailing silence until the decks swap.

## Mixed sample rates (2026-10-25)

Owner's report: no smooth transition from a 44.1 kHz FLAC into a 48 kHz Opus. The mixer only mixes
an opening of the outgoing track's rate, so such a seam fell back to the plain fades. DefaultAudioSink
also replaced its AudioTrack at the join because the output rate changed, which pauses the sound.

- **Android: one output rate across a seam.** `SeamResamplingAudioSink` sits in front of
  DefaultAudioSink. It was first a processor first in the chain, which broke the sink's clock; see
  "Clock jump" below.
  After a reposition (a start, a seek, a skip, where the sink restarts anyway) a track plays at its
  own rate. A track the player moves on to by itself, while smooth transitions are on, plays at the
  rate the output already has. A track at that rate passes through bit for bit, because the processor
  is inactive then. So the AudioTrack is kept, and the opening can mix. The rate is published as
  `CrossfadeSettings.outputRate`. The intro capture resamples to it with the same processor, so the
  captured frames equal the player's at the join.
- **Resampler (`PcmResampler`, commonMain).** Kaiser-windowed sinc with 16 zero crossings, β 8 and a
  95 % passband. A downsampling cutoff narrows to the output's Nyquist. Output frame n sits exactly at
  input n × in / out in integer arithmetic, so the output does not depend on how the input is cut into
  buffers (tested), and it has no delay. Measured: below −70 dB error at 44.1 → 48 kHz, below −60 dB
  at 48 → 44.1 kHz for a 15 kHz tone, and a 30 kHz tone at 96 → 48 kHz stays below −70 dB. Cost is
  about 32 taps per output frame and channel at 44.1/48 kHz; downsampling from 192 kHz takes about
  140.
- **iOS.** The submixer converts each input bus to the graph's rate, so a deck of another rate now
  joins (`sharesGraphFormat` compares channel counts only), and plays on converted until the next
  load. Same channel count is still required on both platforms.

## Late openings and junk tails (2026-10-26)

Owner's report: "In the Meantime" → "Locked Away" faded instead of overlapping the first time, and
overlapped the second. The slider is at 12 s and the MP3's real end is 276.95 s, so the window opens
at 264.95 s. The owner seeked to 264.0 s, and the next opening's decode, started by that seek, landed
around 266 s. The processor settles a seam once, at the window, so it chose the plain fades with no
plan yet. The second time a seek to 246 s left time for the decode.

- **A seam waits for an opening being decoded.** The coordinator marks the seam
  (`CrossfadeSettings.preparingFor`), and the processor plays on at full level for at most half the
  fade (3 s at most) past the window instead of settling on the fades. An opening that arrives late
  still mixes on its own clock: the next track comes in where the overlap has got to, rising no
  faster than a cancel falls (300 ms). That holds up to half the overlap (3 s at most) late. A start
  before the window, from the slider lowered after the decode, still takes the fades as before.
- **A file that runs out before its estimated length is measured as it plays.** That MP3 has no
  seek table. The container's 299.5 s estimate comes from the file size and includes a junk tail,
  while the decoder reaches its end at 276.9 s. The loudness and sound-edge scan rejected that as a
  truncated scan, so the track never got a measurement. When the source itself ends, the scan now
  stands down to half of the estimate (`LoudnessScanLimits.complete(sourceEnded = true)`; the file
  size is part of the audio identity).
- **Seam log.** `LJCrossfade` lines for each seam decision: overlap with its anchors and lateness,
  fade with its reason, and a capture that failed.

## Stutter after a join into Opus (2026-10-26)

Owner's report: a farting noise from Bohemian Rhapsody (FLAC 44.1 kHz) into Heartbreaker (Opus
48 kHz). The phone (vc34) went BUFFERING/PLAYING every 250–350 ms for 3 s after the join, at
19.5 s into Heartbreaker.

- **Cause.** At the join the player drops its decoder's copy of the heard opening (19.5 s here)
  and plays on meanwhile from the rest of the captured opening, which was a fixed 2 s. Opus comes in
  20 ms packets, and the player's render loop gets through about ten times real time with them. So
  21.5 s took about 3.3 s, the 2 s ran out, and the sink starved again and again. FLAC and MP3 packets
  are larger, which is why those joins held. The resampler is not the limit: about 400× real time on
  the host.
- **Fix.** The opening decoded ahead (`introCaptureMs`) is what will be heard plus half of it, at
  least 2 s. The rest then lasts while the decoder runs at three times real time. A 12 s overlap
  decodes 18 s (it was 14 s), which still fits the 8 MiB capture up to 96 kHz.

## Clock jump after a rate-changing join (2026-10-26)

Owner's report: no transition felt from Heartbreaker (Opus 48 kHz, played at 44.1 kHz since the
seam before it) into More Than a Feeling (FLAC 44.1 kHz). At the join the sink logged
`UnexpectedDiscontinuityException`: it expected the next buffer 27.2 s later than it came. Then the
position read 0 for a moment and jumped about 20 s, the sink drained and re-synced in the middle of
the track, and the processors were flushed.

- **Cause.** DefaultAudioSink times every byte given to it since its last flush at the current
  track's input rate (`inputFramesToDurationUs(getSubmittedFrames())`). With the resampler inside its
  chain, a reused AudioTrack saw its input rate change at the join, so the frames of the track before
  were counted at the wrong rate. The seam into Heartbreaker had the same fault and probably added
  to its stutter.
- **Fix.** The resampler moved in front of the sink (`SeamResamplingAudioSink`, under
  `PcmOnlyAudioSink`). The sink only ever sees the output's rate, with gapless encoder delay and
  padding scaled to it. A buffer the sink cannot take yet is offered again as the same conversion.
  The capture goes through the same sink, so its PCM is the player's at the join (tested).
- **Also.** A track whose stream ends before its overlap was settled (`CrossfadeAudioProcessor`
  `Decision.Join.early`) now brings the next track in at its first sound, rising on the overlap's
  curve from its decoded opening.

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

## Owner's answers (2026-10-22)

1. The single-player PCM mix, with today's fade as the fallback: yes.
2. Metadata switches at the end of the overlap: yes.
3. Normalisation moves into the PCM chain for everyone: yes.
4. iOS in the same round: yes.
