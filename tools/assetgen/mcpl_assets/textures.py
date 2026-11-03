"""
Procedural 64x64 textures built only from palette ramps, with fixed seeds (always identical output).

The tower models are drawn at display scale 4 (16 model units = 4 blocks), so a 64px texture over 16 units keeps the
vanilla density of 16 texels per block.

Shading rules shared by every generator (STSV ART_STYLE):
  - light from the top-left: top/left edges +1 step, bottom/right edges -1 step
  - jitter: at most one ramp step, on a minority of pixels
  - surfaces tile seamlessly (the model's auto UV wraps every 16 units)

Animated textures are frame strips (frames stacked vertically, height = n * 64); FRAME_TIME gives their frame time
in ticks. Tintable textures are drawn in near-white greys ("tint" ramp): the minecraft:dye tint multiplies them with
the team colour, dark pixels stay dark.
"""

import math
import random

from PIL import Image

from .palette import ramp

S = 64
NOISE = 0.5


def _new(fill=None, frames=1):
    return Image.new("RGBA", (S, S * frames), fill or (0, 0, 0, 0))


def _j(rnd, i, chance=0.2):
    # the random draw always happens so seeds stay stable when NOISE changes
    roll, step = rnd.random(), rnd.choice((-1, 1))
    return i + step if roll < chance * NOISE else i


def _field(seed, cells=4):
    """Tileable smooth noise 0..1."""
    rnd = random.Random(seed)
    grid = [[rnd.random() for _ in range(cells)] for _ in range(cells)]

    def at(x, y):
        gx, gy = x / S * cells, y / S * cells
        x0, y0 = int(gx) % cells, int(gy) % cells
        x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
        fx, fy = gx - int(gx), gy - int(gy)
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        top = grid[y0][x0] * (1 - fx) + grid[y0][x1] * fx
        bottom = grid[y1][x0] * (1 - fx) + grid[y1][x1] * fx
        return top * (1 - fy) + bottom * fy
    return at


# ------------------------------------------------------------------------------------------------
# Hull plating
# ------------------------------------------------------------------------------------------------

