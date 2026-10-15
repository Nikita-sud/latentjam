# SPDX-License-Identifier: Apache-2.0
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("fuzz_run", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ReplayTest(unittest.TestCase):
    def test_empty_or_directories_only_corpus_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaisesRegex(ValueError, "no seed files"):
                runner.replay_seeds(directory)
            (directory / "nested").mkdir()
            with self.assertRaisesRegex(ValueError, "no seed files"):
                runner.replay_seeds(directory)

    def test_zero_byte_inputs_are_valid_and_seed_order_is_stable(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "b").write_bytes(b"example")
            (directory / "a").write_bytes(b"")
            (directory / "nested").mkdir()
            self.assertEqual([directory / "a", directory / "b"], runner.replay_seeds(directory))

    def test_empty_replay_fails_before_gradle_or_jazzer_runs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tools/fuzz/corpus/m3u").mkdir(parents=True)
            with mock.patch.object(runner, "ROOT", root), mock.patch.object(runner.subprocess, "run") as run:
                with mock.patch("sys.argv", ["run.py", "m3u", "--jazzer", "jazzer", "--replay"]):
                    with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit:
                        runner.main()
            self.assertEqual(2, exit.exception.code)
            run.assert_not_called()

    def test_jazzer_failure_propagates_out_of_the_runner(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            seeds = root / "tools/fuzz/corpus/m3u"
            seeds.mkdir(parents=True)
            (seeds / "seed").write_bytes(b"crash")
            classpath = root / "composeApp/build/fuzz/classpath.txt"
            classpath.parent.mkdir(parents=True)
            classpath.write_text("compiled-classes")
            calls = []
            def run(command, **options):
                calls.append((command, options))
                if command[0] != "./gradlew":
                    self.assertTrue(options["check"])
                    raise subprocess.CalledProcessError(77, command)
            with mock.patch.object(runner, "ROOT", root), mock.patch.object(runner.subprocess, "run", run):
                with mock.patch("sys.argv", ["run.py", "m3u", "--jazzer", "jazzer", "--replay"]):
                    with self.assertRaises(subprocess.CalledProcessError) as failure:
                        runner.main()
            self.assertEqual(77, failure.exception.returncode)
            self.assertEqual(str(seeds / "seed"), calls[-1][0][-1])
            self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()
