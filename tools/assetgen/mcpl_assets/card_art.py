"""Art of the floating augment cards (증강 야생전, `augments.ui: CARDS`): card frames per tier, key caps, the reroll
mark, the title ribbon and the timer bar. Everything is drawn in code from palette ramps (STSV style: light from the
top-left, one ramp step of bevel, a dark outline); `augment_cards.py` writes the glyphs. The augment icons are
illustrations painted by `augment_icons.py` (`icon_kit`).
"""

import math

from PIL import Image, ImageDraw

from .palette import add_ramps, ramp, size

add_ramps({
    "card_ink":    (282, 0.040, 0.26, 0.14, 5, False),  # the card's lacquered panel
    "card_silver": (245, 0.022, 0.70, 0.46, 5, False),  # silver frame
    "card_gold":   (88, 0.120, 0.72, 0.46, 5, True),    # gold frame
    "prism_cyan":  (205, 0.110, 0.74, 0.42, 5, True),   # prism frame: the colour runs round the border
    "prism_violet": (300, 0.120, 0.66, 0.42, 5, True),
    "prism_pink":  (352, 0.110, 0.72, 0.42, 5, True),
    "ic_red":      (25, 0.165, 0.56, 0.36, 4, True),
    "ic_white":    (85, 0.010, 0.90, 0.16, 3, False),
    "ic_sky":      (230, 0.110, 0.68, 0.36, 4, True),
})

CLEAR = (0, 0, 0, 0)
OUTLINE = ramp("card_ink", 0)


def top(name):
    return size(name) - 1


# ====================================================================== shape layer helper (small pixel glyphs)

class Canvas:
    """An RGBA image built from bevelled shapes: each shape is a mask filled with a ramp, its top-left edge one step
    lighter and its bottom-right edge one step darker; `finish` adds the dark outline round everything."""

    def __init__(self, w=32, h=32):
        self.w, self.h = w, h
        self.im = Image.new("RGBA", (w, h), CLEAR)

    def mask(self):
        return Image.new("L", (self.w, self.h), 0)

    def fill(self, mask, name, mid=None, bevel=True, light=None, dark=None):
        n = size(name)
        mid = (n - 1) // 2 + (1 if n % 2 == 0 else 0) if mid is None else mid
        light = min(n - 1, mid + 1) if light is None else light
        dark = max(0, mid - 1) if dark is None else dark
        m = mask.load()
        px = self.im.load()
        for y in range(self.h):
            for x in range(self.w):
                if not m[x, y]:
                    continue
                shade = mid
                if bevel:
                    if x == 0 or y == 0 or not m[x - 1, y] or not m[x, y - 1]:
                        shade = light
                    elif x == self.w - 1 or y == self.h - 1 or not m[x + 1, y] or not m[x, y + 1]:
                        shade = dark
                px[x, y] = ramp(name, shade)
        return self

    def poly(self, points, name, **kw):
        m = self.mask()
        ImageDraw.Draw(m).polygon([(round(x), round(y)) for x, y in points], fill=255)
        return self.fill(m, name, **kw)

    def ellipse(self, box, name, **kw):
        m = self.mask()
        ImageDraw.Draw(m).ellipse(box, fill=255)
        return self.fill(m, name, **kw)

    def rect(self, box, name, **kw):
        m = self.mask()
        ImageDraw.Draw(m).rectangle(box, fill=255)
        return self.fill(m, name, **kw)

    def line(self, points, name, width=1, **kw):
        m = self.mask()
        ImageDraw.Draw(m).line([(round(x), round(y)) for x, y in points], fill=255, width=width)
        kw.setdefault("bevel", width > 1)
        return self.fill(m, name, **kw)

    def dots(self, points, colour):
        px = self.im.load()
        for x, y in points:
            if 0 <= x < self.w and 0 <= y < self.h:
                px[x, y] = colour
        return self

    def outline(self, colour=OUTLINE):
        src = self.im.copy().load()
        px = self.im.load()
        for y in range(self.h):
            for x in range(self.w):
                if src[x, y][3]:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < self.w and 0 <= ny < self.h and src[nx, ny][3] > 128:
                        px[x, y] = colour
                        break
        return self


# ====================================================================== card frames (112 x 160, body 96 x 144)

