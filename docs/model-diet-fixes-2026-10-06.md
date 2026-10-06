# Model diet: final audit and continuation experiments, 2026-10-06

## Current conclusion

The former experiments do not establish a model-size ceiling. The audit repaired reproducible
training/export mistakes, trained a compact encoder with improved raw MPD neighbor retrieval,
and found a separate queue-selection restriction that can hide suitable tracks.

The user clarified the objective during this work: smooth transitions and not skipping suitable
remaining tracks matter; a long queue need not stay tied to its first track. Therefore the inherited
seed-playlist P@10/P@20 scores remain descriptive measurements, **not the primary verdict on a long
queue**. A lower seed-co-membership score alone does not establish worse playback. Earlier rejected
state-map experiments below were rejected under that earlier metric, not judged by a listening test.

## Coverage and continuation change

The default SMART chain selects one 100-track pool once and retains a fixed original-seed pull.
A research trace over 64 listener-history and 64 MPD cold seeds found pre-reordering weak transitions
(fused cosine below the inherited 0.40 threshold) despite eligible close alternatives on 15.78% and
28.20% of hops respectively. Cases where close alternatives existed only outside the fixed pool
accounted for 1.88% and 5.31% of all hops. These are similarity-based diagnostics; they do not prove
that every closer candidate would be preferred by a listener.

`ChainTuning.continueAfterExhaustion` is an explicit experimental mode in `SmartChain`:

- Recompute remaining suitable candidates from the whole eligible library at every pick.
- Preserve the active neighborhood while it has available close tracks; when it is exhausted,
  move the reference to the latest pick and retrieve a continuation there.
- A neighborhood match needs centered audio cosine >= 0.20 and fused audio/descriptor cosine >= 0.40.
  These thresholds come from existing chain constants, not optimization on the new evaluation.
- Refresh the fixed-size scorer input with the current pool, including zero padding. Selected-track
  exclusions use library row ids, not positions in a pool that changes.
- Preserve title deduplication, session exclusions, the global artist cap, and recent-artist penalties.
  Marked-playlist queues retain their existing quota behavior. No weight file, tensor dimension or
  model size changes are required by this policy.

A first rolling-retrieval prototype shifted the reference on every pick and concentrated excessively
on one artist; it was not promoted. Audio-state freezing improved seed P@20 but hurt cold queues and
was not adopted as the answer to the clarified continuity objective.

## Full continuity evaluation

The table compares the new compact bundle with and without the continuation policy, using identical
models, libraries and seed histories. Each pair is scored on fixed reference representations: centered
and unit-normalized 0.7.1 audio and artist-descriptor vectors. Transition cost is the mean squared
`1 - (0.65 * audio_cosine + 0.35 * descriptor_cosine)` over adjacent pairs, including seed to first pick;
lower is smoother under this proxy. The 0.35 semantic weight is inherited from JourneySequencer.
An independent fixed full-regFM audio ruler is also recorded in the JSON; its mean acoustic continuity
improves in all aggregate rows below. Artist diversity is unique artists divided by queue length.

| Evaluation | Queues | Reduction of transition cost | Mean audio-cosine delta, 95% paired seed CI | Artist-diversity delta |
|---|---:|---:|---:|---:|
| MPD cold, 20 tracks | 900 | 17.58% | +0.02361 [+0.01922, +0.02818] | -3.57 pp |
| Listener cold, 20 tracks | 252 | 6.64% | +0.00934 [+0.00323, +0.01574] | +0.73 pp |
| Listener history, 20 tracks | 1073 | 5.12% | +0.00450 [+0.00129, +0.00758] | -0.02 pp |
| Listener history, 40 tracks | 64 | 13.55% | +0.02980 [+0.01890, +0.04128] | -2.50 pp |
| MPD r1k_a, 40 tracks | 64 | 27.03% | +0.05511 [+0.03600, +0.07492] | -5.31 pp |

The 20-track evaluation covers all 900 MPD cold, 252 listener cold and 1,073 listener history seeds.
The 40-track stress test uses the fixed discovery set of 64 seeds per library; it is exploratory,
not a fresh holdout. Better average continuity does not mean every transition improves: for example,
the listener's 20-track lower-tail audio statistic has a crossing-zero interval. The MPD improvement
comes with somewhat more repeated artists. The global cap remains six tracks per artist.

The models had prior exposure to parts of the legacy training/evaluation catalog, and many seed
histories are correlated. Bootstrap intervals treat seed records as sampling units; they must not be
read as an independent listener-preference trial. The full raw queues, per-seed metrics, model hashes,
compiled-source hashes and runtime logs are retained in the experiment folder.

## Control using the previous compact encoder

The same continuation policy was also run on all 2,225 standard queues with the previous p50 bundle,
without replacing or retraining a single model. Transition cost fell by 15.37% on MPD, 5.06% on
listener cold queues, and 5.91% on listener history. Mean baseline-ruler audio cosine increased by
+0.01881 / +0.00866 / +0.00488 respectively; all three paired intervals exclude zero. On the full-regFM
ruler, the listener-history audio improvement is smaller and its interval crosses zero. This control
supports an algorithmic contribution independently of the newly trained compact encoder. Full values
are in `continuation-implementation/current-p50-results.json`.

## Validation and release status

- 401 SMART host tests passed, including four new continuation tests. In particular, a synthetic
  125-neighbor library yields 120 distinct suitable picks even though one pool holds only 100;
  a separate fixture verifies that all eligible close tracks are selected before the first departure.
