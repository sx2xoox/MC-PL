"""
Procedural 32x32 textures built only from palette ramps, with fixed seeds (always identical output).

The tower models are drawn at display scale 2 (16 model units = 2 blocks), so a 32px texture over 16 units keeps the
vanilla density of 16 texels per block.

Shading rules shared by every generator (STSV ART_STYLE):
  - light from the top-left: top/left edges +1 step, bottom/right edges -1 step
  - jitter: at most one ramp step, on a minority of pixels
  - surfaces tile seamlessly (the model's auto UV wraps every 16 units)
"""

import math
import random

from PIL import Image

from .palette import ramp, size

S = 32
NOISE = 0.5


def _new(fill=None):
    return Image.new("RGBA", (S, S), fill or (0, 0, 0, 0))


def _j(rnd, i, chance=0.2):
    # the random draw always happens so seeds stay stable when NOISE changes
    roll, step = rnd.random(), rnd.choice((-1, 1))
    return i + step if roll < chance * NOISE else i


# ------------------------------------------------------------------------------------------------
# Stone
# ------------------------------------------------------------------------------------------------

def _ashlar_base(seed, ramp_name="castle", joint="castle_dark", course=8, block=16):
    """Coursed ashlar: blocks `block` x `course` px, every other course offset by half a block."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    shades = {}
    for y in range(S):
        row = y // course
        off = (block // 2) * (row % 2)
        for x in range(S):
            ry, bx = y % course, (x + off) % block
            if ry == course - 1 or bx == block - 1:
                px[x, y] = ramp(joint, 1 if ry == course - 1 else 2)
                continue
            key = (row, ((x + off) // block) % (S // block))
            base = shades.setdefault(key, rnd.choice((1, 2, 2, 3)))
            i = base + (1 if ry == 0 or bx == 0 else -1 if ry == course - 2 or bx == block - 2 else 0)
            px[x, y] = ramp(ramp_name, _j(rnd, i, 0.25))
    return im


def _cracks(im, seed, count, length, ramp_name="castle_dark", step=0):
    """Random-walk hairline cracks in the darkest step, with a lighter lip on the lit (upper-left) side."""
    rnd = random.Random(seed)
    px = im.load()
    for _ in range(count):
        x, y = rnd.randrange(S), rnd.randrange(S)
        dx = rnd.choice((-1, 1))
        for _ in range(length):
            px[x % S, y % S] = ramp(ramp_name, step)
            lip = ((x - 1) % S, (y - 1) % S)
            if px[lip][:3] != ramp(ramp_name, step)[:3]:
                px[lip] = ramp("castle", 4)
            y += 1
            if rnd.random() < 0.55:
                x += dx
            if rnd.random() < 0.15:
                dx = -dx


def _soot(im, seed, density):
    """Scorch patches: tileable low-frequency blobs, heavier towards the top, one or two steps darker (soot ramp)."""
    rnd = random.Random(seed)
    cells = 4
    grid = [[rnd.random() for _ in range(cells)] for _ in range(cells)]

    def field(x, y):
        gx, gy = x / S * cells, y / S * cells
        x0, y0 = int(gx) % cells, int(gy) % cells
        x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
        fx, fy = gx - int(gx), gy - int(gy)
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        top = grid[y0][x0] * (1 - fx) + grid[y0][x1] * fx
        bottom = grid[y1][x0] * (1 - fx) + grid[y1][x1] * fx
        return top * (1 - fy) + bottom * fy

    px = im.load()
    for y in range(S):
        for x in range(S):
            v = field(x, y) + density - 0.5 + 0.12 * (1 - y / S)
            roll = rnd.random()
            if v > 0.55 and roll < 0.85:
                r, g, b, a = px[x, y]
                lum = (r + g + b) / 3
                px[x, y] = ramp("soot", 3 if lum > 150 else 2 if lum > 110 else 1 if lum > 80 else 0)


def ashlar():
    return _ashlar_base(301)


def ashlar_cracked():
    im = _ashlar_base(301)
    _cracks(im, 302, 4, 12)
    return im


def ashlar_broken():
    im = _ashlar_base(301)
    _cracks(im, 303, 9, 16)
    _soot(im, 304, 0.3)
    return im


def ashlar_dark():
    """Plinth / foundation stone: bigger, darker blocks."""
    return _ashlar_base(305, ramp_name="castle_dark", joint="soot", course=16, block=16)


def cap_stone():
    """Smooth dressed stone for ledges, merlon caps and stairs (bevelled every 16px = 8 model units)."""
    rnd = random.Random(306)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            bx, by = x % 16, y % 16
            i = 3
            if bx == 0 or by == 0:
                i = 4
            elif bx == 15 or by == 15:
                i = 1
            else:
                i = _j(rnd, 3, 0.18)
            px[x, y] = ramp("castle", i)
    return im


def rubble():
    """Irregular broken stones with moss in the gaps (ruins)."""
    rnd = random.Random(307)
    im = _new()
    px = im.load()
    seeds = [(rnd.randrange(S), rnd.randrange(S), rnd.choice((1, 2, 3))) for _ in range(14)]
    for y in range(S):
        for x in range(S):
            best, second, shade = 1e9, 1e9, 2
            for sx, sy, sh in seeds:
                for ox in (-S, 0, S):
                    for oy in (-S, 0, S):
                        d = (x - sx - ox) ** 2 + (y - sy - oy) ** 2
                        if d < best:
                            best, second, shade = d, best, sh
                        elif d < second:
                            second = d
            edge = math.sqrt(second) - math.sqrt(best)
            if edge < 1.2:
                px[x, y] = ramp("moss", 0) if rnd.random() < 0.35 else ramp("castle_dark", 0)
            else:
                lit = 1 if (x + y) % 9 == 0 else 0
                px[x, y] = ramp("castle", _j(rnd, shade + lit, 0.3))
    return im


# ------------------------------------------------------------------------------------------------
# Wood, metal, roof
# ------------------------------------------------------------------------------------------------

def wood_beam():
    """Plain grain (no plank seams): safe on thin beams and doors."""
    rnd = random.Random(310)
    im = _new()
    px = im.load()
    cols = [rnd.choice((1, 1, 2, 2, 3)) for _ in range(S)]
    for y in range(S):
        for x in range(S):
            i = cols[x]
            if (x + y * 3) % 23 == 0:
                i -= 1
            px[x, y] = ramp("wood_dark", _j(rnd, i, 0.2))
    return im


def iron():
    """Dark riveted iron bands."""
    rnd = random.Random(311)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 1 if (x + y) % 9 else 2
            if y % 16 == 0:
                i = 3
            elif y % 16 == 15:
                i = 0
            px[x, y] = ramp("steel", _j(rnd, i, 0.15))
    for (x, y) in ((3, 4), (19, 4), (11, 12), (27, 12), (3, 20), (19, 20), (11, 28), (27, 28)):
        px[x, y] = ramp("steel", 5)
        px[x + 1, y + 1] = ramp("steel", 0)
    return im


def gate():
    """A dark gateway with a portcullis grid in front (used on a thin plate inside the arch)."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            depth = 0
            px[x, y] = ramp("soot", depth)
            if x % 8 in (3, 4) or y % 8 in (3, 4):
                px[x, y] = ramp("steel", 2 if (x % 8 == 3 or y % 8 == 3) else 1)
            if x % 8 in (3, 4) and y % 8 in (3, 4):
                px[x, y] = ramp("steel", 3)
    return im


