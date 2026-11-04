"""
공성전 v2 toy-kingdom map props (장난감 왕국 조형물), item models mcpl:prop/<id>: the 3D versions of the block props of
core/map/toykingdom (ToyKit, ToyLaneZone, ToyJungleZone, ToyRiverZone), shown by item displays where the map declares
a model prop. Pack players only (no vanilla fallback): the block prop's place keeps at most a base.

  사탕 가로등 (lollipop)       a lollipop lamp: a round red-and-white swirl sweet (its glowing middle lights the verge)
                               on a thin white stick, a ribbon bow under it, a round white foot
  지팡이 사탕 (candy_cane)     a striped candy cane with a round hook, a green ribbon bow, on a puff of icing
  장난감 기차 (train)          a wooden-track toy train: a red locomotive with a smiling smokebox face, a yellow boiler
                               with gold bands, a blue cab, a chimney puffing cotton smoke, and a wagon of toy blocks
  태엽 자동차 (windup_car)     a round red wind-up car with headlight eyes, a white racing stripe, a number roundel;
                               its wind-up key (windup_car_key) turns
  팽이 (spinning_top)          a big striped spinning top on its tip with painted stars and dots (it spins)
  깃발 배 (boat)               a toy sailing boat at anchor: a red hull with a white gunwale and portholes, a wooden mast,
                               a billowing striped sail and a jib, a pennant on top and bunting flags fore and aft
  오리 배 (duck)               a big rubber duck floating on the river, in a little sailor cap
  무지개 (rainbow)             a five-band rainbow arching over the river between two sleepy puffy clouds

Every model is designed in blocks at real size, base at y = 0 (the ground's top; for the floating boat and duck the
water's surface), front towards -z (vehicles head -z), then shown at the display scale / centre of PROPS (kept in
sync with the Java side once the map places them).
"""

import math

from mcpl_assets import toy_textures  # noqa: F401  (registers the t_* textures)
from mcpl_assets.toykit import EPS, Build

def _rot(axis, angle, origin):
    return {"rotation": (axis, angle, origin)} if angle else {}


def cyl(b, name, centre, r, a0, a1, tex, axis="y", sides=16, **kw):
    """A round bar (a regular 8- or 16-gon, `r` to its corners) along `axis` from a0 to a1, centred on `centre` (the
    two other coordinates, in x, y, z order): the union of bars through the middle, one per side pair, each as long as
    the polygon is wide and as wide as one side. Their ends are staggered so no two caps share a plane."""
    ap = r * math.cos(math.pi / sides)
    half_side = ap * math.tan(math.pi / sides)
    step = 360 / sides
    count = sides // 2
    e = min(EPS / 3, (a1 - a0) / (4 * count))
    for i in range(count):
        phi = i * step
        lo, hi = a0 + i * e, a1 - i * e
        mid = (lo + hi) / 2
        t = _norm(phi)
        if -45 <= t <= 45:
            du, dv, angle = ap, half_side, t
        else:
            du, dv, angle = half_side, ap, _norm(t - 90)
        # (u, v) is the cross-section plane: (x, z) about y, (y, z) about x, (x, y) about z; the angle turns u to v
        cu, cv = centre
        if axis == "y":
            # a turn about y takes +x towards -z, so v = -z
            frm, to = (cu - du, lo, cv - dv), (cu + du, hi, cv + dv)
            rot = _rot("y", angle, (cu, mid, cv))
        elif axis == "x":
            frm, to = (lo, cu - du, cv - dv), (hi, cu + du, cv + dv)
            rot = _rot("x", angle, (mid, cu, cv))
        else:
            frm, to = (cu - du, cv - dv, lo), (cu + du, cv + dv, hi)
            rot = _rot("z", angle, (cu, cv, mid))
        b.box(f"{name}_{i}", frm, to, tex, **kw, **rot)


def _norm(angle):
    while angle > 90:
        angle -= 180
    while angle <= -90:
        angle += 180
    return angle


def bar_xy(b, name, c, direction, length, half_w, z0, z1, tex, **kw):
    """A straight bar in the x-y plane centred on c = (x, y), pointing at `direction` degrees (a multiple of 22.5,
    0 = +x, 90 = +y), `half_w` thick across, from z0 to z1 deep."""
    t = _norm(direction)
    x, y = c
    if -45 <= t <= 45:
        frm, to, angle = (x - length / 2, y - half_w, z0), (x + length / 2, y + half_w, z1), t
    else:
        frm, to, angle = (x - half_w, y - length / 2, z0), (x + half_w, y + length / 2, z1), _norm(t - 90)
    b.box(name, frm, to, tex, **kw, **_rot("z", angle, (x, y, (z0 + z1) / 2)))


