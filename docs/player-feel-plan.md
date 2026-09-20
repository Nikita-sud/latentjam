# Player Feel Implementation Plan

> **Executor notes:** work task by task, in order. Each task ends with a green test run, a build, and one commit. Steps use checkbox (`- [ ]`) syntax for tracking. Nothing in this plan is optional unless the task says so.
>
> **Status (2026-09-20):** all eleven tasks landed, one commit each, verified on the `LJ-demo` emulator with the demo library and by the host test suites (`:composeApp` 359, `:core:playback` 68). Not yet verified: the iOS build on a device (it compiles), the fades by ear on a phone, and the Bluetooth branch of the output status with real headphones. Two departures from the text below: Task 4 and Task 5 landed as one commit, and the tilt during a swipe applies to the card alone rather than the whole group (a tilted group swung the neighbour cover up and down). A review pass later the same day hardened the gestures against cancellation and track changes (every pointer loop now settles in a `finally`), gave the queue source a stable identity (`LIBRARY_GROUP` routes instead of titles), paced the cloud by wall time instead of frame count, made the output status follow the route the system actually selected (a tap opens an in-app sheet of the system's live-audio routes, with a link to Bluetooth settings), and moved the fade envelope into one `TransportFadeState` shared by both players. A phone pass later that day (SM-S928B, Android 16, dark theme) followed: the cloud lifts the accent's untoned seed on dark surfaces instead of drawing a toned container that vanished on black, the cover yields height to the controls, the queue peeks at exactly handle plus title (58 dp) so no queue row shows above the navigation bar, the play button sits 38 dp below the seek line and 38 dp above the next-up row with the total height unchanged, album and artist pages reserve the rail gutter only while the rail is on screen, and untrusted controllers may drive the device volume so OEM output panels work.

**Goal:** Port the "B" player concept and its feel layer into the app: gestures on the cover (tap flips to track details, hold opens actions, swipe changes track, drag down closes), a live seek bar with fine scrubbing, a mode button with a hold menu, direct track actions from ⋮, a drifting colour cloud, haptics, and audio fades on pause and resume — all without adding steady-state Compose work to the player.

**Architecture:** Every gesture decision is a pure function in `PlayerFeel.kt`, unit-tested in `commonTest`. Composables read gesture state through `Animatable`/`graphicsLayer` and `drawBehind`, never through `mutableStateOf` that would recompose the screen per frame. Playback position stays observed by the seek bar alone, as today. Platform pieces (audio route, fades) are `expect`/`actual` or engine-local, behind pure math that is tested on the JVM.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.11 (`HapticFeedbackType` full set is available on Android and iOS), Media3 ExoPlayer, AVAudioEngine. No new dependencies.

**Spec:** the design canvas "LatentJam Player Concepts", artboard **B · Мой вариант** and its note, plus the "Ползунок · варианты" board (option 1 is the base). This document restates every decision the tasks need, so it can be executed without the canvas.

## Global Constraints

- Licence header on every new file, copied verbatim:
  ```kotlin
  /*
   * Copyright (c) 2026 LatentJam Project
   * SPDX-License-Identifier: Apache-2.0
   */
  ```
- **No steady-state recomposition from motion.** Per-frame values (drag offsets, cloud phase, flip angle, press scale) live in `Animatable` or `mutableFloatStateOf` read inside `graphicsLayer {}` / `drawBehind {}` / `Canvas` lambdas only. Never read them in composition. The existing rule stays: `positionMs` is projected out of `NowPlayingScreen` and observed only by the seek bar.
- **Reduce Motion is honoured** through `rememberReduceMotion()` exactly like the rest of the app: no flip animation (instant swap), no cloud drift, no slide on track change, no press scale. Haptics stay (they are not motion).
- **Haptics vocabulary** is the one table in `PlayerHaptics.kt`. No composable calls `performHapticFeedback` with a raw type.
- **Strings**: every new key goes into `values/strings.xml` **and all 17 translations** in the same task; `StringResourceParityTest` fails the build otherwise. Rules from `docs/map-page-plan.md` Task 8 apply verbatim: positional placeholders only, no `\'`, `values-in` mirrors `values-id` bodies, every plural declares `other`.
- Test commands: `./gradlew --no-daemon :composeApp:testAndroidHostTest`, `./gradlew --no-daemon :core:playback:testAndroidHostTest`. Build checks: `./gradlew --no-daemon :androidApp:assembleDebug`, `./gradlew --no-daemon :composeApp:compileKotlinIosSimulatorArm64`.
- Device smoke test after each UI task on the `LJ-demo` emulator (`~/Library/Android/sdk/emulator/emulator -avd LJ-demo -port 5556`), demo library already on it.
- Commit after every task. Conventional prefixes as used in this repo (`feat(player):`, `fix(player):`, `perf(player):`, `test(app):`, `feat(playback):`). **No AI attribution in commits**, no tool mentions in subject or body.

---

## Part A — the player screen

### Task 1: Gesture arithmetic, pure and tested

Every threshold the gestures use, in one file, so the composables contain no numbers.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlayerFeel.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/PlayerFeelTest.kt`

**Interfaces:**
- Produces:
  - `enum class ArtworkDragAxis { HORIZONTAL, VERTICAL }`
  - `fun artworkDragAxis(dx: Float, dy: Float, slop: Float): ArtworkDragAxis?` — null until the finger has moved `slop` on either axis; VERTICAL only for downward movement dominated by dy.
  - `fun swipeShown(dx: Float, blocked: Boolean): Float` — cover travel for finger travel dx: `dx * 0.92f`, or `dx * 0.3f` when the direction has no neighbour (rubber band).
  - `fun swipeCommits(dx: Float, width: Float, blocked: Boolean): Boolean` — true past 30 % of the cover width in an unblocked direction.
  - `fun collapseShown(dy: Float): Float` — `dy.coerceAtLeast(0f) * 0.75f`.
  - `fun collapseCommits(dy: Float, threshold: Float): Boolean` — `dy > threshold`.
  - `fun scrubFineFactor(dyBelowBar: Float, halfAt: Float, quarterAt: Float): Int` — 1, 2 or 4.
  - `fun scrubDeltaMs(dxPx: Float, trackWidthPx: Float, durationMs: Long, fine: Int): Long`
  - `fun skipHoldMultiplier(heldMs: Long): Int` — 4, 8, 16, 32 (doubles every 1500 ms, capped).
  - `fun estimatedBitrateKbps(sizeBytes: Long?, durationMs: Long?): Int?` — average from size and duration; null when either is missing or zero.
  - `fun fileFormatLabel(fileName: String?, sizeBytes: Long?, durationMs: Long?): String?` — e.g. `FLAC · 1 010 kbps · 34.2 MB`; extension upper-cased; null when the name has no extension.

- [x] **Step 1: Write the failing tests**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFeelTest {
    @Test
    fun dragAxisIsUndecidedInsideTheSlopAndVerticalOnlyDownwards() {
        assertNull(artworkDragAxis(dx = 5f, dy = 5f, slop = 12f))
        assertEquals(ArtworkDragAxis.HORIZONTAL, artworkDragAxis(dx = -30f, dy = 4f, slop = 12f))
        assertEquals(ArtworkDragAxis.VERTICAL, artworkDragAxis(dx = 3f, dy = 40f, slop = 12f))
        // Upward movement is never a collapse; it decides horizontal only when dx dominates.
        assertNull(artworkDragAxis(dx = 3f, dy = -40f, slop = 12f))
        assertEquals(ArtworkDragAxis.HORIZONTAL, artworkDragAxis(dx = 50f, dy = -40f, slop = 12f))
    }

    @Test
    fun swipeFollowsTheFingerAndRubberBandsAtTheEnds() {
        assertEquals(92f, swipeShown(dx = 100f, blocked = false), absoluteTolerance = 0.001f)
        assertEquals(30f, swipeShown(dx = 100f, blocked = true), absoluteTolerance = 0.001f)
        assertTrue(swipeCommits(dx = -120f, width = 342f, blocked = false))
        assertFalse(swipeCommits(dx = -90f, width = 342f, blocked = false))
        assertFalse(swipeCommits(dx = -300f, width = 342f, blocked = true))
    }

    @Test
    fun collapseResistsAndCommitsPastTheThreshold() {
        assertEquals(0f, collapseShown(dy = -50f))
        assertEquals(75f, collapseShown(dy = 100f))
        assertTrue(collapseCommits(dy = 141f, threshold = 140f))
        assertFalse(collapseCommits(dy = 140f, threshold = 140f))
    }

    @Test
    fun fineScrubSlowsWithDistanceBelowTheBar() {
        assertEquals(1, scrubFineFactor(dyBelowBar = 0f, halfAt = 40f, quarterAt = 90f))
        assertEquals(1, scrubFineFactor(dyBelowBar = 40f, halfAt = 40f, quarterAt = 90f))
        assertEquals(2, scrubFineFactor(dyBelowBar = 41f, halfAt = 40f, quarterAt = 90f))
        assertEquals(4, scrubFineFactor(dyBelowBar = 91f, halfAt = 40f, quarterAt = 90f))
        // Above the bar the finger is still on the slider; no slowdown.
        assertEquals(1, scrubFineFactor(dyBelowBar = -200f, halfAt = 40f, quarterAt = 90f))
    }

    @Test
    fun scrubDeltaScalesWithWidthDurationAndFineness() {
        assertEquals(60_000L, scrubDeltaMs(dxPx = 171f, trackWidthPx = 342f, durationMs = 120_000L, fine = 1))
        assertEquals(15_000L, scrubDeltaMs(dxPx = 171f, trackWidthPx = 342f, durationMs = 120_000L, fine = 4))
        assertEquals(-60_000L, scrubDeltaMs(dxPx = -171f, trackWidthPx = 342f, durationMs = 120_000L, fine = 1))
        assertEquals(0L, scrubDeltaMs(dxPx = 50f, trackWidthPx = 0f, durationMs = 120_000L, fine = 1))
    }

    @Test
    fun skipHoldAcceleratesInStepsAndCaps() {
        assertEquals(4, skipHoldMultiplier(heldMs = 0L))
        assertEquals(4, skipHoldMultiplier(heldMs = 1_499L))
        assertEquals(8, skipHoldMultiplier(heldMs = 1_500L))
        assertEquals(16, skipHoldMultiplier(heldMs = 3_000L))
        assertEquals(32, skipHoldMultiplier(heldMs = 4_500L))
        assertEquals(32, skipHoldMultiplier(heldMs = 60_000L))
    }

    @Test
    fun bitrateAndFormatLabelComeFromSizeAndDuration() {
        assertEquals(320, estimatedBitrateKbps(sizeBytes = 9_600_000L, durationMs = 240_000L))
        assertNull(estimatedBitrateKbps(sizeBytes = null, durationMs = 240_000L))
        assertNull(estimatedBitrateKbps(sizeBytes = 9_600_000L, durationMs = 0L))
        assertEquals("FLAC · 1 010 kbps · 34.2 MB", fileFormatLabel("01 - Blue Hour.flac", 34_200_000L, 270_890L))
        assertEquals("MP3", fileFormatLabel("song.mp3", null, null))
        assertNull(fileFormatLabel("noextension", 1L, 1L))
        assertNull(fileFormatLabel(null, 1L, 1L))
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew --no-daemon :composeApp:testAndroidHostTest --tests "*PlayerFeelTest*"`
Expected: compilation failure, `Unresolved reference: artworkDragAxis`.

- [x] **Step 3: Write the implementation**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The arithmetic behind the player's gestures.
 *
 * Every threshold the cover, the seek bar and the transport react to lives here as a pure function,
 * so the composables hold no numbers and the decisions are tested on the JVM instead of by hand.
 */
internal enum class ArtworkDragAxis { HORIZONTAL, VERTICAL }

/** Undecided (null) until the finger travels [slop] on an axis; vertical only counts downwards. */
internal fun artworkDragAxis(dx: Float, dy: Float, slop: Float): ArtworkDragAxis? {
    if (abs(dx) < slop && abs(dy) < slop) return null
    return when {
        abs(dy) > abs(dx) -> if (dy > 0f) ArtworkDragAxis.VERTICAL else null
        else -> ArtworkDragAxis.HORIZONTAL
    }
}

/** The cover moves almost with the finger; a direction with no neighbour resists like a rubber band. */
internal fun swipeShown(dx: Float, blocked: Boolean): Float =
    if (blocked) dx * SWIPE_BLOCKED_FOLLOW else dx * SWIPE_FOLLOW

/** A skip commits past 30 % of the cover width, never into a direction with no neighbour. */
internal fun swipeCommits(dx: Float, width: Float, blocked: Boolean): Boolean =
    !blocked && width > 0f && abs(dx) > width * SWIPE_COMMIT_FRACTION

/** Pulling the player down moves it three quarters of the way, so the release still feels light. */
internal fun collapseShown(dy: Float): Float = dy.coerceAtLeast(0f) * COLLAPSE_FOLLOW

internal fun collapseCommits(dy: Float, threshold: Float): Boolean = dy > threshold

/** Sliding the finger below the bar slows scrubbing: half speed past [halfAt], a quarter past [quarterAt]. */
internal fun scrubFineFactor(dyBelowBar: Float, halfAt: Float, quarterAt: Float): Int = when {
    dyBelowBar > quarterAt -> 4
    dyBelowBar > halfAt -> 2
    else -> 1
}

internal fun scrubDeltaMs(dxPx: Float, trackWidthPx: Float, durationMs: Long, fine: Int): Long {
    if (trackWidthPx <= 0f || durationMs <= 0L) return 0L
    return (dxPx / trackWidthPx * durationMs / fine.coerceAtLeast(1)).roundToLong()
}

/** Holding a skip button scans at 4×, doubling every step until 32×. */
internal fun skipHoldMultiplier(heldMs: Long): Int {
    val steps = (heldMs / SKIP_HOLD_STEP_MS).toInt().coerceIn(0, SKIP_HOLD_MAX_STEPS)
    return SKIP_HOLD_BASE shl steps
}

/** Average bitrate in kbps from file size and duration; the tag itself does not carry one. */
internal fun estimatedBitrateKbps(sizeBytes: Long?, durationMs: Long?): Int? {
    if (sizeBytes == null || durationMs == null || sizeBytes <= 0L || durationMs <= 0L) return null
    return (sizeBytes * 8.0 / durationMs).roundToInt()
}

/** `FLAC · 1 010 kbps · 34.2 MB`, or as much of that as the facts allow; null without an extension. */
internal fun fileFormatLabel(fileName: String?, sizeBytes: Long?, durationMs: Long?): String? {
    val extension = fileName?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() && it.length <= 5 } ?: return null
    val parts = mutableListOf(extension.uppercase())
    estimatedBitrateKbps(sizeBytes, durationMs)?.let { parts += "${groupThousands(it)} kbps" }
    sizeBytes?.takeIf { it > 0L }?.let { bytes ->
        val megabytes = bytes / 1_000_000.0
        val rounded = (megabytes * 10).roundToInt() / 10.0
        val whole = rounded.toLong()
        val tenth = ((rounded - whole) * 10).roundToInt()
        parts += "$whole.$tenth MB"
    }
    return parts.joinToString(" · ")
}

