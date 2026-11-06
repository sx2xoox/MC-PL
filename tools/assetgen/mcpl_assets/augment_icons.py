"""The 증강 card icons: one illustrated composition per augment, painted with `icon_kit` (vector shapes on a
supersampled canvas, lit from the top-left, reduced to 72 x 72 pixel art with cel shading, selective outlines and
manual anti-aliasing). `augment_cards.py` turns them into font glyphs (shown 36 font units tall in the card medallion).

Each recipe draws one readable symbol of what the augment does (이단 점프 = a winged boot, 피의 갈증 = a goblet
running over with blood, ...), in Minecraft's world of objects. Coordinates are design units, 0..100 across the icon;
keep the art inside the circle of radius ~46 round the centre (the medallion ring is outside it).
"""

import math

from .icon_kit import Icon, arc, bez, mirror, move, rot, scale, star_pts, taper


# ====================================================================== shared objects

def blade_sword(ic, base, tip, width=12, blade="steel", guard="gold", grip="leather", pommel="gold", fuller=True,
                guard_w=30, grip_len=14):
    """A sword from its guard centre `base` to its `tip`, the grip continuing behind the guard."""
    (bx, by), (tx, ty) = base, tip
    L = math.hypot(tx - bx, ty - by)
    ux, uy = (tx - bx) / L, (ty - by) / L
    nx, ny = -uy, ux
    path = [(bx + ux * L * t, by + uy * L * t) for t in [i / 20 for i in range(21)]]
    b = ic.taper(path, lambda t: width * (1 - max(0, t - 0.72) / 0.28 * 0.92) if t < 1 else 0.6)
    ic.paint(b, blade, round=width * 0.35)
    if fuller:
        ic.detail(ic.line([(bx + ux * L * 0.08, by + uy * L * 0.08), (bx + ux * L * 0.7, by + uy * L * 0.7)],
                          width * 0.18), blade, 5)
    if guard:
        g0 = (bx + nx * guard_w / 2, by + ny * guard_w / 2)
        g1 = (bx - nx * guard_w / 2, by - ny * guard_w / 2)
        mid = (bx - ux * 3, by - uy * 3)
        ic.paint(ic.taper(bez(g0, mid, g1, n=16), lambda t: width * 0.55 * (0.7 + 0.6 * (1 - abs(t - 0.5) * 2))),
                 guard, round=3)
    if grip:
        e = (bx - ux * grip_len, by - uy * grip_len)
        ic.paint(ic.line([(bx - ux * 2, by - uy * 2), e], width * 0.5), grip, round=2.5)
        for k in (0.35, 0.7):
            p = (bx - ux * grip_len * k, by - uy * grip_len * k)
            ic.detail(ic.line([(p[0] + nx * width * 0.25, p[1] + ny * width * 0.25),
                               (p[0] - nx * width * 0.25, p[1] - ny * width * 0.25)], 1.3), grip, 1)
        if pommel:
            ic.paint(ic.ellipse(e[0] - ux * 3, e[1] - uy * 3, width * 0.42), pommel, round=3)
    return ux, uy


def handle(ic, p0, p1, w=6.5, name="wood"):
    ic.paint(ic.line([p0, p1], w), name, round=2.5)
    ux, uy = p1[0] - p0[0], p1[1] - p0[1]
    L = math.hypot(ux, uy)
    nx, ny = -uy / L, ux / L
    for t in (0.22, 0.5, 0.78):   # grain knots
        p = (p0[0] + ux * t, p0[1] + uy * t)
        ic.detail(ic.line([(p[0] + nx * w * 0.2, p[1] + ny * w * 0.2), (p[0] + ux / L * 4 + nx * w * 0.2,
                                                                         p[1] + uy / L * 4 + ny * w * 0.2)], 1.0),
                  name, 1)


def pickaxe(ic, head="iron", grip="wood", c=(50, 50), size=1.0, angle=0):
    """A pickaxe: the handle from bottom-left to the head at the top right, the head a curved crescent."""
    def T(pts):
        return rot(scale(pts, size, (50, 50)), angle, (50, 50)) if angle else scale(pts, size, (50, 50))
    hp = T([(20, 86), (68, 36)])
    hp = move(hp, c[0] - 50, c[1] - 50)
    handle(ic, hp[0], hp[1], 7 * size, grip)
    crest = T(bez((26, 34), (52, 6), (90, 66), n=30))
    crest = move(crest, c[0] - 50, c[1] - 50)
    ic.paint(ic.taper(crest, lambda t: (3 + 10 * math.sin(math.pi * t) ** 0.8) * size), head, round=4)
    # binding where the head sits on the handle
    m = move(T([(64, 40)]), c[0] - 50, c[1] - 50)[0]
    ic.paint(ic.ellipse(m[0], m[1], 5.5 * size), "leather", round=2.5)


def axe_head(ic, at, angle=0, size=1.0, name="iron", double=False):
    """An axe head on the handle point `at`: a socket round the handle and a bearded blade flaring out to a curved
    edge on the right (rotate with `angle`; `double` mirrors a second bit to the left)."""
    bit = [(4, -7)] + bez((4, -7), (14, -8), (20, -16), n=6) + bez((20, -16), (32, -12), (34, 4), (24, 20), n=16) \
        + bez((24, 20), (18, 10), (12, 6), (4, 6), n=8)
    shapes = [bit] + ([[(-x, y) for x, y in bit]] if double else [])
    sock = [(-5, -9), (5, -9), (5, 8), (-5, 8)]
    for pts in shapes:
        ic.paint(ic.poly(rot([(at[0] + x * size, at[1] + y * size) for x, y in pts], angle, at)), name, round=5)
        sx = 1 if pts is bit else -1
        edge = rot([(at[0] + sx * x * size, at[1] + y * size) for x, y in bez((22, -13), (31, -6), (31, 6), (22, 17),
                                                                                n=10)], angle, at)
        ic.detail(ic.line(edge, 2.6 * size), name, 5)
    ic.paint(ic.poly(rot([(at[0] + x * size, at[1] + y * size) for x, y in sock], angle, at)), "dark", round=2)


def heart_pts(cx=50, cy=52, w=80, h=70):
    pts = bez((0.5, 1), (0.02, 0.62), (-0.04, 0.12), (0.26, 0.0), (0.5, 0.2), n=30) + \
        bez((0.5, 0.2), (0.74, 0.0), (1.04, 0.12), (0.98, 0.62), (0.5, 1), n=30)
    return [(cx - w / 2 + x * w, cy - h / 2 + y * h) for x, y in pts]


def heart(ic, cx=50, cy=52, w=80, h=70, name="red", shine=True):
    m = ic.poly(heart_pts(cx, cy, w, h))
    ic.paint(m, name, round=w * 0.22)
    if shine:
        ic.shine(ic.poly(rot([(cx - w * 0.30, cy - h * 0.22), (cx - w * 0.18, cy - h * 0.32),
                              (cx - w * 0.12, cy - h * 0.26), (cx - w * 0.25, cy - h * 0.12)], 0)))
    return m


def drop_pts(cx, cy, w, h):
    return bez((cx, cy - h / 2), (cx + w * 0.15, cy - h * 0.15), (cx + w * 0.62, cy + h * 0.12),
               (cx + w * 0.5, cy + h * 0.5), (cx, cy + h / 2), n=24) + \
        bez((cx, cy + h / 2), (cx - w * 0.5, cy + h * 0.5), (cx - w * 0.62, cy + h * 0.12),
            (cx - w * 0.15, cy - h * 0.15), (cx, cy - h / 2), n=24)


def flame_pts(cx, by, w, h, lean=0.0, tongues=3):
    """A flame standing on (cx, by): a body with tongues licking up."""
    pts = []
    pts += bez((cx - w * 0.5, by - h * 0.25), (cx - w * 0.55, by + h * 0.02), (cx + w * 0.55, by + h * 0.02),
               (cx + w * 0.5, by - h * 0.25), n=18)
    pts += bez((cx + w * 0.5, by - h * 0.25), (cx + w * 0.45, by - h * 0.55), (cx + w * (0.15 + lean), by - h * 0.6),
               (cx + w * (0.2 + lean), by - h * 0.78), n=12)
    pts += bez((cx + w * (0.2 + lean), by - h * 0.78), (cx + w * 0.05, by - h * 0.7), (cx + w * (0.1 + lean * 1.5),
                                                                                        by - h * 0.9),
               (cx + w * lean * 1.6, by - h), n=10)
    pts += bez((cx + w * lean * 1.6, by - h), (cx - w * 0.2, by - h * 0.75), (cx - w * 0.05, by - h * 0.62),
               (cx - w * (0.25 - lean), by - h * 0.66), n=10)
    pts += bez((cx - w * (0.25 - lean), by - h * 0.66), (cx - w * 0.3, by - h * 0.55), (cx - w * 0.55,
                                                                                         by - h * 0.45),
               (cx - w * 0.5, by - h * 0.25), n=10)
    return pts


def flame(ic, cx, by, w, h, lean=0.0, name="fire"):
    ic.emit(ic.poly(flame_pts(cx, by, w, h, lean)), name, round=w * 0.12)
    ic.emit(ic.poly(flame_pts(cx + w * lean * 0.3, by - h * 0.04, w * 0.5, h * 0.55, lean * 0.6)), name,
            round=w * 0.05, bias=0.22, glow=False)


def feather(ic, base, tip, width=22, vane="white", quill="bone", curve=8, notches=True, emit=False):
    (bx, by), (tx, ty) = base, tip
    L = math.hypot(tx - bx, ty - by)
    nx, ny = -(ty - by) / L, (tx - bx) / L
    mid = ((bx + tx) / 2 + nx * curve, (by + ty) / 2 + ny * curve)
    spine = bez((bx, by), mid, (tx, ty), n=30)
    start = 7
    vpath = spine[start:]
    vmask = ic.taper(vpath, lambda t: width * (math.sin(math.pi * min(1, t * 1.15)) ** 0.7 * (1 - 0.25 * t) + 0.04))
    # notches cut into the vane
    if notches:
        for t, side, d in ((0.42, 1, 9), (0.7, -1, 7)):
            p = vpath[int(t * (len(vpath) - 1))]
            q = (p[0] + nx * side * width * 0.6 + (bx - tx) / L * d, p[1] + ny * side * width * 0.6 + (by - ty) / L * d)
            vmask &= ~ic.poly([p, (q[0] + (tx - bx) / L * 5, q[1] + (ty - by) / L * 5), q])
    if emit:
        ic.emit(vmask, vane, round=width * 0.2)
    else:
        ic.paint(vmask, vane, round=width * 0.3)
    # barbs: a few darker diagonal lines on the vane
    for i in range(3, len(vpath) - 3, 4):
        p = vpath[i]
        for side in (1, -1):
            q = (p[0] + nx * side * width * 0.38 + (bx - tx) / L * 4, p[1] + ny * side * width * 0.38 + (by - ty) / L * 4)
            ic.detail(ic.line([p, q], 1.0) & vmask, vane, 2 if side < 0 else 3)
    ic.paint(ic.taper(spine, lambda t: 2.8 * (1 - t) + 0.8), quill, round=1.5, cut=False)


