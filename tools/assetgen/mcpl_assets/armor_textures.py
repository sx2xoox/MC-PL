"""
공성전 장난감 병정 갑옷 (docs/siege-v2.md §7.3): the armour a 공성전 player wears, painted as a toy soldier's uniform per
team (blue / red) and armour tier (0 = the kit, I-III = 장난감 갑옷 from the shop), plus the inventory icons.

Equipment textures use the vanilla 64x32 humanoid layout (the same as the leather armour's):
  humanoid           helmet (head + hat), chestplate (body + arms), boots (legs)
  humanoid_leggings  leggings (body = waistband, legs = trousers)
The left arm and leg are drawn with the right ones' pixels mirrored, so limbs are painted symmetric.

The pack shows them as the trim texture of a leather-asset armour piece whose trim is one exact (pattern, material)
pair (models/toy_armor.py): the trim layer is drawn over the dyed leather, so every pixel the leather layer covers is
covered here too (`LEATHER_MASK`, checked by `check`).

Style (tools/assetgen/README.md): palette ramps only, fixed shapes (no noise: the armour is small and moves), light from
the top left (lit top rows / left columns one step up, folds and lower edges one step down), toy materials: felt team
cloth, white cotton belts, gold buttons and braid, black lacquer boots, a tin wind-up key. Higher tiers add more gold.
"""

from PIL import Image

from . import toy_textures  # noqa: F401  (registers the pastel t_* ramps used here)
from .palette import PALETTE, add_ramps, ramp

W, H = 64, 32

# team cloth: the hue and chroma of the 공성전 team colours (VanillaCreatureLook.BLUE / RED), a touch calmer
add_ramps({
    "ta_blue": (260, 0.115, 0.58, 0.40, 5, False),
    "ta_red": (15, 0.125, 0.60, 0.40, 5, False),
})

TEAMS = ("blue", "red")
TIERS = (0, 1, 2, 3)

# Minecraft box UV of the armour model parts: (u, v, width, height, depth)
PARTS = {
    "head": (0, 0, 8, 8, 8),
    "hat": (32, 0, 8, 8, 8),
    "body": (16, 16, 8, 12, 4),
    "arm": (40, 16, 4, 12, 4),
    "leg": (0, 16, 4, 12, 4),
}
FACES = ("top", "bottom", "right", "front", "left", "back")


def region(part, face):
    """(x, y, w, h) of a face on the 64x32 sheet. 'right' is the character's right side (outer side of a limb)."""
    u, v, w, h, d = PARTS[part]
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }[face]


# The vanilla leather armour's opaque pixels (26.3 textures/entity/equipment/humanoid[_leggings]/leather.png +
# leather_overlay.png), row by row as (x0, x1) spans: the toy texture must hide every one of them.
_LEATHER = {
    "humanoid": {
        **{y: [(8, 16)] for y in range(0, 8)},
        8: [(0, 32)], 9: [(0, 32)], 10: [(0, 32)],
        11: [(0, 9), (15, 32)], 12: [(0, 4), (20, 32)], 13: [(24, 32)], 14: [(26, 30)],
        **{y: [(8, 12), (44, 48)] for y in range(16, 20)},
        20: [(16, 22), (26, 30), (34, 56)], 21: [(16, 23), (25, 56)],
        **{y: [(16, 56)] for y in range(22, 26)},
        **{y: [(0, 40)] for y in range(26, 29)},
        29: [(0, 16), (21, 27), (33, 39)], 30: [(0, 16), (22, 26)], 31: [(0, 16)],
    },
    "humanoid_leggings": {
        **{y: [(4, 8)] for y in range(16, 20)},
        **{y: [(0, 16)] for y in range(20, 27)},
        27: [(0, 40)], 28: [(0, 40)],
        **{y: [(16, 40)] for y in range(29, 32)},
    },
}


def leather_mask(layer):
    return {(x, y) for y, spans in _LEATHER[layer].items() for x0, x1 in spans for x in range(x0, x1)}