- 12 focused Python regression tests passed. Default-off Kotlin replay matches the old cached
  queues exactly on both 64-seed discovery sets. Full real-model runs report no inference failures.
- The iOS simulator suite of `core:smart` passes too (369 tests, the four continuation tests included).
  `SmartChainParityTest`'s 20 recorded seeds give identical chains with and without the change; the
  fixture itself predates 0.7.0's chain changes and fails against its reference at the previous commit
  as well, so it says nothing about this change and needs regenerating.
- `qat_nets.py --state-target-map identity` keeps the state targets in 0.7.1's coordinates, the recipe of
  the p50 nets the branch ships; without it that recipe could no longer be reproduced.
- The continuation mode is **off by default** and remains a reviewable experiment. The app's assets
  have not been replaced. The new encoder is 3,351,013 bytes versus 3,351,009 for the previous p50;
  the queue change itself adds no model parameters.
- No device latency or listening test has been completed. The new compact encoder also misses the
  conservative 0.99 minimum per-window fakequant/export cosine check on 32 calibration windows
  (mean 0.997245, minimum 0.975409); its downstream measurements were run as diagnostics, not as
  permission to silently waive that gate.
- Coverage is bounded by the requested queue length and by explicit eligibility/repeat exclusions.
  “Suitable” here is the stated model-space criterion, not every song a human might consider suitable.
  Repeated app planning across multiple batches and marked-playlist behavior need separate product
  evaluation before making this mode the default.

### Follow-up, same day

- The export check now passes. `distill_audio_cached.py --aq` fine-tunes with the graph's INT8 convolutions
  and uint8 activations simulated; the teacher-assistant student then exports at minimum 0.9978, but as a
  bundle it fails owner history (ΔP@10 −0.92 [−1.43, −0.40]) and its refitted head loses macro AP (−1.95
  [−3.40, −0.48]), so it is rejected. The p50 the branch already shipped failed the check as well (minimum
  0.955); fine-tuned the same way against the float 0.7.1 encoder it passes (minimum 0.9985) with queue
  quality unchanged within noise, and replaces it on the branch (`76b16b50`). Measurements are in
  `docs/model-diet-plan.md`, phase 3, third round.
- The queue's automatic top-up plans 12 tracks per request and starts each plan from the queue's last
  track; the For You hero, the map's regions and "start SMART" from a track card plan the chosen length in
  one chain, so the long-queue results above do apply there. The mode's state is not carried from one plan
  to the next: in a two-plan replay with the shipped encoder, 12 of 64 cold starts left the neighbourhood at
  the second plan's first pick while suitable tracks remained.

### Carrying the walk across plans

Fixed in `6db0054a`: the chain reports and resumes a `ChainWalk` (its reference track and latest 40 picks),
the engine resumes it when a request continues from the last track of the plan it answered, and such a
plan is ordered from that track (`d42ffa52`, JourneySequencer `from`). Measured with
`tools/research/model_diet/replay_plans.py` and `compare_plans.py`: the shipped bundle, the app's request
pattern (plans seeded with the queue's last track, everything queued excluded, history as
`smartHistoryFor` hands it over), 0.7.1's audio and artist-descriptor vectors as fixed rulers. "Skipped"
counts plan slots that went outside the pick's neighbourhood while suitable tracks remained (the chain's
own criterion, session exclusions included); "cost" is the audit's transition cost; "audio" the mean
adjacent audio cosine; "playlist" the share of tracks sharing a playlist with the pick. Off is the shipped
chain, "earlier" the continuation mode as the listening build first had it, "new" the fix.

| Queues (seeds) | Skipped: off / earlier / new | Cost: off / earlier / new, new − off [95 % CI] | Audio, new − off | Playlist: off / new |
|---|---|---|---|---|
| Owner history, 12+12+12 (1,073) | 9.1 / 5.9 / 0.3 | 0.166 / 0.160 / 0.176, +0.010 [+0.007, +0.013] | −0.002 [−0.006, +0.002] | 33.2 / 27.4 % |
| Owner history, 20+12+12 (1,073) | 9.5 / 5.3 / 0.5 | 0.181 / 0.170 / 0.182, +0.001 [−0.002, +0.004] | +0.010 [+0.006, +0.013] | 30.6 / 24.1 % |
| Owner cold, 12+12+12 (252) | 10.0 / 6.4 / 0.2 | 0.173 / 0.167 / 0.181, +0.008 [+0.003, +0.013] | +0.001 [−0.006, +0.008] | 34.6 / 27.5 % |
| Owner cold, 20+12+12 (252) | 10.6 / 5.7 / 0.3 | 0.183 / 0.173 / 0.187, +0.004 [−0.001, +0.010] | +0.005 [−0.002, +0.012] | 30.9 / 24.4 % |
| Six MPD libraries, 12+12+12 (900) | 9.3 / 5.2 / 0.05 | 0.199 / 0.175 / 0.187, −0.013 [−0.016, −0.009] | +0.018 [+0.014, +0.022] | 17.5 / 18.0 % |

The fix does what the clarified objective asks: queues with any skipped slot fall from 82–90 % (off) and
56–65 % (earlier) to 7–14 % on the owner's library. Artist variety rises everywhere (distinct artists per
track +0.07 on the owner's library, +0.03 on MPD; the longest same-artist run −0.5 to −0.9). Against the
shipped chain, adjacent transitions sound no rougher on the owner's library and smoother on MPD; the cost,
which also counts artist changes, is up to 6 % higher on the owner's top-up queues. Against the earlier
continuation mode the cost is 7–10 % higher: that mode followed the queue's tail instead of finishing the
neighbourhood. Ordering a resumed plan from the queue's last track removed a third of the extra cost at
plan boundaries (owner history: 0.232 → 0.200, against 0.137 off). On the owner's library fewer tracks share
a playlist with the pick (−6 pp in the first 12 for any continuation mode, −6 to −7 pp over the queue); on MPD
there is no measured difference. These are proxies; the listening build with the fix is for the ear test.