def arrow(ic, tail, tip, head="iron", shaft="wood", fletch="white", w=4.0, head_size=1.0):
    (ax, ay), (bx, by) = tail, tip
    L = math.hypot(bx - ax, by - ay)
    ux, uy = (bx - ax) / L, (by - ay) / L
    nx, ny = -uy, ux
    hs = head_size
    hb = (bx - ux * 11 * hs, by - uy * 11 * hs)
    ic.paint(ic.line([(ax + ux * 2, ay + uy * 2), hb], w), shaft, round=1.5)
    # fletching: two feathers
    for side in (1, -1):
        pts = [(ax + ux * 14, ay + uy * 14), (ax + ux * 4 + nx * side * 7, ay + uy * 4 + ny * side * 7),
               (ax - ux * 3 + nx * side * 7, ay - uy * 3 + ny * side * 7), (ax, ay)]
        ic.paint(ic.poly(pts), fletch, round=2.5)
    ic.paint(ic.poly([(bx, by), (hb[0] + nx * 7 * hs, hb[1] + ny * 7 * hs), (hb[0] + ux * 2.5 * hs, hb[1] + uy * 2.5 * hs),
                      (hb[0] - nx * 7 * hs, hb[1] - ny * 7 * hs)]), head, round=2.5)


def boot(ic, ox=0, oy=0, s=1.0, name="leather", sole="deepslate", cuff="leather"):
    def T(pts):
        return [(ox + 50 + (x - 50) * s, oy + 50 + (y - 50) * s) for x, y in pts]
    body = T(bez((30, 16), (28, 40), (30, 56), (26, 64), n=10) + bez((26, 64), (20, 72), (22, 80), n=6)
             + [(70, 80)] + bez((70, 80), (80, 79), (82, 70), (66, 64), n=10) + bez((66, 64), (56, 60), (54, 52), n=6)
             + [(56, 16)])
    ic.paint(ic.poly(body), name, round=9)
    ic.paint(ic.poly(T([(21, 77), (72, 77)] + bez((72, 77), (84, 76), (83, 84), n=6) + [(21, 85)])), sole, round=2)
    ic.paint(ic.rect(*T([(26, 12)])[0], *T([(60, 22)])[0], r=3 * s), cuff, round=3, bias=0.08)
    for i in range(3):   # laces
        y = 32 + i * 9
        ic.detail(ic.line(T([(42, y), (54, y - 2)]), 1.6 * s), "paper", 3)


def wing_pts(root, span=40, height=42, flip=False, feathers=4):
    """A feathered wing from its root, opening up and out (to the left, or right if flip)."""
    sx = -1 if not flip else 1
    rx, ry = root
    pts = bez((rx, ry), (rx + sx * span * 0.3, ry - height * 0.9), (rx + sx * span * 0.9, ry - height * 1.05),
              (rx + sx * span, ry - height * 0.8), n=16)
    # scalloped lower edge: feather tips
    for i in range(feathers):
        t0 = i / feathers
        a = (rx + sx * span * (1 - t0 * 0.85), ry - height * (0.8 - t0 * 0.75))
        b = (rx + sx * span * (1 - (t0 + 1 / feathers) * 0.85), ry - height * (0.8 - (t0 + 1 / feathers) * 0.75))
        tip = (a[0] - sx * 2 + (b[0] - a[0]) * 0.3, a[1] + 9 + i * 1.5)
        pts += bez(a, tip, b, n=8)
    return pts


def wing(ic, root, span=40, height=42, flip=False, name="white", feathers=4):
    m = ic.poly(wing_pts(root, span, height, flip, feathers))
    ic.paint(m, name, round=7)
    sx = 1 if flip else -1
    rx, ry = root
    for i in range(1, feathers):
        t = i / feathers
        a = (rx + sx * span * 0.15, ry - height * 0.45)
        b = (rx + sx * span * (1 - t * 0.85) + sx * 0, ry - height * (0.8 - t * 0.75) + 6)
        ic.detail(ic.line(bez(a, ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2 - 3), b, n=8), 1.2) & m, name, 2)
    return m


def bottle(ic, cx, cy, r, liquid="red", glass="ice", cork="wood", neck=0.42, level=0.55, shine=True):
    """A round potion bottle (Minecraft splash style) centred at (cx, cy) with radius r."""
    ic.paint(ic.ellipse(cx, cy, r), glass, round=r * 0.5, bias=0.05)
    liq = ic.ellipse(cx, cy + r * 0.04, r * 0.82) & ic.rect(0, cy - r * (level - 0.5) * 1.6, 100, 100)
    ic.paint(liq, liquid, round=r * 0.45)
    nw = r * neck
    ic.paint(ic.rect(cx - nw, cy - r * 1.55, cx + nw, cy - r * 0.7, r * 0.1), glass, round=2, bias=0.1)
    ic.paint(ic.rect(cx - nw * 1.2, cy - r * 1.85, cx + nw * 1.2, cy - r * 1.4, r * 0.15), cork, round=2)
    if shine:
        ic.shine(ic.poly(arc(cx, cy, r * 0.72, r * 0.72, 200, 245, n=8) +
                         arc(cx, cy, r * 0.55, r * 0.55, 245, 200, n=8)))


def ingot(ic, cx, cy, w, h, name="iron", angle=0):
    """A Minecraft ingot seen from the front-top: a trapezoid with a lit top face."""
    top = [(cx - w * 0.32, cy - h * 0.5), (cx + w * 0.42, cy - h * 0.5), (cx + w * 0.5, cy), (cx - w * 0.5, cy)]
    front = [(cx - w * 0.5, cy), (cx + w * 0.5, cy), (cx + w * 0.46, cy + h * 0.5), (cx - w * 0.46, cy + h * 0.5)]
    top, front = rot(top, angle, (cx, cy)), rot(front, angle, (cx, cy))
    g = ic.paint(ic.poly(front), name, round=2, bias=-0.12)
    ic.paint(ic.poly(top), name, round=2, bias=0.18, group=g)
    ic.shine(ic.line(rot([(cx - w * 0.22, cy - h * 0.3), (cx + w * 0.12, cy - h * 0.3)], angle, (cx, cy)), 1.6))


def gem(ic, cx, cy, r, name="diamond", angle=0):
    """A cut gem: a crown of facets over a pointed pavilion."""
    pts = [(cx - r, cy - r * 0.2), (cx - r * 0.55, cy - r * 0.75), (cx + r * 0.55, cy - r * 0.75),
           (cx + r, cy - r * 0.2), (cx, cy + r)]
    pts = rot(pts, angle, (cx, cy))
    g = ic.paint(ic.poly(pts), name, round=r * 0.2)
    c = (cx, cy)
    facets = [
        ([(cx - r, cy - r * 0.2), (cx - r * 0.55, cy - r * 0.75), (cx - r * 0.2, cy - r * 0.2)], 4),
        ([(cx - r * 0.2, cy - r * 0.2), (cx - r * 0.55, cy - r * 0.75), (cx + r * 0.55, cy - r * 0.75),
          (cx + r * 0.2, cy - r * 0.2)], 5),
        ([(cx + r * 0.2, cy - r * 0.2), (cx + r * 0.55, cy - r * 0.75), (cx + r, cy - r * 0.2)], 3),
        ([(cx - r, cy - r * 0.2), (cx - r * 0.2, cy - r * 0.2), (cx, cy + r)], 3),
        ([(cx - r * 0.2, cy - r * 0.2), (cx + r * 0.2, cy - r * 0.2), (cx, cy + r)], 2),
        ([(cx + r * 0.2, cy - r * 0.2), (cx + r, cy - r * 0.2), (cx, cy + r)], 1),
    ]
    for pts2, lv in facets:
        ic.detail(ic.poly(rot(pts2, angle, c)), name, lv, group=g)
    ic.shine(ic.poly(rot([(cx - r * 0.45, cy - r * 0.62), (cx - r * 0.25, cy - r * 0.62), (cx - r * 0.5, cy - r * 0.3)],
                         angle, c)))


def cube(ic, cx, cy, s, name, top=None, side=None, bias=(0.25, 0.0, -0.22), group=None):
    """An isometric block: returns (top, left, right) masks."""
    h = s * 0.5
    t = [(cx, cy - s), (cx + s, cy - h), (cx, cy), (cx - s, cy - h)]
    l = [(cx - s, cy - h), (cx, cy), (cx, cy + s), (cx - s, cy + h)]
    r = [(cx, cy), (cx + s, cy - h), (cx + s, cy + h), (cx, cy + s)]
    mt, ml, mr = ic.poly(t), ic.poly(l), ic.poly(r)
    g = ic.paint(ml, side or name, round=2, flat=0.62 + bias[1], group=group)
    ic.paint(mr, side or name, round=2, flat=0.62 + bias[2], group=g)
    ic.paint(mt, top or name, round=2, flat=0.62 + bias[0], group=g)
    return mt, ml, mr, g


def clover(ic, cx, cy, r, name="green", stem=True):
    for a in (-90, 0, 90, 180):
        ca, sa = math.cos(math.radians(a + 45)), math.sin(math.radians(a + 45))
        px, py = cx + ca * r * 0.55, cy + sa * r * 0.55
        leaf = heart_pts(px, py, r * 0.95, r * 0.9)
        leaf = rot(leaf, a + 45 + 90, (px, py))
        ic.paint(ic.poly(leaf), name, round=r * 0.3)
        ic.detail(ic.line([(cx + ca * r * 0.15, cy + sa * r * 0.15), (cx + ca * r * 0.7, cy + sa * r * 0.7)],
                          r * 0.07), name, 2)
    if stem:
        ic.paint(ic.line(bez((cx, cy), (cx + r * 0.3, cy + r * 0.8), (cx + r * 0.1, cy + r * 1.3), n=8), r * 0.18),
                 name, round=1, bias=-0.1)


