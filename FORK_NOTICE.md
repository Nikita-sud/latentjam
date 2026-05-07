# Fork Notice

LatentJam is a fork of [Auxio](https://github.com/oxygencobalt/Auxio) by Alexander Capehart (OxygenCobalt).

The upstream project is licensed under the GNU General Public License v3.0. As a derivative work, LatentJam inherits the same license — see [LICENSE](LICENSE).

## Attribution

All original Auxio source code, copyright notices, and design decisions are credited to the upstream authors. Per the GPL-3 license, those notices remain intact in every file they appear in. Where this fork substantially modifies a file, an additional copyright line is added; otherwise, files retain their original headers unchanged.

## What this fork changes

- Application identity (`applicationId`, display name, FileProvider authority) so a LatentJam APK can coexist on a device with upstream Auxio.
- Eventually: on-device, privacy-first music recommendations driven by a small ML model. This work has not begun yet.

The `dev` branch of this repository is kept as a clean mirror of `upstream/dev`. All fork-specific changes live on `rebrand-to-latentjam` and successor branches.

## Upstream

- Source: <https://github.com/oxygencobalt/Auxio>
- License: GPL-3.0-or-later

If you want the original, well-maintained Auxio — go there. LatentJam is an experimental personal fork.