private fun groupThousands(value: Int): String {
    val digits = value.toString()
    val out = StringBuilder()
    digits.forEachIndexed { index, char ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(' ')
        out.append(char)
    }
    return out.toString()
}

private const val SWIPE_FOLLOW = 0.92f
private const val SWIPE_BLOCKED_FOLLOW = 0.3f
private const val SWIPE_COMMIT_FRACTION = 0.3f
private const val COLLAPSE_FOLLOW = 0.75f
private const val SKIP_HOLD_STEP_MS = 1_500L
private const val SKIP_HOLD_BASE = 4
private const val SKIP_HOLD_MAX_STEPS = 3
```

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --no-daemon :composeApp:testAndroidHostTest --tests "*PlayerFeelTest*"`
Expected: 7 tests pass.

- [x] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlayerFeel.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/PlayerFeelTest.kt docs/player-feel-plan.md
git commit -m "feat(player): the arithmetic behind the cover, seek and skip gestures, tested"
```

---

### Task 2: One haptics vocabulary

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlayerHaptics.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/PlayerHapticsTest.kt`

**Interfaces:**
- Produces: `enum class PlayerHaptic { TAP, HOLD, THRESHOLD, SUCCESS, REJECT, EDGE, RELEASE }`, `fun PlayerHaptic.feedbackType(): HapticFeedbackType`, `fun HapticFeedback.play(event: PlayerHaptic)`.

