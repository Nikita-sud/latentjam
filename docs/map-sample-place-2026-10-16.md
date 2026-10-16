# Map "sample and place" layout — 16 October 2026

Prototype and measurement of a cheaper Map layout for large libraries, on branch
`feat/map-sample-place` (cut from `feat/map-scale-10k`, 95c0b194). Context:
[the 10k scaling note](map-scaling-10k-2026-10-15.md).

## Predeclared gate (written before any candidate result was seen)

The candidate is compared with **full Barnes–Hut** (the current path above 2,000 tracks) on the
same input. Metrics as in `MapScaleReplay`: trust@15 and recall@15 against the PCA-50 input of
the *full* library (the same reference for both methods), playlist@15 (real library) or class@15
(synthetic), and overlap@15 with the full Barnes–Hut map. "BH seed 2" is Barnes–Hut with another
start: it measures how much two acceptable maps already differ.

The candidate is **acceptable** only if, on every input measured (synthetic 5,000 and 10,000;
the real 1,104-track library with a forced sample of 30 %, the production ratio at 10,000):

| Metric | Allowed loss vs full Barnes–Hut |
| --- | --- |
| trust@15 | ≤ 0.010 absolute |
| recall@15 | ≤ 10 % relative |
| playlist@15 / class@15 | ≤ 0.020 absolute |
| overlap@15 with full BH | ≥ overlap(BH seed 2, BH) − 0.10 |
| stability (+5 % tracks) | old tracks' 2-D 15-NN kept ≥ warm-started full BH − 0.05, and median displacement ≤ 1.5× full BH's |

and the cost side must be worth a new code path:

| Cost | Required |
| --- | --- |
| time at 10,000 (256 MB emulator) | ≤ 0.5× full Barnes–Hut |
| peak Java heap at 10,000 | ≤ 0.6× full Barnes–Hut |
| cancellation | every phase stops within 250 ms |

Outcome rules: all pass → adopt above a threshold; quality passes only at some sizes → adopt
above the smallest size where it passes; any quality row fails at 10,000 → reject.
