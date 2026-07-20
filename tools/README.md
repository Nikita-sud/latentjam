# tools

One-off scripts. Not part of the app, not shipped, not on any build path.

## convert_audio_encoder_fp16.py

Converts an FP32 waveform encoder to FP16 weights while retaining FP32 inputs and outputs. By
default its fixed STFT/mel front end remains FP32. Pass `--include-frontend` to reproduce the smaller
production 960-d export selected after the 72-real-clip regression check.

## migrate_legacy_playlists.py

Carries playlists over from the legacy GPL-3 LatentJam app into this one.

It reads only files the legacy app itself **wrote** — its Room database and
its own `music_graph_debug.dot` dump — and never its source. That matters:
the licence boundary is about code, and your playlists are your data.

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
