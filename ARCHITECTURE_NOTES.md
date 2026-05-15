# LatentJam Architecture Notes

Personal reference for the codebase forked from Auxio. Written after a directed read of the top ~10 files. Keep updated when concepts shift.

## Module split

Two Gradle modules:

- **`:app`** — UI, playback orchestration, foreground service, settings, library facade. Namespace `io.github.nikitasud.latentjam`.
- **`:musikr`** — standalone library scanner. Namespace **kept as `org.oxycblt.musikr`** (vendored library — preserved for clean upstream pulls). Has its own `ARCHITECTURE.md` at [musikr/ARCHITECTURE.md](musikr/ARCHITECTURE.md). Includes JNI to TagLib (`musikr/src/main/cpp/`).

`:app` depends on `:musikr`. Never the reverse.

Other vendored projects (settings.gradle): `:media-lib-exoplayer`, `:media-lib-decoder-ffmpeg`, etc. — Auxio's vendored ExoPlayer fork at `media/`.

## Process anchor (3 lifetimes)

```
Application (LatentJam)        ← lives as long as the OS process
   ↓ launches
Activity (MainActivity)        ← lives while user is in the app UI
   ↓ starts via Intent in onResume
Service (LatentJamService)     ← lives while playing OR indexing
```

Activity dies when user backs out → Service keeps running for playback. When playback stops AND indexing completes → Service goes background → OS may kill the process.

## Top-level files

| File | Role |
|---|---|
| [LatentJam.kt](app/src/main/java/io/github/nikitasud/latentjam/LatentJam.kt) | `@HiltAndroidApp` Application — settings migration, dynamic shortcuts, Timber init |
| [MainActivity.kt](app/src/main/java/io/github/nikitasud/latentjam/MainActivity.kt) | `@AndroidEntryPoint` single Activity — `installSplashScreen()`, theme, intent handling, kicks the service |
| [LatentJamService.kt](app/src/main/java/io/github/nikitasud/latentjam/LatentJamService.kt) | `MediaBrowserServiceCompat` host. Composes `PlaybackServiceFragment` + `MusicServiceFragment` |
| [MainFragment.kt](app/src/main/java/io/github/nikitasud/latentjam/MainFragment.kt) | Root fragment shell — playback bottom sheet + queue bottom sheet + back-press routing + FAB logic |
| [music/MusicRepository.kt](app/src/main/java/io/github/nikitasud/latentjam/music/MusicRepository.kt) | Facade over `:musikr` — IndexingWorker registry, `UpdateListener`/`IndexingListener` |
| [musikr/Musikr.kt](musikr/src/main/java/org/oxycblt/musikr/Musikr.kt) | Pipeline orchestrator: `ExploreStep` → `ExtractStep` → `EvaluateStep` |
| [playback/state/PlaybackStateManager.kt](app/src/main/java/io/github/nikitasud/latentjam/playback/state/PlaybackStateManager.kt) | Core playback state — decoupled from ExoPlayer via `PlaybackStateHolder` interface |
| [playback/PlaybackViewModel.kt](app/src/main/java/io/github/nikitasud/latentjam/playback/PlaybackViewModel.kt) | UI-facing wrapper. Subscribes to `PlaybackStateManager.Listener` → exposes `StateFlow`s |

## Package map (`:app`)

```
io.github.nikitasud.latentjam (root: LatentJam, MainActivity, MainFragment, LatentJamService, IntegerTable)
├── detail/      — Album/Artist/Genre/Playlist detail screens + decision dialogs + sort
├── home/        — Home tab UI, tabs config, FAB-driven actions
├── image/       — Cover loading (Coil), CoverProvider (system content provider)
│   ├── coil/      — Custom Coil fetchers/transformations
│   └── covers/    — Cover storage, transcoding, embedded extraction
├── list/        — Shared list adapters, RecyclerView utilities, sort engine
│   ├── adapter/   — FlexibleListAdapter, PlayingIndicator, SelectionIndicator
│   ├── menu/      — Per-item context menus (long-press)
│   ├── recycler/  — Custom RecyclerViews + ViewHolders
│   └── sort/      — Sort modes + dialog
├── music/       — Library facade
│   ├── decision/  — Playlist add/new/import/export/rename/delete dialogs
│   ├── interpret/ — Tag interpretation settings (separators, naming)
│   ├── locations/ — Music folder picker (SAF / MediaStore mode)
│   ├── service/   — MusicServiceFragment (MediaBrowser integration), IndexerNotifications
│   └── shim/      — Hilt bindings between app and musikr APIs
├── playback/
│   ├── decision/  — Play-from-artist/genre dialogs
│   ├── persist/   — Playback state Room persistence (heap/shuffleMapping/index)
│   ├── queue/     — Queue UI, drag/drop, BetterShuffleOrder
│   ├── replaygain/ — ReplayGain audio processor
│   ├── service/   — PlaybackServiceFragment, ExoPlaybackStateHolder, MediaSessionHolder
│   ├── state/     — PlaybackStateManager, PlaybackCommand, RepeatMode, DeferredPlayback
│   └── ui/        — Playback panel UI (cover, seek bar, fast-seek overlay, swipe gestures)
├── search/      — Search UI + fuzzy search engine
├── settings/    — Preferences UI
│   ├── categories/ — Audio / Music / Personalize / UI preference fragments
│   └── ui/        — IntListPreference + custom preference UI
├── tasker/      — Tasker plugin integration (Start)
├── ui/          — Base classes
│   └── accent/    — Accent color customization
├── util/        — Context/Framework/Lang/State extensions, CopyleftNoticeTree (Timber tree)
└── widgets/     — "Now Playing" home screen widget
```

