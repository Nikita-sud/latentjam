<h1 align="center"><b>LatentJam</b></h1>

<p align="center">
    <b>Privacy-first Android music player with local ML recommendations.</b>
</p>

<p align="center">
    LatentJam is a local music player for Android, forked from Auxio. It focuses on privacy and high-quality playback of local music (MP3, FLAC, Opus), with an on-device ML recommender that adapts to your listening habits without any cloud interaction or telemetry.
</p>

## Key Features

- **On-Device ML**: Learns your listening preferences entirely locally using TensorFlow Lite.
- **Privacy-First**: No cloud, no telemetry, no analytics. Ever.
- **High Quality**: Supports FLAC, Opus, and aggressive local caching.
- **Media3 Powered**: Robust background playback and notification controls.
- **Opt-in Network Features**: Metadata lookup via MusicBrainz and AcoustID is strictly opt-in.

## Required Features for v1

### Player Core
- Library scan: MP3 / FLAC / OGG / Opus / M4A
- Media3 playback with background service + notification controls
- Browse by artist / album / genre / year
- Playlists, search, sleep timer
- Bluetooth and wired headphone controls
- Equalizer or system audio effects integration

### Metadata
- Read existing ID3v2 / Vorbis Comments tags
- Display embedded cover art
- Opt-in MusicBrainz lookup for missing metadata
- Opt-in AcoustID fingerprinting for unlabeled tracks
- Cover Art Archive for missing covers
- Aggressive local caching

### ML Recommender (Upcoming)
- Pretrained audio encoder, ~5-15M params, TFLite + NNAPI, int8 quantized
- Background embedding generation: charging + idle + thermal-aware (WorkManager)
- User state: multi-timescale EMAs over listened tracks
- Reward-weighted updates: skip = negative, listen-through = positive, replay = strong positive
- Smart auto-play mode as toggle alongside regular shuffle

## License

LatentJam is Free Software: You can use, study, share, and improve it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This project is a fork of [Auxio](https://github.com/OxygenCobalt/Auxio).
