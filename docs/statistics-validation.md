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

# Statistics v2 — 19 October 2026

The page now follows `docs/statistics-insights-proposal.md` (v5): three sections — *You lately* (on repeat now,
streak), *Your numbers* (the period chips govern only this section) and *Over time* (slipping away, a year ago,
your patterns, library and decades). The facts come from `ListeningNormalization` and `ListeningInsights` in
`core/history`; the overview itself is unchanged and runs on the normalised log.

## Automated checks

- 2,905 Android host tests, 0 failures (new: `ListeningNormalizationTest` 15, `ListeningInsightsTest` 22,
  `StatsSnapshotsTest` 3, including the old-vs-normalised overview equivalence, resume joins, restart and
  backward-seek limits, a SMART-only legacy log, and the producer's failure → retry and abandoned-awaiter cases).
- `StringResourceParityTest` passes for all 21 locale folders (new keys, `stats_days_ago` now a plural,
  `values-in`/`values-iw`/`values-zh` faithful duplicates).
- Android debug + release assembly, Android lint (0 errors, 6 existing warnings, none in this change), iOS
  simulator compilation of `composeApp` and of the `core:history` tests.
- R8 release dex: largest method 243 registers (a static initializer); no method near the 256 edge. App.kt and
  the `ListeningStatsSettings` signature are untouched.

Command:

```sh
./gradlew testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleRelease \
  :composeApp:compileKotlinIosSimulatorArm64 :core:history:compileTestKotlinIosSimulatorArm64 \
  :androidApp:lintDebug --offline
```

## Runtime checks

- Release arm64 APK launch smoke on a read-only Pixel 7 Pro emulator and on the owner's phone (SM-S928B,
  installed as an update over 0.9.1): process up, no VerifyError or crash.
- Read-only emulator with a synthetic 14-month v4 log (4,896 listens over the real library), Russian UI: every
  section renders; *On repeat now*, streak, hero with "≈ 10 days of music" on All time, daily and monthly bars,
  new vs familiar with the previous-window line, dayparts and sessions, top chips (Albums and Genres appear when
  tagged), finished / partly / skipped adding to 100 %, *Slipping away*, sequences, library and decades. A chip
  change dims the old numbers under a progress line.
- Found and fixed on the emulator: the page opened scrolled past section 1 when it finished loading after section
  2 (its heading is now always the first item); track-row counts were truncated; ru/uk/pl day counts read wrong.

## Limits

No Compose UI-test harness exists in the project, so the dimmed-section and retry flows are covered by the
producer's unit tests and by eye on the emulator, not by an automated UI test. Not exercised: iOS runtime, TalkBack
pass, RTL (he/ar) run, 150 % font, a 200k-event log on a 48 MB heap, and the page on the owner's real history (the
build is on the phone for that).
