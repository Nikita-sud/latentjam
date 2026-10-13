#!/usr/bin/env python3
"""Render actual three-weight layouts, with fixed control-region colors."""
import json,sys
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

p=Path(sys.argv[1]);g=p/'geometry';summary=json.loads((p/'geometry-summary.json').read_text())
worlds=json.loads((g/'private-regions-a25.json').read_text())['worlds'];n=summary['metrics']['a25']['tracks']
colors=np.tile([.64,.64,.64,1.],(n,1));cmap=plt.get_cmap('tab20')
for j,w in enumerate(worlds):colors[w['members']]=cmap(j)
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':12})
fig,axes=plt.subplots(1,3,figsize=(16,6),facecolor='#faf9f6')
for ax,v in zip(axes,['a25','a50','a75']):
    xy=np.fromfile(g/f'private-{v}-2d.f32',dtype='<f4').reshape(n,2);m=summary['metrics'][v]
    ax.scatter(xy[:,0],xy[:,1],c=colors,s=9,alpha=.8,linewidths=0)
    ax.set_title(f'{v[1:]}% звука\n{m["regions"]} областей · {m["assigned"]} трека внутри\n{m["outside"]} вне областей',loc='left',fontsize=14,pad=12)
    ax.set_xticks([]);ax.set_yticks([]);ax.set_aspect('equal',adjustable='datalim')
    for spine in ax.spines.values():spine.set_color('#ddd9d1')
fig.suptitle('Одна библиотека, три веса звука',x=.04,ha='left',fontsize=22,y=.98)
fig.text(.04,.055,'Цвета везде обозначают области исходного варианта 25%. Все 1 107 треков и алгоритм проекции одинаковы.\nИзменение формы и смешение цветов не являются оценкой музыкального качества.',fontsize=11,color='#666')
fig.subplots_adjust(left=.035,right=.985,top=.73,bottom=.18,wspace=.12)
fig.savefig(p/'weight-map-comparison.png',dpi=150);fig.savefig(p/'weight-map-comparison.svg');plt.close(fig)
print('Rendered weight-map-comparison.png/.svg')