def swoosh(ic, path, w, name="white", emit=False):
    m = ic.taper(path, lambda t: w * math.sin(math.pi * t) ** 0.8 + 0.3)
    if emit:
        ic.emit(m, name, round=w * 0.3, glow=False)
    else:
        ic.paint(m, name, round=w * 0.4, cut=False)
    return m


# ====================================================================== silver

def iron_body(ic):
    m = heart(ic, 50, 54, 82, 72, "iron", shine=False)
    ic.paint(ic.poly(heart_pts(50, 53, 50, 44)), "red", round=9)
    ic.shine(ic.poly([(36, 42), (41, 37), (44, 40), (39, 45)]))
    for x, y in ((24, 36), (76, 36), (50, 80), (34, 64), (66, 64)):
        ic.paint(ic.ellipse(x, y, 2.6), "steel", round=1.5, cut=False, bias=0.1)
    ic.shine(ic.poly([(20, 34), (26, 26), (32, 25), (24, 38)]) & m)


def iron_bundle(ic):
    # bread loaf behind, two ingots peeking out of an open sack
    ic.paint(ic.rect(18, 22, 50, 38, 7), "wood", round=6, bias=0.1)
    for x in (26, 34, 42):
        ic.detail(ic.line([(x, 24), (x + 3, 30)], 1.6), "wood", 1)
    ingot(ic, 62, 28, 30, 14, "iron", angle=-14)
    ingot(ic, 48, 36, 30, 14, "iron", angle=8)
    body = bez((24, 44), (8, 70), (18, 92), (50, 92), n=14) + bez((50, 92), (82, 92), (92, 70), (76, 44), n=14) + \
        bez((76, 44), (62, 50), (38, 50), (24, 44), n=10)
    ic.paint(ic.poly(body), "leather", round=14)
    ic.paint(ic.taper(bez((22, 46), (50, 56), (78, 46), n=16), [5, 6, 5]), "leather", round=3, bias=0.12)
    ic.paint(ic.taper(bez((36, 54), (50, 60), (64, 54), n=10), [3.5, 4, 3.5]), "gold", round=2)
    for x, y in ((34, 70), (60, 78), (46, 84)):   # patches / stitches
        ic.detail(ic.line([(x, y), (x + 5, y - 2)], 1.4), "leather", 1)


def feather_fall(ic):
    for i, (y, x0, x1) in enumerate(((70, 18, 44), (80, 30, 56), (60, 10, 30))):
        swoosh(ic, bez((x0, y), ((x0 + x1) / 2, y + 4), (x1, y - 2), n=12), 3.0, "sky")
    feather(ic, (40, 84), (78, 16), width=26, curve=-9)


def gale(ic):
    swoosh(ic, arc(52, 50, 34, 30, 150, 400, n=40), 9, "sky")
    swoosh(ic, arc(54, 50, 20, 17, 170, 420, n=30), 7, "ice")
    swoosh(ic, bez((8, 30), (30, 26), (52, 32), n=12), 4, "white")
    swoosh(ic, bez((12, 74), (34, 82), (60, 76), n=12), 4, "white")
    leaf = rot(bez((60, 48), (66, 36), (80, 36), n=10) + bez((80, 36), (78, 50), (60, 48), n=10), -10, (70, 44))
    ic.paint(ic.poly(leaf), "leaf", round=4)
    ic.detail(ic.line(rot([(62, 47), (77, 38)], -10, (70, 44)), 1.2), "leaf", 1)


def miner_touch(ic):
    mt, ml, mr, g = cube(ic, 62, 66, 26, "stone")
    for pts in (((48, 74), (54, 70)), ((70, 76), (78, 70)), ((56, 52), (66, 50)), ((44, 60), (50, 62))):
        ic.detail(ic.line(list(pts), 2.0) & (ml | mr | mt), "stone", 1, group=g)
    # cracks
    ic.detail(ic.line([(62, 44), (58, 52), (64, 58), (60, 66)], 1.4) & (mt | ml | mr), "deepslate", 0, group=g)
    pickaxe(ic, "diamond", "wood", c=(40, 40), size=0.78, angle=-10)
    for x, y, s in ((84, 34, 4), (30, 74, 3), (88, 52, 3)):
        ic.paint(ic.poly(rot([(x - s, y), (x, y - s), (x + s, y + 1), (x - 1, y + s)], x * 7, (x, y))), "stone",
                 round=1.5)
    ic.sparkle(74, 30, 2)


def lumberjack(ic):
    # a column of oak logs, the axe biting into the bottom one
    for i, y in enumerate((72, 50, 28)):
        x = 38 + i * 3
        ic.paint(ic.rect(x - 16, y - 12, x + 16, y + 12, 2), "wood", round=4, bias=-0.05)
        for k in (-8, 0, 8):
            ic.detail(ic.line([(x + k, y - 11), (x + k + 1, y + 11)], 1.4), "wood", 1)
        ic.paint(ic.ellipse(x + 16, y, 4, 11.5), "paper", round=2, bias=-0.05)
        ic.detail(ic.ellipse(x + 16, y, 2, 6) & ~ic.ellipse(x + 16, y, 1, 3.5), "wood", 2)
    ic.paint(ic.poly([(46, 12), (54, 8), (58, 14), (50, 18)]), "leaf", round=3)
    handle(ic, (86, 90), (60, 66), 6.5, "wood")
    axe_head(ic, (60, 66), angle=225, size=1.05, name="iron")


def auto_smelt(ic):
    ic.glow(50, 66, 40, "fire", 0.8)
    flame(ic, 30, 92, 26, 34, -0.1)
    flame(ic, 70, 92, 26, 34, 0.1)
    flame(ic, 50, 94, 34, 48, 0.0)
    ingot(ic, 50, 38, 56, 26, "gold")
    # a raw ore lump falling in, top-left
    ic.paint(ic.poly([(20, 16), (30, 10), (38, 16), (36, 26), (24, 28)]), "stone", round=4)
    ic.detail(ic.ellipse(26, 18, 3) | ic.ellipse(33, 22, 2.4), "copper", 4)


def feast(ic):
    # a plate, a drumstick and bread, a heart rising
    ic.paint(ic.ellipse(50, 72, 42, 16), "white", round=8, bias=-0.05)
    ic.paint(ic.ellipse(50, 70, 32, 10), "white", round=6, bias=0.08)
    ic.paint(ic.rect(18, 50, 56, 72, 9), "wood", round=8, bias=0.12)
    for x in (28, 38, 48):
        ic.detail(ic.line([(x, 54), (x + 4, 62)], 1.8), "wood", 1)
    meat = bez((42, 66), (36, 44), (56, 30), (72, 40), n=12) + bez((72, 40), (84, 52), (74, 72), (54, 72), n=12)
    ic.paint(ic.poly(meat), "meat", round=9)
    ic.detail(ic.line(bez((50, 50), (58, 42), (68, 46), n=6), 1.6), "meat", 5)
    ic.paint(ic.line([(72, 62), (86, 74)], 5.5), "bone", round=2)
    ic.paint(ic.ellipse(87, 72, 4.2) | ic.ellipse(84, 77, 4.2), "bone", round=2)
    heart(ic, 30, 24, 22, 19, "red")
    ic.sparkle(16, 36, 1, "spark")


def lucky_pick(ic):
    clover(ic, 72, 70, 20, "green", stem=False)
    pickaxe(ic, "iron", "wood", c=(44, 42), size=0.82)
    gem(ic, 20, 72, 9, "emerald", angle=-10)
    gem(ic, 32, 86, 7, "emerald", angle=12)
    ic.sparkle(88, 50, 2)


def rations(ic):
    # a steak behind a golden apple
    st = bez((14, 52), (16, 30), (46, 26), (58, 38), n=12) + bez((58, 38), (66, 50), (54, 74), (30, 72), n=12) + \
        bez((30, 72), (12, 70), (12, 60), (14, 52), n=8)
    ic.paint(ic.poly(st), "meat", round=9, bias=-0.08)
    ic.detail(ic.line(bez((22, 50), (32, 40), (48, 42), n=8), 2.0) | ic.line(bez((24, 60), (36, 52), (50, 54), n=8),
                                                                                2.0), "meat", 2)
    ic.paint(ic.ellipse(28, 46, 5, 4), "bone", round=2)
    apple = bez((62, 36), (44, 26), (32, 48), (40, 72), n=14) + bez((40, 72), (48, 92), (62, 88), (64, 86), n=10) + \
        bez((64, 86), (68, 90), (86, 90), (90, 68), n=10) + bez((90, 68), (96, 44), (78, 28), (62, 36), n=14)
    ic.paint(ic.poly(apple), "gold", round=16)
    ic.paint(ic.line(bez((62, 36), (62, 26), (66, 18), n=6), 3.2), "wood", round=1)
    leaf = bez((64, 24), (70, 12), (86, 14), n=8) + bez((86, 14), (80, 26), (64, 24), n=8)
    ic.paint(ic.poly(leaf), "leaf", round=3)
    ic.shine(ic.poly([(50, 46), (54, 40), (58, 42), (52, 52)]))
    ic.sparkle(84, 50, 2, "spark")


def night_eyes(ic):
    ic.glow(50, 50, 48, "magic", 0.35)
    body = bez((50, 92), (18, 92), (14, 60), (22, 40), n=12) + [(18, 16), (36, 30)] + bez((36, 30), (50, 26), (64, 30),
                                                                                            n=6) \
        + [(82, 16), (78, 40)] + bez((78, 40), (86, 60), (82, 92), (50, 92), n=12)
    ic.paint(ic.poly(body), "owl", round=12)
    ic.paint(ic.poly(bez((26, 62), (30, 92), (70, 92), (74, 62), n=12) + bez((74, 62), (60, 70), (40, 70), (26, 62),
                                                                              n=8)), "paper", round=8, bias=-0.1)
    for x in (36, 64):
        ic.paint(ic.ellipse(x, 48, 13), "bone", round=5)
        ic.emit(ic.ellipse(x, 48, 9.5), "spark", round=4)
        ic.paint(ic.ellipse(x + 1, 49, 4.5), "ink", round=2, cut=False)
        ic.shine(ic.ellipse(x - 2, 46, 1.6))
    ic.paint(ic.poly([(46, 58), (54, 58), (50, 68)]), "orange", round=2)
    for x, y in ((40, 76), (50, 80), (60, 76), (45, 86), (55, 86)):
        ic.detail(ic.poly([(x - 3, y), (x + 3, y), (x, y + 3)]), "owl", 2)


