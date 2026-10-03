<p align="center">
  <img src="branding/logo.svg" width="120" alt="LatentJam logo">
</p>

<h1 align="center">LatentJam</h1>

<p align="center">
  <b>A music player that knows what your library <i>sounds</i> like.</b><br>
  Free and offline, for the songs already on your phone: no account, no catalogue, no network.
</p>

<p align="center">
  <a href="https://github.com/Nikita-sud/latentjam/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/Nikita-sud/latentjam?style=flat-square&color=2E7D32"></a>
  <a href="https://f-droid.org/packages/io.github.nikitasud.latentjam.kmp/"><img alt="F-Droid" src="https://img.shields.io/f-droid/v/io.github.nikitasud.latentjam.kmp?style=flat-square&color=1976D2&logo=fdroid&logoColor=white"></a>
  <img alt="Platforms" src="https://img.shields.io/badge/platform-Android%207%2B%20%7C%20iOS%2015.1%2B-1450A8?style=flat-square">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-8E24AA.svg?style=flat-square"></a>
  <img alt="Status: beta" src="https://img.shields.io/badge/status-beta-F9A825?style=flat-square">
</p>

<p align="center">
  <b><a href="https://f-droid.org/packages/io.github.nikitasud.latentjam.kmp/">Get it on F-Droid</a></b> ·
  <b><a href="https://github.com/Nikita-sud/latentjam/releases/latest">Android APK</a></b> ·
  <b><a href="https://github.com/Nikita-sud/latentjam/releases/latest">iOS IPA (sideload)</a></b>
</p>

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#what-it-does">Features</a> ·
  <a href="#how-the-recommender-works">How it works</a> ·
  <a href="#building">Build</a> ·
  <a href="#licence">Licence</a>
</p>

---

Shuffle on a local player is a dice roll. Pick a song in LatentJam and **SMART**, its shuffle, queues
up others from your own library that sound like they belong next to it. **For You** brings back the
records you own and forgot. Edit your tags, follow synced lyrics, see your library as a map. The
models that listen to your music are small enough to run on the phone, so the catalogue *is* your
library and nothing about your listening ever leaves it.

<p align="center">
  <img src="docs/media/walkthrough.gif" width="270" alt="LatentJam: opening the player and following synchronized lyrics">
</p>

<p align="center"><a href="docs/media/player-demo.mp4">Player & lyrics · 25 seconds</a> · <a href="docs/media/walkthrough.mp4">Explore the app · 46 seconds</a></p>

<p align="center">
  <img src="docs/media/for-you.png" width="170" alt="For You page">&nbsp;&nbsp;
  <img src="docs/media/player.png" width="170" alt="Now playing">&nbsp;&nbsp;
  <img src="docs/media/lyrics.png" width="170" alt="Synchronized lyrics with tap-to-seek">&nbsp;&nbsp;
  <img src="docs/media/playlists.png" width="170" alt="Favourite, recent and personal playlists">
</p>

<p align="center"><sub>Screens from the Android app (captured on version 0.5) with a fictional demo library: invented artists, original artwork and timed demo lyrics. More screens, both themes and the library map are in <a href="docs/media/README.md">docs/media</a>.</sub></p>

## Install

> **Beta, pre-1.0.** Screens and settings can still change between releases. Tag editing writes to
> your real files, so back up the music you care about before a big edit. SMART gets good once a
> chunk of your library has been analysed in the background; the first launch starts that on its own.

- **[F-Droid](https://f-droid.org/packages/io.github.nikitasud.latentjam.kmp/)** (Android, updates
  itself) builds each release from this source on its own schedule, usually within a few days, so it
  can be a version behind the Releases page. Its download is larger because it uses the standard
  ONNX Runtime. It is signed with F-Droid's key, so it can't replace the APKs here or be replaced by
  them: to switch, export your data (Settings → Local backup), uninstall, install, then restore.
