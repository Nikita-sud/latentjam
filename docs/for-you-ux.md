# For You — design spec

Research-backed design for LatentJam's For You surface. Grounded in three things: published
evidence on recommendation UIs, a survey of what local/self-hosted players actually ship, and the
lessons already paid for in the legacy LatentJam implementation.

---

## 1. The reframe

A streaming recommender's job is to rank a 100-million-item catalogue under popularity bias. We do
not have that problem. **Owning 850 files is already a strong preference signal** — the user filtered
the world down to this set themselves.

So For You is not *discovery*. It is **rediscovery and decision support**: surfacing what they own
but forgot, and cutting the friction of "what do I put on right now".

Two consequences:

- **Choice overload from catalogue size is not our failure mode.** Ours is **collapse** — a
  recommender that keeps returning the same 50 tracks — and **repetition**.
- **A For You page that is merely as good as browsing the Albums tab is a net loss.** Browsing is
  fast and reliable here. This page has to show something the user could not have scrolled to.

For calibration: a radio station sustains variety on a 250–500 song rotation. 850 tracks is plenty.
Any collapse is self-inflicted by the ranker.

## 2. The architectural split

The predictor was deliberately made **objective** — "what goes together" — not personalised, and the
measurements back that: the personalised model scored *worse* on the playlist target (r@1 0.105 vs
0.158). A For You page is personal by definition. These do not conflict; they divide:

- **Personal signal chooses the seed** — what you neglected, what you played to death last winter,
  what you added and never opened. Mostly counting, no model needed.
- **The objective engine builds the journey** — once a seed exists, SMART sequences it.

This keeps the model doing the one thing it was measured to be good at, and puts the personal part
where it is cheap and legible.

## 3. Evidence that should shape the layout

| Finding | Source | Consequence |
|---|---|---|
| Leftmost item in a row is examined **90.82%** of the time; its **title only 25.77%** | Eye-tracking, N=87 | Row **labels are not load-bearing — art is**. Position 1 of each row effectively *is* the row. |
| Decision window is **60–90s**, ~10–20 items reviewed | Netflix, ACM TMIS 2015 | The **first screen must make a play possible without scrolling**. |
| Small sets ≥ large sets: a high-quality top-20 raised choice difficulty enough to cancel its extra appeal | Bollen et al., RecSys 2010 | **~5–8 items per row**, not 20. |
| Opinion forms after **2–3 plays**, not one | ISMIR 2020 | **Do not reshuffle every visit.** Repeated exposure is what converts a suggestion into a play. |
| Vertical scanning ~2× more likely than upward; row-skip ~1:10; strong top-2-row bias | Eye-tracking | **4–6 sections max.** Row order matters disproportionately. |
| Accuracy-optimised rankers let main interests crowd out minority ones | Steck, RecSys 2018 | **Calibrate**: match the output genre mix to the user's actual mix. |
| Whether carousels beat plain lists is **genuinely unsettled** (4 studies, 3 directions) | Frontiers survey 2023 | Carousels are a reasonable default, **not a proven win**. Don't over-invest. |

## 4. Lessons already paid for (legacy LatentJam)

These came out of real use and are more valuable than any paper here.

1. **Prediction above memory.** Recency shelves are the cheapest and least valuable — last, or cut.
2. **Two recency-ranked shelves over the same log are the same shelf.** Dedup is a band-aid;
   different signals are the fix. (Two "Top" rows were deleted for exactly this.)
3. **Never let a generated label and the content under it come from different selections.** The
   "says Phonk but shows Tanin Jazz" bug: claim the genre only when the content supports it, and
   filter the content to match the claim — or weaken the claim.
4. **A constant explanation is decoration.** A static reason line that reads the same every day
   carries zero bits. Per-item, data-derived captions (`13× · 16 Jun`) earn their space.
5. **Respect the unit in which listening happened.** Tracks whose plays all happened inside one
   playlist are not loved as tracks. Suppress them — but always leave an escape hatch, or items
   become permanently unreachable.
6. **Determinism is a UX property.** A section that reshuffles on every visit reads as broken.

## 5. What to build

Four sections. Ordered prediction → memory. Each hides entirely when empty rather than rendering a
header over nothing.

### 5.1 Hero — one confident play

A single large card: cover, a short honest line, one play button. This is what satisfies the 60-second
window and the "one confident tap while walking" case.

Rules: no claim in the copy that the content does not support. Degrade to less text, never to a
hedge. Icon-only play control at fixed size — text buttons in a constrained row clip under
translation and large font scales, which bit the legacy build three separate times.

### 5.2 Worth revisiting

Dormant favourites — the section with the strongest evidence behind it and the least competition
from ordinary browsing.

- Candidates: high completions, **quiet ≥ 60–90 days**. The legacy 30-day threshold is too short —
  30 days is a normal gap in rotation, so the row surfaces things that do not feel absent, which is
  how the shelf loses credibility. Plexamp uses six months plus a 100-play floor.
- Playlists and albums are first-class here: a dormant playlist outranks weakly-loved loose tracks.
  Use a higher completion threshold for parents (a handful of finishes across a big playlist is not
  a habit).
