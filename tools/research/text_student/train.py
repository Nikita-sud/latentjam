"""Distill MiniLM (tools/research/minilm) into a tiny BERT on the app's text domain.

Output contract matches MiniLM's ONNX: token-level hidden states [batch, seq, 384] that the caller mean-pools
under the attention mask and L2-normalizes. A per-token Linear(hidden -> 384) makes that pooling land in
MiniLM's space, so the scorer net, the pack adapter and search keep working unchanged.
Usage: train.py <name> <vocab> <hidden> <layers> <heads> <ff> <epochs>"""
import json, math, os, sys, time
import numpy as np, torch, torch.nn as nn, torch.nn.functional as F
from tokenizers import BertWordPieceTokenizer
from transformers import BertConfig, BertModel

name, VOCAB, H, L, NH, FF, EPOCHS = sys.argv[1], *map(int, sys.argv[2:8])
dev = "mps" if torch.backends.mps.is_available() else "cpu"
torch.manual_seed(0); np.random.seed(0)
strings = json.load(open("strings.json")); Y = np.load("teacher.npy").astype(np.float32)
INIT = os.environ.get("INIT")
if os.environ.get("AUG"):
    aug_s = json.load(open("aug_strings.json")); aug_y = np.load("aug_teacher.npy").astype(np.float32)
    strings = strings + aug_s; Y = np.concatenate([Y, aug_y])
if os.environ.get("ML"):
    mp = json.load(open("ml_pairs.json"))["train"]; my = np.load("ml_train_teacher.npy").astype(np.float32)
    rep = int(os.environ.get("ML_REPEAT", "20"))
    strings = strings + [p for p, _, _ in mp] * rep; Y = np.concatenate([Y] + [my] * rep)
os.makedirs(name, exist_ok=True)
tok_path = f"vocab_{VOCAB}"
VOCAB_FILE = os.environ.get("VOCAB_FILE")
if VOCAB_FILE:
    VOCAB = sum(1 for _ in open(VOCAB_FILE, encoding="utf-8"))
if VOCAB_FILE:
    pass
elif not os.path.exists(f"{tok_path}/vocab.txt"):
    os.makedirs(tok_path, exist_ok=True)
    t = BertWordPieceTokenizer(lowercase=True, strip_accents=True)
    t.train_from_iterator(strings, vocab_size=VOCAB, min_frequency=2,
                          special_tokens=["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"])
    t.save_model(tok_path)
tok = BertWordPieceTokenizer(VOCAB_FILE or f"{tok_path}/vocab.txt", lowercase=True, strip_accents=True)
tok.enable_truncation(48)
ids = [e.ids for e in tok.encode_batch(strings)]
n = len(ids); perm = np.random.permutation(n); val = perm[:10_000]; train = perm[10_000:]


class Student(nn.Module):
    def __init__(self):
        super().__init__()
        cfg = BertConfig(vocab_size=VOCAB, hidden_size=H, num_hidden_layers=L, num_attention_heads=NH,
                         intermediate_size=FF, max_position_embeddings=64, type_vocab_size=2,
                         hidden_act="gelu", attn_implementation="eager")
        self.bert = BertModel(cfg, add_pooling_layer=False)
        self.proj = nn.Linear(H, 384)

    def forward(self, input_ids, attention_mask, token_type_ids):
        h = self.bert(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids).last_hidden_state
        return self.proj(h)                      # [b, seq, 384], pooled by the caller like MiniLM


def batch(idx):
    seqs = [ids[i] for i in idx]; w = max(len(s) for s in seqs)
    x = np.zeros((len(seqs), w), np.int64); m = np.zeros_like(x)
    for r, s in enumerate(seqs):
        x[r, :len(s)] = s; m[r, :len(s)] = 1
    return torch.tensor(x, device=dev), torch.tensor(m, device=dev), torch.tensor(Y[idx], device=dev)


def pooled(model, x, m):
    h = model(x, m, torch.zeros_like(x))
    p = (h * m.unsqueeze(-1)).sum(1) / m.sum(1, keepdim=True).clamp(min=1)
    return F.normalize(p, dim=-1)


model = Student().to(dev)
if INIT:
    state = torch.load(INIT, map_location="cpu")
    key = "bert.embeddings.word_embeddings.weight"
    if state[key].shape[0] != VOCAB:
        old = state[key]; grown = torch.randn(VOCAB, old.shape[1]) * 0.02; grown[:old.shape[0]] = old; state[key] = grown
    model.load_state_dict(state)
    print(f"initialized from {INIT}", flush=True)
params = sum(p.numel() for p in model.parameters())
print(f"{name}: vocab {VOCAB} hidden {H} layers {L} -> {params / 1e6:.2f}M params; {len(train)} train strings on {dev}", flush=True)
LR = float(os.environ.get('LR', '1e-3'))
opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=0.01)
BS = 512; steps = EPOCHS * math.ceil(len(train) / BS)
sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=steps, pct_start=0.05)
# length-bucketed batches: sort by token count within shuffled chunks
lengths = np.array([len(s) for s in ids])
best = -1
for ep in range(EPOCHS):
    model.train(); t0 = time.time()
    order = np.random.permutation(train)
    chunks = [order[i:i + BS * 50] for i in range(0, len(order), BS * 50)]
    batches = []
    for c in chunks:
        c = c[np.argsort(lengths[c])]
        batches += [c[i:i + BS] for i in range(0, len(c), BS)]
    np.random.shuffle(batches)
    for b in batches:
        x, m, y = batch(b)
        loss = (1 - (pooled(model, x, m) * y).sum(-1)).mean()
        opt.zero_grad(); loss.backward(); opt.step(); sched.step()
    model.eval(); cs = []
    with torch.no_grad():
        for i in range(0, len(val), 1024):
            x, m, y = batch(val[i:i + 1024]); cs.append((pooled(model, x, m) * y).sum(-1).cpu().numpy())
    cos = float(np.concatenate(cs).mean())
    print(f"epoch {ep + 1}/{EPOCHS}: val cos to MiniLM {cos:.4f} ({time.time() - t0:.0f}s)", flush=True)
    if cos > best:
        best = cos; torch.save(model.state_dict(), f"{name}/student.pt")
json.dump({"vocab_file": VOCAB_FILE, "vocab": VOCAB, "hidden": H, "layers": L, "heads": NH, "ff": FF, "params": params, "val_cos": best},
          open(f"{name}/config.json", "w"))
print(f"best val cos {best:.4f}", flush=True)