## Library scan flow

1. **Trigger**: `MainActivity.onResume` → `startService(LatentJamService.ACTION_START)` ([MainActivity.kt:78-82](app/src/main/java/io/github/nikitasud/latentjam/MainActivity.kt:78))
2. **Service.onCreate**: instantiates `MusicServiceFragment` + `PlaybackServiceFragment` via Hilt-injected factories
3. **MusicServiceFragment.start()** registers an `IndexingWorker` with `MusicRepository`, then calls `MusicRepository.requestIndex(withCache=true)`
4. **`MusicRepositoryImpl.indexImpl()`** ([MusicRepository.kt:382](app/src/main/java/io/github/nikitasud/latentjam/music/MusicRepository.kt:382)):
   - Reads music settings (separators, intelligent sorting, locations, cache revision)
   - Builds `Config(FS, Storage, Interpretation)`
   - Calls `Musikr.new(context, config).run(::emitIndexingProgress)`
5. **`Musikr.run`** ([Musikr.kt:116](musikr/src/main/java/org/oxycblt/musikr/Musikr.kt:116)) wires three coroutine pipeline stages via `Channel<Explored>` → `Channel<Extracted>`:
   - `ExploreStep.explore` — SAF/MediaStore traversal, MIME filter, cache check
   - `ExtractStep.extract` — JNI → TagLib → metadata extraction
   - `EvaluateStep.evaluate` — build the music graph (Songs ↔ Albums ↔ Artists ↔ Genres ↔ Playlists)
6. **Result**: `MutableLibrary` returned in `LibraryResult`, `MusicRepository.emitLibrary()` swaps the in-memory library and notifies all `UpdateListener`s on `Dispatchers.Main`

Progress callbacks (`IndexingProgress.Songs(loaded, explored)` / `Indeterminate`) flow back to `MusicRepository` → `IndexingListener`s → UI shows progress.

## Playback flow

```
User action (tap song)
   ↓
PlaybackViewModel.play(song, with)             [HiltViewModel — UI-facing]
   ↓ commandFactory.songFromAll(...) etc.
PlaybackStateManager.play(command)             [singleton — stateful, decoupled from ExoPlayer]
   ↓
PlaybackStateHolder.newPlayback(command)       [interface, impl = ExoPlaybackStateHolder]
   ↓
ExoPlayer.setMediaItems(...) + play()          [actual audio engine]
   ↓ ExoPlayer.Listener event
PlaybackStateManager.ack(holder, StateAck.X)   [holder reports back what changed]
   ↓
listeners.forEach { it.onXxx(...) }             [PlaybackViewModel + others observe]
   ↓
StateFlow updates                              [Compose-style reactive UI]
   ↓
UI (PlaybackBar/Panel/Queue) recomposes
```

Key indirection: **`PlaybackStateManager` does not know about ExoPlayer**. The `PlaybackStateHolder` interface is the seam. This means future ML-driven playback (e.g., "play next track via embedding similarity") can register a different state holder, or wrap the existing one, without touching ExoPlayer code.

`StateAck` enum values cover all change types: `IndexMoved`, `PlayNext`, `AddToQueue`, `Move`, `Remove`, `QueueReordered`, `NewPlayback`, `ProgressionChanged`, `RepeatModeChanged`, `SessionEnded`.

## Single-Activity navigation

