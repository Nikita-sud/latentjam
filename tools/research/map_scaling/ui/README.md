# Cached-map gesture profiling

This standalone shell helper replays a two-pointer gesture against the foreground app. It is not
part of the APK and does not change application data or settings. It **does inject real input**:
verify the foreground page, orientation and plot bounds before running it. Screenshots, UI dumps,
trace files and full `gfxinfo` dumps may contain personal information; keep those outside Git.

## Build

Requires installed JDK and Android SDK tools; the build script performs no downloads, Gradle
builds, installation or device access. The initial build used JDK 21, API 36 and build-tools 36.1.0.
Set `JAVA_HOME` to the JDK used by D8, then run from the repository root:

```sh
python3 tools/research/map_scaling/ui/build_gesture.py \
  --javac "$JAVA_HOME/bin/javac" \
  --android-jar "$ANDROID_HOME/platforms/android-36/android.jar" \
  --d8 "$ANDROID_HOME/build-tools/36.1.0/d8" \
  --output /tmp/latentjam-map-gesture.jar
adb -s DEVICE push /tmp/latentjam-map-gesture.jar /data/local/tmp/latentjam-map-gesture.jar
```

The script verifies that the result contains `classes.dex` and prints source/JAR hashes. Save
these, app version and installed APK hash with each comparison. Do not check in generated binaries.

## Workload

Before sampling, wait for indexing/layout calculations to finish and stop numerical benchmarks.
Use the same warm map, selected region, lens, viewport, orientation and scroll position for paired
samples. Record display/refresh settings, charging state and thermal context; close unrelated
overlays without changing app data. Reset the map to 1× and confirm the chosen coordinates are
inside its plot. Inline maps require two pointers; an ordinary one-pointer swipe scrolls the page.

Arguments are `centerX centerY initialSpan maxSpan cycles [phaseMs=1000]`. A span is the **full
horizontal distance between fingers**, in screen pixels. The historical 1080×2340 portrait sample
used the following coordinates; these are an example, not a discovery mechanism:

```sh
adb -s DEVICE shell dumpsys gfxinfo io.github.nikitasud.latentjam.kmp reset
adb -s DEVICE shell 'CLASSPATH=/data/local/tmp/latentjam-map-gesture.jar app_process /system/bin MapGesture 540 1050 120 480 3 1000' > /tmp/map-gesture-run.log
adb -s DEVICE shell dumpsys gfxinfo io.github.nikitasud.latentjam.kmp > /tmp/map-gfx-run.txt
```

One cycle: DOWN, second-pointer DOWN, pinch out, pan 100px right, return, pinch in, second-pointer
UP, final UP. There are four equally timed movement phases, nominal 16ms cadence and 250ms between
cycles. With 1,000ms phases the helper sends 63 moves per phase; three cycles mean **756 MOVE events
and 768 total events**. The example requires x=300…880 and y=1050 to remain inside the visible plot.
Reset to 1× before each sample, and collect at least three samples rather than a single best run.

Every injection result is checked. Failure aborts with a nonzero exit code and attempts CANCEL,
then pointer-UP/UP if CANCEL fails. Cleanup remains best effort when the OS rejects injection.
The helper uses hidden InputManager APIs through reflection and needs shell injection permission;
it is not a portable substitute for ordinary application instrumentation on all Android versions.

Inspect the resulting map, not just the exit code: accepted input does not prove the map handled
it. The nominal 4× span ratio may differ from actual zoom because of gesture slop or viewport
clamping. The helper waits synchronously for dispatch and reports scheduling lateness; it does not
guarantee exact 60Hz input and does not measure touch-to-photon latency. A slow dispatch delays later
events; keep the helper and workload identical when comparing builds.

## Perfetto: separate diagnostic capture

