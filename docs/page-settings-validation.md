# Page settings and app checks — 5 September 2026

Settings → Pages now controls visibility, page order, and the opening page. Map and Statistics are
hidden by default, including Map's track-menu shortcut. At least one page remains enabled. Preferences persist
on Android and iOS and are included in version 4 local backups; older backups remain readable.

## Fixes and performance changes

- Browse destinations use stable identities rather than fixed indices. Reordering retains the
  current destination and scroll position; hiding it selects an enabled destination.
- Pager count, keys, and page content read one observable page list. This prevents animated
  content from retaining stale page keys during visibility changes. The existing pager survives
  changes so its saved list positions are retained.
- For You discovery and Map layout work run only while their destination is open. Warm discovery
  checks vector fingerprints before allocating fused vectors. Sonic journeys are built for For
  You only. Suggestion-read failures preserve the existing page instead of crashing playback.
- Background indexing contains ordinary initialization, storage, and notification failures.
  Cancellation still checkpoints pending work, and independent cleanup always clears running state.
- Queue reconciliation preserves queues after partial permission failures and removes deleted
  tracks after authoritative scans, including a confirmed empty library. Hidden tracks remain in
  existing queues.

## Automated validation

| Check | Result |
| --- | --- |
| Android host test suites | 904 tests, zero failures or errors |
| Android debug assembly | Passed |
| Shared iOS simulator compilation | Passed |
| Android lint | Zero errors; four platform/API warnings |
| Resource translation and placeholder parity | Passed across all resource bundles |
| Whitespace/diff checks | Passed |

The new regression coverage includes all 512 page-visibility combinations, invalid preferences,
ordering boundaries, backup migration/restoration, destination fallback, indexing failure and
cancellation cleanup, and partial versus authoritative library scans.

The optional Statistics page and dashboard have additional
[statistics validation notes](statistics-validation.md).

Command used:

```sh
./gradlew testAndroidHostTest :androidApp:assembleDebug \
  :composeApp:compileKotlinIosSimulatorArm64 :androidApp:lintDebug --offline
```

## Android emulator checks

Validated in a temporary read-only Pixel 7 Pro session with the existing music library:

- Map defaults to hidden and can be enabled, reordered, and hidden again.
- Repeated visibility changes do not crash the app.
- Reordering Tracks and showing/hiding other pages preserve the exact visible songs and scroll
  offset, including a list scrolled well beyond its first rows.
- The last visible page cannot be disabled; the opening-page chooser excludes hidden pages.
- Hiding the current page switches to an enabled page.
- Settings survive a process restart. Reset restores the default order, Tracks as the opening
  page, and Map hidden.
- A fresh Tracks launch produces no library-clustering log entries.

## Scope

This is a code review, automated validation, and Android emulator smoke test, not a guarantee that
all device-specific bugs are eliminated. Physical-device battery, memory, and frame-time profiling
and iOS runtime testing were not performed. Optional external SMART parity and real-MP3 fixtures
were not configured; their fixture-dependent paths were not exercised by the default host run.

Navigation uses the documented
[PagerState data-set update API](https://developer.android.com/reference/kotlin/androidx/compose/foundation/pager/PagerState#requestScrollToPage(kotlin.Int,kotlin.Float))
to preserve the requested destination while page data changes.
