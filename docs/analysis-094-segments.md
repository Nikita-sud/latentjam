# Segmented audio snapshot (LIFE-03), plan

Status: v2 (after review round 1, NOT READY on v1; v2 is what is implemented). Branch `claude/analysis-094-939dfe`.

## Problem

The audio index is one LJIX v4 file per encoder (`smart_index.<ver>.bin`, `VersionedIndexStore` over a half
`FileIndexStore` / `IosFileIndexStore`). That file's envelope is 64 MiB, about 34,000 rows at 1,974 B each. Above it,
every save is refused and the file freezes at the last checkpoint that fit. Every cold start then re-embeds every row
above the ceiling (external review LIFE-03, reproduced in `review_ext/VERDICTS_SCALE.md`).

## Why segments by position, not shards by id hash

The brief proposed K shard files keyed by a stable hash of the id. That would break one hard requirement:

- **Row order is part of SMART's output.** `nearest()` breaks ties by insertion ordinal, and a restart restores the
  file's order (docs/analysis-094-plan.md §1, "Ties"). Restoring K hash shards one after another changes the order.
  Interleaving them back needs either a per-row ordinal (a format change) or K open readers merged against an order
  file that must stay consistent with shards written at different times.
- **The index's order changes in only three ways** (`InMemoryVectorIndex.put`/`remove`):
  - a new id is appended;
  - a removed id disappears;
  - a replaced id keeps its place.
  So the order is always "the old order minus removals, plus appends at the end".

So the snapshot is cut into **segments**: consecutive runs of the global order, each at most `M = 8,192` rows (about
16 MB). Concatenating the segments in segment-number order is the global order, so a load restores the exact order
with one reader at a time. Each segment is its own half LJIX v4 file with its own 64 MiB envelope (8,192 rows leave
a 4× margin for long ids). This keeps the brief's intent: K files, a per-file envelope, only changed files rewritten,
no envelope raise.

## Files

For a store whose single file is `X.bin` (today `smart_index.<ver>.bin`):

| file | holds |
|---|---|
| `X.s<n>.bin` (n = 6 digits, never reused within a set) | rows of segment n, no failures |
| `X.failed.bin` | the remembered failures only (0 rows) |
| `X.bin` | the 0.9.3 single file. Read only for migration, deleted after the first complete segmented save |

Each file is written by the platform store as today: a temp file, fsync, rename, directory fsync. A file is
therefore either its old version or its new one, never half of each.

## Common `SegmentedIndexStore` (commonMain)