class Sheet:
    """One 64x32 equipment texture, painted face by face in face-local pixels (lx, ly from the face's top left as
    the texture shows it: on the front the left column is the character's right side)."""

    def __init__(self):
        self.im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        self.px = self.im.load()

    def put(self, part, face, lx, ly, colour):
        x, y, w, h = region(part, face)
        if 0 <= lx < w and 0 <= ly < h and colour is not None:
            self.px[x + lx, y + ly] = colour

    def fill(self, part, face, fn, rows=None):
        """fn(lx, ly, w, h) -> colour or None, for every pixel of the face (rows: only these face rows)."""
        x, y, w, h = region(part, face)
        for ly in (rows if rows is not None else range(h)):
            if not 0 <= ly < h:
                continue
            for lx in range(w):
                c = fn(lx, ly, w, h)
                if c is not None:
                    self.px[x + lx, y + ly] = c

    def fill_all(self, part, fn, faces=FACES, rows=None):
        for face in faces:
            self.fill(part, face, lambda lx, ly, w, h, f=face: fn(f, lx, ly, w, h), rows)

    def clear(self, part, face, lx, ly):
        x, y, w, h = region(part, face)
        if 0 <= lx < w and 0 <= ly < h:
            self.px[x + lx, y + ly] = (0, 0, 0, 0)


# ------------------------------------------------------------------------------------------------ colours

def cloth(team, i):
    return ramp(f"ta_{team}", i)


def white(i):
    return ramp("t_snow", i)


def gold(i):
    return ramp("gold", i)


def ink(i):
    return ramp("t_ink", i)


def tin(i):
    return ramp("steel", i)


def rose(i):
    return ramp("t_rose", i)


def gem(team, i):
    """The crest's jewel: a sapphire on red, a ruby on blue (the opposite hue reads on the team cloth)."""
    return ramp("t_sky", i) if team == "red" else ramp("t_rose", i)


def _felt(team, face, lx, ly, w, h):
    """Team felt: lit top row and left column, the right column one step darker (the fold of a sewn edge)."""
    i = 2
    if face == "top":
        i = 3
    elif face == "bottom":
        i = 1
    elif lx == w - 1 and face in ("front", "back"):
        i = 1
    elif lx == 0 and face in ("front", "back") and ly < 2:
        i = 3
    return cloth(team, i)


# ------------------------------------------------------------------------------------------------ helmet

# the cap's black band: over the forehead in front (the visor), lower on the sides, lowest at the back (+ a neck flap)
_BAND = {"front": 3, "right": 4, "left": 4, "back": 5}