- [x] **Step 1: Write the failing test**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerHapticsTest {
    @Test
    fun everyEventMapsToTheIntendedSystemType() {
        assertEquals(HapticFeedbackType.KeyboardTap, PlayerHaptic.TAP.feedbackType())
        assertEquals(HapticFeedbackType.LongPress, PlayerHaptic.HOLD.feedbackType())
        assertEquals(HapticFeedbackType.GestureThresholdActivate, PlayerHaptic.THRESHOLD.feedbackType())
        assertEquals(HapticFeedbackType.Confirm, PlayerHaptic.SUCCESS.feedbackType())
        assertEquals(HapticFeedbackType.Reject, PlayerHaptic.REJECT.feedbackType())
        assertEquals(HapticFeedbackType.SegmentTick, PlayerHaptic.EDGE.feedbackType())
        assertEquals(HapticFeedbackType.GestureEnd, PlayerHaptic.RELEASE.feedbackType())
    }
}
```

- [x] **Step 2: Run it, expect `Unresolved reference: PlayerHaptic`.**

- [x] **Step 3: Implement**

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * What the player says through the vibration motor, named by meaning rather than by waveform.
 *
 * One table keeps every surface consistent: the same press feels the same everywhere, and a
 * platform that lacks a waveform degrades in one place instead of in every call site.
 */
internal enum class PlayerHaptic { TAP, HOLD, THRESHOLD, SUCCESS, REJECT, EDGE, RELEASE }

internal fun PlayerHaptic.feedbackType(): HapticFeedbackType = when (this) {
    PlayerHaptic.TAP -> HapticFeedbackType.KeyboardTap
    PlayerHaptic.HOLD -> HapticFeedbackType.LongPress
    PlayerHaptic.THRESHOLD -> HapticFeedbackType.GestureThresholdActivate
    PlayerHaptic.SUCCESS -> HapticFeedbackType.Confirm
    PlayerHaptic.REJECT -> HapticFeedbackType.Reject
    PlayerHaptic.EDGE -> HapticFeedbackType.SegmentTick
    PlayerHaptic.RELEASE -> HapticFeedbackType.GestureEnd
}

internal fun HapticFeedback.play(event: PlayerHaptic) = performHapticFeedback(event.feedbackType())
```

