# Statistics page — whole-page spec (draft v3, 19 October 2026)

Status: **design under adversarial review, nothing implemented.** Branch `claude/stats-habits-55f9ee` (from
`release/0.9.2` at 334a36a9). Review log at the end.

Goal (owner): the page should be *interesting and informative* for an ordinary listener, using what the SMART
research learned about how people listen. Existing cards may go. Everything is computed on the device from the
user's own log and library tags; nothing leaves the device; no population data ships.

## Page at a glance

```
1 · YOU LATELY                          (no chips — always "now")
  L1 On repeat now    5 tracks · bar vs top row · "8 plays in 4 weeks"         tap plays
     Streak 6 days · record 23
2 · YOUR NUMBERS   [ 7 days | 30 days | All time ]   (chips are this section's header)
  N0 Hero            32 h 10 min · +4 h vs previous 30 days        (All time: "≈ 9 days of music")
                     412 plays · 230 tracks · 61 artists
  N1 Activity        daily bars (7/30 d) · "Last 12 months" monthly bars (All time)
  N2 New and familiar [■■■■■■■■□□] 78 % familiar · 22 % new to you
                     31 new tracks · 9 new artists        (counts hidden on All time)
                     New artists that stuck: ≤ 3 rows
  N3 When you listen hour bars (listening time) + 4 daypart labels
                     Typical session 38 min · 9 tracks · Longest 3 h 12 min
  N4 Your top        chips [Artists | Albums | Genres | Tracks] · 5 rows · "Show 10"
  N5 How you listen  Finished 64 % · Skipped 12 % · SMART 41 %
3 · OVER TIME                           (no chips — whole log)
  T1 Slipping away   ≤ 5 old favourites · "12 plays · 4 months"                 tap plays
  T2 On this day     what you played a year ago (log > 1 year)                   tap plays
  T3 Your patterns   Same artist next when you pick: 27 % · sequences X → Y 4/6  (when they exist)
  T4 Library         heard 312 of 5,000 · decades strip
  T5 More appears as you listen   (one card listing what is still locked in sections 1 and 3)
History note
```

**Why this order.** The page opens on something about the listener (round 2: the story was back-loaded under four
screens of period numbers). The chips sit as the header of the only section they govern, so sections 1 and 3 can
never be read as "7 days".

**Removed from today's page:** the separate Habits card (streak → section 1; active days and "per active day"
dropped; "New to you" → N2), the *Repeat plays* meter (contradicted N2), the separate SMART/Coverage card (SMART → N5,
coverage → T4, now all-time only — 7-day coverage read as shaming), the peak-hour sentence (→ daypart labels).
Ten cards plus hero, about the length of today's page at default font (≈ 3,300 dp; ≈ 4,500 dp at 150 %), top
lists capped at 5 rows by default.

**Not shown** (rejected in review): an "added recently, not played" list (`addedAtMs` is the copy time on iOS and
resets on Android migration/rescan; duplicates the Recently added auto playlist), any comparison with other
listeners (see Deferred).

## Normalisation (one pass, `core/history`, pure, clock-injected, host-tested)

Applied once per history revision to the sorted snapshot; every card reads its output.

1. **Exact duplicates** with the same (trackId, startedAtMs) are dropped (backup merge uses full equality,
   `LocalBackup.kt:1486`, so near-duplicates survive there).
2. **Overlap** is detected before any capping: two consecutive events that both have `listenedMs`, where the next
   starts more than 15 s before `start + listenedMs` of the previous, are a break (clock jump, two devices merged):
   no step or session crosses it.
3. **Resume fragments.** A v4 event B is merged into the immediately preceding A when `B.start == null`,
   `B.trackId == A.trackId`, `!A.completed`, and `B.startedAtMs − (A.startedAtMs + A.listenedMs) ≤ 6 h` (a resumed
   half after a pause is null-start and the gap is the whole pause, `HistorySessionTracker.kt:233-264`). Merged:
   A's start, origin and shuffle mode; listened = sum; playedMs = max; completed = A ∨ B; skipped = ¬completed ∧
   listened < 30 s; end = B.startedAtMs + B.listenedMs. Legacy lines are not merged. Finished/Skipped shares change
   slightly as a result (documented).
