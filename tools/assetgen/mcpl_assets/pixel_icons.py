"""The 증강 card icons drawn by PixelLab (tools/assetgen/pixellab_icons.py; raw 32 x 32 outputs committed in
tools/assetgen/augment_icons/<augment id>.png), cleaned up the same way for every icon so they read as one set:

- hard alpha (no half-transparent fringe left by the background removal) and no stray single pixels;
- one uniform 1 px dark outline round the silhouette wherever the edge pixel is not dark already;
- cropped to the art, shown SCALE times bigger (nearest neighbour, so every art pixel stays a crisp SCALE x SCALE
  square) and centred on a SIZE x SIZE canvas: 2 canvas pixels per card texel, as tall on the card as before. Art
  reaching outside the medallion's dark well (radius RADIUS round the centre) is scaled down at its own resolution.

`augment_icons.py` (the painted icons) stays as the fallback for an augment without a PixelLab icon.
"""

import json
from pathlib import Path

from PIL import Image

DIR = Path(__file__).resolve().parent.parent / "augment_icons"
SOURCE = 32          # raw PixelLab icon size
SCALE = 2            # canvas pixels per art pixel
SIZE = 72            # canvas (font glyph PNG, shown 36 font units tall)
RADIUS = 39          # the medallion's dark well (19 card texels), in canvas pixels round the centre
OUTLINE = (18, 14, 26, 255)
SPECK = 2            # opaque islands smaller than this many art pixels are dropped


def available():
    """Augment ids with a PixelLab icon."""
    return {p.stem for p in DIR.glob("*.png")}


def prompts():
    return json.loads((DIR / "prompts.json").read_text(encoding="utf-8"))


def _components(im):
    w, h = im.size
    px = im.load()
    seen, out = set(), []
    for y in range(h):
        for x in range(w):
            if px[x, y][3] and (x, y) not in seen:
                stack, comp = [(x, y)], []
                seen.add((x, y))
                while stack:
                    cx, cy = stack.pop()
                    comp.append((cx, cy))
                    for nx in (cx - 1, cx, cx + 1):
                        for ny in (cy - 1, cy, cy + 1):
                            if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen and px[nx, ny][3]:
                                seen.add((nx, ny))
                                stack.append((nx, ny))
                out.append(comp)
    return out


def clean(raw):
    """Hard alpha and no specks."""
    im = raw.convert("RGBA")
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255) if a >= 128 else (0, 0, 0, 0)
    for comp in _components(im):
        if len(comp) < SPECK:
            for x, y in comp:
                px[x, y] = (0, 0, 0, 0)
    return im


def _dark(p):
    return p[3] and 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] < 60


def outline(im):
    """The art cropped with a 1 px margin and a 1 px dark ring outside the silhouette where the silhouette's own edge
    is not dark (4-neighbourhood)."""
    l, t, r, b = im.getbbox()
    src = Image.new("RGBA", (r - l + 2, b - t + 2), (0, 0, 0, 0))
    src.alpha_composite(im.crop((l, t, r, b)), (1, 1))
    out = src.copy()
    sp, dp = src.load(), out.load()
    w, h = src.size
    for y in range(h):
        for x in range(w):
            if sp[x, y][3]:
                continue
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < w and 0 <= ny < h and sp[nx, ny][3] and not _dark(sp[nx, ny]):
                    dp[x, y] = OUTLINE
                    break
    return out.crop(out.getbbox())


def fit(art):
    """Shows the art SCALE times bigger, centred on the canvas; scaled down at its own resolution only when it would
    leave the medallion's well."""
    w, h = art.size
    px = art.load()
    far = max(((x + 0.5 - w / 2) ** 2 + (y + 0.5 - h / 2) ** 2) ** 0.5
              for y in range(h) for x in range(w) if px[x, y][3]) * SCALE
    k = min(1.0, RADIUS / far, SIZE / (max(w, h) * SCALE))
    if k < 1.0:
        art = art.resize((max(1, round(w * k)), max(1, round(h * k))), Image.NEAREST)
    big = art.resize((art.width * SCALE, art.height * SCALE), Image.NEAREST)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.alpha_composite(big, ((SIZE - big.width) // 2, (SIZE - big.height) // 2))
    return canvas


def icon(augment_id):
    return fit(outline(clean(Image.open(DIR / f"{augment_id}.png"))))