- [x] **Step 4: Run, expect 1 test passing.**
- [x] **Step 5: Commit** — `feat(player): one haptics vocabulary for the player`.

---

### Task 3: Copy for the player, in all 18 locales

New keys (English source values; translate each into ar, de, es, fr, hi, id, in, it, ja, ko, pl, pt-rBR, ro, ru, tr, uk, zh-rCN):

| key | value |
|---|---|
| `cd_player_artwork` | Cover. Tap for track details, hold for actions, swipe sideways to change track, drag down to close. |
| `details_title` | Track details |
| `details_language` | Language |
| `details_format` | Format |
| `details_file` | File |
| `details_plays` | Plays |
| `details_plays_none` | Not played yet |
| `details_original_year` | First released |
| `cd_details_close` | Back to the cover |
| `player_mode_title` | Playback mode |
| `player_mode_off` | In order |
| `player_mode_shuffle` | Shuffle |
| `player_mode_smart` | SMART |
| `cd_mode_button` | Playback mode: %1$s. Tap to change, hold to choose. |
| `cd_seek_position` | Playback position |
| `cd_time_toggle` | Switch between remaining and total time |
| `seek_fine_half` | ½ speed |
| `seek_fine_quarter` | ¼ speed |
| `cd_skip_next_hold` | Next track. Hold to fast-forward. |
| `cd_skip_previous_hold` | Previous track. Hold to rewind. |
| `output_phone_speaker` | Phone speaker |
| `output_headphones` | Headphones |
| `output_bluetooth` | Bluetooth |
| `cd_output_route` | Playing on %1$s. Tap to choose where to play. |