def sharp_edge(ic):
    blade_sword(ic, (38, 64), (84, 16), width=15, blade="iron", guard="steel", grip="leather", pommel="steel",
                guard_w=34, grip_len=18)
    # a bright gleam running along the edge
    ic.emit(ic.taper(bez((50, 47), (64, 34), (80, 18), n=12), lambda t: 3.2 * math.sin(math.pi * t)), "spark",
            round=1, glow=False, bias=0.4)
    ic.sparkle(66, 32, 3)
    ic.sparkle(84, 36, 1)


def thick_skin(ic):
    # a turtle shell, the Minecraft helmet of thick skin
    shell = bez((10, 70), (8, 30), (40, 12), (50, 12), n=14) + bez((50, 12), (60, 12), (92, 30), (90, 70), n=14) + \
        bez((90, 70), (70, 76), (30, 76), (10, 70), n=12)
    m = ic.poly(shell)
    g = ic.paint(m, "green", round=20)
    scutes = ([(38, 26), (50, 20), (62, 26), (60, 40), (40, 40)], [(16, 48), (34, 44), (38, 60), (18, 64)],
              [(84, 48), (66, 44), (62, 60), (82, 64)], [(42, 46), (58, 46), (60, 62), (40, 62)])
    for pts in scutes:
        ic.detail(ic.line(pts + [pts[0]], 2.4) & m, "green", 0, group=g)
        ic.shade_mask(ic.poly(scale(pts, 0.6, (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)))),
                      0.12)
    ic.paint(ic.taper(bez((8, 68), (50, 86), (92, 68), n=20), [8, 10, 8]), "leaf", round=3, bias=-0.1)
    ic.shine(ic.poly([(24, 34), (32, 24), (38, 22), (28, 36)]))


def quiver(ic):
    # fletchings first (they stick out of the quiver mouth)
    for x, y, a in ((36, 20, -18), (50, 12, 0), (64, 20, 18)):
        ic.paint(ic.line(rot([(x, y + 4), (x, y + 30)], a, (x, y + 30)), 3.4), "wood", round=1)
        for side in (-1, 1):
            f = rot([(x, y), (x + side * 7, y - 6), (x + side * 7, y + 6), (x, y + 12)], a, (x, y + 30))
            ic.paint(ic.poly(f), "white" if side < 0 else "paper", round=3)
    body = rot([(30, 34), (70, 34), (66, 92), (34, 92)], 12, (50, 62))
    m = ic.poly(body)
    ic.paint(m, "leather", round=10)
    ic.paint(ic.poly(rot([(28, 32), (72, 32), (71, 40), (29, 40)], 12, (50, 62))), "wood_red", round=3, bias=0.1)
    ic.paint(ic.poly(rot([(31, 82), (69, 82), (68, 90), (32, 90)], 12, (50, 62))), "wood_red", round=3)
    for y in range(46, 80, 8):
        ic.detail(ic.line(rot([(36, y), (40, y + 3)], 12, (50, 62)), 1.4) & m, "leather", 1)
    ic.paint(ic.taper(bez((20, 30), (12, 64), (34, 94), n=12), [3, 3]), "leather", round=1, bias=-0.15)


def shield_master(ic):
    outer = [(16, 14), (84, 14)] + bez((84, 14), (86, 60), (70, 82), (50, 94), n=14) + \
        bez((50, 94), (30, 82), (14, 60), (16, 14), n=14)
    inner = scale(outer, 0.82, (50, 50))
    m = ic.poly(outer)
    ic.paint(m, "iron", round=7)
    ic.paint(ic.poly(inner), "wood", round=10)
    for x in (37, 50, 63):
        ic.detail(ic.line([(x, 21), (x, 86)], 1.4) & ic.poly(inner), "wood", 1)
    ic.paint(ic.ellipse(50, 48, 9), "iron", round=4)
    for x, y in ((24, 20), (76, 20), (50, 88), (20, 50), (80, 50)):
        ic.paint(ic.ellipse(x, y, 2.2), "steel", round=1, cut=False, bias=0.2)
    # a deflected blow: a red spark burst at the top right
    ic.emit(ic.poly(star_pts(82, 22, 14, 5, 6, -10)), "glowred", round=3)
    ic.emit(ic.poly(star_pts(82, 22, 7, 3, 6, -10)), "spark", round=2, glow=False)


def fire_blade(ic):
    ic.glow(56, 44, 44, "fire", 0.7)
    blade_sword(ic, (32, 70), (82, 18), width=14, blade="iron", guard="dark", grip="leather", pommel="dark",
                guard_w=30, grip_len=16)
    for t, h in ((0.15, 26), (0.45, 32), (0.75, 24)):
        x, y = 32 + 50 * t, 70 - 52 * t
        flame(ic, x + 4, y + 6, 15, h, 0.15)
    ic.sparkle(88, 30, 1, "spark")


def venom(ic):
    # a scorpion's tail: banded segments curling over into a hooked stinger dripping green poison
    spine = bez((24, 82), (16, 48), (38, 20), (66, 24), n=40)
    for i, t in enumerate((0.0, 0.16, 0.32, 0.48, 0.64, 0.8, 0.95)):
        x, y = spine[int(t * 40)]
        r = 12.5 - i * 0.9
        ic.paint(ic.ellipse(x, y, r), "dark", round=r * 0.7)
        ic.detail(ic.poly(arc(x, y, r * 0.85, r * 0.85, 200, 280, n=8) + arc(x, y, r * 0.45, r * 0.45, 280, 200,
                                                                                n=8)), "violet", 4)
    barb = bez((66, 22), (86, 24), (92, 44), (80, 60), n=16)
    ic.paint(ic.taper(barb, lambda t: 16 * (1 - t) ** 0.8 + 0.8), "dark", round=4, bias=0.08)
    ic.emit(ic.poly(drop_pts(76, 72, 9, 12)), "venom", round=3)
    ic.emit(ic.poly(drop_pts(66, 88, 6, 8)), "venom", round=2)
    for x, y, r in ((44, 66, 5), (54, 80, 3.4), (36, 84, 2.6)):
        ic.emit(ic.ellipse(x, y, r), "venom", round=r * 0.6)
    ic.shine(ic.ellipse(10, 78, 2.4, 2))


def swimmer(ic):
    # a mermaid tail rising out of a wave, bubbles round it
    _swimmer(ic)


