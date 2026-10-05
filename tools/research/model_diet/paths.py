"""Where the model-diet tools find their inputs and put their outputs.

WORK (env MODEL_DIET_WORK) is the working folder the bundle report also uses: baseline/ml (the frozen
0.7.1 assets), libraries/, runs/, smart/ (the SMART runner), candidates/ and phase1/ (teachers/, data/,
runs/). BENCH and RESEARCH are the smart-bench and latentjam-research checkouts the MPD libraries and the
text student come from. Defaults are the maintainer's; set the variables elsewhere.
"""
import os
from pathlib import Path

WORK = Path(os.environ.get("MODEL_DIET_WORK", "~/Documents/LJ/model-diet-2026-10-04")).expanduser()
PHASE = WORK / "phase1"
BENCH = Path(os.environ.get("MODEL_DIET_BENCH", "~/Documents/LJ/smart-bench-2026-09-24")).expanduser()
RESEARCH = Path(os.environ.get("MODEL_DIET_RESEARCH", "~/Documents/LJ/latentjam-research")).expanduser()
TEXT_STUDENT = Path(os.environ.get("TEXT_STUDENT", BENCH / "text_student")).expanduser()