4. **End** = `startedAtMs + effectiveListenedMs`, capped at the next event's start.
5. **Artist key** = `MetadataRerank.normalizeArtist(first artist)`; an empty key never counts as "same artist" and
   such steps leave numerator and denominator.
6. Times use the device's current time zone for the whole log (documented limit).

Terms:
- **Play** = non-skipped event. Skip < 30 s, completed ≥ 85 %, listened time with legacy fallback — unchanged.
- **Session** = maximal run with gap (next start − previous end) ≤ 30 min and no overlap break. A session belongs to
  the period of its first event.
- **Step** X→Y = consecutive plays in one session, X ≠ Y.
- **Pick** = a play whose `origin.start == USER_PICK` (taps in lists, search, For You, Stats, Play/Shuffle of a
  collection, external queues with a start index). The tracker clears `smartPlanPosition` for picks
  (`HistorySessionTracker.kt:242,250`). Skips are not picks (a skip rejects X; in shuffle it draws at random).
  Legacy lines are never picks.
- **Chosen play** (for favourites) = a pick, or a completed play that SMART did not plan
  (`smartPlanPosition == null && shuffleMode != "SMART"`; the shuffle-mode test also covers legacy lines).
- **Burn-in** for a comparison with the previous window: `logStart ≤ previousStart` (as computed in
  `ListeningOverview.kt:118-120`). Otherwise the existing "first period" text.
- **Variety rule** for track lists in sections 1 and 3: at most 1 track per album and 2 per artist, so a whole-album
  listener does not get one album's track list.

## Section 1 · You lately

### L1 On repeat now (new)
- Weight per track = Σ over local days **in the last 28** with ≥ 1 play of `0.5^(days ago / 14)`, evaluated at the
  start of today (one count per day: loops and fragments cannot inflate it).
- Eligible: ≥ 3 plays on ≥ 2 days in the last 28 days. Variety rule. 5 rows; bar = weight / top weight; subtitle
  plural "N plays in 4 weeks". Card note: "A play counts fully today, half after two weeks."
- Under it, one line: current streak and record (all-time, existing code).
- Not eligible yet → listed in T5.

## Section 2 · Your numbers (chips)

The empty-period state covers this section only (one compact card); sections 1 and 3 still render. The full-page
empty state is only for an empty log. Per-card locked rows exist only here, where chip changes would otherwise make
cards jump.

### N0 Hero (changed)
Listening time, comparison with the previous window after burn-in, plays / tracks / artists. *Tracks* and
*artists* count only items with ≥ 1 play (today they include skip-only tracks while sitting next to *Plays*). All
time adds "≈ N days of music" (plural, from listening time).

### N1 Activity (changed)
7 d and 30 d: today's DailyBars. All time: titled "Last 12 months", monthly bars. DailyBars becomes generic over a
bucket type `(key, plays, playedMs)` with a label lambda; endpoint labels only ("12 months ago" … "This month"),
plural for the selected bar. Buckets before log start are not drawn.

### N2 New and familiar (new, replaces "New to you" and "Repeat plays")
- *New to you* = a play that is its track's first non-skipped event in the log; *familiar* = every other play.
  Two-tone bar. Each new track contributes exactly one new play, so the bar and the counts agree and new tracks ≤
  hero tracks.
- *N new tracks / N new artists* = items whose first play falls in the period; hidden on All time (would repeat the
  hero).
- *New artists that stuck* = artist keys whose first play is in the period **and ≥ 30 days after log start**, with
  ≥ 5 plays on ≥ 3 days; ≤ 3 rows (not tappable in v3, see Taps). On All time: first play in the last 90 days.
- Trend vs previous window only after burn-in. Locked under 20 plays. Footnote when the log starts inside the
  period: "LatentJam has listened along for 12 days" (plural relative days).
- Card note: "New to you = first played in LatentJam" (the library may predate the app).

### N3 When you listen (changed)
- Hour bars now in listening time per start hour (skipped listening included, like the hero total); four daypart
  labels with shares under the axis (Night 0–6, Morning 6–12, Afternoon 12–18, Evening 18–24). TalkBack: one
  description from a two-argument template (daypart, percent).
