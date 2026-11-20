"""
Weapon textures for the fourth batch of ability weapons (tools/assetgen/models/weapons_b4.py: myth, beasts, nature,
martial arts and sport). Same rules as weapon_textures.py: 32x32 = 2 texels per unit, palette ramps only (registered
below with palette.add_ramps), fixed seeds, light from the top-left, tileable. Importing this module registers the
ramps and the w_* textures; a name another module already registered is an error.

Plain materials: bronze, lapis, bear fur, fox fur, black feathers, octopus skin with suckers, ink, hydra scales,
tortoise shell plates, amethyst, sculk, tennis felt, racket strings, pink lacquer and the five 오방색 cloths.
Emissive (glow=True, only where the ability's element is light or magic): the sun (Ra), runes (Odin), ki, the magical
girl's heart, the gorgon's gaze, fox fire (여우구슬) and sculk's sonic glow.
"""

import random

from . import textures as T
from .palette import add_ramps, ramp
from .weapon_textures import PULSE, W, _brushed, _fill, _grain, _j, _new

add_ramps({
    # name: (hue, chroma, center lightness, lightness span, steps, accent)
    "bronze":     (70, 0.085, 0.60, 0.40, 5, False),    # warm cast bronze (labrys, titan bands)
    "lapis":      (266, 0.130, 0.43, 0.30, 4, False),   # Egyptian lapis blue (Ra's sceptre)
    "bearfur":    (52, 0.055, 0.38, 0.30, 5, False),    # dark brown bear fur
    "foxfur":     (78, 0.035, 0.87, 0.18, 4, False),    # white fox fur (gumiho tails)
    "crow":       (262, 0.025, 0.25, 0.20, 4, False),   # black-blue tengu / raven feathers
    "octo":       (338, 0.105, 0.52, 0.34, 5, False),   # octopus skin (purple-red)
    "ink":        (275, 0.030, 0.17, 0.12, 3, False),   # squid ink
    "hydra":      (168, 0.075, 0.40, 0.28, 5, False),   # dark teal hydra scales
    "shell":      (72, 0.085, 0.48, 0.32, 5, False),    # tortoise shell plates
    "amethyst":   (305, 0.130, 0.58, 0.42, 5, False),   # amethyst crystal (unlit)
    "sculk":      (218, 0.055, 0.24, 0.16, 4, False),   # deep-dark sculk
    "felt":       (112, 0.170, 0.86, 0.16, 4, False),   # optic yellow tennis felt
    "rose":       (352, 0.105, 0.76, 0.28, 5, False),   # pink lacquer (magical girl)
    "ochre":      (88, 0.140, 0.78, 0.22, 4, False),    # 오방색 yellow
    "obang_blue": (258, 0.130, 0.48, 0.24, 4, False),   # 오방색 blue
    "jade":       (155, 0.110, 0.55, 0.26, 4, False),   # 오방색 green, jade beads
    # emissive accents
    "sun":        ((40, 90), 0.170, 0.74, 0.42, 5, True),  # the sun disc (Ra)
    "rune":       (228, 0.120, 0.80, 0.30, 4, True),    # Odin's runes (pale blue seidr)
    "ki":         (238, 0.140, 0.78, 0.40, 5, True),    # ki energy (blue)
    "heart":      (355, 0.170, 0.68, 0.38, 4, True),    # the magical girl's heart gem
    "gaze":       (112, 0.160, 0.82, 0.30, 4, True),    # the gorgon's petrifying gaze
    "foxfire":    (215, 0.110, 0.84, 0.30, 4, True),    # 여우구슬 / fox fire (blue-white)
})


# ------------------------------------------------------------------------------------------------
# Plain materials
# ------------------------------------------------------------------------------------------------

def bronze():
    """Cast bronze: warm metal with soft hammer dents lit on their upper-left rim, a few dark pits (no streaks: it
    must not read as wood)."""
    rnd = random.Random(701)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("bronze", 3 if (x + y) % 16 in (3, 4) else _j(rnd, 2, 0.1))
    for _ in range(5):
        cx, cy = rnd.randrange(W), rnd.randrange(W)
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                if dx * dx + dy * dy <= 4:
                    px[(cx + dx) % W, (cy + dy) % W] = ramp("bronze", 1 if dx + dy > 0 else 3)
    for _ in range(6):
        px[rnd.randrange(W), rnd.randrange(W)] = ramp("bronze", 0)
    return im


