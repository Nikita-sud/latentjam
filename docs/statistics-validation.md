# Statistics dashboard validation — 5 September 2026

Settings → Statistics and the optional main Statistics page share one dashboard. It includes
seven-day, thirty-day, and all-time filters; listening time and previous-period changes; daily
activity with selectable day values; active days, first listens, streaks, and repeat plays;
completion/skip shares; library coverage; SMART mode usage; hourly activity; and ranked artists
and tracks with artwork.

Statistics is disabled by default. Settings → Pages can enable it, move it, and make it the
opening page. Existing eight-page preferences append Statistics hidden. Saved layouts and local
backups preserve an explicit opt-in. The page remains available with retained listening history
even when the current library is empty.

## Accuracy and performance

- Totals use actual listened duration when recorded, with the established legacy fallback.
  Negative and future timestamps are excluded, and duration additions saturate safely.
- Previous-period comparisons use equal rolling windows; the daily chart explicitly labels its
  seven or thirty local calendar dates. Quiet dates have zero values.
- First listens mean the first recorded play in saved history. Streaks use all saved history.
  SMART usage means SMART shuffle was enabled when the listen started.
- Historical totals include unavailable tracks. Rankings and coverage use the available library.
  Contradictory completed/skipped events cannot inflate their combined rate above 100%.
- A single history pass collects period totals, daily activity, and discovery data, with periodic
  cancellation checks. Chart size is bounded, and rankings are limited to ten entries each.
- History snapshots are reused across filter changes until the recorded-history revision changes.
  Catalog maps and summaries are built off the main thread. Hidden dashboards cancel calculation;
  no playback-position timer drives updates.
- History refreshes and opening/closing overlays retain the displayed dashboard while a fresh
  snapshot is prepared, preventing loading rows from resetting scroll position.

## Automated checks

- 904 Android host tests passed with zero failures or errors, including eleven new history-metric
  regressions and additional optional-page/default/migration/backup tests.
- Android debug assembly, Android lint, and shared iOS simulator compilation passed.
- Lint reports zero errors and four existing platform/API warnings.
- Statistics strings have valid XML and matching placeholders in all eighteen resource bundles.
- Whitespace/diff checks passed.

Command:

```sh
./gradlew testAndroidHostTest :androidApp:assembleDebug \
  :composeApp:compileKotlinIosSimulatorArm64 :androidApp:lintDebug --offline
```

## Android runtime checks

Checked a temporary read-only Pixel 7 Pro emulator using its existing library and saved history:

- Empty recent-period and populated all-time dashboards render correctly in Settings and as a
  main page, with Russian translations, long labels, and CJK artist names.
- A tapped daily bar displays the corresponding day, play count, and listening time.
- Statistics is initially disabled, can be enabled and reordered, and appears in the opening-page
  chooser. Its startup selection and order survive installing the updated build and restarting.
- The exact visible dashboard text and scroll offsets survive opening/closing Settings and a
  round trip through four other main pages; the chosen period is retained.
- At 150% system text size, period chips and summary metrics wrap without overlap.

## Limits

This verifies the shared calculations, builds, and selected Android flows. It does not establish
that every device is bug-free. iOS runtime, physical-device battery/frame-time profiling, and
optional external SMART parity/real-MP3 fixture paths were not exercised. Retained-history behavior
with an empty catalog was reviewed in code; the native smoke test used an existing library.
