#!/usr/bin/env python3
"""Prepare and distill LatentJam's compact multilingual music text encoder.

The student is intentionally app-shaped: WordPiece tokenization already exists
in common Kotlin, sequences are short, the embedding is 256 dimensional, and
the exported ONNX graph accepts the same three integer inputs on iOS/Android.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import itertools
import json
import math
import random
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
import torch
import torch.nn.functional as F
from sentence_transformers import SentenceTransformer
from tokenizers import BertWordPieceTokenizer
from torch import nn
from torch.utils.data import DataLoader, Dataset
from transformers import BertConfig, BertModel, BertTokenizerFast
from unidecode import unidecode


TEMPLATES = (
    "songs by {}", "music by {}", "tracks by {}", "artist {}",
    "песни {}", "музыка {}", "треки {}", "исполнитель {}",
    "música de {}", "canciones de {}", "Musik von {}", "Lieder von {}",
    "musique de {}", "chansons de {}", "musica di {}", "canzoni di {}",
    "música de {}", "piosenki {}", "музика {}", "曲 {}", "{} の音楽",
    "{} 노래", "أغاني {}", "संगीत {}",
)

GENRE_QUERIES = {
    "rock": ("rock music", "рок", "рок-музыка", "música rock", "Rockmusik"),
    "russian rock": ("русский рок", "русский рок музыка", "Russian rock music"),
    "pop": ("pop music", "поп-музыка", "música pop", "Popmusik", "musique pop"),
    "j-pop": ("Japanese pop", "японская поп-музыка", "Jポップ"),
    "metal": ("metal music", "heavy metal", "метал", "тяжёлый металл"),
    "rap": ("rap music", "hip hop", "рэп", "хип-хоп"),
    "electronic": ("electronic music", "dance electronic", "электронная музыка"),
    "dance": ("dance music", "club music", "танцевальная музыка"),
    "ambient": ("ambient music", "music for sleeping", "спокойная фоновая музыка"),
    "classical": ("classical music", "классическая музыка", "musique classique"),
    "anime": ("anime music", "anime soundtrack", "музыка из аниме", "アニメ音楽"),
    "soundtrack": ("soundtrack", "film music", "музыка из фильма", "саундтрек"),
}


def text(value: object) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ""
    return " ".join(str(value).split())


def stable_bucket(value: str, buckets: int = 20) -> int:
    return int.from_bytes(hashlib.sha256(value.encode("utf-8")).digest()[:4], "big") % buckets


def entity_pairs(path: Path):
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as handle:
        for line in handle:
            entity = json.loads(line)
            canonical = text(entity["name"])
            if not canonical:
                continue
            split = "validation" if stable_bucket(entity["mbid"]) == 0 else "train"
            yield split, canonical, canonical, "canonical"
            aliases = [text(value) for value in entity.get("aliases", []) if text(value)]
            for alias in aliases[:8]:
                yield split, alias, canonical, "alias"
            for name in (canonical, *aliases[:4]):
                transliterated = unidecode(name).strip()
                if transliterated and transliterated.lower() != name.lower():
                    yield split, transliterated, canonical, "transliteration"
            for member in entity.get("members", [])[:12]:
                yield split, text(member), canonical, "member_group"
                yield split, f"songs by {text(member)}", canonical, "member_group"
            names = [canonical] + aliases[:2]
            for offset, name in enumerate(names):
                first = stable_bucket(entity["mbid"] + str(offset), len(TEMPLATES))
                for template in (TEMPLATES[first], TEMPLATES[(first + 7) % len(TEMPLATES)]):
                    yield split, template.format(name), canonical, "natural_artist"


def manifest_pairs(path: Path):
    frame = pd.read_parquet(path).reset_index(drop=True)
    for index, row in frame.iterrows():
        artist, title, genre, year = (text(row[k]) for k in ("artist", "title", "genre", "year"))
        document = "; ".join(part for part in (genre, artist, title, year) if part)
        if not document:
            continue
        split = "validation" if stable_bucket(str(row["track_id"])) == 0 else "train"
        for query, kind in ((artist, "artist"), (title, "title"), (genre, "genre")):
            if query:
                yield split, query, document, kind
                transliterated = unidecode(query).strip()
                if transliterated and transliterated.lower() != query.lower():
                    yield split, transliterated, document, "transliteration"
        normalized_genre = genre.lower().strip()
        for genre_key, queries in GENRE_QUERIES.items():
            if genre_key in normalized_genre:
                for query in queries:
                    yield split, query, document, "concept"
        if artist:
            for template in TEMPLATES:
                yield split, template.format(artist), document, "natural_artist"


def prepare(args: argparse.Namespace) -> None:
    args.output.mkdir(parents=True, exist_ok=True)
    pairs_path = args.output / "pairs.jsonl.gz"
    corpus_path = args.output / "tokenizer_corpus.txt"
    counts: dict[str, int] = {}
    with gzip.open(pairs_path, "wt", encoding="utf-8") as pairs, corpus_path.open(
        "w", encoding="utf-8"
    ) as corpus:
        for split, query, document, kind in itertools.chain(
            entity_pairs(args.entities), manifest_pairs(args.manifest)
        ):
            if not query or not document:
                continue
            pairs.write(json.dumps({"q": query, "d": document, "kind": kind, "split": split}, ensure_ascii=False) + "\n")
            corpus.write(query + "\n" + document + "\n")
            counts[split] = counts.get(split, 0) + 1

    tokenizer = BertWordPieceTokenizer(
        clean_text=True,
        handle_chinese_chars=True,
        strip_accents=False,
        lowercase=False,
    )
    tokenizer.train(
        files=[str(corpus_path)],
        vocab_size=args.vocab_size,
        min_frequency=2,
        limit_alphabet=6000,
        special_tokens=["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"],
        wordpieces_prefix="##",
    )
    tokenizer.save_model(str(args.output))
    (args.output / "prepare_stats.json").write_text(
        json.dumps({"pairs": counts, "vocab_size": tokenizer.get_vocab_size()}, indent=2) + "\n"
    )
    print(json.dumps({"pairs": counts, "vocab_size": tokenizer.get_vocab_size()}))


@dataclass(frozen=True)
class Pair:
    query: str
    document: str
    kind: str


class PairDataset(Dataset):
    def __init__(self, pairs: list[Pair]):
        self.pairs = pairs

    def __len__(self):
        return len(self.pairs)

    def __getitem__(self, index: int):
        return self.pairs[index]


class MusicEncoder(nn.Module):
    def __init__(self, config: BertConfig, output_dim: int = 256):
        super().__init__()
        self.bert = BertModel(config, add_pooling_layer=False)
        self.projection = nn.Linear(config.hidden_size, output_dim, bias=False)

    def forward(self, input_ids, attention_mask, token_type_ids):
        tokens = self.bert(
            input_ids=input_ids,
            attention_mask=attention_mask,
            token_type_ids=token_type_ids,
        ).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(tokens.dtype)
        pooled = (tokens * mask).sum(dim=1) / mask.sum(dim=1).clamp_min(1.0)
        return F.normalize(self.projection(pooled), dim=-1)


def load_pairs(path: Path) -> tuple[list[Pair], list[Pair]]:
    train, validation = [], []
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            pair = Pair(row["q"], row["d"], row["kind"])
            (validation if row["split"] == "validation" else train).append(pair)
    return train, validation


def collator(tokenizer: BertTokenizerFast, max_length: int):
    def collate(batch: list[Pair]):
        queries = ["query: " + pair.query for pair in batch]
        documents = ["passage: " + pair.document for pair in batch]
        encoded_query = tokenizer(
            queries, padding=True, truncation=True, max_length=max_length, return_tensors="pt"
        )
        encoded_document = tokenizer(
            documents, padding=True, truncation=True, max_length=max_length, return_tensors="pt"
        )
        return queries, documents, encoded_query, encoded_document

    return collate


@torch.no_grad()
def validate(model, teacher, loader, device, max_batches: int = 100):
    model.eval()
    losses = []
    positive_cosines = []
    for batch_index, (queries, documents, query_tokens, document_tokens) in enumerate(loader):
        if batch_index >= max_batches:
            break
        query_tokens = {key: value.to(device) for key, value in query_tokens.items()}
        document_tokens = {key: value.to(device) for key, value in document_tokens.items()}
        student_q = model(**query_tokens)
        student_d = model(**document_tokens)
        teacher_q = torch.as_tensor(teacher.encode(queries, normalize_embeddings=True), device=device)
        teacher_d = torch.as_tensor(teacher.encode(documents, normalize_embeddings=True), device=device)
        student_geometry = student_q @ student_d.T
        teacher_geometry = teacher_q @ teacher_d.T
        losses.append(F.mse_loss(student_geometry, teacher_geometry).item())
        positive_cosines.extend((student_q * student_d).sum(dim=-1).cpu().tolist())
    model.train()
    return {"geometry_mse": sum(losses) / max(1, len(losses)), "positive_cosine": sum(positive_cosines) / max(1, len(positive_cosines))}


def train(args: argparse.Namespace) -> None:
    random.seed(args.seed)
    torch.manual_seed(args.seed)
    train_pairs, validation_pairs = load_pairs(args.data / "pairs.jsonl.gz")
    random.shuffle(train_pairs)
    if args.priority_fraction > 0:
        target = args.max_pairs or len(train_pairs)
        priority_count = min(target, int(target * args.priority_fraction))
        base_count = target - priority_count
        base = train_pairs[:base_count]
        priority_kinds = ("title", "genre", "concept", "transliteration")
        priority = [pair for pair in train_pairs if pair.kind in priority_kinds]
        if priority:
            base.extend(priority[index % len(priority)] for index in range(priority_count))
        random.shuffle(base)
        train_pairs = base
    if args.balanced:
        by_kind: dict[str, list[Pair]] = {}
        for pair in train_pairs:
            by_kind.setdefault(pair.kind, []).append(pair)
        target = args.max_pairs or len(train_pairs)
        priority = ("title", "genre", "concept", "transliteration", "member_group", "alias", "artist", "canonical", "natural_artist")
        quota = max(1, target // len(priority))
        balanced = []
        for kind in priority:
            values = by_kind.get(kind, [])
            if not values:
                continue
            balanced.extend(values[index % len(values)] for index in range(quota))
        while len(balanced) < target:
            balanced.append(train_pairs[len(balanced) % len(train_pairs)])
        random.shuffle(balanced)
        train_pairs = balanced[:target]
    if args.max_pairs:
        train_pairs = train_pairs[: args.max_pairs]

    tokenizer = BertTokenizerFast(
        vocab_file=str(args.data / "vocab.txt"),
        do_lower_case=False,
        strip_accents=False,
        model_max_length=args.max_length,
    )
    config = BertConfig(
        vocab_size=tokenizer.vocab_size,
        hidden_size=args.hidden_size,
        num_hidden_layers=args.layers,
        num_attention_heads=args.heads,
        intermediate_size=args.intermediate_size,
        hidden_dropout_prob=0.1,
        attention_probs_dropout_prob=0.1,
        max_position_embeddings=64,
        type_vocab_size=2,
        pad_token_id=tokenizer.pad_token_id,
    )
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = MusicEncoder(config, output_dim=args.output_dim).to(device)
    if args.resume:
        resumed = torch.load(args.resume, map_location="cpu", weights_only=False)
        model.load_state_dict(resumed["model"])
    teacher = SentenceTransformer(args.teacher, device=str(device))
    teacher.eval()

    collate = collator(tokenizer, args.max_length)
    loader = DataLoader(
        PairDataset(train_pairs), batch_size=args.batch_size, shuffle=True,
        num_workers=4, pin_memory=True, drop_last=True, collate_fn=collate,
    )
    validation_loader = DataLoader(
        PairDataset(validation_pairs), batch_size=args.batch_size, shuffle=False,
        num_workers=2, pin_memory=True, drop_last=False, collate_fn=collate,
    )
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=0.01)
    scaler = torch.amp.GradScaler("cuda", enabled=device.type == "cuda")
    args.output.mkdir(parents=True, exist_ok=True)

    step = 0
    for epoch in range(args.epochs):
        model.train()
        running = 0.0
        for queries, documents, query_tokens, document_tokens in loader:
            query_tokens = {key: value.to(device, non_blocking=True) for key, value in query_tokens.items()}
            document_tokens = {key: value.to(device, non_blocking=True) for key, value in document_tokens.items()}
            with torch.no_grad():
                teacher_q = torch.as_tensor(
                    teacher.encode(queries, batch_size=args.batch_size, normalize_embeddings=True),
                    device=device,
                )
                teacher_d = torch.as_tensor(
                    teacher.encode(documents, batch_size=args.batch_size, normalize_embeddings=True),
                    device=device,
                )
                teacher_geometry = teacher_q @ teacher_d.T
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device.type, dtype=torch.float16, enabled=device.type == "cuda"):
                student_q = model(**query_tokens)
                student_d = model(**document_tokens)
                student_geometry = student_q @ student_d.T
                geometry_loss = F.mse_loss(student_geometry, teacher_geometry)
                positive_loss = (1.0 - (student_q * student_d).sum(dim=-1)).mean()
                loss = args.teacher_weight * geometry_loss + args.positive_weight * positive_loss
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(optimizer)
            scaler.update()
            running += loss.item()
            step += 1
            if step % 100 == 0:
                print(json.dumps({"epoch": epoch + 1, "step": step, "loss": running / 100}))
                running = 0.0

        report = validate(model, teacher, validation_loader, device)
        report.update({"epoch": epoch + 1, "step": step})
        print(json.dumps(report))
        torch.save(
            {"model": model.state_dict(), "config": config.to_dict(), "output_dim": args.output_dim, "report": report},
            args.output / f"student-epoch-{epoch + 1}.pt",
        )


def export(args: argparse.Namespace) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    payload = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    config = BertConfig.from_dict(payload["config"])
    model = MusicEncoder(config, payload["output_dim"])
    model.load_state_dict(payload["model"])
    model.eval()
    args.output.mkdir(parents=True, exist_ok=True)
    fp32_path = args.output / "text_encoder_music_256.fp32.onnx"
    int8_path = args.output / "text_encoder_music_256.onnx"
    length = 12
    inputs = (
        torch.ones((1, length), dtype=torch.long),
        torch.ones((1, length), dtype=torch.long),
        torch.zeros((1, length), dtype=torch.long),
    )
    torch.onnx.export(
        model,
        inputs,
        fp32_path,
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["embedding"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "tokens"},
            "attention_mask": {0: "batch", 1: "tokens"},
            "token_type_ids": {0: "batch", 1: "tokens"},
            "embedding": {0: "batch"},
        },
        opset_version=17,
        dynamo=False,
    )
    quantize_dynamic(
        fp32_path,
        int8_path,
        per_channel=True,
        reduce_range=False,
        weight_type=QuantType.QInt8,
    )
    (args.output / "text_vocab.txt").write_bytes(args.vocab.read_bytes())
    report = {
        "dimension": payload["output_dim"],
        "checkpoint": args.checkpoint.name,
        "fp32_bytes": fp32_path.stat().st_size,
        "int8_bytes": int8_path.stat().st_size,
        "training_report": payload.get("report"),
    }
    (args.output / "model_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--entities", type=Path, required=True)
    prepare_parser.add_argument("--manifest", type=Path, required=True)
    prepare_parser.add_argument("--output", type=Path, required=True)
    prepare_parser.add_argument("--vocab-size", type=int, default=32_000)

    train_parser = subparsers.add_parser("train")
    train_parser.add_argument("--data", type=Path, required=True)
    train_parser.add_argument("--output", type=Path, required=True)
    train_parser.add_argument("--teacher", default="intfloat/multilingual-e5-small")
    train_parser.add_argument("--hidden-size", type=int, default=256)
    train_parser.add_argument("--layers", type=int, default=4)
    train_parser.add_argument("--heads", type=int, default=8)
    train_parser.add_argument("--intermediate-size", type=int, default=768)
    train_parser.add_argument("--output-dim", type=int, default=256)
    train_parser.add_argument("--max-length", type=int, default=48)
    train_parser.add_argument("--batch-size", type=int, default=192)
    train_parser.add_argument("--epochs", type=int, default=3)
    train_parser.add_argument("--learning-rate", type=float, default=3e-4)
    train_parser.add_argument("--teacher-weight", type=float, default=2.0)
    train_parser.add_argument("--positive-weight", type=float, default=0.5)
    train_parser.add_argument("--max-pairs", type=int)
    train_parser.add_argument("--balanced", action="store_true")
    train_parser.add_argument("--priority-fraction", type=float, default=0.0)
    train_parser.add_argument("--resume", type=Path)
    train_parser.add_argument("--seed", type=int, default=20260720)
    export_parser = subparsers.add_parser("export")
    export_parser.add_argument("--checkpoint", type=Path, required=True)
    export_parser.add_argument("--vocab", type=Path, required=True)
    export_parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    arguments = parse_args()
    if arguments.command == "prepare":
        prepare(arguments)
    elif arguments.command == "train":
        train(arguments)
    else:
        export(arguments)