FRAME_W, FRAME_H = 112, 160
MARGIN = 8
BODY_W, BODY_H = 96, 144
CUT = 7                                   # chamfered corners
# Up-down balanced: the medallion and the name band fill the upper half (4 px from the inner border to the ring), the
# description panel the lower half (4 px to the inner border); gems and corner marks mirror top and bottom.
MEDALLION = (56, MARGIN + 32, 22)         # centre x, centre y, radius
NAME_BAND = (MARGIN + 58, MARGIN + 72)    # top, bottom (y)
DESC_PANEL = (MARGIN + 76, MARGIN + 134)  # top, bottom (y)

TIER_RAMP = {"silver": "card_silver", "gold": "card_gold", "prism": None}
PRISM = ("prism_cyan", "prism_violet", "prism_pink")


def _inside_body(x, y, inset=0):
    x0, y0 = MARGIN + inset, MARGIN + inset
    x1, y1 = MARGIN + BODY_W - 1 - inset, MARGIN + BODY_H - 1 - inset
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cut = max(0, CUT - inset)
    dx = min(x - x0, x1 - x)
    dy = min(y - y0, y1 - y)
    return dx + dy >= cut


def _edge_distance(x, y):
    """0 on the outermost body pixel, 1 one inside, ...; -1 outside."""
    for d in range(0, 12):
        if not _inside_body(x, y, d):
            return d - 1
    return 12


def tier_colour(tier, step, x=0, y=0):
    if tier == "prism":
        t = ((x - MARGIN) / BODY_W * 0.6 + (y - MARGIN) / BODY_H * 0.9) % 1.0
        name = PRISM[int(t * 3) % 3]
        return ramp(name, step)
    return ramp(TIER_RAMP[tier], step)


def frame(tier, lit=False):
    im = Image.new("RGBA", (FRAME_W, FRAME_H), CLEAR)
    px = im.load()
    boost = 1 if lit else 0
    mx, my, mr = MEDALLION
    for y in range(FRAME_H):
        for x in range(FRAME_W):
            d = _edge_distance(x, y)
            if d < 0:
                if lit:   # the glow round a highlighted card: three soft steps
                    out = 0
                    while out < MARGIN and not _inside_body(x, y, -out - 1):
                        out += 1
                    if out < 6:
                        alpha = (150, 95, 60, 35, 20, 10)[out]
                        r, g, b, _ = tier_colour(tier, 4, x, y)
                        px[x, y] = (r, g, b, alpha)
                continue
            if d == 0:
                c = tier_colour(tier, 0 + boost, x, y)
            elif d == 1:
                c = tier_colour(tier, 2 + boost, x, y)
            elif d == 2:
                c = tier_colour(tier, 4 if (x + y) % 2 == 0 or lit else 3, x, y)
            elif d == 3:
                c = tier_colour(tier, 1 + boost, x, y)
            elif d == 4:
                c = ramp("card_ink", 0)
            else:
                # the lacquered panel: lighter near the top, in bands (no smooth gradient)
                edge_y = min(y - MARGIN, MARGIN + BODY_H - 1 - y)
                band = 3 if edge_y < 26 else 2
                if lit and edge_y < 26:
                    band = 4
                c = ramp("card_ink", band)
                if d == 5 and lit:
                    r, g, b, _ = tier_colour(tier, 3, x, y)
                    c = (r, g, b, 255)
            px[x, y] = c
    d = ImageDraw.Draw(im)
    # corner filigree inside the border
    for cx, cy, sx, sy in ((MARGIN + 7, MARGIN + 7, 1, 1), (MARGIN + BODY_W - 8, MARGIN + 7, -1, 1),
                           (MARGIN + 7, MARGIN + BODY_H - 8, 1, -1), (MARGIN + BODY_W - 8, MARGIN + BODY_H - 8, -1, -1)):
        for i in range(5):
            px[cx + sx * i, cy] = tier_colour(tier, 3 + boost, cx, cy)
            px[cx, cy + sy * i] = tier_colour(tier, 3 + boost, cx, cy)
        px[cx + sx, cy + sy] = tier_colour(tier, 4, cx, cy)
    # medallion: rays behind, a ring and a dark well for the icon
    for a in range(0, 360, 30):
        ra = math.radians(a + 15)
        for r in range(mr + 2, mr + 7):
            x, y = round(mx + r * math.cos(ra)), round(my + r * math.sin(ra))
            if _edge_distance(x, y) >= 6 and y < NAME_BAND[0] - 1:
                px[x, y] = tier_colour(tier, 1 + boost, x, y)
    d.ellipse((mx - mr - 1, my - mr - 1, mx + mr + 1, my + mr + 1), fill=tier_colour(tier, 0, mx, my - mr))
    d.ellipse((mx - mr, my - mr, mx + mr, my + mr), fill=tier_colour(tier, 3 + boost, mx, my - mr))
    d.ellipse((mx - mr + 2, my - mr + 2, mx + mr - 2, my + mr - 2), fill=tier_colour(tier, 1, mx, my))
    d.ellipse((mx - mr + 3, my - mr + 3, mx + mr - 3, my + mr - 3), fill=ramp("card_ink", 0))
    d.ellipse((mx - mr + 6, my - mr + 6, mx + mr - 6, my + mr - 6), fill=ramp("card_ink", 1 + boost))
    # gems breaking the border, top and bottom
    gx = FRAME_W // 2
    for gy in (MARGIN + 1, MARGIN + BODY_H - 2):
        d.polygon([(gx, gy - 6), (gx + 6, gy), (gx, gy + 6), (gx - 6, gy)], fill=tier_colour(tier, 0, gx, gy))
        d.polygon([(gx, gy - 5), (gx + 5, gy), (gx, gy + 5), (gx - 5, gy)], fill=tier_colour(tier, 3 + boost, gx, gy))
        d.polygon([(gx, gy - 5), (gx + 5, gy), (gx, gy)], fill=tier_colour(tier, 4, gx, gy))
        px[gx - 2, gy - 1] = ramp("ic_white", 2)
    # name band: a darker strip with tier lines and diamond end caps
    t0, t1 = NAME_BAND
    x0, x1 = MARGIN + 6, MARGIN + BODY_W - 7
    d.rectangle((x0, t0, x1, t1), fill=ramp("card_ink", 0))
    d.line((x0 + 2, t0, x1 - 2, t0), fill=tier_colour(tier, 3 + boost, x0, t0))
    d.line((x0 + 2, t1, x1 - 2, t1), fill=tier_colour(tier, 1 + boost, x0, t1))
    for ex in (x0, x1):
        cy = (t0 + t1) // 2
        d.polygon([(ex, cy - 4), (ex + 4, cy), (ex, cy + 4), (ex - 4, cy)], fill=tier_colour(tier, 3 + boost, ex, cy))
    # description panel
    p0, p1 = DESC_PANEL
    d.rectangle((MARGIN + 7, p0, MARGIN + BODY_W - 8, p1), fill=ramp("card_ink", 0))
    d.rectangle((MARGIN + 8, p0 + 1, MARGIN + BODY_W - 9, p1 - 1), fill=ramp("card_ink", 1 + boost))
    return im


