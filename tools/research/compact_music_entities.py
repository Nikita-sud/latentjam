"""Rewrites an LJENT2 music-entity index as LJENT3: the same keys and ids, about 70 % of the bytes.

    python tools/research/compact_music_entities.py IN.bin OUT.bin

LJENT3 (read by core/smart MusicEntityIndex) keeps LJENT2's 65,536 buckets by the hash's top 16 bits,
but stores each key as the next 24 bits of its 48-bit hash followed by its ids as LEB128 varints (a
count, the first id, then the steps between sorted ids), and the directory points at bytes instead of
key numbers. The shipped keys stay distinct at 40 bits, which this script checks before writing; a
collision stops it. Every key's ids are decoded back and compared with the input.
"""
import struct
import sys

import numpy as np

BUCKETS = 1 << 16
HEADER = 24
KEYS = HEADER + (BUCKETS + 1) * 4


def read_ljent2(raw):
    assert raw[:8] == b"LJENT2\0\0", "not an LJENT2 file"
    n, nv, entities = struct.unpack_from("<III", raw, 8)
    buckets = np.frombuffer(raw, "<u4", count=BUCKETS + 1, offset=HEADER)
    keys = np.frombuffer(raw, np.dtype([("hash", "<u4"), ("offset", "u1", (3,))]), count=n, offset=KEYS)
    u24 = lambda a: a[:, 0].astype(np.int64) | a[:, 1].astype(np.int64) << 8 | a[:, 2].astype(np.int64) << 16
    values = u24(np.frombuffer(raw, "u1", count=nv * 3, offset=KEYS + n * 7).reshape(-1, 3))
    starts = u24(keys["offset"])
    ends = np.append(starts[1:], nv)
    bucket_of = np.repeat(np.arange(BUCKETS, dtype=np.int64), np.diff(buckets.astype(np.int64)))
    hashes = bucket_of << 32 | keys["hash"].astype(np.int64)
    return entities, hashes, [values[s:e] for s, e in zip(starts, ends)]


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def write_ljent3(entities, hashes, lists):
    identity = hashes >> 8
    if len(np.unique(identity)) != len(identity):
        raise SystemExit("two keys share 40 hash bits; LJENT3 cannot hold this index")
    order = np.argsort(hashes, kind="stable")
    directory = np.zeros(BUCKETS + 1, np.int64)
    stream = bytearray()
    bucket = 0
    for k in order:
        b = int(hashes[k] >> 32)
        while bucket <= b:
            directory[bucket] = len(stream)
            bucket += 1
        ids = lists[k]
        stream += struct.pack("<I", int(hashes[k] >> 8) & 0xFFFFFF)[:3]
        stream += varint(len(ids))
        previous = None
        for i in ids.tolist():
            stream += varint(i if previous is None else i - previous)
            previous = i
    while bucket <= BUCKETS:
        directory[bucket] = len(stream)
        bucket += 1
    header = b"LJENT3\0\0" + struct.pack("<IIII", len(hashes), sum(len(x) for x in lists), entities, 0)
    return header + directory.astype("<u4").tobytes() + bytes(stream)


def read_ljent3(raw):
    """Every key's (40-bit identity, ids) back out of an LJENT3 file, for the round-trip check."""
    assert raw[:8] == b"LJENT3\0\0"
    directory = np.frombuffer(raw, "<u4", count=BUCKETS + 1, offset=HEADER)
    out = {}
    pos = KEYS
    for bucket in range(BUCKETS):
        end = KEYS + int(directory[bucket + 1])
        while pos < end:
            stored = raw[pos] | raw[pos + 1] << 8 | raw[pos + 2] << 16
            pos += 3

            def nxt():
                nonlocal pos
                result, shift = 0, 0
                while True:
                    byte = raw[pos]
                    pos += 1
                    result |= (byte & 0x7F) << shift
                    if byte < 0x80:
                        return result
                    shift += 7

            count = nxt()
            ids, previous = [], 0
            for i in range(count):
                previous = nxt() if i == 0 else previous + nxt()
                ids.append(previous)
            out[bucket << 24 | stored] = ids
    return out


def main(src, dst):
    raw = open(src, "rb").read()
    entities, hashes, lists = read_ljent2(raw)
    packed = write_ljent3(entities, hashes, lists)
    back = read_ljent3(packed)
    assert len(back) == len(hashes)
    for h, ids in zip(hashes.tolist(), lists):
        assert back[h >> 8] == ids.tolist(), f"key {h:#x} did not survive"
    open(dst, "wb").write(packed)
    print(f"{len(hashes)} keys, {sum(len(x) for x in lists)} ids: {len(raw) / 1e6:.2f} MB -> {len(packed) / 1e6:.2f} MB; "
          f"every key resolves to the same ids")


if __name__ == "__main__":
    main(*sys.argv[1:3])
