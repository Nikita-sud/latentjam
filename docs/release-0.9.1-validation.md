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
