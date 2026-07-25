# SMART session recency — design spec

Stop a fresh SMART queue from re-serving tracks the listener heard minutes ago in the same
sitting. Written 2026-07-25, after the semantics-aware chain (`scoring-semtext-v1`) shipped and
measurably increased in-session repetition.

---

## 1. The problem

Pick a track, listen to the queue, then pick another seed. The new queue hands back what you just
heard.

Replaying eight real mid-session seed picks from one 78-track listening session, against the
current build and the listener's own 875-track library:

| seed chosen mid-session        | heard so far | re-served by the new queue |
| ------------------------------ | -----------: | -------------------------: |
| А на море белый песок          |           43 |                    11 / 20 |
| Прованс                        |           38 |                     8 / 20 |
| Мальчик хочет в Тамбов         |           53 |                     7 / 20 |
| Тантум Верде Форте             |           45 |                     4 / 20 |
| Another One Bites the Dust     |           40 |                     0 / 20 |
| Ход конём                      |           51 |                     0 / 20 |
| Moldovenii s-au născut!        |           48 |                     1 / 20 |
| Отряд не заметил потери бойца  |           56 |                     1 / 20 |

20% across the set, but bimodal: near zero when the seed jumps clusters, and **more than half the
queue** when it stays inside the listener's dense core. Tracks played **three minutes earlier**
still get picked.

## 2. Why the existing mechanism does not cover it

`RecencyRerank` already exists and already fires. It is a step function on the age of a track's
last play, folded into the chain score in log space:

| age of last play | multiplier | score effect |
| ---------------- | ---------: | -----------: |
| ≤ 30 min         |       0.10 |        −2.30 |
| < 1 day          |       0.25 |        −1.39 |
| < 1 week         |       0.55 |        −0.60 |
| < 1 month        |       0.80 |        −0.22 |
| < 90 days        |       0.92 |        −0.08 |
| older / unseen   |       1.00 |         0.00 |

The three-minute-old track above was scored at −2.30, the harshest penalty the table can produce,
and still won. That is not a tuning error — it is a scale mismatch. The semantic gravity added in
`6db50b4` contributes `2.0·z_seed + 1.0·z_prev` with `z` clipped at ±3, so up to ±9 points, and it
measures **+3.60 on average** across 40 seeds. A fixed −2.30 cannot reliably outrank it.

The same measurement, before and after that commit, over 40 seeds × 20 hops:

| | pre-semantic build | current build |
| --- | ---: | ---: |
| picks played within the last week | 30.6% | **47.5%** |
| such picks per 20-track queue | 6.1 | **9.5** (max 20) |

Repetition rose *because* coherence improved. The tracks nearest the seed's cluster are exactly the
ones a listener working through that cluster just played. Any fix built from a larger constant
re-enters the same arms race the next time a score term gets stronger.

There is a second, unrelated defect worth recording but **not** fixing here: the `< 1 month` and
`< 90 days` tiers never fire, because the app's history only reaches back to its own first launch
(6.9 days at time of writing). The listener's long history lives in the predecessor app's database
and has never been migrated. That is a data-migration question.

## 3. The change

Tracks played during the **current listening session** become ineligible for the queue, rather than
merely penalised. Everything outside the session keeps today's `RecencyRerank` treatment unchanged.

The reasoning for exclusion over a bigger penalty: "I just heard this" is a hard fact, not a
preference to be weighed against acoustic similarity. Encoding it as a score term means re-tuning
it every time another term changes magnitude. Encoding it as eligibility means it cannot be
outvoted.

### 3.1 What counts as the session

The boundary `SmartChain.prepareContext` already computes: walk back from the newest history event
while consecutive gaps are at most `SESSION_GAP_MS` (30 minutes); everything from there on is the
session.

Reusing it rather than introducing a parallel definition is deliberate. That same boundary feeds
`session_features` into the state encoder, so the model's notion of "this session" and the queue's
notion cannot drift apart as either evolves.

### 3.2 What is excluded

Every snapshot row with a history event inside the session, **regardless of `playedFraction`**. A
track skipped two minutes ago is at least as unwelcome as one played to the end, and the listener
has seen it either way.

The seed itself needs no special handling: `buildPool` and `order` already exclude the seed row,
and the chain never emits it.

### 3.3 Where it is applied

One place: **`buildPool`**, in all three of its ranking channels. The pool has 100 fixed slots and
session repeats currently consume up to 40 of them, starving the scorer of genuine alternatives
before scoring begins; removing them at pool construction is both the fix and the only enforcement
point needed.

The per-hop `isEligible` filter is deliberately left alone. Every channel that can put a row in the
pool already filters, so a second check there would be unreachable code that each future reader has
to reason about.

The set is computed once per build, next to `prepareContext`, and respects the existing
`eligibleRows` mask.

### 3.4 Starvation guard

A long session in a small library could leave too few candidates to fill the queue.

Count the rows that remain selectable once session rows are removed — that is, rows where
`eligibleRows` is already true, excluding the seed and the session. If that count is below
`length`, re-admit session rows **oldest-played first** until it reaches `length`, then build the
pool normally. The queue degrades to today's behaviour instead of returning a stub.

