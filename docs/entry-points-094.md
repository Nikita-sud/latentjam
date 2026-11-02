# Entry points from outside the app — audit for 0.9.4

Status: READY (2026-11-01): plan reviewed (R1, R2); F1 and F2 implemented, verified on emulators, final review R3 READY. API 31–35 run on 2026-11-02 (new gap G3, not fixed here). Branch `claude/entry-points-094` from `dev/0.9.4` (1730aa24).

Scope: every way Android, iOS, other apps, devices or the user reach LatentJam without going
through its own screens, checked against these app states:

- **FG**: the app is on screen.
- **BG**: in the background, playing or paused.
- **Dead**: the process is gone and no service runs, as after the system reclaims a paused app.
- **FS**: force-stopped.
- **Fresh**: just installed, nothing played.
- **Boot**: after a reboot, before the app is opened.
- **Lock**: device locked.
- **Perm**: media permission denied or revoked.
- **Doze/low memory**.

The inventory comes from the code and the merged debug manifest
(`androidApp/build/intermediates/merged_manifests/debug/…/AndroidManifest.xml`), not from memory.

## How it was checked

All emulators ran with `-no-audio`, media volume 0 and generated silent MP3s (`anullsrc`), so
nothing was audible on the Mac. None of the test data comes from the owner's library.

| AVD | API | Notes |
|---|---|---|
| LJ-ep37 (new, own) | 37 | google_apis; Android 15+ audio-focus hardening applies |
| LJ-demo (`-read-only`) | 36.1 | Play Store image; demo library and three widgets already placed |
| LJ-api30 (`-read-only`) | 30 | 3.5k-track generated library |

LJ-api37-lock was left alone (another session's device). API 24, 28, 29 and the API 37 tile rows
come from the lockscreen session's measurements (`docs/tile-cold-start-094-plan.md` on
`claude/lockscreen-snackbar-094-4970f3`). API 31–35 were run on 2026-11-02, once those images were
installed; see "API 31–35 run" below. Those rows carry **E 31–35**.

"Dead" was reached without force-stop: pause → force-stop → open the app once (restores paused,
binds the service without starting it) → Home → `am kill` → `pidof` empty and no ServiceRecord.

Evidence codes: **E** = emulator run on the stated API, **C** = read in code (file:line),
**L** = measured by the lockscreen session, **I** = inferred, not run.

## Android inventory

Exported components in the merged manifest:

- `MainActivity`: LAUNCHER, `APP_MUSIC`, and VIEW for `content`/`file` with `audio/*`,
  `application/ogg`, `application/x-flac`, `vnd.android.cursor.item/audio`.
- `PlaybackService`: Media3 session, library and legacy browser, plus `MEDIA_PLAY_FROM_SEARCH`.
- `PlaybackTileService`: the Quick Settings tile.
- Media3 `MediaButtonReceiver`.
- Library components only: WorkManager `SystemJobService` and `DiagnosticsReceiver` (DUMP
  permission), and `ProfileInstallReceiver`.

Non-exported:

- the three widget providers and `PlaybackWidgetActionReceiver`;
- `IndexingService`;
- `LockScreenPlayerActivity`;
- WorkManager's own receivers, which are disabled by default. Its `RescheduleReceiver`
  (BOOT_COMPLETED) is disabled, and `RECEIVE_BOOT_COMPLETED` is removed in the manifest.

Not present at all (grep and manifest):

- app shortcuts;
- a SEND/share target;
- a FileProvider;
- BOOT_COMPLETED, MY_PACKAGE_REPLACED or LOCALE_CHANGED receivers;
- MediaStore `ContentObserver`;
- Bluetooth/A2DP connect receivers;
- `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`;
- cast / MediaRouteProvider;
- Wear OS.

Auto Backup is off (`allowBackup=false`, `fullBackupContent=false`, data-extraction rules).

## Matrix — Android