- `MainActivity` hosts everything. No second Activity.
- Two nav graphs:
  - `res/navigation/outer.xml` — top-level destinations (Home, Settings, About)
  - `res/navigation/inner.xml` — detail navigation (Album/Artist/Genre detail, search, dialogs)
- `MainFragment` contains an `exploreNavHost` FragmentContainerView for nested nav, plus two Material `BackportBottomSheetBehavior` bottom sheets (playback + queue) layered above.
- Back press: routed through 4 chained `OnBackPressedCallback`s in MainFragment ([MainFragment.kt:686+](app/src/main/java/io/github/nikitasud/latentjam/MainFragment.kt:686)) — sheet → detail edit → selection → speed-dial.

## Foreground state machine

`LatentJamService.updateForeground(change)` switches notification source based on what's active:

```
ForegroundListener.Change:
  MEDIA_SESSION  →  PlaybackServiceFragment owns the foreground notification
  INDEXER        →  MusicServiceFragment owns it (only when no playback)
```

Service uses **`START_NOT_STICKY`** intentionally ([LatentJamService.kt:67-68](app/src/main/java/io/github/nikitasud/latentjam/LatentJamService.kt:67)). Comment explains: with `START_STICKY`, restart-after-kill creates foreground errors because the Activity is also dead.

Implication for ML: no auto-restart → any ML state must be persisted. Use Room (already used by playback persistence) or DataStore.

## Hilt setup

- `@HiltAndroidApp` on `LatentJam` class
- `@AndroidEntryPoint` on Activity, Service, Fragments, Receivers
- `@HiltViewModel` on every ViewModel
- Module classes named `*Module.kt` provide bindings (`MusicModule`, `PlaybackModule`, `ImageModule`, `ListModule`, `UIModule`, `MusikrShimModule`, `CoilModule`, `CoversModule`, `PersistenceModule`, `PlaybackStateModule`, `SystemModule`, `DetailModule`, `HomeModule`, `SearchModule`)
- Each ViewModel gets its dependencies (Repository, Settings, Factory) via constructor injection

## DeferredPlayback pattern

When the user clicks "play X" before the playback engine is ready (e.g., before library is loaded, before service starts), the request is wrapped in a `DeferredPlayback` and queued in `PlaybackStateManager.pendingDeferredPlayback`. When the `PlaybackStateHolder` registers later, it pulls and processes the action.

Used for:
- `Intent.ACTION_VIEW` (file manager opens audio file via LatentJam) → `DeferredPlayback.Open(uri)`
- Shuffle-all shortcut → `DeferredPlayback.ShuffleAll`
- App restart → `DeferredPlayback.RestoreState(false)` (restores last queue/position)

For ML: same pattern works for "queue up recommended next track" before model is loaded.

---

## ML integration anchors

These are the seams where future ML code plugs in. **Do not modify any of these in v1.** Just know they exist.

### 1. Library access (feature extraction surface)

[`MusicRepository.library`](app/src/main/java/io/github/nikitasud/latentjam/music/MusicRepository.kt:65) returns the full `Library` after each scan. Iterate `library.songs / albums / artists / genres / playlists`.

[`MusicRepository.UpdateListener.onMusicChanges(Changes)`](app/src/main/java/io/github/nikitasud/latentjam/music/MusicRepository.kt:182) fires whenever the library changes (initial scan, playlist edit). `Changes(deviceLibrary: Boolean, userLibrary: Boolean)` says what changed.

For ML: `class FeatureExtractor : MusicRepository.UpdateListener` registered via Hilt. `onMusicChanges` triggers re-extraction of features for new/changed songs. Cache features in Room.

### 2. Playback observation (recommendation training signal)

[`PlaybackStateManager.Listener`](app/src/main/java/io/github/nikitasud/latentjam/playback/state/PlaybackStateManager.kt:259) is **the single observation point for every play event**. Methods:

| Method | Triggers when |
|---|---|
| `onIndexMoved(index)` | next track in queue starts |
| `onNewPlayback(parent, queue, index, isShuffled)` | user starts playing something fresh |
| `onProgressionChanged(progression)` | play/pause/seek |
| `onSessionEnded()` | listening session ends (user stops, queue drained) |

For ML: `class ListeningEventRecorder : PlaybackStateManager.Listener` registered via Hilt. Persist `(songUid, startTime, durationMs, completed, skipped, parentContext)` tuples. This is your training/online-learning signal.

### 3. Recommendation injection point

