"""Inspectible private regions and public aggregate-only figures."""
import argparse,json,html
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from search import read,write

NAMES={'kmeans':'Spherical k-means','dms':'Directional mean-shift · von Mises','compact':'Directional mean-shift · compact','hdbscan':'HDBSCAN','average':'Average linkage'}
COLORS={'kmeans':'#3977b6','dms':'#bb5c27','compact':'#8556a7','hdbscan':'#238c6e','average':'#b84b74'}
def title(v):return f'{NAMES[v["algorithm"]]} · {round(v["audio_weight"]*100)}/{round((1-v["audio_weight"])*100)} · {v["params"]} · {v["admission"]}'
def main():
 p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--coordinates',type=Path,required=True);a=p.parse_args();root=a.root
 dev=read(root/'development.json');by={v['name']:v for v in dev};selection=read(root/'selection.json');test=read(root/'holdout.json');testby={v['name']:v for v in test['results']};base='a25-kmeans-16-product'
 selected=list(dict.fromkeys([base]+[v['name'] for v in selection['selected'].values() if v]));winner='a75-average-0p75-own'
 plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11})
 fig,axes=plt.subplots(1,2,figsize=(14,6),facecolor='#faf9f6')
 for alg,color in COLORS.items():
  vals=[v for v in dev if v['algorithm']==alg and v['score'] is not None and v['converged']]
  axes[0].scatter([v['coverage']*100 for v in vals],[v['score']*100 for v in vals],s=18,c=color,alpha=.5,label=NAMES[alg])
 axes[0].axvline(55,color='#777',ls='--',lw=1);axes[0].set(xlabel='Треки в областях, % библиотеки',ylabel='Акустическое сходство: средний ранг, 0–100',title='Настройка на 107 треках · 440 вариантов',xlim=(0,100),ylim=(0,100));axes[0].legend(fontsize=8,loc='lower left');axes[0].grid(alpha=.15)
 vals=read(root/'matched-comparisons.json')['family_vs_baseline'];labels=[NAMES[by[v['name']]['algorithm']]+' '+str(round(by[v['name']]['audio_weight']*100))+'/'+str(round((1-by[v['name']]['audio_weight'])*100)) for v in vals]
 yy=np.arange(len(vals));axes[1].set_yticks(yy,labels,fontsize=9);axes[1].invert_yaxis();axes[1].set(xlim=(-6,11),ylim=(len(vals)-.5,-.6),xlabel='Изменение ранга сходства на общих треках, пункты',title='Отложенная проверка · против текущего 25/75');axes[1].grid(axis='x',alpha=.15);axes[1].axvline(0,color='#555',lw=1)
 for i,v in enumerate(vals):
  mid=v['difference']*100;lo,hi=np.array(v['artist_bootstrap_ci98p75_four_comparisons'])*100
  axes[1].errorbar(mid,i,xerr=[[mid-lo],[hi-mid]],fmt='o',capsize=4,color=COLORS[by[v['name']]['algorithm']],markersize=7)
  axes[1].text(mid,i-.18,f'{mid:+.1f} · n={v["anchors"]}',ha='center',fontsize=10)
 fig.suptitle('Поиск алгоритма и веса звука для областей',x=.04,ha='left',fontsize=18)
 fig.text(.04,.025,'1 107 треков. CLAP — косвенная оценка звучания, не процент правильных назначений.\nИнтервалы: bootstrap по исполнителям, 98,75% для четырёх сравнений. У каждой пары свой набор общих треков.',fontsize=9,color='#555')
 fig.subplots_adjust(left=.07,right=.98,top=.86,bottom=.18,wspace=.72);fig.savefig(root/'comparison.png',dpi=160);plt.close(fig)
 meta=read(root/'geometry/private-cooked-metadata.json');xy=np.fromfile(a.coordinates,dtype='<f4').reshape(1107,2)
 variants={}
 for v in test['results']:
  name=v['name'];worlds=read(root/'geometry'/f'private-regions-{name}.json')['worlds'];variants[name]={'title':title(by[name]),'worlds':worlds,'metrics':v}
 payload={'variants':variants,'winner':winner,'rows':[{k:r.get(k) for k in ['title','artist','album','genre']} for r in meta],'xy':np.round(xy,6).tolist(),'selection':selection,'test':test}
 template=Path(__file__).with_name('report-template.html').read_text();(root/'report.html').write_text(template.replace('__DATA__',json.dumps(payload,ensure_ascii=False,separators=(',',':')).replace('<','\\u003c')))
 summary={'population':1107,'raw_settings':len(dev)//2,'output_settings':len(dev),'audit_checks':read(root/'audit-checks.json'),'development_tracks':selection['development_tracks'],'holdout_tracks':test['holdout_tracks'],'selection':selection['selected'],'recommendation':winner,'recommendation_reason':'Development-selected family winner; strongest matched held-out acoustic gain, also positive with approximate four-comparison artist bootstrap adjustment. DMS more stable but its unmatched average is selection-biased. This is an exploratory recommendation on one library, not listener ground truth.','matched_comparisons':read(root/'matched-comparisons.json'),'supplementary_comparisons':read(root/'supplementary-comparisons.json'),'holdout':test['results'],'stability':read(root/'stability.json') if (root/'stability.json').exists() else [],'baseline_exact':read(root/'grid-verification.json')['baseline_exact']};write(root/'public-results.json',summary)
 print('report.html comparison.png public-results.json')
if __name__=='__main__':main()