- [x] Add the keys to `values/strings.xml` and every translation; run `./gradlew --no-daemon :composeApp:testAndroidHostTest --tests "*StringResourceParityTest*"`; commit `feat(app): player copy in 18 locales`.

---

### Task 4: The cover flips, holds, swipes and pulls

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlayerArtwork.kt`
- Modify: `NowPlayingScreen.kt` — replace `LargeArtwork(...)` at the artwork slot with `PlayerArtworkCard`, keep the `sharedElement(ARTWORK_KEY)` on the outer box; add `flipped` state, `detailsRequest: Int` parameter, and the collapse translation on the `Surface`.
- Modify: `App.kt` — `TrackMenuRequest(fromPlayer: Boolean = false)`; when `fromPlayer`, `onInfo` increments `playerDetailsRequest` (Int state) passed to `NowPlayingScreen(detailsRequest = …)` instead of opening `TrackInfoSheet`; the player's `onTrackMenu` passes `fromPlayer = true`.

**Interfaces:**
- Produces:
```kotlin
@Composable
internal fun PlayerArtworkCard(
    track: TrackDescriptor?,
    flipped: Boolean,
    onFlip: (Boolean) -> Unit,
    onHold: () -> Unit,
    onSkip: (forward: Boolean) -> Unit,
    canSkipForward: Boolean,
    canSkipBackward: Boolean,
    /** Called while the finger pulls down (px, resistance already applied) and with 0f on release. */
    onCollapseDrag: (Float) -> Unit,
    onCollapse: () -> Unit,
    details: @Composable () -> Unit,
    modifier: Modifier = Modifier,
)
```
- Gesture rules (from Task 1): slop 12.dp; hold 450 ms only while undecided; tap (release undecided before hold) flips; horizontal drag past `swipeCommits` skips, else springs back; vertical drag reports `collapseShown(dy)` and commits past 140.dp.
- Front face: `AsyncImage` in a 24.dp rounded box with the existing shadow; back face: `details()` content, drawn with `rotationY = 180f` so it reads correctly once the card has turned; `cameraDistance = 12 * density`. Only the face that is closer to the viewer is drawn (`angle <= 90f`). The card's `Animatable` angle animates 0 ↔ 180 with `spring(stiffness = Spring.StiffnessMediumLow)`; Reduce Motion snaps.
- During a hold the card scales to 0.97 over the hold duration and springs back; a 60.dp ring around the touch point fills over the hold (drawn in `drawWithContent` from an `Animatable` started at pointer down, cancelled on move or release).
- Haptics: TAP on flip, HOLD when the actions open, THRESHOLD when the swipe arms, REJECT on a blocked swipe past 40.dp, TAP on a commit, RELEASE when a collapse drag springs back.
- A committed swipe animates the card fully out (`width + 24.dp` in the travel direction, 280 ms), calls `onSkip`, and the card re-enters from the opposite side when `track.id` changes (`AnimatedContent(track, contentKey = id)` with `slideInHorizontally`/`slideOutHorizontally` in the remembered direction; fade-through when the change was not a swipe).

- [x] Implement `PlayerArtworkCard` and the details face slot; wire into `NowPlayingScreen`; build Android and iOS; smoke on the emulator: tap flips, hold opens the actions sheet, swipe changes track, pull closes.
- [x] Commit `feat(player): the cover flips to details, holds for actions, swipes tracks and pulls the player closed`.

---

### Task 5: Track details on the back of the cover

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/TrackDetailsFace.kt`
- Modify: `NowPlayingScreen.kt` — pass `details = { TrackDetailsFace(...) }`.

