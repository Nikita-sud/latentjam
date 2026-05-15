<p align="center"><img src=".github/icon.svg" width="150" alt="LatentJam icon"></p>
<h1 align="center"><b>LatentJam</b></h1>
<h4 align="center">A privacy-first Android music player that recommends what to play next — entirely on-device.</h4>
<p align="center">
    <a href="https://www.gnu.org/licenses/gpl-3.0">
        <img alt="License" src="https://img.shields.io/badge/license-GPL%20v3-2B6DBE.svg?style=flat">
    </a>
    <img alt="Minimum SDK Version" src="https://img.shields.io/badge/API-24%2B-1450A8?style=flat">
    <img alt="Built with Kotlin" src="https://img.shields.io/badge/built%20with-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white">
    <a href="https://huggingface.co/AILOVER3000/latentjam-models">
        <img alt="Models on Hugging Face" src="https://img.shields.io/badge/%F0%9F%A4%97%20models-Hugging%20Face-FFD21E?style=flat">
    </a>
    <img alt="Status" src="https://img.shields.io/badge/status-experimental-orange?style=flat">
</p>
<h4 align="center"><a href="/FORK_NOTICE.md">Fork Notice</a> | <a href="/ARCHITECTURE_NOTES.md">Architecture</a> | <a href="/.github/CONTRIBUTING.md">Contributing</a></h4>

## About

