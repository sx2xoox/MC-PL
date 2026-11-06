"""
Weapon textures for the third batch of ability weapons (tools/assetgen/models/weapons_b3.py): the same rules as
weapon_textures.py (32x32, palette ramps only, fixed seeds, light from the top-left, tileable). Importing this module
registers the extra palette ramps (palette.add_ramps) and the w_* textures.

Emissive textures here: w_rift (violet magic: gravity, blink) only.
"""

import random

from . import textures as T
from .palette import add_ramps, ramp
from .weapon_textures import W, _brushed, _fill, _grain, _j, _new

add_ramps({
    # name: (hue, chroma, center lightness, lightness span, steps, accent)
    "paint_red":  (27, 0.120, 0.50, 0.30, 5, False),    # enamel red (a horseshoe magnet, a widow's hourglass)
    "olive":      (118, 0.045, 0.44, 0.26, 4, False),   # army olive drab (a stick grenade)
    "ivory":      (85, 0.020, 0.86, 0.18, 4, False),    # bone white (clock dial, feathers, silk, a bobber)
    "cork":       (68, 0.065, 0.64, 0.24, 4, False),    # cork grips, hourglass sand
    "ash_wood":   (78, 0.055, 0.74, 0.22, 5, False),    # pale ash (a baseball bat)
    "venom":      (138, 0.150, 0.66, 0.36, 4, True),    # poison green (liquid, not lit)
    "void":       (300, 0.050, 0.34, 0.20, 4, False),   # a dark violet orb (gravity)
    "violet":     (300, 0.150, 0.68, 0.42, 5, True),    # violet magic glow (gravity, blink)
})


def paint_red():
    """Enamelled red: smooth, a soft lighter band near the top-left of every tile."""
    rnd = random.Random(601)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("paint_red", 3 if (x + y) % 16 in (2, 3) else _j(rnd, 2, 0.06)))
    return im


def olive():
    """Olive-drab painted iron with a few chips."""
    rnd = random.Random(602)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("olive", 0 if rnd.random() < 0.02 else _j(rnd, 2, 0.1)))
    return im


def ivory():
    rnd = random.Random(603)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("ivory", _j(rnd, 2, 0.1)))
    return im


def silk():
    """Spider silk / fishing line wrapped round: pale diagonal strands."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("ivory", (3, 2, 2, 1)[(x - y) % 4]))
    return im


def tape():
    """White athletic tape wound in overlapping turns."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("ivory", (1, 3, 2, 2)[(y + x // 8) % 4]))
    return im


def cork():
    rnd = random.Random(604)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("cork", 0 if rnd.random() < 0.08 else _j(rnd, 2, 0.25)))
    return im


def ash_wood():
    """Pale ash (baseball bats): long straight grain."""
    return _grain("ash_wood", 605, (2, 3, 3, 2, 2))


def venom():
    """Poison: green with lighter bubbles (unlit)."""
    rnd = random.Random(606)
    bubbles = {(rnd.randrange(W), rnd.randrange(W)) for _ in range(12)}
    im = _new()
    _fill(im.load(), lambda x, y: ramp("venom", 3 if (x, y) in bubbles else (1 if (x + 2 * y) % 7 == 0 else 2)))
    return im


def chitin():
    """Spider chitin: glossy near-black with a violet sheen streak."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("void", 3 if x % 16 in (4, 5) else (2 if x % 16 in (3, 6) else 1)))
    return im


def hair():
    """Bristly spider leg hair: dark with pale tips."""
    rnd = random.Random(607)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("void", 3 if rnd.random() < 0.12 else 0))
    return im


def void():
    """A dark orb (gravity well): deep violet, the lit rim towards the top-left."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("void", 1 if (x // 4 + y // 4) % 5 == 0 else 0))
    return im


RIFT = 8


def rift():
    """Violet magic, pulsing over 8 frames with drifting motes (gravity ring, blink gem)."""
    rnd = random.Random(608)
    motes = [(rnd.randrange(W), rnd.randrange(W)) for _ in range(9)]
    im = _new(RIFT)
    px = im.load()
    for f in range(RIFT):
        lift = (0, 1, 1, 2, 2, 1, 1, 0)[f]
        for y in range(W):
            for x in range(W):
                i = 1 + lift + (1 if (x + y) % 12 == 0 else 0)
                for mx, my in motes:
                    if (x - mx) % W == 0 and (y - my - f * 4) % W == 0:
                        i = 4
                px[x, f * W + y] = ramp("violet", min(4, i))
    return im


def scales():
    """Snake scales: green diamonds with dark seams."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("venom", 0 if (x + y) % 4 == 0 or (x - y) % 4 == 0 else (2 if y % 4 < 2 else 1)))
    return im


def code():
    """Terminal green glyphs on black (printed, not lit)."""
    rnd = random.Random(610)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("venom", 2) if x % 2 == 0 and rnd.random() < 0.35 else ramp("rubber", 0))
    return im


def brushed_dark():
    """Dark machined steel (mech plates): blued with rolled streaks."""
    return _brushed("gunmetal", (1, 2, 2, 1), 609)


SURFACES = {
    "w_paint_red": paint_red, "w_olive": olive, "w_ivory": ivory, "w_silk": silk, "w_tape": tape, "w_cork": cork,
    "w_ash": ash_wood, "w_venom": venom, "w_chitin": chitin, "w_hair": hair, "w_void": void,
    "w_plate": brushed_dark, "w_snake": scales, "w_code": code,
    # emissive (magic)
    "w_rift": rift,
}
FRAME_TIME = {"w_rift": 3}

for _name in SURFACES:
    assert _name not in T.SURFACES, f"texture {_name} is defined twice"
T.SURFACES.update(SURFACES)
T.FRAME_TIME.update(FRAME_TIME)