def _hull_base(seed, panel=32, ramp_name="gunmetal", base_steps=(1, 2, 2, 3)):
    """Armour plates `panel` px square (2 blocks), bevelled seams, rivets at the corners, a few service lines."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    shades = {}
    for y in range(S):
        for x in range(S):
            key = (x // panel, y // panel)
            base = shades.setdefault(key, rnd.choice(base_steps))
            bx, by = x % panel, y % panel
            if bx == panel - 1 or by == panel - 1:
                i = 0
            elif bx == 0 or by == 0:
                i = base + 1
            elif bx == panel - 2 or by == panel - 2:
                i = base - 1
            else:
                i = _j(rnd, base, 0.2)
            px[x, y] = ramp(ramp_name, i)
    # rivets
    for py in range(0, S, panel):
        for qx in range(0, S, panel):
            for (rx, ry) in ((3, 3), (panel - 5, 3), (3, panel - 5), (panel - 5, panel - 5)):
                px[qx + rx, py + ry] = ramp(ramp_name, 4)
                px[qx + rx + 1, py + ry + 1] = ramp(ramp_name, 0)
    # service seams: a horizontal sub-panel line in some plates
    for (qx, py) in ((0, 0), (32, 32)):
        y = py + 20
        for x in range(qx + 2, qx + panel - 2):
            px[x, y] = ramp(ramp_name, 0)
            px[x, y + 1] = ramp(ramp_name, 3)
    # a small vent grille on one plate
    for y in range(40, 52, 3):
        for x in range(8, 24):
            px[x, y] = ramp(ramp_name, 0)
            px[x, y + 1] = ramp(ramp_name, 1)
    return im


def _cracks(im, seed, count, length, ramp_name="gunmetal"):
    """Random-walk cracks in the darkest step with a lit lip on the upper-left side."""
    rnd = random.Random(seed)
    px = im.load()
    for _ in range(count):
        x, y = rnd.randrange(S), rnd.randrange(S)
        dx = rnd.choice((-1, 1))
        for _ in range(length):
            px[x % S, y % S] = ramp("gunmetal", 0)
            lip = ((x - 1) % S, (y - 1) % S)
            if px[lip][:3] != ramp("gunmetal", 0)[:3]:
                px[lip] = ramp(ramp_name, 4)
            y += 1
            if rnd.random() < 0.55:
                x += dx
            if rnd.random() < 0.15:
                dx = -dx


def _soot(im, seed, density):
    """Scorch patches: tileable low-frequency blobs, one or two steps darker (soot ramp)."""
    rnd = random.Random(seed)
    field = _field(seed + 1)
    px = im.load()
    for y in range(S):
        for x in range(S):
            v = field(x, y) + density - 0.5
            roll = rnd.random()
            if v > 0.55 and roll < 0.85:
                r, g, b, a = px[x, y]
                lum = (r + g + b) / 3
                px[x, y] = ramp("gunmetal", 1 if lum > 90 else 0)


def hull():
    return _hull_base(401)


def hull_cracked():
    im = _hull_base(401)
    _cracks(im, 402, 6, 18)
    _soot(im, 403, 0.12)
    return im


def hull_broken():
    im = _hull_base(401)
    _cracks(im, 404, 12, 24)
    _soot(im, 405, 0.32)
    # torn plate: a hole showing the dark frame inside
    px = im.load()
    for y in range(10, 26):
        for x in range(38, 38 + (y - 6) // 2):
            px[x, y] = ramp("gunmetal", 0) if (x + y) % 5 else ramp("steel", 1)
    return im


def hull_dark():
    """Plinth / foundation: diamond tread plate."""
    rnd = random.Random(406)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 1
            u, v = x % 8, y % 8
            if (u, v) in ((1, 2), (2, 1), (5, 6), (6, 5)):
                i = 3
            elif (u, v) in ((2, 3), (3, 2), (6, 7), (7, 6)):
                i = 0
            px[x, y] = ramp("gunmetal", _j(rnd, i, 0.15))
    for y in range(S):
        for x in range(S):
            if x % 32 == 31 or y % 32 == 31:
                px[x, y] = ramp("gunmetal", 0)
    return im


def hazard():
    """Diagonal warning stripes, 8 px wide, with worn spots."""
    rnd = random.Random(407)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            band = ((x + y) // 8) % 2
            if band == 0:
                i = 1 if (x + y) % 8 == 7 else 2
                if (x + y) % 8 == 0:
                    i = 2
                c = ramp("hazard", _j(rnd, i, 0.2))
            else:
                c = ramp("gunmetal", _j(rnd, 0, 0.2))
            if rnd.random() < 0.04:
                c = ramp("gunmetal", 2)
            px[x, y] = c
    for x in range(S):
        px[x, 0] = ramp("gunmetal", 3)
        px[x, S - 1] = ramp("gunmetal", 0)
    return im


def grate():
    """Cooling fins / vent grille: horizontal slats every 4 px."""
    im = _new()
    px = im.load()
    for y in range(S):
        r = y % 4
        for x in range(S):
            i = (3, 2, 1, 0)[r]
            if x % 32 in (0, 31):
                i = 1 if x % 32 == 31 else 3
            px[x, y] = ramp("steel", i)
    return im


def frame():
    """Exposed structure: girders with X bracing on a dark void (damaged parts)."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            u, v = x % 32, y % 32
            c = ramp("gunmetal", 0)
            if u < 3 or v < 3:
                c = ramp("steel", 2 if (u == 0 or v == 0) else 1)
            elif abs(u - v) <= 1 or abs(u + v - 32) <= 1:
                c = ramp("steel", 1 if abs(u - v) <= 1 else 0)
            px[x, y] = c
    return im


