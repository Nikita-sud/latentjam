# 0.9.4 track 1: library analysis, fast and reliable

Status: **v22 — READY** after 22 adversarial review rounds (log at the end). Nothing is implemented until the owner says go.

Scope: the on-device library analysis on Android and iOS. It covers:
- audio decoding;
- the audio encoder A0 and its semantic head;
- the text encoder for the metadata string;
- the index files;
- the run that drives them: the foreground service, wake lock and background continuation.

Out of scope: SMART ranking, the release build, unrelated UI, and the beat/BPM experiment.

Code baseline: `dev/0.9.4` = origin/main 1730aa24, the released 0.9.3 code plus README changes.

## Summary for the owner (one page)

**What users get, slice by slice:**
- **B1:** fixes for the analysis foreground service (crash-prone start/stop and background updates), a faster codec
  lookup, possibly higher analysis thread priority (only if a measured rule passes), and iOS background time on every
  move to the background.
- **B2:** analysis keeps going with the screen off (a wake lock, capped at 90 min a day). Waits for heat or low
  battery and resumes by itself; honest "Waiting / Paused at X of Y" notices, and Pause / Continue buttons.
- **C:** safer index files (directory fsync, a streaming iOS store, libraries above about 30k tracks still behave as in 0.9.3; raising that limit is parked). SMART
  presses no longer wait behind a whole chunk of analysis. Fixes for hung decoders and failure handling (D1–D5).
- **D:** iOS background tasks and Battery Saver slow-down.
- **E:** optional speed items, each only if its measured rule passes.

