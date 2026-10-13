#!/usr/bin/env python3
"""Add a linear compression control, verify metrics, and plot anonymized pilot layouts."""
from pathlib import Path
import json
import os
import subprocess
import sys
import numpy as np
from sklearn.decomposition import PCA
from sklearn.manifold import trustworthiness

os.environ.setdefault('MPLCONFIGDIR', '/private/tmp/latentjam-parametric-map-mpl')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from parametric_map_pilot import unit, reference, quality, interpolate


def main():
    out = Path(sys.argv[1])
    source = Path(sys.argv[2])
    cp = Path(sys.argv[3]).read_text().strip()
    data = np.load(out / 'private-input-and-splits.npz')
    x, train, val, test = [data[key] for key in ['x', 'train', 'val', 'test']]
    sets = {'train': train, 'validation': val, 'test': test}
    refs = {key: reference(x, q, train) for key, q in sets.items()}
    remap = {int(old): new for new, old in enumerate(data['original_rows'])}
    playlists = [[remap[i] for i in pl['members'] if i in remap]
                 for pl in json.loads((source / 'private-playlists.json').read_text())]
    playlists = [g for g in playlists if len(g) >= 4]
    report = json.loads((out / 'summary.json').read_text())
    latent = unit(PCA(n_components=64, svd_solver='full').fit(x[train]).transform(x))
    inp, dest = out / 'linear64-train.f32', out / 'linear64-train-2d.f32'
    latent[train].astype('<f4').tofile(inp)
    result = subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java', '-Xmx1g', '-cp', str(out) + ':' + cp,
                             'MapProjectionProbe', str(inp), str(dest), '64'], check=True, capture_output=True, text=True)
    coords = np.fromfile(dest, dtype='<f4').reshape(len(train), 2)
    y = interpolate(latent, train, coords)
    np.save(out / 'linear64-final2d.npy', y)
    report['baselines']['pca64_train_fit_plus_tsne_knn5'] = {
        key: quality(y, q, train, refs[key], playlists) for key, q in sets.items()}
    report['linear64_representation'] = {
        key: quality(latent, q, train, refs[key], playlists) for key, q in sets.items()}
    # Independent implementation sanity check; no training or checkpoint changes.
    expected = trustworthiness(x[train], y[train], n_neighbors=15)
    measured = report['baselines']['pca64_train_fit_plus_tsne_knn5']['train']['cross_reference_trustworthiness_at15']
    assert abs(expected - measured) < 1e-6, (expected, measured)
    report['verification'] = {'training_trustworthiness_sklearn': expected,
                              'training_trustworthiness_ours': measured,
                              'max_difference': abs(expected - measured),
                              'note': 'Linear64 comparator added after first pilot results; evaluation is exploratory, not a new untouched test.'}
    display_models = []
    for dims in [2, 64]:
        candidates = [m for m in report['models'] if m['output_dim'] == dims]
        display_models.append(max(candidates, key=lambda m: m['final_2d']['validation']['neighbor_recall_at15']))
    labels = np.full(len(x), -1)
    worlds = json.loads((source / 'private-regions-audio25-meta75.json').read_text())['worlds']
    for i, world in enumerate(worlds):
        for old in world['members']:
            if old in remap:
                labels[remap[old]] = i
    palettes = plt.get_cmap('tab20')
    colors = np.array([palettes(v % 20) if v >= 0 else (.45,.45,.45,1) for v in labels])
    cases = [
        ('Текущая проекция + kNN', np.load(out / 'production-train-knn5.npy'),
         report['baselines']['production_tsne_train_plus_knn5']['test']),
        ('MLP → 2D', np.load(out / f"mlp2-seed{display_models[0]['seed']}-final2d.npy"),
         display_models[0]['final_2d']['test']),
        ('MLP → 64D → проекция + kNN', np.load(out / f"mlp64-seed{display_models[1]['seed']}-final2d.npy"),
         display_models[1]['final_2d']['test']),
        ('PCA → 64D → проекция + kNN', y,
         report['baselines']['pca64_train_fit_plus_tsne_knn5']['test'])]
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11})
    fig, axes = plt.subplots(2, 2, figsize=(13, 10))
    fig.patch.set_facecolor('#faf9f6')
    for ax, (name, points, metric) in zip(axes.flat, cases):
        ax.set_facecolor('#ffffff')
        ax.scatter(points[train,0], points[train,1], s=9, color='#b9b9b9', alpha=.6, linewidths=0)
        ax.scatter(points[test,0], points[test,1], s=20, c=colors[test], edgecolors='white', linewidths=.25, alpha=.9)
        ax.set_title(f"{name}\nСохранено соседей: {metric['neighbor_recall_at15']:.1%}", loc='left', pad=12)
        ax.set_aspect('equal', adjustable='datalim')
        ax.set_xticks([]); ax.set_yticks([])
        for spine in ax.spines.values(): spine.set_color('#dfded9')
    fig.suptitle('Прямая нейросетевая карта: проверка на новых исполнителях', fontsize=18, x=.05, ha='left', y=.985)
    fig.text(.05, .935, '676 обучающих треков — серые; 312 тестовых — цветные. 118 треков использованы для выбора модели.', fontsize=11)
    fig.text(.05, .035, 'Соседи: 15 ближайших среди обучающих треков, эталон — исходное пространство 1344D.\n'
             'Цвета — прежние области, только для иллюстрации. Нейросети выбраны по validation, не по test.\n'
             'Один снимок библиотеки. Это проверка геометрии, а не сходства музыки на слух.', fontsize=10, color='#444444')
    fig.tight_layout(rect=[.02,.10,.99,.91], h_pad=3, w_pad=3)
    fig.savefig(out / 'parametric-map-comparison.png', dpi=170, facecolor=fig.get_facecolor())
    fig.savefig(out / 'parametric-map-comparison.svg', facecolor=fig.get_facecolor())
    plt.close(fig)
    report['plot_model_selection'] = {'criterion': 'Highest final2d validation recall within each family',
                                    'seeds': {str(m['output_dim']): m['seed'] for m in display_models}}
    (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'linear64_test': report['baselines']['pca64_train_fit_plus_tsne_knn5']['test'],
                      'linear64_representation': report['linear64_representation']['test'],
                      'verification': report['verification']}, indent=2))


if __name__ == '__main__':
    main()
