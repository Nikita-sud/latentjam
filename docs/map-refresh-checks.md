# Map refresh — focused checks per piece

Implementation of the plan in `docs/map-cost-research-2026-10-25.md` (research branch
`claude/research-map-cost-91141c`, "Implementation plan", commit 5802b23f). Each piece's check is
frozen here before it runs. Public data only: synthetic `hierarchicalFixture` libraries and FMA
draws from the licence-clean general-stand pool. Never the owner's library.

## Piece 1: layout refresh (frozen 27 October 2026, before the run)

**Code under test:** `LibraryLayoutRefresh.build` / `refresh` at commit 440169ef.
**Harness:** `MapRefreshCheck` (opt-in host test, `core/smart/src/androidHostTest`).

**Data** (none used before):

- synthetic seeds 37 / 41 / 43 at 10,000 tracks;
- FMA draws s404 (5,000), s505 (5,000) and s606 (6,500), made by the research branch's
  `tools/research/map_gate_fma_dump.py --seed`.

**Cases per library.** In every case the "before" map is a full build through
`LibraryLayoutRefresh.build`, and the "after" map is `refresh`.

- uniform additions of 0.5 / 1 / 2 %;
- changed vectors at 0.5 / 1 / 2 %;
- album additions of about 1 % (10–20-track groups from one style);
- for FMA only, a held-out-split addition of 1 %;
- **a 2 % chain:**
  - start at 88 % of the library and add 0.5 % per step for 20 steps;
  - the map turns stale when the change since the last full build exceeds 2 %;
  - a stale map is rebuilt (`build`, warm from the refreshed map) before the next step.

**Rows.** Each must hold in every case on every library; "today" is today's warm full build
(`LibraryLayout.compute` from the before map).

| Row | Pass |
| --- | --- |
| trust@15 | ≥ today − 0.005 |
| recall@15 | ≥ today × 0.97 |
| class@15 (style / FMA top genre) | ≥ today − 0.010 |
| RF (recall@15 of changed tracks) | ≥ today × 0.85 |
| lost neighbours of unchanged tracks (1 − kept15) | ≤ 0.5 × today's |
| p90 move of unchanged tracks | ≤ 0.5 × today's and ≤ 0.02 of the map width |
| refresh decision | every case is refreshed (none declined); 0.5 % and 1 % uniform or changed cases are not marked stale unless a hint fires (hints are counted and reported) |
| neighbour lists | the new state's lists are bit-identical to `BarnesHutTsne.nearestNeighbours` over its reduced rows |
| overlap@15 with a cold build, against a 5-seed noise band | **per case** ≥ the band's minimum − 0.02; **mean over cases** ≥ the band's mean − 0.05 |
| CF of changed tracks, against the same band | **per case** ≥ the band's minimum − 0.02; **mean over cases** ≥ the band's mean − 0.05 |
| chain, at every step that turns stale and at the last step | the waiting map against a cold build of that library: trust ≥ cold − 0.005, recall ≥ cold × 0.97, RF ≥ cold × 0.85; and bit-identical lists at every step |

**The noise band** is five cold builds of the after library with seeds +1, +3, +5, +7 and +9:
- for overlap: each band build's overlap with the default cold build;
- for CF: each band build's CF for the changed tracks.

**Verdict.** Piece 1 passes only if every row passes. A failing row is reported and nothing is
re-gated on this data.
