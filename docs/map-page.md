# Map — design spec

The library as a place: one screen showing every owned track positioned by what the SMART
engine believes about it, with listening history painted on top as colour.

Measured on the real 873-track library pulled from `R5CWC1VJ12R` on 2026-07-25. Harness and
raw numbers: `~/Documents/LJ/map-layout-2026-07-25/`.

---

## 1. The reframe

[for-you-ux.md](for-you-ux.md) argues that For You is rediscovery and decision support, and
that a row earns its place only by showing something you could not have reached by browsing.
Map is held to the same bar, and it clears it in a way no list can: it is the only surface
that shows the library's **shape** — where you actually live, and where you never go.

The page began as "tracks visualised, or interesting statistics". Those are two different
products and only one of them clears the bar:

- A **statistics dashboard** — top artists, total minutes, most-played — is reachable by
  sorting the Artists tab. It is the Tracks tab with extra steps, and it repeats the mistake
  already paid for once: *two recency-ranked shelves over the same log are the same shelf.*
- Every statistic that **is** worth showing turns out to be **spatial**. Dark matter is a
  region. Skips are a region. Concentration is density. Calibration is a mix over regions.

So there is no dashboard. The statistics are **lenses on the map**, and because they are on a
map, every fact is tappable: "here is something true about you" and "play it" become the same
gesture.

Three consequences worth stating, because they are the reason for the shape:

- **It degrades gracefully.** Day one it is a working atlas. Lenses light up as history
  accrues. A separate stats page would have to render an empty state instead.
- **It costs one destination, not two.**
- **It cannot ship as a page whose honest description is "numbers you will read twice".**

## 2. What the library actually looks like

From the real listening log, and these are the headline numbers the lenses show:

| | |
|---|---|
| Tracks with both an audio and a text vector | 873 |
| **Never played** | **435 (50%)** |
| Tracks accounting for half of all 700 plays | **114 (13%)** |
| Regions (k-means, k=8) | 8 |
| Worst region | Film score & soundtrack — 62% never played, 65% of starts skipped |

That last row is the proof the page is worth building: nothing in the app today can tell you
that an eighth of your library is both unplayed and actively disliked.

## 3. Layout — what "near" means on screen

The map projects the **fused space the app already clusters in**: `LibraryVectorFusion`,
concat(unit(audio)·√0.25, unit(metadata)·√0.75), 1344-d, centered. Not a new space — the same
one `LibraryWorlds` groups, so the picture and the regions agree by construction.

Five families were built and scored against ground truth **the user made by hand** — 15
curated playlists and 25 artists with ≥4 tracks — asking: of a track's 15 nearest *on-screen*
neighbours, how many come from the same playlist or artist. Plus trustworthiness (fidelity to
the 1344-d neighbourhoods, all 873 tracks) and stability (neighbourhood retention after 5% of
the library is dropped and the layout recomputed).

| layout | playlist@15 | artist@15 | trust | stability | paired vs t-SNE |
|---|---|---|---|---|---|
| **t-SNE, perplexity 20** | **0.368** | 0.458 | **0.965** | 81.9% | — |
| kNN force layout | 0.355 | 0.448 | 0.916 | 82.3% | indistinguishable |
| UMAP n30 d0.0 | 0.354 | 0.437 | 0.961 | 72.6% | **worse** (CI excludes 0) |
| PaCMAP n15 | 0.342 | 0.452 | 0.942 | 70.2% | **worse** |
| PCA | 0.272 | 0.386 | 0.817 | 95.4% | **worse** |
| *full 1344-d space (ceiling)* | *0.384* | *0.505* | — | — | — |

**Decision: t-SNE, perplexity 20, cosine metric, PCA-50 pre-reduction, seeded.**

The reasoning, stated honestly because the first round got it wrong:

- Absolute 95% CIs on playlist@15 are ~[0.20, 0.54] — every candidate sits inside every
  other's interval. That is a sample-size artefact of 15 playlists, not a tie. The **paired**
  bootstrap, which resamples the same groups for both layouts so the shared between-playlist
  variance cancels, is what has power.
- Paired, **UMAP and PaCMAP are really worse**, and PCA is far worse. Widening the search
  ruled options out rather than finding a winner.
