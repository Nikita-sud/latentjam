"""Google Play listing assets from what the repository already has.

Reads docs/media/*.png (phone screenshots from the fictional demo library) and the iOS
1024 px app icon; writes into the output directory:

  icon-512.png              512 x 512, opaque
  feature-graphic-1024x500.png  opaque, icon + wordmark + tagline
  screenshots/NN-name.png   1080 x 2160 — Play rejects anything taller than 2:1, so the
                            status bar and the gesture area are cropped, nothing else

Usage: python3 tools/play/listing_assets.py <output dir>
Needs Pillow. The wordmark uses a bold sans-serif from the system fonts; the file names it
tries are macOS ones, with Pillow's built-in font as the last resort.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
MEDIA = os.path.join(REPO, "docs", "media")
ICON = os.path.join(REPO, "iosApp", "iosApp", "Assets.xcassets", "AppIcon.appiconset", "AppIcon-1024.png")

# Order on the store page: what the app is for first, the player second. docs/media/map.png is
# a Russian-locale capture, so it stays out of the en-US set; a Russian listing can use it.
SCREENSHOTS = ["for-you", "player", "statistics", "pages"]
STATUS_BAR_PX = 100   # 1080 x 2340 emulator capture: clock and icons live in the top 100 px
GESTURE_BAR_PX = 80   # the navigation pill lives in the bottom 80 px
BACKGROUND = (12, 12, 12)
TAGLINE = "Offline music player that learns your library on-device"


def font(size, bold):
    candidates = [
        ("/System/Library/Fonts/HelveticaNeue.ttc", ["Bold" if bold else "Regular"]),
        ("/System/Library/Fonts/Helvetica.ttc", ["Bold" if bold else "Regular"]),
        ("/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold else
         "/System/Library/Fonts/Supplemental/Arial.ttf", [None]),
    ]
    for path, wanted in candidates:
        if not os.path.isfile(path):
            continue
        for index in range(0, 24):
            try:
                face = ImageFont.truetype(path, size, index=index)
            except (OSError, IOError):
                break
            family, style = face.getname()
            if wanted == [None] or style in wanted:
                return face
    return ImageFont.load_default(size)


def screenshots(out):
    os.makedirs(os.path.join(out, "screenshots"), exist_ok=True)
    for number, name in enumerate(SCREENSHOTS, start=1):
        source = os.path.join(MEDIA, f"{name}.png")
        image = Image.open(source).convert("RGB")
        width, height = image.size
        cropped = image.crop((0, STATUS_BAR_PX, width, height - GESTURE_BAR_PX))
        assert cropped.size[1] <= cropped.size[0] * 2, cropped.size
        cropped.save(os.path.join(out, "screenshots", f"{number:02d}-{name}.png"), optimize=True)


def icon(out):
    image = Image.open(ICON).convert("RGB").resize((512, 512), Image.LANCZOS)
    image.save(os.path.join(out, "icon-512.png"), optimize=True)


def rounded(image, radius):
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0) + image.size, radius=radius, fill=255)
    return image, mask


def feature_graphic(out):
    canvas = Image.new("RGB", (1024, 500), BACKGROUND)
    mark = Image.open(ICON).convert("RGB").resize((300, 300), Image.LANCZOS)
    mark, mask = rounded(mark, radius=64)
    canvas.paste(mark, (88, 100), mask)
    draw = ImageDraw.Draw(canvas)
    title = font(112, bold=True)
    body = font(30, bold=False)
    draw.text((444, 156), "LatentJam", font=title, fill=(245, 245, 245))
    # Two lines so the tagline never runs into the right edge at this size.
    words = TAGLINE.split(" ")
    first, second = " ".join(words[:4]), " ".join(words[4:])
    draw.text((448, 296), first, font=body, fill=(178, 178, 178))
    draw.text((448, 338), second, font=body, fill=(178, 178, 178))
    canvas.save(os.path.join(out, "feature-graphic-1024x500.png"), optimize=True)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    screenshots(out)
    icon(out)
    feature_graphic(out)
    for root, _, files in os.walk(out):
        for name in sorted(files):
            path = os.path.join(root, name)
            with Image.open(path) as image:
                print(f"{os.path.relpath(path, out)}\t{image.size[0]}x{image.size[1]}\t{image.mode}\t{os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    main()