# ====================================================================== key caps, reroll mark, ribbon, timer bar

FONT_3X5 = {
    "1": [".X.", "XX.", ".X.", ".X.", "XXX"], "2": ["XX.", "..X", ".X.", "X..", "XXX"],
    "3": ["XX.", "..X", ".X.", "..X", "XX."], "4": ["X.X", "X.X", "XXX", "..X", "..X"],
    "5": ["XXX", "X..", "XX.", "..X", "XX."], "F": ["XXX", "X..", "XX.", "X..", "X.."],
    "Q": [".X.", "X.X", "X.X", "X.X", ".XX"], "^": [".X.", "XXX", "X.X", ".X.", ".X."],
}
FONT_5X7 = {
    "1": ["..X..", ".XX..", "..X..", "..X..", "..X..", "..X..", ".XXX."],
    "2": [".XXX.", "X...X", "....X", "...X.", "..X..", ".X...", "XXXXX"],
    "3": ["XXXX.", "....X", "....X", ".XXX.", "....X", "....X", "XXXX."],
    "4": ["...X.", "..XX.", ".X.X.", "X..X.", "XXXXX", "...X.", "...X."],
    "5": ["XXXXX", "X....", "XXXX.", "....X", "....X", "X...X", ".XXX."],
    "F": ["XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "X...."],
    "Q": [".XXX.", "X...X", "X...X", "X...X", "X.X.X", "X..X.", ".XX.X"],
    "^": ["..X..", ".XXX.", "XX.XX", "X...X", "..X..", "..X..", "..X.."],
}


def _glyph_text(px, x, y, rows, colour):
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch == "X":
                px[x + i, y + j] = colour


def keycap(label, lit=False):
    """The big key cap under a card (16 x 16): dark, or gold when the card is highlighted."""
    im = Image.new("RGBA", (16, 16), CLEAR)
    d = ImageDraw.Draw(im)
    body = "card_gold" if lit else "card_ink"
    d.rounded_rectangle((0, 0, 15, 15), 3, fill=ramp(body, 0))
    d.rounded_rectangle((1, 1, 14, 13), 2, fill=ramp(body, 3 if lit else 2))
    d.rounded_rectangle((2, 2, 13, 11), 2, fill=ramp(body, 4 if lit else 3))
    _glyph_text(im.load(), 5, 3, FONT_5X7[label][:7], ramp("card_ink", 0) if lit else ramp("ic_white", 2))
    return im


def small_key(label):
    """An inline key cap for the hint line (13 x 11, sits on the text line: ascent 9)."""
    w = 13
    im = Image.new("RGBA", (w, 11), CLEAR)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, w - 1, 10), 2, fill=ramp("card_ink", 0))
    d.rounded_rectangle((1, 0, w - 2, 9), 2, fill=ramp("card_silver", 3))
    d.line((2, 0, w - 3, 0), fill=ramp("card_silver", 4))
    _glyph_text(im.load(), 4, 1, FONT_5X7[label], ramp("card_ink", 0))
    return im


