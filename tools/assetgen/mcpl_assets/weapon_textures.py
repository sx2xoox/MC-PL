"""
Ability weapon textures: 32x32, i.e. 2 texels per model unit (weapons are drawn at about display scale 1, so this is
twice the vanilla item density: enough for bevels, grain and grip ribs on 1-2 unit parts, still pixel art).

Same rules as the tower textures (textures.py): palette ramps only, fixed seeds, light from the top-left, tileable
(the model's auto UV wraps every 16 units = one texture). Every name starts with "w_" so it never clashes with a
tower texture. Importing this module registers the textures in textures.SURFACES / FRAME_TIME.

Materials are classic: forged iron, polished steel, wood, leather, brass, gold, black lacquer. Emissive textures
(glow=True boxes) are only for an ability whose element is light: fire / heat, lightning, a lamp, magic. A lit box
renders unshaded at full light. Animated ones are frame strips (frames stacked vertically, 32 px each).
"""

import random

from PIL import Image

from . import textures as T
from .palette import ramp

W = 32


def _new(frames=1, fill=None):
    return Image.new("RGBA", (W, W * frames), fill or (0, 0, 0, 0))


def _j(rnd, i, chance=0.15):
    roll, step = rnd.random(), rnd.choice((-1, 1))
    return i + step if roll < chance else i


def _fill(px, fn, frame=0):
    for y in range(W):
        for x in range(W):
            px[x, frame * W + y] = fn(x, y)


def _brushed(ramp_name, steps, seed):
    """Brushed metal: vertical streaks one ramp step apart."""
    rnd = random.Random(seed)
    cols = [rnd.choice(steps) for _ in range(W)]
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp(ramp_name, _j(rnd, cols[x], 0.08))
    return im