- Paired, **the force layout ties t-SNE**, and it is equally stable. It was never a quality
  compromise; the apparent 4% gap was noise.
- t-SNE is chosen on the one axis where a real, well-powered difference survives:
  **trustworthiness 0.965 vs 0.916**. That number is the promise the page makes — that the
  neighbourhood you see is the neighbourhood SMART will queue from.
- PCA's 95.4% stability is a trap. It is stable because it is a fixed linear projection:
  stable and wrong.

**Rejected outright:**

- **Named axes** (era × something). Worst but one on every metric, and dead on data anyway —
  year is present on 152 of 873 tracks (17%).
- **Cluster-then-pack.** Best silhouette of any candidate (0.560 — perfect discs) and the
  worst fidelity (playlist@15 0.196 against a 0.393 ceiling on that run — roughly half). Both
  rejected options were scored in the first round, before the sweep narrowed the field to
  neighbour embeddings, so their numbers come from `refine.py` rather than `sweep.py`; the
  gap is far too large for the difference in scoring to matter. It would have looked best in a
  screenshot while discarding half the real structure. Rock and pop sit *mixed inside* its
  tidy discs. Recorded here so nobody re-proposes it.

### 3.1 Stability

At ~82% retention, a fifth of on-screen neighbourhoods shift when the library moves by 5%.
For a surface people learn the shape of, that is too much churn, so recomputation is not cold:

- Initialise from the **previous layout's positions** for tracks that still exist; new tracks
  start at the centroid of their k nearest existing neighbours.
- **Procrustes-align** the result to the previous layout (rotation + reflection + scale) before
  storing, so the map keeps its orientation. Without this, a rerun can mirror the whole picture
  and every learned location is wrong.

Measured separately, the two mechanisms do not contribute equally. With the Procrustes step
running — the normal case, whenever a recompute keeps at least 3 tracks in common with the
previous layout — warm beats cold in the large majority of sampled library/dimension
configurations, consistently on both JVM and iOS. The t-SNE warm start alone, with alignment
withheld, is a real but weak and noisy effect: it wins on the median configuration on both
platforms but not on every single one, and cross-platform floating-point differences swing
individual configurations enough that no single fixture's ratio is a sound thing to pin a test
threshold to. The anti-churn guarantee above should be read as coming primarily from Procrustes
alignment, with the warm start as a modest assist rather than the load-bearing piece.

## 4. Architecture

```
:core:smart
  cluster/LibraryLayout.kt        NEW  fused space -> List<LayoutPoint>(trackId, x, y)
  cluster/LayoutStore.kt          NEW  map_layout.bin persistence + invalidation
:core:history
  LibraryListeningStats.kt        NEW  pure aggregates: per-region plays/skips/unplayed,
                                       concentration, darkest + skippiest region
composeApp
  MapTab.kt                       NEW  canvas, gestures, selection card
  MapLenses.kt                    NEW  pure: lens -> colour, radius, legend, headline inputs
  App.kt                          EDIT new pager destination at index 1
```

`LibraryLayout` is a pure function with no Compose and no platform APIs: same library in, same
picture out, testable headlessly. It consumes the `LibraryVectorSpace` that
`LibraryVectorFusion` already produces, so no second copy of the matrix is materialised — the
same constraint `TrackClustering` works under.

`MapLenses` holds every decision that can be checked without rendering — which colour a track
gets under which lens, which legend appears, which numbers the headline needs. `MapTab` only
draws. The split exists so lens logic is unit-tested rather than screenshot-tested.

### 4.1 Persistence

The layout is computed **once per material library change**, never per visit, and stored as
`map_layout.bin` beside `smart_index.bin` using the existing `FileIndexStore` format
(big-endian magic, formatVersion, UTF modelVersion, dim, count, then rows). Key includes the
layout algorithm version and the track-id set; a mismatch schedules a background recompute and
the cached map keeps showing until it lands.

## 5. The lenses

Four chips: **Worlds · Plays · Never played · Skips**. One is active at a time.

The binding constraint, found while mocking this up: a scatter plot only supports **three**
categorical hues at colourblind-safe separation across all pairs, and the library has eight
regions. A rainbow map is therefore not available — it would be pretty and unreadable. So:

