# Text student

`text_encoder.onnx` in the app is a three-layer, 256-wide BERT (5.1M parameters, 5.2 MB as INT8)
distilled from MiniLM (`../minilm/`, all-MiniLM-L6-v2). Its per-token Linear maps into MiniLM's 384-d
space, so the scorer, the artist adapter, search and the map read its vectors unchanged. It has its own
WordPiece vocabulary: 8,000 trained pieces plus 3,588 character tokens for scripts the pieces missed.

Nothing here reads a listener's library or the MPD evaluation set. Run the steps in an empty working
directory; each writes its outputs there.

```bash
python3 corpus.py entities.jsonl.gz attributes.json      # strings.json, teacher.npy (1.2M strings)
python3 train.py s256x3 8000 256 3 4 768 6               # vocab_8000/, s256x3/
python3 augment.py                                        # aug_strings.json, aug_teacher.npy
AUG=1 INIT=s256x3/student.pt LR=5e-4 python3 train.py s256x3a 8000 256 3 4 768 4
LLM_KEY=... python3 translate.py                          # translations.json (DeepSeek-V4.1-Flash)
python3 multiling.py                                      # vocab_ext.txt, ml_pairs.json, ml_train_teacher.npy
VOCAB_FILE=vocab_ext.txt AUG=1 ML=1 INIT=s256x3a/student.pt LR=5e-4 python3 train.py s256x3m 8000 256 3 4 768 4
python3 export.py s256x3m                                 # s256x3m/student_int8.onnx, s256x3m/vocab.txt
```

`entities.jsonl.gz` and `attributes.json` are the knowledge-pack build's entity corpus and teacher
attribute cache (see `../build_wikidata_tail.py` and `../build_artist_knowledge.py --attributes`). The
shipped files are `s256x3m/student_int8.onnx` as `text_encoder.onnx` and `s256x3m/vocab.txt` as
`text_vocab.txt`. Training takes about 36 s per epoch on an M-series GPU (MPS).

What the three rounds teach:
- **s256x3**: MiniLM's vectors for artist names and aliases, trusted metadata strings synthesized from
  the teacher attributes (`genre; artist; year; language`), genre, decade and mood query templates, and
  MiniLM's vocabulary words.
- **s256x3a**: typos land on the correct name, year spellings (`'85`, `1985 год`) land on the year, and
  Russian queries land where MiniLM puts their English equivalent.
- **s256x3m**: search phrases in 20 languages land on their English source's MiniLM vector. 20 % of
  the English sources are held out for the test.

Measured on 2026-09-24 (the bench is outside this repository):

| | MiniLM (22.7 MB) | student (5.2 MB) |
|---|---:|---:|
| Search recall@10, basic families | 0.726 | 0.739 |
| Search recall@10, wide set (typos, years, Russian) | 0.648 | 0.755 |
| Cross-lingual top-1 over 80 held-out sources, 20 languages | 0.38 | 0.95 |
| Exact-year queries recall@10 | 0.48 | 0.38 |
| SMART P@10, listener and MPD libraries | — | ±0 |

Exact years are weaker, so search filters by year and decade explicitly.
