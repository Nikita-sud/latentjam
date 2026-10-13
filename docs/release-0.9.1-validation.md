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
