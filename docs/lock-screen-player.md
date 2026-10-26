# Player on the lock screen (Android)

An optional full-screen LatentJam player shown above the keyguard after the screen turns off, next
to the system media card that the MediaStyle notification already gives every Android version.
Setting: Settings → Handling → "Player on the lock screen", off by default.

## Can a third-party app open it on screen-off? (measured 2026-10-26)

Probe: a dynamic `ACTION_SCREEN_OFF` receiver in the app process (the `mediaPlayback` foreground
service running) starts a `setShowWhenLocked(true)` activity with
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION`. Emulator Pixel 7 Pro image, API 36
(Android 16), targetSdk 36. The verdict is system_server's `START … (BAL_*)` log line.

| Path | Needs | Result | Screen stays off |
|---|---|---|---|
| App in the background, only the media foreground service | nothing | `BAL_BLOCK`, refused silently (`startActivity` does not throw) | — |
| LatentJam (or its lock player) on screen when the screen goes off | nothing | `BAL_ALLOW_FOREGROUND`; on wake the player sits above a secure PIN keyguard | yes |
| App in the background with "Display over other apps" | `SYSTEM_ALERT_WINDOW` in the manifest plus a manual toggle in system settings | `BAL_ALLOW_SAW_PERMISSION` | yes |
| Full-screen-intent notification from the background | `USE_FULL_SCREEN_INTENT` (revoked by Play for non-call/alarm apps; user-revocable on 14+) | opens | **no**: the screen turns back on about 4 s after the user switched it off |

A media foreground service is not a background-activity-start exemption on Android 10+. Samsung
Music's `LockActivity` works from anywhere because it holds the privileged
`START_ACTIVITIES_FROM_BACKGROUND` (see below). The full-screen
intent is rejected because it re-wakes the screen on every screen-off (battery, pocket touches) and
leaves a heads-up "call" notification behind.

One UI on the Galaxy S24 Ultra (Android 16) runs the AOSP BAL rules, so the same table is expected
there; it was not measured on that phone.

## Why Samsung Music can and we cannot (owner's manifest check, 2026-10-26)

Samsung Music 16.2.45.1 declares 57 permissions and **no** `SYSTEM_ALERT_WINDOW`; its code has no
`canDrawOverlays`, no `TYPE_APPLICATION_OVERLAY` / `TYPE_SYSTEM_ALERT` windows and no special-access
flow. `LockActivity` is started with `NEW_TASK | NO_ANIMATION` from a screen-off coroutine, and that
start is allowed because the app holds `START_ACTIVITIES_FROM_BACKGROUND`, a signature/privileged
permission. It reaches the lock screen through `setShowWhenLocked(true)`, not an overlay, and the
`mediaPlayback` foreground service keeps it alive. Its other permissions (`INTERACT_ACROSS_USERS_FULL`,
`INJECT_EVENTS`, `INTERNAL_SYSTEM_WINDOW`, `MANAGE_ACTIVITY_STACKS`, `STATUS_BAR`,
`WRITE_SECURE_SETTINGS`, `DEVICE_POWER`, …) mark it as a privileged system app.

So a third-party app cannot reproduce Samsung's background launch as is. The nearest third-party
equivalent is the "Display over other apps" grant (`SYSTEM_ALERT_WINDOW`): we would not draw an
overlay with it, only use its background-activity-start exemption (`BAL_ALLOW_SAW_PERMISSION`,
measured above), with the same `setShowWhenLocked` activity. The difference from Samsung: the
listener must turn the special access on by hand in system settings.

## What ships

The no-permission path: the player opens when LatentJam itself was on screen at screen-off, and
again at every later screen-off while the lock player is the top screen. When another app was in
front, Android refuses the start silently and the system media card remains the lock-screen
player. Owner's decision (2026-10-26): ship this path only, as an option off by default; no
`SYSTEM_ALERT_WINDOW`, no full-screen intent, no new permission of any kind, because Samsung's own
mechanism (a privileged background start) is not available to a third-party app. The setting's
description says plainly that the player appears when LatentJam was open as the screen went off.

- `LockPlayerTrigger` (commonMain, tested): opens once per screen-off, only when the setting is on
  and music plays, never on top of a showing player; the guard clears when the screen comes on.
- `installLockScreenPlayer` (Android): the screen on/off receiver exists only while the setting is on.
- `LockScreenPlayerActivity`: its own task, singleInstance, excluded from Recents, translucent
  window over the keyguard, edge to edge with transparent bars and the short-edge cutout. It renders
  `NowPlayingScreen` through `LockScreenPlayer` with the app's theme, against the same
  `PlaybackController` as the app, so it always agrees with the notification. It closes on unlock
  (`ACTION_USER_PRESENT`, which System UI sends, so its receivers are exported) and when the
  player empties (no track).
- When it leaves (`lockPlayerShouldLeave`, commonMain, tested): while the screen is off it stays
  only as long as playback is meant to run (`lockPlayerKeepsWhileAsleep`: a track and the pause
  button showing, so a buffering blip does not close it). A pause, the sleep timer, the end of the
  queue, a focus loss or unplugged headphones while the screen is off close it, and so does the
  next screen-off after a pause, so the next wake shows the plain lock screen with the system
  media card. A pause pressed on the lock player with the screen on keeps it until that screen-off.
  A stop while the screen is off counts only once it has lasted 5 s (`lockPlayerLeavesAfterGrace`):
  a SMART queue that ran dry passes through an ended state until its next rows are planned.
- No keyguard, no player: when it starts or resumes with the screen on and
  `KeyguardManager.isKeyguardLocked` false (lock "None", or a wake within the "lock after N s"
  grace, where no unlock broadcast comes), it leaves rather than stay as an ordinary screen on an
  unlocked phone. The keyguard counts as locked while this player hides it, so the normal path is
  unaffected. With lock "None" its first frame may still flash. Such a wake also clears a
  swipe-away, as an unlock would.
- The lock surface (`PlayerSurface.LOCK_SCREEN`, every entry point listed in `PlayerEntry`) is
  Samsung's rule: the same content, minus anything that opens a dialog, sheet, picker or another
  screen. Kept: previous/play-pause/next, mode (tap cycles), repeat, favourite, the queue page
  (play, reorder, remove), lyrics, and the seek bar. Samsung drops the seek bar; ours stays on
  purpose, since it opens nothing. Left out: the overflow and track sheet (share, sleep timer,
  information live there), the cover's hold menu and its back face (track info, tag editing, map,
  equaliser), the mode button's hold menu, queue row menus, add-to-playlist, the audio output
  chooser (the route stays as plain text), and the artist/album links and "Playing from" chip
  (plain text).
- Ways out: Close and Back ask for the unlock and leave. The small LatentJam button asks for the
  unlock and then opens the full player in the app. A swipe down returns to the plain lock screen
  without unlocking; a swiped-away player stays away until the next unlock (otherwise Android's
  ~10 s grace period after it closes would bring it back on the next screen-off, and later it
  would not).

## iOS

iOS gives apps no way to draw their own UI on the lock screen; the system Now Playing controls
(fed by `MPNowPlayingInfoCenter`) are the only lock-screen player there. The setting is hidden on
iOS (`lockScreenPlayerAvailable = false`).
