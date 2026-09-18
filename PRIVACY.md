# LatentJam privacy policy

Effective 18 September 2026. Applies to the LatentJam app for Android and iOS, whether
installed from Google Play, the App Store, or a build downloaded from this repository.

## The short version

LatentJam collects no personal data. It has no account, no analytics, no advertising, no
crash reporting and no server. The Android app does not even request the internet permission,
so it cannot send anything anywhere. Everything the app knows lives on your device and
leaves it only if you copy it out yourself.

## What the app reads on your device

- **Your music files and their tags** — title, artist, album, genre, year, language, lyrics
  and cover art embedded in the files — to build the library, the audio index, the
  recommendations, the map, search and the duplicate finder. The audio is analysed on the
  device with bundled models; no audio or metadata is uploaded.
- **Listening history** — which tracks you played, skipped or finished, and when — used for
  statistics, "For You" and the recommendations. It is recorded only while *Save listening
  history* is on in Settings, where it can also be cleared at any time.
- **Your playlists, favourites, hidden tracks, duplicate decisions and settings.**

All of this is stored in the app's private storage on the device. Nothing is shared with the
developer or with any third party.

## Permissions and why they are asked for

| Permission | Why |
| --- | --- |
| Read audio files (Android), Media Library (iOS) | To find and play your music |
| Notifications | Playback controls in the notification shade |
| Foreground service | To keep playing with the screen off and to index files in the background |
| Modify audio settings | Equaliser and volume normalisation |
| Keep the device awake | Uninterrupted playback |

## Things that happen only when you ask

- **Tag editing** rewrites the tags inside your own audio files, on the device.
- **Delete file** removes a file through the system's own confirmation dialog.
- **Local backup** writes a backup file to the location you choose, and restores from it.

## Third parties

There are none. The app embeds no analytics, advertising or tracking SDKs. The store you
installed from (Google Play or the App Store) may collect install and crash statistics on its
own terms, described in that store's privacy policy; LatentJam itself sends nothing.

## Deleting your data

Uninstalling the app removes everything it stored. Inside the app, Settings offers separate
controls to clear the listening history and the audio analysis.

## Children

LatentJam is a general-purpose music player and is not directed at children.

## Changes

Changes to this policy are published in this file with a new effective date.

## Contact

Questions go to the project's issue tracker:
<https://github.com/Nikita-sud/latentjam/issues>.
