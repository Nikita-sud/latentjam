import ast
import os
from pathlib import Path
import sys
import numpy as np
import torch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
os.environ["DEVICE"] = "cpu"
import qat_nets as q


def actq_class():
    tree = ast.parse((TOOLS / "qat_audio.py").read_text())
    node = next(
        n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "ActQ"
    )
    ns = {"torch": torch, "nn": torch.nn}
    exec(
        compile(
            ast.Module(body=[node], type_ignores=[]),
            str(TOOLS / "qat_audio.py"),
            "exec",
        ),
        ns,
    )
    return ns["ActQ"]


def test_activation_quantization_survives_checkpoint():
    Q = actq_class()
    a = Q(False)
    a(torch.linspace(-1, 1, 1000))
    a.eval()
    b = Q(False)
    b.load_state_dict(a.state_dict())
    b.eval()
    x = torch.tensor([0.123456, 3.0])
    assert b.seen
    torch.testing.assert_close(a(x), b(x), rtol=0, atol=0)
    assert float(b(x)[1]) < 1.1


def test_legacy_activation_checkpoint_keeps_ranges_active():
    Q = actq_class()
    a = Q(False)
    a(torch.linspace(-1, 1, 1000))
    a.eval()
    old = {k: v for k, v in a.state_dict().items() if k != "_extra_state"}
    b = Q(False)
    b.load_state_dict(old)
    b.eval()
    torch.testing.assert_close(
        a(torch.tensor([3.0])), b(torch.tensor([3.0])), rtol=0, atol=0
    )


def test_state_target_moves_with_candidate_space():
    torch.manual_seed(2)
    old = torch.nn.functional.normalize(torch.randn(80, 5), dim=-1)
    rotation = torch.linalg.qr(torch.randn(5, 5)).Q
    new = old @ rotation
    data = {"matrix": old.numpy(), "student_matrix": new.numpy()}
    mapping = q.fit_space_map(data)
    state = torch.nn.functional.normalize(torch.randn(7, 5), dim=-1)
    task = object.__new__(q.StateTask)
    task.space_map = mapping
    task.teacher = lambda: state
    moved = task.target(())
    torch.testing.assert_close(moved @ new.T, state @ old.T, atol=1e-5, rtol=1e-5)
    assert (state @ new.T - state @ old.T).abs().mean() > 0.1


def test_identity_state_target_keeps_old_coordinates():
    torch.manual_seed(3)
    old = torch.nn.functional.normalize(torch.randn(40, 5), dim=-1)
    new = old @ torch.linalg.qr(torch.randn(5, 5)).Q
    data = {"matrix": old.numpy(), "student_matrix": new.numpy(), "state_target_map": "identity"}
    task = object.__new__(q.StateTask)
    task.space_map = q.state_space_map(data)
    state = torch.nn.functional.normalize(torch.randn(3, 5), dim=-1)
    task.teacher = lambda: state
    assert task.space_map is None
    torch.testing.assert_close(task.target(()), state, rtol=0, atol=0)
    data["state_target_map"] = "dual"
    assert q.state_space_map(data) is not None


def test_scorer_reads_the_new_state_and_preserves_text(monkeypatch):
    class FakeState(torch.nn.Module):
        def __init__(self, *args):
            super().__init__()

        @staticmethod
        def split(x):
            return (x,)

        def forward(self, x):
            return x[:, :1].expand(-1, 960)

    monkeypatch.setattr(q.nets, "StateNet", FakeState)
    monkeypatch.setattr(q.nets, "Scorer", FakeState)
    monkeypatch.setattr(q, "moved_states", lambda data, x: x)
    old = np.zeros((2, 1344), np.float32)
    old[:, 960:] = 0.7
    data = {
        "s_state": old,
        "s_rows": np.array([[0], [1]]),
        "matrix": np.zeros((2, 1344), np.float32),
        "student_matrix": np.ones((2, 1344), np.float32),
        "student_state": "new.onnx",
        "e_in": np.array([[0.1], [0.2]], np.float32),
        "e_out": old[:, :960],
    }
    task = q.ScorerTask(data)
    original, heard, _, _, _ = task.batch(np.array([0, 1]))
    torch.testing.assert_close(original[:, :960], torch.zeros(2, 960))
    torch.testing.assert_close(
        heard[:, :960], torch.tensor([[0.1], [0.2]]).expand(-1, 960), atol=1e-4, rtol=0
    )
    torch.testing.assert_close(heard[:, 960:], original[:, 960:])


def test_query_map_preserves_rankings_for_nonorthogonal_change():
    torch.manual_seed(13)
    old = torch.randn(80, 5)
    transform = torch.diag(torch.tensor([0.3, 0.7, 1.0, 2.0, 4.0]))
    new = old @ transform
    query = torch.randn(12, 5)
    data = {"matrix": old.numpy(), "student_matrix": new.numpy()}
    moved = query @ q.fit_query_map(data)
    torch.testing.assert_close(moved @ new.T, query @ old.T, rtol=1e-5, atol=1e-5)
    incorrect = query @ q.fit_space_map(data)
    assert (incorrect @ new.T - query @ old.T).abs().mean() > 1.0


def test_catalog_audit_detects_shared_evaluation_tracks(tmp_path, monkeypatch):
    import json
    import data

    features = tmp_path / "features"
    folder = features / "train_one.hash"
    folder.mkdir(parents=True)
    (folder / "ids.txt").write_text("shared\ntraining-only\n")
    (tmp_path / "libraries").mkdir()
    (tmp_path / "libraries/held.json").write_text(json.dumps({"rows": [{"id": "shared"}, {"id": "test-only"}]}))
    monkeypatch.setattr(data, "FEATURES", features)
    monkeypatch.setattr(data, "ROOT", tmp_path)
    audit = data.split_audit(["train_one"], ["held", "unavailable"])
    assert audit["evaluation_catalog_overlap"] == {"held": 1}
    assert audit["missing_evaluation_libraries"] == ["unavailable"]


def test_head_selection_detects_wrong_genres_despite_same_music_argmax():
    task = object.__new__(q.HeadTask)
    task.audio_scale = torch.ones(3)
    task.fma_scale = torch.ones(2)
    teacher_audio = torch.tensor([[10.0, 1.0, 0.0], [10.0, 0.0, 1.0]])
    teacher_fma = torch.tensor([[3.0, -3.0], [-3.0, 3.0]])
    bad_audio = teacher_audio + torch.tensor([0.0, 2.0, -2.0])
    bad_fma = -teacher_fma
    assert torch.equal(bad_audio.argmax(1), teacher_audio.argmax(1))
    assert task.logit_error(teacher_audio, teacher_fma, teacher_audio, teacher_fma) == 0
    assert task.logit_error(bad_audio, bad_fma, teacher_audio, teacher_fma) > 1
