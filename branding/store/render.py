#!/usr/bin/env python3
"""Renders the F-Droid store images from branding/store/raw.

    python3 branding/store/render.py

Writes fastlane/metadata/android/en-US/images/icon.png (512x512, rounded
corners on transparency, like branding/logo.svg), featureGraphic.png (1024x500)
and the captioned phoneScreenshots (1080x2340). Needs Google Chrome; the
headline font is the website's Alsina.
"""

import html
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
RAW = ROOT / "branding/store/raw"
FONT = ROOT / "website/fonts/Alsina-Ultrajada.ttf"
LOGO = ROOT / "branding/logo.svg"
OUT = ROOT / "fastlane/metadata/android/en-US/images"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# (output name, raw screenshot, headline, subline) in store order.
SHOTS = [
    ("1-smart", "2-player.png", "SMART picks the next song", "From your own files, by how they sound"),
    ("2-for-you", "1-for-you.png", "For You, with no account", "Rows for this hour, made on the phone"),
    ("3-lyrics", "3-lyrics.png", "Lyrics that follow along", "Tap a line to jump there"),
    ("4-playlists", "4-playlists.png", "Playlists, M3U in and out", "Custom order, recently added, favorites"),
    ("5-tracks", "6-tracks-light.png", "Light or dark, A–Z rail", "Albums, artists, genres and folders"),
    ("6-map", "5-map.png", "Your library as a map", "Clusters are genres as the model hears them"),
    ("7-statistics", "7-statistics.png", "Your listening, counted locally", "Nothing leaves the phone"),
    ("8-pages", "8-pages.png", "Your pages, your order", "Show, hide and reorder what you browse"),
]

BASE_CSS = f"""
@font-face {{ font-family: Alsina; src: url("{FONT.as_uri()}"); }}
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
html, body {{ width: 100%; height: 100%; overflow: hidden; background: #0b0b0b; color: #f2f2f2;
  font-family: -apple-system, "Helvetica Neue", Roboto, Arial, sans-serif; }}
.glow {{ position: absolute; border-radius: 50%; filter: blur(120px); opacity: .55; }}
"""


def shot_html(raw: pathlib.Path, headline: str, subline: str) -> str:
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{BASE_CSS}
body {{ position: relative; }}
.g1 {{ width: 900px; height: 900px; left: -380px; top: -260px; background: #8E24AA; }}
.g2 {{ width: 900px; height: 900px; right: -420px; top: 420px; background: #00ACC1; opacity: .4; }}
.text {{ position: absolute; left: 90px; right: 90px; top: 150px; text-align: center; }}
h1 {{ font-family: Alsina, sans-serif; font-weight: 400; font-size: 100px; line-height: 1.08; letter-spacing: .01em; text-wrap: balance; }}
p {{ margin-top: 34px; font-size: 44px; line-height: 1.3; color: #b4b4b4; }}
.phone {{ position: absolute; left: 50%; top: 610px; width: 840px; transform: translateX(-50%);
  border-radius: 56px; overflow: hidden; border: 3px solid #333;
  box-shadow: 0 40px 120px rgba(0,0,0,.7), 0 0 0 14px #161616; }}
.phone img {{ display: block; width: 100%; }}
</style></head><body>
<div class="glow g1"></div><div class="glow g2"></div>
<div class="text"><h1>{html.escape(headline)}</h1><p>{html.escape(subline)}</p></div>
<div class="phone"><img src="{raw.as_uri()}"></div>
</body></html>"""


def feature_html() -> str:
    player = (RAW / "2-player.png").as_uri()
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{BASE_CSS}
body {{ position: relative; }}
.g1 {{ width: 520px; height: 520px; left: -160px; top: -220px; background: #8E24AA; filter: blur(110px); }}
.g2 {{ width: 520px; height: 520px; left: 520px; top: 160px; background: #00ACC1; opacity: .35; filter: blur(110px); }}
.left {{ position: absolute; left: 64px; top: 0; bottom: 0; width: 560px;
  display: flex; flex-direction: column; justify-content: center; }}
.brand {{ display: flex; align-items: center; gap: 22px; }}
.brand img {{ width: 92px; height: 92px; }}
h1 {{ font-family: Alsina, sans-serif; font-weight: 400; font-size: 84px; line-height: 1; }}
.tag {{ margin-top: 30px; font-size: 33px; line-height: 1.25; font-weight: 600; }}
.pills {{ margin-top: 24px; display: flex; gap: 8px; flex-wrap: nowrap; }}
.pills span {{ font-size: 18px; padding: 6px 13px; border-radius: 99px; border: 1.5px solid #3a3a3a;
  color: #c9c9c9; background: rgba(20,20,20,.6); }}
.pills span.smart {{ color: #7bd5e4; border-color: #2f5d65; }}
.phone {{ position: absolute; right: 70px; top: 46px; width: 280px; transform: rotate(4deg);
  border-radius: 30px; overflow: hidden; border: 2px solid #333;
  box-shadow: 0 30px 80px rgba(0,0,0,.75), 0 0 0 8px #161616; }}
.phone img {{ display: block; width: 100%; }}
</style></head><body>
<div class="glow g1"></div><div class="glow g2"></div>
<div class="left">
  <div class="brand"><img src="{LOGO.as_uri()}"><h1>LatentJam</h1></div>
  <div class="tag">A music player that hears<br>what your library sounds like</div>
  <div class="pills"><span class="smart">SMART queue</span><span>On-device</span><span>No account</span><span>No network</span></div>
</div>
<div class="phone"><img src="{player}"></div>
</body></html>"""


def icon_html() -> str:
    # Store clients draw the icon as it is, without a launcher mask, so the corners stay transparent.
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
* {{ margin: 0; }} html, body {{ background: transparent; }} img {{ display: block; width: 512px; height: 512px; }}
</style></head><body><img src="{LOGO.as_uri()}"></body></html>"""


def render(page: str, out: pathlib.Path, width: int, height: int) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        src = pathlib.Path(tmp) / "page.html"
        src.write_text(page, encoding="utf-8")
        subprocess.run(
            [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
             "--force-device-scale-factor=1", "--allow-file-access-from-files",
             "--default-background-color=00000000",
             f"--window-size={width},{height}", "--virtual-time-budget=3000",
             f"--screenshot={out}", src.as_uri()],
            check=True, capture_output=True,
        )


def main() -> None:
    shots = OUT / "phoneScreenshots"
    shots.mkdir(parents=True, exist_ok=True)
    render(icon_html(), OUT / "icon.png", 512, 512)
    render(feature_html(), OUT / "featureGraphic.png", 1024, 500)
    for name, raw, headline, subline in SHOTS:
        render(shot_html(RAW / raw, headline, subline), shots / f"{name}.png", 1080, 2340)
    print("rendered", 2 + len(SHOTS), "images into", OUT)


if __name__ == "__main__":
    main()