### Settings sweep against 0.7.1 (written protocol)

Question: which settings make queues better than 0.7.1 for any listener, in the app's plan pattern
(12+12+12)? The protocol was written before any run (`sweep-2026-10-06/protocol.json` in the experiment
folder): settings chosen on discovery sets (MPD r1k_a, s1k_a, r3k; the owner's even seed rows), accepted
only if on both held-out pools (MPD r1k_b, r1k_c, s1k_b; the owner's odd seed rows) the playlist share is
not more than 1 pp lower, fewer neighbourhood slots are skipped, audio adjacency is not more than 0.005 lower
and abrupt transitions (audio cosine under 0.2) not more than 0.5 pp more frequent.

Swept (`tools/research/model_diet/replay_plans.py` cfg variants, `sweep_eval.py`): the plain chain with the
semantic terms ×0.5, ×1.5, ×2; the continuation mode with a hard or soft neighbourhood (bonus 0–4) and with the
neighbourhood's descriptor share 0.3/0.5/0.7 and semantic weight ×1/1.5/2.

- No setting passed. The selection rule picked the continuation mode with its defaults; held out it is better
  than 0.7.1 on MPD in every measure (playlist share +1.0 pp [+0.1, +1.9], audio adjacency +0.017, abrupt
  transitions −1.0 pp, skipped slots −8.7, longer artist runs gone) and worse on the owner's library (playlist
  share −4.3 pp [−5.5, −3.1], audio adjacency −0.006). The owner's loss sits in other artists' tracks, not in
  fewer repeats of the pick's artist, and at every neighbourhood size.
- The semantic weight is a trade-off, not a free gain: ×1.5–2 adds about 1 pp of playlist share on the
  owner's library (none on MPD) for rougher transitions; ×0.5 makes queues markedly smoother (+0.03 audio
  adjacency) for 3 pp less playlist share on the owner's library.
- A soft neighbourhood or a different descriptor share does not recover the owner's loss.
- Ordering has no headroom: the exact optimal order within each plan would add +0.003 audio adjacency.
- The new bundle with the shipped chain is indistinguishable from 0.7.1 on both held-out pools.

### Decision: the continuation mode is on in the app

The owner set the goal: good queues for other listeners, not for the owner's own library. On the held-out MPD
libraries (r1k_b, r1k_c, s1k_b; 450 starts each) the mode was checked in every way the app plans a queue,
against 0.7.1:

| First plan, then 12-track top-ups | Playlist share, Δ pp [95 % CI] | Audio adjacency Δ | Abrupt transitions Δ | Protocol |
|---|---|---|---|---|
| 12 (top-up only) | +1.02 [+0.14, +1.92] | +0.017 | −1.0 pp | passes, better |
| 10 | +1.48 [+0.64, +2.32] | +0.013 | −1.1 pp | passes, better |
| 20 (default length) | +0.46 [−0.33, +1.29] | +0.013 | −0.7 pp | passes |
| 40 | −0.27 [−1.00, +0.49] | +0.031 | −1.3 pp | fails the −1 pp margin by 0.004 pp |

Skipped neighbourhood slots fall to almost none at every length. The neighbourhood test is now computed once
per reference and a hard neighbourhood's pool scores only its rows (byte-identical queues; a 36-track replay on
a 3,002-track library takes 233 ms per seed against 463 before and 238 for the default chain). `AppGraph`
turns the mode on; `SmartEngineConfig` keeps it off by default, which is the 0.7.1 chain.

Known limits: thematic libraries like the owner's lose playlist share (−4.3 pp held out); the MPD libraries
have no listening histories, so history-mode evidence comes from the owner's library alone; playlist
co-membership and audio continuity are proxies for listening.

