#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""Offline RBJ gain-interaction probe, NOT a shipped EQ or the full ACGE paper design.

Fits existing Q=1.4 filters to either band centres or centres + log midpoints.
Static headroom and dynamic limiting are excluded, to isolate tonal response error.
Only Python's standard library is required. No music, personal data or network access.
"""
import argparse
import cmath
import csv
import json
import math
import random
import statistics
from pathlib import Path

FREQUENCIES = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
PRESETS = {
    'flat': [0] * 10,
    'bass': [7, 6, 4.5, 2.5, 0, 0, 0, 0, 0, 0],
    'treble': [0, 0, 0, 0, 0, 1, 2.5, 4.5, 6, 7],
    'vocal': [-2, -1.5, 0, 2, 4, 5, 4, 2, 0, -1],
    'electronic': [5, 3.5, 1, 0, -1.5, 1, 3, 4.5, 5, 3.5],
}


def band_response(rate, centre, gain, points):
    a = 10 ** (gain / 40)
    w = 2 * math.pi * centre / rate
    alpha = math.sin(w) / (2 * 1.4)
    c = math.cos(w)
    result = []
    for frequency in points:
        z = cmath.exp(-2j * math.pi * frequency / rate)
        h = ((1 + alpha * a) - 2 * c * z + (1 - alpha * a) * z * z) / (
            (1 + alpha / a) - 2 * c * z + (1 - alpha / a) * z * z)
        result.append(20 * math.log10(abs(h)))
    return result


def response(rate, gains, points):
    bands = [band_response(rate, f, g, points) for f, g in zip(FREQUENCIES, gains)]
    return [sum(band[i] for band in bands) for i in range(len(points))]


def solve(matrix, vector):
    """Pivoted elimination; damping in fit() keeps normal equations nonsingular."""
    rows = [list(row) + [value] for row, value in zip(matrix, vector)]
    size = len(rows)
    for column in range(size):
        pivot = max(range(column, size), key=lambda row: abs(rows[row][column]))
        rows[column], rows[pivot] = rows[pivot], rows[column]
        divisor = rows[column][column]
        if abs(divisor) < 1e-12:
            raise ValueError('Singular gain-fit system')
        rows[column] = [value / divisor for value in rows[column]]
        for row in range(size):
            if row != column:
                factor = rows[row][column]
                rows[row] = [a - factor * b for a, b in zip(rows[row], rows[column])]
    return [row[-1] for row in rows]


def fit(rate, target, midpoints):
    points, desired = [], []
    for index, frequency in enumerate(FREQUENCIES):
        points.append(frequency)
        desired.append(target[index])
        if midpoints and index < 9:
            points.append(math.sqrt(frequency * FREQUENCIES[index + 1]))
            desired.append((target[index] + target[index + 1]) / 2)
    gains = list(target)
    for _ in range(8):
        actual = response(rate, gains, points)
        residual = [a - b for a, b in zip(desired, actual)]
        columns = []
        for frequency, gain in zip(FREQUENCIES, gains):
            low = band_response(rate, frequency, gain - 0.01, points)
            high = band_response(rate, frequency, gain + 0.01, points)
            columns.append([(h - l) / 0.02 for l, h in zip(low, high)])
        normal = [[sum(a * b for a, b in zip(x, y)) + (0.001 if i == j else 0)
                   for j, y in enumerate(columns)] for i, x in enumerate(columns)]
        vector = [sum(a * b for a, b in zip(column, residual)) for column in columns]
        delta = solve(normal, vector)
        old_error = sum(x * x for x in residual)
        accepted = False
        for fraction in [1, 0.5, 0.25, 0.125]:
            candidate = [max(-24, min(24, g + fraction * d)) for g, d in zip(gains, delta)]
            error = sum((a - b) ** 2 for a, b in zip(desired, response(rate, candidate, points)))
            if error < old_error:
                gains = candidate
                accepted = True
                break
        if not accepted or max(abs(d) for d in delta) < 1e-5:
            break
    return gains


def errors(rate, gains, target):
    centres = response(rate, gains, FREQUENCIES)
    mids = [math.sqrt(a * b) for a, b in zip(FREQUENCIES, FREQUENCIES[1:])]
    middle = response(rate, gains, mids)
    return max(abs(a - b) for a, b in zip(centres, target)), max(
        abs(a - (b + c) / 2) for a, b, c in zip(middle, target, target[1:]))


def run(count):
    curves = dict(PRESETS)
    curves.update({'all_plus_6': [6] * 10, 'all_plus_12': [12] * 10,
                   'all_minus_12': [-12] * 10,
                   'zigzag': [12 if i % 2 else -12 for i in range(10)]})
    for index in range(10):
        curves[f'single_{FREQUENCIES[index]}'] = [6 if i == index else 0 for i in range(10)]
    rng = random.Random(20261014)
    for index in range(count):
        curves[f'random_{index:03d}'] = [rng.uniform(-12, 12) for _ in range(10)]
    rows = []
    for rate in [44100, 48000]:
        for name, target in curves.items():
            row = {'rate_hz': rate, 'curve': name}
            for mode, gains in [('current', target), ('centre_fit', fit(rate, target, False)),
                                ('midpoint_fit', fit(rate, target, True))]:
                centre_error, mid_error = errors(rate, gains, target)
                row[f'{mode}_centre_error_db'] = round(centre_error, 6)
                row[f'{mode}_midpoint_error_db'] = round(mid_error, 6)
                row[f'{mode}_largest_filter_gain_db'] = round(max(abs(g) for g in gains), 6)
            rows.append(row)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--random-curves', type=int, default=100)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.random_curves < 0:
        parser.error('--random-curves must be non-negative')
    rows = run(args.random_curves)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    summary = {'curves_per_rate': len(rows) // 2, 'sample_rates_hz': [44100, 48000], 'seed': 20261014,
               'notes': 'Numerical tonal response only; excludes bass shelf, headroom, limiter and listening.'}
    for mode in ['current', 'centre_fit', 'midpoint_fit']:
        for location in ['centre', 'midpoint']:
            values = [row[f'{mode}_{location}_error_db'] for row in rows]
            summary[f'{mode}_{location}_median_max_error_db'] = round(statistics.median(values), 4)
            summary[f'{mode}_{location}_worst_error_db'] = max(values)
    print(json.dumps(summary, indent=2))
    print('all_plus_6 at 48 kHz:', next(row for row in rows if row['curve'] == 'all_plus_6' and row['rate_hz'] == 48000))


if __name__ == '__main__':
    main()
