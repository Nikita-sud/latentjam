<p align="center">
  <img src="branding/logo.svg" width="120" alt="LatentJam logo">
</p>

<h1 align="center">LatentJam</h1>

<p align="center">
  <b>A local music player that understands what your library <i>sounds</i> like.</b><br>
  On-device neural recommendation for the music you already own — no account, no catalogue, no network.
</p>

<p align="center">
  <a href="https://github.com/Nikita-sud/latentjam/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/Nikita-sud/latentjam?style=flat-square&color=2E7D32"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-8E24AA.svg?style=flat-square"></a>
  <img alt="Platforms" src="https://img.shields.io/badge/platform-Android%20%7C%20iOS-1450A8?style=flat-square">
  <img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin%20Multiplatform-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white">
  <img alt="On-device" src="https://img.shields.io/badge/inference-100%25%20on--device-00897B?style=flat-square">
  <img alt="Status" src="https://img.shields.io/badge/status-experimental-E65100?style=flat-square">
</p>

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#why-it-exists">Why</a> ·
  <a href="#what-it-does">Features</a> ·
  <a href="#how-the-recommender-works">How it works</a> ·
  <a href="#building">Build</a> ·
  <a href="docs/model-selection.md">Model notes</a> ·
  <a href="#licence">Licence</a>
</p>

---

Shuffle on a local player is a dice roll. Streaming services solved sequencing — but only for their
own catalogue, and only by shipping your listening to a server. **LatentJam does it for the music
already on your phone, and it never leaves.** Every track is embedded from its audio *and* its tags
by compact ONNX models running on the device itself, so shuffle can follow a thread and a *For You*
page can resurface the records you own and forgot.

One engine. Two platforms. Nothing in the cloud.

<p align="center">
  <img src="docs/media/walkthrough.gif" width="270" alt="Walkthrough: the player, albums, the statistics dashboard and page settings">
</p>

<p align="center">
  <img src="docs/media/for-you.png" width="170" alt="For You page">&nbsp;&nbsp;
  <img src="docs/media/player.png" width="170" alt="Now playing">&nbsp;&nbsp;
  <img src="docs/media/statistics.png" width="170" alt="Statistics dashboard">&nbsp;&nbsp;
  <img src="docs/media/pages.png" width="170" alt="Settings → Pages">
</p>

<p align="center"><sub>Captured on a demo library of fictional artists, original cover art and synthesized audio — how it was made is in <a href="docs/media/README.md">docs/media</a>.</sub></p>

## Install

