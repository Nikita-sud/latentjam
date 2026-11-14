# Apple Lossless (ALAC) decoder

Apple's reference ALAC decoder, as released by Apple Inc. under the Apache License, Version 2.0 (see `LICENSE` and
the header of every file).

- Source: https://github.com/macosforge/alac, commit `c38887c5c5e64a4b31108733bd79ca9b2496d987` (2016-05-11),
  directory `codec/`.
- Only the decoder files are vendored. The encoder and the convert utility are left out.
- Android has no platform ALAC decoder, so without this LatentJam played Apple Lossless `.m4a` files silently
  (issue #34). `lj_alac_jni.cpp` wraps it, and the playback module turns ALAC samples into PCM as they are read.

## Changes from upstream

- `ALACDecoder.cpp`: a partial frame's sample count is checked against the configured frame length, in both the
  single-channel and the channel-pair element. Upstream wrote past the mix buffers when a stream claimed more samples
  than its magic cookie allowed. Both places are marked with `LatentJam:`.
- `EndianPortable.c`: any target whose compiler reports little-endian byte order is treated as little-endian. Upstream
  knew only x86 and Windows, and Apple hosts got the answer from Apple's headers, so on Android's ARM targets every
  big-endian field (frame length, sample rate) was read unswapped and no stream opened. Marked with `LatentJam:`.

No other file is changed.
