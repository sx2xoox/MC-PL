"""
Inventory icons of the 공성전 장난감 병정 갑옷 (armor_textures.py): 16x16 pixel art per piece, team and tier.

Each icon is a letter grid ('.' = clear; the letter's material and ramp step in _ink) drawn for tier 0, then the tier's
details painted over it. Edges that face down or right take the material's darkest step (the outline vanilla items
have), so the icon reads on any slot background. Palette ramps only (check_icon).
"""

from PIL import Image

from .armor_textures import cloth, gem, gold, ink, palette_colours, rose, white

PIECES = ("helmet", "chestplate", "leggings", "boots")

_ICONS = {
    "helmet": [
        "................",
        "................",
        "......LLLL......",
        "....LLCCCCLL....",
        "...LCCCCCCCCc...",
        "...CCCCYGCCCc...",
        "...CCCCGgCCCc...",
        "...CCCCCCCCCc...",
        "..HKKKKKKKKKKk..",
        "..KKKKKKKKKKKk..",
        "...W........w...",
        "....W......w....",
        ".....WWWwww.....",
        "................",
        "................",
        "................",
    ],
    "chestplate": [
        "................",
        "..LLL......LLL..",
        ".LCCCLWWWWLCCCL.",
        "LCCCCCCWWCCCCCCc",
        "CCCCCCCCCCCCCCCc",
        "CCC.CCYCCCCC.CCc",
        "CCC.CCgCCCCC.CCc",
        "WWw.CCCCCCCC.WWw",
        "....CCYCCCCC....",
        "....CCgCCCCc....",
        "....WWWYGWWw....",
        "....CCCCCCCc....",
        "....cccccccc....",
        "................",
        "................",
        "................",
    ],
    "leggings": [
        "................",
        "................",
        "...WWWWWWWWWw...",
        "...wwwwCcwwww...",
        "...WWCWWWWCWw...",
        "...WWCWWWWCWw...",
        "...WWCW..WCWw...",
        "...WWCW..WCWw...",
        "...WWCW..WCWw...",
        "...WWCW..WCWw...",
        "...WWCW..WCWw...",
        "...WWCW..WCWw...",
        "...wwww..wwww...",
        "................",
        "................",
        "................",
    ],
    "boots": [
        "................",
        "................",
        "................",
        "................",
        "...WWw....WWw...",
        "...KKk....KKk...",
        "...HKk....HKk...",
        "...HKk....HKk...",
        "...KKk....KKk...",
        "..KKKk....KKKk..",
        ".HKKKk....KKKKk.",
        ".kkkkk....kkkkk.",
        "................",
        "................",
        "................",
        "................",
    ],
}


def _band(y0, y1, x0, x1, letter):
    return [(x, y, letter) for x in range(x0, x1) for y in range(y0, y1)]


_HELMET_1 = [(6, 4, "Y"), (7, 4, "Y"), (5, 5, "G"), (8, 5, "G"), (6, 6, "G"), (7, 6, "g"),
             *[(x, 7, "g") for x in range(3, 13)]]
_GOLD_STRAP = [(3, 10, "G"), (12, 10, "g"), (4, 11, "G"), (11, 11, "g"), *[(x, 12, "g") for x in range(5, 11)]]
_EPAULETTES = [*[(x, 1, "Y") for x in (2, 3, 4, 11, 12, 13)], *[(x, 2, "G") for x in (1, 2, 3, 12, 13, 14)],
               (0, 7, "G"), (1, 7, "G"), (2, 7, "g"), (13, 7, "G"), (14, 7, "G"), (15, 7, "g")]
_BUTTONS = [*[(x, y, "Y") for x in (5, 10) for y in (4, 6, 8)], (6, 5, "C"), (6, 6, "C"), (6, 8, "C"), (6, 9, "C")]
_STRIPES = [*_band(4, 12, 5, 6, "L"), *_band(4, 12, 10, 11, "c")]

