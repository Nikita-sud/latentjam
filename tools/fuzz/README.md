# Parser fuzzing

Jazzer 0.30.0 runs as a standalone Linux process over the existing Android host-test
classpath (also exercised locally on macOS). No JUnit 5 dependency or production dependency is added. Build/replay works
without Jazzer: `./gradlew testAndroidHostTest` replays checked-in seeds and regressions,
alongside the existing full-disk, sync-failure, torn-write and recovery suites.

```sh
python3 tools/fuzz/run.py id3 --jazzer /absolute/path/to/jazzer --seconds 300
python3 tools/fuzz/run.py backup --jazzer /absolute/path/to/jazzer --replay
```

Targets: id3, flac, mp4, ogg, backup, m3u. The workflow is manual to keep long exploratory
runs separate from PR gates. Inputs are capped at 1 MiB, JVM heap at 512 MiB, process RSS
at 2 GiB and an input at 10 seconds. These are harness budgets, not claims about all
possible production inputs. Native allocation and timeout failures are findings too.
Only explicit format/stream refusals are accepted; unexpected exceptions escape.

Tag targets plan and serialize into memory, then verify audio digests, untouched
inventory and requested fields. They never open user music for writing. Existing
DurableWriter crash tests simulate power loss during both writing and recovery.

Minimize a failure with Jazzer's `-minimize_crash=1`, copy the minimal bytes into the
matching `corpus/<target>/` directory and add an ordinary regression assertion explaining
the bug. Keep original corpus entries. CI artifacts may contain supplied input bytes;
use synthetic or redistributable seeds only. The runner also exports complete valid
containers from `WriteFixtures` before mutation, so it reaches serialization rather
than spending every run rejecting a truncated header.

The 2026-10-14 exploratory runs found three regressions now replayed on every PR:

- ID3 lone track/disc totals (such as `/2`) were preserved by the writer but incorrectly
  rejected by verification after unrelated edits. Verification now preserves untouched pairs.
- MP4 chunk offsets beyond EOF could acquire a different meaning when the final `moov`
  grew, or be shifted during relocation. Planning now refuses these malformed targets.
- MP4 chunk tables inside opaque user data could be relocated as if they belonged to
  track sample tables. Planning refuses this unsupported relocation before writing.

DurableWriter's verification protected the source in these cases; these findings do
not establish prior data loss. The saved inputs and ordinary regression assertions
cover the fixes. Short exploratory runs are not proof that all parser defects are gone.

