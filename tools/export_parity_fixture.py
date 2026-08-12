#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""Generate the SmartChain parity fixture consumed by

    core/smart/src/androidHostTest/.../chain/SmartChainParityTest.kt

The Kotlin test replays the *model outputs* recorded here (per-hop state vectors and
candidate logits) through the ported chain and asserts the pool and the queue come out
identical. Feeding the graphs' outputs back in isolates everything :core:smart actually
owns — pool construction, the geometric + semantic terms, metadata multipliers, the
skip/exclude rules and the selection loop — from the two ONNX graphs, which are the same
files on both sides. A drift of one constant or one filter shows up as a different chain.

This is an INDEPENDENT re-implementation of the chain (SmartChain.kt / SmartSnapshot.kt /
SemanticZ.kt / Reanchor.kt / MetadataRerank.kt / Genres.kt) in numpy. It is not a wrapper
around the Kotlin; agreement between the two is the actual cross-check.

Scorer note (phase 2). The fixture *records* logits and the chain *replays* them, so which
scorer produced them does not affect whether parity is reachable — the same numbers drive
both sides. `--scorer` only decides which behaviour the fixture pins:
  * twostage  (default) — B's shipped stack: frozen acoustic scorer (predictor_scorer_n100,
               state 960 / candidates 100x960) + the text residual
               (predictor_text_residual_n100_960). This is what the device runs today.
  * semtext1344         — A HEAD's swapped 1344-d semtext scorer (app assets
               predictor_scorer_n100.onnx: state 1344 / candidates 100x1344). B does not
               ship this graph yet; recording its logits pins A-HEAD taste on top of B's
               owned geometry.
  * none                — zero logits (pure geometry + metadata + semantic-z + reanchor).

Cold contract. On the cold-start path both slow-taste encoder inputs (history_medium,
history_large) are the seed's audio and stay FIXED across hops — the app re-encodes only
history_small (RecommendationEngine re-encodes `features.copy(historySmall = ...)`; the port
passes the same fixed `context.medium`/`context.large`). The recorded states are produced
that way, and SmartChainParityTest's ReplayRuntime asserts exactly that invariant.

Output is written to a git-ignored directory (default: tools/research/output/parity-fixture,
override with --out); point SMART_PARITY_FIXTURE at it and run the test.