def arc_xy(b, name, c, radius, first, last, half_w, z0, z1, tex, overlap=1.15, stagger=True, **kw):
    """An arc of bars around c in the x-y plane: one bar per 22.5 degrees, bisectors from `first` to `last` degrees
    (multiples of 22.5, either direction). Neighbours alternate their depth by EPS (no shared front planes)."""
    step = 22.5 if last >= first else -22.5
    n = int(round((last - first) / step)) + 1
    length = 2 * (radius + half_w) * math.tan(math.radians(11.25)) * overlap
    for k in range(n):
        phi = first + k * step
        x = c[0] + radius * math.cos(math.radians(phi))
        y = c[1] + radius * math.sin(math.radians(phi))
        e = EPS if stagger and k % 2 else 0
        bar_xy(b, f"{name}_{k}", (x, y), phi + 90, length, half_w, z0 - e, z1 + e, tex, **kw)


def puff(b, name, c, size, tex="t_white"):
    """A round cotton puff (smoke, clouds) of `size` blocks: a chamfered box."""
    x, y, z = c
    h = size / 2
    b.rbox(name, (x - h, y - h * 0.85, z - h), (x + h, y + h * 0.85, z + h), tex, h * 0.45)


# ------------------------------------------------------------------------------------------------
# 사탕 가로등 (lollipop lamp)
# ------------------------------------------------------------------------------------------------

LOLLIPOP_Y = 8.6        # the sweet's middle above the ground


def lollipop():
    b = Build()
    cyl(b, "foot", (0, 0), 0.75, 0, 0.3, "t_white")
    cyl(b, "foot_top", (0, 0), 0.45, 0.3, 0.55, "t_white")
    cyl(b, "stick", (0, 0), 0.17, 0.55, LOLLIPOP_Y - 2.6, "t_white", sides=8)
    # the sweet: a thick disc, the swirl as rings of alternating colour standing a hair proud of each other, their
    # middles drifting round so the rings read as a spiral
    y = LOLLIPOP_Y
    rings = ((3.3, "t_cherry", 0.0, 0.0), (2.8, "t_white", 0.12, 0.05), (2.3, "t_cherry", 0.02, 0.16),
             (1.8, "t_white", -0.1, 0.08), (1.3, "t_cherry", -0.04, -0.06), (0.8, "t_butter", 0.03, 0.0))
    for n, (r, tex, dx, dy) in enumerate(rings):
        half = 0.42 + n * 0.06
        glow = tex == "t_butter"
        cyl(b, f"sweet_{n}", (dx, y + dy), r, -half, half, tex, axis="z", glow=glow)
    # a ribbon bow under the sweet: a knot and two loops tilted outwards, two short tails
    by = LOLLIPOP_Y - 3.75
    b.box("bow_knot", (-0.24, by - 0.2, -0.24), (0.24, by + 0.2, 0.24), "t_rose")
    b.mirror("bow_loop", (-0.95, by - 0.28, -0.12), (-0.22, by + 0.28, 0.12), "t_rose",
             rotation=("z", -22.5, (-0.22, by, 0)))
    b.mirror("bow_tail", (-0.42, by - 0.85, -0.08), (-0.2, by - 0.15, 0.08), "t_rose",
             rotation=("z", 22.5, (-0.2, by - 0.15, 0)))
    return b


# ------------------------------------------------------------------------------------------------
# 지팡이 사탕 (candy cane)
# ------------------------------------------------------------------------------------------------

def candy_cane():
    b = Build()
    cyl(b, "icing", (0, 0), 0.75, 0, 0.22, "t_white")
    cyl(b, "icing_top", (0, 0), 0.5, 0.22, 0.38, "t_white")
    top = 5.8
    cyl(b, "post", (0, 0), 0.42, 0.3, top, "t_candy_red", sides=8)
    # the hook: round over the top towards +x and down again, a rounded tip
    r = 1.05
    arc_xy(b, "hook", (r, top), r, 180, -22.5, 0.4, -0.4, 0.4, "t_candy_red", overlap=1.3)
    tip_x = r + r * math.cos(math.radians(-22.5))
    tip_y = top + r * math.sin(math.radians(-22.5))
    b.rbox("tip", (tip_x - 0.36, tip_y - 0.62, -0.36), (tip_x + 0.36, tip_y - 0.1, 0.36), "t_candy_red", 0.12,
           top=False)
    # a green ribbon bow round the post
    by = 4.2
    b.box("ribbon", (-0.47, by - 0.18, -0.47), (0.47, by + 0.18, 0.47), "t_lime")
    b.mirror("ribbon_loop", (-0.95, by - 0.25, -0.58), (-0.4, by + 0.3, -0.42), "t_lime",
             rotation=("z", -22.5, (-0.4, by, -0.5)))
    b.mirror("ribbon_tail", (-0.32, by - 0.8, -0.56), (-0.12, by - 0.1, -0.47), "t_lime",
             rotation=("z", 22.5, (-0.12, by - 0.1, -0.5)))
    return b


