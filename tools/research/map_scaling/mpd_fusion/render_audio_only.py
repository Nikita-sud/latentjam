#!/usr/bin/env python3
"""Private human-readable region compositions and a full-library audio comparison."""
import json
import os
from pathlib import Path
import sys
import numpy as np
os.environ.setdefault('MPLCONFIGDIR','/private/tmp/latentjam-mpd-fusion-mpl')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

p=Path(sys.argv[1]);d=json.loads((p/'private-region-details.json').read_text());s=json.loads((p/'audio-only-summary.json').read_text())
a=s['metrics']['audio_only'];c=s['metrics']['current_1344']
lines=['# Области только по звуку — 13 октября 2026','',
       'Проверка на всех 1 107 треках сохранённой библиотеки. Кластеризация использует только 960-мерные звуковые векторы. Имена и последующее выделение мемов/звуковых эффектов используют прежние теги и семантические правила приложения. Это не новая оценка на слух или через MOSS.','',
       'Получилось 16 исходных кластеров и 18 итоговых областей. В них вошли 879 треков, вне областей остались 228. Контроль текущего гибрида в точности воспроизвёл прежние 17 областей / 974 трека.','',
       '| Показатель | Текущий гибрид | Только звук |','|---|---:|---:|',
       f"| Треков в областях | {c['assigned_tracks']} | {a['assigned_tracks']} |",
       f"| Соседи из того же плейлиста, @15 | {c['playlist_at15']:.1%} | {a['playlist_at15']:.1%} |",
       f"| Соседи того же исполнителя, @15 | {c['same_artist_at15']:.1%} | {a['same_artist_at15']:.1%} |",
       f"| Покрытие ведущим жанром внутри областей | {c['dominant_genre_coverage']:.1%} | {a['dominant_genre_coverage']:.1%} |",
       f"| Средний косинус звуковых векторов внутри областей | {c['within_region_audio_pair_cosine']:.3f} | {a['within_region_audio_pair_cosine']:.3f} |",'',
       'Звуковой косинус посчитан в одной и той же центрированной звуковой системе; он закономерно выигрывает у метода, который оптимизирует именно её. Это не независимое доказательство качества на слух. Жанровые/плейлистовые метрики, наоборот, ближе к сигналу метаданных.','',
       'Названия могут вводить в заблуждение: «Anime» включает и электронную/ambient музыку; «Soundtrack + Rap • Mix» содержит явно разнородные центральные примеры. Две области Rock / Pop не являются одинаковыми наборами — их центры различаются.','',
       '## Состав областей','', '| № | Название в приложении | Треков | Ведущие семейства тегов | Центральные примеры |','|---:|---|---:|---|---|']
for i,w in enumerate(d['audio_only'],1):
    genres=', '.join(f'{g}: {n}' for g,n in w['top_genre_families'][:3])
    examples='<br>'.join(f"{r.get('artist') or '?'} — {r.get('title') or '?'}" for r in w['central_tracks'][:3])
    lines.append(f"| {i} | {w['name']} | {w['size']} | {genres} | {examples.replace('|','/')} |")
lines+=['','Счётчики жанров могут перекрываться: у трека бывает несколько тегов. «Центральные» здесь означает близость к центру области в пространстве векторов, а не экспертный выбор самых характерных песен.','',
        'Вывод: чистый звук даёт содержательные области и меньше привязывается к исполнителю, но некоторые группы труднее назвать и больше треков отбрасывается при текущих порогах. Для выбора единственного режима карты нужна отдельная оценка прослушиванием; автоматически объявлять гибрид или звук победителем по «своей» метрике было бы неверно.','']
(p/'audio-only-regions-2026-10-13.md').write_text('\n'.join(lines))
colors=np.full((1107,4),(.6,.6,.6,1.));palette=plt.get_cmap('tab20')
for i,w in enumerate(d['current_1344']):colors[w['members']]=palette(i%20)
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11})
fig,axes=plt.subplots(1,2,figsize=(14,7));fig.patch.set_facecolor('#fbfaf7')
for ax,name,title in zip(axes,['current_1344','audio_only'],['Текущий гибрид: 25% звук / 75% метаданные','Кластеризация только по звуку']):
 xy=np.fromfile(p/f'private-{name}-2d.f32',dtype='<f4').reshape(-1,2);ax.scatter(xy[:,0],xy[:,1],s=12,c=colors,alpha=.85,linewidths=0)
 m=s['metrics'][name];ax.set_title(f"{title}\n{m['regions']} областей · {m['assigned_tracks']} треков в областях",loc='left',pad=15)
 ax.set_aspect('equal',adjustable='datalim');ax.set_xticks([]);ax.set_yticks([])
 for spine in ax.spines.values():spine.set_color('#dfdcd5')
fig.suptitle('Что меняется, если строить карту по звуку',fontsize=18,x=.04,ha='left',y=.98)
fig.text(.04,.045,'Одинаковые 1 107 треков и алгоритм проекции. Цвета в обеих панелях — области текущего гибрида.\nСмешение цветов справа показывает изменение соседства, а не автоматически худшее звучание.',fontsize=10,color='#555')
fig.tight_layout(rect=[.02,.12,.99,.91],w_pad=3);fig.savefig(p/'audio-only-map-comparison.png',dpi=170,facecolor=fig.get_facecolor());plt.close(fig)
print('Private region report and audio-only-map-comparison.png saved.')