def slate_roof():
    """Overlapping slate shingles in rows of 8px, darker towards the lower lip of each row."""
    rnd = random.Random(312)
    im = _new()
    px = im.load()
    for y in range(S):
        row = y // 8
        off = 4 * (row % 2)
        for x in range(S):
            ry, bx = y % 8, (x + off) % 8
            i = 2 if ry < 5 else 1
            if ry == 7:
                i = 0
            if bx == 0:
                i -= 1
            if ry == 0:
                i += 1
            px[x, y] = ramp("slate", _j(rnd, i, 0.2))
    return im


def gold_trim():
    rnd = random.Random(313)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if y % 16 == 0:
                i = 3
            elif y % 16 == 15:
                i = 0
            elif (x + y) % 11 == 0:
                i = 3
            px[x, y] = ramp("gold", _j(rnd, i, 0.1))
    return im


# ------------------------------------------------------------------------------------------------
# Tintable (team colour) parts: near-white greys, the dye tint multiplies them
# ------------------------------------------------------------------------------------------------

def banner():
    """Hanging cloth with vertical folds and a diamond crest."""
    rnd = random.Random(320)
    im = _new()
    px = im.load()
    folds = [3, 3, 4, 4, 3, 2, 2, 3, 3, 4, 4, 4, 3, 2, 2, 3] * 2
    for y in range(S):
        for x in range(S):
            i = folds[x] - (1 if y > 27 else 0)
            px[x, y] = ramp("tint", _j(rnd, i, 0.1))
    # crest: a diamond in the middle, outlined dark
    cx, cy = 15.5, 13
    for y in range(S):
        for x in range(S):
            d = abs(x - cx) + abs(y - cy)
            if d < 6:
                px[x, y] = ramp("tint", 4)
            elif d < 7.2:
                px[x, y] = ramp("tint", 1)
    return im