Grab the latest build from the [Releases page](https://github.com/Nikita-sud/latentjam/releases/latest).

- **Android** — download the `arm64` APK (right for virtually every phone from the last decade; an
  `armv7` build exists for old 32-bit devices) and open it on the phone. You will need to allow
  installing unknown apps and tap through Play Protect's sideload warning. Requires **Android 7.0+**.
  A new version upgrades the previous install in place.
- **iOS** — the IPA is **unsigned**: sideload it with AltStore, Sideloadly or similar, which re-sign it
  with your Apple ID. Built for **iOS 15+**, arm64.

These are experimental builds for sideloading, not store releases. SMART gets good once a chunk of
your library has been analysed in the background — the first launch starts that work on its own.

## Why it exists

Your library is full of music a good DJ would sequence beautifully and that random shuffle butchers.
The obvious fix — a recommendation model — normally means a catalogue you don't own and a server that
watches what you play.

LatentJam takes the opposite bet: the model is small enough to run on the phone, so the catalogue
*is* your library and the "server" is your pocket. There is no account to make and no listening to
upload, because there is nowhere for it to go.

## What it does

🎧 &nbsp;**SMART shuffle** — not "nearest neighbour" on repeat. Picking a track plans a *walk*: a
scoring chain balances the model's vote against local coherence, keeps gravity toward the track you
actually chose, and applies pool-relative semantic scores so niche corners of a library stay intact
instead of collapsing into whatever the model finds generically popular. Metadata rules then space
out artists, suppress duplicate titles, and damp the dense cinematic/anime cluster that otherwise
leaks into everything. Entering SMART mid-queue keeps only the recent plays as context, not the
whole queue.

✨ &nbsp;**For You** — rediscovery, not discovery; you already own everything here. A hero card offers
one confident play — something you were interrupted mid-way through, a favourite gone quiet, a record
never heard — and the rows surface what browsing can't: proven favourites untouched for 90+ days,
tracks SMART found that you then played all the way through, albums you own and never opened. The
page also learns *when*: a row for this time of day, the phase you are in when a few days lean one
way, one wildcard, and a **sonic journey** — a mix that travels across the library from one track to
a distant one. Offers you ignored cool down for a few days instead of repeating, and when several
dormant favourites share a playlist, the *playlist* is offered — the unit in which music was loved is
not always the track.

🏷️ &nbsp;**Every tag the file carries** — the system scanner keeps one genre per track; the files keep
them all. LatentJam reads every genre of a multi-genre track, the credited artists and the original
release year straight from FLAC, Opus and mp3 tags, and feeds them to browsing and to SMART alike.

🗺️ &nbsp;**A map of your library** — the embedding space made visible and navigable: clusters are
your genres as the model hears them, and every track's menu can answer *"where does this live in my
library?"*. The Map page is off by default; enable it in **Settings → Pages**.

🔎 &nbsp;**Search that ranks like you expect** — title matches first, then artist and album, then genre,
then meaning, with the matched text highlighted in every result. Folding is script- and
spelling-aware (фонк finds *phonk*), and a local CC0 MusicBrainz index resolves artist aliases,
transliterations and band-member names entirely on device, so a query and a tag that spell an artist
differently still match.

🎚️ &nbsp;**A real player underneath** — Media3 playback with a proper media session, an editable
queue that survives restarts, playlists with M3U import/export and hand-drag ordering, album/artist/
genre/folder browsing behind an A-Z rail, optional crossfade and volume normalization measured from
real playback, embedded lyrics from mp3, FLAC and Opus (timed lyrics included), a tag-blind duplicate
finder built on the SMART index, never-played and rediscover auto playlists, a system equalizer, ID3
tag editing, local backup and restore, and a settings screen that says honestly what the recommender
knows about your library.

⚙️ &nbsp;**Your pages, your order** — **Settings → Pages** lets you show or hide each browsing page,
move pages up or down, choose an enabled page to open on launch, or restore the defaults. At least
one page stays available. Layouts survive restarts and are included in local backups. For You and
Map calculations run only when their page is open, and reuse unchanged results on return.

📊 &nbsp;**Your listening, visible** — **Settings → Statistics** shows listening time and changes
from the previous period, daily activity, listening habits, first listens, library coverage,
SMART usage, and ranked tracks and artists. Explore seven days, thirty days, or all saved history.
Statistics can also be enabled and reordered as a main page in **Settings → Pages**; it is off by
default. Everything uses the same private local history SMART reads, with calculations off the
main thread and refreshed only while the dashboard is open.

📱 &nbsp;**At home on the home screen** — three widget styles that wear the playing track's colour
the way the player does, a Quick Settings tile, an Android Auto browse tree for the car, and
playback resumption that restores the exact queue, its source and its order after a restart.

🌍 &nbsp;**17 languages** — including Russian, Romanian, Arabic (RTL) and CJK, with correct plural
forms.

## How the recommender works

Two signals per track, both computed on the device:

| Signal | Dimensions | Where it comes from |
|---|---:|---|
| **Audio embedding** | 960 | MobileNetV4-Conv-M encoder over the raw waveform |
| **Metadata embedding** | 384 | Int8 MiniLM over trusted `genre; artist; year` tags |

Retrieval round-robins separate anchor-audio, session-audio and seed-text rankings into a single
candidate pool, so there is no hand-tuned numeric weight between the embedding spaces. A 960-d GRU
state encoder — reading your last four plays, completion/skip signals, and 30- and 365-day taste
centroids — feeds a frozen scorer over 100 candidates, and a 253 KB learned MiniLM residual
conditions those logits when trusted text exists. Missing text falls back to an exact audio-only
path. Track *titles* are deliberately excluded from the embedding, so a filename like
`Hard Techno Mix` can't inject a genre claim.

Five ONNX graphs ship in-tree: the audio and metadata encoders run once while tracks are indexed;
the state encoder, acoustic scorer and text residual run while a queue is built. The full
model + vocabulary bundle is **≈68 MiB on both Android and iOS**, plus a 15 MiB MusicBrainz alias pack. There is no precomputed per-track
catalogue — an imported track gets exactly the same fully-local path as everything else.

On first launch the audio index builds progressively in small persisted batches; until candidates are
ready, playback *abstains* rather than quietly falling back to random. A listener with no history gets
an explicitly-trained seed-only state, not a zero vector. As private listening accumulates, SMART uses
it as runtime context — **without training on the phone or uploading it** — and both platforms persist
that history locally across launches.

The scorer's weights are frozen and *objective*: SMART ranks by what the music is, not by who is
listening. The constants around it — chain weights, quotas, spacing — are chosen by replaying
recorded model outputs and simulated listeners through the full chain, not by hand.

## One engine, two platforms

The intelligence lives in shared Kotlin; each platform supplies only the native seams.

| | Android | iOS |
|---|:---:|:---:|
| SMART recommendation engine | ✓ | ✓ |
| On-device ONNX inference | ✓ ORT (JNI) | ✓ ORT (native C API, Swift host) |
| Audio decode for indexing | MediaCodec | AVAudioFile / AVAudioConverter |
| Library source | MediaStore | Files import · Music library |
| Media3 / AVPlayer playback | ✓ | ✓ |
| Index persistence across launches | ✓ | ✓ |
| Tag facts from the files (genres, artists, year, lyrics) | ✓ | ✓ |
| Settings → Pages · Statistics dashboard · local backup | ✓ | ✓ |
| System equalizer | ✓ | ✓ (AVAudioEngine graph, imported files) |
| Crossfade · volume normalization | ✓ | — not yet |
| Widgets · QS tile · Android Auto | ✓ | n/a |
| ID3 tag editing | ✓ | — not yet |

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

Requires **JDK 17+** and the Android SDK; the first build pulls a large Kotlin/Native toolchain. iOS
brings ONNX Runtime in through CocoaPods (`onnxruntime-c`) and reaches it from Kotlin through the
Swift host.

## Layout

```
core/smart      similarity engine, SMART chain, ONNX runtimes, tokenizer, MusicBrainz index
core/library    library scanning, tag reading (FLAC/Ogg/ID3), playlists, catalog grouping, ID3 writing
core/playback   Media3 / AVPlayer playback, queue, equalizer
core/history    listening events, aggregates, For You impressions
composeApp      all UI, shared verbatim by both platforms
androidApp      packaging shell — contains no Kotlin
iosApp          Xcode project + thin Swift host
docs/           design notes and validation records
tools/          one-off scripts and research harnesses, not on any build path
```

`androidApp` holds no Kotlin on purpose: AGP 9 ships no Compose Multiplatform *application* plugin and
`com.android.application` can't combine with the KMP plugin, so the UI lives in `composeApp` as a
library and `androidApp` exists only to package it.

## Testing

```bash
./gradlew testAndroidHostTest
```

Beyond the unit suites, a few checks cover what unit tests usually miss. Each skips unless its
fixture is present, so the default run stays fast and offline:

- **SMART parity** replays the reference implementation's own recorded model outputs through this port
  and asserts the resulting queues match *exactly*. Point `SMART_PARITY_FIXTURE` at a directory from
  `tools/export_parity_fixture.py`.
- **Chain and For You simulations** replay recorded outputs and synthetic listeners through the full
  chain; they are how the recommender's constants are chosen and defended.
- **Real files** run the tag reader, the lyrics reader and the ID3 writer against actual music, because
  synthetic fixtures only prove a codec matches one reading of the spec — real files prove it matches
  what encoders actually emit. Point `ID3_REAL_FILES` at a folder of `.mp3`s, `REAL_AUDIO_FILE` at a
  single track.

The research behind the For You design is in [docs/for-you-ux.md](docs/for-you-ux.md).

## Credits

Built on the shoulders of:

- **[ONNX Runtime](https://onnxruntime.ai/)** — on-device inference on both platforms
- **[MobileNetV4](https://arxiv.org/abs/2404.10518)** and **[MiniLM](https://arxiv.org/abs/2002.10957)** — the encoder architectures behind the audio and text embeddings
- **[MusicBrainz](https://musicbrainz.org/)** — the CC0 artist-alias data behind smart search
- **[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)**, **[Koin](https://insert-koin.io/)**, **[Coil](https://coil-kt.github.io/coil/)**, **[Media3](https://developer.android.com/media/media3)**

## Licence

**Apache-2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Bundled models under `androidApp/src/main/assets/ml/` are covered by permissive licences documented
in [LICENSE-MODEL.txt](androidApp/src/main/assets/ml/LICENSE-MODEL.txt). The architecture selection,
benchmarks, rejected candidates and next compression target are in
[docs/model-selection.md](docs/model-selection.md).

<p align="center"><sub>Everything runs on your device. Your taste stays there.</sub></p>