def reroll_mark(lit=False):
    """↻ under a card while rerolls are left (16 x 16)."""
    c = Canvas(16, 16)
    pts = []
    for i in range(24):
        a = math.radians(-40 + 280 * i / 23)
        pts.append((7.5 + 5.5 * math.cos(a), 8 + 5.5 * math.sin(a)))
    name = "ic_sky"
    c.line(pts, name, width=2, mid=3 if lit else 2, bevel=False)
    a = math.radians(-40)
    tx, ty = 7.5 + 5.5 * math.cos(a), 8 + 5.5 * math.sin(a)
    c.poly([(tx - 3, ty - 3), (tx + 3, ty - 2), (tx + 1, ty + 3)], name, mid=3 if lit else 2, bevel=False)
    c.outline()
    return c.im


RIBBON_W, RIBBON_H = 176, 18


def ribbon(tier):
    im = Image.new("RGBA", (RIBBON_W, RIBBON_H), CLEAR)
    px = im.load()
    for y in range(RIBBON_H):
        for x in range(RIBBON_W):
            edge = min(x, RIBBON_W - 1 - x)
            notch = abs(y - RIBBON_H // 2)
            if edge < 9 - notch // 2 and edge < 8:   # swallow tails
                if edge < (RIBBON_H // 2 - notch) // 2:
                    continue
            if y == 0 or y == RIBBON_H - 1:
                px[x, y] = tier_colour(tier, 0, x * 0.6, y)
            elif y == 1:
                px[x, y] = tier_colour(tier, 4, x * 0.6, y)
            elif y == RIBBON_H - 2:
                px[x, y] = tier_colour(tier, 1, x * 0.6, y)
            elif 14 <= edge < RIBBON_W // 2 and 3 <= y <= RIBBON_H - 4:
                px[x, y] = ramp("card_ink", 1 if y < RIBBON_H // 2 else 0)
            else:
                px[x, y] = tier_colour(tier, 2 if (x + y) % 2 else 3, x * 0.6, y)
    return im


BAR_W, BAR_H, BAR_STEPS = 122, 8, 20


def timer_bar(level):
    """The time left: `level` of BAR_STEPS segments (gold, turning red under a third)."""
    im = Image.new("RGBA", (BAR_W, BAR_H), CLEAR)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, BAR_W - 1, BAR_H - 1), 3, fill=ramp("card_ink", 0))
    d.rounded_rectangle((1, 1, BAR_W - 2, BAR_H - 2), 2, fill=ramp("card_ink", 2))
    fill = "ic_red" if level * 3 < BAR_STEPS else "card_gold"
    seg = (BAR_W - 4) / BAR_STEPS
    for i in range(level):
        x0 = 2 + round(i * seg)
        x1 = 2 + round((i + 1) * seg) - 2
        d.rectangle((x0, 2, x1, BAR_H - 3), fill=ramp(fill, 3))
        d.line((x0, 2, x1, 2), fill=ramp(fill, top(fill)))
    return im