**Interfaces:**
```kotlin
@Composable
internal fun TrackDetailsFace(
    track: TrackDescriptor,
    stats: TrackStats?,            // from AppGraph.history.stats()[track.id], loaded when first flipped
    onEditTags: () -> Unit,        // opens TrackInfoSheet (existing) — App's infoTarget
    onShowOnMap: (() -> Unit)?,    // null when the Map is not visible
    onClose: () -> Unit,
)
```
- Rows: title (header with close button), `info_artist`, `info_album` (or `info_not_set`), `info_genre`, `info_year` (+ `details_original_year` row when `originalYear != null && originalYear != year`), `details_language` (`track.language` or `info_not_set`), `info_duration` (`formatDuration`), `details_format` (`fileFormatLabel(track.fileName, track.sizeBytes, track.durationMs)`), `details_file` (`track.fileName ?: track.audioUri`), `details_plays` (`stats?.plays` or `details_plays_none`). Buttons at the bottom: `info_edit` → `onEditTags`, `action_show_on_map` → `onShowOnMap` when present.
- `stats` is read once per flip in `NowPlayingScreen`: `LaunchedEffect(flipped, track.id) { if (flipped) stats = AppGraph.history.stats()[track.id] }`. It is not observed continuously.
- New parameters on `NowPlayingScreen`: `onEditTags: (TrackDescriptor) -> Unit`, `onShowOnMap: ((TrackDescriptor) -> Unit)?`; App passes `{ infoTarget = it }` and the same map-focus lambda the actions sheet uses (extract it into a local `fun showOnMap(track)` so both share it).

- [x] Implement, build, smoke (flip shows rows, Edit tags opens the editor), commit `feat(player): track details on the back of the cover`.

---

### Task 6: A live seek bar

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlayerSeekBar.kt`
- Modify: `NowPlayingScreen.kt` — replace `PlaybackSeekBar` with `PlayerSeekBar(playback, durationMs, lyrics)`.

**Interfaces:**
```kotlin
@Composable
internal fun PlayerSeekBar(playback: PlaybackController, durationMs: Long, lyrics: Lyrics?)
```
- Only this composable observes `positionMs` (as `PlaybackSeekBar` does today).
- Drawn with `Canvas`: 4.dp remaining line at 22 % of `onSurface`, played line in `onSurface`, a 4×26.dp bar handle; while dragging the lines are 6.dp and the handle 34.dp (`animateDpAsState`, `Motion.QUICK_MS`).
- Touch: pointer down anywhere in the 44.dp band puts the handle there (TAP haptic) and starts a drag; per move `scrubDeltaMs(dx, width, duration, scrubFineFactor(dyBelowBar, 40.dp, 90.dp))`; EDGE haptic once per edge reached; release seeks (`playback.seekTo`) with RELEASE haptic.
- Bubble above the handle while dragging: time, `seek_fine_half`/`seek_fine_quarter` when fine > 1, and the lyric line at that time when `lyrics?.synced == true` (`LyricsTimeline` from `LyricsFollowing.kt`).
- Times row: elapsed on the left; the right label is a `TextButton`-less clickable text toggling between total and `−remaining` (`cd_time_toggle`, TAP haptic).
- Semantics: `progressBarRangeInfo` + `cd_seek_position`, `setProgress` action seeks.

- [x] Implement, build, smoke (tap, drag, fine mode, bubble, remaining toggle), commit `feat(player): a thin live seek bar with fine scrubbing and a time bubble`.

---

### Task 7: Transport with feel

**Files:**
- Modify: `NowPlayingScreen.kt` — transport row.
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlayerModeMenu.kt`