> **Colour always encodes a number, never an identity.**

| lens | encoding | headline |
|---|---|---|
| Worlds | selected region in accent, rest neutral; region labels at centroids | "873 tracks settle into 8 regions." |
| Plays | sequential blue ramp, unplayed neutral | "114 tracks — 13% of your library — are half of everything you have ever played." |
| Never played | binary: never-played accent + larger radius, played neutral small | "You have never played 435 of 873 tracks. Film score & soundtrack is 62% untouched." |
| Skips | sequential orange ramp over skip rate, unplayed neutral | "You bail out of Film score & soundtrack more than anywhere else — 65% of starts." |

Never-played uses **size as well as colour**, so identity is never carried by hue alone. Every
lens ships a legend; the sequential legends are discrete swatch steps, not a gradient.

Region names come from `LibraryWorlds`, unchanged — genre, optionally sharpened by decade,
else artist mix, else a neutral label. Map does not invent a naming scheme.

**Headlines are full sentences containing counts, so they are string resources with plurals.**
This is exactly where the locale traps live: never reuse a `sort_*` key, and never assume a
count slots into a translated sentence the way it does in English.

## 6. Interaction

| gesture | result |
|---|---|
| tap a dot | select its region; the card fills with that region's real numbers |
| pinch / pan | zoom the canvas — **required**, not optional: at phone size ~33% of dots overlap another dot, so track-level tapping is impossible without it |
| long-press a dot | existing `TrackActionsSheet` |
| *Play region* | queue the region, medoid first |
| *SMART from here* | seed SMART with the region medoid |

The page is built once per visit and does not re-rank underneath the reader — same rule For
You already follows, and more important here, since the whole value is a stable shape you
learn.

## 7. Placement

New pager destination at **index 1**, between For You and Playlists.

Stated cost: this makes 8 tabs and pushes Folders further off-screen. Accepted because Map is
a destination rather than a browse mode. If the strip proves too crowded in use, the fallback
is entry from the For You "Worlds" row header instead of a tab — the row already exists and
already means this.

## 8. Cold start

| state | behaviour |
|---|---|
| indexing incomplete | empty state naming the reason; no partial map |
| embeddings ready, thin history | Worlds lens only; the three stat lenses are hidden, not shown empty |
| fewer tracks than `MIN_CLUSTER_SIZE` regions can support | fall back to a single unnamed region |

The stat lenses appear once the listening log can support a true sentence. A lens that says
"you have never played 100% of your library" is technically correct and worthless.

## 9. Testing

- **Determinism** — same library, same seed, byte-identical layout. The whole design rests on
  the map not moving.
- **Stability** — drop 5% of a fixture library, recompute with warm start + Procrustes,
  assert neighbourhood retention stays above a floor. Guards the anti-churn machinery.
- **Layout quality regression** — run playlist@15 against a fixture library and assert it
  stays above a floor. Catches a layout change that silently degrades the map; no ordinary
  unit test would.
- **Lens purity** — `MapLenses` is pure, so colour/radius/legend/headline selection is unit
  tested directly.
- **`LibraryListeningStats`** — aggregates tested against a hand-built event log.

No screenshot tests.

## 10. Deliberately excluded

- Total minutes listened, longest streak, listening-by-hour. Non-spatial, and each is a number
  you read twice. If they are ever wanted they are a labelled trivia strip, not a screen.
- Top artists / most played. The Artists and Tracks tabs sorted by plays already answer these.
- Any per-track audio visualisation (waveform, spectrum). Different product.
- Recommending deletions. The Skips lens shows the evidence; acting on it stays the user's
  call.

## 11. Open questions

1. Does *Play region* queue medoid-first or shuffled within the region?
2. Does *SMART from here* seed from the region medoid or from its least-played member? The
   second turns the map into a rediscovery engine and is more in keeping with For You's
   purpose, but it is a different promise from the one the tap gesture implies.
3. Is 8 regions the right k for a map? `TrackClustering.DEFAULT_K` was chosen for a carousel
   row, where 8 cards is a full row. A map can carry more regions legibly.
