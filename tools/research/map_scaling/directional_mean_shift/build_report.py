#!/usr/bin/env python3
"""Private inspectable report and fixed-coordinate figure; no private Git output."""
import argparse,json,html
from collections import Counter
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import to_hex
from scipy.optimize import linear_sum_assignment

def read(p):return json.loads(p.read_text())
def display(name):return 'Текущий алгоритм' if name=='baseline' else 'DMS · κ='+name[3:].replace('p','.')
def track(row):return f'{row.get("artist") or "Без исполнителя"} — {row.get("title") or "Без названия"}'
def pct(x):return f'{100*x:.1f}%'
def main():
    ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);ap.add_argument('--map-coordinates',type=Path,required=True);a=ap.parse_args();p=a.root;g=p/'geometry'
    results=read(p/'results.json');metrics=results['metrics'];regions=read(p/'private-region-details.json');rows=read(g/'private-cooked-metadata.json');n=len(rows)
    xy=np.fromfile(a.map_coordinates,dtype='<f4').reshape(n,2)
    assert np.isfinite(xy).all();xy.tofile(g/'private-fixed-map-2d.f32')
    base=regions['baseline'];cmap=plt.get_cmap('tab20');palette=[to_hex(cmap(i)) for i in range(20) if i not in [14,15]];colors={};labs={}
    for name,worlds in regions.items():
        overlap=np.array([[len(set(a['members'])&set(b['members'])) for b in base] for a in worlds])
        rr,cc=linear_sum_assignment(-overlap) if len(worlds) else ([],[])
        col=[to_hex(plt.get_cmap('hsv')((j*.6180339)%1)) for j in range(len(worlds))]
        for i,j in zip(rr,cc):col[i]=palette[j]
        if name=='baseline':col=[palette[j] for j in range(len(worlds))]
        colors[name]=col;labels=np.full(n,-1,dtype=int)
        for j,w in enumerate(worlds):assert np.all(labels[w['members']]==-1);labels[w['members']]=j
        labs[name]=labels.tolist()
    plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11})
    fig,axes=plt.subplots(2,2,figsize=(14,11),facecolor='#faf9f6')
    for ax,name in zip(axes.flat,['baseline','dms8','dms8p5','dms9']):
        l=np.array(labs[name]);outside=l<0
        ax.scatter(xy[outside,0],xy[outside,1],color='#c2c2c2',s=9,alpha=.65,linewidths=0)
        for j,w in enumerate(regions[name]):
            z=xy[w['members']];ax.scatter(z[:,0],z[:,1],c=colors[name][j],s=11,alpha=.9,linewidths=0)
        m=metrics[name];ax.set_title(f'{display(name)}\n{m["regions"]} областей · {m["assigned"]} внутри · {m["outside"]} вне областей',loc='left',fontsize=13,pad=12)
        margin=.04*np.ptp(xy,axis=0);ax.set_xlim(xy[:,0].min()-margin[0],xy[:,0].max()+margin[0]);ax.set_ylim(xy[:,1].min()-margin[1],xy[:,1].max()+margin[1]);ax.set_aspect('equal');ax.set_xticks([]);ax.set_yticks([])
        for spine in ax.spines.values():spine.set_color('#ddd9d1')
    fig.suptitle('Directional mean-shift: та же карта, другой состав областей',fontsize=19,x=.045,ha='left',y=.98)
    fig.text(.045,.035,'1 107 треков · звук / метаданные 25 / 75 · координаты одинаковы во всех панелях\nЦвет показывает собственные области каждого варианта; серые точки не вошли в области.\nПохожие цвета сопоставлены по пересечению составов. Картинка не измеряет музыкальное качество.',fontsize=10,color='#555')
    fig.subplots_adjust(left=.04,right=.97,top=.89,bottom=.13,hspace=.3,wspace=.16)
    fig.savefig(p/'comparison.png',dpi=160,facecolor=fig.get_facecolor());fig.savefig(p/'comparison.svg',facecolor=fig.get_facecolor());plt.close(fig)
    table=['| Вариант | Исходных групп | Итоговых областей | Внутри | Вне | Самая большая |','|---|---:|---:|---:|---:|---:|']
    for name,m in metrics.items():table.append(f'| {display(name)} | {m["raw_modes"]} | {m["regions"]} | {m["assigned"]} | {m["outside"]} | {m["largest"]} |')
    lines=['# Directional mean-shift на вашей библиотеке','', '14 октября 2026 · 1 107 треков · звук 25% / метаданные 75%. Приложение не изменено.','',
       '**Результат:** directional mean-shift выделяет отдельные OST/альбомы и небольшие стили, но при близком количестве областей заметно уменьшает покрытие библиотеки. Для замены текущего алгоритма преимущества пока недостаточно.','',
       'При κ=8.5 получаются **16 областей / 730 треков**, у текущего алгоритма **17 / 974**. При κ=9 — **21 / 682**. Это потеря покрытия, а не доказательство, что исключённые треки музыкально не подходят.','',
       '## Что сравнивали','',
       'Directional mean-shift с ядром фон Мизеса: каждая точка движется к взвешенному среднему соседних направлений и снова нормализуется. Использованы все 1 107 точек как начальные позиции, полные 1 344 измерения, без PCA. Параметр κ=1/h²: больше κ — уже окно и обычно больше исходных групп. Центрирование побайтно совпадает с Kotlin.','',
       'После группировки применены действующие Kotlin-правила: минимум 4 трека, уверенность относительно центров, выделение специального контента и названия по метаданным/плейлистам. Дополнительно сохранён вариант без фильтра уверенности. Эти правила рассчитаны на k-means, поэтому здесь оценивается замена в текущем приложении, а не универсальное качество DMS.','',
       'Координаты карты оставлены одинаковыми: меняется только принадлежность к областям. Число исходных мод не равно числу областей после отсева маленьких групп и остальных правил. Ни геометрия соседей, ни плавность карты этим экспериментом не меняются.','',
       '## Чувствительность к масштабу','',*table,'',
       'Сначала выполнена грубая сетка 2–64. Правило выбора только по близости числа конечных областей выбрало κ=32: 20 областей, но всего 255 треков. Это показало непригодность такого критерия: при сильном дроблении маленькие группы просто исчезают. Исходные результаты сохранены. Затем исследован переход 6–10. Для подробной проверки заранее выбран midpoint κ=9; κ=8.5 показан дополнительно как вариант с близким к исходному числом областей. Это исследовательский подбор, не независимая проверка победителя.','',
       '## Как меняется состав','',
       '- Некоторые крупные блоки сохраняются, а отдельные саундтреки становятся самостоятельными областями.','- Более конкретные маленькие группы появляются одновременно с более широкими большими. Точный состав и пересечения перечислены ниже.','- При κ=9 появляются дополнительные группы, связанные с отдельными альбомами и исполнителями. Это не обязательно лучшие музыкальные миксы.','',
       f'Без фильтра уверенности при κ=8.5 остаётся {metrics["dms8p5"]["without_confidence_assigned"]} трека, при κ=9 — {metrics["dms9"]["without_confidence_assigned"]}. Значит, потерю покрытия нельзя объяснить только прежним порогом уверенности: действуют также маленькие группы и остальные правила областей.','',
       '## Проверки и ограничения','',
       'Реализация сверена с кодом авторов на независимом наборе; проверены три известных сферических сгущения, рост плотности за шаг, сходимость и объединение мод. Текущие 17 областей воспроизведены точно, включая названия и порядок треков.','',
       'При трёх удалениях случайных 10% треков raw-кластеры DMS κ=9 имеют ARI '+', '.join(f'{z["dms_raw_ari"]:.3f}' for z in results['stability'])+'; у k-means — '+', '.join(f'{z["kmeans_raw_ari"]:.3f}' for z in results['stability'])+'. Это устойчивость исходной группировки, не конечных названных областей; число групп различается.','',
       f'Для включённых треков silhouette по косинусной дистанции: текущий {metrics["baseline"]["retained_silhouette_cosine"]:.3f}, κ=8.5 {metrics["dms8p5"]["retained_silhouette_cosine"]:.3f}, κ=9 {metrics["dms9"]["retained_silhouette_cosine"]:.3f}. Сравниваются разные оставшиеся треки, поэтому рост не доказывает улучшения всей библиотеки. Жанровые/плейлистовые показатели также являются косвенными признаками.','',
       'Прослушивания всех областей не было. MOSS не использовался после провала проверки надёжности. Один снимок одной библиотеки не обосновывает смену алгоритма для всех пользователей. Замеры времени сделаны на компьютере, а не на телефоне.','',
       'Источники: [Zhang & Chen, JMLR 2021](https://jmlr.org/papers/v22/20-1194.html), [код авторов](https://github.com/zhangyk8/DirMS/tree/9a9fd6754a3eec0bf7eba7260a268fb3dbf67112).','',
       '## Все области и треки','', 'Ниже показаны текущий вариант и две настройки DMS. Полный список для каждой области раскрывается в интерактивном отчёте; JSON содержит результаты всей сетки.','']
    for name in ['baseline','dms8p5','dms9']:
        lines+=['### '+display(name),'']
        for j,w in enumerate(regions[name]):
            lines += [f'**{j+1}. {w["name"]} — {w["size"]} треков**','', 'Ближе к центру: '+'; '.join(track(rows[i]) for i in w['central_rows'])+'.','', 'Дальше от центра: '+'; '.join(track(rows[i]) for i in w['boundary_rows'])+'.','', 'Пересечение с текущими областями: '+'; '.join(f'{z["region"]}: {z["members"]}/{z["baseline_size"]}' for z in w['baseline_overlap'])+'.','']
    (p/'report-ru.md').write_text('\n'.join(lines))
    payload={'rows':[{k:r.get(k) for k in ['title','artist','genre','album']} for r in rows],'xy':np.round(xy,6).tolist(),'variants':regions,'metrics':metrics,'colors':colors,'labels':labs}
    template=Path(__file__).with_name('report-template.html').read_text()
    (p/'report.html').write_text(template.replace('__DATA__',json.dumps(payload,ensure_ascii=False,separators=(',',':')).replace('<','\\u003c')))
    print('Wrote comparison.png/svg, report.html and report-ru.md')

if __name__=='__main__':main()
