"""Re-key the offline semantic descriptors so this app can find them.

The descriptors (768-d nomic vectors of an LLM-written description per track) were computed offline
and keyed by the legacy player's content-hash song UID. This app identifies tracks by MediaStore id
and cannot reproduce those hashes, so the asset is rebuilt keyed by normalised metadata instead.

Each track is written twice: once under "artist\\u001Ftitle" and, when the title is unique across
the corpus, once under the bare title. The loader tries the precise key first, so a title collision
can never resolve to the wrong artist's vector.

    python3 tools/build_descriptor_asset.py

Format (little-endian):
    magic   int32  0x4C4A5344  ("LJSD")
    version int32  2
    count   int32
    dim     int32
    entries [keyLen int32][key utf8][dim float32]   -- vectors L2-normalised, uncentered
"""
import os, re, struct, sys, collections
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
NPZ = "/Users/nichitabulgaru/Documents/LJ/datagen/text_emb_full.npz"
STORE = "/Users/nichitabulgaru/Documents/LJ/synth-data-2026-07-15/store_dev841.parquet"
OUT = os.path.join(ROOT, "androidApp/src/main/assets/ml/semantic_descriptors.bin")

UNIT = ""  # unit separator: cannot occur in a tag, so the key halves stay unambiguous
BRACKETED = re.compile(r"\s*[\(\[][^()\[\]]*[\)\]]\s*")
WS = re.compile(r"\s+")


def norm_title(s: str) -> str:
    """Must stay identical to MetadataRerank.normalizeTitle on the Kotlin side."""
    return WS.sub(" ", BRACKETED.sub(" ", (s or "").lower())).strip()


def norm_artist(s: str) -> str:
    return WS.sub(" ", (s or "").lower()).strip()


def main():
    import pandas as pd

    for path in (NPZ, STORE):
        if not os.path.exists(path):
            sys.exit(f"missing input: {path}")

    z = np.load(NPZ)
    store = pd.read_parquet(STORE)
    labels = {
        str(r.track_id): (
            str(r.title) if r.title == r.title else "",
            str(r.artist) if r.artist == r.artist else "",
        )
        for r in store.itertuples()
    }

    title_counts = collections.Counter()
    for uid in z.files:
        title, _ = labels.get(uid, ("", ""))
        if title:
            title_counts[norm_title(title)] += 1

    entries, unlabelled, zero_norm = {}, 0, 0
    for uid in sorted(z.files):
        title, artist = labels.get(uid, ("", ""))
        if not title:
            unlabelled += 1
            continue
        v = z[uid].astype(np.float32)
        n = float(np.linalg.norm(v))
        if n < 1e-6:
            zero_norm += 1
            continue
        v = v / n
        nt = norm_title(title)
        if artist:
            entries[norm_artist(artist) + UNIT + nt] = v
        # Bare title only when it cannot be ambiguous.
        if title_counts[nt] == 1:
            entries.setdefault(nt, v)

    dim = int(next(iter(entries.values())).shape[0])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(struct.pack("<iiii", 0x4C4A5344, 2, len(entries), dim))
        for key, vec in sorted(entries.items()):
            raw = key.encode("utf-8")
            f.write(struct.pack("<i", len(raw)))
            f.write(raw)
            f.write(vec.tobytes())

    print(
        f"wrote {len(entries)} keys for {len(z.files) - unlabelled - zero_norm} tracks "
        f"dim={dim} -> {OUT}"
    )
    if unlabelled:
        print(f"  {unlabelled} descriptors had no metadata in the store and were skipped")
    if zero_norm:
        print(f"  {zero_norm} zero-norm descriptors skipped")


if __name__ == "__main__":
    main()