def debris():
    """Torn scrap plates and cables (ruin piles)."""
    rnd = random.Random(408)
    im = _new()
    px = im.load()
    seeds = [(rnd.randrange(S), rnd.randrange(S), rnd.choice((0, 1, 2, 3))) for _ in range(22)]
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
            if edge < 1.3:
                px[x, y] = ramp("gunmetal", 0)
            elif shade == 0:
                px[x, y] = ramp("hazard", 0) if (x + y) // 4 % 2 else ramp("gunmetal", 1)
            else:
                px[x, y] = ramp("gunmetal", _j(rnd, shade, 0.3))
    return im


def cable():
    """Bundled black cables: 4 px cords with a highlight, clamps every 32 px."""
    rnd = random.Random(409)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            u = x % 4
            i = (1, 2, 1, 0)[u]
            if y % 32 in (14, 15, 16, 17):
                i = (3, 4, 4, 2)[y % 32 - 14]
                px[x, y] = ramp("steel", i)
                continue
            px[x, y] = ramp("gunmetal", _j(rnd, min(3, i), 0.15))
    return im


def steel_light():
    """Pale brushed metal (satellite dishes, antenna masts)."""
    rnd = random.Random(410)
    im = _new()
    px = im.load()
    for y in range(S):
        row = rnd.choice((3, 3, 4, 4, 3))
        for x in range(S):
            i = row
            if y % 32 == 0:
                i = 5
            elif y % 32 == 31:
                i = 2
            px[x, y] = ramp("steel", _j(rnd, i, 0.15))
    return im


def glass_dark():
    """Dark smart glass with mullions every block and a diagonal sheen."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 1
            if ((x - y) % 32) in range(4, 9):
                i = 2
            if ((x - y) % 32) == 6:
                i = 3
            if x % 16 == 15 or y % 16 == 15:
                px[x, y] = ramp("gunmetal", 0)
                continue
            if x % 16 == 0 or y % 16 == 0:
                px[x, y] = ramp("gunmetal", 2)
                continue
            px[x, y] = ramp("glass_dark", i)
    return im


def amber():
    """Warning lamp lens (not tinted, glows)."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            u, v = x % 16, y % 16
            i = 2 if 3 <= u <= 12 and 3 <= v <= 12 else 1
            if u in (0, 15) or v in (0, 15):
                i = 0
            px[x, y] = ramp("amber", i)
    return im


# ------------------------------------------------------------------------------------------------
# Tintable, glowing parts (team colour)
# ------------------------------------------------------------------------------------------------