- Caption per card: play count · last-played date. Data-derived, so it varies.
- **Anchor rows on a named entity** where possible (Roon's pattern) — the row then explains itself
  without a separate explanation string.

### 5.3 Found by SMART

Tracks SMART surfaced that you then listened through. `ListenEvent.shuffleMode` has been recorded
since day one and read by nothing. **No other player can show this row**, and it directly answers
"is this thing any good?" — which makes it the best argument for the whole feature.

### 5.4 Worlds

Clusters of the library in semantic-descriptor space — the one section that works with **zero
listening history**, and the only component immune to feedback-loop collapse. It should therefore be
the day-one surface.

Because labels are barely read, the medoid's cover does the work; the name is secondary. Order
worlds by how much the user actually lives in them. Keep clustering deterministic and cached.

## 6. Data reality

From an audit of the current modules:

**Free today** — pure reads of data already collected:
- Continue listening — `recentEvents` + `completed == false`, and `playedMs` gives the resume point
- Worth revisiting — `TrackStats.plays` / `completions` / `lastPlayedAtMs` (all computed, all
  currently unused by the UI)
- Rediscovery (never played) — library minus `stats().keys`
- Found by SMART — `ListenEvent.shuffleMode`

**Modest work:**
- Similar-to-recent — `smartQueue(seed, library, n)` is already public. Ships today against the
  **text** index, which is warm for the whole library at first launch.
- Genre worlds — needs `MetadataRerank.normalizeGenre` / `isHubGenre` made public, or reimplemented.

**Blocked:**
- Time-of-day and day-of-week — the data is in `startedAtMs`, but **there is no local-time
  conversion anywhere in shared code**. Needs kotlinx-datetime or an expect/actual
  `localHourAndDay(epochMs)`. Do **not** use `(ms / 3_600_000) % 24` — wrong for every non-UTC user.
- Embedding worlds — no clustering code exists, and the **audio index is cold** on a fresh install
  (only the Diagnostics dialog fills it). Run over the text index, or wait for background indexing.
- `energy` is never populated by anything, so that chain term is currently inert.

**Two fixes to fold in regardless:**
- `HistorySessionTracker.flush()` is never called — every session's last track is lost.
- Adding a field to `ListenEvent` silently discards all existing history unless the parser learns
  both versions.

## 7. Do not build

- **New releases / release radar** — no catalogue. Album *anniversaries* from file metadata are the
  local equivalent.
- **Social proof** — impossible, and the least persuasive and most privacy-alarming explanation type
  anyway.
- **Popularity-bias correction / long-tail promotion** — meaningless; there is no popularity signal,
  only your own play counts.
- **A heavy negative-feedback UI** — users prefer ignoring content to marking "not interested", and
  skips are weak signals. A skip may mean wrong moment, not dislike. Decay skip penalties hard and
  never let skips alone bury a track: in an 850-track library a few contextual skips can permanently
  remove something the user loves.
- **Wrapped-style stats as a permanent shelf** — retrospective content, not a decision aid.
- **Fifteen configurable rows.** The loudest complaint found in the whole survey was that the most
  configurable home screen in the category shipped a default that read as bloat and cost one user 15
  minutes to dismantle. **A lean default of 3–4 rows that provably have content beats 15 options.**

## 8. Rules that apply across the page

1. **Freeze it.** Snapshot at construction; seed ordering from a day-epoch so the page is stable
   within a day and changes predictably. Refresh is **user-initiated** (swipe-to-refresh).
2. **Deduplicate across sections, not just within them.** At 850 tracks and four sections drawing on
   overlapping pools, the same album will otherwise appear three times.
3. **Exclude what is currently playing or already queued.**
4. **Calibrate the whole page** against the user's own genre distribution.
5. **Per-section empty states, never a global one.** Progressive unlock as thresholds are met.
6. **Instrument coverage.** Track what fraction of the library For You has surfaced over 30/90 days.
   Under ~30% means collapse — and you will not see it from the UI alone.

## 9. Open decisions

- **Where it lives.** A sixth carousel tab, or the landing surface? There is no bottom nav.
- **Favourites.** Several strong patterns (notably "liked tracks you have least recently played",
  which structurally cannot go stale) need a favourites concept that does not exist yet.
- **Time-of-day.** Worth the kotlinx-datetime dependency? It unlocks two sections and the hero's
  best framing.

## Sources

Eye-tracking: arXiv 2504.20792 (SIGIR 2025), arXiv 2507.10135 (IUI 2026), arXiv 2604.21019.
Choice overload: Bollen et al. RecSys 2010; Willemsen et al. UMUAI 2016; Long et al. M&SOM 2025.
Netflix: Gomez-Uribe & Hunt, ACM TMIS 2015. Calibration: Steck, RecSys 2018.
Repeat consumption: ISMIR 2020 pp.633–639; Anderson et al. WWW 2014; arXiv 2210.16226.
Multi-list surveys: Frontiers in Big Data 2023. Explainability: Tsukuda & Goto RecSys 2020;
Torkamaan et al. RecSys 2019. Local players: Symfonium docs/changelogs, Navidrome + Subsonic API,
Finamp, Feishin, Jellyfin, Poweramp, Musicolet.

Discarded as unverifiable: the "80% of hours from recommendations" and "$1bn/year churn" Netflix
figures, and several widely-circulated statistics traceable only to marketing blogs.
