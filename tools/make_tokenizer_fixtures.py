"""Generate byte-exact BERT tokenization fixtures for the on-device tokenizer test.

Expected ids come from HuggingFace `transformers` itself, over the bundled vocab, so the Kotlin
port is checked against the real tokenizer rather than against a second guess at it.

    python3 tools/make_tokenizer_fixtures.py

Writes core/smart/src/androidHostTest/resources/tokenizer_fixtures.tsv (string TAB comma-separated
ids). The library strings come from the legacy metadata store when present; the edge cases are
always included.
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
VOCAB = os.path.join(ROOT, "androidApp/src/main/assets/ml/text_vocab.txt")
OUT = os.path.join(ROOT, "core/smart/src/androidHostTest/resources/tokenizer_fixtures.tsv")
STORE = "/Users/nichitabulgaru/Documents/LJ/synth-data-2026-07-15/store_dev841.parquet"

EDGE_CASES = [
    "Disco; ABBA; Mamma Mia; 1975",
    "Vocaloid; 初音ミク; Ievan Polkka; 2007",
    "Pop; Гурцкая; Ты здесь; 2003",
    "Estradă; Sofia Rotaru; Melancolie; 1975",
    "Hip-Hop; Eminem; Lose Yourself (From \"8 Mile\"); 2002",
    "Brazilian Phonk; Nakama, Mc Staff; SEM SAÍDA",
    "Rock; AC/DC; T.N.T.; 1975",
    "Электроника; Ёлка; Прованс",
    "J-Pop; 米津玄師; Lemon; 2018",
    "Soundtrack; Ramin Djawadi; Main Title — Game of Thrones; 2011",
    "café naïve Zoë",                     # accent stripping
    "  multiple   spaces\tand\ttabs  ",   # whitespace collapsing
    "emoji 🎵 test 🎧",                    # astral plane
    "a" * 150,                            # exceeds maxInputCharsPerWord -> [UNK]
    "hello-world, it's [bracketed] {and} <angled>!",
    "",                                   # blank
    "1234567890",
    "ÅÄÖ åäö ß",
    "混ぜるな危険",                          # CJK spacing
    "don't  DO'NT  Don’t",                # ASCII vs curly apostrophe
]


def main():
    # The `tokenizers` library, not `transformers`: transformers 5.x dropped the slow tokenizers,
    # and its BertTokenizer now ignores an explicit vocab_file, silently emitting [UNK] for every
    # word. This path is the same Rust WordPiece implementation the model was trained with.
    from tokenizers import BertWordPieceTokenizer

    if not os.path.isfile(VOCAB):
        sys.exit(f"vocab not found: {VOCAB}")
    tok = BertWordPieceTokenizer(VOCAB, lowercase=True)

    strings = list(EDGE_CASES)
    try:
        import pandas as pd

        store = pd.read_parquet(STORE)
        for r in store.itertuples():
            parts = [
                str(r.genre) if r.genre == r.genre else "",
                str(r.artist) if r.artist == r.artist else "",
                str(r.title) if r.title == r.title else "",
                str(int(r.year)) if r.year == r.year and int(r.year) > 0 else "",
            ]
            s = "; ".join(p for p in parts if p.strip())
            if s:
                strings.append(s)
    except Exception as e:  # noqa: BLE001 - the library strings are a bonus, not a requirement
        print(f"note: no library strings ({e}); writing edge cases only")

    seen, rows = set(), []
    for s in strings:
        if s in seen:
            continue
        seen.add(s)
        if "\t" in s or "\n" in s or "\r" in s:
            # The fixture format is TAB-separated; keep such strings out rather than escape them.
            continue
        rows.append((s, tok.encode(s).ids))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        for s, ids in rows:
            f.write(s + "\t" + ",".join(str(i) for i in ids) + "\n")
    print(f"wrote {len(rows)} fixtures -> {OUT}")


if __name__ == "__main__":
    main()
