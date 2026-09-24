"""Export a trained student to ONNX with MiniLM's interface, then dynamic INT8 (MatMulInteger).

    python3 export.py s256x3m   # writes student_fp32.onnx, student_int8.onnx and vocab.txt into s256x3m/
"""
import json, os, shutil, sys
import torch
from onnxruntime.quantization import quantize_dynamic, QuantType
name = sys.argv[1]
import importlib.util
cfg = json.load(open(f"{name}/config.json"))
sys.argv = ["train.py", name, str(cfg["vocab"]), str(cfg["hidden"]), str(cfg["layers"]), str(cfg["heads"]), str(cfg["ff"]), "0"]
from transformers import BertConfig, BertModel
import torch.nn as nn


class Student(nn.Module):
    def __init__(self):
        super().__init__()
        c = BertConfig(vocab_size=cfg["vocab"], hidden_size=cfg["hidden"], num_hidden_layers=cfg["layers"],
                       num_attention_heads=cfg["heads"], intermediate_size=cfg["ff"], max_position_embeddings=64,
                       type_vocab_size=2, hidden_act="gelu", attn_implementation="eager")
        self.bert = BertModel(c, add_pooling_layer=False)
        self.proj = nn.Linear(cfg["hidden"], 384)

    def forward(self, input_ids, attention_mask, token_type_ids):
        return self.proj(self.bert(input_ids=input_ids, attention_mask=attention_mask,
                                   token_type_ids=token_type_ids).last_hidden_state)


m = Student(); m.load_state_dict(torch.load(f"{name}/student.pt", map_location="cpu")); m.eval()
x = torch.ones(2, 8, dtype=torch.int64)
torch.onnx.export(m, (x, x, torch.zeros_like(x)), f"{name}/student_fp32.onnx", input_names=["input_ids", "attention_mask", "token_type_ids"],
                  output_names=["last_hidden_state"], dynamic_axes={k: {0: "batch", 1: "seq"} for k in ("input_ids", "attention_mask", "token_type_ids", "last_hidden_state")},
                  opset_version=17, dynamo=False)
quantize_dynamic(f"{name}/student_fp32.onnx", f"{name}/student_int8.onnx", weight_type=QuantType.QInt8, per_channel=False)
shutil.copy(cfg.get("vocab_file") or f"vocab_{cfg['vocab']}/vocab.txt", f"{name}/vocab.txt")
for f in ("student_fp32.onnx", "student_int8.onnx", "vocab.txt"):
    print(f, f"{os.path.getsize(f'{name}/{f}') / 1e6:.2f} MB")