def _helmet(s, team, tier):
    """A toy soldier's cap: team felt over the crown of the head, a black lacquer visor and band, a white chin strap,
    a gold badge; II adds a rose pompom, III a gold crown band with a jewel."""
    crown = tier >= 3
    # the cap: top face whole, sides and back down to the band, the front over the forehead
    s.fill("head", "top", lambda lx, ly, w, h: cloth(team, 3 if (lx + ly) % 7 else 4))
    s.fill("head", "bottom", lambda lx, ly, w, h: ink(0) if ly == 0 else None)  # the visor's underside

    def side(face, lx, ly, w, h):
        if face in ("top", "bottom"):
            return None
        band = _BAND[face]
        if crown and ly <= 2:
            return gold(3 if ly == 0 else 2 if ly == 1 else 1)
        if ly < band:
            return _felt(team, face, lx, ly, w, h) if ly else cloth(team, 3)
        if ly == band:
            return ink(2 if lx % 4 == 1 else 1)
        if face == "back" and ly == band + 1 and 1 <= lx <= 6:
            return ink(0)                       # the neck flap
        return None
    s.fill_all("head", side)
    # the visor: black lacquer across the front, a lit rim
    for lx in range(8):
        s.put("head", "front", lx, 3, ink(2) if lx in (1, 2) else ink(1))
    # the chin strap: down the sides near the front edge, gold from II
    strap = gold(2) if tier >= 2 else white(2)
    for ly in range(5, 8):
        s.put("head", "right", 6, ly, strap)    # right side: the front edge is on the right
        s.put("head", "left", 1, ly, strap)     # left side: the front edge is on the left
    for lx in range(1, 7):
        s.put("head", "bottom", lx, 1, strap)   # under the chin (the bottom face's top row is the front)
    # the badge on the front
    if crown:
        # the crown band: jewels between gold points, the big one in front
        for lx in (0, 7):
            s.put("head", "front", lx, 0, gold(1))
        for lx in (3, 4):
            s.put("head", "front", lx, 1, gem(team, 2))
        s.put("head", "front", 3, 0, gem(team, 3))
        s.put("head", "front", 4, 2, gem(team, 1))
        for face in ("right", "left", "back"):
            s.put("head", face, 3, 1, gem(team, 2))
            s.put("head", face, 4, 1, gem(team, 1))
            s.put("head", face, 3, 0, gem(team, 3))
        # the crown's rim seen from above, around the velvet
        s.fill("head", "top", lambda lx, ly, w, h: gold(2 if lx in (0, 7) or ly in (0, 7) else 0)
               if lx in (0, 7) or ly in (0, 7) else None)
        for lx, ly in ((0, 0), (7, 0), (0, 7), (7, 7)):
            s.put("head", "top", lx, ly, gold(3))
        s.put("head", "top", 3, 7, gem(team, 3))
        s.put("head", "top", 4, 7, gem(team, 2))
    elif tier >= 1:
        # a gold star badge (a plus with a lit centre)
        for lx, ly in ((3, 0), (4, 0), (2, 1), (5, 1), (3, 2), (4, 2)):
            s.put("head", "front", lx, ly, gold(2))
        s.put("head", "front", 3, 1, gold(3))
        s.put("head", "front", 4, 1, gold(1))
        # a gold band above the visor on the sides and back
        for face in ("right", "left", "back"):
            band = _BAND[face]
            for lx in range(8):
                s.put("head", face, lx, band - 1, gold(1 if lx % 3 else 2))
    else:
        s.put("head", "front", 3, 1, gold(3))
        s.put("head", "front", 4, 1, gold(2))
        s.put("head", "front", 3, 2, gold(1))
        s.put("head", "front", 4, 2, gold(1))
    # the top button / pompom (on the head layer) and the plume (on the hat layer, half a pixel higher)
    if tier >= 2:
        pom = (lambda i: gem(team, i)) if crown else rose
        for lx in range(2, 6):
            for ly in range(2, 6):
                corner = lx in (2, 5) and ly in (2, 5)
                if not corner:
                    s.put("hat", "top", lx, ly, pom(3 if (lx, ly) in ((3, 2), (2, 3)) else 2 if lx < 4 else 1))
        s.put("hat", "top", 3, 3, white(3))
    else:
        for lx in (3, 4):
            for ly in (3, 4):
                s.put("head", "top", lx, ly, gold(3 if (lx, ly) == (3, 3) else 1 if (lx, ly) == (4, 4) else 2))
    # the visor again on the hat layer: it stands half a pixel proud of the cap
    for lx in range(8):
        s.put("hat", "front", lx, 3, ink(2) if lx in (1, 2) else ink(0))


# ------------------------------------------------------------------------------------------------ tunic

HEART = [(1, 0), (2, 0), (5, 0), (6, 0), *[(x, 1) for x in range(1, 7)], *[(x, 2) for x in range(2, 6)],
         (3, 3), (4, 3)]
"""A 6 x 4 heart on an 8 px face (columns 1-6), relative to its top row."""


