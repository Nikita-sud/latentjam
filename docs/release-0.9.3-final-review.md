# 0.9.3 final release review

Scope: `git diff v0.9.2..release/0.9.3-full` (base 2e55516d), the whole app. Earlier loops:
`docs/release-0.9.3-review.md`, `docs/release-0.9.3-review-2.md`.

Method, per round: fresh attacker subagents find problems with evidence; a fresh defender subagent answers each one
(agree with a fix, or disagree with evidence). Agreed points are fixed and tested; disputed points go to the next
round. The loop stops when an attacker round finds nothing real.

Baseline at 2e55516d: host tests green; arm64 release APK 36,971,208 bytes (reduced runtime), armeabi-v7a 44,496,012,
x86_64 58,857,366; most dex registers within the limit.

## Round 1

Four attackers (A hygiene and settings text, B data safety and memory, C SMART correctness, D features and build), three
defenders. 25 findings.

| id | finding | verdict | outcome |
|---|---|---|---|
| A1-1, D1-1 | versionName `0.9.3-smart-c095` (shown in About) | agreed | a4a2bfdb `0.9.3`; base versionCode is the owner's (≥ 44 to replace the phone's 43) |
| A1-2, D1-2 | the changelog is `39.txt`; no build has that code (splits are base × 10 + ABI) | agreed | owner: file names follow the base; wording of "artist variety applies to every queue" |
| B1-1, C1-1 | the personal evidence build (habits on) runs out of memory after a large import: 50k listens at 48 MB, 100k at 96 MB in a host JVM; `OutOfMemoryError` kills the app | agreed | cd6c4c29: the build falls back to CORE on `Error` and is not retried until the history grows by a quarter; a pair's first session held inline (bit-identical, goldens green; 50k builds at 40 MB instead of 48, 100k at 64 instead of 96) |
| B1-2 | local backup drops a listen's pick surface and personal flag, so Merge of this device's own backup doubles every 0.9.3 listen | agreed (regression) | ec39de60: backup v12 carries both; merge ignores them when matching listens |
| C1-2 | the energy pass never invalidates a snapshot built before it: the first session after an upgrade plans without energy | agreed | 876016ab (test fails before, passes after) |
| C1-3 | the personal term reads marked playlists too (rules O and C) | agreed (bug) | a8e4241a: unmarked playlists only; changes picks only with habits on and a marked playlist |
| A1-3 | the held-back adaptive crossfade setting is stored but read by nothing; two strings in 22 locales unused | agreed | d1d9dcdf (settings plumbing and strings; the playback side stays for 0.9.4) |
| A1-4 | rejected similarity guard still in the engine (stand knob) | agreed | 4127cf10 removed (goldens green) |
| A1-5 | garbled SMART config comment; private file citations | agreed | ffe2cbd7 |
| A1-7 | committed scratch: a machine-bound script, three unreferenced pilot logs, scratch paths in a doc | agreed (narrower) | ffe2cbd7; referenced logs and perf scripts kept with their docs |
| D1-3 | F-Droid arm64 recipe installs one CMake and leaves it off PATH | agreed | a4a2bfdb |
| D1-5 | F-Droid doc lists the full-runtime arm64 size | agreed | a4a2bfdb |
| D1-4, C1-5 | hearts act (habits on) while "Use favourites" is hidden; stale comment | split: comment agreed (a4a2bfdb); show the row or not disputed | to round 2 |
| C1-4 | a row outside M can win when the best M row gets t < 0 (NOVELTY) | disputed: spec ambiguity, the D1 amendment needs the all-rows argmax | owner proposal |
| C1-6 | per-plan allocations (QueueClasses, ThemePool, PersonalTerm) | agreed, small | 0.9.4 |
| A1-6 | `|| mapRefreshOverride()` dead with DEFAULT = true | narrower: the override still gates debug-only markers | left |
| A1-8 | a hidden German row runs long | hidden in release | left |
| D1-6 | a stale reduced-runtime repo fails every Gradle run | developer-only | left |
| D1-7 | habits and lock-screen player not in the backup | not reset by a restore; no regression | 0.9.4 |

Not a finding but a release fact: 0.9.3 ships a new audio encoder (`embedding_version.txt` changed), so an upgrade
analyses the library again; history and settings stay (emulator: 0.9.2 → 0.9.3, 4 plays kept, 125 tracks re-analysed).

