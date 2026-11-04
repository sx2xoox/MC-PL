"""
공성전 v2 toy-kingdom textures (docs/siege-v2.md §7): 32x32, pastel plastic, felt and painted wood.

The toy models are drawn at very different display scales (creatures ~1, the fountain 2, towers 4), so these textures
are deliberately calm: a flat base colour with a soft one-step dither, and a pattern only where the material has one
(bricks, roof shingles, candy stripes, stitches). The shading comes from the faces (Minecraft's per-face light), which
is exactly how real moulded toys read. Same rules as the other assetgen textures: palette ramps only (the pastel ramps
are registered below with the shared OKLCH formula), fixed seeds, tileable (auto UV wraps every 16 units).

Every name starts with "t_". Tintable textures ("t_*_tint") are drawn in near-white greys: the item's dyed colour
(team colour) multiplies them. Importing this module registers the textures in textures.SURFACES.

Soap bubble (t_bubble): every pixel has alpha BUBBLE_ALPHA, the marker the pack's item shader looks for to draw the
iridescent film (assets/minecraft/shaders/core/item.fsh); without the shaders it still reads as a pale rainbow film.
"""

import math
import random

from PIL import Image

from . import textures as T
from .palette import add_ramps, ramp

W = 32

# the item shader's marker: texture alpha exactly 77/255 = soap film (keep in sync with item.fsh BUBBLE_ALPHA)
BUBBLE_ALPHA = 77

# pastel ramps: (hue, chroma, centre lightness, span, steps, accent)
add_ramps({
    "t_cream":    (88, 0.030, 0.90, 0.16, 4, False),   # castle walls
    "t_lilac":    (300, 0.045, 0.76, 0.22, 4, False),  # plinths, stone trim
    "t_pink":     (2, 0.075, 0.82, 0.20, 4, False),    # music box lacquer, candy
    "t_rose":     (10, 0.120, 0.70, 0.22, 4, True),    # blush, bows, candy stripes
    "t_mint":     (165, 0.070, 0.84, 0.20, 4, False),  # dragon, jelly, fountain
    "t_sky":      (235, 0.065, 0.82, 0.20, 4, False),  # windows (light), syrup
    "t_butter":   (95, 0.100, 0.90, 0.14, 4, False),   # yellow plastic
    "t_peach":    (55, 0.050, 0.87, 0.12, 4, False),   # painted faces
    "t_cocoa":    (60, 0.070, 0.66, 0.22, 4, False),   # teddy plush
    "t_caramel":  (70, 0.090, 0.74, 0.20, 4, False),   # teddy muzzle, paws, wood toys
    "t_snow":     (260, 0.012, 0.95, 0.10, 4, False),  # rabbit fur, white paint
    "t_ink":      (285, 0.030, 0.30, 0.10, 3, False),  # button eyes, boots
    "t_navy":     (265, 0.070, 0.42, 0.18, 4, False),  # soldier trousers, dark glass
    "t_lime":     (130, 0.090, 0.80, 0.20, 4, False),  # archer, leaves
    "t_grape":    (305, 0.090, 0.66, 0.22, 4, False),  # rabbit waistcoat, dragon belly spots
    "t_cherry":   (24, 0.150, 0.60, 0.22, 4, False),   # toy red: candy swirls, train, car, boat hull
    "t_cobalt":   (258, 0.110, 0.58, 0.20, 4, False),  # toy blue: the train's cab, the top's band
    "t_orange":   (58, 0.140, 0.76, 0.18, 4, False),   # the duck's beak and feet
    "t_sand":     (75, 0.055, 0.84, 0.18, 4, False),   # sandbox sand: castles, buckets
    "t_cheese":   (82, 0.130, 0.86, 0.18, 4, False),   # the clockwork mice's cheese
    "t_lemon":    (100, 0.165, 0.86, 0.16, 4, False),  # bright toy yellow: the spade, the bucket's handle
})

# the marble jar's glass: every pixel this alpha (not the soap film's marker)
GLASS_ALPHA = 96


def _new(fill=None):
    return Image.new("RGBA", (W, W), fill or (0, 0, 0, 0))


def _plastic(name, base=2, seed=1, chance=0.06):
    """Moulded plastic / paint: one colour, a sparse one-step dither (no grid lines at any display scale)."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            roll, step = rnd.random(), rnd.choice((-1, 1))
            px[x, y] = ramp(name, base + step if roll < chance else base)
    return im


def _felt(name, base=2, seed=3):
    """Plush / felt: a soft, slightly denser fuzz than plastic."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            r = rnd.random()
            px[x, y] = ramp(name, base - 1 if r < 0.05 else base + 1 if r < 0.09 else base)
    return im


def plastic(name, base=2, seed=1):
    return lambda: _plastic(name, base, seed)


def felt(name, base=2, seed=3):
    return lambda: _felt(name, base, seed)


