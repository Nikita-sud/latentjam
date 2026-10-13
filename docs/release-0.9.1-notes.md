# 0.9.1 — release candidate

This patch follows the October 13 feedback on playlist migration, missing artwork and Android
integration. Android and iOS use version 0.9.1, build 15. Publication is a separate step after
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

## Deliberate scope

The 3,000-track Map limit is unchanged pending memory, runtime and layout-quality measurements.
Queue history, extra player styles, folder artwork and adding to an arbitrary playlist from a
notification are not part of this patch. A firmware-specific default-music-app list still needs
verification on Honor; declaring supported intents cannot guarantee that list's behaviour.

## Validation

Validation results are recorded in `release-0.9.1-validation.md` after the candidate is built.
Required checks include host tests, translation parity, iOS source compilation, an Android release
build and its DEX-register gate, plus emulator playback/open-file and notification checks.
