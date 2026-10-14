# 0.9.1 — validation record

Validated on 2026-10-13 in `codex/release-0.9.1`, based on `origin/main` at `68496c56`.
This is a locally built, debug-signed release candidate (0.9.1, build 15). Nothing was published.

## Automated checks

All **2,693 Android host tests** passed across 277 suites; **zero failures, errors or skipped tests**.

| Module | Tests passed |
|---|---:|
| `composeApp` | 1,058 |
| `core/history` | 140 |
| `core/library` | 799 |
| `core/playback` | 172 |
| `core/smart` | 524 |

Results are in each module's `build/test-results/testAndroidHostTest/` directory. Core checks
passed in `/private/tmp/latentjam-091-core-tests.log`; the final affected build passed in
`/private/tmp/latentjam-091-final-build.log` (1 minute 42 seconds).

- `StringResourceParityTest`: **11/11 passed**, including a rerun in the final build. It checks
  Compose resource files, keys, placeholders, untranslated English, apostrophes, plurals and
  locale aliases across English plus 21 locale directories. Android legacy locale aliases are
  also checked. A separate XML check confirmed both sets of `notification_controls.xml` have
  all 22 bundles, matching keys and no English values left in translated bundles.
- `:composeApp:compileKotlinIosSimulatorArm64`: passed, including shared and iOS source changes.
- `:androidApp:assembleRelease` and `:androidApp:checkReleaseDexRegisters`: passed. All three
  APKs (arm64-v8a, armeabi-v7a, x86_64) contain 29,191 methods in one DEX. Maximum register count:
  **243**, below the gate's limit of **255**. Report:
  `androidApp/build/reports/dex-registers/release.txt`.
- `:core:library:connectedAndroidDeviceTest`: **5/5 passed**, zero failures/errors/skips.
  These exercise actual embedded MP3 artwork extraction, rotated and mirrored JPEGs,
  dimension limits after rotation, and empty/undecodable images. Log:
  `/private/tmp/latentjam-091-artwork-tests.log`; XML results:
  `core/library/build/outputs/androidTest-results/connected/androidMain/`.

Reproduction tasks, from the worktree with the repository's JDK/Android SDK setup:

```sh
./gradlew :core:history:testAndroidHostTest :core:library:testAndroidHostTest :core:smart:testAndroidHostTest :core:playback:testAndroidHostTest
./gradlew :core:library:connectedAndroidDeviceTest
./gradlew :composeApp:testAndroidHostTest :composeApp:compileKotlinIosSimulatorArm64 :androidApp:assembleRelease :androidApp:checkReleaseDexRegisters
```

## Runtime checks

Manual ADB checks used arm64 release candidate APKs on the `LJ-big36` emulator (`emulator-5554`),
Android 16 / API 36.1, launched in read-only AVD mode. The Repeat-to-Favorite setting and SystemUI
button checks used the first consolidated release APK. The final APK was then installed in place
before the persistence, open-file and Activity-recreation checks.

| Scenario | Observed result |
|---|---|
| `MAIN` + `APP_MUSIC` intent resolution | Resolved to `MainActivity`, with `isDefault=true`. |
| Cold `ACTION_VIEW` for `content://media/external/audio/media/22` | Played **Река**. |
| Unknown row `99999999` while paused | Kept the same song, paused position **1,624 ms** and **21-entry queue**; showed a localized unavailable-file snackbar. |
| Warm `ACTION_VIEW` for row `23` | Opened **Дождь дорога река**. |
| Consumed intent across Activity recreation | Changed app locale to Russian, then English using `cmd locale`; **Дождь дорога река** remained paused at **871 ms** with the same **21-entry queue**, without replay or resume. Restored the system locale afterward. |
| Default notification controls | Repeat present before enabling the new preference. |
| Enable Appearance → Favorites in notification | Replaced Repeat with **Add to favorites**, retaining SMART. |
| Tap the real SystemUI favorite action | **Add to favorites** changed to **Remove from favorites**. |
| Reinstall the final APK in place and relaunch | The preference and favorite state persisted; **Blue — Zane Rossi** still offered **Remove from favorites**. |

## Remaining verification