**The trade-off users feel.** On a cheap phone a first analysis of 10k tracks takes about 2–3 days with daily app
opens. Without app opens it keeps going on screen-on and playback time (on API 35+ until the system's 6 h cap), and an app open or Continue resumes it. While the pass
is incomplete or paused, the Map and For You regions use metadata only, as in 0.9.3.

**Compared with 0.9.3:**
- 0.9.4 adds **no new automatic stops on any API level**. Heat, low battery and a spent budget only make it wait, and
  it resumes by itself, as 0.9.3 does on screen-on. A hung file is skipped on fresh workers, as in 0.9.3.
- On API 35+ the system's 6 h cap still ends the service, as in 0.9.3. The next app open resumes it.

**What does not change:**
- stored vectors and their order (except D1–D5);
- SMART's answers (except on the D1–D5 tracks);
- the model;
- the Map's behaviour.

**What the owner decides (§7):**
- D1–D5;
- a test phone, or a test build on the S24 that reads only a test-files folder;
- whether charging counts in the wake-lock budget;
- the Play declaration risk;
- per-row Retry;
- the iOS "keep analysing" action;
- snapshot reuse during analysis, and fused regions before completion (both default "no").

## 0. Hard rules

1. **Stored vectors do not change.** For the same file on the same platform, 0.9.4 stores the same rows as 0.9.3:
   binary16 for audio, float32 for text. They come in the same order, which is the order of a 0.9.3 full snapshot
   of the same in-memory index. With the vectors unchanged, nobody re-analyses, SMART does not move, and both
   `AUDIO_MODEL_VERSION` (`mnv4-960-clean-a0-fma-v1-p50q4aq`) and the text identity stay as they are.
2. **Declared deviations, and only these.** Each needs the owner's yes, and each is reported in the tests.

   | id | what changes | why |
   |---|---|---|
   | D1 | On **both platforms**, a timeout becomes *transient* (retried with backoff) instead of a remembered `InvalidAudio`. One exception: a timeout whose worker is later classed stuck (§4 1c) is remembered as Broken. Timeouts that 0.9.3 already remembered stay as they are, because their failure rows keep no reason. The budget clock starts when the inference side begins waiting for that track's decode, as it does today (DL:136-140) | R3 |
   | D2 | A transient failure that is **track-local** stops after 3 attempts with backoff and is shown as failed with Retry. Track-local means `Unavailable` at the SOURCE_OPEN stage or a source-access failure, or a timeout of the track's own decode or inference segment. CODEC-stage `Unavailable` (contention), model, session, generation, cancel and OOM outcomes never count as an attempt. 0.9.3 retries every transient failure at every pass, forever | R11 |
   | D3 | Retry clears only the tracks it is pressed for, not every remembered failure | R11 |
   | D4 | A track whose window was `Unavailable` twice is stored first from its other windows, in chunk order, as 0.9.3 does. After the pass it gets one more try. If that succeeds, its row is upserted in place with the full 3-window vector (the upsert keeps its position), which is what a clean 0.9.3 run stores | R14 |
   | D5 | The codec cap and the back-off (§4 1c) let some windows succeed that 0.9.3 would leave `Unavailable`. Those tracks get the full clean-run vector, not the partial row 0.9.3 would store. G-f reports the set | R14 |

   What a user notices:
   - D1/D2: a file that is briefly unavailable or slow is analysed later instead of being marked failed for good. A
     truly broken one is shown with its reason after 3 tries.
   - D3: Retry affects only the tracks chosen.
   - D4/D5: a few more tracks get their complete vector (typically the ones hit by codec contention). Nothing else
     changes.

   Rows may differ from a 0.9.3 run only for tracks covered by D1–D5. The tests list that set. After a Rebuild, rows
   equal a clean run; the 0.9.3 oracle has no defined state for a Rebuild during a gated relaunch.

   Anything else that cannot be exact needs a tolerance declared before it is measured, plus a SMART check;
   otherwise it is dropped. The yardstick for "harmless" is a difference that already exists between the platforms:
   iOS resamples with AVAudioConverter, while Android interpolates linearly (`AndroidAudioDecoder.kt:550-573`).
3. **Every speed change passes the golden gates of §2.2.**
4. **Owner rules.**
   - Never use the owner's library or listening history. Benchmarks and gates read an **explicit corpus directory**
     (app-private files pushed with adb, or bundled into a test APK), never MediaStore. That way any device, the S24
     included, can be used without touching his library.
   - While analysis is partial or paused, SMART behaves exactly as in 0.9.3: the analysis-order spread and the
     existing UI hint.
   - Nothing is installed on the owner's phone without his word.
   - No audible audio. Tests run muted: emulator audio off, and volume at 0 before anything plays.
   - At most 4 workers per job. Keep at least 30 GB of disk free, and delete intermediates.
   - No AI attribution in commits, and no invented facts in public text.

## 1. What the code does now (verified against 1730aa24)

Abbreviations:

| short | file |
|---|---|
| ENG | `core/smart/src/commonMain/.../DefaultSimilarityEngine.kt` |
| ABK | `core/smart/src/androidMain/.../EmbeddingBackend.android.kt` |
| IBK | `.../iosMain/.../EmbeddingBackend.ios.kt` |
| DEC | `.../androidMain/.../AndroidAudioDecoder.kt` |
| DL | `.../commonMain/.../TrackDeadline.kt` |
| AG | `composeApp/src/commonMain/.../AppGraph.kt` |
| SW | `iosApp/iosApp/IosOnnxInferenceProvider.swift` |
| FIS | `.../androidMain/.../FileIndexStore.kt` |
| FISI | `.../iosMain/.../FileIndexStore.ios.kt` |
| VIS | `.../commonMain/.../VersionedIndexStore.kt` |
| NOT | `composeApp/src/androidMain/.../IndexingNotifier.android.kt` |
| NOTI | `composeApp/src/iosMain/.../IndexingNotifier.ios.kt` |

**The run.** `ensureAutomaticIndexing` (AG:538-~726) runs in the app scope. A Compose `LaunchedEffect` triggers it
(App.kt:1199). The run does these steps in order:
1. sync the library and carry over tag edits;
2. if forced, `retryFailedTracks(all ids)` (AG:609);
3. the **metadata pass**: chunks of 8, with a checkpoint every 32 tracks (AG:636-643);
4. the **audio pass** in `analysisOrder`, in chunks of 8 (AG:878), with a checkpoint every 32 tracks (AG:882;
   cadence at AG:886-909) and `delay(75)` after each chunk;
5. `ensureEnergies`.

Android keeps the process alive with a `dataSync` foreground service (`androidApp/src/main/AndroidManifest.xml:119`; `RECEIVE_BOOT_COMPLETED` is removed on purpose at :40-45). It holds **no wake lock**: the
codebase has none, although the WAKE_LOCK permission is declared. iOS takes `beginBackgroundTask` once per run (NOTI).

**A chunk** is `stageLibraryIndex` (ENG:250-372), in three phases:
1. under the engine `mutex`: partition the tracks, apply remembered failures, load the model lazily;
2. under `inference` only, for the **whole chunk** (ENG:310-318): `backend.embedEach`. On Android this is one
   `DeadlineDecoding.each` per chunk. Its look-ahead fills and drains within the chunk, and its `finally` abandons the
   workers if any decode is still in flight (DL:116-162);
3. under `mutex`: install in chunk order, encode text, and persist if asked.

**A track on Android:**
- **Windows.** Up to 3 windows of 10 s at 20/50/80 % of (duration − 10 s). A window at 0 s is used for short tracks,
  and as a head retry when all three fail (ABK:254-263). A window at 0 ms is decoded without a seek (DEC:266).
- **Decoding.** Each window gets a new fd, a new `MediaExtractor`, a new `MediaCodecList` lookup and a new codec
  (DEC:208-315). It is decoded to 32 kHz mono into a fresh zero-filled array (DEC:556).
- **Model.** A0 is `ml/mnv4_audio.onnx`, 3.3 MB. Its graph has 46 `latentjam.Q4Conv1x1` nodes (core/ort-ops, kernel
  path picked at run time from i8mm, dotprod or portable) and 32 `QLinearConv` nodes. Input and output are fixed:
  `[1, 320000]` → `[1, 960]`.
- **Gain retries.** A window is retried in place at gain 1, 0.5 and 0.25 if its output is non-finite (ABK:198-222).
- **Pooling.** Window outputs are summed in window order, then L2-normalised.
- **ORT settings.** intra = inter = 1, sequential execution, no arena, no execution provider (`OrtSessions.kt:26-34`).
- **Threads.** The decode threads and the engine's inference thread run at `THREAD_PRIORITY_BACKGROUND`
  (ABK:85, `SmartDispatcher.android.kt:24`).
- **Workers.** 1 worker on `isLowRamDevice`, 2 with ≤ 4 cores, 3 otherwise (ABK:70-79).
- **Prefetch retry.** A window whose prefetch came back `Unavailable` is decoded again, alone, on a worker
  (ABK:226-229).
- **Budget.** 90 s per track, counting the decode wait and the inference (DL:26, 136-140).

**A track on iOS.** One native call per track, which decodes and infers in series (SW:52-154). Each window opens a
new `AVAudioFile` and a new `AVAudioConverter` (SW:349-402). The audio and semantic models load lazily inside every
call, with no lock (SW:44-50). The call runs on `Dispatchers.IO`. `abandon()` is a no-op, so a call that ran out of
budget keeps running beside the next one (IBK:33-39).

**Text.** The string is `genre; artist; year; language word` (+ artist decade) (`TextEncoder.kt:63-81`). The title
only feeds the per-title language guess. Encoding runs under the engine `mutex` (ENG:353-356, 840-843), with no
cache.

**The engine `mutex` is also held**:
- for every SMART press, which can itself write full snapshots: `persistForPlayback(persistTextIndex)` at ENG:941,
  and the audio save after a seed embed at ENG:953-977;
- for the whole energy backfill (ENG:636-666);
- while a press rebuilds the `SmartSnapshot`, including `cacheSemantics`. Every install sets `snapshotCache = null`
  (ENG:1416, 1445-1452), so during analysis every press rebuilds it.

**Index files, Android.** Every save writes the **whole** snapshot to `.tmp`, fsyncs it and renames it (FIS:232-299).
There is no directory fsync. The two indexes differ:

| index | row encoding (verified) | size per row | at 10k rows |
|---|---|---|---|
| audio | binary16 (`half = true`, ABK:454) | ≈ 2.0–2.1 KB | ≈ 20 MB |
| text | float32 | ≈ 1.6 KB | ≈ 16 MB |

The audio store sits inside `VersionedIndexStore` (VIS), which has two behaviours that matter here:
- `retireLegacy()` deletes the legacy file on the first versioned save (VIS:51-64);
- the legacy file is read only when the version file delivered nothing (VIS:36-46).

**Index files, iOS:**
- **Writing:** the whole snapshot is encoded into a `ByteArray`, copied to `NSData` and written atomically (FISI:84-91).
- **Reading:** there is no streaming reader. `NSData` and a `ByteArray` copy exist at the same time (FISI:107-125).
- **Location:** Caches (FISI:141-155). The map stores sit in Caches too.

**Envelope.** Both platforms have a 64 MiB envelope (FIS:483, FISI:408). With halves it is reached at about 30k audio
rows and about 38k text rows. The "~17,000" KDocs (FIS:32, FISI:403-408) predate halves.

**Ties.** `nearest()` breaks equal scores by insertion ordinal (InMemoryVectorIndex.kt:154-166). So **row order is
part of SMART's output**: duplicate files give exact ties.

### 1.1 Findings: speed (weights to be measured in Step 0)

| # | finding | where |
|---|---|---|
| P1 | Per window: a new fd, `MediaExtractor` and `MediaCodecList`, plus codec create, configure, start and release, 3–4 times per track | DEC:208-315 |
| P2 | Decode throughput on an S24 Ultra is 1.8 s per track alone and 0.34 s per track with 3 workers (a pre-0.9.3 measurement, ABK:65-69). Inference takes 3 × 65–93 ms ≈ 0.2–0.28 s per track | ABK |
| P3 | **All analysis threads run at `THREAD_PRIORITY_BACKGROUND`.** On many big.LITTLE SoCs that confines them to little cores. The S24 Ultra's 1.8 s per track is slower than a 4-core emulator's 0.72 s. It also slows play-time seed embeds, which run on the same thread. Priority does not change bytes | ABK:85, SmartDispatcher.android.kt:24 |
| P4 | **The pipeline drains every 8 tracks.** Each chunk is its own `embedEach`, and phase 3, the checkpoint and `delay(75)` run with no decoding in flight. So each chunk pays one full single-track decode latency (≈ 1–1.8 s), a possible 30–50 % overhead. A chunk that finishes normally does not abandon its workers: DL:157-160 abandons only when a decode is still incomplete | DL:116-162, AG:654-692 |
| P5 | O(N²) checkpoint writes for **both** passes. Audio: ≈ 310 full saves at 10k tracks ≈ 3 GB. Text, in the metadata pass: ≈ 2.5 GB, and again on every re-encode. Every save runs under `mutex` | FIS:232-299, ENG:853-860, AG:636-640 |
| P6 | iOS: decode and inference in series; `AVAudioFile` reopened per window | IBK, SW:350 |
| P7 | One inference session with intra = 1 caps inference once decoding is fast. Separate sessions with intra = 1 on separate tracks compute the same bytes by construction (see 2c for the conditions) | OrtSessions.kt |
| P8 | Identical text strings are encoded again and again | ENG:1403-1420 |
| P9 | Look-ahead memory: up to `ahead + 2` tracks × 3 windows × 1.28 MB, plus source-rate chunk arrays and their concatenation (DEC:338, 482-487). That is ≈ 20 MB of churn with 3 workers | DL:125-139 |
| P10 | Resample and downmix Kotlin loops: probably a few ms per window | DEC:492-573 |
| P11 | Android copies at the ORT boundary (`FloatBuffer.wrap`, a 1.28 MB copy, ≈ 0.1 ms): negligible. **The iOS bridge is not negligible if waveforms cross it**: `KotlinFloatArray(_:)` and `swiftArray` copy element by element (SW:809-823) | ABK:341-354 |
| P12 | Batching the windows into `[3, 320000]` is impossible without re-exporting the graph (batch is fixed at 1). The gain at intra = 1 is small and parity would be at risk. **Dropped** | — |

### 1.2 Findings: reliability

| # | finding | where |
|---|---|---|
| R1 | **Long `mutex` and `inference` holds.** The `inference` lock is held for a whole chunk. A seed embed (ENG:507-532, 959) waits behind it under `mutex`, and the seed's own decode and inference also run under `mutex`. Presses also write full snapshots under `mutex` (ENG:941, 953-977). `ensureEnergies` holds `mutex` for the whole backfill. Each press during analysis rebuilds the `SmartSnapshot` | ENG |
| R2 | A kill loses up to 32 + 7 analysed tracks (memory only until a checkpoint) | AG:882 |
| R3 | Android only: a timeout is remembered as `InvalidAudio` until Retry, even when its cause was transient. iOS already forgives one timeout across a suspension (DL:55-67, IBK:86-100) | DL:43-44, 104-108 |
| R4 | Hung decode threads keep their codec and fd for the life of the process. A cancelled pass also abandons workers whenever a decode is in flight (DL:157-160). `shutdownNow` lets threads that are not hung finish their window and release their codec, so after a library change this is a short overlap of up to 3 extra codecs, not a leak, but it can still produce `Unavailable` windows. On iOS, a timed-out call keeps decoding and running inference | DL:157-160, ExecutorDecodeWorkers.kt:34-39, IBK:33-39 |
| R5 | **No wake lock.** With the screen off, not playing and not charging, the CPU suspends and the pass stops. The ETA then lies. This never shows up while music plays | NOT, manifest |
| R6 | **Foreground-service rules.** The type is `dataSync`; Android 15 added `mediaProcessing` for media work. `startForegroundService` from the background throws on Android 12+, and the generic catch then abandons the pass with only a `println` (AG:805). The 6 h cap (`onTimeout`) stops big libraries on slow phones as a normal case: 50k × 0.34 s ≈ 4.7 h on an S24, and 3–5× that on a cheap phone. Nothing resumes until the app is opened (`START_NOT_STICKY`) | NOT:110-123, manifest:119, AG:805 |
| R7 | **iOS background:** one `beginBackgroundTask` per run (about 30 s, and none after the first backgrounding). While music plays (audio background mode), analysis runs at full CPU, and iOS kills apps that keep CPU high in the background, which kills the music too. Only `beginBackgroundTask` is used; `BGContinuedProcessingTask` (iOS 26) and `BGProcessingTask` are not | NOTI |
| R8 | The iOS index and map stores live in Caches, which the OS can purge: a full re-analysis follows. File protection is implicit: class C by default | FISI:141-155 |
| R9 | Above 64 MiB (about 30k audio rows), nothing persists | FIS:28-32 |
| R10 | Silent catches: text encode → null (`TextEncoder.android.kt:88-90`); energy save `runCatching` (ENG:680-690); half migration (FIS:161-164); iOS classify and encodeText → nil (SW:229, 265); a pass failure is only a `println` (AG:805). The prefetch catch (ABK:169-173) is by design | listed |
| R11 | Transient failures are retried at every pass with no backoff or cap. Retry clears the remembered failures of **every** track (AG:609, ENG:374-389) | listed |
| R12 | No directory fsync. A damaged snapshot means the whole library is re-analysed. No process-kill tests exist for analysis | FIS:264-269, ENG:1383-1387 |
| R13 | Memory: look-ahead ignores both heap and native memory. Sessions are never released. There is no `onTrimMemory` or memory-warning handler. `isLowRamDevice` is true only on Go-class devices | ABK:70-79 |
| R14 | Partial rows: a window that is `Unavailable` twice (prefetch, then a lone re-decode) is skipped for good. In practice this comes from codec exhaustion (R4) | ABK:224-256 |
| R15 | Analysis and playback share codecs and CPU. Crossfade can hold two playback decoders. Nothing measures underruns or decoder-init failures during analysis | — |
| R16 | Races: iOS lazy model init (SW:44-50); ljq4 mutates kernel caches on first use (ljq4.cc:404-424), so one session must never run concurrently | SW, core/ort-ops |
| R17 | Open notes from the 0.9.3 review: R1-I-4 (on 1-worker devices a re-decode queues behind a hung look-ahead) and R1-I-3 (a late restore keeps stale rows) | docs/release-0.9.3-review-2.md in git history (removed in 160f0bc6) |
| R18 | No golden test for audio vectors: `SmartInferenceDeviceTest` checks only finiteness and norm | androidDeviceTest |


## 2. Step 0: measure, with predeclared decisions (no product change)

### 2.1 Devices and corpus

**Devices.**
- **Physical:** one arm64 Android phone, preferably low/mid-range with 3–4 GB RAM and big.LITTLE cores. The S24 also
  works once the owner allows it, because the benchmarks read only the corpus directory.
- **Emulators:** LJ-api24 (48 MB heap), LJ-api30 (rootable), Pixel_7_Pro.
- **iOS:** the simulator. The iOS background checks need a real iPhone, which is the owner's call.

Until a physical phone is available, emulator timings count as relative only.

**Corpus.** About 300 FMA tracks under commercial CC licences (MP3), plus local transcodes. They are for tests only
and never shipped. The set covers:
- **Containers:**
  - AAC in M4A, ADTS `.aac` and MKV/WebM;
  - FLAC native, in Ogg and in MP4, at 16 and 24 bit;
  - Ogg Vorbis; Opus in Ogg and in WebM;
  - WAV at 16 bit, 24 bit and float32 (the `pcmFloat` branch);
  - MP3 VBR with Xing/ID3 headers and wrong durations.
- **Layouts:** mono, stereo and 5.1.
- **Sample rates:** 22.05, 44.1, 48 and 96 kHz.
- **Edge lengths:** shorter than 10 s, a window running past EOF, longer than 20 min.
- **Duplicates:** exact copies, to produce ties.
- **Fuzz:** the `tools/fuzz/corpus` audio seeds.

**Libraries.**
- **1k:** distinct sources (transcodes vary). It supplies the decode timings.
- **10k:** hard links in app-private storage (`run-as ln`) to the 300 sources, plus a copy→source map, so it costs
  no extra disk. The page cache makes its decode times optimistic, so it is used only for correctness (G-c) and the
  end-to-end flow.
- **Checkpoint cost at 10k and 30k:** measured with synthetic rows, without decoding.

### 2.2 Golden gates (produced by the 0.9.3 code before any change)

| gate | what | where | needed before |
|---|---|---|---|
| G-a | A CRC32 of every decoded window, compared with the **0.9.3 golden** | each device | 2a, 2b |
| G-b | Window embeddings for fixed waveforms, with ljq4 forced to each kernel path the device supports | each device | 1b-0, 2c |
| G-c | Real-device runs log `Unavailable` windows per run, and D4/D5 tracks are left out of the exact-order comparison. Compared: final audio rows and their order, text rows and their order, failure rows (as a set), energy rows. The 10k links are checked against their source rows. All on a deterministic script: no presses, no injected failures, and compared only at identical restart points (`adopt` renormalises on load) | each device | every slice |
| G-d | SMART smoke test: `nearest` and a fixed `smartQueue` on a library with duplicates | host + one device | every slice |
| G-e | Store property test: random mutation sequences (upsert, in-place upsert, a seed then a chunk installing the same id, remove, re-add, failure add/forget/clear, `carryOverAudio`, text re-encode, restore retry with a live seed, damaged/torn/stale tail, a failed tail write) with persists and reloads interleaved. The oracle is a 0.9.3 `FileIndexStore` (a frozen copy of the 1730aa24 source in test sources) full snapshot of the same in-memory state: rows byte-equal, order equal, failure set equal. For the 1a fixes it covers the envelope, the iOS streaming store and directory fsync | host | 1a, 1a-tail |
| G-f | Differential engine test: the 0.9.3 engine (a frozen copy of the 1730aa24 `DefaultSimilarityEngine` in test sources) is the oracle, with a fake deterministic backend. Scripted presses and seed embeds at chunk boundaries, removals mid-run, Retry, and scripted failure classes. It compares rows, order, failures and energies after every step, allowing only the D1–D5 set | host | 1b, AnalysisRun, 2d |

**Noise.** 0.9.3 runs twice on each device. Window bytes that differ get a recorded noise band. Order has no noise
band.

**Allow-list.** A decode change is allow-listed per (container × mime × codec name). The container comes from the
extractor's metrics format (`android.media.mediaextractor.fmt`) or a sniffed signature. Any triple not in the corpus
keeps today's path.

### 2.3 Measurements

Minimum set, on the physical phone:
- **Per track:**
  - wall time and core placement at `BACKGROUND` vs `DEFAULT + LESS_FAVORABLE`;
  - measured both with the app visible and with the screen off under the foreground service, because the process
    cpusets differ;
  - at least 3 runs per arm.
- **Idle gap per chunk** (P4).
- **Tracks done in 30 min**, screen off, unplugged, not playing:
  - without a wake lock;
  - with a wake lock;
  - with a wake lock plus 2e pacing.
- **Jank** (janky-frame %) during a scripted scroll.
- **Playback health:** `onAudioUnderrun` count and decoder-init failures during muted crossfade playback.
- **Energy:** batterystats per-UID estimate per 100 tracks (ODPM rails where present), at least 3 runs per arm.

Everything else (`DecodeStageTimes`, press waits, heap/native/RSS, per-session memory) runs on emulators or waits for
the item it gates. The iPhone checks (background CPU %, CPU-resource diagnostics, whether a sustained-CPU kill happens
at all, which is unverified) come before any iOS duty-cycle number is chosen.

**Platform matrix (written).**
- **Columns:** API 24, 26, 29, 31, 34, 35, 36, plus OEM (Samsung, MIUI/HyperOS; dontkillmyapp.com).
- **Rows:**
  - background FGS start and its exemptions (incl. battery-optimisation allowlist);
  - dataSync vs mediaProcessing (same 6 h cap; the cap resets in the foreground);
  - Play "excessive partial wake locks": ≥ 2 h in 24 h of non-exempt partial wake locks while in the background or
    under an FGS; charging is unclear between sources, and the default counts it; job wake locks are counted
    (blog), map-rebuild job time included;
  - Doze: whether a partial wake lock held under an FGS is honoured in deep Doze and in OEM idle modes, measured
    over ≥ 90 min stationary plus `dumpsys deviceidle force-idle`;
  - FGS start/stop race;
  - the Android 14+ swipe-away FGS notification and the Task Manager Stop;
  - reboot (no `RECEIVE_BOOT_COMPLETED`);
  - iOS `beginBackgroundTask`, `BGProcessingTask`, `BGContinuedProcessingTask`.

### 2.4 Predeclared decision rules

| item | build or keep only if |
|---|---|
| 2-0 priority | Over ≥ 3 runs per arm, each starting at the same battery level (± 5 %) and temperature (± 2 °C), and outside the measured noise. All must hold: tracks done in 30 min rise ≥ 15 %, with an abort if thermal status reaches SEVERE; jank % stays inside the 0.9.3 band; measured energy per track (the `BATTERY_PROPERTY_CHARGE_COUNTER` delta; batterystats per UID is reported but does not decide; if the counter is unsupported or coarse, energy is undecided and `BACKGROUND` stays) rises ≤ 10 %; no underrun or decoder-init failure during muted crossfade. Each state, visible and screen-off FGS, is decided on its own |
| 2a.2 / 2a.3 | Per (container × mime × codec name) triple: G-a **equals the 0.9.3 golden** on every corpus file of that triple, on that device, in 2 runs |
| 2c parallel inference | Inference is ≥ 35 % of wall time after 2-0, 2a and 1b, and peak memory fits the device-class budget |
| `:analysis` process | At least 1 real hang on the physical phone (corpus + fuzz) after 1c, or a user report of one. It also needs a single-writer store design |
| P10 resample/downmix | More than 5 % of wall time |
| Battery-temperature fallback (row 3) | Battery temperature is recorded in the charging night too. 42 °C / 38 °C stay only if ≥ 42 °C covers < 10 % of charging-under-load time; otherwise 45 °C / 41 °C |
| Thermal threshold (T, H), table row 3 | Status and headroom are recorded **with the pass running** at the chosen 2-0 arm: the Step 0 30 min runs plus one charging night. Default: T = MODERATE, H = 0.85. If MODERATE covers ≥ 10 % of charging-under-load time, then T = SEVERE, H = 0.95. Resume uses T−1 and H − 0.1 (row 3). These defaults come from one phone; on API 35+ H is taken from `getThermalHeadroomThresholds()` for status T, and iOS uses `.serious`, as the per-device check. The headroom part ships in Slice B2 (API 30+). 2e has no thresholds of its own |
| Snapshot reuse during analysis | Owner decision |

The geometric cadence of v3 is **dropped**: it made the kill-loss window worse than 0.9.3. The 0.9.3 cadence stays,
and 1a-tail is conditional (§4 1a).

## 3. Work order

The cheap, user-felt levers come first. Each slice is its own set of logical commits. Only items marked † need the
physical phone. Everything else ships on the host and emulator gates.

1. **Slice A.** The Step 0 minimum set (the † parts as soon as the phone exists), the platform matrix, and G-a, G-c,
   G-d. G-b, G-e and G-f each come just before the items they gate.
2. **Slice B1, quick wins:**
   - the FGS start/stop race fix, notify-only progress, the explicit type; a failed `show()` ends the pass as an
     exception does today (no crash), with a log line;
   - 2a.1, the codec-list cache;
   - 2-0 †, if its rule passes;
   - iOS: `beginBackgroundTask` on every move to the background (the `expired` latch, NOTI:39).
3. **Slice B2:** the 1e-min state table (rows 1–6), the wake lock and its budget, Pause and Continue, the stop and
   restart semantics, and the visible states. B1 ships even if B2 slips.
4. **Slice C:**
   1. 1a fixes: directory fsync, and the streaming iOS `loadRows`, writer and half migration; then the iOS
      Application Support copy (it needs the directory fsync);
   2. 1b-0, the API split;
   3. 1b, locks and `AnalysisRun`;
   4. 2d, the text cache with single-hold installs;
   5. 1c;
   6. 1d (D1–D5).

   1a-tail follows only if its rule fires.
5. **Slice D:** 1e-ios (the background tasks, own review round), then 2e pacing with its pause state machine.
6. **Slice E**, each item by its rule and the owner's call: 2a.2/2a.3, 2b and 1g.

**Parked** (not in 0.9.4 unless a rule or the owner pulls them in):
- the `:analysis` process;
- 1f, the black box;
- 2c, parallel inference;
- 1a-tail;
- P10;
- Android WorkManager slices and the speed mode;
- the iOS duty cycle until an iPhone is available.

## 4. Reliability items

### 1a. Index store: keep 0.9.3's full snapshots and fix around them

Rounds 2–4 found a blocking order or replay defect in every incremental store design. A full snapshot reproduces
0.9.3's bytes and order by construction. So **0.9.4 keeps 0.9.3's full-snapshot writes and cadence** (every 32
tracks, at most 39 lost on a kill, as today) and fixes only what does not touch the format:

- **Directory fsync** after every rename, on Android (`Os.fsync` on a directory fd) and on iOS.
- **Streaming iOS reader and writer.** POSIX `open/write/fsync/rename`, with `F_FULLFSYNC` for the base, and never
  `-[NSFileHandle writeData:]`. Today the whole snapshot is built in memory, and the read holds both NSData and a
  ByteArray (FISI:84-125).
- **Envelope: unchanged (64 MiB).** Raising it was D6 in v5–v19. Every version drew new findings (size-scaled
  cadence, per-device caps that move with free memory, iOS jetsam on restore), so it is **parked** for a later
  release with its own design. Libraries above about 30k tracks behave as in 0.9.3.

If a restore fails (an I/O error, or an OOM on Android, ENG:1371-1378), the file is never deleted. **An unreadable
index behaves exactly as in 0.9.3**: it is not a stop reason, and the audio pass is **not gated** by the table and
takes no wake lock while the engine reports the restore failure (a new engine query that reflects
`audioRestoreFailed`). The pass's one restore retry (ENG:259-264) runs **before** the gate is evaluated, so a pass that
heals there is gated normally. The ungated path still skips `show()` while `userPaused` is set. So every chunk fails at once with `ModelUnavailable`, and the pass sets `complete` within
seconds, as in 0.9.3. The engine's press-time retries (`retryRestoreIfDue`, ENG:1332-1343)
can heal it within the process; after a heal, the `force` Retry resumes analysis, as in 0.9.3. Rebuild asks first:
"The stored analysis will be deleted, and analysis restarts now for the whole library (on a slow phone that can take
days)". The relaunch runs in a `finally`, so a failed `clearAnalysis` still relaunches.
While that engine query reports the failure, the pending Retry-ids latch is not consumed.

- **Presses persist as in 0.9.3.** `retryFailedTracks` persists before it returns.
- **`.retry` sidecar** for D2 attempt state (see 1d). It is not part of the snapshot format.

**1a-tail (conditional).** Built only if Step 0 shows, at 10k on the physical phone, one of:
- checkpoint time > 3 % of wall time in the audio pass, or > 10 % in the metadata pass;
- the press `mutex` wait from checkpoint writes > 1 s at p95.

If built, it must meet every requirement the review found. Each one has a G-e case:

1. A single tail file of appends only, keyed by a base fingerprint, with a CRC per record and two-pass validation. A
   tail problem never drops base rows.
2. Base state is clean, none or unknown. A load into a non-empty index, any skipped row or failure, or any adopted row
   whose re-encoded bytes differ from the file bytes (`adopt` renormalises; text float32 is not a fixed point) makes
   it unknown, which forces a full base.
3. The tail delta is (the 0.9.3 snapshot view now) minus (the durable base + tail). It keeps FIS's disjointness rule:
   no failure for an id that has a row (ENG:1661-1667, FIS:247).
4. Structural counters force a full base on:
   - any in-place upsert, removal or clear;
   - any identity change of a durable id;
   - any failure removal.
5. **Superseding.** Before a base rename, a `supersededBy(F_new)` record is appended and fsynced. On load, a tail
   whose superseder equals the base fingerprint is dropped; otherwise only the records before the marker count. A
   fresh tail is a new header-only file written via tmp, fsync, rename and a directory fsync, never truncated in
   place. This covers a full base that is byte-identical to the previous one.