- Play/pause: keep `FilledIconButton` semantics; shape animates between a circle (paused) and a 24.dp-radius square (playing) via `animateDpAsState` on `RoundedCornerShape`; `scaleOnPress` on every transport button (existing modifier, `interactionSource` passed to the button).
- Skip buttons: `pointerInput` with `detectTapGestures(onPress = …)`: release before 420 ms → `next()`/`previous()` (TAP); holding past 420 ms starts scanning (HOLD): every 250 ms `playback.seekTo(position + 250 * skipHoldMultiplier(heldMs) * direction)` clamped to `[0, duration]` (EDGE once at an edge); release stops (RELEASE). Content descriptions `cd_skip_next_hold`/`cd_skip_previous_hold`. Position for the scan is read from `playback.state.value.positionMs` at hold start and advanced locally, so nothing observes the ticker.
- Mode button (replaces `ShuffleButton` in place, same slot): icon `Icons.Rounded.Shuffle` for OFF/ON, `LatentJamMark` for SMART, tinted as today; tap cycles (`cycleShuffleMode`, TAP); hold (450 ms, HOLD) opens `PlayerModeMenu`: a `DropdownMenu` anchored to the button with three rows (`player_mode_off`/`player_mode_shuffle`/`player_mode_smart`, the current one ticked → `setShuffleMode`) and a `intelligence_queue_length` row with 10 · 20 · 40 chips (`settings.setSmartQueueLength`, using `SMART_QUEUE_LENGTH_OPTIONS`). `cd_mode_button` with the current mode name.
- `NowPlayingScreen` gains `smartQueueLength: Int` and `onSmartQueueLength: (Int) -> Unit` parameters; App passes `smartQueueLength` and `settings::setSmartQueueLength` (the same setter Settings uses).

- [x] Implement, build, smoke (morph, hold-scan, mode menu), commit `feat(player): play button morphs, skip buttons scan on hold, the mode button chooses on hold`.

---

### Task 8: ⋮ goes straight to actions; artist and album are links; the source is quiet

**Files:**
- Modify: `App.kt` — `OverflowButton` gains `onClick: (() -> Unit)? = null` (no dropdown when set); player wiring passes `onTrackMenu = { trackMenuRequest = TrackMenuRequest(it, fromPlayer = true) }` and `onSleepTimer` into the sheet only for `fromPlayer` requests; `showAlbumOf`/`showArtistOf` are passed to the player as `onGoToAlbum`/`onGoToArtist` (they already close nothing — add `showNowPlaying = false` before opening the collection).
- Modify: `TrackActionsSheet.kt` — new optional `onSleepTimer: (() -> Unit)?` row (`Icons.Rounded.Bedtime`, `sleep_timer`, shows the countdown subtitle as the dropdown did) placed after `action_information`.
- Modify: `NowPlayingScreen.kt` — the ⋮ button calls `onTrackMenu(track)` directly; the sleep-timer dialog state moves behind the sheet's row (`showSleepTimer` is set by App through a new `sleepTimerRequest: Int` parameter, mirroring `detailsRequest`); artist and album become `TextButton`-styled links (`showAlbumOf`/`showArtistOf`), album omitted when blank; the "Playing from" label becomes a borderless chip with a queue icon (`Icons.AutoMirrored.Rounded.QueueMusic`) that expands the queue sheet (`sheetState.bottomSheetState.expand()`).

- [x] Implement, build, smoke, commit `feat(player): actions in one tap from ⋮, sleep timer inside them, artist and album as links`.

---