def neon():
    """Neon tube: bright core, slightly dimmer rim (every 4 px across so thin strips still show both)."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            u = x % 4
            px[x, y] = ramp("tint", 4 if u in (1, 2) else 3)
    return im


FLICKER = 8


def neon_flicker():
    """Damaged neon: segments drop out and come back (animated)."""
    rnd = random.Random(411)
    im = _new(frames=FLICKER)
    px = im.load()
    for f in range(FLICKER):
        dead = {s for s in range(8) if rnd.random() < (0.35 if f % 3 else 0.7)}
        for y in range(S):
            seg = y // 8
            for x in range(S):
                u = x % 4
                lit = 4 if u in (1, 2) else 3
                px[x, f * S + y] = ramp("tint", 1 if seg in dead else lit)
    return im


def rack():
    """Server rack front: 4 px rack units with drive bays and status LEDs (animated blink; tinted, glows)."""
    frames = 4
    rnd = random.Random(412)
    im = _new(frames=frames)
    px = im.load()
    leds = [(x, y, rnd.random()) for y in range(1, S, 4) for x in range(3, 13, 3)]
    for f in range(frames):
        for y in range(S):
            for x in range(S):
                u, v = x % 16, y % 4
                c = ramp("tint", 0)
                if u in (0, 15):
                    c = ramp("tint", 1)
                elif v == 3:
                    c = ramp("tint", 0)
                elif 14 <= u or u <= 1:
                    c = ramp("tint", 1)
                px[x, f * S + y] = c
        for (lx, ly, phase) in leds:
            for qx in range(0, S, 16):
                on = (phase * 4 + f + qx // 16) % 4 < (3 if phase > 0.5 else 2)
                px[qx + lx, f * S + ly] = ramp("tint", 4 if on else 1)
                px[qx + lx + 1, f * S + ly] = ramp("tint", 3 if on else 1)
            # drive bay slits
        for y in range(2, S, 8):
            for x in range(S):
                if 13 <= x % 16 <= 14:
                    px[x, f * S + y] = ramp("tint", 2)
    return im


DATA_FRAMES = 8


def data():
    """Data column: bit streams running down (animated, tinted, glows)."""
    rnd = random.Random(413)
    im = _new(frames=DATA_FRAMES)
    px = im.load()
    columns = [[rnd.random() < 0.45 for _ in range(S)] for _ in range(S // 2)]
    for f in range(DATA_FRAMES):
        for y in range(S):
            for x in range(S):
                col = columns[x // 2]
                bit = col[(y - f * 8) % S]
                if x % 2 == 1:
                    i = 0
                else:
                    i = 4 if bit and (y + x) % 7 else 3 if bit else 1
                px[x, f * S + y] = ramp("tint", i)
    return im


def circuit():
    """Processor die: bright traces and pads on a dark field (tinted, glows)."""
    rnd = random.Random(414)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("tint", 0)
    for _ in range(46):
        x, y = rnd.randrange(S), rnd.randrange(S)
        horizontal = rnd.random() < 0.5
        for _ in range(rnd.randrange(6, 24)):
            px[x % S, y % S] = ramp("tint", 3)
            if horizontal:
                x += 1
            else:
                y += 1
            if rnd.random() < 0.12:
                horizontal = not horizontal
        px[x % S, y % S] = ramp("tint", 4)
        px[(x + 1) % S, y % S] = ramp("tint", 4)
    for y in range(S):
        for x in range(S):
            if x % 32 in (0, 31) or y % 32 in (0, 31):
                px[x, y] = ramp("tint", 2)
    return im


def crystal():
    """Faceted data crystal: diagonal facet bands with a bright lit edge (glows, tinted)."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            band = ((x + y) // 12) % 4
            i = (2, 3, 4, 3)[band]
            if x % 32 < 2 or y % 32 < 2:
                i = 4
            elif x % 32 > 29 or y % 32 > 29:
                i = 1
            if (x - y) % 23 == 0:
                i = min(4, i + 1)
            px[x, y] = ramp("tint", i)
    return im


SCAN = 8


def energy():
    """Firewall barrier: translucent hex lattice with a scan band sweeping down (animated, tinted, glows)."""
    im = _new(frames=SCAN)
    px = im.load()
    lattice = _hex(S, 16)
    for f in range(SCAN):
        sweep = f * S // SCAN
        for y in range(S):
            d = (y - sweep) % S
            boost = 70 if d < 6 else 30 if d < 12 else 0
            for x in range(S):
                edge = lattice[y][x]
                line = y % 4 == 0
                if edge:
                    px[x, f * S + y] = ramp("tint", 4, min(255, 190 + boost))
                else:
                    px[x, f * S + y] = ramp("tint", 3 if line else 2, min(255, (90 if line else 70) + boost))
    return im


def energy_broken():
    """A failing barrier: the lattice tears open (alpha 0 holes) and stutters (animated)."""
    im = energy()
    px = im.load()
    rnd = random.Random(415)
    for f in range(SCAN):
        holes = [(rnd.randrange(S), rnd.randrange(S), rnd.randrange(6, 14)) for _ in range(5)]
        dim = f in (2, 5)
        for y in range(S):
            for x in range(S):
                r, g, b, a = px[x, f * S + y]
                for (hx, hy, hr) in holes:
                    dx = min(abs(x - hx), S - abs(x - hx))
                    dy = min(abs(y - hy), S - abs(y - hy))
                    if dx + dy * 0.6 < hr:
                        a = 0
                if dim and a:
                    a = a // 3
                px[x, f * S + y] = (r, g, b, a)
    return im