# ------------------------------------------------------------------------------------------------
# 장난감 기차 (toy train) on a strip of wooden track, heading -z
# ------------------------------------------------------------------------------------------------

def _wheel(b, name, x, y, z, r, outer):
    """A wheel on the side x (outer face at `outer`, sign of x): black tyre, gold hub."""
    s = 1 if outer > 0 else -1
    a0, a1 = sorted((x, x + 0.22 * s))
    cyl(b, name, (y, z), r, a0, a1, "t_ink", axis="x")
    h0, h1 = sorted((x + 0.22 * s, x + 0.28 * s))
    cyl(b, name + "_hub", (y, z), r * 0.42, h0, h1, "t_gold", axis="x", sides=8)


def train():
    b = Build()
    # the wooden track: a plank with two grooves, and its sleepers' ends
    b.rbox("track", (-0.95, 0, -5.9), (0.95, 0.2, 5.9), "t_wood", 0.08, bottom=False)
    b.mirror("groove", (-0.62, 0.2, -5.88), (-0.42, 0.215, 5.88), "t_cocoa")
    # locomotive chassis and wheels
    b.rbox("chassis", (-0.8, 0.5, -4.7), (0.8, 1.0, -0.2), "t_cherry", 0.12)
    for n, z in enumerate((-3.9, -2.65, -1.4)):
        for side in (-1, 1):
            _wheel(b, f"wheel_{n}_{side}", side * 0.82, 0.78, z, 0.56, side)
    for side in (-1, 1):
        x0, x1 = sorted((side * 1.1, side * 1.16))
        b.box(f"rod_{side}", (x0, 0.72, -4.05), (x1, 0.86, -1.25), "t_metal")
    # boiler with gold bands, the smokebox face at the front
    cyl(b, "boiler", (0, 1.75), 0.78, -4.25, -1.6, "t_butter", axis="z")
    for n, z in enumerate((-3.75, -2.6)):
        cyl(b, f"band_{n}", (0, 1.75), 0.83, z - 0.1, z + 0.1, "t_gold", axis="z")
    cyl(b, "smokebox", (0, 1.75), 0.8, -4.5, -4.2, "t_snow", axis="z")
    b.eyes("eye", 1.95, -4.5, 0.62, size=0.2, depth=0.04)
    b.cheeks("cheek", 1.6, -4.5, 1.02, w=0.2, h=0.1, depth=0.02)
    b.box("smile", (-0.18, 1.42, -4.52), (0.18, 1.48, -4.5), "t_ink")
    b.mirror("smile_end", (-0.26, 1.47, -4.52), (-0.16, 1.53, -4.5), "t_ink")
    cyl(b, "lamp", (0, 2.72), 0.22, -4.42, -4.12, "t_butter", axis="z", sides=8, glow=True)
    b.box("lamp_back", (-0.18, 2.48, -4.14), (0.18, 2.6, -3.9), "t_ink")
    # chimney and its cotton smoke, the steam dome
    cyl(b, "chimney", (0, -3.7), 0.27, 2.4, 3.2, "t_cherry", sides=8)
    cyl(b, "chimney_top", (0, -3.7), 0.42, 3.2, 3.5, "t_ink", sides=8)
    puff(b, "smoke_0", (0, 4.0, -3.55), 0.75)
    puff(b, "smoke_1", (0.15, 4.65, -3.05), 0.6)
    puff(b, "smoke_2", (-0.05, 5.15, -2.5), 0.45)
    cyl(b, "dome", (0, -2.3), 0.3, 2.4, 2.75, "t_gold", sides=8)
    b.box("dome_knob", (-0.1, 2.75, -2.4), (0.1, 2.9, -2.2), "t_gold")
    # cowcatcher and buffer beam
    b.box("buffer", (-0.85, 0.65, -4.85), (0.85, 1.0, -4.65), "t_ink")
    b.box("cowcatcher", (-0.75, 0.2, -5.1), (0.75, 0.75, -4.85), "t_cherry", rotation=("x", 22.5, (0, 0.5, -4.95)))
    # the cab
    b.rbox("cab", (-0.85, 1.0, -1.65), (0.85, 3.0, -0.2), "t_cobalt", 0.1, top=False)
    b.rbox("roof", (-1.02, 3.0, -1.85), (1.02, 3.3, -0.02), "t_white", 0.1, bottom=False)
    for side in (-1, 1):
        x0, x1 = sorted((side * 0.85, side * 0.87))
        b.box(f"cab_window_{side}", (x0, 1.95, -1.35), (x1, 2.7, -0.55), "t_window")
    b.box("cab_window_front", (-0.55, 2.55, -1.67), (0.55, 2.85, -1.65), "t_window")
    # coupling
    b.box("coupling", (-0.1, 0.68, -0.2), (0.1, 0.84, 0.7), "t_metal")
    # the wagon full of toy blocks
    b.rbox("wagon_chassis", (-0.8, 0.5, 0.7), (0.8, 1.0, 4.6), "t_cherry", 0.12)
    for n, z in enumerate((1.45, 3.85)):
        for side in (-1, 1):
            _wheel(b, f"wagon_wheel_{n}_{side}", side * 0.82, 0.7, z, 0.48, side)
    b.box("wagon_floor", (-0.62, 1.0 + EPS, 0.98), (0.62, 1.1, 4.32), "t_wood")
    b.box("wagon_front", (-0.8, 1.0, 0.8), (0.8, 2.0, 0.98), "t_butter")
    b.box("wagon_back", (-0.8, 1.0, 4.32), (0.8, 2.0, 4.5), "t_butter")
    b.mirror("wagon_side", (-0.8, 1.0 + EPS, 0.98), (-0.62, 2.0 - EPS, 4.32), "t_butter")
    b.box("wagon_rim_front", (-0.86, 2.0, 0.74), (0.86, 2.1, 1.02), "t_white")
    b.box("wagon_rim_back", (-0.86, 2.0, 4.28), (0.86, 2.1, 4.56), "t_white")
    b.mirror("wagon_rim_side", (-0.86, 2.0 + EPS, 1.02), (-0.58, 2.1 - EPS, 4.28), "t_white")
    cargo = (("t_sky", -0.22, 1.1, 1.55, 0.78, 0), ("t_pink", 0.2, 1.1, 2.7, 0.8, 22.5),
             ("t_mint", -0.12, 1.1, 3.75, 0.74, -22.5), ("t_butter", -0.1, 1.88, 1.6, 0.6, 45),
             ("t_lilac", 0.15, 1.9, 3.0, 0.55, 0))
    for n, (tex, x, y0, z, s, turn) in enumerate(cargo):
        h = s / 2
        y0 += n * EPS
        b.box(f"cargo_{n}", (x - h, y0, z - h), (x + h, y0 + s, z + h), tex, **_rot("y", turn, (x, y0 + h, z)))
    # a letter dot on the front of the sky block
    b.box("cargo_dot", (-0.4, 1.32, 1.15), (-0.06, 1.66, 1.16), "t_cherry")
    return b


