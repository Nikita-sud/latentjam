<h1 align="center"><b>LatentJam</b></h1>

<p align="center">
    <b>Privacy-first Android music player with on-device ML recommendations.</b>
</p>

<p align="center">
    LatentJam is a local music player for Android, forked from <a href="https://github.com/OxygenCobalt/Auxio">Auxio</a>. It adds a smart shuffle mode that picks tracks via a CLAP audio encoder and a small predictor model — all running on-device. No cloud, no telemetry, no analytics.
</p>

## Features

- **Smart shuffle**: Cycle the shuffle button through OFF → ON → SMART. SMART picks the next track using your listening history (skips, listen-throughs, replays) and a CLAP audio embedding of every song in your library.
- **On-device inference**: ONNX Runtime with the QNN execution provider for Hexagon NPU offload on Snapdragon devices. CPU fallback on everything else. No data leaves the device.
- **Favorites**: A dedicated home tab and a per-track star toggle.
- **Standard local-music player**: MP3 / FLAC / OGG / Opus / M4A, Media3-powered background playback, embedded cover art, ReplayGain, gapless, Android Auto, widgets, search, playlists.

## Build

Requirements:
- JDK 21
- Android SDK with platform 36 + NDK 28+
- `ninja` (for the native audio decoder build): `brew install ninja` / `apt install ninja-build`

```bash
git clone https://github.com/Nikita-sud/latentjam.git
cd latentjam
git submodule update --init --recursive
./scripts/download-models.sh
./gradlew app:assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## ML models

The ONNX models are hosted on Hugging Face (not in this repo) because `clap_audio.onnx` is 116 MB, which exceeds GitHub's 100 MB per-file hard cap.

Run `./scripts/download-models.sh` to fetch them into `app/src/main/assets/ml/`. The script pulls from `huggingface.co/Nikita-sud/latentjam-models` by default — override with `LATENTJAM_HF_MODELS=your/repo ./scripts/download-models.sh` if you have your own copy.

Three models ship:
- `clap_audio.onnx` (116 MB) — audio encoder, derived from CLAP. Produces an embedding per track on first index.
- `predictor_state.onnx` (32 MB) — predictor state model.
- `predictor_scorer_n100.onnx` (5 MB) — top-100 scorer.

## License

LatentJam is licensed under the **GNU General Public License v3.0** (GPL-3.0-or-later) — see [LICENSE](LICENSE).

This project is a fork of [Auxio](https://github.com/OxygenCobalt/Auxio) by Alexander Capehart (OxygenCobalt). Auxio is also GPL-3.0; per the license, the original copyright notices and source headers are preserved throughout. See [FORK_NOTICE.md](FORK_NOTICE.md) for the full attribution.

The vendored native libraries in `app/src/main/cpp/third_party/` (libopus, libogg, libopusfile, fdk-aac, dr_libs, minimp3, minimp4, stb_vorbis) each retain their own upstream licenses.

ML models distributed via Hugging Face have their own model cards and licenses — see the model repo.