def holo():
    """Holographic sign: scrolling glyph rows (animated, translucent, tinted, glows)."""
    frames = 4
    rnd = random.Random(416)
    im = _new(frames=frames)
    px = im.load()
    glyphs = [[rnd.random() < 0.55 for _ in range(S // 4)] for _ in range(S // 8)]
    for f in range(frames):
        for y in range(S):
            for x in range(S):
                row = ((y // 8) + f) % (S // 8)
                gy = y % 8
                on = glyphs[row][x // 4] and 2 <= gy <= 5 and x % 4 != 3
                a = 220 if on else 60 if y % 2 == 0 else 35
                px[x, f * S + y] = ramp("tint", 4 if on else 3, a)
        for x in range(S):
            px[x, f * S] = ramp("tint", 4, 230)
            px[x, f * S + S - 1] = ramp("tint", 4, 230)
    return im


SPARK_FRAMES = 6


def sparks():
    """Electric sparks bursting from a torn panel (animated cutout; thin plates only)."""
    rnd = random.Random(417)
    im = _new(frames=SPARK_FRAMES)
    px = im.load()
    for f in range(SPARK_FRAMES):
        if f % 3 == 2:
            continue                       # a dark beat between bursts
        for _ in range(6):
            x, y = S // 2 + rnd.randrange(-6, 6), S // 2 + rnd.randrange(-6, 6)
            dx, dy = rnd.choice((-1, 1)), rnd.choice((-1, 0, 1))
            for n in range(rnd.randrange(10, 26)):
                if 0 <= x < S and 0 <= y < S:
                    px[x, f * S + y] = ramp("fire", 5 if n < 4 else 4 if n < 10 else 3)
                x += dx
                y += dy if rnd.random() < 0.6 else 1
                if rnd.random() < 0.3:
                    dx = rnd.choice((-1, 1))
    return im


def _hex(size, cell):
    """Boolean honeycomb edges (Voronoi of offset rows: centres every `cell` px, rows `cell` apart), tileable."""
    centres = [(i * cell + (cell / 2 if j % 2 else 0), j * cell)
               for j in range(-1, size // cell + 2) for i in range(-1, size // cell + 2)]
    out = [[False] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            d = sorted((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 for cx, cy in centres)
            out[y][x] = math.sqrt(d[1]) - math.sqrt(d[0]) < 1.4
    return out


def shield():
    """Translucent hex-grid energy dome (pale cyan, not tinted)."""
    im = _new()
    px = im.load()
    lattice = _hex(S, 16)
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("shield", 2, 170) if lattice[y][x] else ramp("shield", 1, 40)
    return im


def figure():
    """Preview only: the 1.8-block player silhouette."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("leather", 2)
    return im


SURFACES = {
    "hull": hull,
    "hull_cracked": hull_cracked,
    "hull_broken": hull_broken,
    "hull_dark": hull_dark,
    "hazard": hazard,
    "grate": grate,
    "frame": frame,
    "debris": debris,
    "cable": cable,
    "steel_light": steel_light,
    "glass_dark": glass_dark,
    "amber": amber,
    "neon": neon,
    "neon_flicker": neon_flicker,
    "rack": rack,
    "data": data,
    "circuit": circuit,
    "crystal": crystal,
    "energy": energy,
    "energy_broken": energy_broken,
    "holo": holo,
    "sparks": sparks,
    "shield": shield,
    "figure": figure,
}

# frame time (ticks) of the animated textures
FRAME_TIME = {"neon_flicker": 3, "rack": 6, "data": 2, "energy": 2, "energy_broken": 2, "holo": 5, "sparks": 2}

# textures whose translucency is the point (drawn on thin plates on purpose, not holes)
TRANSLUCENT = {"shield", "energy", "energy_broken", "holo"}

_cache = {}


def get(name):
    if name not in _cache:
        _cache[name] = SURFACES[name]()
    return _cache[name]


def first_frame(name):
    im = get(name)
    return im.crop((0, 0, im.width, im.width)) if im.height > im.width else im


def has_transparency(name):
    if name in TRANSLUCENT:
        return False
    return any(p[3] < 255 for p in get(name).getdata())
