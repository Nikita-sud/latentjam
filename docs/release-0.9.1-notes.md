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

- Android equalizer and bass boost now process decoded audio inside the player instead of relying
  on device-specific audio effects. Ten bands, five presets and a separate bass shelf apply live;
  selecting a preset also turns the equalizer on. Curve changes are smoothed; boosts reserve at
  most 2 dB of fixed headroom and a 5 ms lookahead limiter handles actual peaks instead of turning
  the whole track down by 9–17 dB. Existing tone settings migrate where the device exposes the old curve.
- The equalizer screen now has a curve preview, labelled band controls, grouped presets and
  separate bass/normalization cards. The normalization card reports measurement progress,
  failure, no reduction needed, or the actual reduction in dB. Normalization still attenuates loud
  tracks; it does not amplify quiet recordings.
- Cancelling an Android cover-color read no longer crashes the app with `InterruptedIOException`.

## Deliberate scope

The 3,000-track Map limit is unchanged pending memory, runtime and layout-quality measurements.
Queue history, extra player styles, folder artwork and adding to an arbitrary playlist from a
notification are not part of this patch. A firmware-specific default-music-app list still needs
verification on Honor; declaring supported intents cannot guarantee that list's behaviour.

## Validation

Validation results are recorded in `release-0.9.1-validation.md` after the candidate is built.
Required checks include host tests, translation parity, iOS source compilation, an Android release
build and its DEX-register gate, plus emulator playback/open-file and notification checks.