# ------------------------------------------------------------------------------------------------
# 태엽 자동차 (wind-up car), heading -z; its key turns (windup_car_key)
# ------------------------------------------------------------------------------------------------

CAR_KEY_Y = 1.25
CAR_KEY_Z = 2.95


def windup_car():
    b = Build()
    b.rbox("body", (-1.0, 0.38, -2.6), (1.0, 1.3, 2.6), "t_cherry", 0.32)
    b.rbox("upper", (-0.9, 1.2, -2.15), (0.9, 1.62, 2.2), "t_cherry", 0.25, bottom=False)
    for n, z in enumerate((-1.6, 1.6)):
        for side in (-1, 1):
            x0, x1 = sorted((side * 0.86, side * 1.26))
            cyl(b, f"wheel_{n}_{side}", (0.58, z), 0.58, x0, x1, "t_ink", axis="x")
            h0, h1 = sorted((side * 1.26, side * 1.32))
            cyl(b, f"hubcap_{n}_{side}", (0.58, z), 0.3, h0, h1, "t_white", axis="x", sides=8)
            g0, g1 = sorted((side * 1.32, side * 1.36))
            b.box(f"hub_{n}_{side}", (g0, 0.5, z - 0.08), (g1, 0.66, z + 0.08), "t_gold")
            f0, f1 = sorted((side * 0.8, side * 1.12))
            b.rbox(f"fender_{n}_{side}", (f0, 1.0, z - 0.78), (f1, 1.38, z + 0.78), "t_cherry", 0.1, bottom=False)
    # the cabin: white pillars, light glass, a white roof
    b.rbox("cabin", (-0.74, 1.62, -0.85), (0.74, 2.45, 1.35), "t_sky", 0.12, top=False, bottom=False)
    for sx in (-1, 1):
        for sz, z in ((-1, -0.85), (1, 1.35)):
            x0, x1 = sorted((sx * 0.6, sx * 0.78))
            z0, z1 = sorted((z, z - sz * 0.18))
            b.box(f"pillar_{sx}_{sz}", (x0, 1.62, z0 - 0.02 * sz), (x1, 2.45, z1 - 0.02 * sz), "t_white")
    b.rbox("roof", (-0.85, 2.45, -0.98), (0.85, 2.72, 1.48), "t_white", 0.12, bottom=False)
    # racing stripe over bonnet and boot
    b.box("stripe_front", (-0.22, 1.62, -2.0), (0.22, 1.64, -0.86), "t_white")
    b.box("stripe_back", (-0.22, 1.62, 1.36), (0.22, 1.64, 2.05), "t_white")
    # headlight eyes, a grille smile, bumpers
    for side in (-1, 1):
        x = side * 0.55
        cyl(b, f"light_{side}", (x, 1.02), 0.27, -2.68, -2.55, "t_white", axis="z")
        b.box(f"pupil_{side}", (x - 0.09 + side * 0.04, 0.97, -2.71), (x + 0.09 + side * 0.04, 1.15, -2.68), "t_ink")
        b.box(f"glint_{side}", (x - 0.02 + side * 0.04, 1.08, -2.72), (x + 0.03 + side * 0.04, 1.13, -2.71),
              "t_white")
    b.box("smile", (-0.25, 0.68, -2.62), (0.25, 0.74, -2.58), "t_ink")
    b.mirror("smile_end", (-0.33, 0.73, -2.62), (-0.23, 0.79, -2.58), "t_ink")
    b.rbox("bumper_front", (-0.95, 0.36, -2.78), (0.95, 0.56, -2.5), "t_white", 0.08)
    b.rbox("bumper_back", (-0.95, 0.36, 2.5), (0.95, 0.56, 2.78), "t_white", 0.08)
    for side in (-1, 1):
        x = side * 0.62
        b.box(f"taillight_{side}", (x - 0.15, 0.95, 2.6), (x + 0.15, 1.1, 2.62), "t_rose")
    # number roundels on the doors
    for side in (-1, 1):
        x0, x1 = sorted((side * 1.0, side * 1.03))
        cyl(b, f"roundel_{side}", (0.88, 0.05), 0.34, x0, x1, "t_white", axis="x")
        n0, n1 = sorted((side * 1.03, side * 1.05))
        b.box(f"number_{side}", (n0, 0.7, -0.02), (n1, 1.06, 0.1), "t_cobalt")
        b.box(f"number_flag_{side}", (n0, 0.96, -0.1), (n1, 1.06, -0.02), "t_cobalt")
    # the key's socket in the boot
    cyl(b, "key_socket", (0, CAR_KEY_Y), 0.2, 2.55, 2.78, "t_metal", axis="z", sides=8)
    return b