def _swimmer(ic):
    def S(pts):
        return scale(pts, 0.86, (40, 80))
    tail = bez((40, 82), (28, 62), (30, 42), (50, 34), n=14) + bez((50, 34), (62, 30), (66, 22), (62, 16), n=8) + \
        bez((62, 16), (80, 22), (76, 46), (60, 58), n=12) + bez((60, 58), (52, 66), (54, 76), (60, 82), n=10)
    m = ic.poly(S(tail))
    ic.paint(m, "ender", round=10)
    for y in range(40, 80, 7):
        for x in range(30, 72, 7):
            ic.detail(ic.poly(arc(x + (y // 7 % 2) * 3.5, y, 3.2, 2.4, 0, 180, n=6)) & m, "ender", 2)
    fin = bez((62, 16), (66, 4), (84, 2), (92, 8), n=10) + bez((92, 8), (80, 12), (76, 18), (70, 22), n=8) + \
        bez((70, 22), (84, 22), (94, 32), (90, 40), n=8) + bez((90, 40), (76, 30), (66, 26), (62, 16), n=8)
    ic.paint(ic.poly(S(fin)), "diamond", round=5)
    wave = bez((6, 84), (20, 72), (34, 80), (46, 80), n=12) + bez((46, 80), (62, 80), (74, 70), (94, 82), n=12) + \
        [(94, 96), (6, 96)]
    ic.paint(ic.poly(wave) & ic.ellipse(50, 60, 46), "water", round=6)
    ic.detail(ic.line(bez((10, 82), (20, 75), (32, 80), n=6), 2) & ic.poly(wave), "white", 4)
    for x, y, r in ((22, 48, 5), (16, 30, 3.4), (28, 22, 2.4)):
        ic.paint(ic.ellipse(x, y, r), "ice", round=r * 0.6, bias=0.05)
        ic.shine(ic.ellipse(x - r * 0.35, y - r * 0.35, r * 0.3 + 0.6))


def high_jump(ic):
    # a slime block launching up: arrow-like motion arcs above it
    for x, h in ((26, 26), (74, 26)):
        swoosh(ic, bez((x, 46), (x + (50 - x) * 0.1, 46 - h * 0.5), (x, 46 - h), n=10), 3.5, "white")
    mt, ml, mr, g = cube(ic, 50, 72, 26, "slime", bias=(0.25, 0.02, -0.18))
    inner_t = scale([(50, 46), (76, 59), (50, 72), (24, 59)], 0.5, (50, 59))
    ic.detail(ic.poly(move(scale([(50, 46), (76, 59), (50, 72), (24, 59)], 0.55, (50, 72)), 0, 8)) & ml, "slime", 2,
              group=g)
    ic.detail(ic.poly(move(scale([(50, 46), (76, 59), (50, 72), (24, 59)], 0.55, (50, 72)), 0, 8)) & mr, "slime", 1,
              group=g)
    ic.detail(ic.poly(inner_t), "slime", 5, group=g)
    ic.paint(ic.poly(rot([(50, 6), (62, 20), (55, 20), (55, 30), (45, 30), (45, 20), (38, 20)], 0)), "slime", round=3,
             bias=0.1)


def hunter_compass(ic):
    ic.paint(ic.ellipse(50, 52, 40), "iron", round=10)
    ic.paint(ic.ellipse(50, 52, 31), "paper", round=16, bias=-0.04)
    for a in range(0, 360, 45):
        r0 = 26 if a % 90 else 22
        p0 = (50 + r0 * math.cos(math.radians(a)), 52 + r0 * math.sin(math.radians(a)))
        p1 = (50 + 29 * math.cos(math.radians(a)), 52 + 29 * math.sin(math.radians(a)))
        ic.detail(ic.line([p0, p1], 2.0), "paper", 1)
    ic.paint(ic.poly([(50, 52), (44, 48), (76, 24), (54, 58)]), "red", round=3)
    ic.paint(ic.poly([(50, 52), (56, 56), (24, 80), (46, 46)]), "white", round=3, bias=-0.1)
    ic.paint(ic.ellipse(50, 52, 4), "gold", round=2)
    ic.paint(ic.rect(44, 6, 56, 14, 3), "iron", round=3)
    # the hunted: a red target mark
    ic.emit(ic.ellipse(80, 20, 9) & ~ic.ellipse(80, 20, 5.5), "glowred", round=2)
    ic.emit(ic.ellipse(80, 20, 2.5), "glowred", round=2, glow=False)


def footsteps(ic):
    def foot(x, y, a, s=1.0):
        sole = rot(bez((x, y - 12 * s), (x + 9 * s, y - 12 * s), (x + 8 * s, y + 4 * s), (x + 4 * s, y + 10 * s), n=10) +
                   bez((x + 4 * s, y + 10 * s), (x - 2 * s, y + 13 * s), (x - 7 * s, y + 6 * s), (x - 6 * s, y - 2 * s),
                       n=10) + bez((x - 6 * s, y - 2 * s), (x - 7 * s, y - 12 * s), (x, y - 12 * s), n=6), a, (x, y))
        ic.emit(ic.poly(sole), "glowred", round=4 * s)
        for i, (dx, dy, r) in enumerate(((-5, -19, 3.0), (0, -21, 2.6), (5, -20, 2.4), (9, -17, 2.0))):
            p = rot([(x + dx * s, y + dy * s)], a, (x, y))[0]
            ic.emit(ic.ellipse(p[0], p[1], r * s), "glowred", round=1.5)
    ic.glow(50, 50, 46, "glowred", 0.3)
    foot(30, 76, -20, 0.9)
    foot(56, 52, 15, 1.0)
    foot(74, 24, -10, 0.85)


def pearls(ic):
    ic.glow(50, 52, 46, "magic", 0.5)
    ic.paint(ic.ellipse(50, 54, 32), "ender", round=22)
    ic.paint(ic.ellipse(50, 54, 15, 13), "ender", round=8, bias=-0.35)
    ic.paint(ic.ellipse(50, 54, 7, 9), "ink", round=3)
    ic.shine(ic.ellipse(36, 38, 5, 4))
    ic.shine(ic.ellipse(44, 33, 2))
    for x, y in ((14, 28), (84, 34), (20, 80), (86, 76), (66, 14)):
        ic.emit(ic.poly(rot([(x - 3, y), (x, y - 3), (x + 3, y), (x, y + 3)], 0)), "magic", round=1)
    ic.sparkle(80, 18, 2, "magic")


SILVER = {
    "iron_body": iron_body, "iron_bundle": iron_bundle, "feather_fall": feather_fall, "gale": gale,
    "miner_touch": miner_touch, "lumberjack": lumberjack, "auto_smelt": auto_smelt, "feast": feast,
    "lucky_pick": lucky_pick, "rations": rations, "night_eyes": night_eyes, "sharp_edge": sharp_edge,
    "thick_skin": thick_skin, "quiver": quiver, "shield_master": shield_master, "fire_blade": fire_blade,
    "venom": venom, "swimmer": swimmer, "high_jump": high_jump, "hunter_compass": hunter_compass,
    "footsteps": footsteps, "pearls": pearls,
}



# ====================================================================== gold

def bloodthirst(ic):
    # a golden goblet running over with blood
    cup = bez((22, 22), (22, 52), (40, 60), (50, 60), n=14) + bez((50, 60), (60, 60), (78, 52), (78, 22), n=14)
    ic.paint(ic.poly(cup), "gold", round=12)
    ic.paint(ic.rect(45, 58, 55, 76, 2), "gold", round=3, bias=-0.05)
    ic.paint(ic.ellipse(50, 66, 7, 4), "gold", round=3, bias=0.05)
    ic.paint(ic.poly(bez((28, 88), (30, 76), (70, 76), (72, 88), n=10)), "gold", round=5)
    ic.paint(ic.ellipse(50, 22, 28, 7), "blood", round=4, bias=-0.1)
    ic.paint(ic.ellipse(50, 21, 22, 4.6), "blood", round=3, bias=0.2, cut=False)
    # spills over the rim and runs down
    ic.paint(ic.taper(bez((70, 24), (74, 30), (72, 40), (74, 50), n=10), [6, 5, 4, 4]), "blood", round=2)
    ic.paint(ic.poly(drop_pts(74, 54, 7.5, 10)), "blood", round=3)
    ic.paint(ic.taper(bez((30, 25), (27, 32), (29, 38), n=6), [5, 4, 3]), "blood", round=2)
    ic.paint(ic.poly(drop_pts(46, 92, 6, 8)), "blood", round=2)
    for x, y in ((40, 40), (60, 40)):
        ic.paint(ic.ellipse(x, y, 3.2), "red", round=2, cut=False)
    ic.shine(ic.poly([(28, 30), (31, 28), (34, 46), (31, 47)]))


def triple_shot(ic):
    arrow(ic, (10, 70), (60, 10), head="iron", fletch="white", w=3.6)
    arrow(ic, (16, 90), (92, 50), head="iron", fletch="white", w=3.6)
    arrow(ic, (10, 82), (84, 18), head="diamond", fletch="red", w=4.2, head_size=1.25)


def double_jump(ic):
    # a winged leather boot
    for i, y in enumerate((88, 94)):
        swoosh(ic, bez((24 + i * 10, y - 4), (44 + i * 6, y), (64 + i * 4, y - 6), n=10), 3, "sky")
    wing(ic, (40, 40), span=30, height=30, name="white", feathers=4)
    boot(ic, 8, 4, 0.86, "leather", "deepslate", "gold")
    wing(ic, (60, 36), span=30, height=28, flip=True, name="white", feathers=4)


def berserker_pact(ic):
    # a cracked heart behind a double-bitted war axe
    ic.glow(50, 50, 46, "glowred", 0.45)
    heart(ic, 50, 56, 74, 64, "blood", shine=False)
    crack = [(50, 32), (44, 44), (54, 52), (46, 64), (52, 74)]
    ic.detail(ic.line(crack, 3.0) & ic.poly(heart_pts(50, 56, 74, 64)), "ink", 2)
    handle(ic, (28, 92), (70, 18), 6.5, "dark")
    axe_head(ic, (64, 28), angle=-30, size=1.05, name="steel", double=True)
    ic.paint(ic.ellipse(66, 25, 3.5), "glowred", round=1)


def treasure_sense(ic):
    # a stone block with glowing ores seen through it, an eye above
    ic.glow(50, 54, 48, "spark", 0.5)
    mt, ml, mr, g = cube(ic, 50, 58, 32, "deepslate", bias=(0.22, 0.0, -0.16))
    for (x, y), name in (((38, 68), "diamond"), ((60, 74), "gold"), ((70, 54), "iron"), ((30, 50), "gold"),
                         ((50, 40), "diamond"), ((44, 82), "iron")):
        for dx, dy in ((0, 0), (4, 3), (-3, 4)):
            ic.detail(ic.poly([(x + dx - 2.4, y + dy), (x + dx, y + dy - 2.4), (x + dx + 2.4, y + dy),
                               (x + dx, y + dy + 2.4)]), name, 5 if name != "iron" else 4, group=g)
    eye = bez((28, 16), (40, 4), (60, 4), (72, 16), n=12) + bez((72, 16), (60, 28), (40, 28), (28, 16), n=12)
    ic.paint(ic.poly(eye), "white", round=4)
    ic.emit(ic.ellipse(50, 16, 7.5), "spark", round=2)
    ic.paint(ic.ellipse(50, 16, 3.2), "ink", round=1, cut=False)
    ic.sparkle(84, 32, 2)
    ic.sparkle(16, 76, 1)


def smith_blessing(ic):
    # an anvil, a hammer coming down, sparks flying
    ic.paint(ic.poly([(30, 92), (70, 92), (64, 82), (36, 82)]), "dark", round=4)
    ic.paint(ic.rect(40, 66, 60, 84), "dark", round=4, bias=0.05)
    top = [(8, 50), (24, 50), (28, 54), (80, 54), (92, 50), (90, 60), (76, 68), (26, 68)]
    ic.paint(ic.poly(top), "dark", round=4, bias=0.15)
    ic.paint(ic.poly([(26, 52), (82, 52), (80, 56), (28, 56)]), "steel", round=1, bias=0.25, cut=False)
    handle(ic, (82, 8), (52, 36), 6, "wood")
    ic.paint(ic.poly(rot([(36, 30), (58, 30), (58, 46), (36, 46)], 45, (47, 38))), "iron", round=4)
    for x, y, s in ((24, 38, 2), (16, 28, 1), (70, 42, 2), (30, 24, 1)):
        ic.sparkle(x, y, s)
    ic.emit(ic.ellipse(46, 50, 4, 2), "spark", round=1)


def xp_spring(ic):
    # a bottle o' enchanting bursting with xp orbs
    ic.glow(50, 36, 40, "xp", 0.55)
    bottle(ic, 50, 66, 24, liquid="xp", glass="ice", cork="wood", level=0.8)
    for x, y, r in ((50, 18, 8), (28, 26, 6), (74, 24, 6.5), (20, 46, 4.5), (82, 46, 4.5), (38, 8, 4), (64, 6, 3.6)):
        ic.emit(ic.ellipse(x, y, r), "xp", round=r * 0.6)


def enchant_kit(ic):
    # a stack of enchanted books, the top one glinting
    ic.glow(50, 50, 48, "magic", 0.45)
    for y, cover, a in ((78, "wood_red", -4), (62, "sky", 3), (44, "violet", -6)):
        c = (50, y)
        ic.paint(ic.poly(rot([(14, y - 9), (84, y - 9), (84, y + 9), (14, y + 9)], a, c)), cover, round=4)
        ic.paint(ic.poly(rot([(22, y - 5), (84, y - 5), (84, y + 5), (22, y + 5)], a, c)), "paper", round=2,
                 bias=0.05)
        for k in (-2.4, 0.6, 3.6):
            ic.detail(ic.line(rot([(28, y + k), (82, y + k)], a, c), 0.8), "paper", 2)
        ic.paint(ic.poly(rot([(14, y - 9), (24, y - 9), (24, y + 9), (14, y + 9)], a, c)), cover, round=3, bias=0.1)
    for x, y, s in ((24, 22, 2), (76, 18, 3), (88, 40, 1), (46, 14, 1)):
        ic.sparkle(x, y, s, "magic")


def potion_bag(ic):
    bottle(ic, 28, 64, 17, liquid="red", glass="ice", cork="wood")
    bottle(ic, 72, 64, 17, liquid="sky", glass="ice", cork="wood")
    bottle(ic, 50, 52, 21, liquid="violet", glass="ice", cork="wood")
    ic.sparkle(84, 26, 2)


def beast_friend(ic):
    # a tamed wolf's head with its red collar, a heart above
    head = [(18, 30), (26, 8), (40, 26), (60, 26), (74, 8), (82, 30), (86, 58), (74, 74), (26, 74), (14, 58)]
    ic.paint(ic.poly(head), "fur", round=12)
    for ear in ([(22, 28), (27, 14), (35, 26)], [(78, 28), (73, 14), (65, 26)]):
        ic.detail(ic.poly(ear), "meat", 2)
    ic.paint(ic.poly([(36, 52), (64, 52), (66, 74), (34, 74)]), "fur", round=6, bias=0.12)
    ic.paint(ic.rect(44, 52, 56, 60, 3), "ink", round=2)
    for x in (34, 66):
        ic.paint(ic.rect(x - 6, 40, x + 6, 46, 1), "ink", round=1, cut=False)
        ic.detail(ic.rect(x - 2, 40, x + 2, 46), "white", 4)
    ic.paint(ic.poly([(22, 74), (78, 74), (76, 84), (24, 84)]), "red", round=3)
    ic.paint(ic.poly(heart_pts(50, 86, 10, 9)), "gold", round=2)
    heart(ic, 84, 14, 18, 16, "red")


def executioner(ic):
    # a heavy executioner's axe over a skull
    skull_pts = bez((14, 70), (12, 50), (40, 46), (44, 66), n=12) + [(42, 80), (36, 80), (34, 86), (22, 86), (20, 80),
                                                                     (16, 78)]
    ic.paint(ic.poly(skull_pts), "bone", round=7)
    for x, y in ((22, 66), (34, 66)):
        ic.paint(ic.ellipse(x, y, 4.2, 5), "ink", round=2, cut=False)
    ic.detail(ic.poly([(28, 72), (30, 76), (26, 76)]), "ink", 1)
    handle(ic, (30, 94), (66, 10), 6.5, "wood_red")
    blade = [(58, 18), (66, 22)] + bez((66, 22), (90, 14), (96, 46), (82, 70), n=16) + bez((82, 70), (74, 56),
                                                                                          (66, 48), (58, 44), n=8)
    ic.paint(ic.poly(blade), "steel", round=6)
    ic.detail(ic.line(bez((86, 20), (94, 42), (82, 66), n=10), 2.4), "steel", 5)
    ic.paint(ic.taper(bez((70, 30), (76, 34), (72, 44), n=6), [4, 3, 2]), "blood", round=1, cut=False)
    ic.paint(ic.ellipse(62, 30, 4), "dark", round=2)


def combo(ic):
    # three slashes across an impact flash
    ic.emit(ic.poly(star_pts(54, 52, 30, 12, 8, -10)), "spark", round=6)
    for a, b, w in (((10, 28), (78, 14), 7), ((12, 54), (90, 44), 8), ((16, 82), (88, 70), 7)):
        swoosh(ic, bez(a, ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2 + 10), b, n=16), w, "white")
    for x, y in ((86, 16), (20, 64)):
        ic.sparkle(x, y, 1)


def sniper(ic):
    # a scope reticle locked on a distant target
    ic.paint(ic.ellipse(50, 50, 40) & ~ic.ellipse(50, 50, 32), "dark", round=4)
    ic.paint(ic.ellipse(50, 50, 32), "sky", round=30, bias=-0.3)
    for p in ([(50, 18), (50, 40)], [(50, 60), (50, 82)], [(18, 50), (40, 50)], [(60, 50), (82, 50)]):
        ic.paint(ic.line(p, 2.4), "ink", round=1, cut=False)
    ic.emit(ic.ellipse(50, 50, 5.5) & ~ic.ellipse(50, 50, 3), "glowred", round=1)
    ic.emit(ic.ellipse(50, 50, 1.6), "glowred", round=1, glow=False)
    ic.shine(ic.poly(arc(50, 50, 36, 36, 200, 250, n=8) + arc(50, 50, 33.5, 33.5, 250, 200, n=8)))
    arrow(ic, (66, 92), (94, 64), head="iron", fletch="white", w=3.2, head_size=0.8)


def thorns(ic):
    # a chestplate grown over with spikes
    body = [(22, 20), (38, 16), (50, 26), (62, 16), (78, 20), (90, 40), (78, 46), (76, 88), (24, 88), (22, 46),
            (10, 40)]
    m = ic.poly(body)
    ic.paint(m, "iron", round=10)
    ic.paint(ic.poly([(38, 16), (50, 26), (62, 16), (58, 30), (42, 30)]), "steel", round=3, bias=-0.15)
    ic.detail(ic.line([(50, 34), (50, 84)], 1.6) & m, "iron", 1)
    for x, y, a, l in ((18, 30, -150, 13), (82, 30, -30, 13), (26, 58, 180, 13), (74, 58, 0, 13), (34, 84, 135, 11),
                       (66, 84, 45, 11), (40, 44, -120, 10), (60, 44, -60, 10), (50, 64, -90, 10)):
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        nx, ny = -sa, ca
        tip = (x + ca * l, y + sa * l)
        ic.paint(ic.poly([(x + nx * 4.5, y + ny * 4.5), tip, (x - nx * 4.5, y - ny * 4.5)]), "cactus", round=2)
    ic.sparkle(88, 70, 1)


def heavy_hitter(ic):
    # the mace, slamming down with a shockwave
    ic.paint(ic.poly(arc(46, 84, 38, 9, 180, 360, n=24)) & ~ic.ellipse(46, 86, 30, 6), "white", round=2, cut=False)
    for x, y, a in ((16, 68, -30), (78, 70, 30)):
        swoosh(ic, rot([(x, y + 6), (x, y - 8)], a, (x, y)), 4, "white")
    handle(ic, (82, 14), (54, 46), 7, "wood")
    ic.paint(ic.ellipse(78, 18, 5), "steel", round=2)
    head = ic.ellipse(42, 58, 20)
    for a in range(0, 360, 45):
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        head |= ic.poly([(42 + ca * 30, 58 + sa * 30), (42 + ca * 14 - sa * 6, 58 + sa * 14 + ca * 6),
                         (42 + ca * 14 + sa * 6, 58 + sa * 14 - ca * 6)])
    ic.paint(head, "dark", round=10)
    ic.paint(ic.ellipse(42, 58, 9), "steel", round=5)
    ic.shine(ic.ellipse(34, 50, 3, 2))


def frost_arrow(ic):
    ic.glow(70, 30, 34, "frost", 0.6)
    arrow(ic, (12, 88), (78, 22), head="ice", fletch="ice", w=4.2, head_size=1.4)
    for x, y, r in ((78, 22, 14), (60, 40, 8)):
        for a in (0, 60, 120):
            ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
            ic.emit(ic.line([(x - ca * r, y - sa * r), (x + ca * r, y + sa * r)], r * 0.18), "frost", round=1,
                    glow=False)
    ic.sparkle(88, 44, 2, "frost")
    ic.sparkle(52, 18, 1, "frost")


def blast_arrow(ic):
    arrow(ic, (10, 90), (60, 40), head="iron", fletch="red", w=4.0)
    ic.emit(ic.poly(star_pts(68, 32, 30, 15, 9, -20)), "fire", round=7)
    ic.emit(ic.poly(star_pts(68, 32, 16, 9, 7, 5)), "spark", round=4, glow=False)
    for x, y, r in ((26, 30, 5), (90, 66, 4), (40, 14, 3.4)):
        ic.paint(ic.ellipse(x, y, r), "deepslate", round=r * 0.6)
    ic.sparkle(86, 12, 1)


def blink(ic):
    # an eye of ender, a trail of its jump behind it
    for x, y, r in ((16, 82, 4), (26, 72, 5.5), (38, 62, 7)):
        ic.emit(ic.ellipse(x, y, r), "magic", round=r * 0.5, glow=False)
    ic.glow(64, 40, 36, "magic", 0.5)
    ic.paint(ic.ellipse(64, 40, 26), "ender", round=18)
    eye = bez((44, 40), (54, 26), (74, 26), (84, 40), n=10) + bez((84, 40), (74, 54), (54, 54), (44, 40), n=10)
    ic.paint(ic.poly(eye), "green", round=4, bias=0.15)
    ic.paint(ic.ellipse(64, 40, 4, 8), "ink", round=2)
    ic.shine(ic.ellipse(54, 26, 3, 2))
    ic.sparkle(90, 16, 2, "magic")
    ic.sparkle(34, 30, 1, "magic")


def gambler(ic):
    def die(cx, cy, s, a, pips):
        pts = rot([(cx - s, cy - s), (cx + s, cy - s), (cx + s, cy + s), (cx - s, cy + s)], a, (cx, cy))
        ic.paint(ic.poly(pts), "white", round=s * 0.5)
        for px, py in pips:
            p = rot([(cx + px * s * 0.52, cy + py * s * 0.52)], a, (cx, cy))[0]
            ic.paint(ic.ellipse(p[0], p[1], s * 0.17), "blood", round=1, cut=False)
    die(34, 60, 22, -14, ((-1, -1), (1, 1), (0, 0), (1, -1), (-1, 1)))
    die(70, 34, 17, 20, ((-1, -1), (1, 1), (0, 0)))
    for x, y in ((84, 74), (14, 20), (60, 88)):
        ic.sparkle(x, y, 2 if x == 84 else 1, "spark")


def still_cloak(ic):
    # a hooded cloak, two eyes in the dark
    hood = bez((50, 8), (26, 10), (18, 40), (20, 60), n=14) + bez((20, 60), (14, 76), (10, 90), (30, 92), n=8) + \
        [(40, 88), (50, 92), (60, 88), (70, 92)] + bez((70, 92), (90, 90), (86, 76), (80, 60), n=8) + \
        bez((80, 60), (82, 40), (74, 10), (50, 8), n=14)
    m = ic.poly(hood)
    ic.paint(m, "cloth", round=14)
    face = bez((50, 24), (32, 26), (30, 50), (36, 60), n=10) + bez((36, 60), (44, 66), (56, 66), (64, 60), n=8) + \
        bez((64, 60), (70, 50), (68, 26), (50, 24), n=10)
    ic.paint(ic.poly(face), "ink", round=6, bias=-0.2)
    for x in (42, 58):
        ic.emit(ic.ellipse(x, 46, 3.6, 2.4), "soul", round=1)
    for y in (74, 82, 90):   # folds
        ic.detail(ic.line([(30 + (y - 74), y - 6), (32 + (y - 74), y + 2)], 1.6) & m, "cloth", 1)
        ic.detail(ic.line([(70 - (y - 74), y - 6), (68 - (y - 74), y + 2)], 1.6) & m, "cloth", 1)


def lucky_coin(ic):
    ic.paint(ic.ellipse(50, 52, 38), "gold", round=10)
    ic.paint(ic.ellipse(50, 52, 29), "gold", round=24, bias=-0.15)
    clover(ic, 50, 50, 15, "green", stem=True)
    for a in range(0, 360, 30):
        x, y = 50 + 34 * math.cos(math.radians(a)), 52 + 34 * math.sin(math.radians(a))
        ic.detail(ic.ellipse(x, y, 1.3), "gold", 1)
    ic.shine(ic.poly(arc(50, 52, 34, 34, 200, 240, n=6) + arc(50, 52, 31, 31, 240, 200, n=6)))
    ic.sparkle(80, 18, 3)
    ic.sparkle(18, 80, 1)


def sprinter(ic):
    # a boot dashing forward: speed streaks and a dust cloud behind, a bolt of speed
    for y, x0, w in ((34, 4, 4), (50, 0, 5), (66, 8, 4)):
        swoosh(ic, [(x0, y), (x0 + 30, y)], w, "white")
    for x, y, r in ((18, 86, 8), (8, 80, 5.5), (28, 92, 5)):
        ic.paint(ic.ellipse(x, y, r), "paper", round=r * 0.7, bias=-0.1)
    boot(ic, 10, -2, 0.9, "sky", "white", "white")
    ic.emit(ic.poly([(70, 24), (62, 44), (70, 44), (64, 60), (80, 38), (72, 38), (78, 24)]), "spark", round=2)


def cobweb_trap(ic):
    c = (50, 50)
    spokes = list(range(0, 360, 45))
    web = ic.line([c, c], 1)
    for a in spokes:
        ca, sa = math.cos(math.radians(a + 10)), math.sin(math.radians(a + 10))
        web |= ic.line([c, (50 + ca * 44, 50 + sa * 44)], 2.2)
    for r in (12, 24, 36):
        ring = []
        for a in spokes + [spokes[0]]:
            ca, sa = math.cos(math.radians(a + 10)), math.sin(math.radians(a + 10))
            ring.append((50 + ca * r, 50 + sa * r))
        pts = []
        for p, q in zip(ring, ring[1:]):
            mid = ((p[0] + q[0]) / 2 * 0.92 + 50 * 0.08, (p[1] + q[1]) / 2 * 0.92 + 50 * 0.08)
            pts += bez(p, mid, q, n=6)
        web |= ic.line(pts, 2.0)
    ic.paint(web, "white", round=1, flat=0.8)
    # a spider hanging on its thread
    ic.paint(ic.line([(72, 10), (72, 30)], 1.2), "white", flat=0.7, round=1)
    for a in (-40, -10, 20, 50):
        for side in (-1, 1):
            ic.paint(ic.line([(72, 36), (72 + side * 10, 36 + a * 0.12 - 4), (72 + side * 13, 36 + a * 0.2 + 6)], 2.0),
                     "ink", round=1)
    ic.paint(ic.ellipse(72, 38, 7, 6), "ink", round=4, bias=0.25)
    for x in (69, 75):
        ic.emit(ic.ellipse(x, 36, 1.4), "glowred", round=1, glow=False)


# ====================================================================== prism

def phoenix_feather(ic):
    ic.glow(52, 46, 48, "fire", 0.8)
    feather(ic, (28, 90), (74, 10), width=30, vane="fire", quill="gold", curve=-10, emit=True)
    for x, y, s in ((80, 40, 2), (20, 34, 1), (84, 72, 1)):
        ic.sparkle(x, y, s, "spark")
    flame(ic, 26, 94, 20, 24, 0.1)


def diamond_vein(ic):
    # diamond crystals bursting from stone
    rock = bez((8, 74), (12, 58), (30, 54), (50, 60), n=10) + bez((50, 60), (70, 52), (92, 58), (92, 76), n=10) + \
        bez((92, 76), (80, 92), (20, 92), (8, 74), n=12)
    ic.paint(ic.poly(rock), "deepslate", round=10)
    for x, y in ((24, 78), (60, 82), (80, 70)):
        ic.detail(ic.ellipse(x, y, 3, 2), "deepslate", 1)
    for (bx, by), (tx, ty), w in (((50, 70), (50, 8), 20), ((30, 72), (16, 30), 14), ((70, 70), (86, 34), 15),
                                  ((60, 74), (68, 46), 10)):
        L = math.hypot(tx - bx, ty - by)
        ux, uy = (tx - bx) / L, (ty - by) / L
        nx, ny = -uy, ux
        hw = w / 2
        pts = [(bx + nx * hw, by + ny * hw), (tx - ux * w * 0.7 + nx * hw, ty - uy * w * 0.7 + ny * hw), (tx, ty),
               (tx - ux * w * 0.7 - nx * hw, ty - uy * w * 0.7 - ny * hw), (bx - nx * hw, by - ny * hw)]
        g = ic.paint(ic.poly(pts), "diamond", round=3)
        ic.detail(ic.poly([(bx, by), (tx - ux * w * 0.7, ty - uy * w * 0.7), (tx, ty),
                           (tx - ux * w * 0.7 - nx * hw, ty - uy * w * 0.7 - ny * hw), (bx - nx * hw, by - ny * hw)]),
                  "diamond", 2, group=g)
        ic.detail(ic.line([(bx + nx * hw * 0.5, by + ny * hw * 0.5), (tx - ux * w * 0.8 + nx * hw * 0.5,
                                                                      ty - uy * w * 0.8 + ny * hw * 0.5)], 1.6),
                  "diamond", 6, group=g)
    ic.sparkle(36, 14, 2)
    ic.sparkle(84, 18, 1)


def netherite_scrap(ic):
    # a netherite ingot on a smithing template
    tpl = rot([(24, 10), (68, 10), (76, 18), (76, 66), (68, 74), (32, 74), (24, 66), (24, 18)], -10, (50, 42))
    ic.paint(ic.poly(tpl), "deepslate", round=6)
    ic.detail(ic.poly(scale(tpl, 0.78, (50, 42))), "deepslate", 1)
    ic.emit(ic.line(rot([(36, 22), (44, 32), (40, 42), (50, 50)], -10, (50, 42)), 2.2)
            | ic.line(rot([(64, 22), (56, 32), (60, 42), (50, 50)], -10, (50, 42)), 2.2), "magic", round=1)
    ingot(ic, 52, 70, 72, 24, "dark", angle=6)
    ic.sparkle(84, 50, 2, "magic")
    ic.sparkle(16, 84, 1, "magic")


def midas_pick(ic):
    # a golden pickaxe turning stone to gold
    ic.glow(60, 64, 40, "spark", 0.5)
    mt, ml, mr, g = cube(ic, 64, 70, 22, "stone")
    ic.detail(ic.poly([(64, 70), (86, 59), (86, 70), (72, 84), (64, 92)]) & (mr | ml), "gold", 4, group=g)
    ic.detail(ic.poly([(64, 48), (86, 59), (76, 64), (60, 56)]) & mt, "gold", 5, group=g)
    pickaxe(ic, "gold", "wood", c=(40, 40), size=0.8)
    ic.sparkle(88, 44, 3)
    ic.sparkle(40, 84, 2)
    ic.sparkle(16, 60, 1)


def regen_heart(ic):
    ic.glow(50, 50, 48, "xp", 0.4)
    heart(ic, 48, 56, 72, 64, "red")
    swoosh(ic, arc(48, 54, 44, 40, 120, 300, n=30), 5, "xp", emit=True)
    for x, y, r in ((82, 22, 7), (86, 54, 4.5)):
        ic.emit(ic.rect(x - r * 0.35, y - r, x + r * 0.35, y + r) | ic.rect(x - r, y - r * 0.35, x + r, y + r * 0.35),
                "xp", round=1.5)


def earth_blessing(ic):
    # a pickaxe wrapped in a living vine, an emerald glow behind
    ic.glow(50, 50, 48, "xp", 0.4)
    pickaxe(ic, "emerald", "wood", c=(48, 46), size=0.9)
    vine = [(22 + t * 40, 82 - t * 40 + 5 * math.sin(t * 14)) for t in [i / 30 for i in range(31)]]
    ic.paint(ic.line(vine, 2.2), "leaf", round=1, cut=False)
    for t in (0.15, 0.45, 0.75):
        x, y = 22 + t * 40, 82 - t * 40
        leaf = bez((x, y), (x + 4, y - 9), (x + 12, y - 8), n=6) + bez((x + 12, y - 8), (x + 8, y), (x, y), n=6)
        ic.paint(ic.poly(leaf), "green", round=2)
    ic.sparkle(86, 70, 2)
    ic.sparkle(16, 30, 1)


def giant(ic):
    # a towering armoured figure beside a small one
    ic.paint(ic.rect(28, 30, 70, 74, 4), "iron", round=8)
    ic.paint(ic.rect(34, 6, 64, 32, 5), "iron", round=7, bias=0.05)
    ic.paint(ic.rect(38, 16, 60, 22, 1), "ink", round=1, cut=False)
    ic.paint(ic.rect(16, 32, 28, 66, 4), "iron", round=4, bias=-0.05)
    ic.paint(ic.rect(70, 32, 82, 66, 4), "iron", round=4, bias=-0.05)
    ic.paint(ic.rect(32, 74, 48, 94, 2), "deepslate", round=4)
    ic.paint(ic.rect(50, 74, 66, 94, 2), "deepslate", round=4)
    ic.paint(ic.rect(28, 44, 70, 50), "gold", round=2, cut=False)
    ic.paint(ic.rect(84, 76, 94, 94, 2), "leather", round=3)
    ic.paint(ic.rect(84, 66, 94, 76, 2), "meat", round=3)
    for x in (12, 88):
        ic.paint(ic.poly([(x, 16), (x + 6, 26), (x - 6, 26)]), "red", round=2)
        ic.paint(ic.rect(x - 2, 26, x + 2, 38), "red", round=1)


def shadow_strike(ic):
    # a dagger lunging out of a cloud of shadow
    ic.glow(40, 60, 46, "magic", 0.5)
    for x, y, r in ((30, 64, 22), (50, 72, 18), (18, 80, 14), (44, 52, 14)):
        ic.paint(ic.ellipse(x, y, r), "cloth", round=r * 0.7, bias=-0.1)
    blade_sword(ic, (42, 58), (88, 12), width=13, blade="dark", guard="violet", grip="ink", pommel=None, guard_w=26,
                grip_len=10)
    ic.emit(ic.taper(bez((52, 47), (66, 34), (84, 16), n=10), lambda t: 2.6 * math.sin(math.pi * t)), "magic",
            round=1, glow=False, bias=0.3)
    for x, y in ((36, 66), (54, 70)):
        ic.emit(ic.ellipse(x, y, 2.6, 1.6), "magic", round=1, glow=False)
    ic.sparkle(80, 34, 2, "magic")


def war_god(ic):
    # a crested war helm with two crossed swords behind it
    blade_sword(ic, (30, 70), (88, 12), width=10, blade="steel", guard="gold", grip="leather", guard_w=22,
                grip_len=12)
    blade_sword(ic, (70, 70), (12, 12), width=10, blade="steel", guard="gold", grip="leather", guard_w=22,
                grip_len=12)
    plume = bez((50, 30), (54, 4), (82, 2), (90, 14), n=12) + bez((90, 14), (72, 10), (62, 20), (60, 34), n=10)
    ic.paint(ic.poly(plume), "red", round=6)
    helm = bez((24, 66), (20, 30), (40, 20), (50, 20), n=12) + bez((50, 20), (60, 20), (80, 30), (76, 66), n=12) + \
        [(66, 86), (56, 76), (44, 76), (34, 86)]
    ic.paint(ic.poly(helm), "gold", round=12)
    ic.paint(ic.poly([(32, 44), (68, 44), (64, 52), (54, 52), (54, 74), (46, 74), (46, 52), (36, 52)]), "ink",
             round=2, cut=False)
    ic.shine(ic.poly([(30, 34), (36, 26), (40, 26), (32, 40)]))


def fortress(ic):
    # a steel keep: a tower with battlements and a gate
    keep = ic.poly([(22, 30), (78, 30), (82, 92), (18, 92)])
    ic.paint(keep, "steel", round=10)
    for x in (16, 34, 52, 70):
        ic.paint(ic.rect(x, 14, x + 14, 32, 1), "steel", round=3, bias=0.08)
    ic.paint(ic.rect(14, 28, 86, 36, 1), "iron", round=2)
    for y in (48, 62, 76):
        for x in range(20, 80, 14):
            ic.detail(ic.line([(x + (y // 14 % 2) * 7, y), (x + (y // 14 % 2) * 7 + 12, y)], 1.4) & keep, "steel", 2)
    ic.paint(ic.poly(bez((38, 92), (38, 60), (62, 60), (62, 92), n=12)), "dark", round=6, bias=-0.1)
    for x in (44, 50, 56):
        ic.detail(ic.line([(x, 66), (x, 92)], 1.6), "iron", 3)
    ic.detail(ic.line([(40, 76), (60, 76)], 1.6), "iron", 3)
    ic.paint(ic.poly([(48, 4), (51, 4), (51, 16), (48, 16)]), "wood", round=1)
    ic.paint(ic.poly([(51, 4), (64, 8), (51, 12)]), "sky", round=2)


def rewind(ic):
    # a golden clock, a blue arrow winding back round it
    ic.glow(50, 50, 48, "frost", 0.35)
    ic.paint(ic.ellipse(50, 52, 34), "gold", round=10)
    ic.paint(ic.ellipse(50, 52, 26), "paper", round=20)
    for a in range(0, 360, 30):
        r0 = 21 if a % 90 else 18
        ic.detail(ic.line([(50 + r0 * math.cos(math.radians(a)), 52 + r0 * math.sin(math.radians(a))),
                           (50 + 24 * math.cos(math.radians(a)), 52 + 24 * math.sin(math.radians(a)))], 1.8),
                  "paper", 1)
    ic.paint(ic.line([(50, 52), (50, 34)], 3.0), "ink", round=1, cut=False)
    ic.paint(ic.line([(50, 52), (38, 58)], 3.0), "ink", round=1, cut=False)
    ic.paint(ic.ellipse(50, 52, 3), "gold", round=1)
    ring = arc(50, 52, 44, 44, 300, 60 + 360 - 20, n=40)[::-1]
    ic.emit(ic.taper(ring, lambda t: 4 + 3 * t), "frost", round=2)
    end = ring[-1]
    ic.emit(ic.poly([(end[0] - 10, end[1] - 2), (end[0] + 6, end[1] - 10), (end[0] + 4, end[1] + 8)]), "frost",
            round=2)


def clairvoyance(ic):
    # a crystal ball with an eye inside, on a gold stand
    ic.glow(50, 44, 46, "magic", 0.5)
    ic.paint(ic.poly([(26, 92), (74, 92), (66, 76), (34, 76)]), "gold", round=5)
    ic.paint(ic.ellipse(50, 44, 34), "violet", round=30, bias=0.08)
    eye = bez((28, 44), (40, 28), (60, 28), (72, 44), n=12) + bez((72, 44), (60, 60), (40, 60), (28, 44), n=12)
    ic.paint(ic.poly(eye), "white", round=5, bias=0.05)
    ic.emit(ic.ellipse(50, 44, 9), "spark", round=3)
    ic.paint(ic.ellipse(50, 44, 4.2), "ink", round=2, cut=False)
    ic.shine(ic.poly(arc(50, 44, 30, 30, 200, 250, n=8) + arc(50, 44, 25, 25, 250, 200, n=8)))
    ic.sparkle(86, 20, 2, "magic")
    ic.sparkle(14, 64, 1, "magic")


def wings(ic):
    # an elytra spread open, a rocket flame below
    for flip in (False, True):
        root = (46, 44) if not flip else (54, 44)
        sx = 1 if flip else -1
        pts = bez(root, (root[0] + sx * 10, 10), (root[0] + sx * 34, 6), (root[0] + sx * 42, 16), n=14) + \
            bez((root[0] + sx * 42, 16), (root[0] + sx * 46, 50), (root[0] + sx * 30, 80), (root[0] + sx * 18, 92),
                n=14) + bez((root[0] + sx * 18, 92), (root[0] + sx * 12, 70), (root[0] + sx * 4, 56), root, n=10)
        m = ic.poly(pts)
        ic.paint(m, "violet", round=10, bias=-0.05)
        for k in (0.35, 0.65):
            ic.detail(ic.line(bez((root[0] + sx * 6, 30), (root[0] + sx * 30 * k + sx * 10, 50),
                                  (root[0] + sx * 34 * k + sx * 4, 86), n=10), 1.6) & m, "violet", 1)
        ic.detail(ic.line(bez((root[0] + sx * 8, 16), (root[0] + sx * 24, 10), (root[0] + sx * 38, 18), n=10), 2.4)
                  & m, "violet", 5)
    ic.emit(ic.poly(flame_pts(50, 98, 10, 28, 0)), "fire", round=2)
    ic.sparkle(50, 30, 2)


def collector(ic):
    # a fan of three augment cards: silver, gold, prism
    for a, name, gem_name in ((-24, "iron", "white"), (0, "gold", "spark"), (24, "violet", "magic")):
        card = rot([(32, 18), (68, 18), (68, 74), (32, 74)], a, (50, 90))
        g = ic.paint(ic.poly(card), name, round=6)
        ic.detail(ic.poly(rot([(37, 23), (63, 23), (63, 69), (37, 69)], a, (50, 90))), "ink", 1, group=g)
        c = rot([(50, 40)], a, (50, 90))[0]
        ic.emit(ic.poly([(c[0], c[1] - 9), (c[0] + 7, c[1]), (c[0], c[1] + 9), (c[0] - 7, c[1])]),
                gem_name if gem_name != "white" else "frost", round=2, glow=False)
        for k in (54, 60):
            p, q = rot([(40, k), (60, k)], a, (50, 90))
            ic.detail(ic.line([p, q], 1.6), name, 3, group=g)
    ic.sparkle(84, 16, 2)
    ic.sparkle(16, 20, 1)


def vengeful(ic):
    # a wither skull wreathed in soul fire
    ic.glow(50, 50, 48, "soul", 0.5)
    flame(ic, 28, 70, 26, 50, -0.15, "soul")
    flame(ic, 72, 70, 26, 50, 0.15, "soul")
    flame(ic, 50, 56, 34, 56, 0.0, "soul")
    sk = [(24, 40), (76, 40), (76, 76), (66, 76), (66, 86), (34, 86), (34, 76), (24, 76)]
    ic.paint(ic.poly(sk), "deepslate", round=8, bias=-0.05)
    for x in (38, 62):
        ic.paint(ic.rect(x - 7, 52, x + 7, 64, 1), "ink", round=1, cut=False)
        ic.emit(ic.ellipse(x, 58, 2.6), "soul", round=1, glow=False)
    ic.paint(ic.rect(46, 66, 54, 72), "ink", round=1, cut=False)
    for x in (40, 46, 52, 58):
        ic.detail(ic.rect(x, 78, x + 3, 84), "ink", 1)


GOLD = {
    "bloodthirst": bloodthirst, "triple_shot": triple_shot, "double_jump": double_jump,
    "berserker_pact": berserker_pact, "treasure_sense": treasure_sense, "smith_blessing": smith_blessing,
    "xp_spring": xp_spring, "enchant_kit": enchant_kit, "potion_bag": potion_bag, "beast_friend": beast_friend,
    "executioner": executioner, "combo": combo, "sniper": sniper, "thorns": thorns, "heavy_hitter": heavy_hitter,
    "frost_arrow": frost_arrow, "blast_arrow": blast_arrow, "blink": blink, "gambler": gambler,
    "still_cloak": still_cloak, "lucky_coin": lucky_coin, "sprinter": sprinter, "cobweb_trap": cobweb_trap,
}

PRISM = {
    "phoenix_feather": phoenix_feather, "diamond_vein": diamond_vein, "netherite_scrap": netherite_scrap,
    "midas_pick": midas_pick, "regen_heart": regen_heart, "earth_blessing": earth_blessing, "giant": giant,
    "shadow_strike": shadow_strike, "war_god": war_god, "fortress": fortress, "rewind": rewind,
    "clairvoyance": clairvoyance, "wings": wings, "collector": collector, "vengeful": vengeful,
}

ICONS = {**SILVER, **GOLD, **PRISM}


def icon(augment_id, size=72):
    ic = Icon(size)
    ICONS[augment_id](ic)
    return ic.render()
