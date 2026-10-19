# Statistics page — whole-page spec (v5, 19 October 2026)

Status: **design READY after five adversarial review rounds; IMPLEMENTED 19 October 2026** (see `docs/statistics-validation.md`, "Statistics v2"). Branch `claude/stats-habits-55f9ee` (from
`release/0.9.2` at 334a36a9). Review log at the end.

Goal (owner): the page should be *interesting and informative* for an ordinary listener, using what the SMART
research learned about how people listen. Existing cards may go. Everything is computed on the device from the
user's own log and library tags; nothing leaves the device; no population data ships.

## Page at a glance

```
1 · YOU LATELY                          (no chips — always "now")
  L1 On repeat now    5 tracks · bar vs top row · "artist · on 6 days · 12 plays"  tap plays
                      (fallback: artists on repeat, when < 3 tracks qualify)
     Streak 6 days · record 23   (under 2 days: "on 5 of the last 7 days" instead)
2 · YOUR NUMBERS   [ 7 days | 30 days | All time ]   (chips are this section's header)
  N0 Hero            32 h 10 min · +4 h vs previous 30 days        (All time: "≈ 9 days of music")
                     412 plays · 230 tracks · 61 artists
  N1 Activity        daily bars (7/30 d) · All time: monthly bars "Last 12 months" (yearly when log ≥ 2 years)
  N2 New and familiar [■■■■■■■■□□] 78 % familiar · 22 % new to you
                     31 new tracks · 9 new artists        (counts hidden on All time)
                     New artists that stuck: ≤ 3 rows
  N3 When you listen hour bars (listening time) + 4 daypart labels
                     Typical session 38 min · 9 tracks · Longest 3 h 12 min
  N4 Your top        chips [Artists | Albums | Genres | Tracks] · 5 rows · "Show 10"
  N5 How you listen  Finished 64 % · Partly heard 24 % · Skipped 12 %   (of starts)
                     ── Played in SMART mode 41 % (of plays)
3 · OVER TIME                           (no chips — whole log)
  T1 Slipping away   ≤ 5 old favourites · "12 plays · 4 months"                 tap plays
  T2 On this day     what you played a year ago (log > 1 year)                   tap plays
  T3 Your patterns   Picking the same artist again: 27 of 100 picks · X → Y 4/6  (when they exist)
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

1. **Exact duplicates** with the same (trackId, startedAtMs) are dropped, keeping the copy with an origin, else the
   first in stable order (backup merge uses full equality, `LocalBackup.kt:1486`, so near-duplicates survive there).
2. **Overlap** is detected before any capping: two consecutive events that both have `listenedMs`, where the next
   starts more than 15 s before `start + listenedMs` of the previous, are a break (clock jump, two devices merged):
   no step or session crosses it.
3. **Resume fragments.** A v4 event B is merged into the immediately preceding (possibly already merged) event A
   when `B.start == null`, `B.trackId == A.trackId`, no overlap break lies between them, `B.startedAtMs − end(A) ≤ 6 h`
   (end of the merged event so far), and **B began where A stopped**: `B.playedMs − B.listenedMs ≥ A.playedMs − 5 s`
   — the tracker's own resume test (`lastPositionMs >= positionMs`, `HistorySessionTracker.kt:217`; resumed half is
   null-start, lines 230-253, flushed only when backgrounded while paused, `PlaybackHistoryRecorder.kt:64-69`).
   A restart from 0 fails the test whether or not A was completed; a resume after 85 % is merged instead of becoming
   a phantom second completed play. Merged: A's start, origin and shuffle mode; listened = sum; playedMs = max;
   completed = A ∨ B; skipped = ¬completed ∧ listened < 30 s; end = B.startedAtMs + B.listenedMs. Chains merge
   repeatedly. Legacy lines are not merged. Finished/Skipped shares change slightly as a result (documented).
   Documented limits of the position test (`playedMs` is the furthest playhead, `listenedMs` heard time): a backward
   seek inside B can block a real merge (host test); a forward seek inside a restart can wrongly allow one (rare —
   restarts carry a signal); the tracker compares A's *last* position, the spec A's *furthest*.
4. **End** = `startedAtMs + effectiveListenedMs`, capped at the next event's start.
5. **Artist key** = `MetadataRerank.normalizeArtist(first artist)`; an empty key never counts as "same artist" and
   such steps leave numerator and denominator. **Album key** = (album, albumArtist ?: first artist); a null album
   never groups tracks.
6. **Output.** A normalised `List<ListenEvent>` in which unmerged events are the store's own objects and only merged
   events are new copies, plus primitive side arrays per event: track index (`IntArray`), session id (`IntArray`),
   flags (`ByteArray`: overlap break, pick, chosen). `ListeningOverviews.summarize` runs **unchanged** on that list
   (the store already keeps every `ListenEvent` for the process lifetime, `ListeningHistory.kt:126,153`, so a
   columnar port would add memory, not save it). Equivalence test against today's overview on logs with no duplicates
   and no merges, excluding the fields this spec redefines on purpose (N0 tracks/artists with ≥ 1 play, N2 first
   non-skipped play, play-day streaks).
7. Times use the device's current time zone for the whole log (documented limit). Events outside `0..now` are
   ignored everywhere, as today.

Terms:
- **Play** = non-skipped event. Skip < 30 s, completed ≥ 85 %, listened time with legacy fallback — unchanged.
- **Session** = maximal run with gap (next start − previous end) ≤ 30 min and no overlap break, built over the whole
  log before any period filter. A session belongs to the period of its first event. Skip-only sessions are left out
  of session statistics.
- **Step** X→Y = consecutive plays in one session, X ≠ Y.
- **Pick** = a play whose `origin.start == USER_PICK` (taps in lists, search, For You, Stats, Play/Shuffle of a
  collection, external queues with a start index). The tracker clears `smartPlanPosition` for picks
  (`HistorySessionTracker.kt:242,250`). Skips are not picks (a skip rejects X; in shuffle it draws at random).
  Legacy lines are never picks.
- **Chosen play** (for favourites) = a pick, or a completed play that SMART did not plan and that is not a repeat-one
  loop (`smartPlanPosition == null && shuffleMode != "SMART" && start != REPEAT`; the shuffle-mode test also covers
  legacy lines). Repeat-one on transports that only use the near-end wrap heuristic records a null start and still
  counts (documented).
- **Burn-in** for a comparison with the previous window: `logStart ≤ previousStart` (as computed in
  `ListeningOverview.kt:118-120`). Otherwise the existing "first period" text.
- **Variety rule** for track lists in sections 1 and 3: at most 1 track per album key and 2 per artist key, so a
  whole-album listener does not get one album's track list.
- **Track filter.** Counts (N0 tracks, N2 bar and counts) include tracks no longer in the library, as today's totals
  do; rows that play or rank (L1, T1, T2, N4) are filtered to available tracks after eligibility is computed on the
  whole log.
- **Locked vs nothing qualifies.** A card is *locked* only when the log is too young for it (listed in T5 with its
  condition). An old-enough log where nothing qualifies hides the card silently — T5 never claims "after a few plays"
  to someone with 40k plays.

## Section 1 · You lately

### L1 On repeat now (new)
- Weight per track = Σ over local days **today−27 … today** with ≥ 1 play of `0.5^(days ago / 14)` (one count per
  day: loops and fragments cannot inflate it). Ties: more plays, then most recent.
- Eligible: ≥ 3 plays on ≥ 2 days in that window. Variety rule. 5 rows; bar = weight / top weight; subtitle
  "artist · on N days · M plays" (plurals) — the ranked quantity (days) is shown first, so a row can never look
  out of order. No jargon note.
- **Fallback for shuffle-heavy listeners:** when fewer than 3 tracks qualify, the card shows *Artists on repeat* with
  the same weight over artist keys (artist rows not tappable). L1 shows whenever something qualifies, however young
  the log. Nothing qualifies: log younger than 7 days → locked (T5: "On repeat now — after a week of listening");
  older → hidden silently. On a locked fresh log T5 renders in section 1's place.
- Under it, one line: current streak and record, computed on play days after normalisation (today a skip-only day
  counts; changed and documented). When the current streak is under 2 days (weekday listeners), the line reads "on N
  of the last 7 days · record M days" instead.

## Section 2 · Your numbers (chips)

The empty-period state covers this section only (one compact card); sections 1 and 3 still render. The full-page
empty state is only for an empty log. Per-card locked rows exist only here, where chip changes would otherwise make
cards jump. The chip row is **not sticky** (sticky chips over section 3 would recreate the "7 days" misreading).

On a chip change the chip switches at once; the old section-2 cards stay in place, dimmed (alpha 0.4, semantics
cleared) under a thin progress line until the new result arrives — no spinner collapsing the section, no early
`return` from the list except for an empty log. Section-2 items have stable keys (`n0`…`n5`).

### N0 Hero (changed)
Listening time, comparison with the previous window after burn-in, plays / tracks / artists. *Tracks* and
*artists* count only items with ≥ 1 play (today they include skip-only tracks while sitting next to *Plays*). All
time adds "≈ N days of music" (plural, from listening time).

### N1 Activity (changed)
7 d and 30 d: today's DailyBars. All time: monthly bars titled "Last 12 months"; yearly bars when the log spans ≥ 2
years; daily bars when it spans < 2 months. DailyBars becomes generic over a bucket type `(key, plays, playedMs)` with
a label lambda; endpoint labels only, the left one from the first bucket actually drawn ("3 months ago" … "This
month"), plural for the selected bar; selection keyed on the bucket key. Buckets before log start are not drawn.

### N2 New and familiar (new, replaces "New to you" and "Repeat plays")
- *New to you* = a play that is its track's first non-skipped event in the log; *familiar* = every other play.
  Two-tone bar. Each new track contributes exactly one new play, so the bar and the counts agree and new tracks ≤
  hero tracks.
- *N new tracks / N new artists* = items whose first play falls in the period; hidden on All time (would repeat the
  hero).
- *New artists that stuck* = artist keys whose first play is in the period **and ≥ 30 days after log start**, with
  ≥ 5 plays on ≥ 3 days; ≤ 3 rows (not tappable, see Taps). On All time both rules apply, with the window "last 3
  months" stated in the sub-title.
- Trend vs previous window only after burn-in. Locked under 20 plays. Footnote when the log starts inside the
  period: "LatentJam has listened along for 12 days" (plural relative days).
- Card note: "New to you = first played in LatentJam" (the library may predate the app).

### N3 When you listen (changed)
- Hour bars now in listening time per start hour (skipped listening included, like the hero total); four daypart
  labels with shares under the axis (Night 0–6, Morning 6–12, Afternoon 12–18, Evening 18–24). TalkBack: one
  description from a two-argument template (daypart, percent).
- *Typical session* = median of Σ listened time per session, and median plays per session; *Longest* = max Σ
  listened time, relative day in the subtitle. A commuter's dayparts are this card's point; it needs no burn-in.
- Locked under 3 sessions.

### N4 Your top (changed)
Smaller `AppChoiceChip` row inside the card (visibly subordinate to the period chips): `Artists | Albums | Genres |
Tracks`, selected chip and "Show 10" in `rememberSaveable`, 5 rows. Artists and Tracks always show; Albums (album
key) and Genres (first genre tag) only when ≥ 60 % of the period's plays carry that tag. If the saved chip is absent
for a period, Artists shows and the saved choice is kept. Counts are plays. Only Tracks rows are tappable. Known
overlap: Tracks on 30 days resembles L1 (default chip is Artists).

### N5 How you listen (changed)
`ShareMeter`s: Finished, Partly heard (the rest), Skipped — definitions unchanged, after normalisation — and
*Played in SMART mode* (moved here; definition unchanged: SMART was on when the listen started, picks included).
Partly heard = 100 % − the two others after rounding, so the three always add up. *Played in SMART mode* sits
below a divider with its base stated ("of plays"), so it is not read as a fourth slice of starts.
Visible note: "Finished = heard to 85 %, skipped = left in the first 30 s.".

## Section 3 · Over time (whole log)

### T1 Slipping away (new; the page's first "play it again" action)
- ≥ 5 chosen plays on ≥ 3 distinct days ever, **no start** (skips included) in the last 60 days, ranked by chosen
  plays; variety rule; ≤ 5; subtitle shows chosen plays. Old enough but nothing qualifies → hidden silently (a
  SMART-only listener may then see section 3 as T4 alone; accepted — SMART repetition must not be called a favourite).
  A tapped row that leaves the list shrinks the card; the scroll anchor holds unless T1 is the first visible item.
- Recording-gap guard: only if the log has events on ≥ 10 of the last 60 days (Privacy can pause recording).
  Subtitle: "12 plays · 4 months" (plurals); card note "not played in LatentJam since then". A tapped row leaves the
  list once its listen is recorded (intended; the list must not jump the scroll position).
- Locked (→ T5) until the log spans ≥ 90 days.

### T2 On this day (new)
- When `logStart ≤ today − 1 year − 3 days`: the tracks with the most plays on the dates within ±3 days of today one
  year ago (≥ 2 plays in that week, variety rule, ≤ 3 rows), titled "A year ago" (no date formatter needed). Hidden if
  nothing qualifies; not listed in T5 (a year is too far to promise).

### T3 Your patterns (new; hidden entirely until it has content, not locked)
- *Picking the same artist again* ("27 of 100 picks") = steps whose Y is a pick and X, Y have the same non-empty artist key, over all
  steps whose Y is a pick. Shown with ≥ 30 such steps. Card note: "Includes starting an artist's page."
- *Sequences* X→Y over steps whose Y is a pick; denominator = X's steps whose Y is a pick. Rows when ≥ 3 occurrences
  from ≥ 3 sessions (steps packed with their session id and sorted as (pair, session)) and ≥ 40 %; ≤ 3 rows: two thumbs, `X` / `→ Y` on two ellipsised lines with
  `Icons.AutoMirrored.Rounded.ArrowForward` (decorative), trailing "4/6". Not tappable in v3.
- Pick-based and all-time, so it does not flicker with chips; for logs without v4 picks the card simply is not there.

### T4 Library (changed)
*Played at least once* = available tracks with ≥ 1 play, shown as "312 of 5,000" without a score-like meter colour. *Decades* strip from `originalYear ?: year` of all plays,
shown when ≥ 60 % of plays have a year.

### T5 More appears as you listen
One card listing the locked parts of sections 1 and 3 with their unlock condition ("On repeat now — after a week
of listening", "Slipping away — after three months of listening"). Absent when nothing is locked.

### History note (kept)
Adds the documented limits: time zone, restores dropping unresolved tracks, files re-identified after a move, an
artist known only from deleted tracks can show as new.

## Taps
Only **track rows** play (L1, T1, T2, N4 Tracks). Artist, album, genre, discovery and sequence rows are not
interactive in v3: elsewhere in the app such rows open their page, and opening a page from here needs navigation
wiring through `App()` — a follow-up. A tap does what tapping a track in any list does: `AppGraph.queueSource.value = null` — as `App.kt:2201-2205` does
for hand-picked sets with no single honest name (`TRACKS` would make the source chip jump to the whole library; a new
kind would edit `App()`'s exhaustive `when`) — and `AppGraph.playback.play(rows, index)` — so in SMART mode SMART continues from that track, in ON the rows are
shuffled, in OFF they play in order. Implemented inside StatsScreen.kt (`rememberCoroutineScope`); identical on the
main page and in Settings (no `playable` flag). Rows: `Role.Button`, 48 dp, merged description title + count, `onClickLabel` "Play". `ListeningStatsSettings`'s
signature and `App()` stay untouched.

## Loading, memory, threading
- **One snapshot producer** per history revision: a `Deferred<Result<Snapshot>>` launched from a
  `rememberCoroutineScope` owned by the screen (not inside either effect) with
  one `SupervisorJob(parent)` per screen scope (reused, not one per revision) `+ Dispatchers.Default`, keyed by
  (history, revision); a revision change cancels the superseded in-flight producer. On the main page the whole
  composable is re-keyed by `replacedHistoryEpoch` (`App.kt:2559`); the Settings entry is not, as today. The producer never throws (failures come back as `Result.failure`), so a
  failure cannot cancel the scope. `active` turning false cancels only an in-flight producer; a finished snapshot
  stays cached across tab-aways. An awaiter that finds the producer cancelled re-creates it once. One Retry re-creates
  the snapshot and restarts both effects (a single Retry card even if both sections failed). Never two `allEvents()`
  copies.
- **Section 2** effect: keyed by (selection, revision), as today, awaits the snapshot.
- **Sections 1+3** effect: keyed by (revision, tracks, active, local day — a `produceState` that delays until the next
  local midnight, so "today" in L1 and the streak roll over while the page stays open), awaits the same snapshot, own
  try/catch; on failure see the last bullet (neutral note), never the dashboard's error. Both keep showing their last result while
  recomputing (the same `displayed` rule as today), so a new listen never swaps cards to locked and back.
- Per-track state through one `HashMap<TrackId, Int>`; `IntArray`/`FloatArray` per track; steps in a `LongArray`
  sized by the counted pick steps, packed `(from shl 32) or to`, sorted in place, run-length counted; session totals
  in primitive arrays. Cancellation checks every 1,024 events. Budget for 200k events, on top of the ~40 MB of
  `ListenEvent`s the history store already holds: persistent ≈ 3–4 MB while the page is open (normalised list
  references 1.6 MB + side arrays ≈ 1.8 MB + merged copies), transient ≈ 2–3 MB per pass. No OOM-catching
  claim (Kotlin/Native cannot catch it); the budget is the guard.
- Section 1+3 failure shows a neutral "Couldn't load" note with Retry, never T5's "appears as you listen" text.

## Strings and checks
~35 keys × 18 bundles, label–value forms (no sentences with track names), plurals for every count (and fix the
existing `stats_days_ago`, which is a plain string), literal `%`, `values-in` = `values-id`. Host tests for every
definition (dedup, overlap, fragment merge incl. completed-then-restart not merged, burn-in, pick per origin case,
legacy logs, SMART-only legacy log → T3 absent and T1 SMART repetition not a favourite, recording gap, variety rule,
thresholds, cancellation, empty period with non-empty log, completed-then-resumed merged, restart-from-0 not merged,
merge chains and gap from the merged end, repeat-one not a chosen play, big SMART-only log shows no false lock text,
old vs new overview equivalence (with the stated exclusions), backward seek inside a resumed half, snapshot race
(cancel one awaiter, the other completes), N4 chip-absent fallback). Compose UI tests: producer failure → Retry →
success; dimmed section 2 while section 3 stays rendered. RTL run (he/ar: bars, N2 bar, "4/6"), TalkBack pass,
one iOS runtime look. `testAndroidHostTest`, `compileKotlinIosSimulatorArm64`,
lint, R8 release APK: dexdump register count of the `App()` lambda before/after plus launch smoke; read-only
emulator with a fresh and an old log at default and 150 % font; update `docs/statistics-validation.md`.

## Deferred
- **Comparison with typical listeners.** The k2a1 population figure uses another estimator (sampled seeds, 30-min
  start-to-start sessions, first 10 collapsed pairs, scrobbles incl. radio) and is not comparable with T3; its
  slider targets are shrunk-posterior quantiles. Shipping one needs a new offline ListenBrainz (CC0) aggregate
  measured with T3's exact definition, ≥ 10 listeners per number.
- Opening artist/album/genre pages from Stats rows (navigation wiring).
- Albums heard start to finish (`trackNumber`/`discNumber` exist in `TrackDescriptor`; a good next card for album
  listeners, left out to keep v1 of this page bounded).
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

**Round 3 (3 fresh reviewers) — all REVISE, no blockers; majors were local.**
Listener: shuffle-heavy 3-year log could open on an empty L1 with a false "after a few plays" lock; N5's Finished/
Skipped gap and bare "SMART" unexplained. Honesty: `!A.completed` turned "completed, paused, resumed" into a phantom
second play (fix: tracker's own resume-position test); chain gaps, dedup tie-break, repeat-one as chosen play, "no
start" for T1, T2 coverage, session/skip-only rules, T3 session counting, SMART label, streak on play days, false
claim about track numbers. UX: snapshot `Deferred` ownership/cancellation/retry; `TRACKS` source jumps to the
library (→ null source, no `App()` edit); per-section loading without collapsing; persistent memory of the raw list
(→ columnar snapshot, raw copy dropped); chip row not sticky; N4 chip rules; rows filtered to available tracks.
→ v4: all of the above applied.

**Round 4 (3 fresh reviewers).** Listener: **READY** (polish folded in: L1 subtitle shows the ranked quantity, plain
T3 wording with an anchor, weekday-friendly streak line, SMART meter separated). Honesty: REVISE on one false memory
claim (the store already retains every event; `allEvents()` is a shallow copy) — all definitions verified; seek edge
cases of the resume test documented. UX: REVISE on the same memory claim (→ normalised list reusing the store's
objects, `summarize` unchanged) and on the producer scope (a failed `async` would cancel a plain-`Job` scope → never
throws, `SupervisorJob` + `Dispatchers.Default`, single Retry, day key, Compose UI tests).
→ v5: all applied. Both REVISE verdicts named these as their only majors and called the spec READY once fixed.

**Round 5 (1 fresh confirmation reviewer).** Both round-4 majors verified fixed against the code (`summarize` reads
only fields a merged copy carries; the memory budget is right). One contradiction left (failure text for sections 1+3)
→ removed; minors applied (one reused `SupervisorJob`, superseded producer cancelled, midnight tick via
`produceState`, precise re-keying note, L1 lock condition aligned with T5). Reviewer: READY once these were applied.
**Final: READY.**