- *Typical session* = median of Σ listened time per session, and median plays per session; *Longest* = max Σ
  listened time, relative day in the subtitle.
- Locked under 3 sessions.

### N4 Your top (changed)
`AppChoiceChip` row `Artists | Albums | Genres | Tracks` (selected chip and "Show 10" in `rememberSaveable`), 5 rows.
Albums key = (album, albumArtist ?: first artist); Genres = first genre tag; each chip shown only when ≥ 60 % of the
period's plays carry that tag, otherwise the chip is absent. Counts are plays. Only Tracks rows are tappable.

### N5 How you listen (changed)
`ShareMeter`s: Finished, Skipped (definitions unchanged, after normalisation), SMART share (moved here, unchanged).

## Section 3 · Over time (whole log)

### T1 Slipping away (new; the page's first "play it again" action)
- ≥ 5 chosen plays on ≥ 3 distinct days ever, no play in the last 60 days, ranked by chosen plays; variety rule; ≤ 5.
- Recording-gap guard: only if the log has events on ≥ 10 of the last 60 days (Privacy can pause recording).
  Subtitle: "12 plays · 4 months" (plurals); card note "not played in LatentJam since then".
- Locked (→ T5) until the log spans ≥ 90 days.

### T2 On this day (new)
- Log spans > 1 year: the tracks with the most plays on the dates within ±3 days of today one year ago (≥ 2 plays
  in that week, variety rule, ≤ 3 rows), titled "A year ago" (no date formatter needed). Hidden if nothing qualifies.

### T3 Your patterns (new; hidden entirely until it has content, not locked)
- *Same artist next when you pick* = steps whose Y is a pick and X, Y have the same non-empty artist key, over all
  steps whose Y is a pick. Shown with ≥ 30 such steps. Card note: "Includes starting an artist's page."
- *Sequences* X→Y over steps whose Y is a pick; denominator = X's steps whose Y is a pick. Rows when ≥ 3 occurrences
  from ≥ 3 sessions and ≥ 40 %; ≤ 3 rows: two thumbs, `X` / `→ Y` on two ellipsised lines with
  `Icons.AutoMirrored.Rounded.ArrowForward` (decorative), trailing "4/6". Not tappable in v3.
- Pick-based and all-time, so it does not flicker with chips; for logs without v4 picks the card simply is not there.

### T4 Library (changed)
*Heard* = available tracks with ≥ 1 play / library size. *Decades* strip from `originalYear ?: year` of all plays,
shown when ≥ 60 % of plays have a year.

