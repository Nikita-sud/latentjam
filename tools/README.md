# tools

Build helpers and research scripts. The scripts themselves are not shipped in the app.
`release/check-dex-registers.py` runs on release builds; `release/build-native-eq.py` builds the
shared DSP for iOS and JVM tests.

## release/check-dex-registers.py

Fails when a method in a release APK needs more than 255 Dalvik registers. R8 9.2.14 mis-encodes
such a method (an 8-bit register operand gets the low byte of a register above v255), and Android's
verifier then rejects the whole class at load time. The 0.8.1 candidate fe24e12c crashed at launch
on every Android version this way: App's theme content lambda had grown to 261 registers. Debug
builds do not run R8, so only the release DEX shows it.

`assembleRelease` depends on `:androidApp:checkReleaseDexRegisters`, which runs this script over the
split APKs with the build's R8 mapping and writes `androidApp/build/reports/dex-registers/release.txt`.
It fails the build on a method over the limit and only warns when python3 or the SDK's `dexdump`
is missing; F-Droid's build does not run it. By hand, on any APK:

```bash
tools/release/check-dex-registers.py \
  --mapping androidApp/build/outputs/mapping/release/mapping.txt \
  androidApp/build/outputs/apk/release/*.apk
```

It prints, per APK, the heaviest methods (`--top`, 3 by default) or every method over the limit,
each with its original name from the mapping. Exit status 0 is clean, 1 a method over the limit,
2 a missing tool or input. The heaviest method of 0.8.1 is a Compose resources static initializer
at 243 registers, a library's, which does not change with the app's code; App's theme content is
next. When App grows, move groups of its state into the remembered holders next to it in `App.kt`
rather than raising the limit.

## release/resolve-apk.py

Prints the current APK path for an ABI using AGP's artifact locator and output metadata:

```bash
python3 tools/release/resolve-apk.py --variant release --abi arm64-v8a
```

Device-targeted builds can write to `androidApp/build/intermediates/apk/release` while older APKs
remain in `outputs/apk/release`. Resolve the artifact before installing it; a successful Gradle
build does not make a hard-coded `outputs/apk` path current. Verify the installed APK hash against
the resolved file when validating a phone build. The script does not run ADB or install anything.

## release/build-native-eq.py

Builds the vendored C++ equalizer without downloading dependencies. Playback Gradle tasks invoke
it for iOS Arm64, iOS simulator Arm64 and the JVM test host. Android production uses the
`core/audio-dsp` NDK/CMake module. Host builds need a C++17 compiler and JDK JNI headers; Apple
builds need Xcode's `xcrun`. See `core/audio-dsp/README.md` for lifecycle, flags and validation.

## convert_audio_encoder_fp16.py

Converts an FP32 waveform encoder to FP16 weights while retaining FP32 inputs and outputs. By
default its fixed STFT/mel front end remains FP32. Pass `--include-frontend` to reproduce the smaller
production 960-d export selected after the 72-real-clip regression check.

## migrate_legacy_playlists.py

Carries playlists over from an earlier LatentJam build into this one.

It reads only files that build itself **wrote** — its Room database and
its own `music_graph_debug.dot` dump — never its source. Your playlists are
your data.

The chain is:

1. `user_music.db` → playlist names and the song UIDs they contain.
2. `music_graph_debug.dot` → song UID to title (the legacy app identifies
   songs by a content hash, not a path, so this dump is the only data-level
   bridge to something human-matchable).
3. The target device's MediaStore → title to the track id this app uses.

Entries whose song is no longer on the device are reported and skipped; they
are already dangling references in the source database.

```bash
# with the old phone and the target device both connected:
adb -s <phone> shell "run-as io.github.nikitasud.latentjam.debug cat databases/user_music.db" > legacy/user_music.db
adb -s <phone> shell "run-as io.github.nikitasud.latentjam.debug cat files/music_graph_debug.dot" > legacy/graph.dot
python3 migrate_legacy_playlists.py            # writes legacy/playlists.txt
cat legacy/playlists.txt | adb -s <target> shell "run-as io.github.nikitasud.latentjam.kmp sh -c 'cat > files/playlists.txt'"
```

Force-stop the app before writing, or it will overwrite the file on exit.
Paths at the top of the script are hard-coded; edit them for your machine.