6. `clearAnalysis` deletes the tail first. A tail is never delivered without its own base, including VIS's legacy
   fallback (VIS:41-46).
7. A failed tail write makes the next persist a full base. The envelope counts base + tail, and `EnvelopeRefusal`
   must not block appends.
8. Opt-in for the audio and text stores only.

### 1b-0. Backend API split (before 1b and 2b)

- **New calls:**
  - `decodeTrack(track) → handle`;
  - `embedWindow(handle, i) → WindowResult` (a sealed class: embedding / unusable / failed), which is one `inferStable`; a timed-out segment is never confused with an unusable window;
  - `decodeWindow(handle, startMs)`, an on-demand decode for the two existing retries: the lone re-decode of an
    `Unavailable` prefetch window, and the 0 s head retry when every window failed (ABK:226-263). G-c has a case for
    each;
  - `pool(handle)`.
- `WindowResult.failed` carries the `AudioDecodeFailureStage`, which drives back-off and D2 (1c).
  `AudioDecodeResult.Unavailable` gains a stage field. Today it has none (DEC:64-68). The null descriptor (DEC:219)
  and the invalid fd (DEC:244) map to SOURCE_OPEN.
  On iOS, `localURL` returns at once for `file://` URIs (SW:334-336). So the mapping follows `classifyAudioDecodeError`
  (SW:171-187):
  - no-such-file, no read permission and not-readable map to SOURCE_OPEN;
  - `NSFileReadUnknownError` and `NSFileReadTooLargeError` are non-attempts;
  - Data protection is tracked with an atomic flag plus a flip counter, fed by the
    `protectedDataWillBecomeUnavailable` / `protectedDataDidBecomeAvailable` notifications.
    `isProtectedDataAvailable` is main-thread only. Both observers are registered first, and only then is it read
    once, on the main thread at startup. Until
    that read, data counts as unavailable, so every outcome is a non-attempt. The locked cold-launch test covers this. If the flag was false, or the counter
    changed during the call, the outcome is a non-attempt whatever the error code (a data-protection denial looks
    like EPERM);
  - the rest map to CODEC.
- **iOS:**
  - The handle is opaque, and waveforms never cross the bridge.
  - Pooling and normalisation stay in Swift (SW:426-440).
  - Models load once, under a lock.
  - A per-session lock covers every **model run and model load** (never a decode), including late runs from a
    timed-out call. The late call signals its completion, and the BUSY retry waits on that signal. It is taken by **try-lock, in a
    non-timed step**. If a late call holds it, the new call returns **BUSY**, which is transient, never an attempt,
    and never stuck. The Kotlin side retries after the late call ends. **On the analysis side** BUSY releases `inference`, waits for the completion signal outside every lock, then retakes
    it. **On the press path**, BUSY waits at most
    one model-run bound. After that the seed embed fails as transient (it is not remembered), and the press takes its
    existing no-audio-seed fallback, so a press never waits unbounded while it holds `mutex`. The completion signal
    also fires when the holder is classed stuck. G-f case: a press during an analysis BUSY. Only the call that held the lock can be
    classed stuck. When the lock holder is classed stuck, the provider opens **one fresh session** for the rest of
    the process, so BUSY can never loop. A second stuck holder makes presses skip the seed embed, and analysis
    tracks fail as transient for that process. G-f has this case.
  - Every handle has an explicit `release(handle)`. Cancel and abandon release in the completion path, so a late
    `decodeTrack` never strands about 3.8 MB of native buffers. The fault suite checks for leaks.
- **Android:** `embed` = the same calls in sequence, with the same arithmetic.
- **Seed decodes** get a one-thread priority lane: a **separate** `DecodeWorkers` and `DeadlineDecoding`, so an
  abandon on one side never strands the other side's queued work (ExecutorDecodeWorkers.kt:16-19). This also fixes
  R17 R1-I-4.
- Every abandon re-queues **all** incomplete pending work of its own pool, not only after a head timeout.

### 1b. Locks and `AnalysisRun`

**`inference` lock.**
- It is taken per `embedWindow`.
- A press waits on it at most one inference unit: one `inferStable`, or one `classify` batch of ≤ 128 rows.
- `generation` is `@Volatile` and snapshotted into the run. Under `inference`, the session and the generation are
  re-checked before each run. If either changed, the track fails as transient (not an attempt under D2) and is not
  remembered.

**Lock order.**
- `mutex` → `inference`.
- Nothing holds `inference`, or the new text lock, while acquiring `mutex`.
- `release`, `clearAnalysis` and a library change only **signal** a run while they hold `mutex`. They never join it.

**Press path: unchanged from 0.9.3.** `planQueue` and `nextTrack` keep their single `mutex` hold and their exact
order of decisions (ENG:506-537, 933-978): text install and persist, the fallbacks, `ensureAudioModel`, the seed
embed, remembering failures, then the plan. Only two things change:
- the seed embed decodes on the separate seed lane;
- the seed embed takes `inference` **per window**, so it interleaves with the analysis at window granularity, and
  analysis never holds `inference` across a whole chunk again.

The press still holds `mutex` while it embeds a seed, exactly as in 0.9.3. So no new interleaving of press and chunk
installs can arise, and press latency during analysis improves only by losing the wait behind a whole chunk's
inference. Model loads (`loadModel`, `loadSemanticModel`) are a declared inference unit.

**`AnalysisRun`** is opened with the whole `analysisOrder`.
- **Chunk boundaries.** All of 0.9.3's phase 1 (ENG:255-301) is replayed at each chunk boundary, under `mutex`.
  That includes the branch where the engine is not Ready or the restore failed: the whole chunk reports
  `ModelUnavailable`, with no text, no phase 3 and no persist (ENG:265, 301). Checks at submit also apply
  `audioRestoreFailed`. The rest of the replay:
  - `rememberTracks(chunk)`;
  - the one-time restore retry, and its "embed nothing while unreadable" rule;
  - the partition: indexed, remembered failure, no URI;
  - `ensureAudioModel` only when `toEmbed` is non-empty;
  - the model-failure fan-out.

  Checks at decode submit are only an optimisation:
  - a track rejected at submit but accepted at its boundary is submitted at the boundary (after a Retry, a sync
    re-key, or a forgotten transient failure);
  - decodes the boundary rejects are dropped.
- **Install.** Done as 0.9.3's phase 3 (ENG:323-372), in **one `mutex` hold**: the chunk's audio rows in chunk order,
  then the text rows for all chunk tracks, then the persist **when the cadence is due**. Text vectors are encoded outside the lock beforehand;
  the to-encode list is built inside the boundary hold.
  - A track that a press indexed *after* its chunk boundary is upserted in place with the chunk's vector, and its
    failure is removed.
  - As in 0.9.3, text is installed even when the chunk is no longer current (ENG:350-356). Only the audio results are
    dropped.
  - Text install applies 0.9.3's `id in textIndex` skip (ENG:1405-1406), so a row a press added in the gap is kept.
    If `release` bumped the encoder epoch after the vectors were encoded, they are not used. When the engine is Ready
    again, the text is **encoded inside the install hold**, which is what 0.9.3 does (no rows dropped, no order
    change), so rows never
    enter a released engine.
  - A track submitted late at a boundary goes in at its chunk position in the head queue.
  - G-f scripts presses before and after the boundary, and checks a **serialisability property**: under random
    interleavings, the final state equals some serial 0.9.3 order of the same operations. Presses keep their 0.9.3
    hold, so the only new interleavings are at window granularity on `inference`, and those do not touch state.
- **Persist:** at the cadence above. `close`, `cancel` and every pause flush dirty state under `NonCancellable`, as
  today's final checkpoint does (AG:579-590).
- **Decoding** keeps running across chunks. Phase 3 (install in chunk order, text, checkpoint) and the 75 ms yield
  run per chunk.
- **Lifecycle:** explicit `close`/`cancel`, bound to a generation. AG consumes per-chunk reports; progress, ETA and
  notifications are unchanged. G-f is the gate.

**Budget.** The deadline is **segmented**, so that it can keep `TimeoutCancellationException` semantics (DL:96):
- **When the clock starts:** the head's clock (a `TimeMark` plus the remaining time) starts only after its chunk's
  boundary hold has finished. 0.9.3 never counted phase 1 either (ENG:255-301 run before 310).
- **Segments:** each decode await and each window inference runs in `withTimeoutOrNull(remaining)`.
- **Lock waits:** taking `inference` happens between segments, outside any timed segment. A wait there is bounded by
  one inference unit: one window run, one `classify` batch, or one model load.
- **Other waits:** every other wait counts, exactly as in 0.9.3. That includes workers, codec slots and back-off. On
  expiry the pool is replaced as today (DL:141-154), so a hung look-ahead can never stall the head.
- **D2 accounting:** an attempt counts only when the expired segment was the track's **own** decode or inference
  while it held a worker and a codec. So a hanging file never charges its queue-mates, including a head whose
  re-decode waits behind a hung look-ahead.
- **G-f fault cases:**
  - 1 worker, a hung look-ahead, a head that needs a re-decode;
  - a seed timeout during a look-ahead, and the reverse;
  - a head waiting behind a long phase-3 hold;
  - structurally: AnalysisRun never calls AG's consumer (`missingFromIndex`, checkpoint, notifier) inside an
    `inference` hold (today `onResult` runs under it, ENG:313-317).

**Press latency.** Measured as two numbers:
- the `inference` wait (≤ one unit);
- the `mutex` wait, bounded by the longest of a phase-3 install, a snapshot write, or `cacheSemantics`;
- the text-lock wait, bounded by one encode batch.

All three are measured in Step 0 and reported.

**Energy.** `ensureEnergies` releases `mutex` between its batches of 128. Before installing each energy it re-checks the
`audioVectorIdentities` token and the generation, and it clears `snapshotCache` in each hold. A D4 in-place upsert
persists the removal of the old energy in the same install hold. A kill can therefore never leave a stale energy
paired with the new vector (ENG:672-679).

### 1c. Hung decoders

- **Cancellation:** cancel, then a 5 s grace run by an asynchronous watchdog. Nothing awaits it inside a `mutex`
  hold or in `await`'s catch (DL:96). A worker still busy after that is abandoned and counts as possibly
  stuck.
- **No cross-thread `codec.release()`.** MediaCodec is not thread-safe, and most hang sites are outside the codec.
- **Stuck:** a thread still alive 30 s after abandonment. Analysis **carries on with fresh workers, as in 0.9.3**
  (TrackDeadline.kt:34-35, 158); there is no stuck pause. A track whose worker became stuck is remembered as Broken
  (D1), unless it is indexed by then, in which case it is skipped, as phase 1 does with `id !in index`. This
  happens through a `mutex` install that re-checks the identity, followed by a persist.
- **Codec cap:** the analysis gets `workers` codecs, or `min(workers, 2)` while playback is active, plus **one extra
  slot for the seed lane**. One-worker devices therefore keep 1 analysis codec and 1 seed codec. Stuck or abandoned codecs
  leave the cap and do not hold a slot. On a CODEC-stage `Unavailable` **while another analysis or playback codec is live**, or on
  `CodecException.isTransient` / `isRecoverable` or the resource error codes, the same window is retried after a
  back-off. That is up to 3 times, with **at most 3 s of back-off per track** in total, well inside the budget.
  Deterministic `configure`/`start` rejections with no other codec live are not retried. That covers
  `CodecException` / `InsufficientResource`, and also the IOException or RuntimeException that
  `createByCodecName` usually throws on exhaustion (DEC:79-95, 284). Only then is the window returned `Unavailable`.
  It never counts as a D2 attempt.
- **iOS:** a cancellation flag is checked between windows, and late results are dropped. A native call counts as
  stuck only after 30 s of **foreground running time** past its budget. The time is counted by a watchdog that
  advances only while the app is active, and it is gated by the same transition and background signals as
  `timeoutUnlessSuspended` (DL:45-67). So a suspension is never classed stuck. This covers hung decodes as well as
  lock holders. `forgivenBefore` is kept (DL:45-67): a second forgiven timeout of the same track counts as a D2
  attempt, so a really hung file cannot be retried forever. G-f case: suspend mid-call, then
  resume.
- **Back-off:** the sleep counts toward the 90 s budget, as every wait does.

### 1d. Failure classes (D1–D5)

- **Broken (remembered):** no audio stream, no decoder, a parser rejection, a stable non-finite output, or a stuck
  worker.
- **Transient:** retried at the next pass, then after 1 h, then after 1 day, timed with `elapsedRealtime` plus a
  wall-clock sanity check. Attempts count only under D2's rules. The UI shows the reason, for example "file
  unavailable", so a removed SD card does not look like a broken track. D4's after-pass retries are batched into one
  persist.
- **D4 mechanics.**
  - All windows are decoded and run again, summed in window order and then normalised; a stored row cannot be
    topped up.
  - D4-pending ids live in `.retry`, so a kill does not leave a row partial for good.
  - D4 runs before `ensureEnergies`.
  - D4 is capped at **one** retry. If that retry also fails, the partial row stays as in 0.9.3 and the `.retry`
    entry is closed.
- **`.retry` upkeep.**
  - It is pruned against the live library ids.
  - `clearAnalysis` deletes it. D3 Retry clears and persists it before returning.
  - Tracks that reached the D2 cap do not count in `missingFromIndex`, so no FGS or "0 of N" notification starts for
    them.
  - On iOS, an `AudioUnavailable` that was forgiven because of a suspension is not an attempt.
- **`.retry` sidecar:** small, rewritten whole. Each entry is id, failure identity, attempts and last attempt. It is
  pruned with failures (ENG:472) and ignored for ids now indexed or whose identity changed. Older builds ignore the
  file and retry as today.
- **Retry:** per track or "retry these" (D3).

### 1e-min. Keep running, Android (Slices B1 and B2)

**One state table** decides what the pass does. Rows are read top to bottom; the first match wins. The inputs are
re-read on every chunk and on every system event: screen on/off, playback start/stop, power connected,
`ACTION_BATTERY_CHANGED`, and the thermal listener.

| # | condition | pass | wake lock | FGS / notification |
|---|---|---|---|---|
| 1 | user paused (`userPaused`) | stopped | no | stopped; "Paused by you", with a Continue action |
| 2 | battery ≤ 15 % and unplugged | waiting, **unbounded**; resumes on power-connected (a dynamic receiver; the FGS keeps the process alive) or above 20 % | no | stays up; "Waiting: continues when charging" |
| 3 | too hot: thermal status ≥ T, or headroom > H (API 30+; read on every table event and chunk, at most once per second; NaN = no signal), or battery temperature ≥ 42 °C (`ACTION_BATTERY_CHANGED`; on API 24–28, and on any API where headroom is NaN and status has never left NONE, i.e. no thermal HAL). Headroom uses `forecastSeconds = 10` | waiting, **unbounded**; resumes after status ≤ T−1 **and** headroom < H − 0.1 (or < 38 °C) for 2 min | no | stays up; "Waiting: continues when cooler" |
| 4 | screen on, or playing | runs | no (the CPU is awake anyway) | up; progress |
| 5 | screen off, not playing, budget left, and **no audio envelope refusal active** | runs | **yes**: 5 min timeout, renewed per track | up; progress |
| 6 | screen off, not playing, and budget spent **or** an audio envelope refusal active | waiting, **unbounded**, as in 0.9.3 | no | stays up; "Waiting: continues when the screen is on or music plays" |

**No automatic stops on any API level.** Waiting rows are unbounded on every API. The only cap-driven stop is
`onTimeout` (the API 35+ 6 h dataSync cap), exactly as in 0.9.3. Coming back to the foreground resets that cap, and
ON_START restarts the pass. Stopping earlier to save the cap would save nothing, because any restart needs the same
app open.

**Envelope refusal.** Above about 30k audio rows (64 MiB), every audio save is refused (`EnvelopeRefusal`,
ENG:1546-1557), and that work dies with the process. A new engine query reports an active refusal. It is also true **before the first refused save in a process**: it
compares the index's estimated encoded size with the envelope. A new `IndexStore.encodedSizeOf` API exposes the
sizing that today is private to each store (FIS:490, FISI:408), and the engine keeps an incremental byte counter on
upsert, remove and failure changes, so the query costs O(1). It
turns false when a shrink brings the size back under, as `covers()` does. A host case covers a cold process. While it is set,
row 5 behaves as row 6: no wake lock, with analysis only while the screen is on or music plays. The UI says "This
library is too large to keep its analysis between launches". Slice D's `BGProcessingTask` follows the same rule.
There is a host case for it.

**Rules around the table.**
- **A stale "Waiting" notice** during sleep is accepted. The matrix records whether waiting time spent asleep counts
  toward the 6 h dataSync cap. It is informational only: no stop depends on it.