The Honor X8 and the reporter's problematic audio files were unavailable, so these checks do
not establish that the original device-specific artwork issue is fixed. Physical Android
hardware and iOS runtime behavior were not tested. The Honor firmware's default-music-app list
still needs a device check. Notification relocalization during a running session was not tested.


## October 14 addendum: issue #16 audio effects

The initial implementation below is commit `a8acc806`. The subsequent gain correction at the end
of this document supersedes its headroom policy, throughput measurement and affected test counts.

Branch: `codex/map-performance-0.9.1`. The full arm64 0.9.1/build 15 release candidate was
installed in place on a Samsung Galaxy S24 Ultra (SM-S928B), Android 16, preserving app data.
The installed package is not debuggable. No GitHub release was published.

### Implementation and tradeoffs

The playback service now uses an app-owned Media3 PCM processor for EQ and bass boost. It uses
[RBJ peaking/low-shelf biquads](https://www.w3.org/TR/audio-eq-cookbook/), independent channel
histories, a 20 ms crossfade between stable filter banks, response-based headroom and linked-channel
peak protection. The processor stays in the chain while disabled so a toggle works without a seek.
The disabled/flat path preserves PCM16 samples exactly. Vendor effects are queried only once for
best-effort migration of the old curve; their indices are not reused for the portable preset list.

Compressed passthrough and hardware offload are disabled to keep local playback in the PCM chain.
This trades some potential battery savings for reliable processing. High-resolution/float input is
converted to PCM16 by Media3 before EQ; this is not a bit-perfect high-resolution output mode.
Boosted curves may reduce overall level to reserve headroom. The iOS AVAudioEngine path remains
native, with preset selection now enabling the effect; iOS runtime output was not measured here.

### Automated verification

- `:composeApp:testAndroidHostTest`: **1,062 passed**, no failures/errors/skips, including translation
  parity, normalization status/cancellation and the interrupted artwork-read regression.
- `:core:playback:testAndroidHostTest`: **185 passed**, no failures/errors/skips. New checks cover
  frequency response, channel isolation, all presets, live edits, bypass, empty Media3 drain buffers,
  mono/stereo/multichannel stability from 8 to 192 kHz, and rejection of passthrough/offload.
- `:composeApp:compileKotlinIosSimulatorArm64`: passed.
- `:androidApp:assembleRelease` and `:androidApp:checkReleaseDexRegisters`: passed for arm64-v8a;
  29,214 methods in one DEX, maximum 243 registers (limit 255).
- `EqualizerPlaybackDeviceTest`: **2/2 passed** on the phone. A generated stereo WAV is decoded
  through the production renderer/sink with a capture processor immediately after EQ. The output
  is muted during measurement. Relative to the disabled response, the bass preset changes
  40 Hz versus 1 kHz by **7.06 dB**, treble changes 8 kHz versus 1 kHz by **5.62 dB**, and maximum
  bass boost changes 40 Hz versus 1 kHz by **8.74 dB**. This verifies digital output before
  AudioTrack, not a microphone measurement of the speaker/headphones.
- Processing 10 seconds of 48 kHz stereo with all ten bands and bass active took **524 ms elapsed**
  after warmup in the device test. This is a throughput check, not a battery measurement.

Reproduction commands (repository JDK/SDK environment required):

```sh
./gradlew :composeApp:testAndroidHostTest :core:playback:testAndroidHostTest :androidApp:assembleRelease -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
./gradlew :composeApp:compileKotlinIosSimulatorArm64 :androidApp:checkReleaseDexRegisters -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
./gradlew :core:playback:connectedAndroidDeviceTest
```

Device tests use their own package/preferences and do not change the listener's saved EQ settings.
The test-only package was removed after verification.

### Manual verification on the installed release

- Selecting Bass while EQ was off enabled it and updated the curve and bands.
- Dragging a band changed its value and cleared the selected preset. Horizontal scrolling reached
  all ten bands, including 16 kHz; the bass slider changed independently.
- After force-stopping/relaunching, the manual 31 Hz value (-1.5 dB), remaining curve, 44% bass
  setting and normalization preference survived.
- The normalization screen reported **3.4 dB** reduction for the selected library track. During actual
  FLAC playback, Android AudioFlinger reported **0.677612** on both channels (-3.4 dB); switching
  normalization off returned both to **1.000000**. The active track reported zero underruns.
- Inspected the top and bottom of the screen with the phone's enlarged text settings. Controls,
  labels and normalization result fit. Final state: playback paused, original media volume restored,
  EQ enabled with the Bass preset, additional bass boost cleared, normalization off as before testing.
- No playback error or fatal exception appeared in the final release process during these checks.

The reporter's **Moto e22 / Android 12** was unavailable. Bluetooth/USB route changes and long-term
battery impact were not physically tested. The new path removes dependence on firmware EQ effects,
but these results do not establish that every device/output combination works.


## October 14 follow-up: bass loudness correction

The user reported that turning bass to 100% mainly made playback quieter. The previous static
headroom policy attenuated a flat curve plus bass by 9.26 dB, or the Bass preset plus bass by
17.53 dB. Relative frequency-response tests did not catch this loss of absolute level.

### Change

- Static boost headroom is now at most **2 dB**, with no attenuation for flat/bypass or cut-only
  curves. Bands above the usable input frequency range do not reduce the output level.
- A linked-channel sample-peak limiter delays audio by **5 ms**, smooths attack over that interval,
  holds peaks for 20 ms and releases over 100 ms. A monotonic deque keeps processing amortized O(1)
  per frame. Smoothing cannot overlook the delayed sample's peak: its attenuation is held throughout
  the smoothing window. The tone-active sample ceiling is **-0.5 dBFS**; bypass uses unity.
- End-of-stream drains the delayed frames without adding leading silence or losing the tail.
  Flush clears the delay/filter histories. Bypass still preserves every PCM16 sample exactly.
- Removed the 257-point frequency-response scan from audio-thread setting updates and iterator
  allocation from the per-sample filter loop.

Background: [lookahead, release and channel linking](https://www.fabfilter.com/help/pro-l/using/advancedsettings)
explain the tradeoffs. This implementation is a sample-peak limiter, not an oversampled
[true-peak limiter](https://www.fabfilter.com/help/pro-l/using/truepeaklimiting). The -0.5 dB ceiling
reduces reconstruction overshoots but does not guarantee their absence for every signal.

### Music comparison

Used the actual old and new Kotlin DSP on twelve local library excerpts, 30–42 seconds into each
track, decoded to 48 kHz stereo PCM16, with no volume normalization or loudness matching. The set
includes rock, pop, hip-hop and electronic music; it is a small chosen sample, not a library-wide
or blind listening study. Output float samples were measured with FFmpeg `ebur128=peak=true`.
Per-excerpt results and decoded-input hashes are in `eq-gain-validation-2026-10-14.csv`.

| Setting | Previous median loudness change vs bypass | Corrected median | Corrected range |
|---|---:|---:|---:|
| Flat + bass 100% | -6.8 LU | **-0.65 LU** | -1.2 to +2.1 LU |
| Bass preset + bass 100% | -9.65 LU | **+2.3 LU** | -0.6 to +4.3 LU |

All rendered samples were finite, each excerpt kept its full frame count, the maximum sample peak
was 0.944061 (-0.5 dBFS), and maximum measured true peak was **-0.2 dBFS** in this set. Strong
boosts can still activate limiting and alter dynamics; these figures do not prove transparent
sound on all music. No files were uploaded for this comparison.

### Final verification

- `core/playback`: **194 host tests passed**; `composeApp`: **1,062 passed**, no failures/errors/skips.
  Added checks for absolute bass/midrange level, changing ceilings, smooth transient anticipation,
  stereo linking, steady bass waveform preservation, short streams, exact EOF draining and chunk
  independence. Existing frequency-response, live-setting and sample-rate coverage still passes.
- Device suite: **2/2 passed** on the same Galaxy S24 Ultra / Android 16. Production Media3 PCM
  measurement found bass 100% at **+6.74 dB absolute at 40 Hz**, with 1 kHz at **-2.00 dB**.
  Bass preset + bass 100% measured **+13.94 dB at 40 Hz**, with 1 kHz at **-1.86 dB**, on the
  non-overloading test signal. Every mode emitted all **48,000 frames** of the source.
- Ten seconds of stereo with all bands and bass active took **352 ms elapsed** after warmup.
  This remains a throughput measurement, not a battery benchmark.
- iOS simulator source compilation, arm64 Android release build and DEX gate passed;
  29,217 methods, maximum 243 registers. The limiter changes affect Android playback.
- The full updated 0.9.1/build 15 release APK was installed in place on the phone and launched;
  the temporary instrumentation package was removed. Existing EQ preferences were retained.

Moto e22/Android 12, Bluetooth/USB route switching, battery usage and subjective headphone listening
remain unverified. The permanent PCM16/offload tradeoff from the first implementation is unchanged.


## October 14 follow-up: frequency response and limiter meter

### Implementation

The Android DSP and frequency-response chart now share `EqualizerFilterDesign`, including the
existing Q=1.4 peaking filters, 100 Hz bass shelf and at-most-2 dB static headroom. This refactor
preserves the filter mathematics and presets. The chart samples 121 logarithmically spaced
frequencies from 20 Hz to the lower of 20 kHz and 0.49 times the configured sample rate. The
response includes static headroom; signal-dependent peak limiting is shown separately.

The UI obtains recent minimum limiter gain from PCM buffers. Meter publication uses primitive
fields without per-sample allocations, UI dispatch or locks. A collector snapshots readings at
10 Hz only while the tone screen is present; a 400 ms hold exposes short peaks, and readings
return to zero after 500 ms without input. The known sample rate remains available while paused.
Before the first PCM buffer, the chart identifies its 48 kHz reference as a preview. The meter
measures gain reduction in our sample-peak limiter, not LUFS or reconstructed true peaks.

The iOS native AVAudioUnitEQ does not expose this processing model/meter. Its controls remain
available without an invented matching response curve. This change does not establish Android/iOS
DSP parity or remove the protected Music.app playback limitation.

### Offline band-interaction experiment

Reproduce with standard-library Python:

```sh
python3 tools/research/eq_band_interaction_probe.py --random-curves 100 --output /tmp/eq-band-interaction.csv
```

The experiment uses the shipped RBJ Q=1.4 formula with static headroom, bass shelf and limiter
excluded. It evaluates 119 curves at each of 44.1 and 48 kHz: five app presets, constant boosts/cut,
a zigzag, ten isolated bands and 100 uniformly sampled random curves with seed 20261014. It
compares current gains, an iterative fit to ten band centres, and a fit to centres plus nine
logarithmic midpoints. Filter gains in the fit are bounded to ±24 dB. Results are in
`eq-band-interaction-2026-10-14.csv`; errors are deviations from requested tonal gain, not measured
music quality. Reported errors are rounded to six decimal places.

| Design | Median maximum error at band centres | Median maximum error at midpoints | Worst midpoint error |
|---|---:|---:|---:|
| Current cascade | 3.1833 dB | 1.9857 dB | 5.0966 dB |
| Centre fit | <0.000001 dB | 2.0898 dB | 5.9671 dB |
| Centre + midpoint fit | 0.7801 dB | 1.7764 dB | 4.9002 dB |

The centre fit hits the controls but can worsen the curve between them. Fitting midpoints improves
some aggregate errors but still fails the desired ±1 dB bound on the sampled extreme settings.
Neither candidate is enabled in the release. These numerical results are not a listening study,
a CPU/battery benchmark or an implementation of the complete published ACGE design. The paper
also changes band-filter design/bandwidth; gain fitting alone does not reproduce its guarantees.
A subsequent prototype should compare those filter shapes before any preset migration.

Reference: Välimäki and Liski, [Accurate Cascade Graphic Equalizer](https://acris.aalto.fi/ws/portalfiles/portal/27113913/ELEC_valimaki_et_al_Accurate_cascade_graphic.pdf),
IEEE Signal Processing Letters, 2017. No third-party DSP code was added to the application.


### Verification and installed artifact

- `:core:playback:testAndroidHostTest`: **202/202 passed**, no failures/errors/skips, including
  analytical response vs actual quiet PCM at 44.1/48 kHz, shelf/headroom, log sampling, bypass,
  unsupported bands, peak hold/timeout and production PCM metering.
- `:composeApp:testAndroidHostTest`: **1,062/1,062 passed**, including translation parity for the
  five new labels in all 22 resource bundles and legacy locale aliases.
- `:composeApp:compileKotlinIosSimulatorArm64`: passed; no iOS runtime/audio parity claim.
- Arm64 release assembly and DEX gate passed: **29,247 methods**, maximum **243 registers**.
- `:core:playback:connectedAndroidDeviceTest`: **3/3 passed** on SM-S928B / Android 16.
  The new production Media3 test decodes a strong 40 Hz tone with bass boost and observes the
  controller's UI flow reporting **4.886363 dB** peak reduction at **48 kHz**, with output muted.
  Existing tests preserve all 48,000 frames and the previous absolute bass/midrange results.
  DSP throughput was **396 ms elapsed for 10 seconds** of 48 kHz stereo with all bands and bass
  after warmup; this is not a battery measurement.
- After the user unlocked the phone and opened the EQ, inspected the actual updated screen:
  the card/preset/vertical-fader design from `a8acc806` is present, with the new curve, 2 dB
  level reserve and the user's actual **44.1 kHz** stream. The screenshot is local at
  `/private/tmp/lj-eq-response-verified-ui.png`. The lower card was not visually inspected
  because the screen subsequently locked; its data flow is covered by the device test above.

The initial install incorrectly selected an old APK left in `outputs/apk/release`, visibly
reverting the design. This was corrected by following AGP's current artifact locator:
`androidApp/build/intermediates/apk_ide_redirect_file/release/createReleaseApkListingFileRedirect/redirect.txt`.
The correct APK was in `androidApp/build/intermediates/apk/release/androidApp-arm64-v8a-release.apk`.
Before reinstalling, verified the APK's Compose resource payload contains both the `a8acc806`
panel labels and the new response/meter labels. Its SHA-256 and the installed `base.apk` both equal:

```text
66f6e2d663cfc214e6b90d859ef4c2d81107fe9484734a9d211984825a5a3330
```

`tools/release/resolve-apk.py` now resolves the current artifact path from AGP metadata, so
subsequent installs do not assume the output directory. Full 0.9.1/build 15 was installed with
`adb install -r`, preserving app data. No release was published. Phone media volume and saved
EQ settings were not changed by the automated tests, which use a separate test package.


## Shared Signalsmith C++ EQ — October 14 follow-up

This phase supersedes the earlier Android-only/iOS native-EQ scope above. Both file-playback
backends now use `core/audio-dsp`: pinned Signalsmith DSP 1.7.0 cookbook biquads (MIT), the same
bass shelf, at-most-2 dB boost headroom, 20 ms filter-bank transition and linked lookahead limiter.
The response chart also calls the shared native coefficient design. The previous Kotlin DSP is
retained as a numerical test reference, not the production audio path.

Android passes complete direct PCM16 buffers through JNI. The iOS adapter is an in-process
AUAudioUnit in `AVAudioPlayerNode -> shared EQ -> mainMixer`; its audio render block uses C/C++,
preallocated buffers and the lock-free settings mailbox. Resource reset is performed with the
engine stopped, including seek, file changes and media-services graph reconstruction. iOS keeps
legacy stored curves; a previously applied native bass macro is not double-applied as the new
independent shelf. A shared MIT notice is packaged in both platforms' Compose resources.

### Checks

- Playback Android host suite: **205/205 passed**. Numerical native/reference checks cover
  8, 44.1, 48, 96 and 192 kHz, mono/stereo/6 channels, bypass, presets, alternating maximum
  boost/cut with bass, and live updates. Maximum permitted Float32 error is 0.00002.
- Playback iOS simulator suite: **176/176 passed**, including native/reference comparisons,
  an actual AVAudioEngine Audio Unit render in eight 44.1/48 kHz mono/stereo cases, delayed
  input end, saved controls/migration, file preparation and simulated media-services graph reconstruction.
- Compose Android host suite: **1,062/1,062 passed**. iOS simulator app compilation passed.
- Full iOS Arm64 release framework linkage passed in a separate build (6 min 41 sec). The
  earlier combined build lost its Gradle daemon during linkage; running it separately succeeded.
- Android DSP release AAR built successfully for arm64-v8a, armeabi-v7a, x86 and x86_64.
- Native AddressSanitizer + UndefinedBehaviorSanitizer and separate ThreadSanitizer passed:
  chunked PCM16/EOS is bit/frame exact in bypass, Float32 bypass preserves bits after its delay,
  stereo gain remains linked, processing/settings publication performs zero tracked heap
  allocations, and concurrent publication of 20,000 settings updates loses no final bypass.
- Reproducible CMake/CTest core and Apple Audio Unit tests: **2/2 passed**. Twelve actual Apple
  offline Audio Unit renders (44.1/48 kHz, 1/2/6 channels, bypass/boosted) matched the C core
  exactly, including the last delayed samples. Direct engine output isolates the DSP: passing
  mono through Apple's main mixer can instead apply its -3 dB pan law.
- Phone SM-S928B / Android 16: **3/3 production Media3/device tests passed**. Quiet test signals
  retain all 48,000 frames; separate bass boost gives +6.7367 dB at 40 Hz with about -2.0 dB at
  1 kHz, stacked bass band+shelf gives +13.9366 dB at 40 Hz. A strong bass signal reports
  **4.886363 dB** actual peak reduction through the production controller flow. Output is muted
  inside the separate instrumentation player; app library and EQ preferences are not edited.

### Device throughput

The same settings (ten +6 dB bands, bass 100%) and approximately ten seconds of 48 kHz stereo
were measured after warmup, three runs per implementation:

| Processor | Elapsed runs, ms | Median, ms |
|---|---|---:|
| Previous Kotlin reference | 381.304531, 382.004323, 412.650104 | 382.004323 |
| C++ block processing, JNI Float32 test entry | 78.900260, 78.915104, 110.420573 | 78.915104 |

The median ratio is **4.8407×** on this phone. C++ processes 480,256 frames in 512-frame blocks;
Kotlin processes 480,000 per-frame calls. Input regeneration is included and the runs are not
counterbalanced; the Kotlin reference runs in the instrumentation build without release R8
optimization. This is a practical DSP throughput check, not an end-to-end player benchmark,
battery measurement or a performance guarantee on all devices. Production Android uses the
separate direct PCM16 entry point, covered by Media3 tests.

### Installed Android artifact

Arm64 release assembly, lint and DEX gate passed: **29,241 methods**, at most **243 registers**.
Resolved the actual APK via `tools/release/resolve-apk.py`, verified `liblj_equalizer.so`, packaged
Signalsmith notice, 16 KiB alignment of the native library LOAD segments, and both the modern UI and response/meter resource labels. Installed the
full 0.9.1/build 15 with `adb install -r`; installed `base.apk` and local APK SHA-256 match:

```text
61219272dbd63cc1cf9b9c18b89a72a091ba5db3c40b98aaafcfa9164fe39a7a
```

The installed release opens the modern card/preset/vertical-fader EQ with a native-calculated
curve. A library track was played in the installed release with EQ enabled; MediaSession
reported PLAYING, progressing position and no error. It was then paused. The captured original
media volume was restored and verified at 8/15. No new crash-buffer entries occurred during
this release check. The modern response/fader card was visually inspected at
`/private/tmp/lj-eq-native-final.png`; a floating video partially covered the upper card, so this
is not a complete visual inspection of every screen section. Publication remains a separate step.

### Limits of parity

Identical decoded Float32 input and sample rate have the same DSP response. Android still
converts high-resolution/float PCM to PCM16 in DefaultAudioSink; iOS processes decoded Float32
before route resampling. Decoder, quantization, mixer, system effects, Bluetooth codecs and
headphones can change final output. This migration does not promise bit-identical speaker audio,
less battery consumption, improved perceived loudness at extreme boosts, or an oversampled
true-peak limiter. Protected Music.app/DRM playback remains outside the app's graph and exposes
EQ as unavailable. Physical iPhone listening/output-route checks have not been performed.

Build/reproduction details: `core/audio-dsp/README.md`.