Its inputs are `segment(name): IndexStore` (the platform half store for one file) and `names(): List<String>` (the
file names in the store's directory). Both platforms run the same code, so Android and iOS parity holds by
construction.

**In memory between saves:**
- for each segment: its number, its row count and a 64-bit content digest (ids, identity tokens, vectors as halves);
- `ids → segment`, as a sorted `LongArray` of 64-bit id hashes with a parallel `IntArray`: 12 B a row, 0.6 MB at
  50k.

**loadRows(version, row):**
1. List `X.s<n>.bin` sorted by n. If there are none, delegate to the single file (migration) and return what it
   returns.
2. For each segment, read it in two passes:
   - *Validate:* call `loadRows` with a receiver that drops the rows; a null result means the segment is damaged or
     absent.
   - *Deliver:* if it validated, read it again and hand each row to `row`. Record the id hashes and the digest.
     A damaged segment is skipped: only its rows are missing, so only they re-embed.
3. Read the failures from `X.failed.bin`.
4. If the single file still exists (a migration that crashed), deliver its rows last. The engine skips ids it already
   adopted (`if (id in index) return@loadRows`), and identities guard staleness. Its failures are added to the
   union.
5. Return the union of failures, or null when nothing was usable.
6. `IndexRestoreFailure` (an I/O error or OOM) propagates, as today.

**saveSnapshot(version, snapshot):**
- **Pass 1** walks `snapshot.entries` in order. It looks up each id's old segment and checks that old segment numbers
  never decrease and that new ids come only after every old one.
  - If that holds, the old segments keep their surviving rows. New rows fill the last old segment up to `M`, then go
    into new segments numbered max + 1, and so on.
  - Otherwise (a reorder no engine path makes, a damaged segment, or the first save after migration), everything is
    re-segmented into fresh numbers above the old maximum.
  - The pass computes each group's count and digest.
- **Pass 2** writes every group whose count or digest changed. Each group goes through the platform store as a view
  over the snapshot: its ids, the snapshot's vectors and identities, and no failures. Only one group's id list is
  held at a time.
- Then `X.failed.bin` is written if the failures changed. After that, segment files no longer used are deleted, and
  finally `X.bin` is deleted if it exists.
- The in-memory digests and lookup are replaced only after every write succeeded. A refusal (`IllegalArgumentException`)
  or a failure leaves the old ones, so the next save compares against what is really on disk.

**Crash between files.**
- The normal path rewrites a changed segment under its own number. A row is then either in its old version (with its
  old identity) or its new one.
- A re-embedded row that was removed and appended can sit in an old segment and a new one at once. The engine adopts
  the first and its identity check re-embeds it: extra work, never a wrong vector.
- The fallback writes new numbers before deleting old ones. A crash there leaves the old complete set first in load
  order, plus extra rows.

**clear():** deletes every segment, `X.failed.bin`, `X.bin` and their temp files.

## Wiring

- Android: `VersionedIndexStore("smart_index") { name -> SegmentedIndexStore(name, { FileIndexStore(context, it, half = true) }, { filesDir.list() }) }`.
- iOS: the same with `IosFileIndexStore` and a listing of its directory.
- The text and energy stores are unchanged.

## Gates (frozen before any implementation run)

| gate | what passes |
|---|---|
| G-e | Host property test, 200 random sequences of upsert (new and in place), remove, re-add, failure add/forget, save, reload and clear. After each save and reload, the segmented store's union (rows, order, identities, failures) equals a full snapshot of the same state written and read by a **frozen copy of the v0.9.3 `FileIndexStore`** (test sources, package `oracle093`): vector half bits equal, order equal, failure set equal. `M` is small (5) so that sequences cross segment boundaries. |
| order | Inside G-e: the reloaded order equals the 0.9.3 oracle's order after every reload. |
| 50k | Host: an engine with a 960-d half index, 50,000 tracks, one checkpoint per 32 tracks, then a second engine (cold start) on the same store. It must re-embed **0** tracks. The same case on the single-file store re-embeds about 16,000 (control). |
| torn segment | Host: after a save, truncate one segment file (and, as a second case, overwrite its middle with zeros). The next engine restores every row of the other segments and re-embeds exactly the rows of that segment. |
| migration | Host: a 0.9.3 single file loads unchanged; the first save writes segments and deletes the single file; a crash simulated after k of the segment writes still restores all rows. |
| parity | iOS: the same common store over `IosFileIndexStore` in an iosTest (round trip, torn segment, migration). iOS simulator corpus golden: index rows equal head (union of segments, same order). |
| peak memory | Host, 50k rows: JVM allocation during `saveSnapshot`, segmented ≤ single file (plus the 0.6 MB lookup that stays). Retained heap after save ≤ single file + 12 B/row. |
| unit | All `:core:smart` host and iosSimulatorArm64 tests green. Android golden unaffected: the analysis path is untouched. |

## Named risks

- R1 downgrade: a build before this one sees no `X.bin` and re-embeds the library once.
- R2 many small segments after mass removals; segments are never merged. Worst case one file per surviving run, ≤ N/1.
- R3 save cost: pass 1 reads every vector once per checkpoint (as today's single save does), pass 2 reads the
  changed groups' vectors again.
- R4 the text index keeps one file: about 42,000 rows at 384 floats. Above that, metadata is re-encoded at each
  launch. It is quick and deterministic, but it is not free. Follow-up: wrap it the same way.
- R5 a 64-bit content digest collision leaves one changed segment unwritten. Probability about 2⁻⁶⁴ per save.

## v2: what changed after review round 1

Round 1 (a fresh reviewer, v1 plan against the code) found 1 blocker and 10 majors. Process note: v1's gates were
written before the code, but a first draft of the store existed when the review ran. The v2 gates below were frozen
before the v2 code ran.

| # | finding | v2 |
|---|---|---|
| 1 | LJIX has no checksum, so a zeroed middle passes a structural check | Each segment's 128-bit content digest is kept in the manifest and checked on load. A mismatch means that segment alone is skipped. |
| 2 | No commit point across files | `X.manifest.bin` lists the segments in order (number, rows, digest) plus the failures file. A changed segment is always written under a **new** number, so a file the committed manifest names is never overwritten. The manifest is written last (atomic), and files it does not name are deleted after it. A cut save restores exactly the old or exactly the new snapshot. |
| 3 | Stale in-memory digests after a failed save | The in-memory state is replaced only after the manifest is committed, and it always equals what that manifest says. Orphans from a failed save are deleted by the next commit. |
| 4 | The clear-repair save trusts stale state | `clear()` forgets the state first, then deletes the manifest (the commit), then the files. New numbers always start above every number still listed. |
| 5 | A refusal halfway through, plus the latch | The whole-snapshot checks (rows ∩ failures = ∅, one dimension) run before any write. Segments are cut at 4,096 rows **or** about 32 MiB. With the manifest, a refusal at any file is all or nothing. |
| 6 | Downgrade and re-upgrade; an unvalidated `X.bin` | The manifest records the digest of the `X.bin` it was made from, and `X.bin` is deleted after the commit. An `X.bin` found later with another digest was written by an older build after a downgrade: it is loaded alone and the next save re-segments it. `X.bin` is validated like a segment. |
| 7 | Duplicates, damaged files, partial loads | The lookup records an id's first occurrence only. Every file not named by the new manifest is deleted. Load builds its state locally and installs it at the end. Groups are contiguous slices of the snapshot's order, so a lookup error or an id-hash collision can only cost a rewrite. |
| 8 | The peak-memory gate measured garbage | The gate is now the **smallest `-Xmx` (to 2 MB) at which a child JVM builds a 30k index and saves it twice**, single file against segmented. |
| 9 | 50k was not feasible as specified | One checkpoint per 1,024 tracks. The control at 50k uses the real envelope (it takes about 20 s on the host); both engines are sequential. |
| 10 | G-e only proved the store round-trips | Added an engine differential test: 60 scripts of analysis, file changes, removals, failures, clears and restarts on two engines (segmented, and frozen 0.9.3), comparing the restored index order, vectors and identities after every restart. Added a crash test that cuts a save at every file write or delete. |
| 11 | Host tools read the single file | Added `readAudioIndexSnapshot(dir, base, version)`, used by `SmartStandRunner`. `tools/diagnostics/smart_stand.py` reads the manifest, and also reads LJIX v4 halves, which it could not before. |
| 12 | iOS specifics | The listing uses the resolved store directory (`iosIndexFileNames()` over `indexDirectory`). The name pattern is anchored (`X.s<digits>.bin`, so `.tmp` and `.half` files are excluded) and sorted by integer. `isIndexStoreFile` copies segments and the manifest during the Caches move (prefix `smart_index.`). iosTest covers a round trip, a damaged segment and migration over the real `IosFileIndexStore`. |
| 13 | The validate pass doubles restore reads | Kept: the digest must be known before rows are delivered, and Android streams. This is the cost in R3. |
| 14 | Specify the digest | Two 64-bit lanes (FNV-1a, and a golden-ratio multiply-xorshift lane with a finishing mix) over the model version; each row's id (UTF-16), identity (a marker for none), dimension and the halves written; the row count; then the failures in order. |
| 15 | The fallback is over-triggered | A damaged segment no longer forces it (its rows come back appended). A seed-first reorder still re-segments, as the plan says. Transient disk during a fallback is up to 2× the index. |
| 16 | Hash shards against position segments | Position segments kept, as the reviewer agreed. |

### v2 gates (frozen 2026-11-02 night before the v2 code ran)

| gate | result |
|---|---|
| G-e store | `SegmentedIndexStoreTest.randomSequencesRestoreWhatTheFrozen093StoreRestores`: 200 sequences × 60 ops; segment sizes 1 to 6; restores compared with the frozen v0.9.3 `FileIndexStore` (`androidHostTest/.../oracle093`). **PASS** |
| G-e engine | `SegmentedEngineDifferentialTest`: 60 scripts × 50 steps; order, halves and identities equal after every restart. **PASS** |
| crash | `aSaveCutAtAnyFileRestoresExactlyTheOldOrTheNewSnapshot`: a save cut after each of its file writes or deletes restores exactly the old or the new snapshot. **PASS** |
| 50k | `SegmentedSnapshotLibraryTest`: 50,000 × 960-d, a checkpoint every 1,024. Second launch re-embeds **0** (13 segments). The single-file control re-embeds **16,208**. **PASS** |
| torn segment | Truncated and zeroed-middle cases on Android, truncated on iOS: only that segment's rows are missing. **PASS** |
| migration | The 0.9.3 single file is read unchanged, segmented at the first save, then deleted; a migration cut short restores every row. **PASS** |
| parity | `IosSegmentedIndexStoreTest` (round trip in order, damaged segment, migration) over `IosFileIndexStore`: **PASS**. The rule is common code. |
| peak memory | `SegmentedSavePeakTest`: smallest heap for 30k rows and two saves. Single **65 MB**, segmented **65 MB**. **PASS** |
| unit | All `:core:smart` and `:composeApp` host tests, and `:core:smart` iosSimulatorArm64 tests: **PASS** |

### Named risks after v2

- R1 downgrade: a build before this one sees no `X.bin` and re-embeds the library once. A later re-upgrade loads
  what that build wrote (6).
- R2 segments are never merged. After many removals there can be many small files, bounded by the segments ever
  created (about appends / 4,096, plus one set per fallback).
- R3 restore reads every segment twice (check, then deliver); a save copies each row once more than today's single
  file when a segment changes.
- R4 the text index keeps one file, about 42,000 rows at 384 floats. Above that, metadata is re-encoded at each
  launch. Follow-up: wrap it the same way.
- R5 a 128-bit content-digest collision would leave one changed segment unwritten.
- R6 a seed restored ahead of the stored rows (restore retry with live rows) re-segments the whole library once:
  transient disk up to 2×.

## Review rounds 2 and 3

**Round 2 (code against v2): NOT READY, 4 majors. All fixed:**

| # | finding | fix |
|---|---|---|
| 1 | Recovery without a manifest, and a clear cut partway, restored a mix | No recovery: with no manifest and no single file, load returns null (rows re-embed). `clear()` first commits a **tombstone** manifest (no segments, `migrated-from = cleared`, model version `*`), then deletes segments and leftovers, then the single file, then the manifest. After a tombstone the single file is ignored. |
| 2 | The zeroed test never reached the digest | The zeros now go inside the first row's vector. The test asserts that a plain store still parses the file and that only that segment is dropped. Same on iOS (`pwrite`). |
| 3 | Fallback, migration and clear were untested under cuts, and so was the byte cut | G-e gains a seed-first op (forces the fresh rewrite over a committed set). `cutEverywhere` covers migration, a fresh-order save and `clear()` at every file op. A byte-cut test uses the new `segmentBytes` parameter. |
| 4 | The single file was deleted only after a migration | It is deleted after every commit. |
| 6 | iOS read files whole twice | `readsWhole = true` on iOS: each file is decoded once and its digest taken from that snapshot. |
| 7 | A failed listing read as an empty directory | Both listings throw. |
| 8 | Leftover temp files | Segment numbers with `.bin.tmp` or `.bin.half` are swept through their store's clear. |
| 9 | The legacy store's pattern could match a version's files | The legacy `smart_index.bin` is passed through as a plain store. |

**Round 3: READY.** Remaining items, kept as named risks:
- R7: iOS `IosFileIndexStore.clear()` deletes neither `.tmp` nor `.half`, so a killed iOS segment write can leave up to about 32 MiB of temp file.
- R8: a downgrade right after a crashed clear loses that build's analysis (the tombstone wins).
- R9: a damaged manifest means a full re-embed. This is the cost of never restoring a mix.
- R10: a downgrade single file wins even when it is partial; the segments' extra rows re-embed.
- R11: `tools/diagnostics/smart_stand.py` follows the manifest without checking digests or the downgrade rule. `SmartStandRunner` goes through the real stores.
- R12: the peak gate's resolution is about 4 MB, and it measures saves only.
- R13: the byte cut is tested through a fresh write only, not through tail rows joining an old segment. Engine-level crash and restore-retry are covered at store level only.

Two of round 3's minors were fixed in the same commit: `whole` is cleared at the end of a load, and the post-commit listing cannot fail a committed save.
