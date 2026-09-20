from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest import mock

import numpy as np
from mutagen.id3 import TCON
from mutagen._vorbis import VCommentDict


SCRIPT = Path(__file__).resolve().parents[1] / "embed_with_shipped_encoder.py"
SPEC = importlib.util.spec_from_file_location("embed_with_shipped_encoder", SCRIPT)
assert SPEC and SPEC.loader
embedder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = embedder
SPEC.loader.exec_module(embedder)


class WindowTests(unittest.TestCase):
    def test_window_starts_use_integer_milliseconds_before_sample_offsets(self):
        self.assertEqual(embedder.window_starts_ms(None), [0])
        self.assertEqual(embedder.window_starts_ms(9_000), [0])
        self.assertEqual(embedder.window_starts_ms(10_000), [0])
        self.assertEqual(embedder.window_starts_ms(10_001), [0, 0, 0])
        self.assertEqual(embedder.window_starts_ms(30_007), [4001, 10003, 16005])
        waveform = np.arange(960_224, dtype=np.float32)
        windows = embedder.cut_windows(waveform, 30_007)
        self.assertEqual([int(window[0]) for window in windows], [128032, 320096, 512160])
        self.assertTrue(all(window.shape == (320_000,) for window in windows))

    def test_short_audio_is_zero_padded(self):
        waveform = np.asarray([0.25, -0.5, 1.0], dtype=np.float32)
        windows = embedder.cut_windows(waveform, 1)
        self.assertEqual(len(windows), 1)
        np.testing.assert_array_equal(windows[0][:3], waveform)
        self.assertFalse(windows[0][3:].any())

    def test_metadata_past_eof_does_not_manufacture_silent_windows(self):
        waveform = np.full(70 * 32_000, 0.5, dtype=np.float32)
        windows = embedder.cut_windows(waveform, 235_000)
        self.assertEqual(len(windows), 1)
        self.assertTrue(np.all(windows[0] == 0.5))
        self.assertEqual(embedder.cut_windows(np.zeros(0, dtype=np.float32), 20_000), [])

    def test_partial_window_is_padded_when_some_pcm_exists(self):
        waveform = np.ones(65 * 32_000, dtype=np.float32)
        windows = embedder.cut_windows(waveform, 90_000)
        self.assertEqual(len(windows), 3)
        self.assertTrue(np.all(windows[2][:32_000] == 1.0))
        self.assertFalse(windows[2][32_000:].any())

    def test_retry_gain_and_sequential_sum_normalization(self):
        class Session:
            def __init__(self):
                self.calls = []

            def run(self, outputs, inputs):
                self.calls.append(float(inputs["waveform"][0, 0]))
                vector = np.zeros((1, 960), dtype=np.float32)
                if len(self.calls) == 1:
                    vector[:] = np.nan
                elif len(self.calls) == 2:
                    vector[0, 0] = 1.0
                else:
                    vector[0, 1] = 1.0
                return [vector]

        session = Session()
        windows = [np.ones(320_000, dtype=np.float32), np.full(320_000, 0.25, dtype=np.float32)]
        vector = embedder.infer_track(session, windows, None)
        self.assertEqual(session.calls, [1.0, 0.5, 0.25])
        np.testing.assert_allclose(vector[:2], [2 ** -0.5, 2 ** -0.5], atol=1e-7)
        self.assertFalse(vector[2:].any())

    def test_head_fallback_only_after_all_preferred_windows_fail(self):
        class Session:
            def __init__(self):
                self.calls = 0

            def run(self, outputs, inputs):
                self.calls += 1
                vector = np.zeros((1, 960), dtype=np.float32)
                if float(inputs["waveform"][0, 0]) == 2.0:
                    vector[0, 10] = 1.0
                return [vector]

        session = Session()
        vector = embedder.infer_track(session, [np.ones(320_000, dtype=np.float32)], np.full(320_000, 2.0, dtype=np.float32))
        self.assertEqual(session.calls, 4)
        self.assertEqual(float(vector[10]), 1.0)

    def test_no_valid_embedding_is_an_error(self):
        class Session:
            def run(self, outputs, inputs):
                return [np.zeros((1, 960), dtype=np.float32)]

        with self.assertRaisesRegex(ValueError, "No usable window"):
            embedder.infer_track(Session(), [np.zeros(320_000, dtype=np.float32)], None)