When ML decides "next track should be X", it can:
- (a) Add via `PlaybackStateManager.playNext(songs)` or `addToQueue(songs)` — soft suggestion, user sees it queued
- (b) Implement a custom `PlaybackStateHolder` that wraps `ExoPlaybackStateHolder` and overrides `next()` — hard interception
- (c) `DeferredPlayback.Open(...)` analog for "play recommendation" — fits the existing pattern

(a) is lowest-risk for v1 of the ML feature.

### 4. Privacy opt-in toggle

Settings DataStore lives at [settings/Settings.kt](app/src/main/java/io/github/nikitasud/latentjam/settings/Settings.kt). Per-domain settings (`MusicSettings`, `PlaybackSettings`, `UISettings`, `HomeSettings`, `ImageSettings`) inherit from a `Settings` base.

For ML: add `RecommendationSettings` (or `MlSettings`) with:
- `enableRecommendations: Boolean` (default off)
- `enableEventLogging: Boolean` (default off — so no listening data is recorded without consent)
- `enableNetworkMetadata: Boolean` (already exists pattern: MusicBrainz/AcoustID/CAA opt-ins)

Add to `LatentJam.onCreate` migration list, like the others.

### 5. Foreground service constraints

If ML inference is heavy enough to need its own foreground notification (e.g., during initial embedding pass over the full library), extend `ForegroundListener.Change` enum with a third variant `ML` and route through `LatentJamService.updateForeground`.

For lightweight per-song inference (during play), can run inline in the Listener callback on the IO dispatcher.

---

## Things NOT to touch in v1

| Area | Why |
|---|---|
| `musikr/src/main/cpp/` | TagLib JNI bindings + native build via CMake. Brittle, well-tuned. |
| `musikr/` Kotlin code (any) | Vendored library. Patches make upstream pulls painful. |
| `playback/service/ExoPlaybackStateHolder.kt` | ExoPlayer integration is delicate — handles edge cases for queue restoration, shuffle, repeat |
| `playback/service/MediaSessionHolder.kt` | System integration: lock screen, Bluetooth, Android Auto, Wear OS |
| `playback/service/MediaButtonReceiver.kt` | Comment in code explains workaround |
| `playback/queue/BetterShuffleOrder.kt` | Custom shuffle that respects ExoPlayer's queue contract |
| Pinned dependencies | FragmentKtx 1.6.2, RecyclerView 1.2.1, ViewPager2 1.0.0, Material 1.14.0-alpha10 — all pinned for known bugs (see `app/build.gradle` comments) |
| `org.gradle.parallel=false` in `gradle.properties` | Memory pressure from vendored ExoPlayer tree. Don't flip. |
| `LatentJamService.onStartCommand` returns `START_NOT_STICKY` | Intentional, see code comment |
| Copyright headers in any file | GPL-3 attribution. Restoration was already done in commit `363abca54`. |

---

## "If I forget how this works" — read these first

In order:

1. [musikr/ARCHITECTURE.md](musikr/ARCHITECTURE.md) — the indexer pipeline, three-stage design, threading model
2. [LatentJam.kt](app/src/main/java/io/github/nikitasud/latentjam/LatentJam.kt) (90 lines) — see how Hilt/Timber/migrations wire up
3. [LatentJamService.kt](app/src/main/java/io/github/nikitasud/latentjam/LatentJamService.kt) (~200 lines) — the foreground service contract, how the two ServiceFragments compose
4. [MusicRepository.kt](app/src/main/java/io/github/nikitasud/latentjam/music/MusicRepository.kt) `indexImpl` method — the bridge from app config → Musikr Config → pipeline
5. [PlaybackStateManager.kt](app/src/main/java/io/github/nikitasud/latentjam/playback/state/PlaybackStateManager.kt) `Listener` interface + `ack` method — the only file you need to fully grasp playback events

That's ~700 lines of the most-important code.

---

## Glossary (Auxio idioms)

- **ServiceFragment** — NOT an Android `Fragment`. Custom Auxio composition pattern: a class attached to the Service (`PlaybackServiceFragment`, `MusicServiceFragment`) that owns one concern. Misleading name, blame upstream.
- **StateMirror** — internal data class in `PlaybackStateManagerImpl` holding the current playback snapshot. Updated atomically on ack.
- **DeferredPlayback** — playback request issued before the engine is ready, queued for later
- **Outer / Inner nav** — `outer.xml` for top-level destinations (Home/Settings/About), `inner.xml` for detail-screen navigation
- **CopyleftNoticeTree** — Timber tree that logs a GPL notice on every log call. Auto-planted on forks (i.e. our build). Cosmetic, not a problem.
