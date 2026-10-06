"""Fit the chain's learned score correction (ChainTuning.rerankWeights) to the judge's rankings.

    python fit_rerank.py --ranked OUT/ranked.jsonl [--ranked ...] --out weights.json

Plackett-Luce over the candidates the judge was shown, on the chain's base score and the 12 Rerank features
(standardised for the fit, L2 on the feature weights only). The penalty is chosen by leave-one-library-out
log-likelihood of the judge's first choice. The deployed weights are the feature weights divided by the base
score's weight, so the chain's argmax of base score + correction is the model's.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
from scipy.optimize import minimize

FEATURES = ("audio_previous", "audio_reference", "audio_seed", "style_previous", "style_reference", "style_seed",
            "text_previous", "text_seed", "scorer", "same_artist", "style_known", "in_neighbourhood")


def load(paths):
    hops = []
    for path in paths:
        for line in open(path):
            h = json.loads(line)
            x = np.array([[c[1], *c[3:]] for c in h["candidates"]], float)  # base score, then the features
            hops.append(dict(lib=h["lib"], x=x, ranking=h["ranking"]))
    return hops


def nll(theta, hops, lam, top):
    total, grad = 0.0, np.zeros_like(theta)
    for h in hops:
        u = h["x"] @ theta
        alive = np.ones(len(u), bool)
        for r in h["ranking"][:top]:
            m = u[alive].max()
            e = np.exp(u - m) * alive
            p = e / e.sum()
            total -= u[r] - m - np.log(e.sum())
            grad -= h["x"][r] - p @ h["x"]
            alive[r] = False
    reg = theta.copy()
    reg[0] = 0.0
    return total / len(hops) + lam * (reg @ reg), grad / len(hops) + 2 * lam * reg


def fit(hops, lam, top=3):
    theta0 = np.zeros(hops[0]["x"].shape[1])
    theta0[0] = 1.0
    res = minimize(nll, theta0, args=(hops, lam, top), jac=True, method="L-BFGS-B")
    return res.x


def first_choice(theta, hops):
    """Mean log-likelihood and accuracy of the judge's first choice."""
    ll, hit = [], []
    for h in hops:
        u = h["x"] @ theta
        u = u - u.max()
        ll.append(u[h["ranking"][0]] - np.log(np.exp(u).sum()))
        hit.append(int(np.argmax(u)) == h["ranking"][0])
    return float(np.mean(ll)), float(np.mean(hit))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ranked", action="append", required=True, type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--lambdas", default="0.01,0.1,1,10")
    a = ap.parse_args()
    hops = load(a.ranked)
    allx = np.concatenate([h["x"] for h in hops])
    mu, sd = allx.mean(0), allx.std(0) + 1e-9
    mu[0], sd[0] = 0.0, 1.0  # the base score keeps its units
    for h in hops:
        h["x"] = (h["x"] - mu) / sd
    libs = sorted({h["lib"] for h in hops})
    base = np.zeros(len(mu))
    base[0] = 1.0
    print(f"{len(hops)} ranked hops from {', '.join(libs)}; shown candidates per hop {np.mean([len(h['x']) for h in hops]):.1f}")
    scores = {}
    for lam in [float(x) for x in a.lambdas.split(",")]:
        ll, acc, ll0, acc0 = [], [], [], []
        for held in libs:
            train = [h for h in hops if h["lib"] != held]
            test = [h for h in hops if h["lib"] == held]
            theta = fit(train, lam)
            l, c = first_choice(theta, test)
            l0, c0 = first_choice(base, test)
            ll.append(l); acc.append(c); ll0.append(l0); acc0.append(c0)
        scores[lam] = np.mean(ll)
        print(f"  lambda {lam:g}: held-out library log-likelihood {np.mean(ll):.3f} (base score alone {np.mean(ll0):.3f}), "
              f"first choice hit {np.mean(acc) * 100:.1f} % (base score alone {np.mean(acc0) * 100:.1f} %)")
    lam = max(scores, key=scores.get)
    theta = fit(hops, lam)
    raw = theta / sd  # the feature means only shift every candidate of a hop alike
    if raw[0] <= 0:
        raise SystemExit("the base score lost its positive weight; no correction to deploy")
    weights = raw[1:] / raw[0]
    print(f"chosen lambda {lam:g}; base score weight {raw[0]:.3f}")
    for name, w in zip(FEATURES, weights):
        print(f"  {name:16s} {w:+.3f} score units")
    a.out.write_text(json.dumps(dict(weights=[float(w) for w in weights], features=FEATURES, lambda_=lam,
                                     base_weight=float(raw[0]), hops=len(hops), libraries=libs), indent=2) + "\n")


if __name__ == "__main__":
    main()