### Task 9: The colour cloud drifts and breathes

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/PlayerCloud.kt`
- Modify: `NowPlayingScreen.kt` — replace the vertical gradient `background(...)` with `Modifier.playerCloud(accent, isPlaying)`.

**Interfaces:**
```kotlin
@Composable
internal fun Modifier.playerCloud(accent: TrackAccent, playing: Boolean): Modifier
```
- Three radial-gradient discs (`Brush.radialGradient` with the colour fading to transparent) at 300/260/240 dp, colours `accent.container` and a hue-rotated companion (`hueShift(accent.container, 28f)`), overall alpha 0.6 (the value the user chose on the canvas).
- Phase: one `mutableFloatStateOf` advanced by a `LaunchedEffect(playing, reduceMotion)` loop of `withFrameNanos` that writes at most every 3rd frame (~20 fps) and does nothing while paused or under Reduce Motion (the cloud freezes where it is). Centres move on slow sine paths (periods 19/23/27 s); while playing the whole group scales 1 → 1.06 → 0.98 on a 0.86 s cycle (the breath).
- Read only inside `drawBehind`. No composition reads the phase. Cost target: 3 gradient fills per drawn frame, ≤ 20 draws per second, zero when paused.

- [x] Implement, build, smoke (drift while playing, still on pause), commit `perf(player): a drifting colour cloud that costs nothing while paused`.

---

## Part B — where the sound goes

### Task 10: Output route status

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AudioOutputRoute.kt` (expect), `AudioOutputRoute.android.kt`, `AudioOutputRoute.ios.kt`.
- Modify: `NowPlayingScreen.kt` — status button left of the spacer in the top bar.

**Interfaces:**
```kotlin
internal enum class AudioOutputKind { SPEAKER, WIRED, BLUETOOTH, OTHER }
internal data class AudioOutputRoute(val kind: AudioOutputKind, val name: String?)
@Composable internal expect fun rememberAudioOutputRoute(): AudioOutputRoute?
internal expect fun openAudioOutputChooser()   // Android: Settings.ACTION_BLUETOOTH_SETTINGS; iOS: no-op (status only)
```
- Android: `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` preferring Bluetooth A2DP/BLE, then wired headset/headphones/USB, else built-in speaker; `registerAudioDeviceCallback` on the main handler updates a `mutableStateOf`; `productName` for the label.
- iOS: `AVAudioSession.sharedInstance().currentRoute.outputs.firstOrNull()` mapped by `portType`; `AVAudioSessionRouteChangeNotification` observer updates the state.
- UI: `output_headphones` icon (`Icons.Rounded.Headphones`) or speaker (`Icons.Rounded.Speaker`), the route name or the generic string, `cd_output_route`, TAP haptic + `openAudioOutputChooser()`.

- [x] Implement, build both platforms, smoke on the emulator (speaker) and, when the phone is connected, with Bluetooth headphones; commit `feat(player): where the sound is going, in the top bar`.

---

## Part C — fades

### Task 11: Pause and resume fade

**Files:**
- Create: `core/playback/src/commonMain/kotlin/io/github/nikitasud/latentjam/playback/TransportFade.kt`
- Test: `core/playback/src/commonTest/kotlin/io/github/nikitasud/latentjam/playback/TransportFadeTest.kt`
- Modify: `PlaybackController.android.kt` — `applyEffectiveVolume` multiplies a transport fade factor; `pause()`/`togglePlayPause()` start a 160 ms fade-out and pause when it lands; `play` starts at 0 and fades in over 220 ms; a play during a fade-out cancels it. `pushState()` reflects the target immediately.
- Modify: `PlaybackController.ios.kt` / `IosAudioEngine.kt` — same shape on `engine.mainMixerNode.outputVolume`.

**Interfaces:**
```kotlin
public const val TRANSPORT_FADE_OUT_MS: Long = 160
public const val TRANSPORT_FADE_IN_MS: Long = 220
/** 1 → 0 over [durationMs] for a fade-out, 0 → 1 for a fade-in; equal-power curve so the middle does not dip. */
public fun transportFadeFactor(elapsedMs: Long, durationMs: Long, fadingOut: Boolean): Float
```

- [x] Tests: factor is 1 at start of a fade-out and 0 at its end, 0 → 1 for a fade-in, clamps past the end, `durationMs <= 0` returns the terminal value; then implement; run `./gradlew --no-daemon :core:playback:testAndroidHostTest`; build both platforms; smoke by ear on the phone; commit `feat(playback): pause and resume fade instead of cutting`.

---

## Self-review

- Spec coverage: cover gestures (4), details (5), seek bar (6), transport and mode menu (7), ⋮ and links and source chip (8), cloud (9), output (10), fades (11), haptics everywhere (2), strings (3). The mini-player flick and shared-element morph already exist and stay.
- Performance rules restated in Global Constraints; each UI task names where its per-frame state lives.
- Type consistency: `PlayerHaptic`, `ArtworkDragAxis`, `AudioOutputRoute` and the `NowPlayingScreen` parameters (`detailsRequest`, `sleepTimerRequest`, `smartQueueLength`, `onSmartQueueLength`, `onGoToAlbum`, `onGoToArtist`, `onEditTags`, `onShowOnMap`) are named identically across tasks.
