# 0.9.1 — release candidate

This patch follows the October 13 feedback on playlist migration, missing artwork and Android
integration, plus the Android audio-effects report in issue #16. Android and iOS use version 0.9.1, build 15. Publication is a separate step after
validation; this document describes the candidate, not an already published release.

## Changes

- M3U imports keep the selected file's name when the playlist has no nonblank `#PLAYLIST:` title.
  Only `.m3u` and `.m3u8` suffixes are removed. This works on Android and iOS; missing filename
  metadata does not prevent importing readable contents.
- Android artwork loading falls back to a track's embedded picture when the system artwork
  source cannot provide a usable cover. Explicit artwork edits and removals remain authoritative.
- Android can receive local audio opening requests and play the matching visible library track.
  An unavailable or unrecognised file reports a failure instead of silently playing a namesake.
- An optional Android notification setting replaces Repeat with Favorite while retaining
  SMART/shuffle and the transport controls. Existing notification controls remain the default.

- Android and iOS file-playback equalizer and bass boost now share a C++ Signalsmith DSP core.
  Android processes decoded audio inside Media3 instead of relying
  on device-specific audio effects. Ten bands, five presets and a separate bass shelf apply live;
  selecting a preset also turns the equalizer on. Curve changes are smoothed; boosts reserve at
  most 2 dB of fixed headroom and a 5 ms lookahead limiter handles actual peaks instead of turning
  the whole track down by 9–17 dB. Existing tone settings migrate where the device exposes the old curve.
- The equalizer screen now has a curve preview, labelled band controls, grouped presets and
  separate bass/normalization cards. The normalization card reports measurement progress,
  failure, no reduction needed, or the actual reduction in dB. Normalization still attenuates loud
  tracks; it does not amplify quiet recordings.
- Cancelling an Android cover-color read no longer crashes the app with `InterruptedIOException`.
- Android can preserve playlist, favorite and listening-history references after a file moves to
  a new MediaStore ID, provided its identity metadata was saved before the move and its
  MusicBrainz release-track ID matches unambiguously. Let the app finish reading the original
  files in the background before moving them. Matching also requires the same available volume
  and format, compatible duration and complete reads; duplicates and recording-only tags do not
  trigger automatic merges. Playback and playlist exports use the file's current address; the
  playing or paused track keeps its position when that address changes.
  This does not restore associations already lost before that evidence was saved, or provide
  general AcoustID recognition. Libraries without these tags retain their existing behavior.

## Deliberate scope

The 3,000-track Map limit is unchanged pending memory, runtime and layout-quality measurements.
Queue history, extra player styles, folder artwork and adding to an arbitrary playlist from a
notification are not part of this patch. A firmware-specific default-music-app list still needs
verification on Honor; declaring supported intents cannot guarantee that list's behaviour.

## Validation

The [final Android smoke check](release-0.9.1-final-smoke.md) records the later playback-position
fix, its regression tests and the final APK verification.

The [adversarial review](release-0.9.1-adversarial-review.md) records corrected defects and the
remaining limits of the original reports. The [issue #17 validation](issue-17-track-identity-validation.md)
records the conservative relocation checks, tested source revision and matching APK hashes.
The [earlier validation record](release-0.9.1-validation.md) covers previous candidates; its
device observations do not certify every subsequent APK. Each report identifies its tested revision.
Required checks include host tests, translation parity, iOS source compilation, an Android release
build and its DEX-register gate, plus emulator playback/open-file and notification checks.

## Adversarial review corrections

- A temporarily unavailable Android legacy equalizer no longer marks migration complete with
  flat bands. The next launch retries; later explicit curve edits take precedence. The shared
  supported range now preserves legacy curves through ±15 dB.
- Artwork cache versions distinguish track identities and full revisions. Interrupted provider
  reads preserve coroutine cancellation and use the normal fallback for unrelated I/O failures.
- Region discovery honors cancellation in both algorithms, budgets the temporary row copies,
  and skips centered vectors too small to normalize consistently.
- Android startup rules are placed in AGP's actual startup-profile source directory. Release
  DEX/native checks now also cover F-Droid and device-targeted builds; independent packaging,
  tag serialization and fuzz checks reject incomplete evidence.


## EQ response and peak metering

- Android and iOS tone controls now show the calculated frequency response of the actual filter cascade,
  including the separate bass shelf and static boost headroom. The processor and chart share one
  coefficient definition. The logarithmic chart adapts its dB range to combined boosts/cuts.
- Uses the configured PCM sample rate when known; before playback, the chart explicitly labels
  its 48 kHz preview. Peak attenuation is metered separately from the linear filter response.
- A recent peak-reduction indicator explains why extreme boosts may reduce the rest of a track.
  Audio processing publishes primitive readings per buffer; UI polling runs at 10 Hz only while
  the tone screen is collected, with a 400 ms peak hold and a 500 ms stale-reading timeout.
- File playback on iOS uses the same filters, bass shelf, limiter and response calculation through
  an in-process Audio Unit. Apple Music protected playback remains unavailable for EQ. Existing
  iOS curves are retained; the old bass macro is not added again as a second shelf.
- An offline gain-interaction experiment is available in `tools/research/eq_band_interaction_probe.py`.
  The candidate fits are not enabled for playback: centre-only correction worsens some inter-band
  errors, and the midpoint fit does not meet the desired 1 dB tolerance on extreme settings.
