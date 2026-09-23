# Debugging and optimization — 22 September 2026

This pass covers the current uncommitted redesign, playback, browsing, local playlists,
document exchange and Android widgets. Runtime checks use only the existing fictional
16-track LJ demo emulator. No personal library, published release or connected phone was changed.

## Confirmed issues fixed

- Fence delayed SMART advances against newer playback commands and native item changes;
  propagate cancellation instead of applying a stale recommendation or fallback.
- Preserve pause intent when advancing on iOS, refresh repeat-one lock-screen progress, and
  pause/stop the AVAudioEngine hardware graph when its player is idle.
- Stop hidden/background player observation and visual animation. Reuse queue metadata
  between progress ticks and avoid repeated queue-index scans.
- Decode iOS lock-screen artwork off the main thread, bounded to 512 pixels with a 16-entry
  cache. Release cancelled Android media-controller connections.
- Coalesce duplicate widget refresh targets without losing broadcast completions; serialize
  cold queue restoration. Paused Deck elapsed time uses a static accessible value rather
  than a stopped Chronometer that can drift when the launcher reapplies views.
- Keep playlist disk and memory commits consistent when callers are cancelled. All mutation
  paths retain custom-cover references. Release unclaimed cover-picker results.
- Retain Android document payloads and IO across rotation without putting large text in saved
  state. Cancel cleanly after process loss, reject duplicate requests, and prevent stale delivery
  after dismissal. Move M3U parsing/matching/encoding off the UI thread.
- Disable Back handlers on outgoing/covered navigation screens, remove decorative input
  interception, and avoid restoring busy flags after their work has been cancelled.
- Cache collection artwork/rail inputs, use binary search for alphabet sections, and keep map
  point classifications cached while only the small song-color palette changes.
- Preserve the full-player route across rotation. Bound artwork by both available dimensions;
  short wide windows use artwork beside scrollable controls, while short narrow windows scroll
  the body. The header and queue remain reachable instead of being covered by oversized artwork.
- Reapply the actual rendered theme's system-bar contrast on resume and focus gain. Returning
  from dark system settings no longer leaves white status icons over the light player; listeners
  are removed on disposal and do not require a polling timer.

## Automated validation

Fresh `./gradlew testAndroidHostTest --rerun-tasks --offline`: **1,187 tests passed**,
146 suites, zero failures/errors. All 56 Gradle tasks executed rather than reusing test results.

| Module | Tests |
| --- | ---: |
| UI/application | 473 |
| SMART | 338 |
| Library | 183 |
| Playback | 98 |
| History | 95 |

New regression coverage includes stale/cancelled SMART advances, immutable continuation
snapshots, alphabet bounds/large rails, widget refresh coalescing and failure recovery,
document retention/cancellation, and playlist cancellation after durable writes across nine
mutation paths. Source and XML checks also pass. Optional external-fixture suites do not prove
real-file/model parity unless their external fixture is supplied.

Android debug and optimized release builds, both lint tasks, and shared iOS simulator compilation
passed. The complete Xcode simulator build also passed, including Swift and native linking.
Android lint reports zero errors and four existing warnings: target/compile SDK freshness and two
API-33-only manifest attributes. Existing build deprecations remain. Xcode notes that bundled ICU
was built for a newer simulator deployment target; this pass does not certify iOS 15 hardware.

## Runtime validation

- Updated the installed demo release in place; its queue, playlists and three widgets persisted.
- Paused Deck time stayed at 2:30 after several minutes away, including launcher reapplication.
- Backup export survived rotation while the Files picker was open. The resulting 5,377-byte
  version-4 backup was read successfully and merged back into the same demo data.
- Import cancellation/retry returned to a usable screen. Playlist M3U export contained five
  entries; importing it reported “Imported 5 of 5 tracks.” The temporary imported playlist was
  removed, leaving the original SMART-enabled playlist intact.
- At 200% system text size, library rows, search results, automatic playlist shelf, popup actions
  and all three widgets remained usable. Map actions were reachable by scrolling.