def windup_car_key():
    """The wind-up key: a stem out of the boot and two round lobes (turns about the stem, z)."""
    b = Build()
    y, z = CAR_KEY_Y, CAR_KEY_Z
    cyl(b, "stem", (0, y), 0.12, 2.7, z + 0.2, "t_gold", axis="z", sides=8)
    cyl(b, "hub", (0, y), 0.24, z + 0.1, z + 0.4, "t_gold", axis="z", sides=8)
    for side in (-1, 1):
        x = side * 0.6
        cyl(b, f"lobe_{side}", (x, y), 0.52, z + 0.14, z + 0.36, "t_sky", axis="z")
        cyl(b, f"lobe_hole_{side}", (x, y), 0.24, z + 0.12, z + 0.38, "t_white", axis="z", sides=8)
    return b


# ------------------------------------------------------------------------------------------------
# 팽이 (spinning top): spins about y
# ------------------------------------------------------------------------------------------------

def _top_radius(y):
    """The spinning top's profile: a point at the bottom, widest at 3.6, a dome and a handle."""
    pts = ((0, 0.15), (0.35, 0.45), (1.2, 1.25), (2.2, 2.2), (3.2, 3.0), (3.6, 3.25), (4.2, 3.15), (4.8, 2.7),
           (5.3, 1.9), (5.6, 1.15), (5.75, 0.5))
    for (y0, r0), (y1, r1) in zip(pts, pts[1:]):
        if y0 <= y <= y1:
            return r0 + (r1 - r0) * (y - y0) / (y1 - y0)
    return 0.5


