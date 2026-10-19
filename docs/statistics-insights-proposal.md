# Statistics insights — proposal (19 October 2026)

Status: **design for the owner's review, nothing implemented.** Branch `claude/stats-habits-55f9ee`, cut from
`release/0.9.2` at 334a36a9.

The current dashboard (`composeApp/.../StatsScreen.kt`, facts in `core/history/.../ListeningOverview.kt`, validated in
`docs/statistics-validation.md`) answers *how much* you listened: time, plays, streaks, completion/skip, coverage,
SMART share, hours, top lists. The SMART research measured *how* people listen. This proposal adds five cards that
answer that question for the user, from their own log only, plus one optional comparison with typical listeners.

## What the log gives us

Every card below is computed from `ListenEvent` alone (already in memory as the dashboard's history snapshot):
`trackId`, `startedAtMs`, `listenedMs`, `completed`, `skipped`, `shuffleMode`, and for v4 lines `origin`
(`start` = USER_PICK / AUTO_ADVANCE / SKIP_NEXT / SKIP_PREVIOUS / REPEAT, `smartPlanPosition`, `parentId`). Artist and
album come from the catalog (`TrackDescriptor`); same artist uses the engine key `MetadataRerank.normalizeArtist`.
Legacy (pre-origin) lines have `origin == null`; every card states how it treats them.

Conventions shared by all cards (same as the existing dashboard): a **play** is a non-skipped start; timestamps outside
`0..now` are ignored; tracks no longer in the library count in totals but are not shown in lists.
Two neighbouring plays form a **step** when the second starts ≤ 15 min after the first one ended
(`startedAtMs + listenedMs`) — the research harness's rule — and an immediate repeat of the same track is collapsed
(`score.collapse_repeats`). A **session** is a run of plays with gaps ≤ 30 min.

## The cards

### 1. Returns and discoveries *(follows the period filter)*

> **Familiar 78 % · New to you 22 %** — one two-colour bar.
> "42 first listens, 9 new artists." Trend line under it: "Last 30 days: more new music than the 30 before (22 % vs 15 %)."

- Return = a play of a track that has an earlier play in the saved log; first listen = otherwise. New artist = first
  play of any track of that artist key. One extra hash set of artist keys next to the existing `tracksBeforePeriod`.
- Why: the research found re-listening is most of listening and is driven by recency and frequency; people rarely
  see their own split.
- Small/fresh histories: "first" means "first since the log started". If the log starts less than 14 days before
  the period's start, the card says "Counting since 3 October" and skips the trend line. Hidden under 20 plays.

### 2. In heavy rotation / Slipping away *(whole log, ignores the filter — a habit is a fact about now)*

> **In heavy rotation** — 5 tracks and 3 artists with a strength bar (relative to the first row).
> **Slipping away** — up to 5 tracks you played a lot and have not heard for a while: "12 plays · last 4 months ago". Tap plays.

- Heavy rotation is the ACT-R base level (Anderson; `score.activation()`, d = 0.5):
  `B_i = ln Σ_j max(1 s, now − t_j)^−0.5` over the track's plays. Artists sum over their tracks' plays. Shown as a bar,
  never as the raw number. It differs from *Top tracks* (counts in the period) because last week's plays weigh more
  than last year's.
- Slipping away: ≥ 5 lifetime plays, last play ≥ 60 days ago, ranked by lifetime plays. Gives the dashboard its first action ("play it again") and is honest: these are the user's own
  favourites, not recommendations.
- Hidden under 30 plays or 5 distinct tracks; Slipping away hidden until the log spans ≥ 90 days.

### 3. Your sequences *(whole log)*

> **After "Ce seara" you usually play "Dor de tine" — 4 of 6 times.** Up to 3 rows, each tappable (plays the pair).

- Counts steps X → Y (the research's `transitions()`); shows pairs with ≥ 3 occurrences and ≥ 40 % of X's outgoing
  steps, ranked by count.
- Honesty filter — a fixed order is not a habit: drop a step whose second play is `AUTO_ADVANCE` with
  `smartPlanPosition == null` (album or playlist order), and for legacy lines without an origin drop same-album steps.
  Steps whose second play SMART picked (`smartPlanPosition != null`) are left out too — SMART would be quoting
  itself. What remains: the user's own picks, skips into their own queue, and legacy cross-album steps.
- Empty state: "No repeated sequences yet — they appear once you play the same pair a few times."

### 4. One artist at a time *(follows the period filter; optional comparison)*

> **You stay with the same artist in 31 % of track changes.**
> Optional strip: typical listeners q10–q90 with a dot for "you": "Typical listener: 12 %. Half of listeners are
> between 2 % and 39 %."
> Footer link: "SMART's artist-run setting" (the slider whose Low/High/Maximum targets come from these same quartiles).

- Same-artist adjacency over steps, excluding steps SMART picked (it measures the user, not the slider).
- Population numbers: `k2a1_people.json` — 1,720 ListenBrainz listeners (CC0) with ≥ 30 pairs; ship only mean 0.238
  and raw quantiles 10/25/50/75/90 = 0 %, 2.3 %, 11.9 %, 39.2 %, 70 %. Six constants in code, no listener data, every
  number backed by ≥ 172 listeners (privacy floor ≥ 10 met by far). Caveat text: "measured on public ListenBrainz
  listening, roughly comparable".
- Card hidden under 30 steps (the same floor the population used).
- If the owner prefers no comparison at all, the card stands on its own; this is the only part that uses external data.

### 5. How you listen in one go *(follows the period filter)*

> **Typical session 38 min · 9 tracks.** "Longest: 3 h 12 min on 14 October."
> Four daypart chips with share of listening time — Morning 06–12, Afternoon 12–18, Evening 18–24, Night 00–06 —
> and "Your longest sessions are in the evening". Sits next to the existing hour chart, which stays.

- Median session duration (first start to last end) and median plays per session; longest session in the period.
  Daypart by the session's start hour (local time via `localTimePoint`).
- Why: the research's daypart lift (For You rhythm round) shows when-you-listen is a strong, personal signal.
- Hidden under 3 sessions; a single-day log shows no daypart sentence.

### 6. (Footer, only once the setting exists) Learn from my habits

One line under cards 2–4: "SMART uses your returns and sequences while *Learn from my habits* is on" with a link.
The owner's toggle is decided but not in the code on `release/0.9.2`, so this line ships only with it.

## Not proposed (and why)

- Return share vs typical listeners: no validated population aggregate exists for it yet; would need its own
  measurement round (same privacy floor).
- Skip-rate per origin ("you skip SMART picks 2× more than your own"): useful for SMART evaluation, confusing as a
  user statistic, and v4 origins are only a few weeks old on most installs.
- Genre/era breakdowns: depend on tag quality, not on behaviour; separate topic.

## Performance and memory

All five cards come from **one extra pass** over the already-loaded event snapshot, on `Dispatchers.Default`, with
the existing cancellation checks every 1,024 events, and are cached with the same history revision.

- Activation: one `DoubleArray` per distinct track (indexed through the existing per-track map) — one `pow` per
  play; 200k events ≈ a few ms.
- Steps: packed `(fromIndex shl 32) or toIndex` into a `LongArray` of at most n entries, sorted in place, run-length
  counted — no boxed pair map. 200k events → 1.6 MB transient, freed after the pass.
- Sessions and dayparts: scalars plus an `IntArray` of session lengths for the medians.
- Artist keys: one `HashSet<String>` sized to distinct artists.
For a 10k-track library and a multi-year log this stays well under the event list itself (the largest object the
dashboard already holds). On 48–96 MB heaps the cards degrade by hiding, not by crashing: an `OutOfMemoryError`
in the insights pass is caught and the existing cards are still shown (lesson from the SMART press OOM).

## Implementation outline (after the owner's go)

1. `core/history`: `ListeningInsights.summarize(events, catalog lookups, sinceMs, nowMs, …)` → pure data class,
   clock-injected like `ListeningOverviews`; host tests for every formula, edge case and threshold (legacy origin,
   album order, repeats collapsed, clock skew, empty, single day, cancellation).
2. `composeApp`: new cards in `StatsScreen.kt` from the existing `StatsCard`/`StatsTitle`/`ShareMeter`/`RankedRow`
   and the release/0.9.2 shared components and header styles; tap actions through the existing play callbacks.
   Keep new lambdas out of `App()` (R8 register edge).
3. Strings: ~25 keys in all 18 bundles (literal `%`, `values-in` identical to `values-id`, plurals for counts).
4. Checks: `testAndroidHostTest`, `compileKotlinIosSimulatorArm64`, lint, R8 release APK launch smoke, read-only
   emulator run with an old and a fresh history; update `docs/statistics-validation.md`.

## Questions for the owner

1. Ship the typical-listener comparison (card 4 strip), or keep everything purely personal?
2. Slipping away with tap-to-play — OK as the dashboard's first action?
3. Card order: proposed after *Habits*: 1, 5, 4, 2, 3, then the existing top lists.