- The corrected full player stayed open through portrait/landscape round trips at normal and
  200% text size. Artwork no longer covered the controls; the short landscape controls scrolled
  to expose metadata, transport and next-up. Next while paused changed tracks without starting
  playback. The temporary system font change was restored to 1.0.
- Light-theme player text remained readable over the song-colored clouds. App-scoped logs
  showed no fatal exception, ANR, out-of-memory event, or failed widget operation in these checks.
- On the final installed build, light player → Home → dark system Settings → Home → widget →
  player preserved black status icons. Restored the app to Follow system and portrait orientation.

Final installed Android arm64 release SHA-256:
`ac3d6e1070aa102f6722ba3dbef7001e71e37fc22e638d1317ecf173cf9942c5`.

## Baseline performance observations

Release build with R8/resource shrinking, arm64 Android API 36 emulator, 1440 × 3120 at
560 dpi, default font scale. Values are process diagnostics, not physical-device benchmarks.
CPU is user + system time as a percentage of one CPU core; memory is proportional set size.

| Baseline sample | Duration | New app frames | Average CPU | PSS at end |
| --- | ---: | ---: | ---: | ---: |
| Paused library | 23.9 s | 0 | 0.13% | 134.2 MiB |
| Paused player | 33.8 s | 0 | 0.12% | 140.4 MiB |
| Playing player | 53.9 s | 3,236 | 17.0% | 147.2 MiB |
| Background audio | 42.0 s | 0 | 4.57% | 140.1 MiB |

The playing sample produced approximately 60 frames/second, with zero deadline-missed
frames reported in that sample. Transition/navigation sampling did report 32 janky frames
out of 668; it is not an all-interactions smoothness guarantee. After repeated navigation,
PSS settled from 158.6 MiB back to 141.0 MiB in subsequent samples. Memory diagnostics may
trigger collection; these observations are not a heap-retention or exhaustive leak test.

## Final release performance observations

Same emulator and default font scale, with no build running during measurement. Playback uses
the demo library; background audio has all three widgets on the launcher. CPU is a percentage
of one core, not a battery-use percentage. The counters exclude the screen transition before
each sample.

| Final sample | Duration | New app frames | Average CPU | PSS at end |
| --- | ---: | ---: | ---: | ---: |
| Paused library | 32.1 s | 0 | 0.12% | 136.2 MiB |
| Paused player | 40.6 s | 0 | 0.10% | 147.7 MiB |
| Playing player | 32.2 s | 1,933 | 17.02% | 153.7 MiB |
| Background audio | 35.6 s | 0 | 5.71% | 149.1 MiB |

The playing player produced 60.0 frames/second; Android reported zero janky frames in this
sample, with a 95th-percentile frame duration of 18 ms. Zero-frame samples have no meaningful
frame latency percentile. Both pause and backgrounding stopped app rendering. The earlier
paused-player attempt was excluded because the user confirmed interacting with playback during
that interval.

These short runs establish that idle rendering stops; they do not establish an overall speedup.
The track, warmed process state, library history and interaction sequence differ between runs.
Background CPU and memory were higher than the baseline sample, so no percentage improvement
is claimed. Large physical libraries and long-running playback still need device profiling.
The final arm64 release is 94.0 MiB; R8/resource shrinking and per-ABI packaging remain enabled.
Most package weight is the offline model/runtime/data bundle; this pass did not remove offline
capabilities to reduce the download size.

Final installed-process logs contained zero fatal exceptions, ANRs, out-of-memory errors or
widget-operation failures. Emulator graphics/audio capability warnings remain and are distinct
from app crashes. Playback was paused again after measurement.

Raw build/profiling logs are local under `/private/tmp/latentjam-performance-20260922`
and `/tmp/latentjam-full-debug-*.log` and are not shipped with the app.

## Scope limits

Emulator checks cannot establish physical-device battery life, thermal behavior, haptic feel,
Bluetooth/car routing, or performance on every phone. Android runtime checks and Apple builds
are reported separately. No claim of a bug-free app or universal device validation is made.