def _top_colour(y):
    if y < 0.35:
        return "t_ink"
    if y < 1.2:
        return "t_white"
    if y < 2.2:
        return "t_butter"
    if y < 3.3:
        return "t_cherry"
    if y < 3.9:
        return "t_white"
    if y < 4.7:
        return "t_cobalt"
    if y < 5.3:
        return "t_sky"
    return "t_white"


def spinning_top():
    b = Build()
    cuts = [0, 0.35, 0.75, 1.2, 1.6, 2.0, 2.4, 2.8, 3.3, 3.6, 3.9, 4.3, 4.7, 5.0, 5.3, 5.55, 5.75]
    for n, (y0, y1) in enumerate(zip(cuts, cuts[1:])):
        r = max(_top_radius(y0), _top_radius(y1))
        if y0 >= 3.6:
            r = _top_radius(y0)
        cyl(b, f"tier_{n}", (0, 0), r, y0 + n * EPS * 0.25, y1, _top_colour((y0 + y1) / 2))
    # handle and knob
    cyl(b, "handle", (0, 0), 0.36, 5.7, 7.6, "t_white", sides=8)
    cyl(b, "knob", (0, 0), 0.62, 7.6, 8.2, "t_cherry")
    cyl(b, "knob_top", (0, 0), 0.4, 8.2, 8.42, "t_cherry")
    # painted stars on the blue band, dots on the red band
    for k in range(8):
        ang = k * 45
        for name, y, r, tex, s in (("star", 4.3, 3.05, "t_butter", 0.42), ("dot", 2.75, 2.7, "t_white", 0.36)):
            ap = r * math.cos(math.pi / 16)
            fx, fz = math.sin(math.radians(ang)), -math.cos(math.radians(ang))
            cx, cz = fx * ap, fz * ap
            turn = _norm(-ang) if k % 2 else 0
            if k % 2 == 0:
                # on an axis: a plain plate standing out of the face
                if k in (0, 4):
                    frm = (cx - s / 2, y - s / 2, min(cz, cz + fz * 0.08))
                    to = (cx + s / 2, y + s / 2, max(cz, cz + fz * 0.08))
                else:
                    frm = (min(cx, cx + fx * 0.08), y - s / 2, cz - s / 2)
                    to = (max(cx, cx + fx * 0.08), y + s / 2, cz + s / 2)
                b.box(f"{name}_{k}", frm, to, tex)
            else:
                d = ap + 0.04
                px, pz = fx * d, fz * d
                b.box(f"{name}_{k}", (px - s / 2, y - s / 2, pz - 0.05), (px + s / 2, y + s / 2, pz + 0.05), tex,
                      rotation=("y", turn, (px, y, pz)))
    return b


# ------------------------------------------------------------------------------------------------
# 깃발 배 (flag boat), heading -z, floating: y = 0 is the water's surface
# ------------------------------------------------------------------------------------------------

MAST_Z = -0.3
MAST_TOP = 7.4