- **No ETA** while waiting, and no frozen progress claim.
- **Taps.**
  - **Continue** is an **activity** `PendingIntent`. It opens the app and clears `userPaused`, and the restart trigger
    below starts the pass. A pass can only start from the app, because only the app holds the scanned library,
    `librarySnapshotAuthoritative` and the localised strings (App.kt:1185-1214 → AG:538-556). A user tap on a
    notification action may open an activity on every API level. The service's start exemption
    (restrictions-bg-start) is not relied on.
  - **No live pass.** If `onStartCommand` runs with no live pass behind it, it calls `startForeground` only when the
    intent was started with `startForegroundService` (a marker extra), and then `stopSelf` at once. A plain
    `startService` (a Pause) just stops. It never leaves an empty ongoing notification.
  - Tapping the notification body also opens the app.
  - Tests:
    - a Continue tap after process death;
    - an app open with the activity retained;
    - a service start with no pass.
- **Budget.** The cap is 90 min of our own wake-lock time per rolling 24 h. Entries are stored as wall-clock time
  plus `Settings.Global.BOOT_COUNT`, and checked against `elapsedRealtime` within a boot, so the window survives a
  reboot. It counts charging, on every channel. Channel-specific rules would need a build flavor or an installer
  check, and the build has neither (no `productFlavors`); a flavor would also change the frozen F-Droid recipe.
  Google's sources disagree on charging:
  - the Play Console help (answer 9844486) says the metric is collected only when "not charging" and the screen is
    off, with a > 3 h per battery session threshold and a beta label;
  - the vitals page (updated 2026-09-21) and the 2026-03 enforcement blog do not mention charging, and use ≥ 2 h per
    24 h and > 5 % of sessions.

  Exemptions are audio, location and **user-initiated** jobs only, and the blog counts WorkManager and JobScheduler
  wake locks. The user-initiated data transfer route would need a network constraint, which the app does not have.
  So "charging counts" is the default, and dropping it everywhere is an owner call.
- **Estimate** (pre-Step-0, before Slice C; release notes quote only Step 0 numbers), 10k library on a cheap phone
  (3–5× the S24's pre-0.9.3 0.34 s/track, so 2.8–4.7 h of compute, plus up to 30–50 % chunk-drain overhead until
  Slice C):

  | how the phone is used | when the analysis finishes |
  |---|---|
  | app opened daily | about 1.5–2 h of work per day (the 90 min budget, plus screen-on and playing time, plus time with the app open): **2–3 days** |
  | app not opened | the same daily share, from screen-on and playing time and the refilled budget. On API 35+ it lasts until the system's 6 h cap, **possibly within the first night** if waiting time counts toward the cap (the matrix settles this); after that it waits for an app open |
  | charging overnight | the same 90 min cap |

  The table is the honest statement; the release notes say no more than it.
- **Stop and restart semantics.**
  - **What the table gates.** Waits, stops and `userPaused` gate only the **audio pass** (AG step 4) and the D4
    retries, and only while `missingFromIndex > 0` or D4-pending tracks exist. With nothing missing, the pass finishes exactly as in 0.9.3:
    `complete` is set, and `userPaused` is cleared (only when both sets are empty). A Pause that reaches a service whose last pass completed does
    not block it: the gate ignores the persisted flag and clears it. Host case: a stale Pause after completion, then a
    cold start; `complete` and the map must match 0.9.3. Steps 1–3 always run, as in 0.9.3: sync, tag carry-over and the metadata pass. So do energies on
    existing rows. So deleted tracks lose their rows and new tracks get text rows while paused. The check lives
    inside `ensureAutomaticIndexing`, so a configuration-change re-fire cannot bypass it. A gated or stopped pass
    never sets `complete = true`.
  - **Retry** (D3) runs the clear for the chosen ids inside the relaunched pass, after `synchronizeLibrary`, at
    AG:609's position. So it also works while the engine was still Initializing. `retryFailedTracks` returns 0 when the
    engine is not Ready (ENG:375-377). It then calls
    `ensureAutomaticIndexing(rerun = true)`. `rerun` is a new relaunch flag, separate from today's retry-all `force`
    (AG:609). It relaunches even a `complete` pass, and a pass that is mid-way restarts its audio pass so that
    already-visited chunks are covered. Like 0.9.3's `force`, it resets `complete` and `metadataReady` for the new
    run, so the map regions are metadata-only until the run completes again. That is the same as 0.9.3, not a
    regression. One exception: when the pass stays user-paused after the Retry, `metadataReady` is carried over and
    only `complete` is reset. The audio pass is still gated by the table (`userPaused`).
    Host/G-f cases: Retry after `complete` re-analyses the chosen ids; Retry mid-pass covers ids already visited.
  - **`stopReason`.** It is persisted with the stopped pass: user, timeout-6h, show-failed, exception. An unreadable index is **not** a stop reason (see 1a).
  - **What the restart rules decide.** They decide only whether the **audio pass** may run. Every explicit call
    (Retry, Continue, Rebuild), and every ON_START with a changed key, always relaunches steps 1–3 and Retry's
    failure clear. Host case: Retry while user-paused, answered "no". The failures are cleared, the tracks count as
    missing, no audio pass runs, `stopReason` stays user, and Continue is still shown.
  - **Rebuild** is its own kind of call (App.kt:4594: `clearAnalysis`, then a relaunch). It clears `userPaused` and
    the persisted `stopReason`, then relaunches like Continue. The Rebuild row shows in every non-live state. It is
    never disabled, apart from while a pass is live. `onRebuildAnalysis` **cancels and joins** the automatic job
    (a new AppGraph call) before `clearAnalysis`. AG takes a generation token at job start and passes it to
    `stageMetadataVectors` / `ensureMetadataVectors` and `AnalysisRun`; `clearAnalysis` bumps `generation`, and a call
    with a stale token installs nothing. That overrides ENG:350-356 (text for a non-current chunk), which is
    unreachable after cancel-and-join, and it is a listed G-f exclusion. A job with a stale token **aborts as not
    complete** (its own cancellation; `stopReason` is unchanged). Rebuild's `finally` relaunch bypasses the dedup, as
    `force = true` does today (App.kt:1854), but as a **Continue-kind** call, never through `force`'s retry-all
(AG:609), so a failed clear does not clear every remembered failure (D3). So no old job installs rows after the clear, and no stale job can latch
    `complete`. Host and G-f cases:
    - a restore OOM, then Rebuild (after the confirmation), then the pass runs;
    - a restore that fails, then heals on a press, then the `force` Retry resumes with nothing deleted;
    - Rebuild during a gated relaunch's metadata pass.
  - **Pending Retry ids** are a persisted latch, like Continue's. Any pass consumes them at AG:609's position, and
    they are cleared only after `retryFailedTracks` returns, so a cancel during `initialize` never loses them.
    "Retry + Continue" (the "yes" answer) is **one** call. G-f case: Retry, then Continue inside the `initialize`
    window; the ids must still be cleared.
  - **The failure list while gated.** A read-only engine query, under `mutex`, returns the remembered-failure map
    exactly as phase 1 builds it, plus the D2-capped `.retry` entries. AG publishes it into `failures` whenever the
    audio pass is gated or stopped, so the Problems page, `needsRecovery` and "Retry if failures exist" still see
    them. Host case: a cold start while paused with remembered failures; the Problems page lists them and Retry
    works.
  - **Restart triggers:**
    1. ON_START, which is automatic;
    2. **Continue.** It first writes **persisted state**, synchronously, while the intent or tap is handled: it clears
       `userPaused` and sets a persisted pending-continue latch. The event (`continueRequests`) then
       wakes a warm process. It fires from the in-app button and from the notification extra in
       `onCreate`/`onNewIntent`, whatever `userPaused` was. The latch is consumed once a cached key exists, so a cold
       start or an open permission gate never loses a tap.

    Each re-calls `ensureAutomaticIndexing` with the cached key. **Every** call into `ensureAutomaticIndexing`
    honours the persisted `stopReason` rules for its own kind of call (automatic, Continue or Retry; the rules
    follow), not only these two triggers. That includes a `LaunchedEffect` re-fire
    after a MediaStore rescan.
    - **ON_START restarts only:**
      - timeout-6h; show-failed is restarted silently (the in-app state shows "Analysis continues now");
      - exception, with backoff: 1 min, then 10 min, then the next cold start.
    - **Retry restarts:**
      - exception, which also resets its backoff;
      - show-failed and timeout-6h.

      The live table still applies afterwards. Retry leaves
      `userPaused` alone and asks "Continue analysis too?".
    - **Continue restarts every reason**, user included. It clears `userPaused`.
    - A same-key relaunch carries over `metadataReady`, `done`, `running = false`, `phase`, `stopReason` and
      `failures`, and shows the paused state until the gate passes. So steps 1–3 never flash a busy bar, disable
      Retry and Rebuild, or re-arm the stall timer.
      So `metadataVectorsReady` never flips, and the map regions are not rebuilt (App.kt:1298-1310).

    The state table is evaluated
    **before** `show()`, so a stop row never starts the FGS.
  - **The Continue extra** is handled in `onCreate` / `onNewIntent` before the trigger runs. It is consumed once:
    `removeExtra` + `setIntent`, only when `savedInstanceState == null`, and never with
    `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`. So a recreation or a relaunch from recents cannot undo a later Pause. Its
    `PendingIntent` has its own requestCode and
    action, distinct from `appLaunchPendingIntent()`, so `FLAG_UPDATE_CURRENT` never merges extras.
  - **Pause** is a service `PendingIntent` with its own action. It never opens the app. `onStartCommand` tells it
    apart from progress intents, which today it does not (NOT:80-110). Pause is a table event:
    - a **waiting** pass stops and flushes at once;
    - a **running** pass shows "Pausing…", keeps its wake lock (if one is held) until the next chunk boundary
      (≤ 8 tracks), then
      stops and flushes.
    - **Drain bound** (Slice C): at a stop, each in-flight look-ahead gets a segment clock from the moment the drain
      starts, bounded by the 90 s budget. Leftovers go to the 1c watchdog (cancel, 5 s grace, abandon). The drain
      runs outside `mutex`. The wake lock ends when the drain ends (≤ one budget). G-f case: Pause with a hung
      look-ahead.

    A stale Pause that reaches a service with no live pass persists `userPaused = true` only if the last pass did not
    set `complete`.
  - **Flush.** Entering any waiting or stopped row runs `persistPendingAnalysis` in Slice B2 too.
  - **Tests.** Host and G-f cover each stop reason and each trigger. They include:
    - UI Continue;
    - a shade tap while resumed;
    - a cold start with the extra;
    - a config change while paused.
- **Leaving a wait.**
  - **Hysteresis.** Row 3's "for 2 min" is measured with `elapsedRealtime`.
  - **Missing headroom.** In the exit test a NaN headroom counts as satisfied. The test then falls back to status
    alone, or to battery temperature when status is NONE.
  - **Re-checking.** While a wait row holds, the table is re-evaluated on a 60 s `Handler`/`delay` tick once the CPU is
    awake (the tick runs on uptime), at every table event (screen on/off, playback, the receivers), on ON_START, and
    when the cool condition first holds. A budget refill during sleep is therefore seen at the next table event after
    a wake, and no alarm is added. Each dynamic receiver takes a short-timeout wake lock in `onReceive` before it
    signals, so the CPU cannot sleep before row 5 takes its own wake lock. A night-time row-6 wait ends with the screen turning on
    (row 4) or with the refill, and then it resumes.
  - **Host cases:**
    - a status-entered heat wait with NaN headroom;
    - headroom-only heat, cooling with no further events;
    - a budget refill, seen at the next screen event.
- **Battery Saver slow-down** is 2e (Slice D). Slice B2 ships rows 1–6 only.
- **Where stops take effect.** The user's Pause takes effect at the **next chunk boundary** (at most
  8 tracks), never mid-decode. `onTimeout` stays immediate.
  - In Slice B2 nothing is in flight at a boundary, so no workers are abandoned.
  - From Slice C, AnalysisRun at a table stop or wait stops submitting, **drains** its in-flight look-ahead
    (discarding the results), and only then cancels, so a quick Continue never meets the old codecs.
- **`running` and `phase`.**
  - `running` stays **true** in the waiting rows (3 and 6), because the pass is alive, and it is false only in the
    stopped rows, as in 0.9.3.
  - A new `phase` field (running / waiting-hot / waiting-screen / pausing) drives the display.
  - The readers of `running` therefore keep their 0.9.3 meaning:
    - SettingsScreens.kt:1984, 2009-2024, 2145 and 2233-2246 (progress bar, recovery Retry, Rebuild);
    - MapTab.kt:1645;
    - MapRebuild.kt:198.
  - `analysisStall` (AnalysisStall.kt:24-34) returns null while `phase` is a waiting or pausing state, so the #27
    stall text never fires on a wait.
  - `analysisStall`'s `produceState` (AnalysisStall.kt:53-66) also keys on `phase`, through `rememberUpdatedState`.
    `progressAt` is reset whenever the pass goes back to a running row, so the stall text fires neither during a
    wait nor right after a resume.
  - When the pass enters a waiting or pausing phase, `etaMinutes` is set to null, and the wait is left out of the
    ETA pace. MapTab.kt:1645-1650 and SettingsScreens.kt:1984-1995 show the phase text instead of "X of Y · ETA".
  - **Controls per state** (Settings → Intelligence; the Map's "Stopped" help at MapTab.kt:272/1690 follows the same
    rules). The callers of `retryAutomaticIndexing` are App.kt:2731 (Map), 4583 (Settings) and 4594 (Rebuild). The
    buttons that reach them are SettingsScreens.kt:2019, 2145 and 2244 and MapTab.kt:272/1694. The Problems page's
    page shows the phase text above its branches, next to the #27 stall line (SettingsScreens.kt:2232), so it
    shows whether or not failures are listed.

    | state | shown | controls |
    |---|---|---|
    | running | progress, ETA | **Pause** |
    | waiting (hot / battery / screen) or pausing | phase text, no ETA | **Pause**; Retry disabled; Rebuild shows "Pause to rebuild" |
    | stopped: user | "Paused by you: N tracks waiting" | **Continue**; Retry if failures exist (asks "Continue analysis too?"); Rebuild |
    | stopped: timeout-6h | notification only ("Paused at X of Y" with Continue); the app restarts it at ON_START, so in-app the state shows running | — |
    | stopped: show-failed | notification only; in-app "Analysis continues now", restarted at ON_START | — |
    | stopped: exception | "Analysis stopped" | **Retry** (restarts and resets the backoff); Rebuild |
    | engine Failed or uninitialised | as in 0.9.3 | the 0.9.3 `force` recovery Retry |
    | complete, with failures | as in 0.9.3 | Retry (D3, for the listed failures) |

    `needsRecovery` (SettingsScreens.kt:2009-2024) leaves out the table-stopped reasons, so Continue is never shown
    as "Retry". There are host UI tests for each row and for Rebuild in each stopped row.

    **iOS.** The controls live in commonMain, so iOS gets in-app Pause and Continue through the common `userPaused`
    gate. Before Slice D, the stop reasons that exist on iOS are user and exception. The host
    UI tests cover them.
- **Stopped notices** are best-effort. On API 33+ without `POST_NOTIFICATIONS` they are not shown, so the in-app
  state is the source of truth. This is tested with the permission denied.
- **Map readiness: unchanged from 0.9.3.** `libraryWorlds.audioVectorsReady` stays `complete` with a matching
  track list (App.kt:1194-1229), for every reader:
  - AppMapPage.kt:77, 175, 201 and 231;
  - `LibraryRegionsService.refresh` and its `identityOn` (LibraryRegionsService.kt:54);
  - RegionIdentityKeeper.kt:69.

  Rounds 11–14 tried a "sticky" or coverage-based audio tier. Each version leaked into readers that mean "analysis
  finished", or changed region identity, so it is **dropped**. The known limitation, as in 0.9.3: while a pass is
  incomplete (now also while it waits or is paused), the Map and For You regions use metadata only. Changing that is
  a separate owner call (§7).
- **Map rebuild job.** While the audio pass is in **any waiting or stopped state**, `MapRebuildWorker` returns
  `success()` on NOT_READY instead of `retry()` (MapRebuild.android.kt:56-58), and it is re-enqueued at the restart
  trigger. It is also re-enqueued when a pass sets `complete`, through `MapRebuild.resumePending`, at most once per
  run, so a pass that resumes by itself still gets its rebuild without repeated night wakes. `missingFromIndexOf`
  (MapRebuild.kt:220) leaves out D2-capped tracks, as `missingFromIndex` does. This changes scheduling
  only; what the map computes is unchanged.
- **Wake-lock renewal** happens on every per-track `analysing` change (ENG:305-307), which exists today. The lock is
  `setReferenceCounted(false)`, so renewals never stack. Budget time is recorded from acquire and release timestamps.
- **Map rebuild job.** Its time is measured and counts against the same 90 min, because the blog counts job wake
  locks. Its scheduling is as stated under "Map rebuild job" above: today `retry()`; in waiting or stopped states
  `success()` plus a re-enqueue. What the map computes is unchanged.
