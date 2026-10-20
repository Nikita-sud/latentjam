"""Write ml/smart_manifest.json: the ranker's assets with their sizes and SHA-256 (PLAN_FINAL §7.2's checksummed
manifest; the app refuses to run the ranker on a mismatch and runs the rule-only path instead).

    python3 write_manifest.py ML_DIR
"""
import hashlib, json, sys
from pathlib import Path

ASSETS = ['smart_ranker.json', 'smart_ranker_full.json', 'artist_colisten.bin', 'artist_lfm.bin', 'artist_known.bin',
          'audio_tagger.json', 'audio_tagger.bin', 'artist_pop.bin', 'artist_family.bin', 'artist_family_l2.bin',
          'genre_families.json']
d = Path(sys.argv[1])
# Every audio-derived asset is fitted on one encoder (audit 2026-10-20 #1); the app refuses a different one.
encoder = (d / 'embedding_version.txt').read_text().splitlines()[0].strip()
out = {'version': 1, 'audioEncoder': encoder, 'assets': {n: {'size': (d / n).stat().st_size, 'sha256': hashlib.sha256((d / n).read_bytes()).hexdigest()}
                                for n in ASSETS}}
(d / 'smart_manifest.json').write_text(json.dumps(out, indent=1) + '\n')
print(json.dumps(out, indent=1))