def lapis():
    """Lapis lazuli: deep blue with gold flecks (pyrite)."""
    rnd = random.Random(702)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("gold", 2) if rnd.random() < 0.025 else ramp("lapis", _j(rnd, 1, 0.25)))
    return im


def _fur(name, seed, steps=(1, 2, 3, 3, 2, 2, 1, 0)):
    def make():
        rnd = random.Random(seed)
        phase = [rnd.randrange(8) for _ in range(W)]
        im = _new()
        _fill(im.load(), lambda x, y: ramp(name, steps[(y + phase[x] + x // 3) % 8]))
        return im
    return make



def crow():
    """Black feathers: barbs slanting up from a lighter quill line every 8 texels."""
    def at(x, y):
        c = x % 8
        if c == 0:
            return ramp("crow", 3)
        barb = (y + (c if c < 4 else 8 - c)) % 4
        return ramp("crow", (0, 1, 1, 2)[barb] if c in (2, 3, 4, 5, 6) else 1)

    im = _new()
    _fill(im.load(), at)
    return im


def octo():
    """Octopus skin: mottled purple-red with darker spots."""
    rnd = random.Random(704)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("octo", 1 if rnd.random() < 0.12 else _j(rnd, 2, 0.2)))
    return im


def sucker():
    """Sucker rows: pale rings with a dark centre every 4 texels."""
    def at(x, y):
        cx, cy = x % 4, y % 4
        if (cx, cy) in ((1, 1), (2, 1), (1, 2), (2, 2)):
            return ramp("octo", 1)
        return ramp("rose", 3 if cx + cy < 3 else 2)

    im = _new()
    _fill(im.load(), at)
    return im


def ink():
    """Squid ink: almost black with a wet sheen."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("ink", 2 if (x + y) % 11 in (0, 1) else (1 if (x * 7 + y * 3) % 13 == 0 else 0)))
    return im


def hydra():
    """Hydra scales: rounded rows, each lit on its upper rim."""
    def at(x, y):
        row = y // 4
        sx = (x + (2 if row % 2 else 0)) % 4
        sy = y % 4
        if sy == 0:
            return ramp("hydra", 3 if sx in (1, 2) else 2)
        if sy == 3:
            return ramp("hydra", 0)
        return ramp("hydra", 2 if sx == 0 else 1)

    im = _new()
    _fill(im.load(), at)
    return im


def shell():
    """Tortoise shell: plates outlined by dark seams, each with growth rings (light top-left)."""
    def at(x, y):
        px_, py = x % 16, y % 16
        if px_ in (0, 15) or py in (0, 15):
            return ramp("shell", 0)
        d = max(abs(px_ - 7.5), abs(py - 7.5))
        lit = 1 if px_ + py < 14 else 0
        return ramp("shell", (2 if int(d) % 3 else 1) + lit + (1 if d < 2 else 0))

    im = _new()
    _fill(im.load(), at)
    return im


def amethyst():
    """Amethyst: faceted violet with pale glints (unlit)."""
    def at(x, y):
        a, b = x % 8, y % 8
        if (a, b) in ((1, 1), (2, 1), (1, 2)):
            return ramp("amethyst", 4)
        return ramp("amethyst", 3 if a + b < 6 else (2 if a > b else 1))

    im = _new()
    _fill(im.load(), at)
    return im


def sculk():
    """Sculk: deep teal-black with darker specks."""
    rnd = random.Random(707)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("sculk", 0 if rnd.random() < 0.1 else _j(rnd, 2 if (x + y) % 9 else 3, 0.15)))
    return im


def felt():
    """Tennis felt: fuzzy optic yellow with a white seam every 16 texels."""
    rnd = random.Random(708)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("pearl", 2) if (x + y // 3) % 16 == 0 else ramp("felt", _j(rnd, 2, 0.35)))
    return im


def strings():
    """Racket strings: a pale lattice (thin plates only)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("ivory", 3 if x % 4 == 0 or y % 4 == 0 else 1))
    return im


def rose():
    """Pink lacquer: smooth with a soft lighter band."""
    rnd = random.Random(710)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("rose", 3 if (x + y) % 16 in (2, 3) else _j(rnd, 2, 0.05)))
    return im


def _cloth(name, seed, steps=(1, 2, 2, 3)):
    """A soft cloth ribbon in any ramp: vertical strands."""
    def make():
        rnd = random.Random(seed)
        cols = [rnd.choice(steps) for _ in range(W)]
        im = _new()
        _fill(im.load(), lambda x, y: ramp(name, _j(rnd, cols[x], 0.1)))
        return im
    return make


def jade():
    """Jade: soft green with paler clouds."""
    rnd = random.Random(712)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("jade", 3 if (x // 3 + y // 5) % 7 == 0 else _j(rnd, 2, 0.15)))
    return im


# ------------------------------------------------------------------------------------------------
# Emissive (glow=True)
# ------------------------------------------------------------------------------------------------

def _pulse(name, seed, top, rays=False):
    """A breathing glow over 8 frames with a few bright motes drifting up (magic, the sun, ki)."""
    def make():
        rnd = random.Random(seed)
        motes = [(rnd.randrange(W), rnd.randrange(W)) for _ in range(8)]
        im = _new(PULSE)
        px = im.load()
        for f in range(PULSE):
            lift = (0, 1, 1, 2, 2, 1, 1, 0)[f]
            for y in range(W):
                for x in range(W):
                    if rays:
                        cx, cy = abs(x % 16 - 7.5), abs(y % 16 - 7.5)
                        i = 2 if cx < 1.5 or cy < 1.5 or abs(cx - cy) < 1 else 1
                    else:
                        i = 1 + (1 if (x + y) % 10 == 0 else 0)
                    i += lift // 2 + (f in (3, 4))
                    for mx, my in motes:
                        if (x - mx) % W == 0 and (y - my + f * 4) % W == 0:
                            i = top
                    px[x, f * W + y] = ramp(name, min(top, i))
        return im
    return make


def rune():
    """Runes cut into the spearhead: pale blue strokes pulsing slowly (on a dark blue ground)."""
    im = _new(PULSE)
    px = im.load()
    glyph = {(1, 0), (1, 1), (1, 2), (1, 3), (1, 4), (2, 1), (3, 0), (5, 0), (5, 1), (5, 2), (5, 3), (5, 4), (6, 2),
             (7, 1), (7, 3)}
    for f in range(PULSE):
        lift = (0, 0, 1, 1, 2, 1, 1, 0)[f]
        for y in range(W):
            for x in range(W):
                gx, gy = x % 8, y % 6
                px[x, f * W + y] = ramp("rune", min(3, (1 if (gx, gy) in glyph else 0) + lift))
    return im


def sonic():
    """Sculk's sonic glow: bright rings travelling up over 8 frames (cyan on deep teal)."""
    im = _new(PULSE)
    px = im.load()
    for f in range(PULSE):
        for y in range(W):
            for x in range(W):
                band = (y + f * 4) % 8
                px[x, f * W + y] = ramp("soul", 4 if band == 0 else (3 if band in (1, 7) else 1))
    return im


SURFACES = {
    "w_bronze": bronze, "w_lapis": lapis, "w_bearfur": _fur("bearfur", 703), "w_foxfur": _fur("foxfur", 709,
                                                                                               (1, 2, 3, 3, 2, 2, 1, 1)),
    "w_crow": crow, "w_octo": octo, "w_sucker": sucker, "w_ink": ink, "w_hydra": hydra, "w_shell": shell,
    "w_amethyst": amethyst, "w_sculk": sculk, "w_felt": felt, "w_strings": strings, "w_rose": rose, "w_jade": jade,
    "w_wrap_rose": _cloth("rose", 711, (2, 3, 3, 4)), "w_cloth_ochre": _cloth("ochre", 713),
    "w_cloth_blue": _cloth("obang_blue", 714), "w_cloth_jade": _cloth("jade", 715), "w_cloth_white": _cloth("pearl", 716,
                                                                                                            (0, 1, 1, 2)),
    "w_wood_lapis": lambda: _grain("lapis", 717, (1, 2, 2, 3, 1)),
    # emissive (the sun, runes, ki, the heart, the gaze, fox fire, sculk's sonic glow)
    "w_sun": _pulse("sun", 721, 4, rays=True), "w_rune": rune, "w_ki": _pulse("ki", 722, 4),
    "w_heart": _pulse("heart", 723, 3), "w_gaze": _pulse("gaze", 724, 3), "w_foxfire": _pulse("foxfire", 725, 3),
    "w_sonic": sonic,
}
FRAME_TIME = {"w_sun": 3, "w_rune": 4, "w_ki": 2, "w_heart": 3, "w_gaze": 3, "w_foxfire": 3, "w_sonic": 2}

for _name in SURFACES:
    assert _name not in T.SURFACES, f"texture {_name} is defined twice"
T.SURFACES.update(SURFACES)
T.FRAME_TIME.update(FRAME_TIME)