- **`userPaused`.**
  - It is persisted, and respected at every app open.
  - Only Continue (the notification or the UI), Rebuild, or a pass that finds nothing missing (above) clears it. A library change does not. An explicit Retry clears the chosen
    failures but leaves the pause, and asks "Continue analysis too?".
  - It also shows in the existing SMART partial-analysis hint as "Analysis paused by you: N tracks waiting", where
    its cost is felt.
  - A host test covers it.

**FGS lifecycle.**
- **Starting and stopping:**
  - All stops use `stopSelfResult(startId)`, so a queued `startForegroundService` from a handover is never stopped
    before its own `startForeground`.
  - `finish()`: if `startForeground` has run, stop now. Otherwise set a pending-stop flag; `onStartCommand` honours it
    by calling `startForeground(...)` first and then `stopSelf()`. Stopping before `startForeground` crashes on
    API 26+.
  - `notify`, `stop` and `cancel` are serialised on one thread, so a late `notify` can never re-post an ongoing
    notification after a stop.
  - Progress updates use `NotificationManager.notify` **only**. Today every update after the first calls
    `context.startService` (NOT:51-55). That is legal while the FGS is up, but it throws `IllegalStateException`
    once the FGS has been lost (after `onTimeout` or a stop) while the app is in the background. That call goes, in
    Slice B1.
- **Handover across a library change.** The new run takes a refcount token **before** `existing?.cancelAndJoin()`
  (AG:563), and releases it if `missing == 0`. The old run's `finish()` then only drops its own token.
- **Type.** `ServiceCompat.startForeground` with an explicit `dataSync` type, which covers local file processing.
  `mediaProcessing` is not taken: it has the same 6 h cap. Google's announced deprecation of `dataSync` in a future
  release is noted for later.