def boat():
    b = Build()
    # the hull in three layers, each with a pointed bow (a diamond) and a round stern
    layers = ((-0.45, 0.05, 0.85, -1.6, 1.9, "t_cherry"), (0.05, 0.7, 1.2, -1.75, 2.45, "t_cherry"),
              (0.7, 1.0, 1.3, -1.8, 2.6, "t_white"))
    for n, (y0, y1, hw, zf, zb, tex) in enumerate(layers):
        b.rbox(f"hull_{n}", (-hw, y0, zf), (hw, y1, zb), tex, min(0.35, hw * 0.35), top=False, bottom=n == 0)
        side = hw * math.sqrt(2)
        e = EPS * (n + 1)
        b.box(f"bow_{n}", (-side / 2, y0 + e, zf - side / 2), (side / 2, y1 - e, zf + side / 2), tex,
              rotation=("y", 45, (0, (y0 + y1) / 2, zf)))
    b.box("deck", (-1.18, 1.0, -1.8), (1.18, 1.06, 2.5), "t_wood")
    deck_side = 1.18 * math.sqrt(2)
    b.box("deck_bow", (-deck_side / 2, 1.0 + EPS, -1.8 - deck_side / 2), (deck_side / 2, 1.06 - EPS, -1.8 + deck_side / 2),
          "t_wood", rotation=("y", 45, (0, 1.03, -1.8)))
    for side in (-1, 1):
        for n, z in enumerate((-0.9, 0.3, 1.5)):
            x0, x1 = sorted((side * 1.2, side * 1.24))
            cyl(b, f"porthole_rim_{side}_{n}", (0.42, z), 0.22, x0, x1, "t_white", axis="x", sides=8)
            g0, g1 = sorted((side * 1.24, side * 1.26))
            cyl(b, f"porthole_{side}_{n}", (0.42, z), 0.14, g0, g1, "t_window", axis="x", sides=8)
    # mast, boom, gold ball on top
    cyl(b, "mast", (0, MAST_Z), 0.13, 1.06, MAST_TOP, "t_wood", sides=8)
    cyl(b, "mast_ball", (0, MAST_Z), 0.22, MAST_TOP, MAST_TOP + 0.3, "t_gold", sides=8)
    b.box("boom", (-0.08, 1.75, MAST_Z), (0.08, 1.9, 2.3), "t_wood")
    # the main sail behind the mast: vertical strips (a triangle), billowing out to +x, white with a red band
    n = 9
    width = 2.4 / n
    for k in range(n):
        z0 = MAST_Z + 0.12 + k * width
        z1 = z0 + width + 0.01
        frac = (k + 0.5) / n
        top = 1.95 + (MAST_TOP - 0.6 - 1.95) * (1 - frac)
        bulge = 0.32 * math.sin(math.pi * frac)
        x0, x1 = bulge - 0.04 - (k % 2) * EPS, bulge + 0.04 + (k % 2) * EPS
        segs = ((1.95, min(top, 2.9), "t_white"), (2.9, min(top, 3.5), "t_cherry"), (3.5, min(top, 4.4), "t_white"),
                (4.4, min(top, 5.0), "t_cherry"), (5.0, top, "t_white"))
        for m, (y0, y1, tex) in enumerate(segs):
            if y1 - y0 > 0.02:
                b.box(f"sail_{k}_{m}", (x0, y0, z0), (x1, y1, z1), tex)
    # the jib in front of the mast down to the bow
    n = 7
    width = 2.0 / n
    for k in range(n):
        z1 = MAST_Z - 0.12 - k * width
        z0 = z1 - width - 0.01
        frac = (k + 0.5) / n
        top = 1.35 + (MAST_TOP - 1.2 - 1.35) * (1 - frac)
        bulge = -0.2 * math.sin(math.pi * frac)
        x0, x1 = bulge - 0.035 - (k % 2) * EPS, bulge + 0.035 + (k % 2) * EPS
        b.box(f"jib_{k}", (x0, 1.35, z0), (x1, top, z1), "t_white")
    # the pennant on top, waving aft
    for k, (dz, dx, h) in enumerate(((0.0, 0.0, 0.5), (0.45, 0.07, 0.4), (0.9, 0.0, 0.3), (1.3, -0.06, 0.2))):
        z0 = MAST_Z + 0.15 + dz
        yc = MAST_TOP - 0.35
        b.box(f"pennant_{k}", (dx - 0.03 - k * EPS, yc - h / 2, z0), (dx + 0.03 + k * EPS, yc + h / 2, z0 + 0.46),
              "t_cherry")
    # bunting: a string from the mast top to the bow and to the stern (22.5 degrees off vertical), little flags
    colours = ("t_rose", "t_butter", "t_sky", "t_mint", "t_lilac")
    for name, sign in (("fore", -1), ("aft", 1)):
        top_y = MAST_TOP - 0.15
        length = (top_y - 1.25) / math.cos(math.radians(22.5))
        cy = (top_y + 1.25) / 2
        cz = MAST_Z + sign * (top_y - 1.25) * math.tan(math.radians(22.5)) / 2
        b.box(f"string_{name}", (-0.02, cy - length / 2, cz - 0.02), (0.02, cy + length / 2, cz + 0.02), "t_white",
              rotation=("x", -22.5 * sign, (0, cy, cz)))
        for k in range(5):
            t = (k + 1) / 6
            y = top_y - (top_y - 1.25) * t
            z = MAST_Z + sign * (top_y - y) * math.tan(math.radians(22.5))
            s = 0.3
            b.box(f"flag_{name}_{k}", (-0.025, y - 0.32 - s / 2, z - s / 2), (0.025, y - 0.32 + s / 2, z + s / 2),
                  colours[(k + (0 if sign < 0 else 2)) % len(colours)], rotation=("x", 45, (0, y - 0.32, z)))
    # an anchor rope over the bow
    b.box("rope", (-0.04, 0.2, -2.95), (0.04, 1.05, -2.87), "t_caramel")
    return b


# ------------------------------------------------------------------------------------------------
# 오리 배 (rubber duck), heading -z, floating: y = 0 is the water's surface
# ------------------------------------------------------------------------------------------------

