"""Copies the Python serving reference's golden fixtures (tools/research/listenbrainz_eval/serving/fixtures on the
research branch) into the app's host-test resources as one gzipped JSON-lines file, in the form the app's tiny JSON
reader keeps exact: 64-bit integers (artist-set keys, the hop draws u) as decimal strings, each request's model as
its JSON text. Nothing else changes; the source file's sha256 travels with each line.

    python3 tools/research/ranker/export_serving_fixtures.py SERVING_FIXTURE_DIR \
        core/smart/src/androidHostTest/resources/serving_fixtures.jsonl.gz
"""
import gzip, hashlib, json, sys
from pathlib import Path


def convert(fx):
    for rec in fx['input']['library']:
        rec['resolved']['artistSet'] = [str(a) for a in rec['resolved'].get('artistSet') or []]
    for req in fx['input']['requests']:
        if isinstance(req.get('seed'), dict):
            req['seed']['resolved']['artistSet'] = [str(a) for a in req['seed']['resolved'].get('artistSet') or []]
        req['modelJson'] = json.dumps(req.pop('model'))
    for e in fx['expected']:
        for h in e['trace'].get('hops') or []:
            if h.get('u') is not None: h['u'] = str(h['u'])
    return fx


def main(src, out):
    lines = []
    for f in sorted(Path(src).glob('*.json')):
        if f.name == 'index.json': continue
        raw = f.read_bytes()
        fx = convert(json.loads(raw))
        lines.append(json.dumps({'file': f.name, 'sha256': hashlib.sha256(raw).hexdigest(), **fx}, separators=(',', ':')))
    with gzip.open(out, 'wt', encoding='utf-8') as g:
        g.write('\n'.join(lines) + '\n')
    print(f'{len(lines)} fixtures -> {out}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
