"""
More ability weapon materials (models/weapons_b2.py: the element, spirit and support abilities). Same rules as
weapon_textures.py: 32x32 = 2 texels per unit, palette ramps only (registered below into palette.MATERIALS with the
shared formula), fixed seeds, light from the top-left, tileable. Importing this module registers the ramps and the
w_* textures; a name another module already registered is an error, never a silent override.

Plain materials: sea-worn bronze, water, basalt, white enamel, shadow cloth, ruby, bone, horn, dragon scale, fur,
feathers, pale birch, leaves, hemp rope, coloured grip wraps. Emissive (glow=True, only where the ability's element is
light): holy light, soul fire, ghost flame, dragon breath, a glowing potion.
"""

import random

from . import palette as P
from . import textures as T
from .weapon_textures import PULSE, W, _brushed, _fill, _grain, _j, _new

# name: (hue, chroma, center lightness, lightness span, steps, accent)  (see palette.py)
RAMPS = {
    "verdigris":  (178, 0.050, 0.56, 0.34, 5, False),   # sea-worn bronze patina
    "sea":        (238, 0.105, 0.56, 0.40, 5, False),   # water (unlit)
    "basalt":     (30, 0.012, 0.30, 0.16, 4, False),    # volcanic rock, obsidian
    "pearl":      (90, 0.012, 0.88, 0.14, 3, False),    # white enamel, ivory
    "shade":      (295, 0.040, 0.31, 0.18, 4, False),   # shadow cloth (dark violet)
    "blood":      (22, 0.145, 0.42, 0.34, 5, False),    # ruby, blood, crimson leather
    "bone":       (80, 0.030, 0.76, 0.30, 5, False),    # bone, skulls, claws
    "horn":       (45, 0.030, 0.34, 0.26, 4, False),    # dark ridged horn
    "scale":      (300, 0.055, 0.38, 0.26, 5, False),   # dragon scales (dark purple)
    "fur":        (62, 0.022, 0.50, 0.36, 5, False),    # grey-brown wolf fur
    "birch":      (80, 0.032, 0.76, 0.24, 4, False),    # pale wood, spruce soundboards
    "leaf":       (132, 0.085, 0.55, 0.30, 4, False),   # living leaves
    # emissive accents
    "radiant":    (95, 0.120, 0.87, 0.20, 4, True),     # holy light
    "soul":       (192, 0.125, 0.72, 0.44, 5, True),    # soul fire
    "ghost":      (145, 0.150, 0.78, 0.34, 4, True),    # ghost flame (necromancy)
    "breath":     (305, 0.150, 0.66, 0.40, 4, True),    # dragon breath
    "potion":     (340, 0.160, 0.64, 0.40, 4, True),    # a glowing potion
}

for _name, _spec in RAMPS.items():
    assert _name not in P.MATERIALS, f"palette ramp '{_name}' is defined twice"
    P.MATERIALS[_name] = _spec
    P.PALETTE[_name] = P.build_ramp(*_spec)

ramp = P.ramp


# ------------------------------------------------------------------------------------------------
# Plain materials
# ------------------------------------------------------------------------------------------------

def _wrap(name, steps=(0, 3, 2, 2, 1, 1)):
    """A cloth / leather grip wrap in any ramp: diagonal bands with a dark gap."""
    def make():
        im = _new()
        _fill(im.load(), lambda x, y: ramp(name, steps[(x + y) % len(steps)]))
        return im
    return make


def verdigris():
    """Sea-worn bronze: brushed brass with green patina blotches in the hollows."""
    im = _brushed("brass", (1, 2, 2, 1), 601)
    px = im.load()
    rnd = random.Random(602)
    for _ in range(9):
        cx, cy, r = rnd.randrange(W), rnd.randrange(W), rnd.choice((2, 3))
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if dx * dx + dy * dy <= r * r and rnd.random() < 0.85:
                    px[(cx + dx) % W, (cy + dy) % W] = ramp("verdigris", 2 if dx + dy < 0 else 1)
    return im


WATER = 8


