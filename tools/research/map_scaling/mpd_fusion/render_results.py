#!/usr/bin/env python3
"""Render aggregate experiment results and private, anonymized map coordinates."""
import argparse
import json
import os
from pathlib import Path
import numpy as np
os.environ.setdefault('MPLCONFIGDIR','/private/tmp/latentjam-mpd-fusion-mpl')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def main():
    ap=argparse.ArgumentParser();ap.add_argument('experiment',type=Path);a=ap.parse_args();p=a.experiment
    test=json.loads((p/'test-summary.json').read_text());transfer=json.loads((p/'personal-transfer/transfer-summary.json').read_text())
    names=['current_1344','pca64','pca128','two_branch_MLP-64','two_branch_MLP-128','supervised_linear-64','supervised_linear-128']
    labels=['Текущий 25/75','PCA 64','PCA 128','MLP 64','MLP 128','Обучаемый линейный 64','Обучаемый линейный 128']
    colors=['#353d4b','#709ead','#709ead','#b36956','#b36956','#75865d','#75865d']
    plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11})
    fig,axes=plt.subplots(1,2,figsize=(14,6.7),gridspec_kw={'wspace':.65});fig.patch.set_facecolor('#fbfaf7')
    for panel,ax in enumerate(axes):
        values=[test['metrics'][test['validation_selected_models'].get(n,n)]['ndcg_at20'] for n in names] if panel==0 else [transfer['metrics'][n]['playlist_at15']*100 for n in names]
        ax.barh(labels,values,color=colors,height=.64);ax.invert_yaxis();ax.set_axisbelow(True);ax.grid(axis='x',color='#e7e4dd')
        ax.spines[['top','right','left']].set_visible(False);ax.tick_params(axis='y',length=0)
        for i,value in enumerate(values):ax.text(value+(0.0003 if panel==0 else .6),i,f'{value:.4f}' if panel==0 else f'{value:.1f}%',va='center',fontsize=10)
        if panel==0:
            pop=test['metrics']['popularity']['ndcg_at20'];ax.axvline(pop,color='#777',ls='--',lw=1.4)
            ax.text(pop,.99,'Популярность',transform=ax.get_xaxis_transform(),rotation=90,ha='right',va='top',fontsize=9,color='#555')
            ax.set_xlim(0,.041);ax.set_xlabel('nDCG@20: выше лучше')
            ax.set_title('MPD: новые исполнители\n1 500 отложенных плейлистов',loc='left',pad=16)
        else:
            ax.set_xlim(0,74);ax.set_xlabel('Соседи из того же личного плейлиста, %')
            ax.set_title('Твоя библиотека: перенос\n1 106 треков, соседство до 2D',loc='left',pad=16)
    fig.suptitle('Обучение на MPD помогает на MPD, но ухудшает личную карту',x=.035,ha='left',fontsize=18,y=.97)
    fig.text(.035,.035,'Модели выбраны по валидации. Разные метрики на двух панелях; сравнивать числа между панелями нельзя.\nПлейлисты — косвенная оценка. Прослушивания и независимой оценки музыкальных областей здесь нет.',fontsize=10,color='#555')
    fig.subplots_adjust(left=.2,right=.97,top=.8,bottom=.19)
    fig.savefig(p/'comparison.png',dpi=170,facecolor=fig.get_facecolor());fig.savefig(p/'comparison.svg',facecolor=fig.get_facecolor());plt.close(fig)
    # Anonymized positions, colored by the same production-control worlds in every panel.
    worlds=json.loads((p/'personal-transfer/private-regions-current_1344.json').read_text())['worlds']
    region=np.full(transfer['rows'],-1)
    for i,w in enumerate(worlds):region[w['members']]=i
    palette=plt.get_cmap('tab20');color=[palette(i%20) if i>=0 else (.55,.55,.55,1) for i in region]
    chosen=[('current_1344','Текущая карта'),('pca128','PCA 128'),('two_branch_MLP-64','MLP 64'),('two_branch_MLP-128','MLP 128')]
    fig,axes=plt.subplots(2,2,figsize=(12,10));fig.patch.set_facecolor('#fbfaf7')
    for ax,(name,label) in zip(axes.flat,chosen):
        xy=np.fromfile(p/f'personal-transfer/private-{name}-2d.f32',dtype='<f4').reshape(-1,2)
        ax.scatter(xy[:,0],xy[:,1],s=10,c=color,alpha=.75,linewidths=0)
        m=transfer['metrics'][name]
        ax.set_title(f"{label} · плейлист-соседство в 2D: {m['map_playlist_at15']:.1%}",loc='left')
        ax.set_aspect('equal',adjustable='datalim');ax.set_xticks([]);ax.set_yticks([])
        for spine in ax.spines.values():spine.set_color('#dfdcd5')
    fig.suptitle('Один набор треков, один алгоритм отрисовки, разные векторы',x=.04,ha='left',fontsize=16,y=.98)
    fig.text(.04,.035,'Цвета во всех панелях — области текущего алгоритма; серые точки — вне его областей.\nЭто визуальная диагностика переноса. Компактный цветной остров сам по себе не доказывает музыкальное качество.',fontsize=10,color='#555')
    fig.tight_layout(rect=[.02,.10,.99,.94],h_pad=3,w_pad=2)
    fig.savefig(p/'personal-map-comparison.png',dpi=160,facecolor=fig.get_facecolor());plt.close(fig)
    print('Rendered comparison.png, comparison.svg, personal-map-comparison.png')

if __name__=='__main__':main()