def duck():
    b = Build()
    b.rbox("body", (-1.05, -0.32, -1.0), (1.05, 1.0, 1.6), "t_butter", 0.48)
    b.rbox("back", (-0.85, 0.95, -0.7), (0.85, 1.32, 1.45), "t_butter", 0.3, bottom=False)
    b.rbox("tail", (-0.5, 0.9, 1.3), (0.5, 1.95, 1.88), "t_butter", 0.22, bottom=False)
    b.mirror_rbox("wing", (-1.16, 0.42, -0.2), (-0.98, 0.98, 1.1), "t_butter", 0.08)
    b.rbox("neck", (-0.55, 1.2, -1.0), (0.55, 1.8, 0.0), "t_butter", 0.18, bottom=False)
    b.rbox("head", (-0.76, 1.65, -1.3), (0.76, 2.95, 0.1), "t_butter", 0.36)
    b.rbox("beak", (-0.42, 1.98, -1.82), (0.42, 2.26, -1.24), "t_orange", 0.1)
    b.rbox("beak_low", (-0.34, 1.84, -1.66), (0.34, 2.0, -1.24), "t_orange", 0.07)
    b.eyes("eye", 2.5, -1.3, 0.78, size=0.2, depth=0.04)
    b.cheeks("cheek", 2.12, -1.3, 1.12, w=0.22, h=0.1, depth=0.02)
    # a little sailor cap
    cyl(b, "cap", (0.05, -0.62), 0.55, 2.92, 3.15, "t_white")
    cyl(b, "cap_band", (0.05, -0.62), 0.58, 2.88, 3.0, "t_cobalt")
    b.box("cap_bobble", (-0.07, 3.15, -0.74), (0.17, 3.31, -0.5), "t_cherry")
    return b


# ------------------------------------------------------------------------------------------------
# 무지개 (rainbow) over the river, between two puffy clouds
# ------------------------------------------------------------------------------------------------

RAINBOW_R = 6.6
RAINBOW_BANDS = ("t_cherry", "t_orange", "t_butter", "t_mint", "t_sky")


def rainbow():
    b = Build()
    width = 0.66
    for n, tex in enumerate(RAINBOW_BANDS):
        r = RAINBOW_R - width * (n + 0.5)
        half = 0.45 + (n % 2) * 2 * EPS
        arc_xy(b, f"band_{n}", (0, 0), r, 0, 180, width / 2, -half, half, tex)
    for side in (-1, 1):
        cx = side * (RAINBOW_R - width * 2.5)
        b.rbox(f"cloud_{side}", (cx - 2.1, -0.3, -1.5), (cx + 2.1, 1.5, 1.5), "t_white", 0.6)
        px = cx - side * 0.35
        b.rbox(f"cloud_puff_{side}", (px - 1.05, 1.2, -1.22), (px + 1.05, 2.85, 1.22), "t_white", 0.5)
        b.rbox(f"cloud_side_{side}", (cx + side * 1.15 - 0.8, 1.0, -1.05), (cx + side * 1.15 + 0.8, 2.25, 1.05),
               "t_white", 0.4)
        b.rbox(f"cloud_out_{side}", (cx + side * 1.9 - 0.55, -0.2, -0.95), (cx + side * 1.9 + 0.55, 1.05, 0.95),
               "t_white", 0.3)
        # a sleepy face: closed eyes (short lines) and rosy cheeks on the front of the big puff
        for e in (-1, 1):
            b.box(f"cloud_eye_{side}_{e}", (px + e * 0.36 - 0.15, 2.12, -1.25), (px + e * 0.36 + 0.15, 2.19, -1.22),
                  "t_ink")
            b.box(f"cloud_cheek_{side}_{e}", (px + e * 0.62 - 0.13, 1.86, -1.24), (px + e * 0.62 + 0.13, 1.96, -1.22),
                  "t_rose")
        b.box(f"cloud_mouth_{side}", (px - 0.08, 1.78, -1.25), (px + 0.08, 1.86, -1.22), "t_ink")
    return b


# ------------------------------------------------------------------------------------------------
# registry: model id -> (builder, display scale, centre blocks above the base)
# ------------------------------------------------------------------------------------------------

PROPS = {
    "lollipop": (lollipop, 4, 6.0),
    "candy_cane": (candy_cane, 3, 4.5),
    "train": (train, 4, 6.0),
    "windup_car": (windup_car, 3, 4.5),
    "windup_car_key": (windup_car_key, 3, CAR_KEY_Y),
    "spinning_top": (spinning_top, 4, 6.0),
    "boat": (boat, 3, 4.0),
    "duck": (duck, 2, 1.5),
    "rainbow": (rainbow, 5, 4.0),
}

ALL = {f"prop/{k}": (lambda fn, sc, c, k: lambda: fn().centred(f"prop/{k}", sc, c))(fn, sc, c, k)
       for k, (fn, sc, c) in PROPS.items()}
