# LatentJam contribution guidelines

LatentJam is a personal fork of [Auxio](https://github.com/OxygenCobalt/Auxio) (GPL-3.0) that adds on-device ML features. Contributions are welcome but kept narrow in scope — see below.

## Crashes & Bugs
Log them in the [Issues](https://github.com/Nikita-sud/latentjam/issues) tab.

When reporting an issue:
- **Has it been reported?** Search existing issues first.
- **Has it been already fixed?** Check the latest commit on `main`.
- **Is it still relevant in the latest version?** Test on the latest build before filing.

When you file, include:
- A clear description of the bug/crash
- Steps to reproduce
- A logcat trace if possible (the longer the better)

If you have Android/Kotlin experience, feel free to open a [Pull Request](https://github.com/Nikita-sud/latentjam/pulls) with the fix.

## Feature Requests
File these in the [Issues](https://github.com/Nikita-sud/latentjam/issues) tab too.

Before filing:
- **Has it already been requested?** Search first.
- **Has it been added?** Check the changelog.
- **Will it be accepted?** LatentJam inherits Auxio's [opinionated UX](https://github.com/OxygenCobalt/Auxio/wiki/Why-Are-These-Features-Missing%3F) — many feature requests are intentionally out of scope.

When you file, include:
- What you want
- The problem it solves
- Why it would benefit other users

If you want to implement the feature yourself, open an issue first so we can discuss whether it's in scope before you sink time into it.

## Code Contributions
- If you're picking up an existing bug, comment on the issue first so we don't duplicate work.
- New features: open an issue before coding so we can scope it.
- No non-free software (no binary blobs).
- Stick to [F-Droid Inclusion Guidelines](https://f-droid.org/wiki/page/Inclusion_Policy).
- Code must pass `./gradlew spotlessCheck` — formatting is enforced in CI.
- ***FULLY TEST*** your changes before opening a PR. Untested code will not be merged.
- Kotlin only, except where a UI component must be vendored from upstream.
- Keep your branch up to date with `main`. Resolve conflicts yourself before requesting review.

## Upstream Auxio changes
LatentJam tracks [Auxio](https://github.com/OxygenCobalt/Auxio) as an upstream remote. The `:musikr` module is deliberately kept under its original `org.oxycblt.musikr` namespace to make upstream merges clean — do not rename it. See [ARCHITECTURE_NOTES.md](../ARCHITECTURE_NOTES.md) for the module split.
