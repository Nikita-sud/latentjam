"""Round-2 pairs whose TARGET is another string's teacher vector: typos -> the correct name, year formats -> the
year, Russian queries -> their English equivalent. The student learns to land them where MiniLM puts the clean form."""
import json, os, random, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # tools/research
from build_artist_knowledge import embed

random.seed(1)
strings = json.load(open("strings.json"))
pos = {s: i for i, s in enumerate(strings)}
names = [s for s in strings if 3 <= len(s) <= 40 and ";" not in s and not s.islower()][:400_000]


def typo(w):
    if len(w) < 5: return None
    k = random.randrange(1, len(w) - 1); op = random.random()
    if op < 0.4: return w[:k] + w[k + 1:]                                   # drop
    if op < 0.7 and k < len(w) - 1: return w[:k] + w[k + 1] + w[k] + w[k + 2:]  # swap
    return w[:k] + w[k] + w[k:]                                              # double


pairs = []                                    # (input string, target string)
for n in random.sample(names, 150_000):
    for form in (n, n.lower()):
        t = typo(form)
        if t and t != form: pairs.append((t, n))
for y in range(1950, 2030):
    for f in (f"'{y % 100:02d}", f"{y} год", f"год {y}", f"year {y}", f"{y} songs", f"песни {y}", f"{y}г"):
        pairs.append((f, str(y)))
GENRES = {"рок": "rock", "рэп": "rap", "хип-хоп": "hip hop", "поп": "pop", "попса": "pop music", "электроника": "electronic",
          "электронная музыка": "electronic music", "классика": "classical", "классическая музыка": "classical music",
          "джаз": "jazz", "блюз": "blues", "метал": "metal", "панк": "punk", "шансон": "chanson", "эстрада": "estrada pop",
          "саундтрек": "soundtrack", "саундтреки": "soundtracks", "аниме": "anime", "фонк": "phonk", "техно": "techno",
          "хаус": "house", "диско": "disco", "фолк": "folk", "кантри": "country", "регги": "reggae", "соул": "soul",
          "инди": "indie", "альтернатива": "alternative", "танцевальная музыка": "dance music", "транс": "trance",
          "эмбиент": "ambient", "лоуфай": "lofi", "ремиксы": "remixes", "каверы": "covers", "оркестр": "orchestral"}
LANGS = {"русский": "russian", "русская": "russian", "русские": "russian", "английский": "english", "английская": "english",
         "молдавская": "moldovan", "румынская": "romanian", "японская": "japanese", "корейская": "korean",
         "украинская": "ukrainian", "немецкая": "german", "французская": "french", "испанская": "spanish"}
MOODS = {"грустные": "sad", "весёлые": "happy", "спокойная": "calm", "спокойные": "calm", "энергичная": "energetic",
         "романтичные": "romantic", "танцевальные": "dance", "эпичная": "epic", "мрачная": "dark", "для тренировки": "workout",
         "для сна": "sleep", "для учёбы": "study", "для вечеринки": "party", "в машину": "driving", "летние": "summer"}
for ru, en in GENRES.items():
    pairs += [(ru, en), (f"{ru} музыка", f"{en} music"), (f"песни {ru}", f"{en} songs")]
    for lru, len_ in LANGS.items():
        pairs.append((f"{lru} {ru}", f"{len_} {en}"))
    for d in (70, 80, 90, 2000, 2010):
        label = f"{d:02d}" if d < 100 else str(d)
        pairs += [(f"{ru} {label}-х", f"{d % 100:02d}s {en}"), (f"{ru} {label}х", f"{d % 100:02d}s {en}")]
for d in (50, 60, 70, 80, 90):
    for f in (f"музыка {d}-х", f"песни {d}-х", f"хиты {d}-х", f"{d}-е", f"{d}е", f"восьмидесятые" if d == 80 else f"{d}-ые"):
        pairs.append((f, f"{d}s music"))
for lru, len_ in LANGS.items():
    pairs += [(f"{lru} музыка", f"{len_} music"), (f"{lru} песни", f"{len_} songs")]
for ru, en in MOODS.items():
    pairs += [(f"{ru} песни", f"{en} songs"), (f"{ru} музыка", f"{en} music"), (ru, en)]
targets = sorted({t for _, t in pairs if t not in pos})
print(f"{len(pairs)} pairs; {len(targets)} new target strings to embed", flush=True)
extra = embed(targets, batch=256) if targets else np.zeros((0, 384), np.float32)
tvec = {t: extra[i] for i, t in enumerate(targets)}
T = np.load("teacher.npy")
Y = np.stack([T[pos[t]].astype(np.float32) if t in pos else tvec[t] for _, t in pairs]).astype(np.float16)
np.save("aug_teacher.npy", Y); json.dump([p for p, _ in pairs], open("aug_strings.json", "w"), ensure_ascii=False)
print("done", Y.shape, flush=True)
