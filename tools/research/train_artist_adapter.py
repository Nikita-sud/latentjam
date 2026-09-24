#!/usr/bin/env python3
"""Train ml/artist_adapter.bin: guesses a pack descriptor from a track's trusted text for artists the pack misses.

Recipe measured in smart-bench exp_adapter_synth.py: the adapter trains only on trusted strings SYNTHESIZED from
the teacher's attributes of covered entities, never on any library's real strings. Four strings per entity,
each in the app's text-v2 form `genre; artist; year; language word` (TextEncoder.metadataString, with
TextLanguage's words), are mapped to that entity's descriptor. Both sides are embedded by the shipped MiniLM.

    python3 tools/research/train_artist_adapter.py entities.jsonl.gz out/artist_adapter.bin \\
        --cache out/artist_descriptors.json --attributes out/artist_attributes.json --limit 250000

The descriptor and attribute caches come from build_artist_knowledge.py --teacher --attributes.
"""
import argparse
import json
import random
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from build_artist_knowledge import embed, read_entities
from pack_music_entities import normalize

# TextLanguage's words for the ISO codes the teacher emits; anything else contributes no word.
WORDS = {"ru": "russian", "en": "english", "ro": "romanian", "ja": "japanese", "ko": "korean", "de": "german",
         "fr": "french", "es": "spanish", "it": "italian", "uk": "ukrainian", "pt": "portuguese", "tr": "turkish",
         "pl": "polish", "zh": "chinese", "kk": "kazakh", "be": "belarusian", "ar": "arabic", "hi": "hindi",
         "instrumental": "instrumental"}


def trusted(genre, artist, year, language):
    return "; ".join(x for x in (genre, artist, str(year) if year else None, WORDS.get(language or "")) if x)


class Adapter(nn.Module):
    def __init__(self, width=384, hidden=768):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(width, hidden), nn.GELU(approximate="tanh"), nn.Dropout(0.1),
                                 nn.Linear(hidden, width))

    def forward(self, x):
        return F.normalize(x + self.net(x), dim=-1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("entities", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--attributes", type=Path, required=True)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--epochs", type=int, default=60)
    args = parser.parse_args()
    random.seed(0); np.random.seed(0); torch.manual_seed(0)

    descriptors = json.loads(args.cache.read_text())
    attributes = json.loads(args.attributes.read_text())
    texts, owners = [], []
    for name in read_entities(args.entities, args.limit):
        key = normalize(name)
        teacher = attributes.get(key) or {}
        if not descriptors.get(key) or (teacher.get("confidence") or 0) < 0.5:
            continue
        for _ in range(4):
            genre = random.choice(teacher["genres"]).title() if teacher.get("genres") and random.random() < 0.7 else None
            year = random.choice(teacher["decades"]) + random.randint(0, 9) if teacher.get("decades") and random.random() < 0.7 else None
            language = teacher["languages"][0] if teacher.get("languages") and random.random() < 0.5 else None
            texts.append(trusted(genre, name, year, language)); owners.append(key)
    keys = sorted(set(owners))
    print(f"{len(texts)} synthetic strings for {len(keys)} confident entities", flush=True)
    target = dict(zip(keys, embed([descriptors[k] for k in keys])))
    x = torch.tensor(embed(texts)); y = torch.tensor(np.stack([target[k] for k in owners]))

    held = set(random.sample(keys, max(1, len(keys) // 10)))
    val = torch.tensor([k in held for k in owners]); train = ~val
    model = Adapter(); optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-2)
    best, best_state = -1.0, None
    for _ in range(args.epochs):
        model.train()
        order = torch.nonzero(train).flatten()[torch.randperm(int(train.sum()))]
        for start in range(0, len(order), 256):
            batch = order[start:start + 256]
            loss = 1 - (model(x[batch]) * y[batch]).sum(-1).mean()
            optimizer.zero_grad(); loss.backward(); optimizer.step()
        model.eval()
        with torch.no_grad():
            score = (model(x[val]) * y[val]).sum(-1).mean().item()
        if score > best:
            best, best_state = score, {k: t.clone() for k, t in model.state_dict().items()}
    model.load_state_dict(best_state)
    with torch.no_grad():
        baseline = (F.normalize(x[val], dim=-1) * y[val]).sum(-1).mean().item()
    print(f"held-out entities: cos to their descriptor {baseline:.3f} (text alone) -> {best:.3f} (adapter)", flush=True)

    state_path = args.output.with_suffix(".pt")
    torch.save(model.state_dict(), state_path)
    import subprocess, sys
    subprocess.run([sys.executable, str(Path(__file__).with_name("export_artist_adapter.py")), str(state_path),
                    str(args.output)], check=True)


if __name__ == "__main__":
    main()