| # | Entry point | State | Expected | Current | Ev. |
|---|---|---|---|---|---|
| A1 | Headset/BT PLAY (`input keyevent 126`) | Dead, saved queue | plays the saved queue | PLAYING, FGS 1001, focus granted | E 37, E 30, E 31–35 |
| A1 | | Boot, app never opened | plays | System UI binds the service for its resumption card; key → PLAYING | E 36 |
| A1 | | Dead, **no** saved queue | nothing, no crash | `ForegroundServiceDidNotStartInTime` crash at 30 s | L 37 — **open on `dev/0.9.4` until the lockscreen branch merges** (its media-button promotion fixes it); with that branch merged: no crash, FGS gone after 35 s, E 31–35 |
| A1 | | Perm revoked, System UI holds the service | nothing, or a hint | silent no-op (`onPlaybackResumption` throws, `PlaybackService.kt:353`); no crash. **G3:** if the service was foreground when access was revoked, it restarts foreground and a PLAY leaves an empty foreground service running (≥ 13 min measured) | E 36, E 31–35 |
| A1 | | Perm revoked, Dead and nothing bound | nothing, no crash | same throw as "no saved queue", so the same 30 s crash path | I (same code path as the L 37 row) — **open until the lockscreen branch merges** |
| A1 | | FS | nothing (platform: receivers of a stopped app are not called) | nothing | I (platform rule) |
| A2 | PLAY_PAUSE from an untrusted dispatcher (Google app on Samsung) | BG | toggles | works since f9f43fa (transport-only surface) | C `PlaybackService.kt:100-121` |
| A3 | `cmd media_session dispatch` (shell, no package) | any | dropped, no crash | dropped; black box "rejected: controller without a package name". On 33–35 the shell dispatch carries `com.android.shell` and plays like any media key; on 31–32 nothing reached the black box | E 30, E 31–35, C `NamelessControllerGuard.kt` |
| A4 | Media notification transport | FG, BG | works | works | E 36/37 |
| A4 | | POST_NOTIFICATIONS denied (API 33+) | playback and notification still work | FGS + media notification present (media sessions are exempt) | E 37, E 33–35 |
| A5 | Lock-screen media controls (keyguard) | Lock | work | the same session and notification | L 37 (tile from the locked shade) |
| A6 | System UI resumption card (API 30+) | Dead / Boot | shows the last track, Play resumes | Media3 answers the recent root itself before our `onGetLibraryRoot` (`MediaLibrarySessionImpl.onGetLibraryRootOnHandler`, `isRecent && isSystemUiController`); after boot System UI bound the service | C (Media3 1.10.1 bytecode), E 36 |
| A7 | Quick Settings tile | Dead | plays | **does nothing** (issue #29 report) | L — **known row; fixed on `claude/lockscreen-snackbar-094-4970f3` (unmerged, uncommitted there), not redone here; open on `dev/0.9.4` until it merges** |
| A7 | | BG, Lock, no queue | toggles / opens the app | after that fix: plays/pauses; no queue → opens the app | L 24/29/30/36/37, E 34 (with 478c8b89) |
| A7 | | Dead, API 34, no overlay permission | plays | 478c8b89: the tile's FGS start is refused (`Disallowed … code:DENIED`), the app opens with a play request and starts playback from the foreground (`Allowed uidState: TOP`) | L 34, E 34 |
| A8 | Widget Play (Pulse) | Dead, saved queue | plays | PLAYING, FGS, focus granted (`Background started FGS: Allowed … allowWiu`; on 31–35 `Allowed uidState: RCVR code:ACTIVITY_STARTER`) | E 36, E 37, E 31–35 |
| A8 | | BG paused | plays | plays | E 37, E 31–35 |
| A8 | | Boot | shows the stored track as paused (no ticking clock) | as expected (`capturedBootCount`) | C `PlaybackWidgetState.kt:77-85`, E 36 (screenshot) |
| A8 | | FS | first tap is lost (platform cancels a stopped app's PendingIntents); the next tap plays | first tap only re-renders the widget; second tap → PLAYING | E 36 |
| A8 | | Fresh, nothing played | buttons disabled, "Tap to choose your sound" | as expected | C `PlaybackWidgetProvider.kt:655-657`, E 37 (picker preview) |
| A8 | | **Perm revoked** / library gone | Play leads somewhere the user can fix it | **GAP G2:** the widget keeps showing the track with an enabled Play; the tap is a silent no-op. **After F2:** the tap opens the permission gate, also after "Not now"; after a grant the controls act again | E 36 (gap), E 37 and E 31–35 (fix) |
| A8 | | App updated (package replaced) | widgets keep working | the process is killed; the next process start re-renders (`LatentJamApplication.kt:39`) and the launcher re-sends `APPWIDGET_UPDATE` | C |
| A8b | Media notification body tap | any | opens the existing task | `setSessionActivity` with CLEAR_TOP/SINGLE_TOP (`PlaybackService.kt:811, 1126-1134`) | C |
| A8c | OEM output panel volume slider | BG | moves the volume | untrusted callers get the device-volume commands (`PlaybackService.kt:98-118`) | C |
| A8d | Launcher icon / `APP_MUSIC` (Android Auto's and OEM "music app" shortcuts) | any | opens the app; launch restore runs | MAIN/LAUNCHER and MAIN/APP_MUSIC on `MainActivity` | C, E 36 (`query-activities -c APP_MUSIC`) |
| A9 | Open an audio file from a file manager (VIEW) | FG/BG/Dead | plays the matching library track | plays (chooser → LatentJam → PLAYING) | E 30, E 31–35 (explicit component) |
| A9 | | file not in the library / hidden / unreadable | says it cannot open it | "unavailable" (`ExternalAudioOpen.kt:99-102`) | C |
| A9 | | Perm denied, gate dismissed ("Not now", `MainActivity.kt:96-101`: the app runs with an empty library) | unavailable | unavailable even when the sender granted the URI | C (by design: "never creates a track from an arbitrary URI") |
| A10 | **Open an `.m3u` from a file manager** | any | import it as a playlist (the in-app importer exists) | **GAP G1:** LatentJam is offered for `audio/x-mpegurl` and `audio/mpegurl` because the filter says `audio/*`; the activity then treats the playlist as a track and drops it as "unavailable". **After F1:** the import sheet ("Found 2 of 2 tracks"), cold and warm | E 36 (gap), E 36/37/30 and E 31–35 (fix) |
| A11 | Android Auto browse/search/play | car | browse tree, search, play | implemented (`PlaybackService.kt:195-345`); trust comes from the browser bind | C only — **DHU not run**: it needs the Android Auto app and a Google sign-in on the emulator |
| A12 | Voice "play X on LatentJam" (Gemini) | any | plays | never reaches the app (Google side, measured 2026-09-24); transport voice commands work | memory `latentjam-voice-assistant-trust` |
| A13 | `MEDIA_PLAY_FROM_SEARCH` as an **activity** intent | any | other assistants and car launchers use it | not declared on any activity (only on the service, where the framework never sends it) | E 36 (`query-activities` lists only YouTube apps) |
| A14 | Audio focus loss / becoming noisy | BG | pause; transient → resume | ExoPlayer (`handleAudioFocus=true`, `setHandleAudioBecomingNoisy(true)`); black box logs causes | C `PlaybackService.kt:752-753` |
| A15 | Swipe from Recents | playing / paused | keeps playing / stops | Media3 default `onTaskRemoved` (+ widget freeze) | E 30, C `PlaybackService.kt:867` |
| A16 | Process killed by the system while a service is started | BG | service comes back idle, next control resumes | sticky restart observed; next key → PLAYING | E 36 |
| A17 | Media permission revoked while running | running | process killed by Android; next open shows the gate | as expected | C `MainActivity.kt:149`, E 36 |
| A18 | Library analysis FGS (`IndexingService`) | BG start on 12+ | must not throw | on `dev/0.9.4`, `startForegroundService` rethrows (`IndexingNotifier.android.kt:46-59`). On the analysis branch (`claude/analysis-094-939dfe`, B1) `AnalysisForegroundGate` catches a refused start (`AnalysisForegroundGate.kt:57-58, 123, 133`) | C — **analysis session's file**, already handled there |
| A19 | Lock-screen player (`LockScreenPlayerActivity`) | screen off from another app | opens over the keyguard | needs SAW since 10 (BAL) | L — **lockscreen session**, done on its branch |

## Matrix — iOS

Simulator runs were not attempted: `simctl` cannot press remote commands or relaunch the app for
one, and every interesting iOS case below is about remote commands or background launch. The
rows are code reads (C) and say so; the device checks they need are listed.

| # | Entry point | State | Expected | Current | Ev. |
|---|---|---|---|---|---|
| I1 | Control Center / Lock Screen / AirPods (MPRemoteCommandCenter: play, pause, toggle, next, previous, seek, repeat, shuffle) | FG/BG | work | wired in `PlaybackController.ios.kt:1985-2041` | C |
| I1 | | Background-launched for a remote command after termination | resumes the saved queue | queue restore runs only inside the Compose `PlaybackRestoreEffect` (`AppPlayback.kt:34-108`); a launch without UI has an empty queue and the handlers return `.success` while doing nothing (`PlaybackController.ios.kt:762, 848`) | C — **needs a device check** (does iOS relaunch LatentJam for a remote command at all?) |
| I2 | Now Playing info | BG | title, artist, artwork, position | published on track/state change, cleared with no track (`:2239-2265`) | C |
| I3 | Interruption (call, Siri) | BG playing | pause; resume if `shouldResume` | file backend: yes (`:1792-1820`); Music-library backend: pauses, no resume path (`:1895-1901`) | C, I for the Music-library half |
| I4 | Route change (headphones out) | BG playing | pause | file backend pauses (`:1822-1835`) | C |
| I5 | Media services reset | any | recover | rebuilds the engine, stays paused (`:1731-1752`) | C |
| I6 | Files app ("On My iPhone") | any | files appear in the library | `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`; scanned at the next library load | C `Info.plist:70-73` |
| I7 | Background modes | BG | audio keeps playing; BGTask map rebuild on power | `audio`, `processing`; `registerMapRebuildTask` | C `Info.plist:86-90, 93-96`, `iosApp.swift:18` |
| I8 | Music-library permission denied | any | device source skipped | skipped (`AppPermissions.ios.kt:59-63`) | C |

## Confirmed gaps and what happens to each

| Gap | Owner | Decision |
|---|---|---|
| **G1** M3U opened from a file manager is dropped as "unavailable" | this branch | **Fix F1** |
| **G2** widget Play is a silent no-op when the media permission is gone | this branch | **Fix F2** |
| A7 tile does nothing on a dead process (#29) | lockscreen session | fixed on its branch (uncommitted there at audit time); not redone; open on `dev/0.9.4` until merged |
| A1 headset PLAY with no saved queue, or with the permission revoked, crashes after 30 s | lockscreen session | fixed by its media-button promotion; open until merged |
| A18 `IndexingNotifier.show` rethrows a background FGS-start failure | analysis session | already handled on its branch (`AnalysisForegroundGate`); not touched here |
| I1 iOS remote command after a background relaunch finds no queue | owner (device check first) | not fixed: unverified that iOS relaunches the app at all; a blind fix would move queue restore out of Compose |
| I3 iOS Music-library tracks never resume after an interruption | owner (device check) | not fixed: inferred only |
| **G3** media access revoked while the service is foreground (playing, or paused within Media3's 10-minute window) → the restarted service comes back foreground with an empty player, and a headset PLAY then keeps an invisible foreground service alive (state STOPPED, no notification shown, ≥ 13 min) | whoever owns the media-button path in `PlaybackService` (the lockscreen session's promotion code); found 2026-11-02 | not fixed: same on `dev/0.9.4` + F1/F2 and with 478c8b89 merged; no crash and no sound, the cost is a kept process. The fix belongs next to the promotion code, so it is not done on this branch |

## Fix plan (v2, after R1)

### F1 — an M3U opened from outside becomes the existing import offer

Today `MainActivity` hands every VIEW to `AndroidExternalAudioOpener`, which only knows tracks.

- **Type.** `MainActivity` passes `intent.resolveType(contentResolver)`: the explicit type, or the
  provider's type for a content URI.
- **Classification.** A pure `isPlaylistRequest(type, uri)` in commonMain:
  - a playlist type (`audio/x-mpegurl`, `audio/mpegurl`, `application/x-mpegurl`,
    `application/vnd.apple.mpegurl`) decides;
  - any other specific audio type means a track;
  - otherwise the name decides (`.m3u` / `.m3u8`, query and fragment ignored).
- **Manifest.** The VIEW filter adds `application/x-mpegurl` and `application/vnd.apple.mpegurl`,
  because the two application types do not fall under `audio/*`. `text/plain` and
  `application/octet-stream` stay out: they would offer LatentJam for every unknown file.
- **A separate flow, not the track request (R1-1).**
  - A playlist is read at once, in the opener ViewModel's scope, while the activity that received
    the grant is alive.
  - The reader is the in-app importer's, extracted as `readExternalPlaylist`: `ImportReadBudget`
    with `textOnly = true`, so a binary file named `.m3u` is refused after its first bytes.
  - The result (Success or Failure) goes to a new `ExternalPlaylistOpener.pendingPlaylist` flow. It
    never touches `pending` or the SavedStateHandle, never calls `onBegin`, and never blocks the
    launch restore. It is not subject to the "user picked a song since" revision check.
  - It is held in memory only. If the process dies between the tap and the read, the import is
    lost and the user opens the file again. Re-reading after process death would depend on a
    grant that may be gone.
- **App.**
  - `App` collects `pendingPlaylist` and waits until the catalog exists.
  - It claims the result once (`consumePlaylist`, compare-and-set) and runs `offerM3uImport`: the
    code that the in-app picker's `onImportResult` used, now shared by both. That gives the failure
    snackbar or `sheets.m3uImportOffer`.
  - Nothing is created without the user's choice in the sheet.
  - A picker import running at the same time replaces the offer; the last one wins, as two picker
    imports do today.
- **Path matching** is the importer's own (`matchM3uEntries`: path suffix and file name, never
  relative to the playlist's folder), so relative entries work as well as they do from the picker.
- **Gate dismissed** (empty library): the user gets the "import failed" snackbar. That is correct:
  nothing in the file can be found.
- Tests:
  - commonTest `ExternalPlaylistRequestTest`: types, case, parameters, extensions, query and
    fragment, stated track types;
  - androidHostTest `ExternalAudioOpenTest`:
    - a playlist is read and offered, never queued or saved, and claimed once;
    - an unreadable one becomes a Failure;
    - a track keeps the track path;
  - `MainActivityManifestTest`: the two application types are in the VIEW filter.
- Emulator:
  - `audio/x-mpegurl` content URI, cold and warm → import sheet;
  - an `.m3u8` sent as `application/vnd.apple.mpegurl` → import sheet;
  - a `file://` .m3u on API 30 (scoped storage) → import sheet or the failure snackbar, never
    silence;
  - a normal audio file still plays;
  - the saved queue is still restored after a cold M3U open.

### F2 — widget controls lead to the permission gate when the media permission is gone

- **Target.** A pure `widgetControlTarget(sdkInt, isGranted)` → `RECEIVER` | `APP`. It checks the
  permission for the API level (`READ_MEDIA_AUDIO` from 33, `READ_EXTERNAL_STORAGE` below), and is
  host-tested.
- **Rendering.** `widgetAction` (all five controls: Previous, Play, Next and Deck's repeat and
  shuffle, R1-7) returns an activity PendingIntent instead of the broadcast when the target is
  `APP`.
  - That PendingIntent has its own request code (1) and carries `EXTRA_REQUEST_AUDIO_ACCESS`, so
    it never shares an identity with the body tap's launch (code 0) (R1-6).
- **The gate comes back (R1-5).** `MainActivity` reads the extra in `onCreate` and `onNewIntent`.
  When access is still missing, it clears the "Not now" flag, so the gate shows instead of an empty
  library.
- **The first tap after a revoke** still reaches the receiver, because the widget was drawn before
  the process was killed. The receiver:
  - re-renders all widgets, so the next tap is an activity launch;
  - tries `startActivity` with NEW_TASK.
  - On Android 10+ a refused background start is dropped without an exception (R1-8), so this
    attempt is a bonus. The re-render is the real fix, and the second tap always works.
- **After a grant (R1-9).** `MainActivity` re-renders the widgets when `onResume` sees access flip
  to granted, and in the gate's `onGranted`.
- Tests:
  - androidHostTest `WidgetControlTargetTest`: granted / missing on 24, 32, 33 and 37, and that a
    storage-only grant does not count on 33;
  - `MainActivityManifestTest` unchanged.
- Emulator, 36 and 37:
  - revoke → first tap → widget re-rendered (and the app if the platform allows);
  - second tap → `MainActivity` with the gate, even after "Not now";
  - grant → widget back to the receiver → Play plays.

## Implementation results (debug build, muted emulators, silent generated tracks)

F1 (M3U from outside):

| API | Case | Result |
|---|---|---|
| 36 | `audio/x-mpegurl` content URI, warm (activity alive) | sheet "ep · Found 2 of 2 tracks · New playlist / Add to favorites" |
| 37 | Dead (no process, no service), `.m3u8` sent as `application/vnd.apple.mpegurl` | sheet "ep8 · Found 2 of 2"; the saved queue was restored behind it (paused, Silence a) |
| 30 | `file:///sdcard/Download/ep.m3u` (scoped storage) | sheet "Found 2 of 2". The name was the generic "Imported playlist" because a file URI has no provider name, so the path's last segment is now used |
| 37 | a normal track VIEW after this change | PLAYING "Silence b" (track path unchanged) |

Found while verifying, before R2 reported it: the first build consumed the playlist and then
showed nothing. Consuming changed the effect's key, and Compose cancelled the running import. The
offer now runs in the screen's scope (`scope.launch`).

F2 (widget without the media permission), API 37:

| Case | Result |
|---|---|
| permission revoked, the gate's "Not now" chosen, then widget Play | `MainActivity` resumed with the permission gate (not the empty library) |
| "Grant access" in that gate → Home → widget Play | PLAYING, FGS (controls back on the receiver) |
| widget drawn with access → permission revoked → first tap | `MainActivity` with the gate. The process had already restarted and redrawn the widget before the tap (System UI and the service rebind), so the launcher started the activity (`BAL_ALLOW_VISIBLE_WINDOW [realCaller]`) |

Host tests: `ExternalAudioOpenTest` 12/12, `ExternalPlaylistRequestTest` 3/3,
`WidgetControlTargetTest` 3/3 and `MainActivityManifestTest` 12/12 all pass, and
`:composeApp:compileKotlinIosSimulatorArm64` compiles. The full-suite run is recorded under R3.

Not verified:

- a Honor/Xiaomi launcher;
- the receiver's own `startActivity` fallback in isolation, since on 37 the redraw had already won;
- an App-level Compose test of "the offer survives the consume". The pure parts are tested; the
  Compose wiring is covered only by the emulator runs above.

## API 31–35 run (2026-11-02)

**Build.** This branch merged with `claude/lockscreen-snackbar-094-4970f3` at 478c8b89 (tile fix
for Android 14):
- the merge was made in a throwaway worktree that has since been removed;
- the only conflict was the two intent hooks in `MainActivity`, and both were kept.

**Devices.** One AVD at a time: `LJ-ep31` to `LJ-ep35`, google_apis arm64, Pixel 7.
- Each ran with `-no-audio` and media volume 0.
- The library was four silent generated MP3s.
- Each AVD was deleted after its run. Free disk stayed at 67 GB or more.

**Overlay permission.** `adb install -g` leaves `SYSTEM_ALERT_WINDOW` allowed, and that permission
is itself a foreground-service start exemption (`code:SYSTEM_ALERT_WINDOW_PERMISSION`).
- The first pass on 31–33 had it allowed.
- 34 and 35 ran with it denied (`appops set … SYSTEM_ALERT_WINDOW deny`).
- The headset and widget rows on 31–33 were then re-run with it denied.
- Every result below holds without the overlay permission.

| Row | 31 | 32 | 33 | 34 | 35 |
|---|---|---|---|---|---|
| A1 key 126, dead, saved queue | PLAYING | PLAYING | PLAYING | PLAYING | PLAYING |
| A1 key 126, service not running, no saved queue | no crash, no FGS left | same | same | same | same |
| A1 access revoked, key 126 (service not foreground at the revoke) | no crash | no crash | no crash, fgs 0 | no crash, fgs 0 | no crash, fgs 0 |
| A1 access revoked while foreground, key 126 | **G3** | **G3** | **G3** | **G3** | **G3** |
| A3 shell `dispatch play` | nothing logged | nothing logged | plays (`com.android.shell`) | same | same |
| A4 POST_NOTIFICATIONS revoked, dead, key | — | — | PLAYING, notification posted | same | same |
| A7 tile (with 478c8b89) | lockscreen L | lockscreen L | lockscreen L | see below | lockscreen re-check |
| A8 widget Play, dead | PLAYING | PLAYING | PLAYING | PLAYING | PLAYING |
| A8 widget Play, BG paused | PLAYING | PLAYING | PLAYING | PLAYING | PLAYING |
| A8/F2 access revoked → widget Play | gate | gate | gate | gate | gate |
| F2 after grant → widget Play | PLAYING | PLAYING | PLAYING | PLAYING | PLAYING |
| A9 VIEW content:// (explicit component) | PLAYING | PLAYING | PLAYING | PLAYING | PLAYING |
| A10/F1 VIEW `.m3u` (`audio/x-mpegurl`, `file://`, cold) | import sheet "Found 2 of 2" | same | same | same | same |

How the system let the starts through, with the overlay permission denied:

- **Headset key.** `Background started FGS: Allowed uidState: RCVR code:ACTIVITY_STARTER`, with
  `tempAllowListReason … reasonCode:MEDIA_BUTTON, duration:10000`. Same on all five.
- **Widget Play.** `Allowed uidState: RCVR code:ACTIVITY_STARTER`, and the launcher stays on top.
  After a grant, the state is `SVC`.
- **F2 gate.** The activity start was allowed as `BAL_ALLOW_PENDING_INTENT` (34) or
  `BAL_ALLOW_VISIBLE_WINDOW` (35).

**API 34, tile and media buttons, with 478c8b89.** This is the parent session's re-check request.

| Case | Result |
|---|---|
| tile, dead, saved queue, overlay denied | `Disallowed uidState: CEM code:DENIED`, then START `MainActivity` with the play request, then `Allowed uidState: TOP`. PLAYING(3), the app on top. Same as the lockscreen session measured |
| tile, dead, overlay allowed | `Allowed … code:SYSTEM_ALERT_WINDOW_PERMISSION`. Plays without opening the app |
| tile, BG playing / BG paused / locked shade (no PIN) | pauses / plays / plays |
| tile, access revoked | opens `MainActivity` (gate) |
| tile, no saved queue (`pm clear`) | opens `MainActivity`; no foreground service 30 s after Home |
| headset key: dead + queue / no queue / POST_NOTIFICATIONS revoked | PLAYING / no crash, FGS gone / PLAYING with the notification |

On this image, `cmd statusbar add-tile` and `click-tile` did nothing. The tile was added through
`settings put secure sysui_qs_tiles` and tapped in the expanded shade.

**G3, measured.** The case is: play, pause (Media3 keeps the service foreground for up to 10
minutes after a pause), Home, then revoke music access. What happens:

1. `Killing … permissions revoked`.
2. `Scheduling restart of crashed service … PlaybackService`.
3. The new process is already foreground, with state NONE(0).
4. A headset PLAY gives `No saved playback queue`.
5. `playWhenReady=true` is set on the empty player.
6. Foreground service 1001 stays up with state STOPPED(1) and no metadata. System UI shows no
   notification for it.

It was still up at +100, +300, +600 and +700 s (API 32), and the process was kept. There was no
crash and no sound. The behaviour is the same on a build of `dev/0.9.4` + F1/F2 without the
lockscreen commits, so it predates both branches. When nothing was foreground at the revoke, the
key left no foreground service (33, 34, 35).

**Widget after a grant in system Settings (minor, all levels, follows from F2's design).** If
access is revoked and then granted again in system Settings without opening LatentJam:

- The restarted process drew the widget while access was missing, so the widget still carries the
  gate PendingIntent.
- The first Play opens LatentJam, which has access and shows the library, and redraws the widget.
- The next Play plays.

There is no dead end, so this is listed and not fixed.

## Not implemented (owner decides later; nothing built)

Android:

- `MEDIA_PLAY_FROM_SEARCH` activity handling (A13). It is the standard path for non-Google
  assistants and some car launchers. Any app could then start a queue, so it touches the trust
  policy in `latentjam-voice-assistant-trust`.
- App shortcuts (long-press icon: Shuffle all, Resume, Search).
- Auto-resume when a Bluetooth/wired device connects (opt-in setting in most players).
- Telling system equalizer apps about the session (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`).
  Also: the `MODIFY_AUDIO_SETTINGS` manifest comment still describes the old system-equalizer use.
- Share *into* the app (SEND of audio).
- Cast / Chromecast, Wear OS companion, Android Auto verification on a real head unit or DHU.
- Library refresh while backgrounded (MediaStore observer). Today a deleted file stays in the
  queue, widget and Auto browse until the app comes to the front.

iOS:

- "Open in LatentJam" from Files, Mail or AirDrop (no `CFBundleDocumentTypes`, no `onOpenURL`).
- CarPlay.
- Siri / App Intents / Shortcuts.
- Home-screen and Lock Screen widgets, Control Center control (iOS 18 `ControlWidget`).
- Live Activities.
- An in-app AirPlay picker.
- `skipForward`/`skipBackward`/like commands.
- Documents (the user's music) and NSUserDefaults are part of the iCloud/device backup; only
  Application Support is excluded. This is a policy choice.

## Review log

### R1 (fresh subagent, plan v1) — REVISE
- MAJOR: a playlist request would use up the launch restore (`onBegin`, `pending` gate).
  → v2: a separate `ExternalPlaylistOpener` flow that never touches `pending` or `onBegin`.
- MAJOR: `application/x-mpegurl` and `application/vnd.apple.mpegurl` never reach the activity.
  → added to the VIEW filter, with a manifest test. `text/plain` and octet-stream stay out on
  purpose.
- MAJOR: no type is passed today.
  → `intent.resolveType(contentResolver)`. Not saved in SavedStateHandle: the playlist is read at
  once and held in memory (stated trade-off).
- MINOR: read in the ViewModel, extract the bounded reader, skip the revision check, gate-dismissed
  wording, concurrent picker import, `file://` on 30, relative paths.
  → all taken into v2.
- MAJOR: after "Not now" the widget lands on an empty app.
  → `EXTRA_REQUEST_AUDIO_ACCESS` clears the flag.
- MAJOR: PendingIntent identity collision with the body tap.
  → own request code (1).
- MINOR: Deck repeat/shuffle (covered: `widgetAction` serves all five).
- MINOR: background starts fail silently (the redraw is the real fix).
- MINOR: no redraw after a grant (`onResume` flip and `onGranted`).
- MAJOR (matrix): Perm revoked + Dead + not bound shares the 30 s crash path; A1/A7 open until
  the lockscreen branch merges.
  → rows added and marked.
- MINOR (matrix): missing rows (APP_MUSIC, notification body tap, OEM volume, app update,
  gate-dismissed state) and citations.
  → fixed.

### R2 (fresh subagent, plan v2 + code) — REVISE
- BLOCKER: the consume changes the effect key and cancels the offer.
  → `scope.launch` (found on the emulator at the same time; verified fixed).
- MAJOR: a grant made while the process was dead never redraws the widgets.
  → `reopenAudioGateIfAsked` redraws when the extra arrives with access granted.
- MAJOR: the receiver's redraw was not kept alive.
  → `goAsync()` + `refreshPlaybackWidgets(context, pending::finish)`.
- MINOR: the extra is re-read on recreation.
  → only when `savedInstanceState == null`.
- MINOR: the permission choice was duplicated.
  → MainActivity uses `audioReadPermission`.
- MINOR: `application/ogg` + `.m3u` name read as a playlist.
  → the stated non-audio track types count as tracks; tests added. The octet-stream case is
  commented as not admitted by the filter.
- MINOR: the tests do not cover the Compose wiring.
  → accepted; covered by the emulator runs.

### R3 (fresh subagent, final diff) — READY
- MINOR: a synchronous throw in the receiver's redraw would leave the broadcast open.
  → try/catch finishes it.
- MINOR: the host tests cover the pure helpers, not the wiring (the `widgetAction` redirect, the
  receiver branch, `reopenAudioGateIfAsked`, the App effect).
  → accepted; the emulator runs above cover the wiring.
- MINOR: `intent.resolveType` might block on a slow provider.
  → no change. `Intent.resolveType` returns the explicit type without a provider call and asks the
  provider only for a content URI that has no type; `getType` cannot throw.
- MINOR: when the launch scan fails, an opened playlist waits silently until a scan succeeds.
  → accepted; the scan failure shows its own snackbar.
- Full suites after R2's fixes: `:composeApp:testAndroidHostTest` 1340/1340 and
  `:composeApp:iosSimulatorArm64Test` 1193/1193 pass (K/N runs the new commonTest).