class LabelTests(unittest.TestCase):
    def test_exact_aliases_unknown_and_multiclass_tags(self):
        self.assertEqual(embedder.map_genres(["Hip-Hop", "Rap"]), ("Hip-Hop", ""))
        self.assertEqual(embedder.map_genres(["estradă"]), ("Pop", ""))
        self.assertEqual(embedder.map_genres(["эстрада"]), ("Pop", ""))
        self.assertEqual(embedder.map_genres(["шансон"]), (None, "unmapped_tag"))
        self.assertEqual(embedder.map_genres(["Pop", "unlisted"]), (None, "unmapped_tag"))
        self.assertEqual(embedder.map_genres(["Pop", "Rock"]), (None, "multiple_broad_genres"))
        self.assertEqual(embedder.map_genres([]), (None, "no_genre_tags"))
        self.assertEqual(embedder.map_genres(["ambient instrumental"]), ("Instrumental", ""))
        self.assertEqual(embedder.map_genres(["ambient"]), (None, "unmapped_tag"))

    def test_tag_delimiters_and_id3_numeric_genres(self):
        self.assertEqual(embedder.split_genre_tags(["rock; metal / punk,grunge", "rock"]), ["grunge", "metal", "punk", "rock"])
        self.assertEqual(embedder.tag_values({"TCON": TCON(text=["17"])}, ("TCON",)), ["Rock"])

    def test_specific_soundtrack_and_style_aliases_preserve_exclusions(self):
        aliases = {
            "Instrumental": ["Anime OST", "Game OST", "Epic Orchestral", "TV Score"],
            "Rock": ["Russian Rock", "Soft Rock", "Arena Rock"],
            "Electronic": ["Euro-Disco"],
            "Hip-Hop": ["Brazilian Phonk"],
        }
        for broad, tags in aliases.items():
            for tag in tags:
                with self.subTest(tag=tag):
                    self.assertEqual(embedder.map_genres([tag]), (broad, ""))
                    self.assertEqual(embedder.map_genres([tag, "Production Music"]), (None, "unmapped_tag"))
        for ambiguous in ["Anime", "Ambient", "Funk", "R&B", "Soul", "Production Music"]:
            with self.subTest(ambiguous=ambiguous):
                self.assertEqual(embedder.map_genres([ambiguous]), (None, "unmapped_tag"))
        self.assertEqual(embedder.map_genres(["Anime OST", "Soft Rock"]), (None, "multiple_broad_genres"))

    def test_vorbis_does_not_query_invalid_id3_or_mp4_keys(self):
        tags = VCommentDict()
        tags["GENRE"] = ["Rock"]
        tags["ARTIST"] = ["Synthetic artist"]
        self.assertEqual(embedder.tag_values(tags, ("TCON", "genre", "GENRE", "©gen", "WM/Genre")), ["Rock"])
        self.assertEqual(embedder.tag_values(tags, ("TPE1", "artist", "ARTIST", "©ART", "aART")), ["Synthetic artist"])

    def test_mapping_has_no_conflicting_normalized_aliases(self):
        collected = {}
        for genre, aliases in embedder.GENRE_ALIASES.items():
            for alias in aliases:
                normalized = embedder.normalize_tag(alias)
                self.assertIn(collected.get(normalized), (None, genre))
                collected[normalized] = genre

    def test_collaborators_are_artist_disjoint_and_reproducible(self):
        credits = [{f"artist-{index}"} for index in range(50)]
        credits[1] = {"artist-0", "artist-1"}
        credits[2] = {"artist-1", "artist-2"}
        credits[3] = {"__unknown_artist__"}
        credits[4] = {"__unknown_artist__"}
        splits, groups = embedder.artist_disjoint_splits(credits, seed=42)
        self.assertEqual((splits, groups), embedder.artist_disjoint_splits(credits, seed=42))
        self.assertEqual(len(set(splits[:3])), 1)
        self.assertEqual(len(set(groups[:3])), 1)
        self.assertEqual(groups[3], groups[4])
        artists_per_split = {name: set() for name in ("training", "validation", "test")}
        for split, artists in zip(splits, credits):
            artists_per_split[split].update(artists)
        self.assertFalse(artists_per_split["training"] & artists_per_split["validation"])
        self.assertFalse(artists_per_split["training"] & artists_per_split["test"])
        self.assertFalse(artists_per_split["validation"] & artists_per_split["test"])
        self.assertEqual([splits.count(name) for name in ("training", "validation", "test")], [40, 5, 5])

    def test_track_identifier_is_stable_and_opaque(self):
        identifier = embedder.stable_track_id("synthetic/example.mp3")
        self.assertEqual(identifier, embedder.stable_track_id("synthetic/example.mp3"))
        self.assertEqual(len(identifier), 64)
        self.assertNotEqual(identifier, embedder.stable_track_id("synthetic/another.mp3"))


class LicenseTests(unittest.TestCase):
    def test_allowed_families_and_all_nc_nd_rejected(self):
        for text, family in [("Attribution", "CC BY"), ("Attribution-Share Alike 2.0", "CC BY-SA"), ("CC0 1.0 Universal", "CC0"), ("Public Domain Mark 1.0", "Public Domain")]:
            self.assertTrue(embedder.permissive_license(text))
            self.assertEqual(embedder.license_family(text), family)
        for text in ("Attribution-NonCommercial", "Attribution-Noncommercial-ShareAlike", "Attribution-NoDerivatives", "Attribution-No Derivative Works", "All rights reserved", ""):
            self.assertFalse(embedder.permissive_license(text))


class DecodeTests(unittest.TestCase):
    def test_nonzero_decoder_exit_rejects_even_partial_pcm(self):
        source = embedder.Source("synthetic", "synthetic/example.mp3", "example.mp3", 5000)
        decoded = SimpleNamespace(returncode=1, stdout=np.ones(32000, dtype="<f4").tobytes(), stderr=b"decoder failure")
        with mock.patch.object(embedder.subprocess, "run", return_value=decoded):
            track_id, windows, fallback, error = embedder.decode_source((source, "ffmpeg", "ffprobe"))
        self.assertEqual(track_id, "synthetic")
        self.assertIsNone(windows)
        self.assertIsNone(fallback)
        self.assertIn("decoder exited with status 1", error)

    def test_successful_decode_warning_preserves_real_partial_pcm(self):
        source = embedder.Source("synthetic", "synthetic/example.mp3", "example.mp3", 5000)
        decoded = SimpleNamespace(returncode=0, stdout=np.ones(32000, dtype="<f4").tobytes(), stderr=b"recoverable warning")
        with mock.patch.object(embedder.subprocess, "run", return_value=decoded):
            _, windows, fallback, error = embedder.decode_source((source, "ffmpeg", "ffprobe"))
        self.assertIsNone(error)
        self.assertIsNone(fallback)
        self.assertEqual(len(windows), 1)
        self.assertTrue(np.all(windows[0][:32000] == 1.0))
        self.assertFalse(windows[0][32000:].any())


if __name__ == "__main__":
    unittest.main()