- **Android APK** (newest first) — from the [Releases page](https://github.com/Nikita-sud/latentjam/releases/latest),
  download `LatentJam-vX.Y.Z-arm64.apk` (about 57 MB; right for virtually every phone from the last
  decade; the `armv7` APK, about 65 MB, is for old 32-bit devices) and open it on the phone. Allow
  installing unknown apps and tap through Play Protect's sideload warning. Requires **Android 7.0+**.
  A new APK installs over the old one and keeps your library, history and settings.
- **iOS IPA** — the IPA (about 63 MB) is **unsigned**: sideload it with AltStore, Sideloadly or
  similar, which re-sign it with your Apple ID (with a free Apple ID that lasts 7 days and has to be
  renewed). Requires **iOS 15.1+**, arm64. Music comes from files you import through the Files app
  and from songs downloaded to your Music library.

**Updating:** the app never goes online, so it can't tell you about new versions. F-Droid updates it
for you; otherwise watch this repository for releases (Watch → Custom → Releases).

**Privacy:** no account, no analytics, no ads, no crash reporting, no server. The Android app doesn't
even request the internet permission. Nothing leaves the device unless you export a backup yourself.
Details in [PRIVACY.md](PRIVACY.md).

## What it does

- 🎧 **SMART shuffle** — turn it on and pick any song. The queue that follows stays close to that
  song's sound and mood, moves gently from track to track, and keeps a niche corner of your library
  from drifting into generic hits. Repeat artists are spaced out unless the next match by them is
  much better, and switching SMART on mid-queue uses only what you just played.
- ✨ **For You** — rediscovery, not discovery: you already own everything here. One confident pick
  up top (something you stopped mid-way, a favourite gone quiet, a record never heard), then rows for
  favourites untouched for months, songs SMART found that you played to the end, albums you never
  opened, music for this time of day, and a **sonic journey** across your library from one track to a
  distant one. Offers you ignore cool down instead of repeating.
- ✏️ **Edit your tags** — one song, a whole album, an artist or any selection, in MP3, FLAC, M4A,
  Ogg and Opus: title, artists, album, genre, year, track and disc numbers, lyrics and cover. The
  audio is never touched, and a save cut off by a crash or a full disk ends as the original file or
  the edited one, never a mix. On iOS, songs you imported can be edited; Music-library songs are
  read-only.
- 🏷️ **Every tag the file carries** — every genre of a multi-genre track, the credited artists and
  the original release year, read straight from the file. Albums sort by release year (a 2011
  remaster of a 1973 album reads 1973), and names garbled by a wrong charset ("GrÃ¼ÃŸe") show as
  "Grüße" again without retagging.
- 🎤 **Lyrics** — embedded in MP3, FLAC, Ogg/Opus and M4A files, or from a `.lrc` file beside the
  song. Timed lyrics follow the song, and a tap on a line seeks there.
- 🔎 **Search that ranks like you expect** — titles first, then artist, album, genre, lyrics and
  finally meaning, across languages; `eminem mockingbird` finds the song in either word order. A year or
  decade (`80s`, `1985`) narrows the results, an artist in another alphabet still matches (фонк finds
  *phonk*), and nicknames and band members resolve through a local artist index, all on the phone.
- 🗺️ **A map of your library** — songs that sound alike sit together, so your genres appear as
  islands you can tap through, and any track can show where it lives.
- 🎚️ **A real player underneath**
  - The player lies over your library: pull it down and the page you're going back to follows your
    finger, live. The cover flips to the track's facts, holds for actions and swipes between tracks;
    the seek bar scrubs finely and shows the lyric line under your thumb.
  - An editable queue that survives restarts; playlists with M3U import/export and drag-to-reorder;
    album, artist, genre and folder browsing.
  - Fades on pause, optional crossfade and volume normalization, a system equalizer, a sleep timer.
  - A duplicate finder that recognises the same recording saved twice even when the tags differ,
    and tells you which copy to keep.
  - Local backup and restore.
- ⚙️ **Make it yours** — in **Settings → Pages**, show, hide and reorder pages, pick the one to open
  on, and add the Map or the **Statistics** dashboard (listening time, habits, first listens, top
  tracks and artists; also under Settings → Statistics).
- 📱 **On Android** — three home-screen widgets that wear the playing track's colour, a Quick
  Settings tile, Android Auto, and playback that resumes the exact queue after a restart.
- 🌍 **18 languages** — including Russian, Romanian, Arabic and Hebrew (right-to-left) and CJK, with
  correct plural forms.

iOS has the same SMART, For You, search, lyrics and tag editing. Not there yet: crossfade and volume
normalization. Android only: widgets, the Quick Settings tile and Android Auto.

## How the recommender works

Every track is described by its sound, its tags and what is known about its artist. SMART picks the
next song by asking a small model which candidate fits what you have just played. All of it is
computed on the device:

| Signal | Dimensions | Where it comes from |
|---|---:|---|
| **Audio embedding** | 960 | MobileNetV4-Conv-M encoder over the raw waveform |
| **Metadata embedding** | 384 | A 5 MB multilingual text encoder distilled from MiniLM, over trusted `genre; artist; original year; language` tags |
| **Artist knowledge** | 384 | What an offline teacher wrote about 350k MusicBrainz and Wikidata artists, stored as 22 bytes per artist; a small adapter guesses it for artists outside the pack |

**Retrieval.** Separate anchor-audio, session-audio, seed-text and artist-knowledge rankings are
round-robined into one candidate pool, so there is no hand-tuned weight between embedding spaces.

**Scoring.** A 960-d GRU state encoder reads your last four plays, completion and skip signals, and
30- and 365-day taste centroids, and feeds a frozen scorer over 100 candidates. The scorer sees each
candidate's audio embedding next to its metadata vector, with the session's metadata centroid on the
state side. It was trained with text dropout, so a track without usable tags is scored on audio
alone.

**The queue.** A scoring chain balances the model's vote against local coherence, keeps gravity
toward the track you chose, and uses pool-relative semantic scores so niche corners of a library stay
intact. Metadata rules then nudge repeated artists down instead of banning them, suppress duplicate
titles and damp the dense cinematic/anime cluster, and the finished queue is ordered into gentle
transitions.

**Messy tags.** Track *titles* are excluded from the embedding, so a filename like `Hard Techno Mix`
can't inject a genre claim. The language word comes from the file's tag, then from the language the
artist pack says they sing in, then from the script of the title, never from its words. A track with
no year carries its artist's decade.

**First launch.** The audio index builds in small persisted batches spread over the whole library;
until candidates are ready, SMART *abstains* rather than quietly falling back to random. A listener
with no history gets an explicitly trained seed-only state, not a zero vector. Listening history is
used as runtime context, **never trained on and never uploaded**.

**Tuning.** The scorer's weights are frozen and *objective*: SMART ranks by what the music is, not by
who is listening. The constants around it (chain weights, quotas, spacing) are chosen by replaying
recorded model outputs and simulated listeners through the full chain, not by hand.

Five ONNX graphs ship in-tree: the audio and metadata encoders run once while tracks are indexed; the
state encoder and the scorer run while a queue is built; a small semantic head turns audio embeddings
into the genre-family scores the map and the mixes use. The model bundle (graphs, vocabulary, the
artist index and the knowledge pack) is **≈48 MiB on both Android and iOS**; the complete arm64 APK
is about 57 MB. There is no precomputed per-track catalogue: an imported track takes exactly the same
local path as everything else.

## One engine, two platforms

The intelligence lives in shared Kotlin; each platform supplies only the native seams.

| | Android | iOS |
|---|:---:|:---:|
| SMART recommendation engine | ✓ | ✓ |
| On-device ONNX inference | ✓ ORT (JNI) | ✓ ORT (native C API, Swift host) |
| Audio decode for indexing | MediaCodec | AVAudioFile / AVAudioConverter |
| Library source | MediaStore | Files import · Music library |
| Media3 / AVPlayer playback | ✓ | ✓ |
| Tag editing (MP3 · FLAC · M4A · Ogg · Opus) | ✓ | ✓ (imported files; Music library is read-only) |
| System equalizer | ✓ | ✓ (AVAudioEngine graph, imported files) |
| Crossfade · volume normalization | ✓ | — not yet |
| Widgets · QS tile · Android Auto | ✓ | n/a |

## Building

```bash
# Android (debug)
./gradlew :androidApp:assembleDebug

# Android (release: R8-minified, per-ABI splits under androidApp/build/outputs/apk/release/)
./gradlew :androidApp:assembleRelease

# iOS (from the Xcode workspace, after one pod install)
cd iosApp && pod install
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator build
```

Requires **JDK 17+** and the Android SDK; the first build pulls a large Kotlin/Native toolchain.
Xcode's Gradle phase looks for the JDK on `PATH` plus `/opt/homebrew/opt/openjdk@21/bin`. iOS brings
ONNX Runtime in through CocoaPods (`onnxruntime-c`) and reaches it from Kotlin through the Swift host.

The published arm64 APK swaps in a reduced ONNX Runtime (identical results, smaller download); see
[tools/runtime/README.md](tools/runtime/README.md). Without it the build uses the stock runtime.

## Layout

```
core/smart      similarity engine, SMART chain, ONNX runtimes, tokenizer, MusicBrainz index
core/library    library scanning, tag reading + crash-safe writing (FLAC/Ogg/MP4/ID3),
                lyrics (embedded + .lrc), playlists, catalogue grouping
core/playback   Media3 / AVPlayer playback, queue, equalizer
core/history    listening events, aggregates, For You impressions
composeApp      all UI, shared verbatim by both platforms
androidApp      packaging shell — contains no Kotlin
iosApp          Xcode project + thin Swift host
build-logic/    the build's own Gradle plugin: the Kotlin/Native name check
docs/           design notes and validation records
tools/          one-off scripts, research harnesses and the opt-in reduced ONNX Runtime build
```

`androidApp` holds no Kotlin on purpose: AGP 9 ships no Compose Multiplatform *application* plugin and
`com.android.application` can't combine with the KMP plugin, so the UI lives in `composeApp` as a
library and `androidApp` exists only to package it.

## Testing

```bash
./gradlew testAndroidHostTest
```

Host tests first run `checkNativeIdentifiers`, which catches test names Kotlin/Native rejects but the
JVM accepts (its own tests: `./gradlew :build-logic:test`).

A few checks cover what unit tests usually miss. Each skips unless its fixture is present, so the
default run stays fast and offline:

- **SMART parity** replays the reference implementation's own recorded model outputs through this port
  and asserts the resulting queues match *exactly*. Point `SMART_PARITY_FIXTURE` at a directory from
  `tools/export_parity_fixture.py`.
- **Chain and For You simulations** replay recorded outputs and synthetic listeners through the full
  chain; they are how the recommender's constants are chosen and defended.
- **Real files** run the tag reader, the lyrics reader and the tag writer (every codec, through the
  crash-safe write path) against actual music, because synthetic fixtures only prove a codec matches
  one reading of the spec. Point `TAG_REAL_FILES` at a folder of mixed files, `ID3_REAL_FILES` at
  `.mp3`s, `REAL_AUDIO_FILE` at a single track.

The research behind the For You design is in [docs/for-you-ux.md](docs/for-you-ux.md).

Issues and pull requests are welcome; please run `./gradlew testAndroidHostTest` before opening a PR.

## Credits

Built on the shoulders of:

- **[ONNX Runtime](https://onnxruntime.ai/)** — on-device inference on both platforms
- **[MobileNetV4](https://arxiv.org/abs/2404.10518)** and **[MiniLM](https://arxiv.org/abs/2002.10957)** — the encoder architectures behind the audio and text embeddings
- **[MusicBrainz](https://musicbrainz.org/)** and **[Wikidata](https://www.wikidata.org/)** — the CC0 artist data behind smart search and the knowledge pack
- **[DeepSeek](https://www.deepseek.com/)** — the offline teacher whose artist descriptions the knowledge pack distills
- **[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)**, **[Koin](https://insert-koin.io/)**, **[Coil](https://coil-kt.github.io/coil/)**, **[Media3](https://developer.android.com/media/media3)**

## Licence

**Apache-2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Bundled models under `androidApp/src/main/assets/ml/` are covered by permissive licences documented
in [LICENSE-MODEL.txt](androidApp/src/main/assets/ml/LICENSE-MODEL.txt). The architecture selection,
benchmarks, rejected candidates and next compression target are in
[docs/model-selection.md](docs/model-selection.md).

<p align="center"><sub>Everything runs on your device. Your taste stays there.</sub></p>
