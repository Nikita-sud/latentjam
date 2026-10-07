#!/usr/bin/env python3
"""Build the standalone GitHub Pages site using only public, allowlisted assets."""

from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build" / "site"
MEDIA = ("player.png", "for-you.png", "lyrics.png", "map-landscape.png", "player-demo.mp4")


def main():
    if OUTPUT.is_symlink():
        raise SystemExit(f"Refusing to build through a symbolic link: {OUTPUT}")
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    assets = OUTPUT / "assets"
    assets.mkdir(exist_ok=True)
    for name in ("index.html", "styles.css", "script.js"):
        shutil.copyfile(ROOT / "website" / name, OUTPUT / name)
    shutil.copyfile(ROOT / "branding" / "logo.svg", assets / "logo.svg")
    for name in MEDIA:
        shutil.copyfile(ROOT / "docs" / "media" / name, assets / name)
    for name in ("Alsina-Ultrajada.ttf", "Alsina-LICENSE.txt"):
        shutil.copyfile(ROOT / "website" / "fonts" / name, assets / name)
    (OUTPUT / ".nojekyll").touch()
    print(f"Site ready: {OUTPUT}")


if __name__ == "__main__":
    main()
