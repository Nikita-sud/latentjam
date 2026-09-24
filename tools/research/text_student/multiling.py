"""H1 setup: extend the student's vocab with uncovered characters (ids stay stable), and build multilingual pairs
(foreign query -> MiniLM vector of its English source) with 20% of English sources held out for the test."""
import json, os, random, sys, unicodedata
from collections import Counter
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # tools/research
from build_artist_knowledge import embed
from tokenizers import BertWordPieceTokenizer

random.seed(3)
data = json.load(open("translations.json")); phrases = data["phrases"]; tr = data["translations"]
english = list(phrases); random.shuffle(english)
test_en = set(english[:len(english) // 5])
strings = json.load(open("strings.json"))
old = [l.rstrip("\n") for l in open("vocab_8000/vocab.txt", encoding="utf-8")]
have = set(old)
tok = BertWordPieceTokenizer("vocab_8000/vocab.txt", lowercase=True)
chars = Counter()
for s in strings + [v for m in tr.values() for v in m.values()]:
    for ch in unicodedata.normalize("NFD", s.lower()):
        if unicodedata.category(ch) == "Mn":
            continue                          # accents are stripped by the tokenizer
        if ch.isspace() or ch.isascii():
            continue
        chars[ch] += 1


def cjk(ch):
    cp = ord(ch)
    return 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0xF900 <= cp <= 0xFAFF or 0x20000 <= cp <= 0x2A6DF


add = []
for ch, n in chars.most_common():
    if n < 2:
        break
    if ch not in have:
        add.append(ch); have.add(ch)
    if not cjk(ch) and "##" + ch not in have:
        add.append("##" + ch); have.add("##" + ch)
    if len(add) >= 4000:
        break
open("vocab_ext.txt", "w", encoding="utf-8").write("\n".join(old + add) + "\n")
print(f"vocab {len(old)} -> {len(old) + len(add)} (+{len(add)} character tokens)", flush=True)
ext = BertWordPieceTokenizer("vocab_ext.txt", lowercase=True)
for c in ("zh", "th", "hi", "ja"):
    ph = list(tr[c].values()); e = [ext.encode(p).tokens for p in ph]
    print(f"  {c}: UNK share {sum(t.count('[UNK]') for t in e) / sum(len(t) - 2 for t in e):.3f}")
en_vec = dict(zip(phrases, embed(phrases, batch=256)))
train_pairs, test = [], []
for code, m in tr.items():
    for en, fx in m.items():
        (test if en in test_en else train_pairs).append((fx, en, code))
Y = np.stack([en_vec[en] for _, en, _ in train_pairs]).astype(np.float16)
json.dump({"train": train_pairs, "test": test, "test_en": sorted(test_en)}, open("ml_pairs.json", "w"), ensure_ascii=False)
np.save("ml_train_teacher.npy", Y); np.save("en_vectors.npy", np.stack([en_vec[p] for p in phrases]))
print(f"train pairs {len(train_pairs)}, test pairs {len(test)} over {len(test_en)} held-out English sources", flush=True)
