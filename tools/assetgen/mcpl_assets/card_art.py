"""Art of the floating augment cards (증강 야생전, `augments.ui: CARDS`): card frames per tier, key caps, the reroll
mark, the title ribbon, the timer bar and one icon per augment. Everything is drawn in code from palette ramps
(STSV style: light from the top-left, one ramp step of bevel, a dark outline); `augment_cards.py` writes the glyphs.

Icons are 32 x 32: the augment's own motif (built from a few shape helpers, one recipe per augment in `ICONS`) and a
small badge of its kind (전투, 자원, 기동, 능력치, 양날의 검, 특수) in the bottom-right corner.
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
    "ic_steel":    (250, 0.014, 0.66, 0.52, 4, False),
    "ic_dark":     (300, 0.030, 0.36, 0.22, 4, False),  # netherite, shadow
    "ic_wood":     (58, 0.075, 0.50, 0.30, 4, False),
    "ic_red":      (25, 0.165, 0.56, 0.36, 4, True),
    "ic_green":    (145, 0.140, 0.62, 0.40, 4, True),
    "ic_violet":   (305, 0.130, 0.54, 0.40, 4, True),
    "ic_white":    (85, 0.010, 0.90, 0.16, 3, False),
    "ic_diamond":  (195, 0.100, 0.76, 0.38, 4, True),
    "ic_emerald":  (155, 0.150, 0.68, 0.40, 4, True),
    "ic_ice":      (225, 0.060, 0.84, 0.28, 4, False),
    "ic_slime":    (135, 0.130, 0.76, 0.30, 4, True),
    "ic_leather":  (48, 0.080, 0.50, 0.30, 4, False),
    "ic_sky":      (230, 0.110, 0.68, 0.36, 4, True),
    "ic_orange":   (55, 0.150, 0.70, 0.34, 4, True),
    "ic_meat":     (30, 0.110, 0.52, 0.30, 4, False),
})

CLEAR = (0, 0, 0, 0)
OUTLINE = ramp("card_ink", 0)


def top(name):
    return size(name) - 1


# ====================================================================== shape layer helpers (32 x 32 icons)

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


def along(p0, p1, profile):
    """A polygon along p0 -> p1: profile = [(t, half width), ...] with t from 0 (p0) to 1 (p1)."""
    (x0, y0), (x1, y1) = p0, p1
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    nx, ny = -uy, ux
    left, right = [], []
    for t, hw in profile:
        cx, cy = x0 + dx * t, y0 + dy * t
        left.append((cx + nx * hw, cy + ny * hw))
        right.append((cx - nx * hw, cy - ny * hw))
    return left + right[::-1]


def rotate(points, degrees, centre=(16, 16)):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    cx, cy = centre
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in points]


def shift(points, dx, dy):
    return [(x + dx, y + dy) for x, y in points]


def scale_pts(points, f, centre=(16, 16)):
    cx, cy = centre
    return [(cx + (x - cx) * f, cy + (y - cy) * f) for x, y in points]


# ---------------------------------------------------------------------- motifs (each draws onto a Canvas)

DIAG_LO, DIAG_HI = (5, 27), (27, 5)


def handle(c, p0=(4, 28), p1=(12, 20), name="ic_wood"):
    c.poly(along(p0, p1, [(0, 1.6), (1, 1.6)]), name)


def sword(c, blade="ic_steel", guard="gold", grip="ic_wood", pommel=None, length=1.0):
    tip = (27 - (1 - length) * 12, 5 + (1 - length) * 12)
    handle(c, (5, 27), (11, 21), grip)
    c.poly(along((11, 21), tip, [(0, 2.6), (0.82, 2.4), (1, 0.2)]), blade)
    c.line([(6, 17), (15, 26)], guard, width=3)
    c.ellipse((3, 26, 7, 30), pommel or guard)


def axe(c, head="ic_steel", grip="ic_wood", double=False):
    c.poly(along((5, 28), (25, 8), [(0, 1.6), (1, 1.6)]), grip)
    blade = [(17, 6), (24, 3), (30, 9), (27, 16), (21, 13)]
    c.poly(blade, head)
    if double:
        c.poly([(15, 12), (8, 9), (6, 14), (11, 19), (18, 16)], head)


def pickaxe(c, head="ic_steel", grip="ic_wood"):
    c.poly(along((5, 28), (23, 10), [(0, 1.6), (1, 1.6)]), grip)
    arc = []
    for i in range(0, 181, 15):
        a = math.radians(200 + i * 0.95)
        arc.append((19 + 13 * math.cos(a) + 4, 13 + 13 * math.sin(a) + 4))
    inner = [(x * 0.82 + 23 * 0.18 + 1.5, y * 0.82 + 17 * 0.18 + 1.5) for x, y in arc]
    c.poly(arc + inner[::-1], head)


def mace(c, head="ic_dark", grip="ic_wood"):
    c.poly(along((5, 28), (19, 14), [(0, 1.6), (1, 1.6)]), grip)
    spikes = []
    for i in range(16):
        a = math.radians(i * 22.5)
        r = 11 if i % 2 == 0 else 7.5
        spikes.append((21 + r * math.cos(a), 11 + r * math.sin(a)))
    c.poly(spikes, head)
    c.ellipse((16, 6, 26, 16), "ic_steel")


def arrow(c, p0=(4, 28), p1=(27, 5), head="ic_steel", shaft="ic_wood", fletch="ic_white", width=1.3):
    c.poly(along(p0, p1, [(0, width), (0.78, width)]), shaft)
    c.poly(along(p0, p1, [(0.74, 3.6), (1, 0.2)]), head)
    c.poly(along(p0, p1, [(0, 3.4), (0.22, 3.4), (0.3, width)]), fletch)


def bow(c, string="ic_white", wood="ic_wood"):
    pts = []
    for i in range(0, 181, 12):
        a = math.radians(225 + i)
        pts.append((16 + 13 * math.cos(a) - 3, 16 + 13 * math.sin(a) + 3))
    inner = [(16 + (x - 13) * 0.82 - 0, 19 + (y - 19) * 0.82) for x, y in pts]
    c.poly(pts + inner[::-1], wood)
    c.line([pts[0], pts[-1]], string, width=1)


def heart(c, name="ic_red", box=(4, 5, 28, 28)):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    m = c.mask()
    d = ImageDraw.Draw(m)
    r = w / 4
    d.ellipse((x0, y0, x0 + 2 * r + 1, y0 + 2 * r + 1), fill=255)
    d.ellipse((x1 - 2 * r - 1, y0, x1, y0 + 2 * r + 1), fill=255)
    d.polygon([(x0 + 0.5, y0 + r + 1), (x1 - 0.5, y0 + r + 1), (x0 + w / 2, y1)], fill=255)
    c.fill(m, name)
    c.dots([(x0 + 3, y0 + 3), (x0 + 4, y0 + 3), (x0 + 3, y0 + 4)], ramp(name, top(name)))


def drop(c, name="ic_red", box=(9, 4, 23, 28)):
    x0, y0, x1, y1 = box
    cx = (x0 + x1) / 2
    r = (x1 - x0) / 2
    c.poly([(cx, y0), (x1 - 0.5, y1 - r - 1), (x0 + 0.5, y1 - r - 1)], name, bevel=False)
    c.ellipse((x0, y1 - 2 * r, x1, y1), name)
    c.dots([(round(x0 + r * 0.6), round(y1 - r * 1.2))], ramp(name, top(name)))


def flame(c, name="fire", box=(7, 3, 25, 29)):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    outer = [(x0 + w * 0.5, y0), (x0 + w * 0.78, y0 + h * 0.32), (x1, y0 + h * 0.62), (x0 + w * 0.82, y1),
             (x0 + w * 0.18, y1), (x0, y0 + h * 0.62), (x0 + w * 0.25, y0 + h * 0.38), (x0 + w * 0.38, y0 + h * 0.5)]
    c.poly(outer, name, mid=2, light=3, dark=1)
    inner = scale_pts(outer, 0.55, (x0 + w / 2, y1 - h * 0.2))
    c.poly(inner, name, mid=4, light=5, dark=4)


def feather(c, vane="ic_white", quill="ic_steel", tint=None):
    spine = [(6, 27), (25, 5)]
    c.poly(along(spine[0], spine[1], [(0.18, 0.5), (0.3, 4.2), (0.7, 4.8), (0.95, 1.5), (1, 0)]), tint or vane)
    c.line([(6, 27), (24, 6)], quill, width=1)
    for t in (0.42, 0.58, 0.74):
        x = 6 + 19 * t
        y = 27 - 22 * t
        c.dots([(round(x + 2), round(y + 2)), (round(x + 3), round(y + 3))], ramp(tint or vane, 0))


def eye(c, iris="ic_emerald", white="ic_white", box=(3, 9, 29, 23), pupil="ic_dark"):
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    pts = []
    for i in range(0, 361, 15):
        a = math.radians(i)
        pts.append((cx + (x1 - x0) / 2 * math.cos(a), cy + (y1 - y0) / 2 * math.sin(a) * abs(math.sin(a)) ** 0.3))
    c.poly(pts, white)
    r = (y1 - y0) * 0.42
    c.ellipse((cx - r, cy - r, cx + r, cy + r), iris)
    c.ellipse((cx - r * 0.45, cy - r * 0.45, cx + r * 0.45, cy + r * 0.45), pupil, bevel=False)
    c.dots([(round(cx - r * 0.5), round(cy - r * 0.55))], ramp("ic_white", 2))


def gem(c, name="ic_diamond", box=(6, 6, 26, 26)):
    x0, y0, x1, y1 = box
    cx = (x0 + x1) / 2
    w = x1 - x0
    crown = y0 + (y1 - y0) * 0.35
    c.poly([(x0 + w * 0.25, y0), (x1 - w * 0.25, y0), (x1, crown), (cx, y1), (x0, crown)], name)
    c.poly([(x0 + w * 0.25, y0), (x1 - w * 0.25, y0), (x1 - w * 0.12, crown), (x0 + w * 0.12, crown)], name,
           mid=top(name), light=top(name), dark=top(name) - 1)
    c.line([(cx, crown), (cx, y1 - 2)], name, width=1, mid=1, bevel=False)


def ingot(c, name="ic_steel", box=(4, 12, 28, 24)):
    x0, y0, x1, y1 = box
    c.poly([(x0 + 4, y0), (x1 - 4, y0), (x1, y1), (x0, y1)], name)
    c.poly([(x0 + 6, y0 + 1), (x1 - 6, y0 + 1), (x1 - 5, y0 + 3), (x0 + 5, y0 + 3)], name, mid=top(name),
           bevel=False)


def coin(c, name="gold", box=(5, 5, 27, 27)):
    x0, y0, x1, y1 = box
    c.ellipse(box, name, mid=1, light=2, dark=0)
    c.ellipse((x0 + 3, y0 + 3, x1 - 3, y1 - 3), name, mid=2, light=3, dark=1)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    c.rect((cx - 1, cy - 5, cx + 1, cy + 5), name, mid=1, bevel=False)
    c.rect((cx - 4, cy - 3, cx + 4, cy - 2), name, mid=1, bevel=False)
    c.rect((cx - 4, cy + 2, cx + 4, cy + 3), name, mid=1, bevel=False)


def boot(c, name="ic_leather", trim=None, box=(6, 4, 27, 28)):
    x0, y0, x1, y1 = box
    c.poly([(x0 + 3, y0), (x0 + 12, y0), (x0 + 12, y1 - 9), (x1, y1 - 6), (x1, y1), (x0, y1), (x0, y0 + 10)], name)
    if trim:
        c.rect((x0 + 2, y0, x0 + 13, y0 + 3), trim)
        c.rect((x0, y1 - 2, x1, y1), trim)


def wing(c, name="ic_white", flip=False, box=(2, 6, 30, 26)):
    x0, y0, x1, y1 = box
    pts = [(x0, y0 + 2), (x0 + 6, y0), (x1, y0 + 6), (x1 - 4, y0 + 10), (x1 - 2, y0 + 13), (x1 - 8, y0 + 16),
           (x1 - 7, y1), (x0 + 6, y1 - 4), (x0 + 3, y0 + 10)]
    if flip:
        pts = [(x0 + x1 - x, y) for x, y in pts]
    c.poly(pts, name)
    for i in range(3):
        y = y0 + 6 + i * 5
        xa, xb = (x0 + 7, x1 - 6 - i * 2)
        if flip:
            xa, xb = x0 + x1 - xa, x0 + x1 - xb
        c.line([(xa, y), (xb, y + 2)], name, width=1, mid=0, bevel=False)


def chevrons(c, name="ic_sky", count=2, box=(9, 4, 23, 26)):
    x0, y0, x1, y1 = box
    step = (y1 - y0) / (count + 0.6)
    for i in range(count):
        y = y0 + i * step
        c.poly([(x0, y + 7), ((x0 + x1) / 2, y), (x1, y + 7), (x1, y + 11), ((x0 + x1) / 2, y + 4), (x0, y + 11)],
               name)


def swirl(c, name="ic_sky", centre=(16, 16), r=12, turns=1.6, width=2):
    pts = []
    for i in range(48):
        t = i / 47
        a = t * turns * 2 * math.pi
        rr = r * (1 - t * 0.75)
        pts.append((centre[0] + rr * math.cos(a), centre[1] + rr * math.sin(a)))
    c.line(pts, name, width=width)


def speed_lines(c, name="ic_white", lines=((2, 10, 9), (1, 16, 8), (3, 22, 7))):
    for x, y, w in lines:
        c.rect((x, y, x + w, y + 1), name, bevel=False)


def sparkle(c, x, y, name="ic_white", r=3):
    c.rect((x, y - r, x, y + r), name, mid=top(name), bevel=False)
    c.rect((x - r, y, x + r, y), name, mid=top(name), bevel=False)
    c.dots([(x, y)], ramp("ic_white", 2))


def snowflake(c, cx, cy, r=5, name="ic_ice"):
    for a in (0, 60, 120):
        pts = rotate([(cx - r, cy), (cx + r, cy)], a, (cx, cy))
        c.line(pts, name, width=1, mid=top(name), bevel=False)


def skull(c, name="ic_white", box=(7, 5, 25, 27)):
    x0, y0, x1, y1 = box
    c.ellipse((x0, y0, x1, y1 - 5), name)
    c.rect((x0 + 4, y1 - 8, x1 - 4, y1), name)
    w = x1 - x0
    c.ellipse((x0 + 3, y0 + 7, x0 + 3 + w * 0.28, y0 + 7 + w * 0.28), "ic_dark", bevel=False)
    c.ellipse((x1 - 3 - w * 0.28, y0 + 7, x1 - 3, y0 + 7 + w * 0.28), "ic_dark", bevel=False)
    c.rect((x0 + w / 2 - 1, y1 - 9, x0 + w / 2, y1 - 7), "ic_dark", bevel=False)


def shield_shape(c, name="ic_wood", rim="ic_steel", box=(6, 3, 26, 29), boss="ic_steel"):
    x0, y0, x1, y1 = box
    cx = (x0 + x1) / 2
    pts = [(x0, y0 + 2), (cx, y0), (x1, y0 + 2), (x1, y0 + (y1 - y0) * 0.55), (cx, y1), (x0, y0 + (y1 - y0) * 0.55)]
    c.poly(pts, rim)
    c.poly(scale_pts(pts, 0.78, (cx, (y0 + y1) / 2)), name)
    if boss:
        c.ellipse((cx - 3, y0 + 9, cx + 3, y0 + 15), boss)


def chestplate(c, name="ic_dark", trim="gold"):
    c.poly([(5, 6), (11, 4), (16, 8), (21, 4), (27, 6), (28, 15), (24, 15), (24, 28), (8, 28), (8, 15), (4, 15)],
           name)
    c.line([(16, 9), (16, 27)], name, width=1, mid=0, bevel=False)
    c.rect((9, 24, 23, 26), trim, bevel=False)


def clock(c, rim="gold", face="ic_white", box=(4, 4, 28, 28)):
    x0, y0, x1, y1 = box
    c.ellipse(box, rim)
    c.ellipse((x0 + 3, y0 + 3, x1 - 3, y1 - 3), face)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    c.line([(cx, cy), (cx, y0 + 6)], "ic_dark", width=2, bevel=False)
    c.line([(cx, cy), (cx - 5, cy + 3)], "ic_dark", width=2, bevel=False)


def curved_arrow(c, name="ic_sky", centre=(16, 16), r=11, start=200, end=470, head=True):
    pts = []
    for i in range(30):
        a = math.radians(start + (end - start) * i / 29)
        pts.append((centre[0] + r * math.cos(a), centre[1] + r * math.sin(a)))
    c.line(pts, name, width=3)
    if head:
        a = math.radians(end)
        tx, ty = centre[0] + r * math.cos(a), centre[1] + r * math.sin(a)
        d = math.radians(end + 90)
        dx, dy = math.cos(d), math.sin(d)
        nx, ny = -dy, dx
        c.poly([(tx + dx * 6, ty + dy * 6), (tx + nx * 5, ty + ny * 5), (tx - nx * 5, ty - ny * 5)], name)


def bottle(c, liquid="ic_red", glass="ic_ice", box=(8, 3, 24, 29)):
    x0, y0, x1, y1 = box
    cx = (x0 + x1) / 2
    c.rect((cx - 3, y0, cx + 3, y0 + 8), glass)
    c.ellipse((x0, y0 + 9, x1, y1), glass)
    c.ellipse((x0 + 2, y0 + 14, x1 - 2, y1 - 2), liquid)
    c.rect((cx - 3, y0, cx + 3, y0 + 2), "ic_wood")


def book(c, cover="ic_violet", pages="ic_white"):
    c.poly([(5, 8), (16, 5), (27, 8), (27, 26), (16, 29), (5, 26)], cover)
    c.poly([(7, 9), (15, 7), (15, 25), (7, 24)], pages, bevel=False)
    c.poly([(17, 7), (25, 9), (25, 24), (17, 25)], pages)
    for y in (12, 15, 18):
        c.line([(9, y), (13, y - 1)], "ic_dark", width=1, mid=1, bevel=False)
        c.line([(19, y - 1), (23, y)], "ic_dark", width=1, mid=1, bevel=False)


def sack(c, name="ic_leather", tie="gold"):
    c.ellipse((4, 10, 28, 30), name)
    c.poly([(11, 4), (21, 4), (18, 11), (14, 11)], name)
    c.rect((12, 10, 20, 12), tie)


def compass(c, rim="ic_steel", face="ic_white"):
    c.ellipse((3, 3, 29, 29), rim)
    c.ellipse((6, 6, 26, 26), face)
    c.poly([(16, 7), (19, 16), (16, 25), (13, 16)], "ic_dark", bevel=False)
    c.poly([(16, 7), (19, 16), (13, 16)], "ic_red", bevel=False)


def web(c, name="ic_white"):
    for a in range(0, 180, 45):
        pts = rotate([(2, 16), (30, 16)], a)
        c.line(pts, name, width=1, mid=top(name), bevel=False)
    for r in (5, 9, 13):
        pts = []
        for i in range(0, 361, 45):
            a = math.radians(i + 22.5)
            pts.append((16 + r * math.cos(a), 16 + r * math.sin(a)))
        c.line(pts, name, width=1, mid=top(name) - 1, bevel=False)


def paw(c, name="ic_white"):
    c.ellipse((9, 14, 23, 27), name)
    for x, y in ((5, 9), (11, 4), (18, 4), (24, 9)):
        c.ellipse((x - 1, y, x + 5, y + 7), name)


def footprint(c, x, y, name="ic_white", flip=False):
    c.ellipse((x, y, x + 6, y + 9), name)
    c.ellipse((x + 1, y + 10, x + 5, y + 13), name)


def dice(c, name="ic_white", pips="ic_dark"):
    c.poly([(5, 9), (16, 4), (27, 9), (27, 22), (16, 28), (5, 22)], name)
    c.poly([(5, 9), (16, 14), (16, 28), (5, 22)], name, mid=1)
    c.poly([(16, 14), (27, 9), (27, 22), (16, 28)], name, mid=0, light=1)
    c.dots([(16, 8), (10, 14), (11, 21), (21, 13), (23, 19), (19, 22), (21, 17)], ramp(pips, 1))


def anvil(c, name="ic_dark"):
    c.poly([(3, 9), (27, 9), (29, 12), (21, 15), (19, 20), (24, 22), (24, 27), (8, 27), (8, 22), (13, 20), (11, 15),
            (6, 13)], name)
    c.rect((5, 9, 26, 10), name, mid=top(name), bevel=False)


def hammer(c, head="ic_steel", grip="ic_wood"):
    c.poly(along((8, 29), (20, 11), [(0, 1.5), (1, 1.5)]), grip)
    c.poly(rotate([(12, 3), (28, 3), (28, 10), (12, 10)], 30, (20, 7)), head)


def furnace(c):
    c.rect((5, 5, 27, 27), "ic_steel", mid=1)
    c.rect((8, 16, 24, 25), "ic_dark")
    c.rect((7, 8, 25, 10), "ic_steel", mid=2, bevel=False)
    flame(c, "fire", (10, 15, 22, 25))


def tree(c):
    c.rect((14, 18, 18, 29), "ic_wood")
    c.ellipse((5, 3, 27, 22), "ic_green")


def cloak(c, name="ic_dark"):
    c.poly([(16, 3), (24, 9), (28, 29), (4, 29), (8, 9)], name)
    c.ellipse((10, 8, 22, 20), "ic_dark", mid=0, light=0, dark=0, bevel=False)
    c.dots([(13, 14), (18, 14)], ramp("ic_ice", 3))


def ghost(c, name="ic_white"):
    c.ellipse((6, 3, 26, 22), name)
    c.poly([(6, 13), (26, 13), (26, 28), (22, 25), (19, 29), (16, 25), (13, 29), (10, 25), (6, 28)], name)
    c.ellipse((10, 10, 14, 15), "ic_dark", bevel=False)
    c.ellipse((18, 10, 22, 15), "ic_dark", bevel=False)


def fish(c, name="ic_orange", fin="ic_white"):
    c.ellipse((4, 9, 24, 24), name)
    c.poly([(22, 16), (30, 9), (30, 24)], name)
    c.rect((11, 9, 13, 24), fin, bevel=False)
    c.dots([(9, 14)], ramp("ic_dark", 0))


def wave(c, name="ic_sky", y=24):
    pts = [(x, y + 2 * math.sin(x / 2.2)) for x in range(2, 31)]
    c.line(pts, name, width=2)


def spring(c, name="ic_slime"):
    c.rect((5, 15, 21, 30), name)
    c.rect((9, 19, 17, 26), name, mid=top(name), bevel=False)
    chevrons(c, "ic_sky", 2, (19, 1, 31, 18))


def bag_of_cards(c):
    sack(c, "ic_violet", "gold")
    for i, colour in enumerate(("card_silver", "card_gold", "prism_violet")):
        c.poly(rotate([(13, 1), (19, 1), (19, 9), (13, 9)], -25 + i * 25, (16, 9)), colour)


def orb(c, name="ic_slime", box=(9, 9, 23, 23)):
    c.ellipse(box, name)
    x0, y0 = box[0], box[1]
    c.dots([(x0 + 3, y0 + 3), (x0 + 4, y0 + 3)], ramp("ic_white", 2))


def plus(c, x, y, name="ic_green", r=4, w=1):
    c.rect((x - w, y - r, x + w, y + r), name, mid=top(name) - 1)
    c.rect((x - r, y - w, x + r, y + w), name, mid=top(name) - 1)


def explosion(c, cx=21, cy=11, r=10, name="fire"):
    pts = []
    for i in range(16):
        a = math.radians(i * 22.5 + 10)
        rr = r if i % 2 == 0 else r * 0.5
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    c.poly(pts, name, mid=3, light=5, dark=2)


def bolt(c, name="ic_sky"):
    c.poly([(18, 2), (8, 17), (15, 17), (12, 30), (25, 12), (17, 12), (21, 2)], name)


def crown(c, name="gold"):
    c.poly([(5, 25), (5, 10), (11, 17), (16, 7), (21, 17), (27, 10), (27, 25)], name)
    c.dots([(16, 18), (10, 21), (22, 21)], ramp("ic_red", 2))


def cactus(c):
    c.rect((12, 4, 20, 29), "ic_green")
    c.rect((5, 12, 10, 20), "ic_green")
    c.rect((22, 9, 27, 17), "ic_green")
    for x, y in ((11, 8), (21, 13), (11, 22), (21, 25), (4, 14), (28, 11)):
        c.dots([(x, y)], ramp("ic_white", 2))


def giant(c, name="ic_steel"):
    c.ellipse((11, 2, 21, 12), name)
    c.poly([(7, 13), (25, 13), (27, 24), (22, 24), (21, 30), (11, 30), (10, 24), (5, 24)], name)
    c.dots([(14, 7), (18, 7)], ramp("ic_red", 2))


def owl(c):
    c.poly([(5, 6), (11, 9), (21, 9), (27, 6), (27, 27), (5, 27)], "ic_leather")
    c.ellipse((7, 10, 16, 19), "gold")
    c.ellipse((16, 10, 25, 19), "gold")
    c.ellipse((10, 13, 13, 16), "ic_dark", bevel=False)
    c.ellipse((19, 13, 22, 16), "ic_dark", bevel=False)
    c.poly([(15, 18), (17, 18), (16, 22)], "ic_orange", bevel=False)


def meat(c):
    c.ellipse((8, 4, 28, 22), "ic_meat")
    c.poly(along((7, 27), (14, 18), [(0, 1.8), (1, 1.8)]), "ic_white")
    c.ellipse((3, 24, 8, 29), "ic_white")
    c.ellipse((6, 26, 11, 31), "ic_white")
    c.dots([(14, 9), (15, 9), (19, 7)], ramp("ic_meat", 3))


def apple(c, name="gold"):
    c.ellipse((5, 8, 27, 29), name)
    c.rect((15, 3, 16, 9), "ic_wood", bevel=False)
    c.poly([(17, 6), (24, 2), (22, 8)], "ic_green")


def scope(c):
    c.ellipse((4, 4, 28, 28), "ic_red", mid=2)
    c.ellipse((7, 7, 25, 25), "ic_dark", mid=0, bevel=False)
    c.ellipse((8, 8, 24, 24), "ic_sky", mid=0, bevel=False)
    c.rect((15, 4, 16, 28), "ic_red", mid=2, bevel=False)
    c.rect((4, 15, 28, 16), "ic_red", mid=2, bevel=False)
    c.ellipse((14, 14, 17, 17), "ic_red", mid=3, bevel=False)


def chest(c):
    c.rect((4, 10, 28, 27), "ic_wood")
    c.rect((4, 10, 28, 15), "ic_wood", mid=2)
    c.rect((14, 13, 18, 19), "gold")


def hood_eye(c):
    eye(c, "gold", "ic_white", (5, 10, 27, 22))
    for a in range(-60, 241, 50):
        x, y = 16 + 14 * math.cos(math.radians(a)), 16 - 14 * math.sin(math.radians(a))
        c.dots([(round(x), round(y))], ramp("gold", 3))


# ---------------------------------------------------------------------- kind badges (bottom-right, 11 x 11)

KIND_COLOUR = {
    "COMBAT": "ic_red", "RESOURCE": "ic_emerald", "MOBILITY": "ic_sky", "STAT": "ic_orange",
    "TRADEOFF": "ic_violet", "SPECIAL": "gold",
}
KIND_MARK = {  # 7 x 7 white marks
    "COMBAT": ["X.....X", ".X...X.", "..X.X..", "...X...", "..X.X..", ".X...X.", "X.....X"],
    "RESOURCE": ["...X...", "..XXX..", ".XXXXX.", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."],
    "MOBILITY": ["...X...", "..XXX..", ".XX.XX.", "XX...XX", "...X...", "..XXX..", ".XX.XX."],
    "STAT": ["..XXX..", "..XXX..", "XXXXXXX", "XXXXXXX", "XXXXXXX", "..XXX..", "..XXX.."],
    "TRADEOFF": ["...X...", "...X...", ".XXXXX.", "...X...", "...X...", ".......", ".XXXXX."],
    "SPECIAL": ["...X...", "..XXX..", "XXXXXXX", ".XXXXX.", "..XXX..", ".XX.XX.", "X.....X"],
}


def kind_badge(c, kind):
    name = KIND_COLOUR[kind]
    c.ellipse((20, 20, 31, 31), name, mid=top(name) - 1)
    white = ramp("ic_white", 2)
    for y, row in enumerate(KIND_MARK[kind]):
        for x, ch in enumerate(row):
            if ch == "X":
                c.dots([(22 + x, 22 + y)], white)


# ---------------------------------------------------------------------- one recipe per augment

ICONS = {
    "auto_smelt": lambda c: (furnace(c), ingot(c, "ic_steel", (14, 2, 30, 10))),
    "beast_friend": lambda c: (paw(c, "ic_white"), heart(c, "ic_red", (19, 1, 30, 11))),
    "berserker_pact": lambda c: (axe(c, "ic_dark", "ic_wood", double=True), drop(c, "ic_red", (2, 2, 10, 13))),
    "blast_arrow": lambda c: (arrow(c), explosion(c, 22, 10, 9)),
    "blink": lambda c: (eye(c, "ic_emerald", "ic_white", (2, 9, 24, 23)), sparkle(c, 26, 7, "ic_violet"),
                        sparkle(c, 6, 25, "ic_violet", 2)),
    "bloodthirst": lambda c: (drop(c, "ic_red", (7, 2, 25, 28)), c.poly([(12, 12), (15, 12), (13.5, 19)], "ic_white",
                                                                         bevel=False)),
    "clairvoyance": hood_eye,  # a gold-rayed eye (blink is the green ender eye)
    "cobweb_trap": web,
    "collector": bag_of_cards,
    "combo": lambda c: (sword(c, "gold", "ic_steel"), [c.line([(1 + 4 * i, 9), (5 + 4 * i, 1)], "ic_white", width=1,
                                                              bevel=False) for i in range(3)]),
    "diamond_vein": lambda c: (c.rect((2, 14, 30, 30), "ic_steel", mid=1), gem(c, "ic_diamond", (4, 2, 18, 18)),
                               gem(c, "ic_diamond", (16, 8, 28, 22))),
    "double_jump": lambda c: (boot(c, "ic_leather", None, (2, 10, 19, 30)), chevrons(c, "ic_sky", 2, (18, 1, 30, 22))),
    "earth_blessing": lambda c: (pickaxe(c, "ic_diamond"), c.poly([(2, 3), (9, 2), (11, 8), (5, 11)], "ic_green")),
    "enchant_kit": lambda c: (book(c, "ic_violet"), sparkle(c, 27, 4, "ic_violet")),
    "executioner": lambda c: (axe(c, "gold"), skull(c, "ic_white", (1, 15, 14, 30))),
    "feast": meat,
    "feather_fall": lambda c: feather(c),
    "fire_blade": lambda c: (sword(c, "fire", "ic_dark"), flame(c, "fire", (19, 1, 31, 15))),
    "footsteps": lambda c: (footprint(c, 5, 15), footprint(c, 14, 5), c.line([(23, 18), (27, 22), (23, 26)],
                                                                            "ic_sky", width=1, bevel=False)),
    "fortress": lambda c: chestplate(c, "ic_dark", "gold"),
    "frost_arrow": lambda c: (arrow(c, head="ic_ice", fletch="ic_ice"), snowflake(c, 23, 9, 6)),
    "gale": lambda c: (swirl(c, "ic_sky", (17, 15), 12), speed_lines(c)),
    "gambler": dice,
    "giant": lambda c: (giant(c), chevrons(c, "ic_red", 1, (22, 2, 31, 12))),
    "heavy_hitter": mace,
    "high_jump": spring,
    "hunter_compass": compass,
    "iron_body": lambda c: (heart(c, "ic_steel"), plus(c, 25, 7, "ic_white")),
    "iron_bundle": lambda c: (sack(c, "ic_leather"), c.ellipse((10, 2, 18, 9), "ic_meat"),
                              c.ellipse((16, 3, 23, 9), "ic_steel")),
    "lucky_coin": coin,
    "lucky_pick": lambda c: (pickaxe(c, "ic_steel"), gem(c, "ic_emerald", (1, 18, 12, 29))),
    "lumberjack": lambda c: (tree(c), axe(c, "ic_steel")),
    "midas_pick": lambda c: (pickaxe(c, "gold"), sparkle(c, 7, 8, "gold"), sparkle(c, 27, 25, "gold", 2)),
    "miner_touch": lambda c: (pickaxe(c, "ic_steel"), speed_lines(c, "ic_white", ((1, 24, 6), (3, 28, 5)))),
    "netherite_scrap": lambda c: (ingot(c, "ic_dark", (3, 13, 29, 26)), sparkle(c, 24, 8, "prism_violet")),
    "night_eyes": owl,
    "pearls": lambda c: (orb(c, "ic_emerald", (4, 6, 22, 24)), orb(c, "ic_emerald", (16, 14, 29, 27))),
    "phoenix_feather": lambda c: (feather(c, "fire", "gold", tint="fire"), sparkle(c, 25, 24, "gold")),
    "potion_bag": lambda c: (bottle(c, "ic_red", "ic_ice", (2, 6, 16, 30)), bottle(c, "ic_sky", "ic_ice",
                                                                                   (15, 2, 29, 26))),
    "quiver": lambda c: (arrow(c, (3, 29), (23, 3)), arrow(c, (9, 29), (29, 5))),
    "rations": apple,
    "regen_heart": lambda c: (heart(c, "ic_red"), plus(c, 24, 8, "ic_green", 5, 1)),
    "rewind": lambda c: (clock(c), curved_arrow(c, "ic_sky", (16, 16), 14, 330, 130)),
    "shadow_strike": lambda c: (c.ellipse((1, 1, 13, 13), "ic_violet", mid=3),
                                c.ellipse((5, -1, 15, 10), "ic_violet", mid=0, light=0, dark=0, bevel=False),
                                sword(c, "ic_violet", "ic_dark", "ic_dark")),
    "sharp_edge": lambda c: (sword(c), sparkle(c, 24, 9, "ic_white")),
    "shield_master": lambda c: shield_shape(c),
    "smith_blessing": lambda c: (anvil(c), hammer(c)),
    "sniper": scope,
    "sprinter": lambda c: (boot(c, "gold", "ic_steel", (8, 4, 29, 28)), speed_lines(c)),
    "still_cloak": cloak,
    "swimmer": lambda c: (fish(c), wave(c, "ic_sky", 27)),
    "thick_skin": lambda c: (c.poly([(6, 4), (26, 6), (29, 26), (16, 29), (3, 24)], "ic_leather"),
                             plus(c, 16, 16, "ic_white", 5, 1)),
    "thorns": cactus,
    "treasure_sense": lambda c: (chest(c), sparkle(c, 6, 5, "gold"), sparkle(c, 26, 4, "gold", 2)),
    "triple_shot": lambda c: (arrow(c, (4, 28), (16, 2)), arrow(c, (4, 28), (27, 5)), arrow(c, (4, 28), (30, 17))),
    "vengeful": ghost,
    "venom": lambda c: (drop(c, "ic_green", (8, 3, 24, 29)), skull(c, "ic_white", (11, 15, 21, 27))),
    "war_god": lambda c: (sword(c, "ic_dark", "gold", "ic_dark"),
                          c.poly([(2, 10), (2, 2), (5, 6), (7, 1), (9, 6), (12, 2), (12, 10)], "gold")),
    "wings": lambda c: (wing(c, "ic_white", False, (1, 5, 17, 26)), wing(c, "ic_white", True, (15, 5, 31, 26))),
    "xp_spring": lambda c: (bottle(c, "ic_slime", "ic_ice"), orb(c, "ic_slime", (20, 2, 29, 11)),
                            orb(c, "ic_slime", (2, 4, 9, 11))),
}


def icon(augment_id, kind):
    c = Canvas()
    ICONS[augment_id](c)
    kind_badge(c, kind)
    c.outline()
    return c.im


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
    """An inline key cap for the hint line (11 x 9, sits on the text line)."""
    w = 11 if label != "^" else 9
    im = Image.new("RGBA", (w, 9), CLEAR)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, w - 1, 8), 2, fill=ramp("card_ink", 0))
    d.rounded_rectangle((1, 0, w - 2, 7), 1, fill=ramp("card_silver", 3))
    _glyph_text(im.load(), (w - 3) // 2, 1, FONT_3X5[label], ramp("card_ink", 0))
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