def crystal():
    """Faceted energy crystal: diagonal facet bands with a bright lit edge (glows, tinted)."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            band = ((x + y) // 6) % 4
            i = (2, 3, 4, 3)[band]
            if x < 2 or y < 2:
                i = 4
            elif x > S - 3 or y > S - 3:
                i = 1
            if (x - y) % 13 == 0:
                i = min(4, i + 1)
            px[x, y] = ramp("tint", i)
    return im


def rune():
    """Glowing rune band (tinted): bright glyph strokes on a dimmer field."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("tint", 1)
    glyphs = ["0110", "1001", "1111", "1001", "0110"]
    for gx in range(0, S, 8):
        for gy in range(0, S, 16):
            for r, line in enumerate(glyphs):
                for c, ch in enumerate(line):
                    if (ch == "1") != ((gx // 8) % 2 == 1 and r == 2):
                        px[gx + 2 + c, gy + 5 + r] = ramp("tint", 4)
    return im


def shield():
    """Translucent hex-grid bubble."""
    im = _new()
    px = im.load()
    h = 8
    w = h * math.sqrt(3) / 2 * 2
    for y in range(S):
        for x in range(S):
            # distance to a hex lattice edge (two offset row sets)
            best = 1e9
            for row in range(-1, 6):
                for col in range(-1, 6):
                    cx = col * 8 + (4 if row % 2 else 0)
                    cy = row * 6.0
                    dx, dy = abs(x + 0.5 - cx), abs(y + 0.5 - cy)
                    d = max(dx * 0.866 + dy * 0.5, dy)
                    best = min(best, 4.4 - d) if d < 4.4 else best
            edge = best < 0.9
            px[x, y] = ramp("shield", 2, 150) if edge else ramp("shield", 1, 46)
    _ = w
    return im


SURFACES = {
    "ashlar": ashlar,
    "ashlar_cracked": ashlar_cracked,
    "ashlar_broken": ashlar_broken,
    "ashlar_dark": ashlar_dark,
    "cap_stone": cap_stone,
    "rubble": rubble,
    "wood_beam": wood_beam,
    "iron": iron,
    "gate": gate,
    "slate_roof": slate_roof,
    "gold_trim": gold_trim,
    "banner": banner,
    "crystal": crystal,
    "rune": rune,
    "shield": shield,
}

# textures whose translucency is the point (drawn on boxes on purpose, not holes)
TRANSLUCENT = {"shield"}

_cache = {}


def get(name):
    if name not in _cache:
        _cache[name] = SURFACES[name]()
    return _cache[name]


def has_transparency(name):
    if name in TRANSLUCENT:
        return False
    return any(p[3] < 255 for p in get(name).getdata())