A blind judge (DeepSeek Flash, `tools/research/model_diet/judge_queues.py`: the first 24 tracks of each queue,
every pair judged in both orders and averaged; MPD libraries only, the owner's library never leaves the machine)
sees no difference on the held-out libraries: with top-ups only, 0.7.1 3.46 against 3.52, Δ +0.05 [−0.03,
+0.14] over 450 pairs; at the default length 20, 3.53 against 3.51, Δ −0.02 [−0.10, +0.06]. Clear wins (both
orders agree, a gap of at least one point) split 196 to 193. The judge's complaints about the losing queue are
the same for both versions: drifting off the seed's style (about 180 of the clear losses), flow, mood, a
repeated artist and era. Each version drifts where the other does not: 0.7.1 into classic rock and oldies after
a modern seed, the continuation mode into show tunes or ambient after a pop seed. The proxies' gains are too small
for the judge to notice; the mode is on for its measured continuity and coverage, not as a judged improvement.

### Rings: slower drift, judged better

The judge's main complaint about both versions is drift off the seed's style. Rings (`7fc9cb19`): once the
neighbourhood is spent, the threshold widens around the same pick (0.40, 0.35, … down to a floor) before the walk
follows its latest pick, and inside a widened ring the pull toward the pick weakens so the previous track chooses.
Protocol written first (`sweep-2026-10-06/protocol-rings.json`): variants chosen on MPD r1k_a, s1k_a, r3k by the
judge with proxy limits, accepted on r1k_b, r1k_c, s1k_b in both app scenarios.

- Discovery (450 starts, judge against 0.7.1): every variant +0.21 to +0.25, the current mode +0.22; selected
  step 0.05, floor 0.20, pull 0.25 (+0.23; audio adjacency −0.005 against 0.7.1).
- Held out (450 starts each): judge against 0.7.1 +0.15 [+0.07, +0.22] with top-ups and +0.08 [+0.01, +0.14] from
  a 20-track start; against the current mode +0.09 [+0.03, +0.15] and +0.08 [+0.01, +0.15]. Playlist share against
  0.7.1 +1.12 [+0.30, +1.94] and +0.99 [+0.26, +1.69] pp; abrupt transitions no more frequent; fewer repeated
  artists; audio adjacency −0.003 [−0.009, +0.003] and −0.010 [−0.015, −0.005]. The second misses the
  pre-registered −0.005 smoothness margin, so by its own protocol the variant is not accepted.
- Reading pairs by hand agrees with the judge on its clear calls (2010s pop staying pop instead of turning to rap
  and five Britney Spears tracks; a wide ring letting a grime seed wander to Owl City and AC/DC). Both versions put
  one artist's tracks next to each other (the ordering keeps similar tracks together).

### The owner's criterion: tracks only

The owner objected that a run of one artist is no fault when its tracks fit; a problem would be the same artist
with tracks that do not. Measured on the held-out queues: neighbouring tracks by one artist are as similar as
neighbours by different artists (audio cosine 0.60–0.64 against 0.59–0.61), and the faulty case (same artist,
cosine under 0.2) is rare and rarest in the new versions (4.2 % of same-artist steps for 0.7.1, 1.9 % with rings).
The judge's earlier instruction also asked it not to repeat an artist, so a judge that compares fit and flow only
(`judge_queues.py --prompt fit`) was run again on the held-out libraries: rings against 0.7.1 +0.02 [−0.06, +0.10]
and −0.04 [−0.11, +0.03], against the current mode −0.03 and −0.03, the current mode against 0.7.1 +0.05 and −0.02 —
all level. Rings' earlier win came from fewer artist repeats.

Its complaints about the losing queue (708 clear verdicts): a genre or style jump (99 %), flow (98 %), mood or
energy (86 %), era (62 %), soundtracks, musicals or children's songs (18 %), language (15 %). Three ways to cut the
jumps were tried on the discovery libraries with this judge, each under a protocol written first
(`sweep-2026-10-06/protocol-style.json`, `protocol-stylegate.json`): a stronger semantic weight (×1.5, ×2, ×3:
+0.15, +0.14, +0.13 over 0.7.1) and a style gate on the artist descriptor between consecutive picks (0.1, 0.2,
0.3: +0.13, +0.11, +0.11), against +0.14 for the current mode. None was worth confirming. The current mode itself
is judged better than 0.7.1 on the three discovery libraries (+0.14 [+0.06, +0.23]) and level on the three
held-out ones, so its advantage is library-dependent. Lune (github.com/MrDemonc/Lune, GPL-3.0, read for ideas
only) builds queues from same-artist and genre-tag families, keyword energy and the listener's own transitions;
nothing there addresses the jumps.

### Where the jumps come from

The judge now marks the tracks that break the flow, one queue at a time (`annotate_jumps.py`, the first 24 tracks),
and the chain says why it chose each track (`SmartChain.build`'s `trace`, `replay_plans.py --trace`; tracing
changes no pick: the traced queues equal the sweep's byte for byte). On the six MPD libraries (900 queues of the
current mode, `jumps-2026-10-06/`):

- 18.8 % of tracks are marked; 97 % of queues have at least one mark, 4.52 per queue. Many marks are soft
  (neo-soul inside an R&B queue), some are real drift (reggae → dancehall → reggaeton → EDM → chill electronic).
- Marks grow along the queue: 5 % at the first track, 9 % at 2–4, 15 % at 9–12, 24 % at 14–18, 28 % at 19–24.
  The first track of a later plan is no worse than its neighbours (20.1 % against 20.1 % at position 13).
- Sound does not predict a mark: among the chain's picks the audio cosine to the previous track has an AUC of
  0.51, to the seed 0.55. The artist descriptor does: to the seed 0.67, to the previous track 0.64. At a
  descriptor cosine to the seed of 0.65 or more 3–5 % of tracks are marked, under 0.2 about 31 %, whatever the
  sound. The judge sees only "artist — title" here (the MPD libraries carry no genre or year), so its marks are
  about style by construction; sound continuity has to be measured separately.
- Picks after the walk moved its reference off the seed are marked more often (25 % against 15 %). Picks that
  share a playlist with the seed are marked 7.8 % of the time, the rest 21.6 %, so the marks agree with the
  human playlists.
- The per-track count separates versions the pairwise judge could not: 0.7.1 4.92 per queue against the current
  mode's 4.52 (Δ −0.40 [−0.79, −0.01]); rings 4.50; every semantic weight (×1.5–3), style gate (0.1–0.3) and
  neighbourhood descriptor share (0.7) is level with or above the current mode on the discovery libraries.
- Greedy queues built from the 0.7.1 rulers alone (each step the unused track with the best weighted closeness
  to the previous track and the seed) are marked as often as the chain: 4.50 by style first, 4.44 with equal
  weights, 5.05 by sound alone. Similarity ranking on these signals has no headroom left.

Are the jumps avoidable, or does the library simply run out of the seed's style? For 360 marked tracks and 360
unmarked controls the judge picked the best next track blind from the app's pick and 49 tracks not yet played
(the 15 closest to the seed, the 15 closest to the previous track, 20 random), and the queue with its pick swapped
in was marked again (`avoidable_jumps.py`). It took the app's pick for 8 of the 360 jumps. Asked again about the
unchanged queue it marks 84 % of the same jumps and 4 % of the controls; with its own pick swapped in, 20 % of the
jumps stay marked; with a random candidate swapped in, 68 % (and 45 % of the controls become jumps). Of the 296
jumps it confirms, 237 (80 %) disappear with its pick. Those picks share a playlist with the seed 35 % of the time
against 5 % for the app's picks; they sit closer to the seed by artist style (0.50 against 0.32) and further from
the previous track by sound (0.35 against 0.59); 94 % of them rank among the 15 closest by the chain's own signals.
The chain could see these tracks and weighed sound to the previous track above style to the seed. For scale, two
tracks of one MPD playlist sit at an audio cosine of 0.06–0.26 on average, the chain's neighbours at 0.60.

### A score correction learned from the judge (not adopted)

`ChainTuning.rerankWeights` adds a weighted sum of twelve features the chain already computes (`Rerank`: sound,
artist style and text closeness to the previous pick, the reference and the original pick, the scorer's vote, a
same-artist flag, descriptor coverage, neighbourhood membership); off by default. The judge ranked the three best
of about 18 of the chain's own candidates at 10,000 hops of replays on four MPD libraries never used to evaluate
queues (`train_r1`, `train_r2`, `train_s1`, `train_s2`, prepared for the new bundle; `rank_candidates.py`), and a
Plackett–Luce model was fitted on them (`fit_rerank.py`). On a library left out of the fit it finds the judge's
first choice 15.4 % of the time against 11.8 % for the chain's own score (chance 5.6 %); its pick is in the judge's
top three 34.1 % against 28.9 %. The fitted weights favour artist style to the reference most (27 score units), then
sound to it (14), style and text to the previous track (11, 10), sound to the previous track (8).

Round 1 (`rerank-2026-10-06/protocol-rerank.json`) failed at selection: on the discovery libraries no strength cut
the jumps per queue and every one raised harsh transitions (adjacent audio cosine under 0.2) from 1.8 % to 4.8–6.0 %.
The pairwise fit judge, run afterwards on the same queues, preferred them strongly (+0.27 [+0.20, +0.33]), and the
per-track count turned out to be context-dependent (a glaring jump hides milder ones; in the controls a random swap
lowered the queue's other marks by 2.0). Round 2 (`protocol-rerank-r2.json`, written before its runs) added a sound
floor (`ChainTuning.soundFloor`: a candidate under the floor to the previous pick is passed over while another
remains) and made the pairwise judge the primary endpoint. Selected on the discovery libraries: the fitted weights
with a floor of 0.2 (+0.24 [+0.18, +0.31], harsh transitions +0.19 pp). Acceptance:

- held out (r1k_b, r1k_c, s1k_b, 450 pairs): fit judge +0.36 [+0.29, +0.44] (239 wins, 78 losses); playlist share
  +1.50 [+0.59, +2.48] pp; harsh transitions −0.18 [−0.44, +0.10] pp; the default judge (which also asks not to
  repeat an artist) +0.08 [−0.00, +0.16];
- fresh (train_r3, train_s3, 300 pairs): fit judge +0.24 [+0.15, +0.34]; harsh transitions −0.56 [−0.97, −0.14] pp;
  playlist share −0.11 [−1.22, +0.96] pp, which misses the −1.0 pp bound: **not accepted** by its protocol; the
  default judge +0.00 [−0.09, +0.10];
- the owner's library (local only): playlist share +4.8 pp cold and +4.7 pp with history, harsh transitions −1.1 and
  −1.4 pp, longest run of one artist +0.9 and +0.7.

The gain is mostly artist clustering. Over the 750 held-out and fresh queues the longest run of one artist grows
from 3.45 to 4.41 tracks, queues with five or more in a row from 24 % to 50 %, and distinct artists per 24 tracks
fall from 16.5 to 13.9; one song in two versions appears in 9 % of queues against 6 %. The fit judge was told that
a run of one artist is fine when its tracks fit, and the judge that is not told so sees no difference. Six random
held-out and fresh pairs were shown blind to a separate model asked to answer as an ordinary listener: it preferred
the current mode four times, the correction once, and called one a tie, objecting to runs of four to six songs by one
artist ("an album, not a radio") and to one song twice in different versions. With fewer runs the correction's case
is open; it stays off.

### Runs of one artist: a soft, growing penalty (accepted with the correction)

The owner rejected a hard limit on runs (sometimes only the same artist has the similar tracks) and asked for a
penalty that grows with every track in a row. `ChainTuning.artistRunPenalty`: when the queue ends with k tracks by
one artist (the seed counts), that artist's next candidate loses k times the penalty in standard deviations of the
hop's candidate scores, so a clearly better fit still plays and the unit means the same with any scoring;
`JourneySequencer` takes the same value as the cost of two neighbours by one artist, in standard deviations of the
window's step costs. With the penalty off every pick is unchanged (the replays reproduce the current mode and round 2
byte for byte). With the correction most runs come from the picks themselves (longest run 4.65 in pick order, 4.66
played); in the current mode the order adds about half a track (3.16 to 3.68).

Protocol `rerank-2026-10-06/protocol-artist-runs.json`, written before the runs: penalties 0, 0.25, 0.5, 1, 2 with
the current mode and with the correction, both judges against the current mode on the discovery libraries; the
largest min(fit, default) wins. Discovery:

| variant | fit judge | default judge |
|---|---|---|
| current mode, penalty 0.25 / 0.5 / 1 / 2 | −0.04 / −0.07 / −0.15 / −0.25 | −0.01 / +0.02 / +0.03 / +0.06 |
| correction, penalty 0 / 0.25 / 0.5 / 1 / 2 | +0.22 / +0.23 / +0.14 / +0.06 / −0.01 | −0.01 / +0.03 / +0.07 / +0.11 / +0.15 |

Breaking runs in the current mode only costs coherence; with the correction the penalty trades the fit judge's gain
for the default judge's, and 0.5 keeps both positive. Acceptance (the correction with penalty 0.5, all gates pass):

- held out (r1k_b, r1k_c, s1k_b, 450 pairs): fit judge +0.26 [+0.19, +0.34] (209 wins, 96 losses), default judge
  +0.15 [+0.08, +0.23] (176, 117); playlist share +1.30 [+0.43, +2.23] pp; harsh transitions −0.46 [−0.73, −0.19] pp;
- fresh (train_r3, train_s3, train_r4, train_r5, 600 pairs; the first two reused from round 2): fit judge +0.13
  [+0.07, +0.19], default judge +0.10 [+0.03, +0.16]; playlist share +0.74 [+0.02, +1.48] pp; harsh transitions −0.52
  [−0.82, −0.25] pp;
- the owner's library (local only): playlist share +5.2 pp cold, +4.6 pp with history; harsh transitions −0.95 and
  −1.23 pp; mean adjacent audio cosine −0.002 and −0.008; the longest run of one artist 0.3 and 0.5 shorter;
- jumps per queue level (−0.01 held out, +0.15 fresh, both intervals across zero);
- runs over the 1,050 held-out and fresh queues: longest 3.33 tracks against the current mode's 3.52 (round 2: 4.41),
  five or more in a row in 21 % of queues against 25 % (round 2: 50 %), distinct artists per 24 tracks 15.1 against
  16.4; one song twice in two versions in 7 % against 6 %.

Listener panel (the protocol's veto): two blind models answering as ordinary listeners, ten random held-out or fresh
pairs each, preferred the new queues 12 times, the current mode 6 times, two ties (5:4:1 and 7:2:1); a blind reading
by the author before the key was opened: 10, 3 and 7 ties. Both listeners still object, in both versions, to one
song twice in different versions, to runs of four to six by one artist and to a second half that wanders.

In the app (`9b77945a`, `fe90ab23`, `978ab8de`): `SmartEngineConfig.judgedScoring` turns the scoring on with the
continuation mode, and Settings > SMART > Artist variety picks the penalty from the five measured steps (Off, Low,
Balanced = 0.5, High, Maximum), stored on both platforms and in local backups (format v6). A new level replans the
SMART tracks already queued once the slider rests on it, as marking a playlist does. Plans made with marked playlists
keep the shipped chain, and the app plans with every playlist marked "Keep together in SMART", so while any playlist
is marked every queue keeps the shipped chain, seeds outside the playlists included; Settings then shows Artist
variety as off, with a line saying why.

Versions of one song (`protocol-versions.json`): the repeated-title check also drops a trailing " - qualifier" that
names a version (Radio Edit, Remastered 2011, a remix, From "Grease", feat. ...), keeping qualifiers without a version
word such as a movement's name. Queues playing one song twice or the seed's song again: 9.8 % to 0.0 % held out, 7.5 %
to 0.3 % fresh; both judges slightly prefer the result (fit +0.02 [+0.00, +0.03] and +0.01 [+0.00, +0.02], default
+0.03 [+0.02, +0.05] and +0.02 [+0.01, +0.03]); playlist share and harsh transitions unchanged.

Artifacts: `/Users/nichitabulgaru/Documents/LJ/model-diet-2026-10-04/phase4-auditfix-2026-10-06`. The queue investigation is in `continuation-implementation/`,
with `full-results.json`, `full-per-seed.json`, source snapshots and runnable benchmark scripts.
`continuity-model-comparison.json` re-evaluates the earlier models on fixed transition rulers.
All computation in this audit used local resources; RunPod was not necessary.

## Earlier training/export audit and experiments

The following is the stage-one research record. Its inherited seed-based queue gates are historical;
use the clarified objective above for current conclusions about long playback queues.

The earlier failures do not establish a size ceiling. Several training/export defects were reproducible.
The head correction has measured benefits for the changed-space encoder, while the first state-transfer
prototype regressed downstream queues. Application assets are not promoted by this experiment.

## Changes

- `qat_audio.py`: checkpoint and legacy-checkpoint restoration keep activation fake quantization active.
  Export carries per-tensor scales and zero points; `make_q4_encoder.py` preserves them, including fused
  Conv/ReLU aliases and Add/GlobalAveragePool boundaries.
- `qat_nets.py`: head checkpoint selection uses normalized logit error for both AudioSet and FMA branches,
  not the nearly constant Music argmax; paired FMA examples are required when changing encoder space.
  Epoch zero can remain the best checkpoint. Output scaling uses training rows only.
- State targets and scorer inputs now have explicit coordinate contracts. History means use the forward
  track map. The explicit dual-query option uses the transpose of the new-to-old map; scorer training uses the
  exported state network. The forward query map is retained as an explicitly named rejected experiment.
  Neither linear map guarantees better retrieval for a nonlinear learned space.
- Teacher/student track ids and recorded state/scorer alignment are checked before training.
- The external bundle report reports crossing-zero confidence intervals as `inconclusive`, not `pass`.
  No non-inferiority margin has been introduced. The original report script is preserved beside it.

## Semantic head (official FMA-small test, 800 tracks)

| Encoder / head | Top-1 genre | Macro AP | Maximum shift of mean product output vs baseline |
|---|---:|---:|---:|
| 0.7.1 baseline | 55.25% | 61.342% | — |
| regFM, previous head | 53.875% | 59.350% | 0.230 |
| regFM, corrected head | 56.375% | 61.074% | 0.088 |
| p50 current, previous head | 55.750% | 61.482% | 0.009 |
| p50 current, corrected head | 55.625% | 61.061% | 0.011 |
| p50 distilled, refitted head | 56.500% | 60.760% | 0.050 |

Compared with its previous head, regFM gains 2.50 pp top-1 (95% paired-bootstrap CI +0.50 to +4.375)
and 1.724 pp macro AP (CI +0.614 to +2.855; 1,000 resamples). Improvement over the original 0.7.1 baseline is
not statistically established. The p50 head-only change does not demonstrate improvement. The legacy
teacher-label agreement gate remains failing; real-label quality and teacher imitation are separate
measurements, and neither is silently substituted for the other in the bundle report.

Both heads used the existing MPD training libraries and 2,083 licensed local FMA tracks (1,791 training,
292 official validation); official FMA test tracks were excluded from the new paired-head training.
The same dimensions and low-bit formats were retained. Training used 60 epochs, batch 256, LR 0.0003.

## Full queue checks

Differences below are percentage points against frozen 0.7.1, with the existing paired bootstrap.
MPD: 900 cold seeds; owner: 252 cold and 1,073 historical seeds. P@10 means playlist co-membership
among the first ten recommendations; it is an offline proxy, not a listening preference study.

| Candidate | MPD ΔP@10, 95% CI | Owner cold ΔP@10, 95% CI | Owner history ΔP@10, 95% CI |
|---|---:|---:|---:|
| regFM previous | +0.06 [−0.47, +0.60] | −0.16 [−1.23, +0.99] | −0.18 [−0.73, +0.32] |
| regFM corrected head only | +0.31 [−0.22, +0.86] | −0.28 [−1.31, +0.79] | −0.13 [−0.66, +0.34] |
| regFM head + forward-map state/scorer | +0.02 [−0.54, +0.61] | −1.07 [−2.30, +0.20] | −0.74 [−1.31, −0.21] |
| regFM head + dual-map state/scorer | -0.08 [-0.66, +0.47] | -1.19 [-2.34, +0.00] | -1.26 [-1.83, -0.69] |
| p50 distilled + transferred regFM head/state/scorer | -0.01 [-0.56, +0.57] | +0.44 [-0.83, +1.75] | +0.00 [-0.61, +0.58] |
| p50 distilled + specifically refitted head | +0.04 [−0.50, +0.63] | +0.63 [−0.60, +1.90] | −0.14 [−0.74, +0.40] |
| p50 corrected head only | −0.02 [−0.50, +0.46] | +0.48 [−0.56, +1.47] | +0.11 [−0.33, +0.55] |

Both forward and dual state/scorer candidates are rejected. The dual candidate loses 1.26 pp
owner-history P@10 (95% CI −1.83 to −0.69). High imitation cosine alone was insufficient.
Choosing a state-transfer map is now explicit; neither failing prototype becomes a default upgrade.
Secondary owner-history P@20 also declines for the regFM head-only candidate: −0.564 pp
[−1.002, −0.116], so it is not presented as an overall recommendation improvement.

## Activation export verification

Eleven focused regression cases pass (seven training/export/data contracts and four report verdicts). On 32 frozen calibration windows, a restored AQ smoke checkpoint
and the final custom-operator graph have mean cosine 0.999550 and minimum 0.998592. All 97 retained
activation scales/zero points checked exactly. This is numerical verification, not bit-exactness,
phone timing, or a trained AQ quality result. The local old AQ training checkpoint was unavailable;
its old 0.956 deployment result was not retrospectively re-measured with recovered trained ranges.

## Compact encoder experiment

The same p50 channel indices and 4-bit formats are retained. Starting point: the old regP checkpoint,
which had only 12 epochs. Frozen teacher: deployed regFM encoder. The cache contains 16,348 usable
tracks; official test tracks and evaluation-library paths are excluded from new training. The student
uses one deterministic 10-second crop per track, without waveform augmentation. This is a new bounded
teacher-assistant recipe, not a faithful continuation of the former two-teacher augmented objective.

Pilot: 2,048 training / 384 validation tracks, 4 epochs. Validation teacher cosine 0.963973 → 0.975312;
nearest-neighbor overlap 0.82865 → 0.85365. These are validation measurements of teacher imitation,
not a final retrieval-quality claim. Full run: 15,000 training / 900 validation tracks, 12 epochs after the pilot, batch 32, LR 5e-5
with cosine decay. Best cosine checkpoint: epoch 11, cosine 0.981963,
neighbor overlap 0.853111. Final epoch's neighbor overlap was 0.857000;
checkpoint selection was by validation cosine as declared before training. Training took 20.5 minutes
locally. Export uses the unchanged historical `cal_fma.npy` (200 windows); this is not new training.
Initial checkpoints may already have encountered validation audio in older runs. The MPD
evaluation libraries are excluded from the newly cached training paths.

## Reproducibility and disposition

Research tools: `tools/research/model_diet/`; regression tests: `tests/test_training_contracts.py`.
Artifacts and complete histories are saved outside Git in the model-diet benchmark experiment folder.
Model and teacher hashes, selected train/validation ids, calibration source, export logs and full
queue reports are retained. Existing app models and version identifiers are unchanged.


## Encoder-only controls

Original teacher-benchmark protocol: cosine nearest neighbors, uncentered, playlist co-membership,
four held-out MPD libraries, seed-paired bootstrap (2,000 resamples). These are not full SMART queues.

| Encoder | Size | P@10 | Delta vs 0.7.1, pp | 95% CI, pp |
|---|---:|---:|---:|---:|
| baseline | 10.747 MB | 14.499% | +0.000 | [+0.000, +0.000] |
| p50-current | 3.351 MB | 14.196% | -0.303 | [-0.472, -0.133] |
| regFM | 5.929 MB | 14.766% | +0.268 | [+0.060, +0.476] |
| regP-original | 3.351 MB | 14.028% | -0.471 | [-0.697, -0.241] |

Intervals follow the prior seed-level protocol; overlapping playlists induce dependence, and these
exploratory model comparisons are not a new independent confirmatory study.

The new p50 encoder is 3,351,013 bytes (3.351013 MB). On 6,013 held-out seeds,
P@10 is 14.567%, against 14.196% for the current p50. The paired gain against current
p50 is +0.371 pp [95% CI +0.153, +0.597], approximately +2.61% relative. Against the initial regP
checkpoint it gains +0.539 pp [+0.386, +0.703]. The gain remains positive when same-artist neighbors
are excluded: +0.249 pp [+0.035, +0.466] vs current p50. On the owner's library, improvement is
not established. These measurements concern the exported low-bit encoder, not a float stand-in.

The exported non-AQ p50 checkpoint does not meet the newly specified per-window 0.99 cosine check:
mean 0.997244, minimum 0.975409 on 32 windows. The end-to-end tests were still run as diagnostics,
not as permission to waive this check. The separate AQ fix was verified on a smoke model, but the
new teacher-assistant encoder was trained with weight fake quantization only.


## Integration before the final head refit

The first p50-distilled bundle reused the corrected regFM head and the old regFM state/scorer.
It does not establish a SMART P@10 improvement (MPD −0.011 pp, owner history approximately zero vs
0.7.1), and owner-history P@20 declines by 1.128 pp [−1.589, −0.671]. Its genre macro AP is 59.848%,
against 61.482% for current p50 and 61.342% for 0.7.1. This combination is not promoted.
A final head-only refit uses this encoder's own training-library vectors and paired FMA examples;
its checkpoint is selected on validation logit error, not on the test results.


## Data overlap limitation discovered during the audit

The legacy SMART/head training catalogs contain 7,385 unique track ids. Although the MPD playlist
split is separate, their catalog overlaps evaluation libraries by 776 / 694 / 714 / 910 / 790 / 2,036
tracks respectively (r1k_a / r1k_b / r1k_c / s1k_a / s1k_b / r3k); listener overlap is zero.
Catalog counts include rows reserved for internal validation. These SMART results are not evidence
of track-disjoint generalization. `qat_nets.py` now writes a split audit and can refuse such runs
with `--require-disjoint-tracks`; unavailable evaluation libraries are recorded as missing evidence.

In contrast, the new compact encoder cache has zero evaluation path and filename overlap across all
seven evaluation libraries. Its raw retrieval gain over the previous p50 is +0.37086 pp P@10,
95% paired seed-bootstrap CI [+0.15300, +0.59704] (14.1959% to 14.5668%, 6,013 MPD seeds).
Against the larger baseline, the +0.0682 pp difference remains inconclusive. The seeds and playlists
are dependent observations; these exploratory comparisons are not a fresh confirmatory trial.

## Queue-state investigation

The newly refitted compact bundle still loses 1.151 pp owner-history P@20 against 0.7.1
(95% seed-bootstrap CI [−1.608, −0.694], 1,073 histories), despite its raw retrieval improvement.
The 20-track benchmark is a tail stress test; the app normally plans 12 tracks.

A research-only copy of the frozen Kotlin chain records both selected rows and the complete 100-row
pool. With all interventions disabled it reproduces baseline and candidate cached queues exactly.
Four declared causal ablations were evaluated on 300 histories with even-numbered seed tracks:
use recent audio as the pool query, use the seed as the query, suppress learned scoring, and retain
the initially encoded audio state throughout queue planning. The latter preserves actual history,
while geometric transitions and the text centroid still follow the planned queue. Baseline and
candidate calls are otherwise identical; exploratory instrumentation still makes unused encode
calls to preserve the harness's call contract.

On this diagnostic subset, baseline P@20 is 41.60%, the new bundle is 39.8167%, and retaining its
initial state yields 40.7333% (+0.9167 pp against the new bundle, CI [+0.4163, +1.4333]). The other
three ablations worsen P@20. The average relevant pool count changes only from 19.303 to 19.103;
this alone does not explain the entire deficit. This isolates feedback through simulated audio
history as one contributor, without proving it is the sole cause. Confirmation uses odd-numbered
seed tracks and all cold libraries; the overall benchmark had already been inspected in aggregate,
so even this partition is not a wholly new test corpus. Full outcomes are recorded in the
`queue-diagnosis` experiment directory.