LatentJam is a local music player for Android that adds a **smart shuffle mode** on top of [Auxio](https://github.com/OxygenCobalt/Auxio). Instead of picking the next track at random, smart shuffle scores every song in your library against your listening history using an on-device ML pipeline — a CLAP audio encoder and a small predictor model. No cloud calls, no telemetry, no analytics. Your taste stays on your device.

The codebase is Auxio's local-music engine (Media3 ExoPlayer, the same library scanner, the same Material UI) with one new feature wired in: a `SMART` position on the shuffle button.

> ⚠️ **Experimental.** LatentJam is a personal fork for tinkering with on-device music recommendation. Smart mode works; the rest of the recommender (true on-device fine-tuning, BPM-aware reranking, retrain triggers) is a work in progress. If you want a stable, polished local-music player today, use [Auxio](https://github.com/OxygenCobalt/Auxio) directly.

## Screenshots

<p align="center">
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot0.png" width=250>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot1.png" width=250>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot2.png" width=250>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot3.png" width=250>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot4.png" width=250>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot5.png" width=250>
</p>

*(Screenshots inherited from upstream Auxio — UI is largely unchanged. Smart-mode screenshots coming once the v0.1.0 release is cut.)*

## What's different from Auxio

| | Auxio | LatentJam |
|---|---|---|
| Shuffle modes | OFF / ON | OFF / ON / **SMART** |
| Recommendation engine | – | CLAP audio encoder + predictor model (ONNX Runtime) |
| NPU acceleration | – | QNN execution provider on Snapdragon (Hexagon HTP) |
| Listening history tracking | – | Skips, listen-throughs, replays (Room, local) |
| Favorites tab | – | ✓ (per-track star toggle) |
| Metadata edit dialog | – | ✓ |
| Cloud / telemetry | None | None |

Everything else (library scanning via `:musikr`, Media3 playback, Material UI, widgets, Android Auto, ReplayGain, embedded covers, search, playlists, gapless, edge-to-edge, etc.) is inherited from Auxio as-is.

## Features

### From LatentJam
- **Smart shuffle**: Cycle the shuffle button through `OFF → ON → SMART`. `SMART` picks the next track using your listening history (skips, listen-throughs, replays) and a CLAP audio embedding of every song in your library.
- **On-device ML inference**: ONNX Runtime with the QNN execution provider for Hexagon NPU offload on Snapdragon devices. CPU fallback on everything else. No data leaves the device.
- **Custom native audio decoders**: Hand-written C++ decoders (libopus / fdk-aac / minimp3 / minimp4 / stb_vorbis) replace `MediaExtractor`'s VBR-mp3 seek penalty for the ML pipeline's 15 s audio chunks.
- **Favorites** tab with a per-track star toggle.
- **Metadata edit dialog** for per-track tag overrides.

### From Auxio (inherited)
- Playback based on [Media3 ExoPlayer](https://developer.android.com/guide/topics/media/exoplayer)
- Snappy UI derived from the latest Material Design guidelines
- Opinionated UX that prioritizes ease of use over edge cases
- Support for disc numbers, multiple artists, release types, precise/original dates, sort tags, and more
- Advanced artist system that unifies artists and album artists
- SD Card-aware folder management
- Reliable playlisting functionality
- Playback state persistence
- Android Auto support
- Automatic gapless playback
- Full ReplayGain support (on MP3, FLAC, OGG, OPUS, and MP4 files)
- External equalizer support (e.g. Wavelet)
- Edge-to-edge
- Embedded covers support
- Search functionality
- Headset autoplay
- Stylish widgets that automatically adapt to their size
- Completely private and offline
- No rounded album covers (if you want them)

## Permissions

- Storage (`READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE`) — read and play your music files
- Services (`FOREGROUND_SERVICE`, `WAKE_LOCK`) — keep music playing in the background
- Notifications (`POST_NOTIFICATIONS`) — show the ongoing playback notification

LatentJam **never** requests `INTERNET` for the recommender. The download script in this repo only fetches model files at *build time* on the developer's machine.

## Building

LatentJam inherits Auxio's patched Media3 and TagLib vendored builds, and adds ONNX Runtime + a CLAP audio encoder model. Heads up before you start:

1. `cmake` and `ninja` must be installed.
2. The project uses git submodules — use `--recurse-submodules` when cloning.
3. The C++ build runs shell scripts, so building on Windows isn't supported. Use macOS or Linux.
4. The ML models live on [Hugging Face](https://huggingface.co/AILOVER3000/latentjam-models), not in this repo — `clap_audio.onnx` is 116 MB and exceeds GitHub's per-file cap. The build script fetches them at build time.

### Prerequisites

- **JDK 21** (e.g. `brew install openjdk@21` on macOS)
- **Android SDK** with platform 36 + **NDK 28.2.13676358** (install via Android Studio's SDK Manager). If Gradle can't locate the NDK, export `ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/28.2.13676358`.
- **`ninja`** for the native CMake build: `brew install ninja` / `apt install ninja-build`.

### Build the debug APK

```bash
git clone --recurse-submodules https://github.com/Nikita-sud/latentjam.git
cd latentjam
./scripts/download-models.sh
./gradlew app:assembleDebug
```

Debug APK lands at `app/build/outputs/apk/debug/app-debug.apk` (~230 MB with models baked into assets).

### Install on a connected device

1. **Enable Developer Options on your phone**: Settings → About phone → tap *Build number* 7 times.
2. **Enable USB debugging**: Settings → Developer options → *USB debugging*.
3. **Verify the device is detected**: `adb devices`
4. **Install**: `./gradlew installDebug`

LatentJam should now appear in your app drawer with the icon shown above.

### Load music onto a test device (optional)

```bash
adb push ~/Music/ /sdcard/Music/
```

After installing, open LatentJam → grant storage permission → wait for the indexing notification → the library tab populates. Smart mode becomes available once the encoder has computed embeddings for at least a few dozen tracks in the background (charging + idle, via WorkManager).

## ML models

Three ONNX models ship via Hugging Face:

| File | Size | Role |
|---|---|---|
| `clap_audio.onnx` | 116 MB | Audio encoder derived from CLAP. Produces a 512-d embedding per track. Runs once per track on first index, then cached in Room. |
| `predictor_state.onnx` | 32 MB | Transformer-style state encoder over your listening history. |
| `predictor_scorer_n100.onnx` | 5 MB | Top-100 candidate scorer fed by retrieval + the state encoder. |

`./scripts/download-models.sh` pulls them from [huggingface.co/AILOVER3000/latentjam-models](https://huggingface.co/AILOVER3000/latentjam-models). Override with `LATENTJAM_HF_MODELS=your/repo ./scripts/download-models.sh` if you fork the model repo too.

## Contributing

LatentJam accepts contributions as long as they follow the [Contribution Guidelines](/.github/CONTRIBUTING.md). The repo tracks upstream Auxio at `oxygencobalt/Auxio`; the `:musikr` module is deliberately kept under its original `org.oxycblt.musikr` namespace so upstream merges stay clean — don't rename it.

Architecture notes for the codebase live in [ARCHITECTURE_NOTES.md](/ARCHITECTURE_NOTES.md).

## Credits

- **[Auxio](https://github.com/OxygenCobalt/Auxio)** by [Alexander Capehart](https://github.com/OxygenCobalt) — the entire local-music engine LatentJam is built on. If you like the music-playing parts of this app, that's all Auxio.
- **[CLAP](https://github.com/LAION-AI/CLAP)** (LAION-AI) — the audio encoder backbone, exported to ONNX for on-device use.
- **[ONNX Runtime](https://onnxruntime.ai/)** with the [Qualcomm QNN execution provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html) — inference on Hexagon NPU.
- Vendored native libraries: **libopus**, **libogg**, **libopusfile**, **fdk-aac**, **dr_libs**, **minimp3**, **minimp4**, **stb_vorbis** — each under their own upstream license.

## License

[![GNU GPLv3](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

LatentJam is Free Software: you can use, study, share, and improve it. Specifically you can redistribute and/or modify it under the terms of the [GNU General Public License v3](https://www.gnu.org/licenses/gpl.html) or any later version, as published by the Free Software Foundation.

This project is a fork of [Auxio](https://github.com/OxygenCobalt/Auxio), which is also GPL-3.0. Per the license, original copyright notices and source headers are preserved throughout. See [FORK_NOTICE.md](/FORK_NOTICE.md) for the full attribution.
