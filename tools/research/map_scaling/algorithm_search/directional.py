"""Directional mean shift, von Mises kernel, fixed (non-blurring) data.

Zhang & Chen (2021), https://jmlr.org/papers/v22/20-1194.html
Independent memory-bounded implementation of the published update, not copied
source. Uses float64, all rows as seeds, stable exponent rescaling, and never
stores the full trajectory. Bandwidth h = 1/sqrt(kappa).
"""
import time
import numpy as np
from scipy.special import logsumexp


def unit(x):
    norms = np.linalg.norm(x, axis=1, keepdims=True)
    if np.any(norms < 1e-12):
        raise ValueError('Degenerate direction')
    return x / norms


def step(y, x, kappa):
    logits = kappa * (y @ x.T)
    weights = np.exp(logits - logits.max(axis=1, keepdims=True))
    return unit(weights @ x)


def density(y, x, kappa):
    # Constants cancel in monotonicity checks at fixed dimension and bandwidth.
    return logsumexp(kappa * (y @ x.T), axis=1) - np.log(len(x))


def mode_labels(y, tol=1e-4):
    # Match the authors' source-order representative grouping, including their
    # last-match rule. The tolerance is cosine distance, not Euclidean distance.
    representatives = []
    labels = np.empty(len(y), dtype=int)
    for i, row in enumerate(y):
        matches = np.flatnonzero(1 - y[representatives] @ row <= tol)
        if len(matches):
            labels[i] = matches[-1]
        else:
            labels[i] = len(representatives)
            representatives.append(i)
    return labels, np.asarray(representatives, dtype=int)


def fit(x, kappa, initial=None, eps=1e-9, max_iter=1000):
    started = time.monotonic()
    x = unit(np.asarray(x, dtype=np.float64))
    y = x.copy() if initial is None else unit(np.asarray(initial, dtype=np.float64))
    trace = []
    for iteration in range(1, max_iter + 1):
        new = step(y, x, kappa)
        delta = np.maximum(0, 1 - np.einsum('ij,ij->i', new, y))
        y = new
        if iteration == 1 or iteration % 25 == 0 or delta.max() <= eps:
            trace.append({'iteration': iteration, 'max_cosine_step': float(delta.max())})
        if delta.max() <= eps:
            break
    labels, representatives = mode_labels(y)
    return labels, y, {'kappa': kappa, 'h': float(1 / np.sqrt(kappa)),
        'seconds': time.monotonic()-started, 'iterations': iteration,
        'converged': bool(delta.max() <= eps), 'max_cosine_step': float(delta.max()),
        'modes': len(representatives), 'trace': trace}