def _grain(ramp_name, seed, steps):
    """Wood: wavy vertical grain with a few dark pores."""
    rnd = random.Random(seed)
    phase = [rnd.random() * 6 for _ in range(W)]
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            g = (x + int(1.5 * ((y + phase[x // 4]) % 6 > 3))) % 5
            px[x, y] = ramp(ramp_name, steps[g] if rnd.random() > 0.05 else steps[0] - 1)
    return im


# ------------------------------------------------------------------------------------------------
# Materials
# ------------------------------------------------------------------------------------------------

def iron():
    """Forged iron (hammer and pick heads, bands): mid grey with hammer dents, lit on their upper-left rim."""
    rnd = random.Random(511)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("steel", _j(rnd, 2, 0.12))
    for _ in range(5):
        cx, cy = rnd.randrange(W), rnd.randrange(W)
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                if dx * dx + dy * dy <= 4:
                    px[(cx + dx) % W, (cy + dy) % W] = ramp("steel", 1 if dx + dy > 0 else 3)
    return im


def iron_dark():
    """Blackened iron / blued gun steel: the darkest metal (frames, cylinders, sockets)."""
    return _brushed("gunmetal", (0, 1, 1), 502)


def steel():
    """Polished blade steel: bright brushed streaks."""
    return _brushed("steel", (3, 4, 4, 3), 503)


def steel_edge():
    """Honed edge bevel: the brightest steel with a glint."""
    rnd = random.Random(504)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("steel", 5 if (x + y) % 9 in (0, 1) else _j(rnd, 4, 0.2)))
    return im


def grip():
    """Rubber grip: horizontal ribs every unit (2 px)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("rubber", (2, 0)[y % 2] if x % 16 else 1))
    return im


def lacquer():
    """Black lacquered wood (a police nightstick): near-black with a soft vertical sheen."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("gunmetal", 2 if x % 16 in (3, 4) else (1 if x % 16 in (2, 5, 6) else 0)))
    return im


def wrap():
    """Leather wrap: diagonal bands with a dark gap."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("leather", (0, 3, 2, 2, 1, 1)[(x + y) % 6]))
    return im


def leather():
    """Plain leather (straps, lanyards, sheaths)."""
    rnd = random.Random(512)
    im = _new()
    _fill(im.load(), lambda x, y: ramp("leather", _j(rnd, 2, 0.2)))
    return im


def wood():
    """Warm handle wood (oak / walnut)."""
    return _grain("oak", 505, (1, 2, 2, 3, 1))


def wood_dark():
    """Dark wood (long hafts that should recede)."""
    return _grain("wood_dark", 513, (1, 2, 2, 3, 1))


def brass():
    return _brushed("brass", (2, 3, 3, 2), 506)


def gold():
    """Polished gold (badges, inlays)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("gold", 3 if (x + y) % 8 in (0, 1) else (2 if (x // 2 + y // 2) % 3 else 1)))
    return im


def glass():
    """Lamp glass with a diagonal sheen (unlit)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("glass_dark", 3 if (x - y) % 16 in (3, 4) else 1))
    return im


# ------------------------------------------------------------------------------------------------
# Emissive (glow=True): only where the ability's element is light
# ------------------------------------------------------------------------------------------------

PULSE = 8


def heat():
    """Glowing forge metal: hot orange with yellow veins that crawl upwards (animated; fire, forge)."""
    rnd = random.Random(509)
    veins = [(rnd.randrange(W), rnd.randrange(W)) for _ in range(7)]
    im = _new(PULSE)
    px = im.load()
    for f in range(PULSE):
        lift = (0, 1, 1, 1, 0, 0, 1, 0)[f]
        for y in range(W):
            for x in range(W):
                i = 2 + lift - (1 if (x * 3 + y * 5) % 11 == 0 else 0)
                for vx, vy in veins:
                    if abs(((y + f * 4 - vy) % W) - abs(x - vx) * 2) <= 0 and abs(x - vx) < 4:
                        i = 5
                px[x, f * W + y] = ramp("fire", i)
    return im


def amber():
    """Warm lamp flame / lens glow (a miner's lamp, a lantern)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("amber", 2 if (x % 8 and y % 8) else 1))
    return im


ARC = 6


def arc():
    """Electric arcs jumping across (x) (animated cutout, thin plates only; lightning, tasers)."""
    rnd = random.Random(507)
    im = _new(ARC)
    px = im.load()
    for f in range(ARC):
        for _ in range(2):
            y = rnd.randrange(10, 22)
            for x in range(W):
                for dy in (0, 1):
                    px[x, f * W + (y + dy) % W] = ramp("cyan", 4 if dy == 0 else 3)
                y = max(4, min(27, y + rnd.choice((-2, -1, 0, 1, 2))))
    return im


def spark():
    """Steady electric glow (lightning abilities)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("cyan", 4 if x % 2 == 0 else 3))
    return im


def crystal():
    """Faceted magic crystal breathing over 8 frames (magic abilities)."""
    rnd = random.Random(508)
    base = [[(((x + y) // 6) + ((x - y) // 9)) % 3 for x in range(W)] for y in range(W)]
    sparkle = {(rnd.randrange(W), rnd.randrange(W)) for _ in range(10)}
    im = _new(PULSE)
    px = im.load()
    for f in range(PULSE):
        lift = (0, 1, 1, 2, 2, 1, 1, 0)[f]
        for y in range(W):
            for x in range(W):
                i = 4 if (x, y) in sparkle and f in (3, 4) else base[y][x] + lift
                px[x, f * W + y] = ramp("ore", min(4, i))
    return im


SURFACES = {
    "w_iron": iron, "w_iron_dark": iron_dark, "w_steel": steel, "w_steel_edge": steel_edge, "w_grip": grip,
    "w_lacquer": lacquer, "w_wrap": wrap, "w_leather": leather, "w_wood": wood, "w_wood_dark": wood_dark,
    "w_brass": brass, "w_gold": gold, "w_glass": glass,
    # emissive: only for an ability whose element is light (fire, lightning, a lamp, magic)
    "w_heat": heat, "w_amber": amber, "w_arc": arc, "w_spark": spark, "w_crystal": crystal,
}
FRAME_TIME = {"w_heat": 3, "w_arc": 1, "w_crystal": 3}

T.SURFACES.update(SURFACES)
T.FRAME_TIME.update(FRAME_TIME)
