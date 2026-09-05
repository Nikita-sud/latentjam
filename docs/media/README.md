# README demo media

These are captures of the actual Android app, recorded on 5 September 2026 in a separate, newly
created emulator. No existing emulator data, personal tracks, private artwork, or real listening
history was imported. The demo contains sixteen invented tracks by four fictional artists, original
geometric cover art, quiet synthesized audio, and generated listening history and playlists.

| Asset | Details |
| --- | --- |
| `walkthrough.gif` | 29-second loop, 408 × 884, 12 fps, about 1.7 MiB |
| `walkthrough.mp4` | 42-second silent H.264 video, 720 × 1560, 30 fps, about 1.0 MiB |
| `for-you.png` | Discovery and recommendations |
| `player.png` | Fictional “Blue Hour” in the SMART player |
| `statistics.png` | Synthetic thirty-day listening summary and daily activity |
| `pages.png` | Visibility, ordering, and opening-page controls |

The four PNGs are unmodified 1080 × 2340 emulator screenshots. The GIF is sped up for a shorter
preview; the MP4 keeps the original interaction timing. Video encodes omit audio and source metadata.
The walkthrough opens the player, briefly plays the original synthesized track, browses albums,
changes the statistics period, selects a day, scrolls through habits, moves Statistics up, and
switches Tracks off and back on. Map remains disabled. Statistics is explicitly enabled for this demo.

## Recreate the demo data

Use Python 3 with Pillow, `ffmpeg` with `libmp3lame`, and `rsvg-convert`:

```sh
python3 tools/readme/generate_demo_library.py --output-dir /tmp/latentjam-readme-demo
```

This creates MP3s with embedded cover art, editable SVG covers, and a manifest outside the repository.
Install the current debug APK in a **separate empty emulator**, copy only the generated `Music`
directory to its music storage, and scan those files into MediaStore.

Query the demo emulator's complete music library with the projection
`_id:title:artist:album:duration` and selection `is_music != 0`, saving the result as
`/tmp/readme-media-query.txt`. Generate the matching app-file payloads:

```sh
python3 tools/readme/seed_demo_history.py \
  --metadata /tmp/latentjam-readme-demo/manifest.json \
  --media-query /tmp/readme-media-query.txt \
  --out /tmp/readme-seed --timezone UTC
```

The generator refuses libraries that do not exactly match the fictional title/artist allowlist.
It only creates local payload files. Copy its `files` and `shared_prefs` payloads into the dedicated
demo app while that app is stopped, then launch it. Do not apply the payloads to a real library.
The history uses the current time; `--now-ms` makes the data reproducible for a particular date.

For the captured presentation, use English, dark theme, 1080 × 2340 at 420 dpi, and the Android demo
status bar (09:41, full battery, hidden notifications). Wait for demo library indexing to finish.
Play “Blue Hour” from “Night Current”, pause it, enable SMART, and return to For You before recording.

## Export and review

Record the actual device screen to a temporary MP4. Encode a silent, fast-start H.264 copy at 720 px
wide for playback, and a 408 px, 12 fps looping GIF with a shared palette for the README. Keep original
recordings, generated audio, and emulator images outside the repository.

Validation for these assets included full GIF/MP4 decode checks, PNG verification, README link checks,
and visual inspection of each screenshot and a twelve-frame video contact sheet. All visible titles,
artists, and covers matched the fictional manifest; no permission prompts, errors, or loading screens
appeared in the selected stills. The demo generators passed syntax and data-generation checks.