### T5 More appears as you listen
One card listing the locked parts of sections 1 and 3 with their unlock condition ("On repeat now — after a few
plays on two days", "Slipping away — after three months of listening"). Absent when nothing is locked.

### History note (kept)
Adds the documented limits: time zone, restores dropping unresolved tracks, files re-identified after a move.

## Taps
Only **track rows** play (L1, T1, T2, N4 Tracks). Artist, album, genre, discovery and sequence rows are not
interactive in v3: elsewhere in the app such rows open their page, and opening a page from here needs navigation
wiring through `App()` — a follow-up. A tap does what tapping a track in any list does: `AppGraph.queueSource.value =
QueueSource(QueueSourceKind.TRACKS)` (label check: if "Playing from Songs" reads wrong, add a STATISTICS kind with a
`fallbackLabelRes`; an older build restores an unknown kind as no source, acceptable) and
`AppGraph.playback.play(rows, index)` — so in SMART mode SMART continues from that track, in ON the rows are
shuffled, in OFF they play in order. Implemented inside StatsScreen.kt (`rememberCoroutineScope`); identical on the
main page and in Settings (no `playable` flag). Rows: `Role.Button`, 48 dp, description "Play" + title.

## Loading, memory, threading
- **One snapshot producer** per history revision: a memoised `Deferred` with the raw events and the normalised
  arrays, shared by both effects (never two `allEvents()` copies).
- **Section 2** effect: keyed by (selection, revision), as today, awaits the snapshot.
- **Sections 1+3** effect: keyed by (revision, tracks, active), awaits the same snapshot, own try/catch; on failure
  their cards fall back to T5's text, never the dashboard's error. Both keep showing their last result while
  recomputing (the same `displayed` rule as today), so a new listen never swaps cards to locked and back.
- Per-track state through one `HashMap<TrackId, Int>`; `IntArray`/`FloatArray` per track; steps in a `LongArray`
  sized by the counted pick steps, packed `(from shl 32) or to`, sorted in place, run-length counted; session totals
  in primitive arrays. Cancellation checks every 1,024 events. ≈ 2–3 MB transient for 200k events. No OOM-catching
  claim (Kotlin/Native cannot catch it); the budget is the guard.

## Strings and checks
~35 keys × 18 bundles, label–value forms (no sentences with track names), plurals for every count (and fix the
existing `stats_days_ago`, which is a plain string), literal `%`, `values-in` = `values-id`. Host tests for every
definition (dedup, overlap, fragment merge incl. completed-then-restart not merged, burn-in, pick per origin case,
legacy logs, SMART-only legacy log → T3 absent and T1 SMART repetition not a favourite, recording gap, variety rule,
thresholds, cancellation, empty period with non-empty log). `testAndroidHostTest`, `compileKotlinIosSimulatorArm64`,
lint, R8 release APK: dexdump register count of the `App()` lambda before/after plus launch smoke; read-only
emulator with a fresh and an old log at default and 150 % font; update `docs/statistics-validation.md`.

## Deferred
- **Comparison with typical listeners.** The k2a1 population figure uses another estimator (sampled seeds, 30-min
  start-to-start sessions, first 10 collapsed pairs, scrobbles incl. radio) and is not comparable with T3; its
  slider targets are shrunk-posterior quantiles. Shipping one needs a new offline ListenBrainz (CC0) aggregate
  measured with T3's exact definition, ≥ 10 listeners per number.
- Opening artist/album/genre pages from Stats rows (navigation wiring).
- Albums heard start to finish (needs track numbers, not in `TrackDescriptor`).
- *Learn from my habits* footer — once the setting exists.
- History-merge dedup at the store (the normalisation pass already hides it on this page).

## Review log

**Round 1 (3 fresh reviewers: listener value, statistical honesty, UX/feasibility) — all REVISE.**
Blockers: legacy SMART steps counted as the user's habits; ACT-R `t^-0.5` from now makes the last finished track #1
and contradicts Slipping away; the population strip compares different estimators; no tap route without new `App()`
lambdas; two contradictory "new vs repeat" definitions. Majors: 13 cards and mixed filter scopes; daypart chips
duplicating the hour chart; sentence templates with track names; no date formatter; untrue OOM promise; resume
fragments and repeat-one; fixed-order skips as sequences; paused recording; no burn-in; album listeners.
→ v2: sections, merged cards, own-choice rule, half-life weight, population deferred, in-screen taps.

**Round 2 (3 fresh reviewers) — all REVISE, no blockers.**
Listener: lists repeat each other and collapse to one album; "added recently" wrong after import/restore; sequences
permanently locked for album and SMART listeners; story back-loaded; missing "on this day", genres, human-units total.
Honesty: fragment rule missed the real resume path (gap = whole pause) and could merge a restart after completion;
overlap rule could never fire after capping; N2 counts contradicted the bar and the hero; discoveries without
burn-in; "completed" let SMART repetition make favourites; SKIP-into-other-parent is not a choice (parent is per
queue); empty artist keys matched; B2 weight without window.
UX: SMART/ON modes rewrite tapped lists; artist/album rows play here but open pages elsewhere; two effects without a
shared snapshot; an empty period hid the all-time section; locked-row clutter; no segmented component; missing
plurals; U+2192 does not mirror; dexdump register check.
→ v3: three sections opening on "You lately", variety rule, track-only taps with the app's normal semantics, pick =
USER_PICK, corrected normalisation order and fragment rule, first-play definitions, shared snapshot producer, T3
hidden instead of locked, T5 aggregate locked card, B5 recently-added dropped, On this day / Genres / days-of-music
added.
