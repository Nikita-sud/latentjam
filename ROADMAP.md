# Roadmap — feature parity and beyond

Goal: LatentJam looking and working like the original Auxio-based app — under
Apache-2.0, and where possible better.

**Ground rule (applies to every line below):** parity means re-implementing
*functionality*. Feature sets and standard music-player behavior are not
copyrightable; Auxio's code, layouts, strings, and assets are — nothing is
ever copied, ported-by-diff, or opened-for-reference from the GPL branches.
LatentJam's own features (SMART, For You, …) are re-authored fresh against
the new architecture, spec-first from the project's own design documents.

## Core player (Auxio-functionality parity)

| Feature | Status |
|---|---|
| Playback via Media3 ExoPlayer, background service, media notification | ✅ `:core:playback` |
| Songs list with album art | ✅ |
| Shuffle OFF / ON | ✅ |
| Mini-player (play/pause/next) | ✅ |
| Now-playing screen (seek bar, queue view, artwork) | ⬜ next |
| Albums / Artists / Genres browsing | ⬜ next |
| Search | ⬜ |
| Playlists (create/edit/persist) | ⬜ |
| Playback state persistence (resume queue after restart) | ⬜ |
| Library scanner beyond MediaStore (multi-artist tags, discs, sort tags — musikr-quality metadata, own implementation) | ⬜ |
| Folders view / SD-card awareness | ⬜ |
| ReplayGain | ⬜ |
| Gapless playback | ⬜ (Media3 largely free) |
| Widgets | ⬜ |
| Android Auto | ⬜ |
| Headset autoplay / becoming-noisy | ✅ (Media3) |
| Equalizer intent support | ⬜ |
| Edge-to-edge, Material 3, dynamic color, dark theme | ✅ |
| Settings screen | ⬜ |

## UX polish backlog (from the 2026-07 UX/HCI research pass)

| Item | Why (principle) | Status |
|---|---|---|
| Dark theme + Material You dynamic color (brand palette < API 31, iOS) | Comfort, platform consistency | ✅ |
| Mini-player progress line | Visibility of system status | ✅ |
| Persistent labeled SMART control (icon+label+color) | State visibility, WCAG 1.4.1 | ✅ |
| Predictive back opt-in | Platform consistency | ✅ (flag; nav transitions ⬜) |
| Library search (M3 SearchBar) | Findability — top long-list fix | ⬜ next |
| Fast scroller + sticky A–Z headers (854 rows) | Fitts's law, efficiency | ⬜ next |
| Sort control (title/artist/date-added) + persistence | User control | ⬜ |
| Motion system: container transform mini↔now-playing, shared-axis tabs | Spatial continuity | ⬜ |
| Artwork-derived gradient on now-playing | Content-based color convention | ⬜ |
| Mini-player swipe gestures (horizontal skip, vertical expand) | Efficiency convention | ⬜ |
| Scroll-to-top affordance; queue item animations | Long-list ergonomics | ✅ queue anims; rest ⬜ |
| Consider bottom NavigationBar for top-level browse | Thumb-reach | ⬜ (debatable, last) |

## LatentJam features (our own, re-authored)

| Feature | Status |
|---|---|
| SMART shuffle position (engine-driven next track) | ✅ live, real embeddings |
| On-device MNv4 encoder via ONNX Runtime | ✅ (equivalence gate 0.9959) |
| Embedding index persistence | ⬜ in progress |
| Background full-library indexing (resumable, NPU later via QNN EP) | ⬜ in progress |
| **For You page** (worlds carousel, top rows, worth-revisiting — re-authored spec-first from our own design docs) | ⬜ planned |
| Listening history (skips, listen-throughs, replays; local DB) | ⬜ needed by For You + future predictor |
| Favorites | ⬜ |
| Metadata edit dialog | ⬜ |
| Personalized predictor chain (semantic z-term, fused ranking — port of the research line) | ⬜ later |
| QNN execution provider on Snapdragon (S24 Ultra NPU) | ⬜ later |

## Cross-platform

| Feature | Status |
|---|---|
| iOS: shared UI shell builds and runs | ✅ builds; run pending CoreSimulator fix |
| iOS: AVQueuePlayer playback backend | ⬜ |
| iOS: music library source (files import / MusicKit) | ⬜ |
| iOS: ONNX/Core ML embedding backend | ⬜ |