Data sources (offline, read-only): the on-device Room DB dump + the synth label store +
the descriptor npz — the same inputs the smart-mode-work harness uses.
"""
import argparse
import math
import os
import re
import sqlite3
import struct
import sys
from collections import deque

import numpy as np
import onnxruntime as ort
import pandas as pd

np.seterr(all="ignore")

LJ = os.environ.get("LJ_ROOT", "/Users/nichitabulgaru/Documents/LJ")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ML = f"{REPO}/androidApp/src/main/assets/ml"

# ---- chain constants (mirror ChainConfig / SemanticZ / Reanchor / MetadataRerank) ----
SCORER_SQUASH, SCORER_TEMP = 1.5, 2.0
COSINE_BLEND_WEIGHT = 3.0
CHAIN_SEED_GRAVITY = 2.5
SEM_CHAIN_SEED_GRAVITY, SEM_CHAIN_PREV_BLEND = 2.0, 1.0
HUB_CHAIN_DAMP, HUB_PENALTY_BETA = 0.6, 1.0
CHAIN_ARTIST_SPACING, CHAIN_ARTIST_QUEUE_CAP = 3, 3
ENERGY_DEADBAND, ENERGY_FLOOR = 0.2, 0.7
MULT_MIN, MULT_MAX = 0.05, 2.0
POOL_SIZE, AUDIO_DIM, TEXT_DIM, DESC_DIM = 100, 960, 384, 768
HUB_TOPK, HUB_ANCHOR_SAMPLE = 10, 1024

SEM_Z_CLIP, SEM_STD_FLOOR, SEM_MIN_VALID = 3.0, 0.05, 10

REANCHOR_ENABLED = True
REANCHOR_MIN_IDX, REANCHOR_NICHE_COS, REANCHOR_NICHE_MIN, REANCHOR_SEED_KEEP = 8, 0.40, 3, 0.4

SAME_GENRE_BONUS, SAME_ARTIST_BONUS, CROSS_GENRE_MALUS = 1.20, 1.12, 0.90
CROSS_LANGUAGE_PENALTY, SAME_ALBUM_PENALTY, ERA_DECADE_PENALTY = 0.75, 0.15, 0.04
SEED_CROSS_GENRE_PENALTY, SEED_GENRE_MIN_POOL_SUPPORT, SEED_GENRE_PREFIX_TARGET = 0.80, 6, 4

# Encoder feature vectors (baked into the recorded states; the test replays states by call
# index, so the exact values only need to be internally consistent here).
TIME = np.array(
    [
        math.sin(2 * math.pi * 20 / 24),
        math.cos(2 * math.pi * 20 / 24),
        math.sin(2 * math.pi * 2 / 7),
        math.cos(2 * math.pi * 2 / 7),
        0,
    ],
    "f",
)
SESS = np.array([math.log(2), 0, math.log(1.5), 1, 1], "f")

GENRE_ALIASES = [
    ("hip", "rap"), ("rap", "rap"), ("trap", "rap"), ("phonk", "rap"),
    ("rock", "rock"), ("metal", "rock"), ("punk", "rock"), ("grunge", "rock"),
    ("pop", "pop"),
    ("dance", "dance"), ("electronic", "dance"), ("edm", "dance"),
    ("house", "dance"), ("techno", "dance"),
    ("classical", "classical"), ("orchestral", "classical"), ("baroque", "classical"),
    ("soundtrack", "soundtrack"), ("score", "soundtrack"),
]
HUB_TOKENS = {"ost", "soundtrack", "score", "anime", "cinematic",
              "orchestral", "game", "ambient", "library", "western"}
TOKEN_SPLIT = re.compile(r"[^a-zа-яё]+")
BRACKETED = re.compile(r"\s*[\(\[][^()\[\]]*[\)\]]\s*")
WHITESPACE = re.compile(r"\s+")


def _genre_tokens(value):
    # Mirror of Genres.tokenize: runs of letter-or-digit characters, Unicode-aware.
    tokens, start = [], -1
    for i, ch in enumerate(value):
        if ch.isalnum():
            if start < 0:
                start = i
        elif start >= 0:
            tokens.append(value[start:i])
            start = -1
    if start >= 0:
        tokens.append(value[start:])
    return tokens


def _contains_phrase(tokens, phrase):
    if not phrase or len(phrase) > len(tokens):
        return False
    return any(tokens[i:i + len(phrase)] == phrase
               for i in range(len(tokens) - len(phrase) + 1))


def normalize_genre(g):
    # Mirror of Genres.normalize: whole-token phrase aliases, not substrings —
    # "Chiptune" must not resolve to rap via the "hip" needle.
    raw = (g or "").lower().strip()
    if raw in ("", "<unknown>", "unknown", "other"):
        return None
    tokens = _genre_tokens(raw)
    for needle, family in GENRE_ALIASES:
        if _contains_phrase(tokens, needle.split(" ")):
            return family
    return raw


def normalize_artist(artist):
    # Mirror of MetadataRerank.normalizeArtist: the canonical artist key shared by
    # pairwise scoring, queue spacing, and per-artist caps.
    return WHITESPACE.sub(" ", (artist or "").lower()).strip()


def is_hub(g):
    if not g:
        return False
    return any(tok in HUB_TOKENS for tok in TOKEN_SPLIT.split(g.lower()))


def detect_language(title, artist):
    for ch in (title or "") + (artist or ""):
        o = ord(ch)
        if 0x0400 <= o <= 0x04FF:
            return "ru"
        if 0x3040 <= o <= 0x30FF or 0x4E00 <= o <= 0x9FFF:
            return "ja"
    return "en"


def normalize_title(title):
    s = (title or "").lower()
    s = BRACKETED.sub(" ", s)
    return WHITESPACE.sub(" ", s).strip()


def order_desc(scores, exclude, eligible_finite=False):
    """Mirror SmartChain.order: ascending-index candidates != exclude, stable descending sort."""
    idx = np.argsort(-scores, kind="stable")
    if eligible_finite:
        return [int(i) for i in idx if i != exclude and np.isfinite(scores[i])]
    return [int(i) for i in idx if i != exclude]


class Library:
    """Snapshot equivalent: raw + centered spaces, hub penalty, masks, metadata."""

    def __init__(self, scorer_mode):
        pp = sqlite3.connect(f"{LJ}/smart-mode-work/pp.db")
        store = pd.read_parquet(f"{LJ}/synth-data-2026-07-15/store_dev841.parquet")
        lab = {
            r.track_id: (
                str(r.title),
                str(r.artist),
                (str(r.album) if r.album == r.album else ""),
                (str(r.genre) if r.genre == r.genre else ""),
                (int(r.year) if r.year == r.year else None),
            )
            for r in store.itertuples()
        }

        def dec(b, n):
            return None if b is None else np.frombuffer(b, "<f4", n).astype(np.float32)

        uid, A, T, hasT, ENE = [], [], [], [], []
        for u, e, te, en in pp.execute(
            "SELECT songUid,embedding,textEmbedding,energy FROM TrackEmbeddingEntity"
        ):
            a = dec(e, AUDIO_DIM)
            na = np.linalg.norm(a)
            if na < 1e-3:
                continue
            uid.append(u)
            A.append((a / na).astype("f"))
            ENE.append(float(en) if en is not None else float("nan"))
            t = dec(te, TEXT_DIM)
            if t is not None:
                nt = np.linalg.norm(t)
                T.append((t / nt).astype("f") if nt > 1e-9 else np.zeros(TEXT_DIM, "f"))
                hasT.append(nt > 1e-9)
            else:
                T.append(np.zeros(TEXT_DIM, "f"))
                hasT.append(False)

        self.uid = uid
        self.N = len(uid)
        self.raw = np.stack(A)                      # unit-norm raw audio (SmartTrack input)
        self.rawText = np.stack(T)                  # unit-norm raw text (uncentered)
        self.hasT = np.array(hasT)
        self.energy = np.array(ENE, "f")
        self.lab = [lab.get(u, ("", "", "", "", None)) for u in uid]

        # descriptors (768) keyed by uid, mirrors centeredMaskedCopy input
        z = np.load(f"{LJ}/datagen/text_emb_full.npz")
        self.hasD = np.array([u in z.files for u in uid])
        self.rawDesc = np.zeros((self.N, DESC_DIM), "f")
        for i, u in enumerate(uid):
            if self.hasD[i]:
                v = z[u].astype("f")
                n = np.linalg.norm(v)
                self.rawDesc[i] = v / n if n > 1e-9 else 0

        # centered audio (SmartSnapshot centering: mean over raw, subtract, renormalize)
        mean = self.raw.mean(0)
        c = self.raw - mean
        c /= np.clip(np.linalg.norm(c, axis=1, keepdims=True), 1e-12, None)
        self.centered = c.astype("f")

        self.hub = self._hub_penalty(self.centered)
        self.centeredText = self._centered_masked(self.rawText, self.hasT)
        self.centeredDesc = self._centered_masked(self.rawDesc, self.hasD)

        # metadata
        self.mlang = [detect_language(m[0], m[1]) for m in self.lab]
        self.mgenre = [normalize_genre(m[3]) for m in self.lab]
        self.mhub = [is_hub(m[3]) for m in self.lab]
        self.mtitle = [normalize_title(m[0]) for m in self.lab]
        self.martist = [(m[1] or "") for m in self.lab]
        self.makey = [normalize_artist(m[1]) for m in self.lab]

        # ONNX — all graphs come from this repo's own assets; the state and scorer files are
        # byte-identical to the ones the legacy checkout ships, so nothing points across.
        state_asset = f"{ML}/predictor_state.onnx"
        self.se = ort.InferenceSession(state_asset)
        self.scorer_mode = scorer_mode
        if scorer_mode == "twostage":
            self.sc = ort.InferenceSession(f"{ML}/predictor_scorer_n100.onnx")
            self.res = ort.InferenceSession(f"{ML}/predictor_text_residual_n100_960.onnx")
        elif scorer_mode == "semtext1344":
            self.sc = ort.InferenceSession(f"{ML}/predictor_scorer_n100.onnx")
            self.res = None
        else:  # none
            self.sc = None
            self.res = None

    def _hub_penalty(self, centered):
        n = self.N
        if n > HUB_ANCHOR_SAMPLE:  # not hit for the 841-track library; exact path below
            raise NotImplementedError("sampled hub path unused by this fixture")
        s = centered @ centered.T
        np.fill_diagonal(s, -2.0)
        top = np.sort(s, axis=1)[:, -HUB_TOPK:].mean(1)
        return (top - top.mean()).astype("f")

    def _centered_masked(self, M, mask):
        if not mask.any():
            return None
        m = M[mask].mean(0)
        c = M - m
        c /= np.clip(np.linalg.norm(c, axis=1, keepdims=True), 1e-12, None)
        c[~mask] = 0.0
        return c.astype("f")

    # ---- semantic-z (SemanticZ.poolZ + combine, via chainSemanticZ) ----
    def _pool_z(self, matrix, mask, ref, pool):
        if matrix is None or ref < 0 or not mask[ref]:
            return None
        sims = np.full(len(pool), np.nan, "f")
        valid = 0
        for k, p in enumerate(pool):
            if mask[p]:
                sims[k] = float(matrix[ref] @ matrix[p])
                valid += 1
        if valid < SEM_MIN_VALID:
            return None
        ok = ~np.isnan(sims)
        mu = sims[ok].mean()
        sd = max(float(sims[ok].std()), SEM_STD_FLOOR)
        z = np.clip((sims - mu) / sd, -SEM_Z_CLIP, SEM_Z_CLIP)
        z[~ok] = np.nan
        return z

    def chain_semantic_z(self, ref, pool):
        za = self._pool_z(self.centeredDesc, self.hasD, ref, pool)
        zb = self._pool_z(self.centeredText, self.hasT, ref, pool)
        out = np.zeros(len(pool), "f")
        for k in range(len(pool)):
            vals = [z[k] for z in (za, zb) if z is not None and not np.isnan(z[k])]
            out[k] = sum(vals) / len(vals) if vals else 0.0
        return out

    def centered_cos(self, a, b):
        return float(self.centered[a] @ self.centered[b])

    def desc_cos(self, a, b):
        if self.centeredDesc is None or not (self.hasD[a] and self.hasD[b]):
            return None
        return float(self.centeredDesc[a] @ self.centeredDesc[b])

    # ---- encoder ----
    def encode_state(self, hist_small, seed_med):
        return self.se.run(
            None,
            {
                "history_small": hist_small.astype("f"),
                "history_medium": seed_med[None].astype("f"),
                "history_large": seed_med[None].astype("f"),
                "time_features": TIME[None],
                "session_features": SESS[None],
            },
        )[0]

    # ---- scorer ----
    def text_state(self, rows, weights):
        out = np.zeros(TEXT_DIM, "f")
        tw = 0.0
        for r, w in zip(rows, weights):
            if self.hasT[r]:
                out += self.rawText[r] * w
                tw += w
        if tw > 0:
            n = np.linalg.norm(out)
            if n > 0:
                out /= n
        return out

    def text_centroid(self, rows):
        rr = [r for r in dict.fromkeys(rows) if self.hasT[r]]
        if not rr:
            return np.zeros(TEXT_DIM, "f")
        c = self.rawText[rr].mean(0).astype("f")
        n = np.linalg.norm(c)
        return (c / n) if n > 1e-9 else c

    def score(self, state, pool, hist_rows, cand_audio, cand_text, cand_mask):
        if self.scorer_mode == "none":
            return np.zeros(POOL_SIZE, "f")
        if self.scorer_mode == "semtext1344":
            cent = self.text_centroid(hist_rows)
            st = np.concatenate([state[0], cent]).astype("f")[None]
            cand = np.zeros((1, POOL_SIZE, AUDIO_DIM + TEXT_DIM), "f")
            for i, p in enumerate(pool[:POOL_SIZE]):
                cand[0, i, :AUDIO_DIM] = self.raw[p]
                cand[0, i, AUDIO_DIM:] = self.rawText[p] if self.hasT[p] else 0.0
            return self.sc.run(None, {"state": st, "candidates": cand})[0][0]
        # twostage: acoustic base_scores + text residual (B's shipped stack)
        base = self.sc.run(None, {"state": state, "candidates": cand_audio[None]})[0]
        ts = self.text_state(hist_rows, [1.0] * len(hist_rows))
        out = self.res.run(
            None,
            {
                "base_scores": base,
                "state": state,
                "candidates": cand_audio[None],
                "text_state": ts[None].astype("f"),
                "text_candidates": cand_text[None],
                "text_mask": cand_mask[None],
            },
        )[0]
        return out[0]


def build_pool(lib, seed, state):
    n = lib.N
    anchor = np.array(
        [lib.centered_cos(seed, r) - HUB_PENALTY_BETA * lib.hub[r] for r in range(n)], "f"
    )
    state_scores = np.zeros(n, "f")
    text_scores = np.full(n, -np.inf, "f")
    if state.size >= AUDIO_DIM:
        q = state[0] / max(np.linalg.norm(state[0]), 1e-9)
        state_scores = (lib.raw @ q).astype("f")
        if lib.hasT[seed]:
            seed_text = lib.rawText[seed]
            for r in range(n):
                if lib.hasT[r]:
                    text_scores[r] = float(lib.rawText[r] @ seed_text)
    else:
        state_scores = anchor.copy()

    anchor_order = order_desc(anchor, seed)
    state_order = order_desc(state_scores, seed)
    text_order = order_desc(text_scores, seed, eligible_finite=True)

    pool, seen = [], set()
    i = 0
    while len(pool) < POOL_SIZE and i < len(anchor_order):
        if anchor_order[i] not in seen:
            seen.add(anchor_order[i]); pool.append(anchor_order[i])
        if len(pool) < POOL_SIZE and state_order[i] not in seen:
            seen.add(state_order[i]); pool.append(state_order[i])
        if len(pool) < POOL_SIZE and i < len(text_order) and text_order[i] not in seen:
            seen.add(text_order[i]); pool.append(text_order[i])
        i += 1
    return pool[:POOL_SIZE]


def energy_smoothness(pe, ce):
    if math.isnan(pe) or math.isnan(ce):
        return 1.0
    over = abs(pe - ce) - ENERGY_DEADBAND
    return 1.0 if over <= 0 else max(1.0 - over, ENERGY_FLOOR)


def adjust_multiplier(lib, anchor, cand):
    m = 1.0
    a, c = lib.lab[anchor], lib.lab[cand]
    if a[2] and a[2] == c[2]:
        m -= SAME_ALBUM_PENALTY
    aa, ca = lib.makey[anchor], lib.makey[cand]
    if aa and aa == ca:
        m *= SAME_ARTIST_BONUS
    ga, gc = lib.mgenre[anchor], lib.mgenre[cand]
    if ga is not None and gc is not None:
        m *= SAME_GENRE_BONUS if ga == gc else CROSS_GENRE_MALUS
    if lib.mlang[cand] != lib.mlang[anchor]:
        m *= CROSS_LANGUAGE_PENALTY
    ay, cy = a[4], c[4]
    if ay is not None and cy is not None:
        m *= 1.0 - ERA_DECADE_PENALTY * abs(ay - cy) / 10.0
    return m


def seed_intent_multiplier(seed_genre, support, family_picks, cand_genre):
    if seed_genre is None or support < SEED_GENRE_MIN_POOL_SUPPORT:
        return 1.0
    if family_picks >= SEED_GENRE_PREFIX_TARGET:
        return 1.0
    if cand_genre is None:
        return 1.0
    return 1.0 if cand_genre == seed_genre else SEED_CROSS_GENRE_PENALTY


def build_chain(lib, seed, length):
    """Mirror SmartChain.build cold-start; record states + logits + pool + chain."""
    ck = 4
    seed_med = lib.raw[seed].copy()                         # fixed cold medium/large
    hist = np.zeros((1, ck, AUDIO_DIM + 1), "f")
    for k in range(ck):
        hist[0, k, :AUDIO_DIM] = lib.raw[seed]
        hist[0, k, AUDIO_DIM] = 1.0

    states, logits_rows = [], []
    state = lib.encode_state(hist, seed_med)
    states.append(state[0].copy())

    pool = build_pool(lib, seed, state)
    pool_rows = np.array(pool)

    cand_audio = np.zeros((POOL_SIZE, AUDIO_DIM), "f")
    cand_text = np.zeros((POOL_SIZE, TEXT_DIM), "f")
    cand_mask = np.zeros(POOL_SIZE, "f")
    for i, p in enumerate(pool):
        cand_audio[i] = lib.raw[p]
        if lib.hasT[p]:
            cand_text[i] = lib.rawText[p]
            cand_mask[i] = 1.0

    seed_genre = lib.mgenre[seed]
    support = sum(1 for p in pool if lib.mgenre[p] == seed_genre) if seed_genre else 0

    chain, used = [], set()
    anchor = seed
    text_rows = [seed] * ck
    # recentArtists starts EMPTY: SmartChain deliberately lets the seed lead into one closely
    # related track by the same artist before the spacing window engages (see SmartChain.kt).
    recent = deque()
    seen_titles = {lib.mtitle[seed]} if lib.mtitle[seed] else set()
    artist_plays = {}
    family_picks = 0
    z_seed = lib.chain_semantic_z(seed, pool)

    def eligible(i):
        if i in used:
            return False
        r = pool[i]
        if lib.makey[r] in recent:
            return False
        if lib.mtitle[r] and lib.mtitle[r] in seen_titles:
            return False
        if artist_plays.get(lib.makey[r], 0) >= CHAIN_ARTIST_QUEUE_CAP:
            return False
        return True

    while len(chain) < length:
        z_prev = lib.chain_semantic_z(anchor, pool)
        logits = lib.score(state, pool, text_rows, cand_audio, cand_text, cand_mask)
        logits_rows.append(np.asarray(logits, "f").copy())

        eff_seed, z_seed_active = None, z_seed
        if REANCHOR_ENABLED and seed >= 0 and len(chain) >= REANCHOR_MIN_IDX:
            on_niche = 0
            for i in range(len(pool)):
                if not eligible(i):
                    continue
                r = pool[i]
                fused_d = lib.desc_cos(seed, r)
                ac = lib.centered_cos(seed, r)
                fused = ac if fused_d is None else 0.5 * (ac + fused_d)
                if fused >= REANCHOR_NICHE_COS:
                    on_niche += 1
            if on_niche < REANCHOR_NICHE_MIN:
                cent = np.zeros(AUDIO_DIM, "f")
                for pr in chain:
                    cent += lib.centered[pr]
                cn = np.linalg.norm(cent)
                if cn > 1e-9:
                    cent = cent / cn
                    seed_vec = lib.centered[seed].copy()
                    eff = REANCHOR_SEED_KEEP * seed_vec + (1 - REANCHOR_SEED_KEEP) * cent
                    en = np.linalg.norm(eff)
                    if en > 1e-12:
                        eff = eff / en
                    eff_seed = eff.astype("f")
                    med, best = -1, -1e30
                    for pr in chain:
                        d = float(cent @ lib.centered[pr])
                        if d > best:
                            best, med = d, pr
                    if med >= 0:
                        z_seed_active = lib.chain_semantic_z(med, pool)

        best_i, best_score = -1, -1e30
        for i in range(len(pool)):
            if not eligible(i):
                continue
            r = pool[i]
            s = SCORER_SQUASH * math.tanh(logits[i] / SCORER_TEMP)
            s += COSINE_BLEND_WEIGHT * lib.centered_cos(anchor, r)
            if eff_seed is None:
                s += CHAIN_SEED_GRAVITY * lib.centered_cos(seed, r)
            else:
                s += CHAIN_SEED_GRAVITY * float(eff_seed @ lib.centered[r])
            s += SEM_CHAIN_SEED_GRAVITY * z_seed_active[i] + SEM_CHAIN_PREV_BLEND * z_prev[i]
            m = min(max(adjust_multiplier(lib, anchor, r), MULT_MIN), MULT_MAX)
            m *= seed_intent_multiplier(seed_genre, support, family_picks, lib.mgenre[r])
            # recency: cold history is empty -> multiplier 1 for every track (no-op)
            if lib.mhub[r] and not lib.mhub[anchor]:
                m = max(m * HUB_CHAIN_DAMP, MULT_MIN)
            m = max(m * energy_smoothness(lib.energy[anchor], lib.energy[r]), MULT_MIN)
            s += math.log(m)
            if s > best_score:
                best_score, best_i = s, i
        if best_i < 0:
            break

        picked = pool[best_i]
        chain.append(picked)
        used.add(best_i)
        if lib.mgenre[picked] == seed_genre and seed_genre is not None:
            family_picks += 1
        if lib.mtitle[picked]:
            seen_titles.add(lib.mtitle[picked])
        artist_plays[lib.makey[picked]] = artist_plays.get(lib.makey[picked], 0) + 1
        recent.append(lib.makey[picked])
        while len(recent) > CHAIN_ARTIST_SPACING:
            recent.popleft()
        anchor = picked

        if len(chain) >= length:
            break
        hist[0, :ck - 1] = hist[0, 1:]
        hist[0, ck - 1, :AUDIO_DIM] = lib.raw[picked]
        hist[0, ck - 1, AUDIO_DIM] = 1.0
        text_rows = text_rows[1:] + [picked]
        state = lib.encode_state(hist, seed_med)
        states.append(state[0].copy())

    return states, logits_rows, pool, chain


def find_seed(lib, sub):
    for i in range(lib.N):
        t, a = lib.lab[i][0], lib.lab[i][1]
        if sub.lower() in (a + " " + t).lower():
            return i
    return -1


SEEDS = [
    ("estrada_suruceanu", "Suruceanu"),
    ("estrada_dumitras", "Dumitraș"),
    ("ru_pop_gurtskaya", "Гурцкая"),
    ("ru_pop_gayazov", "GAYAZOV"),
    ("soviet_synthpop_kombinacia", "Комбинация"),
    ("anime_miku", "初音"),
    ("phonk_semsa", "SEM SA"),
    ("western_abba", "ABBA"),
    ("hiphop_eminem", "Eminem"),
    ("filmscore_djawadi", "Djawadi"),
]


def w_f32(path, arr):
    np.asarray(arr, "<f4").tofile(path)


def w_i32(path, arr):
    np.asarray(arr, "<i4").tofile(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out",
                    default=f"{REPO}/tools/research/output/parity-fixture")
    ap.add_argument("--scorer", choices=["twostage", "semtext1344", "none"],
                    default="twostage")
    ap.add_argument("--length", type=int, default=20)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    lib = Library(args.scorer)
    print(f"library: N={lib.N}  scorer={args.scorer}  length={args.length}")

    N = lib.N
    with open(os.path.join(args.out, "dims.txt"), "w") as f:
        f.write(f"N={N}\n")
    w_f32(os.path.join(args.out, "audio.f32"), lib.raw.reshape(-1))
    w_f32(os.path.join(args.out, "text.f32"), lib.rawText.reshape(-1))
    w_f32(os.path.join(args.out, "desc.f32"), lib.rawDesc.reshape(-1))
    open(os.path.join(args.out, "hasT.u8"), "wb").write(
        bytes(1 if x else 0 for x in lib.hasT))
    open(os.path.join(args.out, "hasD.u8"), "wb").write(
        bytes(1 if x else 0 for x in lib.hasD))
    w_f32(os.path.join(args.out, "energy.f32"), lib.energy)
    w_f32(os.path.join(args.out, "time.f32"), TIME)
    w_f32(os.path.join(args.out, "sess.f32"), SESS)
    with open(os.path.join(args.out, "meta.tsv"), "w") as f:
        for m in lib.lab:
            year = "" if m[4] is None else str(m[4])
            # strip tabs/newlines from free text so the TSV stays one row per track
            fields = [str(x).replace("\t", " ").replace("\n", " ") for x in
                      (m[0], m[1], m[2], m[3])] + [year]
            f.write("\t".join(fields) + "\n")

    seed_lines = []
    for tag, q in SEEDS:
        si = find_seed(lib, q)
        if si < 0:
            print(f"  {tag}: seed '{q}' NOT FOUND, skipped")
            continue
        states, logits, pool, chain = build_chain(lib, si, args.length)
        w_f32(os.path.join(args.out, f"{tag}.states.f32"), np.stack(states).reshape(-1))
        w_f32(os.path.join(args.out, f"{tag}.logits.f32"), np.stack(logits).reshape(-1))
        w_i32(os.path.join(args.out, f"{tag}.pool.i32"), pool)
        w_i32(os.path.join(args.out, f"{tag}.chain.i32"), chain)
        seed_lines.append(f"{tag}\t{si}\t{len(states)}\t{len(logits)}\t{len(pool)}\t{len(chain)}")
        print(f"  {tag}: seed row {si}  states={len(states)} hops={len(logits)} "
              f"pool={len(pool)} chain={len(chain)}")
    with open(os.path.join(args.out, "seeds.tsv"), "w") as f:
        f.write("\n".join(seed_lines) + "\n")
    print(f"wrote fixture to {args.out}")
    print("run: SMART_PARITY_FIXTURE=%s ./gradlew :core:smart:testAndroidHostTest "
          "--tests '*SmartChainParityTest*'" % args.out)


if __name__ == "__main__":
    main()