def _key(s, team, tier, top=1):
    """The wind-up key on the back: a bow of two hollow loops (the cloth shows through them) on a collar, and a short
    shaft into the back (tin; gold from II)."""
    metal = gold if tier >= 2 else (lambda i: tin(min(5, i + 3)))
    for lx, ly, i in ((1, 0, 3), (2, 0, 3), (5, 0, 3), (6, 0, 2), (0, 1, 2), (7, 1, 1), (1, 2, 1), (2, 2, 1),
                      (5, 2, 1), (6, 2, 0), (3, 1, 2), (4, 1, 1)):
        s.put("body", "back", lx, top + ly, metal(i))
    for ly in (2, 3):
        s.put("body", "back", 3, top + ly, metal(2))
        s.put("body", "back", 4, top + ly, metal(1))
    s.put("body", "back", 3, top + 4, metal(0))
    s.put("body", "back", 4, top + 4, metal(0))


def _tunic(s, team, tier):
    """The tunic: team felt with a white collar and belt (gold on III) over the body, sleeves down to white cuffs.
    I: a double row of gold buttons and a key on the back; II: a white sash, a medal and gold epaulettes;
    III: a silver breastplate with the team heart, gold braid, a cape."""
    s.fill_all("body", lambda f, lx, ly, w, h: _felt(team, f, lx, ly, w, h))
    belt = gold if tier >= 3 else white
    # collar
    for lx in range(2, 6):
        s.put("body", "front", lx, 0, belt(3) if lx in (2, 3) else belt(2))
        s.put("body", "top", lx, 3, belt(3))
    # belt (rows 9-10) all round, buckle in front
    for face in ("front", "right", "left", "back"):
        x, y, w, h = region("body", face)
        for lx in range(w):
            s.put("body", face, lx, 9, belt(3) if face == "front" else belt(2))
            s.put("body", face, lx, 10, belt(1))
        s.put("body", face, w - 1, 11, cloth(team, 0))
        for lx in range(w - 1):
            s.put("body", face, lx, 11, cloth(team, 1))
    buckle = gem if tier >= 3 else (lambda t, i: gold(i))
    s.put("body", "front", 3, 9, buckle(team, 3))
    s.put("body", "front", 4, 9, buckle(team, 2))
    s.put("body", "front", 3, 10, buckle(team, 1))
    s.put("body", "front", 4, 10, buckle(team, 1))
    if tier < 3:
        for lx, ly in ((2, 9), (5, 9), (2, 10), (5, 10)):
            s.put("body", "front", lx, ly, gold(1))

    if tier in (1, 2):
        # placket: a lighter centre strip with a double row of gold buttons
        for ly in range(1, 9):
            s.put("body", "front", 3, ly, cloth(team, 3))
            s.put("body", "front", 4, ly, cloth(team, 2))
        for ly in (2, 4, 6, 8):
            s.put("body", "front", 2, ly, gold(3))
            s.put("body", "front", 5, ly, gold(2))
    if tier == 0:
        # three round gold buttons down the middle
        for ly in (2, 4, 6):
            s.put("body", "front", 3, ly, gold(3))
            s.put("body", "front", 4, ly, gold(1))
    if tier == 2:
        # the white sash from the right shoulder to the left hip, a rose ribbon medal on the left breast
        for ly in range(0, 9):
            lx = ly * 7 // 8
            s.put("body", "front", lx, ly, white(3))
            s.put("body", "front", lx + 1, ly, white(2) if ly < 8 else white(1))
        for ly in range(0, 9):
            s.put("body", "back", 7 - ly * 7 // 8, ly, white(2))
        s.put("body", "front", 6, 1, rose(3))
        s.put("body", "front", 6, 2, rose(1))
        s.put("body", "front", 6, 3, gold(3))
        s.put("body", "front", 7, 1, rose(2))
        s.put("body", "front", 7, 2, rose(1))
        s.put("body", "front", 7, 3, gold(1))
    if tier >= 3:
        # the breastplate: silver enamel bordered in gold, the team heart with a jewel in the middle
        for ly in range(1, 9):
            for lx in range(0, 8):
                edge = lx in (0, 7) or ly in (1, 8)
                if lx in (0, 7) and ly in (1, 8):
                    continue
                if edge:
                    c = gold(3 if lx == 0 or ly == 1 else 1)
                else:
                    c = white(3 if lx <= 2 and ly <= 3 else 2 if lx < 6 else 1)
                s.put("body", "front", lx, ly, c)
        for lx, ly in HEART:
            lit = ly == 0 or lx == 1
            s.put("body", "front", lx, 3 + ly, cloth(team, 3 if lit else 2 if lx < 5 else 1))
        s.put("body", "front", 2, 3, white(3))   # the heart's shine
        # gold braid on the shoulders (top face) and down the side seams
        for lx in range(8):
            s.put("body", "top", lx, 0, gold(2))
        for face in ("right", "left"):
            for ly in range(1, 9):
                s.put("body", face, 1 if face == "left" else 2, ly, gold(1 if ly % 2 else 2))
        # the cape on the back: deep team felt in folds, a gold hem, a gold crown clasp and the key's bows above
        for ly in range(0, 12):
            for lx in range(8):
                fold = lx % 3 == 2
                c = cloth(team, 0 if fold else 1)
                if ly == 0:
                    c = cloth(team, 2)
                if ly == 11:
                    c = gold(2 if lx % 2 else 1)
                s.put("body", "back", lx, ly, c)
        for lx, ly in HEART:
            s.put("body", "back", lx, 5 + ly, gold(3 if ly == 0 or lx == 1 else 2 if lx < 5 else 1))
        for lx, ly in ((0, 0), (1, 0), (6, 0), (7, 0)):
            s.put("body", "back", lx, ly, gold(3 if lx in (0, 7) else 2))
        _key(s, team, tier, top=0)
        # the cape continues round the sides' back edges
        s.put("body", "right", 0, 0, cloth(team, 1))
        for ly in range(1, 12):
            s.put("body", "right", 0, ly, cloth(team, 0) if ly < 11 else gold(1))
            s.put("body", "left", 3, ly, cloth(team, 0) if ly < 11 else gold(1))
    elif tier >= 1:
        _key(s, team, tier)
    else:
        _key(s, team, tier, top=2)

    # sleeves: felt to a white cuff at row 8 (gold from II); the hands stay bare (rows 9-11)
    cuff = gold if tier >= 2 else white

    def sleeve(face, lx, ly, w, h):
        if face == "bottom":
            return None
        if face == "top":
            return cloth(team, 3)
        if ly > 8:
            return None
        if ly == 8:
            return cuff(3 if lx == 0 else 2)
        if ly == 7 and tier >= 1 and face != "left":
            return gold(2 if tier >= 3 else 1)
        if face == "left":
            return cloth(team, 1)   # the inner side, out of the light
        return _felt(team, face, lx, ly, w, h)
    s.fill_all("arm", sleeve)
    if tier >= 2:
        # gold epaulettes: a pad on top and a fringe over the shoulder (III: longer, with a jewel)
        for lx in range(4):
            for ly in range(4):
                s.put("arm", "top", lx, ly, gold(3 if (lx + ly) < 2 else 2 if lx < 3 else 1))
        fringe = 3 if tier >= 3 else 2
        for face in ("right", "front", "back"):
            for lx in range(4):
                s.put("arm", face, lx, 0, gold(2))
                for ly in range(1, fringe):
                    s.put("arm", face, lx, ly, gold(1 if lx % 2 else 2))
        if tier >= 3:
            s.put("arm", "top", 1, 1, gem(team, 3))
            s.put("arm", "top", 2, 2, gem(team, 1))
            # a gold chevron on the outer side
            for lx, ly in ((0, 4), (1, 5), (2, 5), (3, 4)):
                s.put("arm", "right", lx, ly, gold(2))


# ------------------------------------------------------------------------------------------------ boots

def _boots(s, team, tier):
    """Black lacquer boots from row 6 down (a white turn-down cuff, gold on III), a shine down the front, a sole;
    I: a gold buckle, II: gold heels, III: gold toe caps and a jewelled buckle."""
    cuff = gold if tier >= 3 else white

    def boot(face, lx, ly, w, h):
        if face == "top":
            return None
        if face == "bottom":
            return ink(0)
        if ly < 6:
            return None
        if ly == 6:
            return cuff(3 if lx < 2 else 2)
        if ly == 11:
            return ink(0)
        if face == "front" and lx == 1 and ly in (7, 8):
            return ink(2)
        if face == "front" and lx == 0 and ly == 7:
            return white(0)                     # the lacquer's glint
        return ink(1)
    s.fill_all("leg", boot)
    if tier >= 1:
        b = (lambda i: gem(team, i)) if tier >= 3 else gold
        s.put("leg", "front", 1, 8, b(3))
        s.put("leg", "front", 2, 8, b(2))
        s.put("leg", "front", 1, 9, gold(1))
        s.put("leg", "front", 2, 9, gold(1))
    if tier >= 2:
        for lx in range(4):
            s.put("leg", "back", lx, 10, gold(1))
            s.put("leg", "back", lx, 11, gold(0))
    if tier >= 3:
        for lx in range(4):
            s.put("leg", "front", lx, 10, gold(2))
            s.put("leg", "front", lx, 11, gold(1))
        for lx in range(4):
            s.put("leg", "bottom", lx, 0, gold(1))


# ------------------------------------------------------------------------------------------------ trousers

def _trousers(s, team, tier):
    """White trousers with a team stripe down the outer seam (I: wider, III: gold edged), a white waistband with the
    team-coloured pleat in front."""
    def leg(face, lx, ly, w, h):
        if face == "bottom":
            return white(1)
        if face == "top":
            return white(3)
        i = 2
        if face == "left":
            i = 1
        elif lx == 0 and face == "front":
            i = 3
        elif ly in (4, 5) and face == "front" and lx == 2:
            i = 1                               # the knee crease
        return white(i)
    s.fill_all("leg", leg)
    for ly in range(12):
        if tier >= 3:
            s.put("leg", "right", 0, ly, gold(2))
            s.put("leg", "right", 3, ly, gold(1))
            s.put("leg", "right", 1, ly, cloth(team, 3))
            s.put("leg", "right", 2, ly, cloth(team, 2))
        elif tier >= 1:
            s.put("leg", "right", 1, ly, cloth(team, 3))
            s.put("leg", "right", 2, ly, cloth(team, 2))
        else:
            s.put("leg", "right", 2, ly, cloth(team, 2))

    def waist(face, lx, ly, w, h):
        if face in ("top", "bottom"):
            return white(1) if face == "bottom" else None
        if ly < 7:
            return None
        if ly == 7:
            return white(3)
        return white(2 if face != "left" else 1)
    s.fill_all("body", waist)
    for ly in range(8, 12):
        s.put("body", "front", 3, ly, cloth(team, 2))
        s.put("body", "front", 4, ly, cloth(team, 1))


# ------------------------------------------------------------------------------------------------ public

def humanoid(team, tier):
    """Helmet, chestplate and boots (the 'humanoid' equipment layer)."""
    s = Sheet()
    _helmet(s, team, tier)
    _tunic(s, team, tier)
    _boots(s, team, tier)
    return s.im


def leggings(team, tier):
    """The trousers (the 'humanoid_leggings' equipment layer)."""
    s = Sheet()
    _trousers(s, team, tier)
    return s.im


LAYERS = {"humanoid": humanoid, "humanoid_leggings": leggings}


def palette_colours():
    return {tuple(c[:3]) for r in PALETTE.values() for c in r}


def check(layer, im):
    """Problems of one equipment texture: off-palette pixels, semi-transparent pixels (the trim layer is cutout),
    leather pixels it would leave showing."""
    problems = []
    allowed = palette_colours()
    px = im.load()
    for y in range(H):
        for x in range(W):
            r, g, b, a = px[x, y]
            if a not in (0, 255):
                problems.append(f"({x}, {y}) is semi-transparent")
            elif a and (r, g, b) not in allowed:
                problems.append(f"({x}, {y}) {r, g, b} is not a palette colour")
    for x, y in sorted(leather_mask(layer)):
        if px[x, y][3] == 0:
            problems.append(f"({x}, {y}) leaves the dyed leather showing")
    return problems
