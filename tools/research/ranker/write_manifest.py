"""Write ml/smart_manifest.json: the ranker's assets with their sizes and SHA-256 (PLAN_FINAL §7.2's checksummed
manifest; the app refuses to run the ranker on a mismatch and runs the rule-only path instead).

    python3 write_manifest.py ML_DIR
"""
import hashlib, json, sys
from pathlib import Path

ASSETS = ['smart_ranker.json', 'smart_ranker_full.json', 'artist_colisten.bin', 'artist_lfm.bin', 'artist_known.bin',
          'audio_tagger.json', 'audio_tagger.bin', 'artist_pop.bin', 'artist_family.bin']
d = Path(sys.argv[1])
out = {'version': 1, 'assets': {n: {'size': (d / n).stat().st_size, 'sha256': hashlib.sha256((d / n).read_bytes()).hexdigest()}
                                for n in ASSETS}}
(d / 'smart_manifest.json').write_text(json.dumps(out, indent=1) + '\n')
print(json.dumps(out, indent=1))