# tier details: piece -> tier -> [(x, y, letter)] painted over the tier 0 icon
_ICON_TIERS = {
    "helmet": {
        1: _HELMET_1,
        2: [*_HELMET_1, (6, 0, "R"), (7, 0, "Q"), (5, 1, "R"), (6, 1, "Q"), (7, 1, "R"), (8, 1, "r"),
            (6, 2, "r"), (7, 2, "r"), *_GOLD_STRAP],
        3: [*[(x, y, "Y" if y == 4 else "G" if y == 5 else "g") for x in range(3, 13) for y in (4, 5, 6)],
            (3, 3, "Y"), (6, 3, "Y"), (9, 3, "Y"), (12, 3, "Y"), (3, 2, "G"), (12, 2, "G"),
            (6, 2, "j"), (9, 2, "j"), (7, 5, "J"), (8, 5, "j"), (4, 5, "J"), (11, 5, "J"),
            (6, 0, "J"), (7, 0, "j"), (5, 1, "J"), (6, 1, "j"), (7, 1, "J"), (8, 1, "J"), *_GOLD_STRAP],
    },
    "chestplate": {
        1: [*_BUTTONS, *_band(3, 10, 7, 8, "L")],
        2: [*_BUTTONS, *[(4 + i, 2 + i, "W") for i in range(8)], *[(5 + i, 2 + i, "w") for i in range(7)],
            (10, 3, "R"), (10, 4, "r"), (10, 5, "Y"), (11, 3, "R"), (11, 4, "r"), (11, 5, "g"), *_EPAULETTES],
        3: [*[(x, y, "W" if x < 9 else "w") for x in range(5, 11) for y in range(3, 10)],
            *[(x, 3, "Y") for x in range(5, 11)], *[(x, 9, "g") for x in range(5, 11)],
            *_band(3, 10, 4, 5, "G"), *_band(3, 10, 11, 12, "g"),
            (6, 5, "L"), (9, 5, "C"), *[(x, 6, "C") for x in range(6, 10)], (6, 6, "L"), (7, 7, "C"), (8, 7, "C"),
            (7, 6, "j"), (8, 6, "J"), *[(x, 2, "Y") for x in range(6, 10)],
            *[(x, 10, "Y" if x < 8 else "G") for x in range(4, 12)], (7, 10, "j"), (8, 10, "J"),
            *_EPAULETTES, (1, 3, "J"), (14, 3, "J")],
    },
    "leggings": {
        1: _STRIPES,
        2: [*_STRIPES, (7, 2, "Y"), (8, 2, "G")],
        3: [*_band(4, 12, 4, 5, "Y"), *_STRIPES, *_band(4, 12, 11, 12, "g"),
            *[(x, 2, "G") for x in range(3, 13)], (7, 2, "J"), (8, 2, "j")],
    },
    "boots": {
        1: [(4, 8, "Y"), (5, 8, "G"), (11, 8, "Y"), (12, 8, "G")],
        2: [(4, 8, "Y"), (5, 8, "G"), (11, 8, "Y"), (12, 8, "G"), (5, 11, "g"), (10, 11, "g"),
            (3, 4, "G"), (4, 4, "G"), (5, 4, "g"), (10, 4, "G"), (11, 4, "G"), (12, 4, "g")],
        3: [(4, 8, "J"), (5, 8, "j"), (11, 8, "J"), (12, 8, "j"),
            (3, 4, "Y"), (4, 4, "Y"), (5, 4, "G"), (10, 4, "Y"), (11, 4, "Y"), (12, 4, "G"),
            (1, 10, "Y"), (2, 10, "G"), (1, 11, "g"), (2, 11, "g"), (13, 10, "G"), (14, 10, "Y"),
            (13, 11, "g"), (14, 11, "g")],
    },
}


def _ink(team, letter):
    """(colour function, ramp step) of an icon letter."""
    team_cloth = lambda i: cloth(team, i)  # noqa: E731
    jewel = lambda i: gem(team, i)  # noqa: E731
    return {
        "C": (team_cloth, 2), "L": (team_cloth, 3), "c": (team_cloth, 1),
        "W": (white, 2), "w": (white, 0), "Q": (white, 3),
        "G": (gold, 2), "Y": (gold, 3), "g": (gold, 1),
        "K": (ink, 1), "H": (ink, 2), "k": (ink, 0),
        "J": (jewel, 2), "j": (jewel, 3),
        "R": (rose, 2), "r": (rose, 1),
    }[letter]


def icon(piece, team, tier):
    rows = [list(r) for r in _ICONS[piece]]
    for x, y, letter in _ICON_TIERS[piece].get(tier, []):
        rows[y][x] = letter
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()

    def clear(x, y):
        return not (0 <= x < 16 and 0 <= y < 16) or rows[y][x] == "."
    for y in range(16):
        for x in range(16):
            letter = rows[y][x]
            if letter == ".":
                continue
            fn, step = _ink(team, letter)
            if clear(x + 1, y) or clear(x, y + 1):
                step = 0
            px[x, y] = fn(step)
    return im


def check_icon(im):
    allowed = palette_colours()
    return [f"pixel {(x, y)} is off-palette" for y in range(16) for x in range(16)
            if im.getpixel((x, y))[3] and tuple(im.getpixel((x, y))[:3]) not in allowed]
