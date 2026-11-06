"""
Weapon textures for batch 1 of the ability weapons (models/weapons_b1.py): cloth, silk wraps, antler, ice,
stone, moss, rope, paper, enamel, flame. Same rules as weapon_textures.py (32x32 = 2 texels per unit, palette ramps
only, fixed seeds, light from the top-left, tileable); importing this module registers the ramps and the textures.
"""

import random

from . import palette as P
from . import textures as T
from .palette import ramp
from .weapon_textures import W, _brushed, _fill, _j, _new

# name: (hue, chroma, center lightness, lightness span, steps, accent)  -- see palette.MATERIALS
RAMPS = {
    "crimson":  (25, 0.150, 0.46, 0.34, 5, False),   # red cloth, tassels, glove leather
    "navy":     (262, 0.070, 0.34, 0.24, 4, False),  # silk sword wrap
    "antler":   (78, 0.035, 0.80, 0.30, 5, False),   # antler, bone, tape
    "ice":      (222, 0.070, 0.82, 0.36, 5, True),   # clear ice crystal
    "moss":     (135, 0.090, 0.52, 0.26, 4, False),  # moss on stone
    "paper":    (88, 0.030, 0.90, 0.16, 4, False),   # fan paper, string
    "royal":    (262, 0.130, 0.46, 0.30, 5, True),   # blue enamel (a guardian's shield)
    "teal":     (200, 0.080, 0.62, 0.30, 4, True),   # wind motif ink
    "earth":    (75, 0.018, 0.52, 0.34, 5, False),   # grey field stone
}
P.add_ramps(RAMPS)


def cloth_red():
    """Crimson cloth / horsehair: soft vertical strands."""
    rnd = random.Random(601)
    cols = [rnd.choice((1, 2, 2, 3)) for _ in range(W)]
    im = _new()
    _fill(im.load(), lambda x, y: ramp("crimson", _j(rnd, cols[x], 0.1)))
    return im