def brick():
    """Cream toy bricks (8 x 4 px, half-offset rows) with a slightly darker, wide mortar: reads as chunky blocks."""
    rnd = random.Random(21)
    im = _new()
    px = im.load()
    shades = {}
    for y in range(W):
        row = y // 4
        for x in range(W):
            bx = (x + (4 if row % 2 else 0)) % W
            key = (row, bx // 8)
            base = shades.setdefault(key, rnd.choice((2, 2, 2, 3)))
            if y % 4 == 3 or bx % 8 == 7:
                px[x, y] = ramp("t_cream", 1)
            elif y % 4 == 0:
                px[x, y] = ramp("t_cream", 3)
            else:
                px[x, y] = ramp("t_cream", base)
    return im


def brick_cracked():
    """The bricks with a few missing chips and a zig-zag crack."""
    im = brick()
    px = im.load()
    rnd = random.Random(23)
    for _ in range(2):
        x, y = rnd.randrange(W), rnd.randrange(W)
        for _ in range(14):
            px[x % W, y % W] = ramp("t_lilac", 0)
            y += 1
            x += rnd.choice((-1, 0, 1))
    for (cx, cy) in ((5, 9), (22, 24)):
        for dy in range(3):
            for dx in range(5):
                px[(cx + dx) % W, (cy + dy) % W] = ramp("t_lilac", 1 if dy else 0)
    return im


def stone():
    """Lilac plinth stone: big soft slabs (16 px) with a light upper-left bevel."""
    rnd = random.Random(31)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            bx, by = x % 16, y % 16
            if bx == 15 or by == 15:
                i = 0
            elif bx == 0 or by == 0:
                i = 3
            else:
                i = 2 if rnd.random() > 0.05 else 1
            px[x, y] = ramp("t_lilac", i)
    return im


def roof_tint():
    """Round shingles (team colour by tint): scalloped rows, the lower lip a step darker."""
    im = _new()
    px = im.load()
    for y in range(W):
        row = y // 8
        for x in range(W):
            lx = (x + (4 if row % 2 else 0)) % 8 - 3.5
            ly = y % 8
            edge = ly >= 6 - (lx * lx) / 6.0
            i = 1 if edge else (4 if ly == 0 else 3)
            px[x, y] = ramp("tint", i)
    return im


def cloth_tint():
    """Painted team colour (flags, jackets, ribbons): near-white with a soft fold every 8 px."""
    rnd = random.Random(41)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            i = 3 if (x % 8) < 6 else 2
            px[x, y] = ramp("tint", i if rnd.random() > 0.04 else i - 1)
    return im


def paint_tint():
    """Flat team-coloured plastic."""
    return _plastic("tint", 4, 43, 0.03)


def gold():
    """Painted gold: warm yellow with a diagonal sheen band."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            d = (x + y) % 16
            px[x, y] = ramp("gold", 3 if d in (2, 3) else 1 if d in (10, 11, 12) else 2)
    return im


def wood():
    """Painted toy wood (caramel): gentle vertical grain."""
    rnd = random.Random(51)
    cols = [rnd.choice((1, 2, 2, 2)) for _ in range(W)]
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("t_caramel", cols[x] if rnd.random() > 0.03 else cols[x] + 1)
    return im


def window():
    """Night-blue glass with a white glint in the upper left of every 16 px pane."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            bx, by = x % 16, y % 16
            glint = (bx, by) in ((3, 3), (4, 3), (3, 4), (5, 3), (3, 5), (6, 6))
            px[x, y] = ramp("t_snow", 3) if glint else ramp("t_navy", 1 if by > 8 else 2)
    return im


def candy():
    """Candy-cane stripes: rose and white diagonals 4 px wide."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("t_rose", 2) if ((x + y) // 4) % 2 else ramp("t_snow", 2)
    return im


def candy_red():
    """Map-prop candy stripes: cherry red and white diagonals 8 px wide (bolder than t_candy for big props)."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("t_cherry", 2) if ((x + y) // 8) % 2 else ramp("t_snow", 3)
    return im


def bandage():
    """A sticking plaster (cute damage): beige with a row of air holes."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            hole = x % 4 == 1 and y % 4 == 1
            px[x, y] = ramp("t_peach", 1 if hole else 2)
    return im


def stitched(name, base=2):
    """Plush with a running stitch every 8 px (teddy seams)."""
    def make():
        im = _felt(name, base, 61)
        px = im.load()
        for y in range(W):
            for x in range(W):
                if y % 16 == 15 and x % 4 < 2:
                    px[x, y] = ramp(name, base - 2)
        return im
    return make


def jelly():
    """Gummy jelly: soft strawberry pink, lighter at the top, one white glint stripe (opaque, glossy)."""
    rnd = random.Random(71)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            i = 3 if y < 10 else 2
            if rnd.random() < 0.03:
                i -= 1
            px[x, y] = ramp("t_snow", 3) if (x + y) % 32 in (6, 7) and y < 12 else ramp("t_pink", i)
    return im


def syrup():
    """Candy fountain syrup: pale sky to pink swirls (opaque; drawn glowing slightly by the model)."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            v = math.sin((x + 2 * math.sin(y / 5.0)) / 3.0) + math.sin(y / 4.0)
            px[x, y] = ramp("t_sky", 3) if v > 1.0 else ramp("t_pink", 3) if v < -1.0 else ramp("t_sky", 2)
    return im


def jelly_lime():
    """Lime gummy jelly (the jungle's jelly puddings): like t_jelly in lime green."""
    rnd = random.Random(72)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            i = 3 if y < 12 else 2
            if rnd.random() < 0.03:
                i -= 1
            glint = (x + y) % 32 in (6, 7) and y < 14 or (x + y) % 32 == 22 and 18 < y < 26
            px[x, y] = ramp("t_snow", 3) if glint else ramp("t_lime", i)
    return im


def sand():
    """Sandbox sand: pale tan with a scatter of darker and lighter grains."""
    rnd = random.Random(73)
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            r = rnd.random()
            px[x, y] = ramp("t_sand", 1 if r < 0.12 else 3 if r < 0.18 else 2)
    return im


def glass():
    """Clear toy glass (the marble jar): a pale sky film at GLASS_ALPHA, a white glint stripe down every 16 px."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            if x % 16 in (3, 4):
                px[x, y] = ramp("t_snow", 3, 200)
            else:
                px[x, y] = ramp("t_sky", 3, GLASS_ALPHA)
    return im


def bubble():
    """Soap film: pale rainbow bands (a thin-film look baked in for clients without the shaders), every pixel at the
    shader's marker alpha."""
    im = _new()
    px = im.load()
    hues = ("t_pink", "t_butter", "t_mint", "t_sky", "t_lilac")
    for y in range(W):
        for x in range(W):
            band = (y + int(3 * math.sin(x * math.pi / 16))) // 5
            r, g, b, _ = ramp(hues[band % len(hues)], 3)
            px[x, y] = (r, g, b, BUBBLE_ALPHA)
    return im


def metal():
    """Toy tin (keys, wheels' hubs, cannon barrel): cool grey with a sheen line."""
    im = _new()
    px = im.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = ramp("steel", 5 if y % 16 == 2 else 4 if y % 16 in (1, 3) else 3)
    return im


def iron():
    """The cannon barrel: dark navy-black enamel."""
    return _plastic("t_navy", 0, 81, 0.05)


SURFACES = {
    "t_brick": brick,
    "t_brick_cracked": brick_cracked,
    "t_stone": stone,
    "t_roof_tint": roof_tint,
    "t_cloth_tint": cloth_tint,
    "t_paint_tint": paint_tint,
    "t_gold": gold,
    "t_wood": wood,
    "t_window": window,
    "t_candy": candy,
    "t_bandage": bandage,
    "t_jelly": jelly,
    "t_syrup": syrup,
    "t_bubble": bubble,
    "t_jelly_lime": jelly_lime,
    "t_sand": sand,
    "t_glass": glass,
    "t_cheese": plastic("t_cheese", 2, 122),
    "t_cheese_hole": plastic("t_cheese", 0, 123),
    "t_lemon": plastic("t_lemon", 2, 124),
    "t_metal": metal,
    "t_iron": iron,
    "t_pink": plastic("t_pink", 2, 101),
    "t_rose": plastic("t_rose", 2, 102),
    "t_mint": plastic("t_mint", 2, 103),
    "t_mint_dark": plastic("t_mint", 1, 113),
    "t_sky": plastic("t_sky", 2, 104),
    "t_butter": plastic("t_butter", 2, 105),
    "t_peach": plastic("t_peach", 2, 106),
    "t_snow": plastic("t_snow", 2, 107),
    "t_ink": plastic("t_ink", 1, 108),
    "t_navy": plastic("t_navy", 2, 109),
    "t_lime": plastic("t_lime", 2, 110),
    "t_grape": plastic("t_grape", 2, 111),
    "t_cherry": plastic("t_cherry", 2, 119),
    "t_cobalt": plastic("t_cobalt", 2, 120),
    "t_orange": plastic("t_orange", 2, 121),
    "t_candy_red": candy_red,
    "t_lilac": plastic("t_lilac", 2, 112),
    "t_caramel": plastic("t_caramel", 2, 114),
    "t_cream": plastic("t_cream", 2, 115),
    "t_white": plastic("t_snow", 3, 116),
    "t_cocoa": stitched("t_cocoa", 2),
    "t_fur": felt("t_snow", 2, 117),
    "t_plush": felt("t_mint", 2, 118),
}

T.SURFACES.update(SURFACES)
T.TRANSLUCENT.add("t_bubble")
T.TRANSLUCENT.add("t_glass")
