# R128 normalization and independent tag verification

Implementation on `codex/opensource-reliability`, based on `b1141683` (the build with
shared Signalsmith EQ). No ORT/provider, Media3, projection backend or user-library
schema change is included.

## Integrated loudness

The production normalizer now measures the whole decoded programme through pinned
libebur128 1.2.6 instead of sampled 16 kHz mono RMS. Android uses MediaExtractor and
MediaCodec; iOS local-file playback uses AVAudioFile and now applies normalization
through its app-owned mixer. Both channels are retained. Results contain integrated
LUFS and maximum true peak in dBTP, persisted in a new `r128-v1` derived cache with
source identity. Old RMS rows are discarded, without touching library/history data.

The feature remains opt-in and lazy. A single background scan processes bounded
4,096-frame blocks; histogram mode bounds the native history (0.1 LU bins). Cancelled,
partial, invalid or timed-out results are never cached. Wall budget is 120 seconds,
programme cap six hours, and Android also detects five seconds without PCM progress.
The scope of this first integration is mono/stereo at 8–192 kHz. Unsupported layouts,
protected Music.app items and failed decodes retain their original volume.

Gain only attenuates: toward -14 LUFS, also reserving source headroom to -1 dBTP.
It ramps over 400 ms when discovered mid-track and combines with transport fades.
Quiet tracks are not boosted. This source true-peak margin does not turn the existing
post-EQ sample-peak limiter into a true-peak limiter. Boundary fades remain disabled
on iOS independently of the newly enabled normalization control.

Unmodified upstream bytes, hashes and MIT/BSD notices are included in the tree and
application resources. The augmented SBOM now has 215 components. libebur128 1.2.6
rewrites shared constants at construction, so the wrapper serializes all calls;
none run in a playback render callback.

## TagLib

The new host-only reader uses TagLib 2.3.2 at
`deadc2990767dfbda0701e0ab35fdeea653db08f`, with pinned utf8cpp at
`2d8e20b22dcb3e9b3c4f52103182ebda949c6089`. It is a test/CI dependency and adds
nothing to the application runtime. The existing writer, journal, verification and
recovery path are preserved.

35 synthetic before/after cases cover MP3, FLAC, Opus, Vorbis and MP4, including
Unicode, deletion, patch/rewrite paths, lyrics, custom fields and ID3v1 migration.
The first comparison exposed a difference in reader semantics: TagLib's MPEG
property map selects ID3v2 wholesale, whereas LatentJam migrates fields found only
in ID3v1. The oracle now independently reads both containers; no production writer
change was necessary. All 35 cases pass.

Run `python3 tools/taglib/verify.py`; see `tools/taglib/README.md` for pinned/offline
source options. CI executes it after the existing host tests.

## iOS restoration fix found during verification

The full playback test run reproduced an Objective-C `-10868` exception when a
prepared equalizer graph was reconfigured from stereo to mono after restoration.
Changing only one bus could trigger resource allocation while input/output formats
disagreed. The adapter now detaches the stopped Audio Unit, configures both buses,
then reconnects it. Connection errors return failure across the native boundary.
A regression also verifies that normalization and transport factors compose and
survive graph recreation.

## Verification

- All 2,759 host tests pass across the five modules.
- All 1,143 iOS tests pass in the changed playback/Compose modules (186 + 957),
  including the real file decoder and audio graph restoration. Other iOS module
  suites were not rerun for this integration.
- Three instrumentation tests pass on Samsung SM-S928B / Android 16: complete
  stereo decoding, cancellation/cleanup/reuse, and MP3/AAC/FLAC decoder paths.
- The 12-second stereo fixture measured -20.116 LUFS / -20.002 dBTP in 488 ms on
  the final run (694 ms on the earlier run). These are synthetic decoder tests,
  not app-wide, battery or low-end-device benchmarks.
- Native ASan/UBSan contracts pass (three audio checks and one existing map check).
  The meter's four-thread create/process/destroy test also passes ThreadSanitizer.
- Android optimized release, DEX gate, native inventory and 16 KiB alignment pass
  for all three shipping ABIs. Maximum DEX registers: 243, project gate: 255.
- Real Xcode simulator app build succeeds, linking Kotlin, Swift, CocoaPods and
  the native audio library. This is not physical-iPhone validation.
- ARM64 APK: 52,956,542 bytes, +67,173 bytes from the preceding combined build.
  SHA-256: `2ce37b9e0613e9302b747c0819abe309d1c17b86539fc9eb0d6af968572cd017`.

Machine reports and raw logs remain under `build/reports/` and `build/loudness-*`.
No claim of compatibility with all phones, new F-Droid reproducibility comparison,
or official EBU test-suite certification is made by these checks.
