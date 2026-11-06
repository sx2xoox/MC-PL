"""The 증강 card icons drawn by PixelLab (tools/assetgen/pixellab_icons.py; raw 64 x 64 outputs committed in
tools/assetgen/augment_icons/<augment id>.png), cleaned up the same way for every icon so they read as one set:

- hard alpha (no half-transparent fringe left by the background removal) and no stray specks of a few pixels;
- cropped to the art and centred on a SIZE x SIZE canvas; art reaching outside the medallion circle (radius RADIUS
  round the centre) or wider than MAX_SIDE is scaled down (nearest neighbour) to fit;
- one uniform 1 px dark outline round the silhouette wherever the edge pixel is not dark already.

`augment_icons.py` (the painted icons) stays as the fallback for an augment without a PixelLab icon.
"""

import json
from pathlib import Path

from PIL import Image

DIR = Path(__file__).resolve().parent.parent / "augment_icons"
SIZE = 72            # canvas (font glyph PNG, shown 36 font units tall), the size of the painted icons
RADIUS = 33.5        # the medallion's inner circle, in canvas pixels round the centre
MAX_SIDE = 66
OUTLINE = (18, 14, 26, 255)
SPECK = 4            # opaque islands smaller than this many pixels are dropped


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


def fit(im):
    """Crops to the art and centres it on the canvas, scaled down only when it leaves the circle (1 px kept for the
    outline)."""
    box = im.getbbox()
    art = im.crop(box)
    w, h = art.size
    px = art.load()
    far = max(((x + 0.5 - w / 2) ** 2 + (y + 0.5 - h / 2) ** 2) ** 0.5
              for y in range(h) for x in range(w) if px[x, y][3])
    k = min(1.0, (RADIUS - 1) / far, (MAX_SIDE - 2) / max(w, h))
    if k < 1.0:
        art = art.resize((max(1, round(w * k)), max(1, round(h * k))), Image.NEAREST)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.alpha_composite(art, ((SIZE - art.width) // 2, (SIZE - art.height) // 2))
    return canvas


def outline(im):
    """A 1 px dark ring outside the silhouette where the silhouette's own edge is not dark (4-neighbourhood)."""
    out = im.copy()
    src, dst = im.load(), out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < SIZE and 0 <= ny < SIZE and src[nx, ny][3] and not _dark(src[nx, ny]):
                    dst[x, y] = OUTLINE
                    break
    return out


def icon(augment_id):
    return outline(fit(clean(Image.open(DIR / f"{augment_id}.png"))))