def glove():
    """Red glove leather: smooth, a soft diagonal sheen and a dark stitch line every 16 px."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("crimson", 0 if (x % 16 == 15 and y % 2 == 0) else
                                       (3 if (x + y) % 16 in (4, 5) else 2)))
    return im


def silk():
    """Sword wrap (tsuka-ito): crossing navy silk bands with pale diamonds of the ray skin showing through."""
    def px(x, y):
        a, b = (x + y) % 8, (x - y) % 8
        if a in (0, 1) and b in (0, 1):
            return ramp("antler", 3)
        if a in (0,) or b in (0,):
            return ramp("navy", 0)
        return ramp("navy", 2 if (x + y) % 8 < 4 else 1)
    im = _new()
    _fill(im.load(), px)
    return im


def wrap_blue():
    """Navy leather wrap: diagonal bands with a dark gap (a frost blade's grip)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("navy", (0, 3, 2, 2, 1, 1)[(x + y) % 6]))
    return im


def antler():
    """Antler / bone: off-white with darker ridges."""
    rnd = random.Random(603)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("antler", 1 if (x * 3 + (y // 3)) % 13 == 0 else _j(rnd, 3, 0.18)))
    return im


def tape():
    """White hand tape: overlapping diagonal layers."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("antler", (4, 3, 3, 3, 2)[(y * 2 - x) % 5]))
    return im


def ice():
    """Ice crystal: pale facets with white cracks and a bright upper-left glint."""
    rnd = random.Random(604)
    base = [[(((x + 2 * y) // 7) + ((2 * x - y) // 11)) % 3 + 1 for x in range(W)] for y in range(W)]
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            i = base[y][x]
            if (x - y) % 16 == 0:
                i = 4
            px[x, y] = ramp("ice", _j(rnd, i, 0.06))
    return im


def frost():
    """Frosted metal: silver with white rime specks."""
    rnd = random.Random(605)
    im = _brushed("steel", (3, 4, 4), 606)
    px = im.load()
    for _ in range(60):
        px[rnd.randrange(W), rnd.randrange(W)] = ramp("ice", 4)
    return im


def stone():
    """Rough field stone: irregular flat facets (tileable cells), each one shade, with a lit rim and dark seams."""
    rnd = random.Random(607)
    seeds = [(rnd.randrange(W), rnd.randrange(W), rnd.choice((2, 2, 3))) for _ in range(6)]

    def near(x, y):
        best = []
        for sx, sy, shade in seeds:
            dx, dy = min(abs(x - sx), W - abs(x - sx)), min(abs(y - sy), W - abs(y - sy))
            best.append((dx * dx + dy * dy, shade))
        best.sort()
        return best

    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            b = near(x, y)
            gap = b[1][0] - b[0][0]
            i = b[0][1]
            if gap < 3:
                i = 0
            elif gap < 9:
                i = min(4, i + 1)
            px[x, y] = ramp("earth", _j(rnd, i, 0.06))
    return im


def moss():
    rnd = random.Random(608)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("moss", _j(rnd, 2 if (x + y * 3) % 5 else 1, 0.3)))
    return im


def hemp():
    """Twisted hemp rope / lashing: diagonal strands."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("oak", (4, 3, 3, 2)[(x + y) % 4] if (x + y) % 4 else 1))
    return im


def paper():
    """Fan paper: warm white with faint fibres."""
    rnd = random.Random(609)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("paper", _j(rnd, 2, 0.1)))
    return im


def paper_wind():
    """Fan paper painted with a teal wind swirl band."""
    rnd = random.Random(610)
    im = _new()

    def px(x, y):
        wave = (x // 2 + (3 if (y // 4) % 2 else 0)) % 8
        if 10 <= y <= 13 or (y in (9, 14) and wave < 4):
            return ramp("teal", 1 if y in (9, 14) else (2 if wave < 5 else 3))
        return ramp("paper", _j(rnd, 2, 0.1))
    _fill(im.load(), px)
    return im


def enamel():
    """Blue enamel shield field with a soft top-left sheen."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("royal", 3 if (x + y) % 24 in (2, 3) else (2 if (x // 4 + y // 4) % 5 else 1)))
    return im


def granite():
    """Darker dressed stone (rune bands)."""
    rnd = random.Random(612)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("earth", _j(rnd, 1, 0.3)))
    return im


FLAME = 8


def flame():
    """Living flame (emissive, fire only): yellow core licking up through orange, 8 frames."""
    rnd = random.Random(613)
    tongues = [(rnd.randrange(W), rnd.randrange(3, 7)) for _ in range(6)]
    im = _new(FLAME)
    px = im.load()
    for f in range(FLAME):
        for y in range(W):
            for x in range(W):
                i = 2
                for tx, wdt in tongues:
                    d = abs(((x - tx + W // 2) % W) - W // 2)
                    if (y + f * 4) % W < 20 and d < wdt * (1 - ((y + f * 4) % W) / 20):
                        i = 4
                if (x * 5 + y * 3 + f) % 17 == 0:
                    i = 5
                px[x, f * W + y] = ramp("fire", i)
    return im


SURFACES = {
    "w_cloth_red": cloth_red, "w_glove": glove, "w_tsuka": silk, "w_wrap_blue": wrap_blue, "w_antler": antler,
    "w_handwrap": tape, "w_ice": ice, "w_frost": frost, "w_stone": stone, "w_moss": moss, "w_hemp": hemp,
    "w_paper": paper, "w_paper_wind": paper_wind, "w_enamel": enamel, "w_granite": granite,
    # emissive (fire only)
    "w_flame": flame,
}
FRAME_TIME = {"w_flame": 2}

for _name in SURFACES:
    assert _name not in T.SURFACES, f"texture '{_name}' is defined twice"
T.SURFACES.update(SURFACES)
T.FRAME_TIME.update(FRAME_TIME)
