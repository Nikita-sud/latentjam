# Map interaction, layout and caching — 23 September 2026

## Changes

- The portrait page scrolls at every window size. One-finger drags over the plot belong to
  the page, while two fingers claim map pan/zoom. Once claimed, a pinch consumes its final
  finger-up so it cannot turn into a scroll or a tap. Adding a finger to an already-started
  page scroll does not steal that gesture.
- Inline portrait plot height depends only on window width, not on the active lens, legend,
  region metadata or action labels. The headline reserves the tallest available text; legends
  measure all four small keys but only place/draw/expose the active one.
- Landscape windows at least 640 dp wide use a fixed plot beside scrolling controls. Safe
  horizontal insets keep the display cutout away from plot marks and labels. Narrow windows
  and large text retain a scrolling fallback. Region labels increased from labelMedium to
  labelLarge and region counts use bodyMedium.
- Lens, selected region and normalized viewport survive tab returns and rotation. Region
  selection is anchored to a track so reassigned region indices do not silently select a
  different group. Panning reads state in the draw phase; it does not rebuild the page.
- The plot is an opaque neutral surface in both themes. The song cloud remains behind page
  chrome, not behind analytical marks.
- Reset uses a restart arrow; a separate expand button opens a full-window map. Close and
  reset stay available. There is only one active canvas, and expanded-map pans can use one
  finger where no parent page scroll competes. The usual page, lens and selection are restored
  on closing; opening tracks/actions dismisses the expanded map first.
- The bounded MapPageCache retains one exact vector fingerprint/population layout and one
  presentation. Warm returns do not reload the layout or listening stats. Listening revisions,
  including clearing history, rebuild facts without recalculating coordinates; locale and
  region membership update presentation only. Changed vectors/population invalidate geometry.
  Computation remains lazy, cancellable and off the UI thread; the existing disk cache and
  3,000-track ceiling remain. Fully computed geometry survives a best-effort disk-save failure.
- A cached MapPage also retains its spatial index and centroids across tab returns. This is a
  data cache, not a raster cache: theme, lenses and zoom remain crisp without storing screenshots.
- The existing 10 dp list-top fade now also covers For You, Statistics, Settings, search
  suggestions, queue and lyrics. Tracks, collection lists, alphabet lists/grids and playlists
  already had it. The mask only draws while scrolled and only uses a small top-strip layer.
  The plot itself never receives this mask.

## Automated checks

- MapTabTest: 51 passed (viewports, picking, labels, accessibility, fallback and build states).
- MapLensesTest: 24 passed (lens policy, finite palettes, spatial index and culling).
- MapInteractionTest: 6 passed (gesture ownership, small/wide layouts and normalized rotation).
- MapPageCacheTest: 9 passed (warm reads, history changes/clearing, localization, reassignment,
  vector invalidation, deletion, unclaimed/missing positions, persistence failure and cancellation).
- Android minified release assembly and iOS simulator Kotlin compilation passed.

## Runtime observations

Android demo emulator, 16 fictional tracks in four regions, paused playback:

- Dragging vertically over the portrait plot scrolls the page and exposes all region actions.
- Switching Worlds/Plays retains the plot bounds and dot positions.
- Both landscape orientations show the independent controls pane; scrolling it leaves the
  plot fixed, and the camera cutout is outside the plot after applying horizontal safe insets.
- Region label taps update the selected region; the selection and Plays lens survive rotation
  and a trip through appearance settings. Light theme has a neutral plot and readable dark marks.

- Full-screen portrait expands the plot without the root header or mini-player. Worlds/Plays
  keep the same canvas bounds; region selection remains interactive. Show the tracks closes
  the dialog before opening the selected collection (verified with Soft Geometry, four tracks).
- Full-screen landscape pins reset/close above an independently scrolling control pane.
  Closing by the close icon or system Back restores the inline map. Rotation remains usable.
- Full-screen light mode fills the window to its edges with matching system-bar appearance;
  status icons stay dark and the camera cutout remains outside the plot. Dark full-screen
  portrait was also checked after restoring the original Follow system theme.
- The Settings list was scrolled in light mode: only the partial top row fades at the header;
  lower text remains sharp. No fade is applied to the map canvas.

## Delivered build

- Updated the existing app on emulator-5554 without clearing its data. Left the full-screen
  map open in portrait with the original Follow system theme restored.
- APK: `androidApp/build/outputs/apk/release/androidApp-arm64-v8a-release.apk`
- SHA-256: `c04ca3274c2e6605d7505db9ed791f3a490f5de09fc3ef784432c487836aafc7`
- Final Android release / iOS compilation log: `/tmp/latentjam-map-delivery-build.log`.
- Map test run log: `/tmp/latentjam-map-fullscreen-validation.log` (90 passing tests).
- `git diff --check` passed. No LatentJam crash appeared in the emulator crash buffer.

## Limits

This is not a new t-SNE algorithm benchmark; the reduction in repeated disk/statistics reads is
verified by instrumented cache-call counts in unit tests. The UI automation can drag one pointer
but cannot hold two concurrent pointers, so physical pinch arbitration still needs a real-device
check. No Samsung is currently attached. iOS was compile-checked, not exercised in Simulator here.