The count is taken over the whole library rather than the assembled pool, so the decision is made
once, before pool construction, and cannot oscillate as slots fill.

## 4. What this deliberately does not do

**It does not guard against cluster exhaustion.** If the listener has heard 15 of their 20
Russian-pop tracks and seeds a sixteenth, the correct queue is the five they have not heard,
followed by a drift outward — and `Reanchor` already handles that drift, re-anchoring on the
chain's own centroid once the seed's niche is spent. Suppressing exclusion to keep the queue inside
an exhausted cluster would reintroduce exactly the repetition this spec removes.

**It does not touch the beyond-session curve.** Days-old tracks keep their current multipliers.

**It does not add persistence.** The session is derived from the history the caller already passes.

## 5. Parity

`SmartChainParityTest` calls `build()` without `historyEvents`, exercising the cold-start path.
Empty history yields an empty session, which excludes nothing, so recorded fixtures stay
byte-identical and the negative control still works.

This is the same discipline `Reanchor` follows: a chain that never reaches the new condition is
indistinguishable from one built without the feature.

## 6. Testing

Unit tests in `core/smart/src/commonTest`:

- **Boundary.** The session is defined by *consecutive* gaps, not by distance from the newest
  event. Given events at −90, −60 and −5 minutes, all three are one session and all are excluded,
  because no adjacent pair is more than 30 minutes apart. Insert a 40-minute gap and everything
  before it falls out of the session while everything after it stays in.
- **Skips count.** A track skipped at 2% is excluded exactly like one completed.
- **Empty history is a no-op.** A chain built with no history is byte-identical to one built before
  this change. This is the parity guarantee expressed as a unit test rather than left to the
  fixture harness.
- **Starvation fallback.** With a library smaller than the session, the queue still reaches
  `length`, and re-admitted tracks appear oldest-played first.
- **Pool slots.** Session rows do not occupy pool positions while non-session candidates remain.

## 7. Acceptance

Measured with the offline replay harness against the real library, before and after:

- The eight replayed session picks in §1 drop to **0 re-served tracks**, except where the
  starvation guard demonstrably fires.
- Coherence is preserved: across the 40-seed benchmark, seed-language match stays near **0.91** and
  seed-genre-family match near **0.73**.

The second criterion is the one that can fail. Forcing the chain off just-played tracks pushes it
away from the seed's cluster, which is where the coherence fix does its work. If those numbers drop
materially, the guard in §3.4 is wrong and the design needs revisiting rather than shipping.

## 8. Alternatives considered

**Steepen the `RecencyRerank` curve** (≤30 min → 0.02, < 1 day → 0.08). One line, minimal blast
radius. Rejected: a fixed constant is exactly what failed here, so this buys time rather than a
fix, and a uniformly harsher week over-suppresses the five-day-old track the listener probably does
want back.

**Pool-relative recency**, penalising by rank within the pool the way `SemanticZ` does, so the term
self-balances against whatever the semantic weight becomes. Genuinely more principled and worth
revisiting if a third term later disturbs the balance. Rejected for now as more machinery than
"do not replay what I just heard" requires, and it would still be a penalty rather than a fact.

**Reuse `SmartExclusions`.** It is the listener's own permanent block-list, user-visible and
persisted; overloading it with automatic, transient state would confuse both.

## 9. Measured result

Measured 2026-07-25 with the offline NumPy/ONNX replay harness
(`~/Documents/LJ/smart-diag-2026-07-25/`) against the real 875-track library, after mirroring
the rule into `smart_chain.py` (`Chain._session_exclusions`, threaded through `_build_pool` and
`_stable_order`).

**§1/§7 in-session repetition** (`session_repeat.py`, the same eight replayed mid-session picks):

| | before | after |
| --- | ---: | ---: |
| re-served tracks | 32/160 = 20% | **0/160 = 0%** |

Every one of the eight per-seed breakdowns dropped to 0/20; none triggered the §3.4 starvation
guard (the 875-track library was never small relative to the session length at these points).

**§7 coherence floor** (`benchmark.py 40`, head variant, 40 seeds × 20-track queues):

| | before | after | floor |
| --- | ---: | ---: | ---: |
| seed-language match | 0.910 | **0.910** | ≥ 0.85 |
| seed-genre-family match | 0.731 | **0.731** | ≥ 0.68 |

Both figures are bit-identical to the pre-change baseline — not merely close. A direct check of
`Chain._session_exclusions` confirms the exclusion is genuinely live for this run (it removed
28, 1, 10 and 10 pool candidates respectively for the four screenshot seeds with real session
history, and 1 candidate for each of the 36 randomly sampled seeds, which only carry a single
adjacent history event before the fixed build timestamp). Comparing pool contents directly shows
these exclusions do swap ~10 rows out of the 100-slot pool for the real-session seeds. The
resulting top-20 chains were unaffected, i.e. the specific tracks removed from pool eligibility
were not the ones landing in the final queue in this sample — `RecencyRerank`'s existing penalty
had already pushed them out of the top 20 before this change. The `session_repeat.py` scenario
(a much longer, denser real session, 38–56 tracks already heard) is the one where the pool-level
exclusion actually changes what ships, which is exactly what its 20%→0% result shows.
