"""Distillation corpus for a small text encoder: only the domain the app encodes (artist names, text-v2 strings,
genres, search-style queries), from CC0 entity names and the teacher's attributes. No listener or MPD strings.
Teacher targets: MiniLM (tools/research/minilm, INT8 ONNX), mean-pooled and L2-normalized, as the app pools.

    python3 corpus.py <entities.jsonl.gz> <attributes.json>   # the pack build's corpus and attribute cache

Writes strings.json and teacher.npy into the working directory."""
import gzip, json, os, random, sys
from collections import Counter
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # tools/research
from build_artist_knowledge import embed
from pack_music_entities import normalize

random.seed(0)
WORDS = {"ru": "russian", "en": "english", "ro": "romanian", "ja": "japanese", "ko": "korean", "de": "german",
         "fr": "french", "es": "spanish", "it": "italian", "uk": "ukrainian", "pt": "portuguese", "tr": "turkish",
         "pl": "polish", "zh": "chinese", "kk": "kazakh", "be": "belarusian", "ar": "arabic", "hi": "hindi",
         "instrumental": "instrumental"}
rows = [json.loads(l) for l in gzip.open(sys.argv[1], "rt", encoding="utf-8")]
attrs = json.load(open(sys.argv[2]))
strings = set()
genre_count = Counter()
for r in rows:
    strings.add(r["name"])
    for a in r.get("aliases", [])[:2]:
        strings.add(a)
    a = attrs.get(normalize(r["name"])) or {}
    for g in a.get("genres") or []:
        genre_count[g] += 1
genres = [g for g, c in genre_count.items() if c >= 3]
print(f"{len(rows)} entities, {len(genres)} genres", flush=True)

# text-v2 strings, synthesized as the app forms them: "Genre; Artist; year; language word"
confident = [r for r in rows if ((attrs.get(normalize(r["name"])) or {}).get("confidence") or 0) >= 0.5]
for r in confident:
    a = attrs[normalize(r["name"])]
    for _ in range(2):
        g = random.choice(a["genres"]).title() if a.get("genres") and random.random() < 0.8 else None
        y = random.choice(a["decades"]) + random.randint(0, 9) if a.get("decades") and random.random() < 0.6 else None
        lang = WORDS.get((a.get("languages") or [None])[0]) if random.random() < 0.6 else None
        strings.add("; ".join(x for x in (g, r["name"], str(y) if y else None, lang) if x))

# search-style queries
decades = [1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020]
moods = ["sad", "happy", "chill", "calm", "energetic", "romantic", "dark", "epic", "relaxing", "party", "workout",
         "sleep", "study", "driving", "summer", "melancholic", "aggressive", "dance", "love", "motivational"]
ru = ["русский рок", "русский рэп", "русская попса", "попса", "шансон", "эстрада", "советские песни", "музыка 80-х",
      "песни 90-х", "хиты 2000-х", "грустные песни", "весёлые песни", "спокойная музыка", "музыка для тренировки",
      "музыка для сна", "танцевальная музыка", "романтичные песни", "молдавская музыка", "румынская музыка",
      "аниме", "саундтреки", "музыка из фильмов", "музыка из игр", "классическая музыка", "джаз", "метал", "электроника"]
langs = sorted(set(WORDS.values()))
for g in genres:
    for t in ("{g}", "{g} music", "{g} songs", "best {g}", "{g} hits", "old {g}", "new {g}"):
        strings.add(t.format(g=g))
    for d in random.sample(decades, 3):
        strings.add(f"{d % 100:02d}s {g}"); strings.add(f"{g} from the {d % 100:02d}s"); strings.add(f"{d}s {g}")
    strings.add(f"{random.choice(moods)} {g}"); strings.add(f"{random.choice(langs)} {g}")
for d in decades:
    for t in ("{s}s music", "music of the {s}s", "{s}s hits", "{d}s music", "{s}s songs", "музыка {s}-х", "песни {s}-х", "хиты {s}-х"):
        strings.add(t.format(s=f"{d % 100:02d}", d=d))
    for y in range(d, d + 10):
        strings.add(str(y)); strings.add(f"music {y}"); strings.add(f"songs from {y}")
for l in langs:
    strings.add(f"{l} songs"); strings.add(f"{l} music"); strings.add(f"{l} hits")
for m in moods:
    strings.add(f"{m} music"); strings.add(f"{m} songs"); strings.add(m)
strings.update(ru)
for r in random.sample(rows, 120_000):
    n = r["name"]
    strings.add(n.lower()); strings.add(n.split()[0].lower()); strings.add(f"{n} songs")
# every WordPiece token of the teacher's vocabulary, so no word is out of domain
minilm_vocab = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "minilm", "text_vocab.txt")
vocab = [t.strip() for t in open(minilm_vocab, encoding="utf-8")]
strings.update(t for t in vocab if t and not t.startswith("[") and not t.startswith("##"))
strings = sorted(s for s in strings if s and len(s) <= 120)
random.shuffle(strings)
print(f"{len(strings)} strings; embedding with MiniLM ...", flush=True)
X = embed(strings, batch=256).astype(np.float16)
np.save("teacher.npy", X)
json.dump(strings, open("strings.json", "w"), ensure_ascii=False)
print("done", X.shape, flush=True)
