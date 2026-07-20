# LatentJam

A local music player that understands what your library sounds like.

No account, no catalogue, no network. LatentJam reads the music already on your device and runs a
small neural recommender on it — entirely on the phone — so shuffle can follow a thread instead of
throwing dice, and a "For You" page can surface the records you own and forgot.

Kotlin Multiplatform · Compose Multiplatform · Apache-2.0

---

## Why it exists

Shuffle on a local player is random. Streaming services solved the sequencing problem, but only for
their own catalogue, and only by sending your listening to a server.

LatentJam does it for music you already own. Every track is embedded twice — once from its audio,
once from its tags — and compact ONNX models decide what should follow what. All of it runs
locally. Nothing about your listening leaves the device, because there is nowhere for it to go.

## What it does

**SMART shuffle.** Not "nearest neighbour" repeated. Picking a track plans a *walk*: a scoring chain
balances the model's vote against local coherence, gravity back toward the track you actually chose,
and pool-relative semantic scores that keep niche corners of a library intact — the case where a
Moldovan estradá seed used to collapse into whatever the model found generically popular. Metadata
rules then space out artists, suppress duplicate titles, and damp the dense cinematic/anime cluster
that otherwise leaks into everything.

**For You.** Rediscovery, not discovery — you already own everything here. A hero card offers one
confident play (something you were interrupted mid-way through, a favourite gone quiet, something
never heard), and the rows surface what browsing cannot: proven favourites untouched for 90+ days,
tracks SMART found that you then listened all the way through, records you own and never opened.
When several dormant favourites share a playlist, the playlist is offered instead of its tracks —
the unit in which music was loved is not always the track.

**The rest of a real player.** Media3 playback with a proper media session, queue, playlists,
album/artist/genre browsing, search with a fast scroller, a system equalizer, ID3 tag editing, and a
settings screen that says honestly what the recommender knows about your library.

**17 languages**, including Russian, Romanian, Arabic (RTL) and CJK, with correct plural forms.

## How the recommender works

Two signals per track, both computed on the device:

| Signal | Dimensions | Where it comes from |
|---|---|---|
| Audio embedding | 960 | MobileNetV4-Conv-M encoder over the waveform |
| Metadata embedding | 384 | Int8 MiniLM over trusted `genre; artist; year` tags |

Retrieval round-robins separate anchor-audio, session-audio and seed-text rankings into a candidate
pool, so there is no hand-tuned numeric weight between embedding spaces. A 960-d GRU state encoder
over the last four plays, completion/skip signals, and 30/365-day local taste centroids feeds a
frozen scorer over 100 candidates. A 253 KB
learned MiniLM residual conditions those logits when trusted text exists; no manually chosen
text/audio score weight is involved. Missing text is an exact audio-only fallback. Track titles are
deliberately excluded from the embedding, so a filename such as `Hard Techno Mix` cannot inject a
genre claim. The chain then applies local audio coherence, seed gravity and metadata safety rules.

The app ships five ONNX graphs: audio and metadata encoders run once while tracks are indexed; state,
acoustic scorer and text residual run while a SMART queue is built. The complete model/vocabulary
bundle is about 57.3 MiB on both Android and iOS. There is no precomputed per-track descriptor
catalogue: imported tracks get the same fully local path as every other track.

On first launch, the app creates the audio index progressively in small persisted batches and embeds
the selected seed on demand; playback abstains rather than silently replacing SMART with randomness
until candidates are ready. A person with no history gets an explicitly trained seed-only state,
not a zero vector. On iOS, the device Music library and app-owned Files imports are merged. Protected
Music downloads remain playable and use local metadata/text recommendations; owned items that expose
an asset URL also receive waveform embeddings.
As private listening accumulates, SMART uses it as runtime context without training on the phone or
uploading it. Both platforms persist that history locally across launches.

## Building

```bash
./gradlew :androidApp:assembleDebug
```

Requires JDK 17+ and the Android SDK. The first build downloads a large Kotlin/Native toolchain.

SMART ONNX inference and playback are implemented on Android and iOS; the iOS shell uses the same
graph contracts through ONNX Runtime's native C API. iOS intentionally keeps tag writing disabled
for Music-library items because the system exposes them read-only; the equalizer remains Android-only.

## Layout

```
core/smart      similarity engine, SMART chain, ONNX runtimes, tokenizer
core/library    MediaStore scanning, playlists, catalog grouping, ID3 tag writing
core/playback   Media3 playback, queue, equalizer
core/history    listening events and aggregates
composeApp      all UI, shared by both platforms
androidApp      packaging shell (see below)
iosApp          Xcode project
tools/          one-off scripts, not on any build path
```

`androidApp` contains no Kotlin. AGP 9 ships no Compose Multiplatform *application* plugin, and
`com.android.application` cannot be combined with the KMP plugin — so the UI lives in `composeApp`
as a library and `androidApp` exists only to package it.

## Testing

```bash
./gradlew :core:smart:testAndroidHostTest \
          :core:library:testAndroidHostTest \
          :composeApp:testAndroidHostTest
```

Two suites are worth knowing about, because they check what unit tests usually miss:

- **SMART parity** replays the reference implementation's own recorded model outputs through this
  port and asserts the resulting queues match exactly. It needs an ~8 MB fixture that is not
  committed — set `SMART_PARITY_FIXTURE` to a directory produced by
  `tools/export_parity_fixture.py`, or the test skips.
- **ID3 real files** runs the tag writer against actual music, checking that every frame survives an
  edit and the audio stream stays byte-identical. Set `ID3_REAL_FILES` to a directory of `.mp3`s, or
  it skips. Synthetic fixtures only prove a codec matches one reading of the spec; real files prove
  it matches what encoders actually emit.

See [ROADMAP.md](ROADMAP.md) for what is done and what is not, and
[docs/for-you-ux.md](docs/for-you-ux.md) for the research behind the For You design.

## Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

This tree shares no code and no git history with LatentJam's earlier incarnation, which was a fork of
[Auxio](https://github.com/oxygencobalt/Auxio) and is licensed GPL-3.0. This branch descends from its
own parentless root commit and was written from scratch against a new architecture. Nothing may be
copied across that boundary in either direction. The earlier work remains available under GPL-3.0 on
its own branch.

Bundled models under `androidApp/src/main/assets/ml/` are covered by permissive licences documented
in [LICENSE-MODEL.txt](androidApp/src/main/assets/ml/LICENSE-MODEL.txt). The selected architecture,
benchmarks, rejected candidates and next compression target are in
[docs/model-selection.md](docs/model-selection.md).
