SMART picks better, and the app gets smaller: smoother transitions and fewer jumps between genres, an Artist variety slider, marked playlists that stay together without abrupt changes — on audio models half the size that analyse your library up to twice as fast. Still fully on-device: no account, no catalogue, no network — nothing about your listening leaves the phone.

### New
- **Reverse group sorting.** Genres, artists and folders each remember their own ascending/descending order. Date-named genres can show the newest first. Local backup format 8 preserves these choices and still reads versions 1–7.
- 🎧 **SMART chooses the next song more the way a person would.** A correction learned from a blind judge of real playlists (public MPD data, never your library) now ranks the candidates: it passes over a song that would change the sound abruptly while a closer one is left, and keeps the walk going across top-ups instead of starting over. In blind comparisons on public libraries, both judges preferred the new queues to 0.7.1's about 2:1, and two listener panels 10:6.
- 🎚️ **Artist variety** (Settings → SMART engine → Recommendations): from Off to Maximum, how strongly SMART avoids playing one artist several times in a row. Balanced by default; a run still happens when that artist really has the closest songs, and a new level reaches the songs already queued.
- 📌 **"Keep together in SMART" without forced turns.** A marked playlist no longer takes every third song by force: its songs earn points instead. They play as often as before, sit next to each other twice as often, and come with a fifth of the abrupt changes. One marked playlist also no longer switches the new SMART off for every queue.
- 🪶 **Smaller and faster.** The on-device models are half the size, and the audio model runs about a third faster on LatentJam's own 4-bit operator; the arm64 download shrinks from 57 to 38 MB. After the update the library is analysed once more — up to twice as fast as before.

### Fixed
- Android 10 and later: every song shows its own cover. Songs without an album tag, and same-named albums of different artists in one folder, no longer all show one song's cover; a song with no cover of its own still shows its album's ([#12](https://github.com/Nikita-sud/latentjam/issues/12)).
- Dragging a queue entry, a playlist or a page in Settings → Pages to either edge scrolls the list while you hold it there, so it can be moved beyond the visible rows.
- iPhone import preserves existing shorter or different files with the same name and safely gives the new copy another name.
- After a large tag edit, the library and queue refresh again when Android finishes indexing the changed files.
- A remix, radio edit, remaster or live take of a song you just heard no longer comes up again in SMART as if it were another song (in 0.7.1 it happened in about one queue in twelve).
- Removing a song from the SMART queue keeps it out of the rest of the queue, on Android and iPhone alike (on iPhone it came back at the next top-up).
- The interface no longer freezes if you press Back or tap outside a sheet while it closes, and dismissing a sheet no longer performs the action you were about to pick.
- Restoring a local backup can no longer overwrite your playlists and listening history with empty ones when the library has not been read yet, and a reference that carries only a track id is restored again.
- The app no longer crashes when the media library is unavailable while it scans, when the favourites store fails, or on the SMART exclusions screen; it reports the failure instead.
- Statistics and the map refresh after you clear your listening history or restore a backup, instead of showing the old numbers.
- iPhone: a tap on a lyric line or a drag of the seek bar to the very end of a file no longer risks a crash; a damaged listening-history file no longer blocks new plays, clearing or restoring; an unreadable Music library no longer looks like an empty one, which could make the app forget your music.
- iPhone: artist names with superscripts (Girls², H₂O, 8½ Souvenirs) resolve to the right artist again, and ONNX Runtime failing to start no longer kills the app at launch — SMART turns off and the player keeps working.
- Analysis no longer latches an empty index after a cancelled library scan, and cancelling "Rebuild analysis" reports a cancellation instead of a failure.
- This build also carries the fixes from an independent code audit; the traceable list — finding, commit, verification — is in `docs/audit-fixes-2026-10-08.md`.
- The equaliser and queue are not included in local backups; the backup screen lists what travels, and a damaged or foreign backup file is refused before anything is applied.
- A backup file written by this version carries format 8: older builds refuse it whole instead of failing halfway, and a restore that is interrupted by leaving the screen now finishes instead of applying half of its sections.
- Editing the queue, seeking back or changing shuffle no longer stops playback while the sleep timer waits for the end of a track, and the quick-settings tile starts the queue again after it has finished.
- The widget shows the right transport while a track is buffering, the restored repeat mode is actually applied to the player, and the "playing" state no longer survives the death of the process.
- Search highlights "ph" the way the search index folds it, an empty song list no longer wipes the lyrics cache, and a search with a year reaches lyric matches again.
- Playlists keep their scroll position, an empty auto-playlist card no longer opens a page with nothing on it, and an M3U file with a byte-order mark no longer adds a phantom track.
- iPhone: the Music library is no longer walked from scratch on every return to the foreground, the bass boost slider shows the real amount of boost, the audio engine is rebuilt after the system resets its audio services and waits for you to press Play, the media-library prompt is localized, and a large embedded cover no longer costs full-size decoding.
- On the player, the swipe preview no longer promises a track the gesture will not reach, the seek bar takes the position back from your finger as soon as the player confirms it, and the touch band is the promised 44 dp.
- Translations: European Portuguese is covered, Hebrew reached the widget, tile and Android Auto strings, Indonesian reached the notification commands, the widget and Android Auto, and the Arabic sleep-timer labels have every plural form. Simplified Chinese now also reaches Chinese devices outside mainland China (Singapore, Malaysia); devices set to Traditional Chinese show the Simplified translation, with some system notification labels still in English, until a Traditional translation exists.
- Restoring a backup merges your listening history under one lock, so a track that finishes while the restore runs is no longer dropped.
- All three home-screen widgets follow playback state now, including the artwork widget, and the elapsed time stops soon after the app is gone instead of ticking in the launcher.
- Removed songs stay removed even in the "on a roll" row and in the cover of a world card.
- The genre tags of a file are capped like everywhere else in the app, and a broken ID3 size or a stub MP4 cover can no longer send a reader down the wrong path.

### Install (Android)
Download `LatentJam-v0.8.0-arm64.apk` (38 MB — right for virtually every phone from the last decade; if unsure, pick this one). `LatentJam-v0.8.0-armv7.apk` exists for old 32-bit devices. Open it on your phone — it upgrades any earlier release from here in place; your history, playlists and settings stay, and the library is analysed once more in the background. You'll need to **allow installing unknown apps**, and tap through Play Protect's sideload warning. Requires **Android 7.0+**.

### Install (iOS)
`LatentJam-v0.8.0-unsigned.ipa` is an **unsigned** build: sideload it with AltStore, Sideloadly or similar (they re-sign it with your Apple ID). Built for iOS 15.1+, arm64.

### F-Droid
LatentJam is [on F-Droid](https://f-droid.org/packages/io.github.nikitasud.latentjam.kmp/); it builds this version from source on its own schedule, with the standard ONNX Runtime (so that download is larger). This version compiles a small native library, so its F-Droid build needs an updated recipe and may arrive a little later than usual. That build is signed with F-Droid's key rather than the one used here, so the two cannot upgrade each other: to switch, export your data first (Settings → Local backup), uninstall, then install from F-Droid and restore.

> **Beta, pre-1.0.** Screens and settings can still change between releases. Tag editing writes to your real files, so back up the music you care about before a big edit. SMART gets noticeably better once the first background analysis has finished. Found a bug? [Open an issue](https://github.com/Nikita-sud/latentjam/issues).
