# Teacher bench

Which offline model would teach the music encoder something it does not know yet, measured before any
distillation (October 2026). The results and the decision are in `docs/model-diet-plan.md` (phase 3, second round).

The bench folder (env `TEACHER_BENCH`, default `~/Documents/LJ/teacher-bench-2026-10-05`) lives outside the
repository: `manifests/<library>.json` (`{id, path, remote}` per track of the bundle report's libraries), `out/`
(vectors), `vendor/` (github.com/minzwon/musicfm, EfficientAT and its two AudioSet checkpoints) and `layers.json`
(the layer each space is read at).

1. `embed.py TEACHER[,...] LIBRARY[,...]` embeds every track the way the app does (three 10 s windows at 20, 50 and
   80 %, each window's per-layer time mean, summed, unit length). Teachers: `mn10`, `dymn20` (the encoder's own,
   AudioSet), `musicfm_msd`, `musicfm_fma` (MIT), `muq`, `mert330` (CC-BY-NC-4.0, measured only), `dasheng_base`,
   `dasheng_06b` (Apache-2.0), `clap_music` (Apache-2.0; its vectors were degenerate through transformers 4.46).
   Public audio goes to a GPU pod (`mktar.py` streams it, `setup_pod.sh` installs the packages, `--remote`); the
   owner's library is embedded on the Mac only.
2. `probe.py [--center] SPACE ...` scores a space by cosine nearest neighbours: MPD playlist co-membership P@10 on
   six libraries (a layer chosen on two, reported on four, also with the seed's artist removed), the owner's
   library (playlists, same artist, next track of the listening history) and FMA-small genre (logistic regression,
   10-NN), paired-bootstrapped against 0.7.1. `a+b` joins two centred spaces; `encoders.json` registers more
   app-format encoders.
3. `probe_proj.py SPACE ...` gives every space the same learned 256-d map (InfoNCE on three MPD libraries' playlists,
   tested on the other three, then applied to the owner's library): the information a space holds, apart from
   its geometry.

`qat_audio.py`'s `MIX` options distil a joined space into the encoder.