Collect the untraced `gfxinfo` baseline separately, so tracing overhead is not silently mixed into
the comparison. Start an 18-second trace in terminal A, passing
[map-gesture.pbtxt](map-gesture.pbtxt) through stdin. On the tested phone, Perfetto could not read
a config placed in `/data/local/tmp` (`Permission denied`); stdin was verified to work:

```sh
adb -s DEVICE shell perfetto --txt \
  -c - -o /data/misc/perfetto-traces/latentjam-map-gesture.perfetto-trace \
  < tools/research/map_scaling/ui/map-gesture.pbtxt
```

Once recording starts, run the same gesture command in terminal B. After terminal A finishes:

```sh
adb -s DEVICE pull /data/misc/perfetto-traces/latentjam-map-gesture.perfetto-trace /tmp/map-gesture.perfetto-trace
```

The first October capture used a **32 MiB ring buffer and wrapped**. Its retained duration was only
about 15.38 seconds despite an 18-second recording. Missing trace sections in that capture cannot
prove the absence of layout, prefetch or input work. The checked-in config uses **64 MiB**; this is
more capacity, not a guarantee of completeness. Verify each new trace covers the whole workload
and inspect trace-processor loss/error statistics, for example:

```sql
SELECT (end_ts - start_ts) / 1e9 AS retained_seconds FROM trace_bounds;
SELECT name, idx, severity, value FROM stats
WHERE value > 0 AND severity != 'info'
ORDER BY name, idx;
```

Use FrameTimeline and app/UI/RenderThread tracks to separate frame completion from presentation
and input delay. Inspect measure/layout and neighbouring-page prefetch during two-pointer gestures.
Absence of a slice is meaningful only if that category was collected for the app and the relevant
interval is retained. The injector logs uptime and wall time; do not equate its uptime directly
with every trace clock without checking the trace clock mapping.

## Historical baseline, 13 October 2026

These **precede** the current removal of region polygons and the Map fade/inset changes. They are
not an after-change result or a speedup claim. Device: Samsung S24 Ultra, Android 16, installed
LatentJam 0.9.0, 1,107-track map. Each untraced sample used three cycles above; a nearby battery
snapshot recorded USB charging and 38.0°C, not sustained thermal behaviour.

| Run | Rendered frames | p50 | p95 | p99 | Janky frames | High-input-latency counter |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 756 | 8ms | 12ms | 14ms | 2 / 0.26% | 76 |
| 2 | 756 | 8ms | 12ms | 15ms | 5 / 0.66% | 216 |
| 3 | 756 | 9ms | 12ms | 15ms | 3 / 0.40% | 122 |

All 768 injected events were accepted in each run. Actual gesture durations were 13.374 / 13.383 /
13.389 seconds; 493 / 498 / 483 moves started more than 16ms after their scheduled time, with maximum
lateness 50 / 54 / 68ms. Scheduling lateness is a helper/dispatch measurement, not a calibrated
end-to-end UI latency measurement. `gfxinfo` repeats aggregate and per-window counters in its dump;
do not sum the duplicated blocks.

Low jank in these samples **does not establish low input latency**, sustained responsiveness,
performance on weaker devices or an improvement over the September workload. The high-input-latency
counters and injector lateness are reasons to investigate input/presentation timing independently.

## UI change review checklist

The Map's former outer bottom padding cut scroll content off before the existing 24dp player dock
gradient. The change moves that clearance to the end of scroll content and applies the shared 10dp
top fade when scrolled. No new page-sized offscreen layer or continuously animated state was added.

Verify portrait at top/middle/end, returning from a selected region, large text and both landscape
orientations. The last action must scroll entirely above the player. Landscape's fixed plot retains
bottom clearance and uses the same usable-height breakpoint; only its controls scroll underneath
the dock fade. Fullscreen retains safe insets and its fixed-plot layout, with no mini-player fade.
Without a mini-player there is no dock fade, and navigation-bar/end padding still keeps actions
reachable. Fade modifiers do not add gesture owners; two-pointer arbitration is unchanged.