- **Stopping for good:**
  - when the pass is done;
  - on `onTimeout` (the 6 h cap);
  - on table row 1 (the user's Pause).

  Swiping the notification away does not stop the service on Android 14+. Task Manager's Stop would end the music
  too, so the Pause action is the user's way to stop analysis. Opening the app resets the 6 h cap.

  Every stopped notice is plain and dismissible. It is posted through `NotificationManager` on its **own
  notification id**, not the FGS's 4201 (NOT:150), with a non-ongoing builder, after
  `stopForeground(STOP_FOREGROUND_REMOVE)`. So it survives the service stop and the "no live pass" path. B2 tests check
  this. Its text is localised and passed in from the run, because the
  Service has no Android string resources (NOT:125-137). On reboot it is the same: there is no
  `RECEIVE_BOOT_COMPLETED`, and adding it is the owner's call.

**Failed `show()` in B1 alone:** the pass ends not complete, as an exception does today, with a log line.
**With B2** (a background start is refused): no wake lock; analysis stops at the next chunk, and the UI shows
"Analysis continues now". ON_START restarts it silently, as the controls table says. Today a run starts only from composition (AG:565-580, NOT:46-58). So the real
triggers are a library change while the app is in the background, and the refcount handover. The tests cover
exactly those.

**Visible states** in the analysis UI: one per row of the controls-per-state table below. Slowed (Battery Saver) comes
with 2e. The notification is optional, because Android 14+ lets users swipe it away.

**Definitions.**
- *Screen off*: `PowerManager.isInteractive == false`. Always-on display counts as off.
- *Playing*: `player.isPlaying`.

**Invariant.** The app sets no `setWakeMode` and declines offload (`EqualizerAudioProcessor.kt:354`,
`DEFAULT_UNSUPPORTED`). So during playback the system audio wake lock keeps the CPU awake. If offload is ever
enabled, this plan's wake-lock rule must be revisited.

The wake lock is released the moment the FGS is lost.

**Boot.** The boot permission question is moot for this FGS on API 35+, where `BOOT_COMPLETED` may not start a
`dataSync` FGS. It only matters together with the parked WorkManager option.

**Play declaration** (owner call, **outside this plan's state machine**). Play's `dataSync` declaration ("Local
processing: Other") asks for user-initiated work that the review video shows starting from a user action (answer
13392821), while analysis today starts by itself. This plan keeps 0.9.3's automatic start. A first-run consent
would also need:
- a place in the UI (the first-run flow after `AudioPermissionGate`, the Map empty state, Settings, the `NotIndexed`
  hint);
- an iOS scope;
- a migration that survives restore failures.

So it is its own small design with its own review round, built only if the owner wants it for Play.

### 1e-ios. Keep running, iOS (Slice D, own review round)

- **Background assertion:** `beginBackgroundTask` again on every move to the background (lands in Slice B1).
- **Backgrounded with audio:** one serial lane, with a duty cycle set from the iPhone measurement. The App Store
  2.5.4 risk is noted.
- **iOS 26+, `BGContinuedProcessingTask`:**
  - **Swift side:** implemented in Swift behind `@available(iOS 26)`, because the K/N platform libraries may lack the
    iOS 26 SDK. The handler is registered dynamically, then submitted from a "Keep analysing in the background" tap
    while in the foreground.
  - **Identifier:** a wildcard identifier `<bundle>.analysis.*` in `BGTaskSchedulerPermittedIdentifiers`.
  - **Behaviour:** the task has a title and subtitle, reports steady `progress`, and honours the user's Cancel by
    persisting and stopping.
- **`BGProcessingTask`:** `requiresExternalPower = true`, registered before launch finishes, as
  `registerMapRebuildTask` is. It shares one identifier and one request with the map rebuild: analysis runs first,
  then the map. `BGContinuedProcessingTask` needs the Xcode 26 SDK in the Swift target. It rebuilds the work list from the library scan and
  the index. That cold-launch path gets its own test with the device locked (class C before first unlock). Neither task runs after a force-quit, and the UI says nothing that suggests otherwise.
- **Storage move to Application Support:**
  - **Before the move:** if this session restored cleanly, write a fresh full base. Never write it while
    `IndexRestoreFailure` is set or the file is unreadable; then the plain copy alone is used. Then copy each store
    file (and the `.retry` sidecar) to a `.tmp` in
    Application Support, fsync it, rename it, and fsync the directory. Which copy wins is decided **per store**, after
    its rename. VIS's versioned file and its legacy file count as one unit, versioned first, so a mixed
    Caches/Application Support pair is never read. A torn copy can never win. The fault suite kills the app mid-copy.
  - **Attributes:** `isExcludedFromBackup` on a dedicated subdirectory, not on the Application Support root; explicit `CompleteUntilFirstUserAuthentication`, which is
    already the default class C; `Complete` is forbidden.
  - **Read rule:** a protected file counts as not readable now.
  - **Copy and keep:** the index is copied into Application Support and the Caches copy stays until 0.9.5.
    Application Support is authoritative whenever it exists.
  - **Downgrade:** a downgrade to 0.9.3 (AltStore allows it) uses the old Caches copy. Progress made under 0.9.3
    after that is lost on re-upgrade. This is stated.
- **QoS:** analysis on dedicated threads with an explicit QoS. Kotlin/Native `Dispatchers.IO` cannot be set.

### 1f, 1g and the Android slices (Slice E)

**1f, a black box:** a `media_commands.log`-style ring file of 256 KB, written per chunk. It holds:
- a salted HMAC of each id;
- stage times;
- outcome and error classes.

No titles or paths. The user exports it. Every catch in R10 records its class there.

**1g, memory:**
- Look-ahead depth = min(workers, the budget from heap and `ActivityManager.MemoryInfo`).
- Pooled buffers are zero-filled.
- Sessions are released after 60 s idle, on `UI_HIDDEN`/`BACKGROUND`, and on iOS memory warnings.

**Android WorkManager slices** are optional and need the owner's yes. They need:
- a non-Compose entry point: a library scan with a permission check, `librarySnapshotAuthoritative` semantics and
  plain Android string resources;
- mutual exclusion with a cold-started UI run;
- slice wake-lock time counted in the 1e budget.

They are no use after a reboot. The "While charging" speed mode depends on them; without slices it is dropped.

## 5. Speed items

**2-0. Thread priority (P3), by its rule.** Analysis runs at `DEFAULT + LESS_FAVORABLE`:
- while the app is visible;
- under the FGS with the screen off, unless playback is active on a weak device (≤ 4 cores or < 4 GB RAM), in which
  case it runs at `BACKGROUND`.

Seed embeds always run at the higher priority. The priority is applied per worker through its captured tid.

**2a. Android decoder (P1).**
1. Cache the `MediaCodecList(REGULAR_CODECS)` instance, and call `findDecoderForFormat(inputFormat)` per window as
   now.
2. One fd and one extractor per track, with `seekTo` between the 20/50/80 % windows. The 0 ms window keeps a fresh
   extractor. Allow-listed per triple (§2.2).
3. One codec per track with `flush()` between windows, allow-listed per triple.

**2b. iOS pipeline (P6).** One `AVAudioFile` per track (G-a). The 1b-0 handles let decoding of track n+1 overlap
inference on track n. The overlap is capped in the background.

**2c. Parallel inference (P7), by its rule.**
- k sessions, each with intra = 1, its own lock and a dedicated thread.
- Results are installed in chunk order.
- k = 2 only when charging, or screen-off and cool:
  - Android API 29+ thermal status; API 30+ headroom at most once per chunk, NaN → k = 1; below 29, battery
    temperature;
  - iOS `thermalState` ≤ fair and not Low Power Mode.
- Gate: G-b on every kernel path.

**2d. Text (P8).**
- A per-pass LRU of 64 entries: string → the raw `encode()` output. Null or failed results are never cached.
- The cache hands out copies only, and `adopt` never receives a cached array.
- The to-encode list is built inside the `mutex` hold. Installs happen with the chunk's audio rows in one hold (1b),
  so text and audio order stay serial.
- Upserting a cached output normalises exactly as today, so the stored row is the same as a fresh encode's.
- Chunk encodes move out of `mutex` and hold only the text lock. Every other encoder use (`planQueue`,
  `semanticSearch`, `release` → `close`) takes the text lock after `mutex`. Nothing holds the text lock while
  acquiring `mutex`, so `close` can never race an encode.
- Gate: G-f.

**2e. Pacing (Slice D).**
- **Soft first.** Battery Saver or Low Power Mode **slows** the pass (1 worker, BACKGROUND priority, k = 1). It never
  pauses it.
- **Hard pause:** this is 1e's table, rows 2 and 3, already in Slice B2, with the thresholds from §2.4. iOS uses
  `.serious`.
- **Implementation:** events (`addThermalStatusListener` on API 29+, `ACTION_BATTERY_CHANGED` and power receivers),
  plus a headroom read per chunk. Never a `delay`-based poll while the CPU may be suspended.


**Not in this track:**
- A0 re-export or the four-step FFT;
- execution providers;
- another resampler;
- window batching;
- a new model.

## 6. Done

1. The plan is READY by review and approved by the owner. The owner's calls are listed in §7, each with a default.
   Slice D starts only after the 1e-ios review round is READY. Items 3–4 need the physical phone for their † parts;
   until it exists, those parts are reported as emulator-relative and explicitly waived.
2. Logical commits on `claude/analysis-094-939dfe`, each with tests.
3. Step 0 numbers before and after, on the same devices.
4. G-a … G-f pass on Android and iOS. Rows differ only on the reported D1–D5 set.
5. The fault suite passes. It covers:
   - a kill at random points, including mid-snapshot and mid-clear (and the 1a-tail cases if it is built);
   - a hung decoder (test hook);
   - hostile files and fuzz seeds;
   - a full disk during a tail write;
   - a purged or protected iOS directory;
   - a missing model;
   - cancellation storms;
   - a downgrade to 0.9.3 and back;
   - a test build installed over a release build.

   Passing means:
   - no committed track is lost;

   - no failure is wrongly remembered;
   - no unbounded thread or codec leak;
   - press waits are within the reported bounds.
6. A short report in Russian.

Nothing is pushed, tagged or released by this session.

## 7. Owner calls (each with a default and the cost of saying no)

| call | default | if no |
|---|---|---|
| D1–D5 (table in §0) | yes | 0.9.3 failure rules stay. Hung files remain failed for good, transient ones are retried forever, and partial rows stay |
| A test phone, or a test build on the S24 reading only the corpus folder | S24, corpus folder only | 2-0 stays undecided, and emulator numbers count as relative only |
| Wake-lock budget: charging counts | yes, on every channel | If charging is free: first analysis finishes in one night on charge, but this relies on the one Google page that excludes charging, and it risks the store listing if that page is wrong |
| Play declaration (affects Play tracks only; F-Droid, GitHub and AltStore are unaffected) | **default: keep 0.9.3's automatic start.** Risk: Play may reject the `dataSync` declaration or an update, because "Local processing: Other" requires user-initiated work (answer 13392821). 0.9.4's wake lock adds time under the same declaration but does not change the question. The owner says whether 0.9.3's declaration was accepted for the closed test | The alternative is a separate, reviewed first-run consent design |
| Per-row Retry button in the UI | yes, plus "retry these" | Only "retry these" |
| iOS "Keep analysing in the background" action (iOS 26+) | yes | iOS analysis continues only in the foreground and in short background windows |
| Snapshot reuse during analysis: SMART would answer from a snapshot up to one chunk old instead of rebuilding it on every press | no | (default) Presses during analysis stay slower |
| Fused (audio) Map / For You regions before a pass completes | no: 0.9.3 behaviour, metadata-only until complete | A separate design would be needed (rounds 11–14 showed the risks to region identity) |
| WorkManager slices and the speed mode | parked | — |

## Implementation log

The owner said go on 2026-11-01 ("Делай всё что считаешь нужным"), with the default for every §7 call, D1–D5 included.

### Slice A (Step 0, emulator part)

- Corpus: 459 files built from commercial-CC FMA clips (`~/Documents/LJ/analysis-094/make_corpus.py`, outside the repo,
  licences in its `LICENSES.tsv`): 200 MP3 originals, 12 sources × 20 transcodes (AAC in M4A/ADTS/MKV, FLAC native,
  24-bit, in Ogg and MP4, Vorbis, Opus in Ogg/WebM, WAV s16/s24/f32, mono, 5.1, 22.05/48/96 kHz, VBR MP3 without a
  Xing header), short (6 s) and long (21 min) files, 4 exact duplicates, and the `tools/fuzz/corpus` audio seeds. The
  FMA sources are 30 s clips, so the window positions are closer together than on full tracks.
- Tool: `AnalysisGoldenDeviceTest` (52c43398) reads the corpus from the test package's files, never MediaStore, and
  writes the CRC of every decoded window and of every track vector the chunked pass computes.
- **G-a / G-c baseline on Pixel_7_Pro (API 36, 4 cores, emulator):** two runs of the 0.9.3 code are byte-identical:
  1,303 windows, 437 vectors, 22 failures (the fuzz seeds and 3 FMA files the platform rejects). Noise band: none.
  Timing (relative only): decode 0.96–0.99 s/track sequential, chunked pass 0.73–0.80 s/track.
- Not done yet: the physical-phone minimum set (†), G-b, G-d, G-e, G-f, the platform matrix.

### Slice B1 (9bfd518b, f6c2263f, 981af414)

- FGS: one gate (`AnalysisForegroundGate`) decides starts, updates and stops under **one lock** (the plan said "one
  thread"; a lock gives the same ordering and lets a refused start reach the caller synchronously). Progress posts via
  `NotificationManager.notify`; a stop before `startForeground` is deferred; runs hold sessions, opened before the old
  run is cancelled; explicit `dataSync` type. A refused start ends the pass as an exception did.
- iOS: `beginBackgroundTask` again after every expiry, on the next background or active transition.
- 2a.1: the codec list is built once per process. Review round 1 pointed out that the framework already shares a
  static list, so this saves only the per-window copy; the gain is expected to be small, and the comment says so.
- 2-0 (thread priority) is **not** built: it needs the physical phone and its predeclared rule.
- Review round B1-1: engineering READY, platform READY (no blocking, no major). The 9 minor findings were fixed in
  981af414 (timeout then destroy, refused start on the way to a stop, stale content, blank channel name, double
  relaunch before dispatch) or reworded (codec comment).

### Slice B2 (52afc8d2, 3bbda9b7, 8c4a3302)

- Rows 1–6 as in §4, thresholds T = MODERATE, H = 0.85 (defaults; Step 0 on a phone may move them), budget 90 min per
  24 h counting charging, wake lock 5 min renewed per track, receivers take a 3 s lock on screen and power events only
  (not on every battery broadcast).
- **Envelope refusal** uses the engine's existing refusal latch through a lock-free query. The size estimate before the
  first refused save (`IndexStore.encodedSizeOf`) is left for Slice C: until then a cold process over the envelope may
  hold the wake lock for at most one checkpoint window (32 tracks) before the first refusal.
- Stop reasons USER, TIMEOUT, SHOW_FAILED, EXCEPTION are persisted; restart rules per call kind as in §4. The pending
  Retry-ids latch belongs to D3 and comes with Slice C.
- Strings in `values*/analysis_control.xml`, all 22 locale folders.
- Review round B2-1: engineering NOT READY (M1 Continue during steps 1–3 ran the pass unseen; M2 no tests above the
  gate; M3 failures not listed while paused), platform NOT READY (same M1, M2). Fixed in b52c4cf5: the audio pass moved
  into `GatedAudioPass` with host tests, failures listed via a read-only engine query, and the minors.
- Emulator check (Pixel_7_Pro, API 36, 1,299 tracks incl. the corpus): FGS `dataSync` with a Pause action; screen off →
  `LatentJam:analysis` wake lock held and progress went on (923 → 1,046 in a minute); notification Pause → service
  stopped, "Paused by you" notice, `userPaused`/`stopReason=user` persisted, no wake lock; notice Continue → app opened,
  pass resumed, notice gone; pass completed with clean preferences. It also found a real defect: the fixed headroom 0.85
  held the emulator in a heat wait at 30 °C (its MODERATE threshold is 0.967), fixed in 7f37dada by using the device's
  own threshold on API 35+.
- Review round B2-2: NOT READY (M-A: the "pass is finishing" Continue branch fired during steps 1–3 and could undo a
  later Pause). Fixed in a5f9ca7e with a flag set by the paused stop, plus the round-2 minors.

### Slice C (started)

- 1a (49f74872): directory fsync on Android; iOS streaming writer (POSIX, `F_FULLFSYNC`, directory fsync) and a
  one-copy reader. Byte equality of the streamed path is tested in `IosFileIndexStoreTest`, but **the iOS tests could
  not run** in this session (`simctl` hung); iOS compiles. The Application Support move is not done.
- P4 (eeebfdd9) instead of the full `AnalysisRun`: the engine passes the next chunk as a hint, and the Android
  pipeline starts its decodes once the current chunk's are all started; the next call reuses them. The press path,
  the locks and the install are 0.9.3's. This is a smaller design than §4 1b, chosen because it keeps every install and
  lock as they were; per-window `inference` (press latency) is not done.
- Review round C-1: stores READY (minors: directory `F_FULLFSYNC`, fixed); carry NOT READY (major: carried work
  orphaned when a press replaced the pool between chunks). Fixed in c5a911e0 with a pool generation; round C-2 then found
  two majors in D1/D2 (below), fixed in 1ef4991f; round C-3 READY; its minors fixed in 87e764b8.
- G-a/G-c with carry on and off (Pixel_7_Pro emulator, two pairs): **byte-identical to the 0.9.3 golden** in every
  run, but carry was slower: 527 and 487 ms/track against 470 and 461 without. Rule frozen after the fact and stated
  plainly: carry stays **off** (`ANALYSIS_CARRY_DECODING = false`) until the physical phone with full-length tracks
  shows it faster; the 30 s clips and 4 emulator cores make the gap a small share and the extra concurrent decodes cost.

### D1/D2 (c5a911e0, 1ef4991f, 87e764b8)

- D1: a timeout is `AudioUnavailable` (transient). D2 is app-side (`AnalysisRetryLedger`, persisted in the analysis
  preferences rather than a `.retry` sidecar): retried at the next pass, then no sooner than 1 h after the second, and
  the third is remembered by the engine (`rememberAudioFailures`) and listed with Retry. Only timeouts count; a passing
  failure between timeouts keeps the count. A press does not wait again on a seed that timed out in this process.
- Narrower than §4 1d: SOURCE_OPEN `Unavailable` is still retried at every pass as in 0.9.3 (needs the 1b-0 stage
  field); "stuck → Broken" (1c) is not built; nothing schedules the 1 h retry (it waits for the next pass).
- D3 (per-track Retry), D4 and D5 are not built.

## Review log

### Round 1 (2026-11-01): two fresh reviewers on v1 → NOT READY

**Correctness (39 findings).**
- **Blocking:**
  - a stale journal replays over a base that an older build rewrote;
  - VIS `retireLegacy` combined with a journal can leave no base.
- **Major:**
  - text O(N²);
  - the per-chunk drain of the look-ahead;
  - the iOS non-streaming reader;
  - other long `mutex` holders;
  - cancellation abandons workers and so produces partial rows;
  - row order is SMART's tie order;
  - ljq4 kernel paths and vendor codecs are not covered by the gates;
  - the head window must keep a fresh extractor;
  - pooled buffers must be zero-filled;
  - the iOS bridge copy cost;
  - a cross-track mix-up would go unseen with a copies-only corpus;
  - replay merge semantics;
  - not every mutation path was journalled;
  - concurrent compaction is unsafe;
  - transient failures must not go into the base;
  - lock order;
  - a session closed between windows;
  - the budget counting lock waits;
  - the API split must come before the locks.
- **Minor:** line cites, R12 overstated, `ahead + 2`, no host build of ljq4, the decoder-name cache key, the text
  cache, fsync semantics, the compaction floor, the directory backup flag, the WorkManager mutual exclusion, the
  interim cadence, HMAC.

**Product / prior art (15 findings).**
- **Blocking:** no wake lock, so analysis stops with the screen off.
- **Major:**
  - `THREAD_PRIORITY_BACKGROUND` (little cores) is probably the largest speed lever;
  - iOS background reality (30 s assertion, CPU kill while playing, `BGContinuedProcessingTask`);
  - FGS type and background-start failure, and 6 h as the normal case;
  - playback interference is not measured;
  - Step 0 needs a physical device and predeclared rules;
  - the journal needs a base generation.
- **Minor:**
  - a decision record (journal, SQLite or segments);
  - iOS file protection and the map stores;
  - deprecated `onTrimMemory` levels and `isLowRamDevice`;
  - thermal API levels;
  - the "stop for the process life" rule;
  - the ljq4 scratch per thread;
  - black-box ids;
  - a user speed mode.

Spot-checked by the author against the code: no wake lock anywhere, priorities at ABK:85 and SmartDispatcher:24,
manifest:119, VIS:36-64, the tie ordinal (InMemoryVectorIndex.kt:154-166), the press persists at ENG:936-945.

**v2 accepts all findings except one, with these changes:**
- the journal is replaced by fingerprinted segments, merged last-wins and loaded in order;
- a dirty-id set covers every mutation path;
- the 1b-0 API split comes first, with lock re-validation, a budget that ignores lock waits, and one pipeline across
  chunks;
- new items: 2-0 priority, wake lock and FGS rules, the iOS background plan, codec caps and playback measurement;
- gates G-a to G-d, with order, kernel paths, vendor allow-lists and duplicates;
- Step 0 has a physical-device precondition and predeclared decision rules;
- the interim cadence 1a-0.

**Not taken:** the `.bak` file. It is dropped rather than fixed.

### Round 2 (2026-11-01): two fresh reviewers on v2 → NOT READY

**Correctness: 18 findings.**
- **Blocking:**
  - The v2 segment merge could not reproduce 0.9.3 order. A removed and then re-added id is appended in 0.9.3
    (OrderedIdMap.kt:8-12; `synchronizeLibrary` removes changed ids at ENG:465-468), while v2 would have put it back
    at its base position. The restore retry with a live seed has the same problem.
- **Major:**
  - No differential test for the store.
  - No base at all on a fresh install, or after the legacy file was adopted.
  - The merge map's memory, and envelope overflow.
  - Segment gaps after power loss.
  - The failure deviations go beyond the one declared (timeouts, the 3-attempt cap, partial-row order, Retry scope).
  - "No abandon on cancellation" is unsafe while open, seek and codec start are unguarded.
  - The cross-chunk pipeline does not fit the per-chunk engine API.
  - Copying stored text rows re-normalises them, which breaks bit parity.
- **Minor:**
  - The per-window lock vs the per-track API.
  - A data race on `generation`.
  - The fingerprint after the half migration, and an identical-bytes rewrite.
  - The dirty-id list (no seed install in `nextTrack`).
  - `clearAnalysis` delete order.
  - The iOS late run sharing a session.
  - Wake-lock renewal.
  - The 1a-0 order claim.
  - Single writer for `:analysis`.
  - Line cites.

**Product / platform: 8 major, 9 minor, no blocking.**
- 1a-0 at max(32, 60 s) is still O(N²) (a geometric term fixes it).
- The order of work does not put the cheap, user-felt levers first.
- The Play "excessive partial wake locks" vital.
- Background FGS starts are unresolved for pause, charging mode, `onTimeout` and library change.
- Reboot: `RECEIVE_BOOT_COMPLETED` is removed on purpose, so no resume after a reboot.
- The 2-0 rule ignores jank, energy and thermal.
- The S24 option breaks the no-owner-library rule while benchmarks read MediaStore.
- `BGContinuedProcessingTask` needs a user action.
- Minor:
  - matrix columns and OEMs;
  - `mediaProcessing` buys nothing;
  - the 6 h cap resets in the foreground;
  - thermal thresholds too late;
  - iOS QoS needs threads of its own;
  - the black box is over-engineered;
  - Step 0 too large;
  - pause states invisible;
  - the lock file is not needed.

Spot-checked by the author against the code: OrderedIdMap order semantics, ENG:465-468, manifest:40-45.

**v3 takes all of them:**
- Tail segments are appends only. Any other change writes a full base, so no merge is needed and the order is
  exactly 0.9.3's.
- Fingerprints include the inode and mtime. Gaps count as damage, and the directory is fsynced.
- The first base is written before `retireLegacy`.
- Transient attempt state moves to a `.retry` sidecar.
- New gate G-e: a property test with the 0.9.3 store as oracle.
- Deviations D1–D4 are declared, and D4 keeps chunk order and then upserts in place.
- 5 s cancel grace, with the budget covering worker waits.
- The `AnalysisRun` API.
- The text cache holds raw encoder output.
- A per-window API; `generation` is volatile.
- Geometric 1a-0; slices reordered so the quick wins come first; 1a is gated by a rule.
- A wake-lock budget for the Play vital; `dataSync` kept with an explicit type; a paused state.
- Honest charging-mode and reboot statements.
- A tighter 2-0 rule, and a corpus directory instead of MediaStore.
- A user action for `BGContinuedProcessingTask`.
- Thermal at MODERATE, visible pause states, a slimmer black box, a Step 0 minimum set.

### Round 3 (2026-11-01): two fresh reviewers on v3 → NOT READY (no blocking findings)

**Correctness: 10 major, 14 minor.**
- **Major:**
  - A damaged tail could discard the whole base via `discardAdopted` (ENG:1354-1390).
  - Tails could be written over a base this process did not load cleanly, for three reasons: base damage, a text I/O
    error, or rows skipped on load.
  - The order check cannot see in-place upserts or identity re-keys (OrderedIdMap.kt:34-41). Failure changes bypass
    `forgetAudioFailure` (ENG:1446).
  - Partitioning `AnalysisRun` up front is an undeclared deviation.
  - The iOS timeout change was undeclared, and the start of the budget clock was unspecified.
  - D2 counted engine-wide failures as if they were per-track.
  - A cross-thread `codec.release()` is unsafe, and the codec cap could starve.
  - The geometric cadence makes kill loss worse than 0.9.3.
  - The press-latency criterion was unreachable while `mutex` is held for full writes.
  - The 2a rules checked determinism, not equality with the golden; the allow-list key lacked the container. G-f
    (differential engine test) was missing.
- **Minor:** the inode/mtime rationale, the half migration, the iOS move, permanent failures forcing bases, `.retry`
  identity and pruning, D1 for old timeouts, stuck → Broken, text-lock order, signal-not-join, a failed tail write,
  duplicate scope, envelope downgrade wording and per-store opt-in, energy rows in the gates, a single tail file as the
  cheaper design.

**Product / platform: 7 major, 12 minor.**
- **Major:**
  - The wake-lock budget must count all screen-off time, charging included (Play vital ≥ 2 h per 24 h).
  - The paused state cannot work on timers while the CPU is suspended; nothing resumes after the FGS stops in the
    background.
  - Android WorkManager slices have no non-Compose entry and little value.
  - Slice B held the heaviest item.
  - A 10k corpus of copies is 50–100 GB.
  - The 2-0 rule cannot be decided on one phone, and its thermal clause fights 2e.
  - The FGS start/stop race ("did not then call startForeground").
- **Minor:**
  - `BGContinuedProcessingTask` details.
  - Pause hysteresis.
  - A failed `show()` under the freezer.
  - Paused minutes count toward the 6 h cap.
  - The FGS notification can be swiped away.
  - SMART during partial analysis.
  - Gate placement.
  - D2 contention.
  - 2-0 coverage.
  - iOS downgrade.
  - The unverified iOS CPU-kill claim.
  - File protection is already class C.

**v4 takes all of them:**
- **Store:** a single tail file with per-record CRC and two-pass validation; a tail problem never drops the base; the
  base state is clean / none / unknown; structural mutation counters; failure additions are allowed in the tail;
  opt-in per store; stated envelope downgrade; the geometric cadence is dropped, and 1a is unconditional in Slice C.
- **Engine:** `AnalysisRun` re-checks at submit and at install; presses never persist during a run; press latency is
  split into two measured bounds; signal-not-join; the text-lock order is defined.
- **Failure handling:** D1 covers both platforms, with the clock start stated and stuck → Broken; D2 counts
  track-local failures only; no cross-thread release, and stuck codecs leave the cap; G-f added and the gates are
  placed per item; the 2a rule is "equals the golden", keyed by container.
- **Android run:** a wake-lock budget over all screen-off time; event-driven pause with `elapsedRealtime`; honest
  "Paused at X of Y"; the FGS race fix with a refcount handover; 1e-min split out for Slice B, with D1 in B;
  slices and the speed mode moved to E as owner options.
- **Measurement:** a hard-linked 10k corpus; a 2-0 rule with ≥ 3 runs, batterystats and a "tracks in 30 min under
  pacing" metric.
- **iOS:** `BGContinuedProcessingTask` specifics; resume hysteresis; the iOS Caches copy kept until 0.9.5; the iOS
  CPU kill marked unverified.

### Round 4 (2026-11-01): two fresh reviewers on v4 → NOT READY

**Correctness: 1 blocking, 6 major, 8 minor.**
- **Blocking:**
  - A stale tail replays over a byte-identical full base. The case: a kill between the base rename and the tail
    truncate after a Retry. Truncating in place was also unsafe.
- **Major:**
  - The tail must carry the snapshot view, keeping rows and failures disjoint (ENG:1661-1667). A durable row's added
    identity also needs a full base.
  - `adopt` renormalisation against a base that is never rewritten: text float32 rows are not a fixed point.
  - 0.9.3 phase 3 upserts over a press's seed; it does not skip it (ENG:330-345).
  - "Presses never persist during a run" can lose a seed or a Retry.
  - The codec cap and back-off change windows that D1–D4 do not cover.
  - The budget clock was contradictory, and queue-mates of a hanging file were charged attempts.
- **Minor:**
  - An orphan tail after `clearAnalysis`.
  - A load into a non-empty index.
  - The envelope counts base + tail.
  - Text-cache aliasing, lock waits and the install re-check.
  - A D1 test in Slice B.
  - Batching D4.
  - The seed lane counted in the codec cap.
  - Installing stuck → Broken.

**Product / platform: 4 major, 6 minor.**
- **Major:**
  - Stopping the FGS at the wake-lock budget is worse than 0.9.3. Keep the FGS and drop only the wake lock.
  - A hard pause on Battery Saver starves users who leave it on (soft over hard).
  - The wake lock was held during screen-off playback, which already has the system audio wake lock.
  - The 10k estimate under the budget is missing, and the budget per channel is not listed as an owner call.
- **Minor:**
  - Pending-stop wording, notify serialisation and a localised paused text.
  - Handover order before `cancelAndJoin`.
  - 2-0 measured without 2e, with charge-counter energy and controlled starts.
  - The Play smell of an idle FGS.
  - iOS SDK and shared-identifier details, plus copy-and-keep semantics.
  - D2 showing the reason.

**Decision in v5.** Every incremental store design has drawn a blocking finding in three rounds running. 0.9.4
therefore **keeps 0.9.3's full snapshots and cadence**, which match by construction. 1a shrinks to:
- directory fsync;
- the streaming iOS store;
- the higher envelope;
- the `.retry` sidecar.

1a-tail becomes conditional on a measured cost: more than 3 % of wall time, or a press `mutex` p95 above 1 s. If
built, it must meet requirements 1–8, which carry every store finding from rounds 2–4, including the
`supersededBy` marker, fresh tail files written via rename, the snapshot-view delta, and the adopt byte check.

**Other v5 changes.**
- `AnalysisRun` emulates phase 1 at chunk boundaries and phase 3's in-place upsert.
- Pauses and cancels flush under `NonCancellable`.
- D5 added.
- The budget clock is exactly DL:136, waits are excluded, and only tracks that held a worker and a codec count as
  attempts.
- The wake lock is taken only when the screen is off and nothing is playing; at the budget only the wake lock goes.
- Battery Saver slows the pass and never pauses it.
- The 10k estimate and the per-channel owner call are in §4.
- All the minor findings are taken.

### Round 5 (2026-11-01): two fresh reviewers on v5 → NOT READY

**Correctness: 1 blocking, 4 major, 10 minor.**
- **Blocking:**
  - v5 excluded worker waits from the 90 s budget. A hung look-ahead could then stall the pass forever, because the
    head's clock paused and the hung track never became head (DL:136-154, ABK:149, 226-229).
- **Major:**
  - Split seed and text installs give row orders no serial 0.9.3 run can produce (ENG:323-372, 938-941).
  - D1 in Slice B without 1c/1d makes a hung file cost 90 s and a leaked thread at every launch.
  - The 512 MiB envelope: a launch-time OOM, the iOS default `loadRows` and the in-memory migration, and persisting
    beyond 0.9.3 is an undeclared deviation.
  - Phase-1 emulation was underspecified: resubmit at the boundary, `rememberTracks`, the restore retry, the lazy
    model, text when not current.
- **Minor:**
  - D4 rebuild mechanics, persisting pending ids, and running before energies.
  - `.retry` pruning, Retry, `missingFromIndex`, iOS forgiveness.
  - The energy identity re-check.
  - Building the 2d list inside the hold.
  - The duplicate slice entry.
  - Frozen oracles.
  - A reserved seed codec slot and back-off accounting.
  - iOS stuck detection.
  - The 1a-tail trigger pass.
  - G-c flakiness on devices.

**Product / platform: 0 blocking, 4 major, 7 minor.**
- **Major:**
  - Play's `dataSync` declaration wants user-initiated work, but analysis starts by itself.
  - The waiting states were inconsistent (a frozen progress bar at the budget, an FGS stop at a hard pause).
  - The charging and job premises of the wake-lock budget.
  - Slice B waited on the phone, and iOS got nothing early (the `expired` latch at NOTI:39).
- **Minor:**
  - The pending stop must call `startForeground` and then `stopSelf`.
  - Definitions of screen-off and playing, and the offload invariant.
  - The boot call is moot on API 35+.
  - No user Pause.
  - D1–D5 are written in engine terms, and the owner calls have no defaults.
  - iOS identifier, backup subdirectory and locked cold launch.
  - Citations.
- **Suggested cuts:**
  - the `:analysis` process, 1f, 2c, 1a-tail, P10;
  - WorkManager slices and the speed mode;
  - the iOS duty cycle.

**Author check of the M3 premises against the sources** (fetched 2026-11-01):
- **Play Console help (9844486):** "not charging", > 3 h per battery session, beta.
- **Vitals page (updated 2026-09-21):** ≥ 2 h per 24 h, > 5 % of sessions; exempt are only audio, location and
  JobScheduler *user-initiated* APIs.
- **Blog of 2026-03-04:** counts WorkManager and JobScheduler wake locks.
- **Conclusion:** the "jobs are exempt" premise is wrong. Charging is contradictory between sources, so it is an owner
  call with a conservative default.

**v6 takes everything else:**
- the 0.9.3 budget clock, with the exclusions only in D2 accounting;
- single-hold installs for chunks (audio + text) and for presses (text + seed + plan), plus a serialisability
  property in G-f;
- D1 moved to Slice C beside 1c and 1d;
- D6 for the envelope, with OOM behaviour, a full iOS streaming scope and 40k/100k gates at 48 and 96 MB;
- full phase-1 replay at boundaries;
- all the minor fixes;
- one waiting state with a "Waiting: continues when…" notification and no ETA;
- a Pause action;
- the Play declaration as an owner call;
- Slice B gated on host and emulator except 2-0, with the iOS latch fix and the Application Support copy moved in;
- the parked list;
- §7, the owner calls with defaults;
- a user-facing D table.

### Round 6 (2026-11-01): two fresh reviewers on v6 → NOT READY (no blocking findings)

**Correctness: 5 major, 5 minor.**
- **Major:**
  - A `withTimeoutOrNull` deadline cannot be paused, and a hand-made one loses `TimeoutCancellationException`
    semantics (DL:96). A head would also count boundary `mutex` waits that 0.9.3 never counted.
  - The shared decode pool: one abandon strands the other path's queued work (ExecutorDecodeWorkers.kt:16-19).
  - The press's decisions before the decode were unspecified, so two holds were needed, plus re-checks and the text
    on every exit path (ENG:933-978).
  - D6 with the 0.9.3 cadence writes quadratically beyond 30k (about 80 GB at 50k).
  - The reserved seed slot left zero analysis codecs on 1-worker devices.
- **Minor:**
  - `nextTrack` and model loads hold `inference` for longer than a unit.
  - Contradictory text-lock rules (a use-after-close risk).
  - `snapshotCache` while energies are released.
  - A stale energy after D4 and a kill.
  - The not-Ready branch missing from the phase-1 replay.

**Product / platform: 3 major, 4 minor.**
- **Major:**
  - Waiting under the FGS burns the 6 h dataSync cap, so the overnight promise is false on API 35+. On API 26–34 a
    non-dismissible notice could sit for days.
  - Slice B shipped an uncapped wake lock off Play before the battery and thermal guards existed.
  - User Pause did not persist, so the next app open undid it.
- **Minor:**
  - The budget window does not survive a reboot.
  - The Play row had no default.
  - The wake-lock row's "if no" was lopsided.
  - The failed-`show()` triggers were unnamed.

**v7 response:**
- **Narrower scope:** the press path stays exactly 0.9.3's single hold. Only the seed's decode lane and the
  per-window `inference` change. That removes the press/chunk interleaving class entirely; R1's remaining gain is
  the end of waiting behind a whole chunk.
- **Budget:** a segmented deadline, with `withTimeoutOrNull(remaining)` per segment and lock acquisition between
  segments. The clock starts after the boundary hold.
- **Decode pools:** separate pools for the seed lane, and every abandon re-queues all pending work.
- **D6 cadence:** size-scaled above 64 MiB only, with the 1a-tail rule also checked at 30k, 50k and 100k.
- **Codec cap:** workers plus a seed slot.
- **All the correctness minor findings are taken.**
- **Waiting:** short waits only, ≤ 30 min, thermal or screen-off. Long waits stop the FGS with "Paused at X of Y",
  and the estimate now says "with app opens".
- **Slice B:** the wake-lock cap applies on every channel until Slice D, and the minimal battery and thermal stops
  move into Slice B.
- **Pause:** a persisted `userPaused` flag, cleared by Continue or Retry.
- **Product minor findings:** all taken. The Play row now has a default: keep `dataSync` and add a first-run
  consent.

### Round 7 (2026-11-01): two fresh reviewers on v7

**Correctness → READY. No blocking or major findings, 5 minor.**
- Checked as sound:
  - no deadlock or starvation between presses holding `mutex` and the run (fair `Mutex`, one lock order);
  - the segmented deadline is buildable with `withTimeoutOrNull(remaining)` and keeps DL:96;
  - the not-Ready replay;
  - energy re-checks;
  - separate seed pools.
- **Minor:**
  - `FloatArray?` is ambiguous under a timeout.
  - Text install parity: the `id in textIndex` skip, and an encoder epoch after `release`.
  - The D6 time trigger is quadratic on slow phones.
  - The 5 s grace must never be awaited inside a hold.
  - The cap during playback must be `min(workers, 2)`.
  - The D2 charge when a re-decode waits behind a hung look-ahead.
  - Late-submit position.
  - A structural test that AnalysisRun never calls AG inside `inference`.

**Product / platform → NOT READY. 2 major, 5 minor.**
- **Major:**
  - The waiting and stopping rules contradicted each other in three places: at the budget cap, on screen-off waits,
    and in the low-battery resume (1e vs 2e). So the 10k estimate could not be checked.
  - Per-channel defaults need channel detection, and the build has no flavors. A flavor would also change the
    frozen F-Droid recipe.
- **Minor:**
  - The 30 min bound cannot fire while the CPU sleeps.
  - There is no thermal API on API 24–28, and headroom is polling.
  - `userPaused` is invisible where its cost is felt.
  - What each notification tap does was not stated.
  - Notify-only progress must be in Slice B (NOT:51-55 `startService` throws from the background).

**v8 response:**
- **One state table** (rows 1–7) replaces the scattered prose:
  - the cap leads to a waiting row bounded at 30 min, checked at the next wake;
  - low battery stops and resumes by Continue;
  - 2e only adds the soft Battery Saver slow-down and headroom.
- **Estimate** recomputed per usage pattern: 2–3 days with daily opens.
- **Defaults** are the same on every channel: charging counts, a first-run consent, and existing users are treated
  as consented.
- **Taps:** Continue is a service PendingIntent (an exempt FGS start); a body tap opens the app.
- **Thermal on API 24–28:** battery temperature.
- **`userPaused`** shows in the SMART hint.
- **Slice B:** notify-only progress.
- **All correctness minor findings taken.**

### Round 8 (2026-11-01): two fresh reviewers on v8 → NOT READY (no blocking findings)

**Product / platform: 2 major, 5 minor.**
- **Major:**
  - Row 7 could never match under "first match wins".
  - Continue (and "next app open") had no way to start a pass: the only entry point is composition; `LaunchedEffect`
    does not re-fire with unchanged keys; the service holds no scan or strings.
- **Minor:**
  - A Continue on row 3 stops again at once.
  - Retry cleared `userPaused` globally.
  - §2.3 stated charging as a fact.
  - The map-rebuild job's wake locks were not in the budget.
  - Doze was missing from the matrix.

**Correctness: 5 major, 5 minor.**
- **Major:**
  - The same Continue gap.
  - D6: presses still write the whole snapshot above 64 MiB (ENG:938-941, 955-976).
  - Codec exhaustion usually throws IOException or RuntimeException from `createByCodecName`, so contention counted
    as a D2 attempt.
  - The iOS copy to Application Support was not atomic, yet it wins.
  - Slice B stops mid-chunk abandon workers and leave partial rows, with no repair until Slice C.
- **Minor:**
  - The text epoch drop was undeclared.
  - Install "then the persist" contradicted the cadence.
  - The handle API lacked the two retry decodes.
  - Wake-lock renewal had no per-track hook in Slice B.
  - D4 retries were never closed off.

**v9 response, all taken:**
- The 30 min bound lives in rows 2 and 6, with one shared timer.
- Continue is an activity PendingIntent with an ON_START restart trigger. A service start with no pass stops at
  once. Tests cover each case.
- Above 64 MiB, presses only mark dirty.
- Back-off on every CODEC-stage `Unavailable`. D2 counts only source-open or access failures and own-segment
  timeouts. `WindowResult` carries the stage.
- The iOS copy goes per file via tmp, fsync, rename and dir fsync.
- Slice B stops act at chunk boundaries.
- Text after `release`: encoded inside the hold, as in 0.9.3.
- The persist runs only when the cadence is due.
- `decodeWindow(handle, startMs)` covers the retries.
- Renewal on the `analysing` change.
- D4 is capped at one retry.
- Row 3 continues with a 10 % re-arm.
- Retry leaves the pause.
- Charging is worded as unclear.
- Map-rebuild time counts in the budget.
- Doze row and a ≥ 90 min measurement.

### Round 9 (2026-11-01): two fresh reviewers on v9 → NOT READY (no blocking findings)

**Product / platform: 1 major, 5 minor.**
- **Major:**
  - The ON_START restart trigger had no notion of stop class. It undid the stuck pause and the restore block,
    restarted after exceptions with no backoff, and flickered on battery stops.
- **Minor:**
  - The Pause action had no mechanism.
  - The reason given for notify-only progress was wrong (`startService` is legal while the FGS is up).
  - The 2 min cooling hysteresis relied on a timer that stops during suspend.
  - Retry cannot run once the pass is gated.
  - Map-job wakes during a partial analysis.

**Correctness: 4 major, 5 minor.**
- **Major:**
  - In-app Continue, and a shade tap while the activity is resumed, fire no ON_START.
  - Pause gating the whole run would change stored state: sync, tag carry-over, the metadata pass and Retry live in
    it (AG:597-643).
  - The trigger undoes the stuck pause and the battery stop.
  - The iOS per-session lock: a call queued behind a late call gets classed stuck and remembered as Broken.
- **Minor:**
  - The back-off hits deterministic codec rejections, with no cap.
  - `Unavailable` has no stage field.
  - iOS handles leak from late calls.
  - Waiting rows hold dirty tracks with no flush in Slice B.
  - The VIS pair as one copy unit, and the Continue PendingIntent identity.

**v10 response, all taken:**
- One "stop and restart semantics" block. The table gates only the audio pass and D4. Retry is direct. A
  persisted `stopReason` decides which reasons a trigger may restart. Triggers are ON_START plus the `userPaused`
  flow. The extra is handled before `setContent`. The table is checked before `show()`.
- Pause is a service intent with its own action.
- A flush on entering waiting or stopped rows.
- `elapsedRealtime` hysteresis, and "may need Continue" wording.
- The notify-only rationale corrected; the map-job wakes measured.
- iOS: try-lock with a BUSY result, plus `release(handle)`.
- The back-off only under contention or transient errors, capped at 3 s per track.
- A stage field, with its mapping.
- The VIS pair copied as one unit.

### Round 10 (2026-11-01): two fresh reviewers on v10 → NOT READY (no blocking findings)

**Both reviewers, independently:**
- Continue fires no trigger for stops where `userPaused` is already false (battery, wait-30, timeout) while the
  activity is resumed (singleTop, `onNewIntent`, MainActivity.kt:135-139).
- The restartable list also left out `user`.

**Product / platform: 2 major, 4 minor.**
- **Major:**
  - The Continue trigger gap above.
  - The first-run consent was not in the state machine.
- **Minor:**
  - Pause while waiting has no chunk boundary.
  - Row 3 said "session" in one place and "process" in another.
  - Stopped notices need `POST_NOTIFICATIONS`.
  - "No live pass" calls `startForeground` for a plain `startService`.

**Correctness: 2 major, 5 minor.**
- **Major:**
  - Retry no longer relaunched a `complete` pass (AG:551-556), a regression in Slice B on its own.
  - The Continue trigger gap above.
- **Minor:**
  - A replayed Continue extra undoes a later Pause.
  - The iOS stage mapping (`localURL` returns at once for `file://`).
  - A same-key relaunch resets the state and rebuilds map regions.
  - Slice C stops at a boundary still abandon look-ahead; Pause released the wake lock too early.
  - iOS: a stuck lock holder loops BUSY; the move's fresh base during a restore failure.

**v11 response, all taken:**
- Continue is an explicit one-shot `continueRequests` event that restarts every reason except stuck and
  restore-blocked. ON_START restarts only the automatic reasons.
- Row 0 (consent): persisted and migrated once, with tests.
- Retry: `retryFailedTracks(chosen)`, then `rerun = true`.
- Extras consumed once, with guards.
- iOS mapping per error code.
- A same-key relaunch carries its state over.
- AnalysisRun drains its look-ahead at a stop.
- Pause is a table event that keeps the wake lock to the boundary.
- A stuck holder pauses iOS at once.
- The fresh base is written only after a clean restore.
- Best-effort stopped notices.
- The `startForegroundService` marker.
- Row 3 scoped to the process.

### Round 11 (2026-11-01): two fresh reviewers on v11 → NOT READY (no blocking findings)

**Product / platform: 1 major, 4 minor.**
- **Major:**
  - Row-0 consent had no UI place and no iOS scope, so a fresh install could stay unanalysed forever. The Map's
    "still reading" text would be false.
- **Minor:**
  - A cold-start Continue is a state, not an event.
  - Zero-row migration silently stops analysis.
  - Map-job wakes while gated.
  - The MODERATE thermal threshold is a guess.

**Correctness: 3 major, 5 minor.**
- **Major:**
  - A gated relaunch drops the audio side of the map regions for hours (`audioVectorsReady = complete`).
  - The consent migration can switch consent off after a failed restore.
  - The look-ahead drain at a stop has no bound.
- **Minor:**
  - A Continue lost before anything listens.
  - Reference-counted wake-lock renewals.
  - The iOS try-lock scope and its completion signal.
  - Protected-data detection.
  - `stopSelfResult`.

**v12 response:**
- **Consent** keeps producing defects that belong to a product and policy feature, not to analysis reliability. It
  is therefore **taken out of this plan**: 0.9.3's automatic start stays. A consent design would be separate and
  reviewed on its own, and only on the owner's word (§7).
- **Everything else is taken:**
  - map readiness kept while gated or stopped;
  - the map job returns success while user-paused;
  - a bounded drain outside `mutex`;
  - Continue as persisted state plus a latch;
  - a non-reference-counted wake lock;
  - the iOS lock only on model runs, with a completion signal;
  - an `isProtectedDataAvailable` snapshot;
  - `stopSelfResult`;
  - a predeclared row-2 threshold rule.

### Round 12 (2026-11-01): two fresh reviewers on v12 → NOT READY (no blocking findings)

**Product / platform: 1 major, 5 minor.**
- **Major:**
  - The row-2 thermal rule contradicted itself: two headroom values, a resume that cannot hold under the fallback,
    and no load stated.
- **Minor:**
  - The §7 Play row was hard to read.
  - Map readiness did not survive a restart.
  - The map job looped through waits.
  - Small inconsistencies (10 % flag scope, "if held", the Slice B copy needing Slice C's fsync).
  - Split Slice B; Done conditions for 1e-ios and the phone.

**Correctness: 2 major, 5 minor.**
- **Major:**
  - A stale Pause, or a pause with nothing left, kept a complete library from ever being complete again.
  - On iOS, a press could wait on BUSY unbounded while holding `mutex`.
- **Minor:**
  - Map readiness after a library change while paused.
  - LaunchedEffect re-fires bypassed `stopReason`.
  - `isProtectedDataAvailable` is main-thread only.
  - The map job loops on D2-capped tracks.
  - Thermal thresholds conflicted between row 2 and 2e.

**v13 response, all taken:**
- One thermal rule (T, H, resume T−1 / H−0.1), predeclared and measured under load; 2e defers to it.
- The gate applies only while tracks are missing; a stale Pause is harmless.
- iOS press BUSY is bounded, then the no-audio fallback.
- A sticky coverage-based `audioReady`.
- `stopReason` honoured on every call.
- A notification-fed protected-data flag.
- The map job returns success in every waiting or stopped state; `missingFromIndexOf` excludes capped tracks.
- Slice B split into B1/B2; the iOS copy moves to Slice C.
- Done conditions added; the §7 Play row rewritten.

### Round 13 (2026-11-01): two fresh reviewers on v13

**Correctness → READY (no blocking or major).** Minor findings:
- A waiting pass that resumes by itself loses its map rebuild.
- A running relaunch drops the sticky tier.
- D4 retries ran through a Pause; a cold-started service cannot know what is missing.
- The iOS analysis side must wait for BUSY outside `inference`.
- The protected-data flag had no starting value.

**Product / platform → NOT READY: 1 major, 5 minor.**
- **Major:**
  - The sticky readiness leaked into every reader of `audioVectorsReady`, which treats it as "analysis finished"
    (AppMapPage.kt:77, 175, 201, 231; LibraryRegionsService.kt:54; RegionIdentityKeeper.kt:69). It also flipped on
    every move between waiting and running.
- **Minor:**
  - No headroom reader while waiting; NaN handling.
  - The #27 stall message fires in waiting states.
  - B1's failed `show()` was undefined without B2.
  - Heat outranked low battery in the table.
  - Thermal defaults come from one phone.

**v14 response, all taken:**
- `audioVectorsReady` stays 0.9.3's. A separate monotonic `audioTierReady` feeds only the regions refresh. It is
  computed over analysable tracks and decided at run start.
- The map rebuild is re-enqueued at completion and at waiting → running.
- The gate includes D4-pending tracks; Pause always persists.
- iOS: BUSY is handled outside the locks; presses skip after a stuck holder; the protected-data flag reads once at
  startup.
- Headroom is read on events; NaN means no signal.
- `running` is false while waiting.
- B1 `show()` behaviour defined.
- Battery row placed above heat; per-device thermal checks.

### Round 14 (2026-11-01): two fresh reviewers on v14 → NOT READY (no blocking findings)

**Both reviewers found the same defect independently.**
- `audioTierReady` fed the very `audioReady` argument that LibraryRegionsService.kt:54 reads as "analysis
  finished". With `RegionIdentityFlag` on by default, region identity was saved while analysis was still incomplete.
- Because the tier was monotonic, completion no longer triggered a refresh.
- **Correctness also found:**
  - the tier survived Rebuild analysis;
  - the cold-start order of tier and metadata.
- **Product also found:**
  - the fused-before-complete behaviour is undeclared and visible to users.
  - `running = false` while waiting re-enabled the force-Retry and Rebuild buttons in Settings
    (SettingsScreens.kt:2009-2024, 2145).
- **Minor findings:**
  - contradictions about Pause;
  - B1's "non-fatal show";
  - the iOS observer order;
  - a home for the per-row Retry;
  - throttling map-job re-enqueues;
  - a one-page owner summary.

**v15 response:**
- The coverage tier is **dropped**. The Map and For You keep 0.9.3 readiness for every reader. Fused regions before
  completion become a separate owner call.
- `running` stays true while waiting; a new `phase` field drives the display and the stall text.
- The Settings Retry moves to the D3 path.
- Pause wording unified; B1 `show()` defined.
- iOS observers registered before the read.
- The map-job re-enqueue happens once per run.
- An owner summary at the top.

### Round 15 (2026-11-01): two fresh reviewers on v15 → NOT READY (no blocking findings)

**Both reviewers found one major each, in the same area: Retry and the controls.**
- **Product:**
  - the Settings recovery Retry (`needsRecovery`, SettingsScreens.kt:2009-2024) would show as "Retry" in every new
    stopped state;
  - there was no in-app Pause or Continue.
- **Correctness:**
  - `rerun` contradicted "every call honours `stopReason`", so Retry could not recover an exception stop that
    0.9.3's `force` does recover;
  - not all three call sites were named.
- **Minor:**
  - the stall `produceState` keys (AnalysisStall.kt:53-66);
  - an ETA and a frozen "X of Y" while waiting;
  - Retry and Rebuild disabled during waits;
  - what `rerun` resets;
  - Retry when the engine is not Ready;
  - Rebuild while paused;
  - the summary missed the user trade-off.

**v16 response, all taken:**
- A controls-per-state table, with every caller named and Continue never shown as "Retry".
- Retry has its own restart row: it restarts exception (and resets its backoff), show-failed, wait-30, timeout-6h
  and battery; it is disabled for stuck and restore-blocked.
- The Retry clear runs inside the relaunched pass after `initialize()`.
- `rerun` resets like `force`, as in 0.9.3.
- Stall keys on `phase`, and `progressAt` resets on resume.
- ETA is null while waiting.
- Rebuild clears `userPaused`.
- The trade-off is in the summary.

### Round 16 (2026-11-01): two fresh reviewers on v16 (the first launch was on unchanged text and was stopped)

**Product / platform → READY (no blocking or major).** Minor findings:
- Rebuild is a caller of its own.
- The Problems page shows a busy bar while waiting.
- The iOS scope of the controls was not stated.
- Retry while paused reset `metadataReady`.
- Honesty about the night wait, the 0.9.3 comparison, and "SMART's answers".

**Correctness → NOT READY: 2 major, 3 minor.**
- **Major:**
  - Rebuild (App.kt:4594) was an unlisted caller with no call kind.
  - Retry while user-paused did nothing, because the restart rules looked as if they gated the whole relaunch.
- **Minor:**
  - Rebuild versus the `userPaused` rule.
  - "open-app" versus show-failed.
  - Where the Retry clear runs (after `synchronizeLibrary`, at AG:609).

**v17 response, all taken:**
- The restart rules decide only the audio pass. Explicit calls always relaunch steps 1–3 and the clear.
- Rebuild is its own kind: it clears `userPaused` and `stopReason`, and is disabled for stuck and restore-blocked.
- Callers named (App.kt:2731, 4583, 4594).
- Retry while paused carries `metadataReady` over.
- The Problems bar shows the phase text.
- iOS controls scope stated.
- **Row-6 bound by API level:** 30 min on 35+, 6 h on ≤ 34, so 0.9.4 is never worse than 0.9.3's endless FGS.
- Matrix records the first event after 30 min.
- Summary gains the 0.9.3 comparison and qualifies "SMART's answers".

### Round 17 (2026-11-01): two fresh reviewers on v17 → NOT READY (no blocking findings)

**Product / platform: 1 major, 4 minor.**
- **Major:**
  - Rebuild was disabled for restore-blocked. That removed 0.9.3's only way out of an unreadable index
    (ENG:1246-1253), and D6 can make a restore fail every time.
- **Minor:**
  - Row 6 overclaimed "not worse".
  - `wait-30` misnamed for the 6 h bound.
  - Rebuild missing from some states.
  - The Problems-page phase text covered only one branch.

**Correctness: 3 major, 2 minor.**
- **Major:**
  - The failure list stays empty while the audio pass is gated, so D3 Retry and D2-capped tracks were unreachable.
  - The 6 h bound and the shared timer made API ≤ 34 worse than 0.9.3: a heat blip after 30 min in row 6 stopped
    the pass.
  - Retry ids lived only in the relaunched pass and could be lost to a following relaunch (the "yes" path).
- **Minor:**
  - Persisted stuck and restore-blocked could block for good.
  - A stale Pause after completion, followed by Retry.

**v18 response, all taken:**
- Rebuild stays enabled for restore-blocked and is disabled only for stuck.
- A per-device envelope cap if a reload runs out of memory at 48 MB.
- Row 6 is unbounded on API ≤ 34. Row 3 counts only its own wait time. The 30 min total applies only on 35+.
  `wait-bound` renamed.
- A gated-state failure query is published into `failures`.
- A persisted Retry-ids latch; "Retry + Continue" is one call.
- Stuck and restore-blocked are process-scoped.
- No stale Pause after `complete`.
- The Problems phase text shows above its branches.

### Round 18 (2026-11-01): two fresh reviewers on v18

**Product / platform → READY (no blocking or major).** Minor findings:
- The heat stop on API ≤ 34 is new.
- The low-battery stop never resumes on plug-in.
- No heat signal without a thermal HAL.
- The envelope cap is not mentioned in the summary or for iOS.
- "Visible states" and the summary list were incomplete.

**Correctness → NOT READY: 1 major, 6 minor.**
- **Major:**
  - The engine can heal restore-blocked within a process (`retryRestoreIfDue`, ENG:1332-1343). v18 kept the pass
    stopped and steered the user to Rebuild, which deletes a now-readable file. 0.9.3 completes with
    `ModelUnavailable` failures, and its `force` Retry recovers.
- **Minor:**
  - An iOS OOM is an uncatchable jetsam kill.
  - The Retry latch is consumed while blocked.
  - The row-3 timer counts while idle anyway.
  - The press-persistence wording contradicted itself.
  - A gated relaunch resets the visible state.
  - The map readiness change for restore-blocked was undeclared.

**v19 response, all taken:**
- An unreadable index is **not a stop reason**: it behaves exactly as in 0.9.3. That also restores the 0.9.3 map
  readiness.
- Rebuild asks for confirmation.
- No latch consumption while blocked.
- iOS: a reload gate and an `os_proc_available_memory` cap, plus a restore marker against crash loops. A capped
  device says "too large to keep".
- **API ≤ 34 has no new automatic stops:** heat and low battery wait and resume.
- The row-3 timer counts only while a running row would match.
- Battery-temperature fallback without a thermal HAL; `forecastSeconds` stated.
- A gated relaunch carries `running = false`, phase, `stopReason` and failures.
- The press wording is qualified.
- Visible states point to the controls table; the summary lists all owner calls.

### Round 19 (2026-11-01): two fresh reviewers on v19 → NOT READY (no blocking findings)

**Product / platform: 2 major, 5 minor.**
- **Major:**
  - The 2-stuck pause is a new automatic stop on every API level, which made the summary's claim false.
  - On iOS, a suspension could be classed as stuck.
- **Minor:**
  - The summary and estimate wording by API level.
  - Unstable envelope-cap inputs.
  - The iOS restore marker's scope.
  - No rule for the 42 °C fallback.
  - The Rebuild confirmation did not state its cost.

**Correctness: 3 major, 3 minor.**
- **Major:**
  - A cap that moves with free memory can overwrite a good index: an over-limit file reads as absent (FIS:190,
    205-208).
  - The iOS restore marker still leaves a crash path or a dead pass.
  - Rebuild could run during a relaunch's steps 1–3 and interleave text rows (ENG:833-850 has no generation check).
- **Minor:**
  - The unreadable index was still gated by the table.
  - The latch rule named a private field.
  - A host case contradicted 0.9.3's dedup.

**v20 decision:** most of the remaining defects sit in the D6 envelope raise (caps, iOS jetsam, size-scaled
cadence), so **D6 is parked**. The 64 MiB envelope and libraries above ~30k stay as in 0.9.3. The stuck pause is
dropped as well: analysis carries on with fresh workers, as in 0.9.3, and stuck tracks are Broken (D1).

**Also taken:**
- iOS stuck is counted on foreground running time; a stuck lock holder gets one fresh session.
- An unreadable index is ungated, with no wake lock, via a new engine query, which also gates the latch.
- Rebuild cancels and joins the job, and `clearAnalysis` bumps `generation`.
- The host case rewritten to "force Retry resumes".
- Summary wording per API level.
- The battery-temperature rule.
- The Rebuild cost stated.

### Round 20 (2026-11-01): two fresh reviewers on v20 → NOT READY (no blocking findings)

**Product / platform: 1 major, 5 minor.**
- **Major:**
  - The API 35+ wait-bound and battery stops save nothing. A dataSync restart needs the same app open, which also
    resets the 6 h cap. They cost the morning auto-resume that 0.9.3 has, plus a whole API split.
- **Minor:**
  - "Pauses" vs "waits" in the summary.
  - No Rebuild during long waits.
  - The 10 % threshold only arms on API 35+.
  - The show-failed in-app text.
  - The Rebuild confirmation does not say analysis restarts.

**Correctness: 1 major, 5 minor.**
- **Major:**
  - With D6 parked, libraries above about 30k get a daily wake lock for work that can never be saved
    (`EnvelopeRefusal`, ENG:1546-1557).
- **Minor:**
  - A D6 leftover in §7.
  - Stuck → Broken for an id indexed meanwhile.
  - iOS forgiven timeouts could retry forever (keep `forgivenBefore`).
  - The generation check inside one hold is empty (needs a job token).
  - The restore retry must run before the gate, and the ungated path must respect `userPaused`.
  - Rebuild must relaunch in a `finally`.

**v21 response, all taken:**
- **No automatic stops on any API level:**
  - rows 2, 3 and 6 wait unbounded;
  - `onTimeout` is the only cap stop, as in 0.9.3;
  - wait timers, battery and wait-bound stops, and the 10 % flag are removed.
- **Envelope refusal:** row 5 acts as row 6 (no wake lock), with a "too large to keep" message.
- **Rebuild:** "Pause to rebuild" while waiting; a confirmation that says analysis restarts; relaunch in `finally`.
- **show-failed** restarts silently.
- **Engine and iOS fixes:**
  - stuck skips indexed ids;
  - `forgivenBefore` kept;
  - a generation token from AG;
  - the restore retry before the gate;
  - the ungated path skips `show()` while paused.
- **Summary and estimate** rewritten.

### Round 21 (2026-11-01): two fresh reviewers on v21

**Product / platform → READY (no blocking or major).** Minor findings:
- The cooling resume can stall with no event.
- The budget refill is noticed late.
- The show-failed text was inconsistent.
- Stopped notices must survive the service stop.
- The API 35+ "not opened" window may end within the first night.

**Correctness → NOT READY: 1 major, 5 minor.**
- **Major:**
  - A row-3 heat wait could stay stuck for the process. NaN headroom never satisfied the exit test, and nothing
    re-checks after cooling. Since the pass stays live, an app open dedups and does not resume it.
- **Minor:**
  - The show-failed text.
  - The generation token against ENG:350-356.
  - A stale-token job latching `complete`.
  - The envelope-refusal query false before the first refusal.
  - Summary wording.

**v22 response, all taken:**
- NaN headroom is satisfied at exit, with a status or battery-temperature fallback.
- A 60 s re-evaluation tick while waiting, plus ON_START, screen-on and first-cool re-checks; the same tick catches
  the budget refill.
- show-failed text unified.
- The token overrides ENG:350-356 and is a listed G-f exclusion; a stale-token job aborts as not complete; Rebuild
  bypasses the dedup.
- The refusal query predicts from estimated size.
- Stopped notices are re-posted as non-ongoing.
- "Possibly within the first night" added; summary wording fixed.

### Round 22 (2026-11-01): two fresh reviewers on v22 → **READY** (both roles: no blocking, no major)

**Minor findings, product / platform:**
- The tick runs on uptime.
- The estimate is built on numbers not yet measured.
- The map-job text contradicted itself.
- The timeout and show-failed rows exist only as notifications.
- A fallback for the charge counter; formatting fixes.

**Minor findings, correctness:**
- The token rule conflicts with §0.2.
- A failed clear followed by `force` wipes every failure.
- The envelope sizing API and its cost.
- Stopped notices share the FGS notification id.
- The receiver should take a wake lock before signalling; the tick wording.

**All taken as text fixes in the final v22:**
- Tick and event wording.
- A short wake lock in `onReceive`.
- The estimate is marked "pre-Step-0", including drain overhead.
- One rule for the map job.
- Notification-only rows.
- A fallback for undecided energy.
- A §0.2 note on Rebuild.
- Rebuild relaunches as a Continue-kind call.
- `IndexStore.encodedSizeOf` with an incremental counter.
- A separate notification id for stopped notices.
- Formatting fixes.

**The plan is READY for the owner's decision.**