def water():
    """Moving water: blue with pale ripple crests sliding down (animated, unlit)."""
    im = _new(WATER)
    px = im.load()
    for f in range(WATER):
        for y in range(W):
            for x in range(W):
                wave = (y + f * 4 + (3 if (x // 4) % 2 else 0)) % 16
                i = 4 if wave == 0 else (3 if wave in (1, 15) else (2 if wave < 8 else 1))
                px[x, f * W + y] = ramp("sea", i)
    return im


def basalt():
    """Volcanic rock: dark facets with lighter upper-left rims and black pits."""
    rnd = random.Random(611)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("basalt", _j(rnd, 1, 0.2))
    for _ in range(10):
        cx, cy, r = rnd.randrange(W), rnd.randrange(W), rnd.choice((2, 3))
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if abs(dx) + abs(dy) <= r:
                    px[(cx + dx) % W, (cy + dy) % W] = ramp("basalt", 2 if dx + dy < 0 else (3 if dx + dy < -2 else 1))
        px[cx, cy] = ramp("basalt", 0)
    return im


def pearl():
    """White enamel / ivory with a soft diagonal sheen."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("pearl", 2 if (x + y) % 12 in (0, 1) else (1 if (x // 3 + y // 5) % 4 else 0)))
    return im


def shade():
    """Shadow cloth: a dark violet weave."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("shade", (1, 2, 1, 0)[(x + 2 * (y % 2)) % 4]))
    return im


def ruby():
    """Faceted ruby: triangular facets, a bright glint top-left (unlit gem)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("blood", 4 if (x % 8, y % 8) in ((1, 1), (2, 1), (1, 2)) else
                                       (3 if (x % 8) + (y % 8) < 6 else (2 if (x % 8) > (y % 8) else 1))))
    return im


def blood():
    """Dark red blood channel with brighter wet streaks."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("blood", 2 if x % 5 == 1 else 1))
    return im


def bone():
    """Bone: off-white with pores and a few hairline cracks."""
    rnd = random.Random(621)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("bone", _j(rnd, 3, 0.18))
    for _ in range(14):
        px[rnd.randrange(W), rnd.randrange(W)] = ramp("bone", 1)
    for _ in range(3):
        x, y = rnd.randrange(W), rnd.randrange(W)
        for _ in range(6):
            px[x % W, y % W] = ramp("bone", 2)
            x, y = x + rnd.choice((-1, 0, 1)), y + 1
    return im


def horn():
    """Ridged horn: dark bands across, lighter towards the top-left."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("horn", (3, 2, 2, 1, 1, 0)[(y + (x // 8)) % 6]))
    return im


def scales():
    """Overlapping dragon scales: rounded rows, each lit on its upper rim."""
    im = _new()

    def at(x, y):
        row = y // 4
        sx = (x + (2 if row % 2 else 0)) % 4
        sy = y % 4
        if sy == 0:
            return ramp("scale", 3 if sx in (1, 2) else 2)
        if sy == 3:
            return ramp("scale", 0)
        return ramp("scale", 2 if sx == 0 else 1)

    _fill(im.load(), at)
    return im


def fur():
    """Shaggy fur: long wavy strands with dark roots."""
    rnd = random.Random(631)
    phase = [rnd.randrange(8) for _ in range(W)]
    im = _new()
    _fill(im.load(), lambda x, y: ramp("fur", (1, 2, 3, 3, 2, 2, 1, 0)[(y + phase[x] + x // 3) % 8]))
    return im


def feather():
    """Phoenix feathers: barbs slanting up from a bright quill line every 8 texels (red into orange)."""
    def at(x, y):
        c = x % 8
        if c == 0:
            return ramp("gold", 2)
        barb = (y + (c if c < 4 else 8 - c)) % 4
        return ramp("fire", (0, 1, 1, 2)[barb] if c in (2, 3, 4, 5, 6) else 0)

    im = _new()
    _fill(im.load(), at)
    return im


def birch():
    """Pale wood (birch / spruce soundboards)."""
    return _grain("birch", 641, (1, 2, 2, 3, 1))


def leaf():
    """Leaves: a bright midrib with darker veins."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("leaf", 3 if x % 8 == 3 else (1 if (x + y) % 4 == 0 else 2)))
    return im


def glass_clear():
    """Clear flask glass: pale grey-blue with a bright diagonal sheen (unlit)."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("steel", 5 if (x - y) % 16 in (3, 4) else 4))
    return im


def rope():
    """Twisted hemp rope across the grip."""
    im = _new()
    _fill(im.load(), lambda x, y: ramp("oak", (4, 3, 3, 2)[(x + 2 * y) % 4] if y % 4 else 1))
    return im


# ------------------------------------------------------------------------------------------------
# Emissive (glow=True)
# ------------------------------------------------------------------------------------------------

def radiant():
    """Holy light: a warm white core breathing over 8 frames, rays crossing at the centre."""
    im = _new(PULSE)
    px = im.load()
    for f in range(PULSE):
        lift = (0, 0, 1, 1, 1, 1, 0, 0)[f]
        for y in range(W):
            for x in range(W):
                cx, cy = abs(x % 16 - 7.5), abs(y % 16 - 7.5)
                i = 3 if cx < 1.5 or cy < 1.5 else (2 if cx + cy < 9 else 1)
                px[x, f * W + y] = ramp("radiant", min(3, i + lift - (1 if f in (0, 7) and i == 1 else 0)))
    return im


def _flame(name, seed, steps=(1, 2, 3, 4)):
    """Licking flames crawling upwards (animated): tongues of the brighter steps over a darker body."""
    def make():
        rnd = random.Random(seed)
        tongues = [(rnd.randrange(W), rnd.randrange(W), rnd.choice((3, 4, 5))) for _ in range(8)]
        im = _new(PULSE)
        px = im.load()
        for f in range(PULSE):
            for y in range(W):
                for x in range(W):
                    i = steps[0] + ((x + y * 3) % 7 == 0)
                    for tx, ty, h in tongues:
                        dy = (ty - y - f * 4) % W
                        if dy < h * 2 and abs(x - tx) <= (h * 2 - dy) // 3:
                            i = max(i, steps[min(len(steps) - 1, 1 + (abs(x - tx) == 0) + (dy < h))])
                    px[x, f * W + y] = ramp(name, i)
        return im
    return make


def potion():
    """A glowing potion: bubbles rising through magenta liquid."""
    rnd = random.Random(651)
    bubbles = [(rnd.randrange(W), rnd.randrange(W)) for _ in range(9)]
    im = _new(PULSE)
    px = im.load()
    for f in range(PULSE):
        for y in range(W):
            for x in range(W):
                i = 1 if (x + y) % 9 else 2
                for bx, by in bubbles:
                    if x == bx and (by - f * 4) % W == y:
                        i = 3
                    elif abs(x - bx) + abs(((by - f * 4) % W) - y) == 1:
                        i = max(i, 2)
                px[x, f * W + y] = ramp("potion", i)
    return im


def breath():
    """Dragon breath glow: a purple pulse (eyes, the breath in the jaws)."""
    im = _new(PULSE)
    px = im.load()
    for f in range(PULSE):
        lift = (0, 1, 1, 2, 2, 1, 1, 0)[f]
        for y in range(W):
            for x in range(W):
                px[x, f * W + y] = ramp("breath", min(3, (1 if (x + y) % 3 else 0) + lift))
    return im


SURFACES = {
    "w_verdigris": verdigris, "w_water": water, "w_basalt": basalt, "w_pearl": pearl, "w_shade": shade,
    "w_ruby": ruby, "w_blood": blood, "w_bone": bone, "w_horn": horn, "w_scales": scales, "w_fur": fur,
    "w_feather": feather, "w_birch": birch, "w_leaf": leaf, "w_rope": rope, "w_glass_clear": glass_clear,
    "w_wrap_pale": _wrap("pearl", (0, 2, 1, 1, 2, 2)), "w_wrap_shade": _wrap("shade"),
    "w_wrap_blood": _wrap("blood", (0, 2, 1, 1, 1, 0)), "w_wrap_sea": _wrap("sea"),
    # emissive: only for an ability whose element is light (fire, lightning, a lamp, magic)
    "w_radiant": radiant, "w_soulfire": _flame("soul", 661), "w_ghost": _flame("ghost", 662, (0, 1, 2, 3)),
    "w_breath": breath, "w_potion": potion,
}
FRAME_TIME = {"w_water": 2, "w_radiant": 3, "w_soulfire": 2, "w_ghost": 2, "w_breath": 3, "w_potion": 3}

for _name in SURFACES:
    assert _name not in T.SURFACES, f"texture '{_name}' is defined twice"
T.SURFACES.update(SURFACES)
T.FRAME_TIME.update(FRAME_TIME)
