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

The second batch:

  솜사탕 나무 (cotton_tree)    a candy-striped stick under a cloud of pink fluff with white tufts
  크레용 상자 (crayon_box)     a yellow box with a white band, seven sharpened crayons, one lying in front
  구슬 병 (marble_jar)         a clear glass jar (t_glass) full of marbles, a wooden lid, a ribbon bow, a label
  알파벳 블록 더미 (block_stack) two big lettered blocks, a green bridge with a red triangle block, a cylinder, a ball
  장난감 상자 (toy_box, toy_box_pink / _sky / _yellow)  open toy chests (lid up, toys peeking out); the teddy's big
                               one with two stacked blocks beside it (toy_box_mirror: beside its other end, for the
                               mirrored quarters)
  젤리 푸딩 (jelly)            a fluted lime jelly on a plate, whipped cream and a cherry
  치즈 (cheese)                a cheese wedge with holes; the mice's wind-up key (cheese_key) spins in its top
  토끼 귀와 회중시계 (rabbit_watch)  two rabbit ears with a gold pocket watch hung between them; its hands
                               (rabbit_watch_hands) tick round
  모래성 / 양동이 / 삽 (sand_castle, sand_bucket, sand_spade)  by the dragon's sandbox (castle and spade stand on the
                               sand, one block below the ground)

The third batch:

  스펀지 덤불 (sponge_bush)    a round bush of porous sponge on the lane verges: a soft stepped dome, a darker damp
                               underside, a little bud at its front, pink five-petal flowers on top

Every model is designed in blocks at real size, base at y = 0 (the ground's top; for the floating boat and duck the
water's surface), front towards -z (vehicles head -z), then shown at the display scale / centre of PROPS (kept in
sync with the Java side once the map places them).

Round parts without the overlap mess: a round bar (cyl) is turned bars for its wall only, each end one flat plate whose
texture is cut to the polygon (toy_textures.cap; the lollipop paints its swirl on it), and painted dots are such
plates too (dot_y); arcs (arc_xy) and hollow walls (tube) meet corner to corner instead of overlapping by a margin;
stripes on bent or many-sided parts are painted part by part (red, white) rather than a striped texture that breaks
up at every turned face. Every prop model also runs Model._turned_overlaps: turned faces must not z-fight either.
"""

import math

from mcpl_assets import toy_textures  # registers the t_* textures
from mcpl_assets.toykit import EPS, Build

def _rot(axis, angle, origin):
    return {"rotation": (axis, angle, origin)} if angle else {}


def cyl(b, name, centre, r, a0, a1, tex, axis="y", sides=16, ends=(True, True), cap_tex=None, **kw):
    """A round bar (a regular 8- or 16-gon, `r` to its corners) along `axis` from a0 to a1, centred on `centre` (the
    two other coordinates, in x, y, z order): the union of bars through the middle, one per side pair, each as long as
    the polygon is wide and as wide as one side, which make the round wall. The bars have no end faces (eight crossing
    ends on one plane flicker and show their seams): each end is one flat plate cut to the polygon by its texture
    (toy_textures.cap; `cap_tex` = an own painted plate, toy_textures.painted_cap). `ends` = (a0 end, a1 end): leave
    out an end that sits inside something else."""
    ap = r * math.cos(math.pi / sides)
    half_side = ap * math.tan(math.pi / sides)
    step = 360 / sides
    count = sides // 2
    end_faces = {"y": ("down", "up"), "x": ("west", "east"), "z": ("north", "south")}[axis]
    faces = dict(kw.pop("faces", None) or {})
    cu, cv = centre
    for i in range(count):
        t = _norm(i * step)
        along_u = -45 <= t <= 45
        if along_u:
            du, dv, angle = ap, half_side, t
        else:
            du, dv, angle = half_side, ap, _norm(t - 90)
        # (u, v) is the cross-section plane: (x, z) about y, (y, z) about x, (x, y) about z; the angle turns u to v.
        # A bar's long sides are inside the polygon: no faces (their edges would draw seams across the ends)
        inner = _INNER[axis][0 if along_u else 1]
        bar_faces = {**faces, end_faces[0]: None, end_faces[1]: None, inner[0]: None, inner[1]: None}
        mid = (a0 + a1) / 2
        if axis == "y":
            frm, to = (cu - du, a0, cv - dv), (cu + du, a1, cv + dv)
            rot = _rot("y", angle, (cu, mid, cv))
        elif axis == "x":
            frm, to = (a0, cu - du, cv - dv), (a1, cu + du, cv + dv)
            rot = _rot("x", angle, (mid, cu, cv))
        else:
            frm, to = (cu - du, cv - dv, a0), (cu + du, cv + dv, a1)
            rot = _rot("z", angle, (cu, cv, mid))
        b.box(f"{name}_{i}", frm, to, tex, faces=bar_faces, **kw, **rot)
    plate = cap_tex or toy_textures.cap(tex, sides, round(2 * ap, 2))
    for end, (a, face) in enumerate(zip((a0, a1), end_faces)):
        if not ends[end]:
            continue
        if axis == "y":
            frm, to = (cu - ap, a, cv - ap), (cu + ap, a, cv + ap)
        elif axis == "x":
            frm, to = (a, cu - ap, cv - ap), (a, cu + ap, cv + ap)
        else:
            frm, to = (cu - ap, cv - ap, a), (cu + ap, cv + ap, a)
        b.box(f"{name}_end{end}", frm, to, plate, faces={f: (plate if f == face else None) for f in FACE_NAMES},
              full_uv=(face,), **kw)


FACE_NAMES = ("north", "east", "south", "west", "up", "down")
# the inside faces of a round bar's bars about each axis: (bar long along u, bar long along v)
_INNER = {"y": (("north", "south"), ("east", "west")), "x": (("north", "south"), ("up", "down")),
          "z": (("up", "down"), ("east", "west"))}


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


def arc_xy(b, name, c, radius, first, last, half_w, z0, z1, tex, stagger=True, **kw):
    """An arc of bars around c in the x-y plane: one bar per 22.5 degrees, bisectors from `first` to `last` degrees
    (multiples of 22.5, either direction), `radius` to the bars' middle line. Each bar is exactly as long as its
    sector's outer chord, so neighbours meet corner to corner on the outside (no corner sticks out, no notch) and
    their inner corners stay inside the neighbour. Neighbours step their depth by EPS (the small wedge where they
    overlap has no shared front plane): in twos, or in threes for a thick arc whose inner corners reach past the
    neighbour's middle to the next bar. `tex` may be a sequence: bar k takes tex[k % len(tex)] (painted stripes)."""
    step = 22.5 if last >= first else -22.5
    n = int(round((last - first) / step)) + 1
    length = 2 * (radius + half_w) * math.tan(math.radians(11.25))
    cycle = 3 if math.degrees(math.atan2(length / 2, radius - half_w)) > 22.5 else 2
    assert not (cycle == 3 and n == 16), f"{name}: a closed ring this thick cannot stagger its bars"
    for k in range(n):
        phi = first + k * step
        x = c[0] + radius * math.cos(math.radians(phi))
        y = c[1] + radius * math.sin(math.radians(phi))
        e = EPS * (k % cycle) if stagger else 0
        t = tex if isinstance(tex, str) else tex[k % len(tex)]
        bar_xy(b, f"{name}_{k}", (x, y), phi + 90, length, half_w, z0 - e, z1 + e, t, **kw)


def nudge(b, name, d):
    """Moves the box `name` by d = (dx, dy, dz) (its turn's origin too): takes one of two crossing boxes off the
    plane they share."""
    for i, (n, frm, to, tex, kw) in enumerate(b.boxes):
        if n == name:
            kw = dict(kw)
            if kw.get("rotation"):
                axis, angle, origin = kw["rotation"]
                kw["rotation"] = (axis, angle, tuple(o + v for o, v in zip(origin, d)))
            b.boxes[i] = (n, tuple(a + v for a, v in zip(frm, d)), tuple(a + v for a, v in zip(to, d)), tex, kw)
            return
    raise KeyError(name)


def puff(b, name, c, size, tex="t_white"):
    """A round cotton puff (smoke, clouds) of `size` blocks: a chamfered box."""
    x, y, z = c
    h = size / 2
    b.rbox(name, (x - h, y - h * 0.85, z - h), (x + h, y + h * 0.85, z + h), tex, h * 0.45)


# ------------------------------------------------------------------------------------------------
# 사탕 가로등 (lollipop lamp)
# ------------------------------------------------------------------------------------------------

LOLLIPOP_Y = 8.6        # the sweet's middle above the ground
SWEET_R = 3.3


def _swirl(r, sides=16):
    """The lollipop's face: two red and two white bands winding out from a butter middle (a band pair every block
    outwards, like the rings it replaces), a red rim; the toy textures' own pixels (t_cherry, t_white)."""
    ap = r * math.cos(math.pi / sides)
    size = toy_textures.CAP_MAX
    k = 2 * ap * toy_textures.CAP_DENSITY / size
    tex = {n: toy_textures.sampler(n) for n in ("t_cherry", "t_white", "t_butter")}

    def colour(i, j, u, v):
        rr = math.hypot(u, v) * ap
        if rr < 0.85:
            name = "t_butter"
        elif rr > ap - 0.3:
            name = "t_cherry"
        else:
            phase = (2 * math.atan2(v, u) / (2 * math.pi) + rr / 1.0) % 1
            name = "t_cherry" if phase < 0.5 else "t_white"
        return tex[name](int(i * k), int(j * k))
    return toy_textures.painted_cap(f"t_swirl{sides}_{round(r * 100)}", sides, size, colour)


def lollipop():
    b = Build()
    cyl(b, "foot", (0, 0), 0.75, 0, 0.3, "t_white")
    cyl(b, "foot_top", (0, 0), 0.45, 0.3, 0.55, "t_white")
    cyl(b, "stick", (0, 0), 0.17, 0.55, LOLLIPOP_Y - 2.6, "t_white", sides=8, ends=(True, False))
    # the sweet: one thick disc with the red-and-white swirl painted on both faces (one plate each, no stacked
    # rings), a lit butter bead standing out of its middle
    y = LOLLIPOP_Y
    cyl(b, "sweet", (0, y), SWEET_R, -0.48, 0.48, "t_cherry", axis="z", cap_tex=_swirl(SWEET_R))
    cyl(b, "sweet_light", (0, y), 0.8, -0.66, 0.66, "t_butter", axis="z", glow=True)
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
    # the post in red and white bands like the hook's (a striped texture breaks up at every face of the octagon)
    bands = 10
    for n in range(bands):
        y0, y1 = 0.3 + (top - 0.3) * n / bands, 0.3 + (top - 0.3) * (n + 1) / bands
        cyl(b, f"post_{n}", (0, 0), 0.42, y0, y1, ("t_white", "t_cherry")[n % 2], sides=8, ends=(False, False))
    # the hook: round over the top towards +x and down again, a rounded tip; the stripes are painted bar by bar
    # (red, white) so they follow the curve instead of crossing it at every bar's own angle
    r = 1.05
    arc_xy(b, "hook", (r, top), r, 180, -22.5, 0.4, -0.4, 0.4, ("t_cherry", "t_white"))
    tip_x = r + r * math.cos(math.radians(-22.5))
    tip_y = top + r * math.sin(math.radians(-22.5))
    b.rbox("tip", (tip_x - 0.36, tip_y - 0.62, -0.36), (tip_x + 0.36, tip_y - 0.1, 0.36), "t_cherry", 0.12,
           top=False)
    # a green ribbon bow round the post
    by = 4.2
    b.box("ribbon", (-0.47, by - 0.18, -0.47), (0.47, by + 0.18, 0.47), "t_lime")
    b.mirror("ribbon_loop", (-0.95, by - 0.25, -0.58), (-0.4, by + 0.3, -0.42), "t_lime",
             rotation=("z", -22.5, (-0.4, by, -0.5)))
    b.mirror("ribbon_tail", (-0.32, by - 0.8, -0.56), (-0.12, by - 0.1, -0.47), "t_lime",
             rotation=("z", 22.5, (-0.12, by - 0.1, -0.5)))
    nudge(b, "ribbon_tail_r", (0, 0, -EPS))
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
        e = EPS * (n + 1.5)
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


# ================================================================================================
# second batch (모델만, not on the map yet: PENDING)
# ================================================================================================

def tube(b, name, centre, r, y0, y1, thick, tex, sides=16, **kw):
    """A hollow round wall (a regular `sides`-gon, `r` to its corners, `thick` deep) around y from y0 to y1: one
    panel per side, turned about y, exactly one side wide on the outside, so neighbours meet corner to corner (no
    corner sticks out) and their inner corners overlap inside the wall; neighbours stagger their ends."""
    cx, cz = centre
    ap = r * math.cos(math.pi / sides)
    hs = ap * math.tan(math.pi / sides)
    for k in range(sides):
        phi = k * 360 / sides
        q = int(round(phi / 90)) % 4
        rem = phi - 90 * round(phi / 90)
        e = EPS if k % 2 else 0
        lo, hi = y0 + e, y1 - e
        if q == 0:
            frm, to = (cx + ap - thick, lo, cz - hs), (cx + ap, hi, cz + hs)
        elif q == 2:
            frm, to = (cx - ap, lo, cz - hs), (cx - ap + thick, hi, cz + hs)
        elif q == 1:
            frm, to = (cx - hs, lo, cz + ap - thick), (cx + hs, hi, cz + ap)
        else:
            frm, to = (cx - hs, lo, cz - ap), (cx + hs, hi, cz - ap + thick)
        b.box(f"{name}_{k}", frm, to, tex, **kw, **_rot("y", rem, (cx, (y0 + y1) / 2, cz)))


# pixel letters and signs (5 x 5, the star 7 x 7) for the alphabet blocks and decals, top row first
FONT = {
    "A": (".###.", "#...#", "#####", "#...#", "#...#"),
    "B": ("####.", "#...#", "####.", "#...#", "####."),
    "C": (".####", "#....", "#....", "#....", ".####"),
    "1": ("..#..", ".##..", "..#..", "..#..", ".###."),
    "2": ("####.", "....#", ".###.", "#....", "#####"),
    "3": ("####.", "....#", ".###.", "....#", "####."),
    "heart": (".#.#.", "#####", "#####", ".###.", "..#.."),
    "star": ("...#...", "..###..", "#######", ".#####.", "..###..", ".##.##.", ".#...#."),
}


def glyph(b, name, ch, face, c, cell, depth, tex, **kw):
    """Raises a pixel letter (FONT) of `cell` blocks a pixel off a face. `face`: '-z' (front), '+z', '-x', '+x' or
    '+y' (top, the letter's top towards -z); `c` = the letter's middle on the face's surface. Reads unmirrored from
    in front of that face."""
    cx, cy, cz = c
    rows = FONT[ch]
    hm = len(rows) / 2
    for r, row in enumerate(rows):
        hn = len(row) / 2
        s = 0
        while s < len(row):
            if row[s] != "#":
                s += 1
                continue
            t = s
            while t + 1 < len(row) and row[t + 1] == "#":
                t += 1
            u0, u1 = (s - hn) * cell, (t + 1 - hn) * cell     # along the reader's right
            v0, v1 = (hm - r - 1) * cell, (hm - r) * cell     # up
            n = f"{name}_{r}_{s}"
            if face == "-z":
                b.box(n, (cx - u1, cy + v0, cz - depth), (cx - u0, cy + v1, cz), tex, **kw)
            elif face == "+z":
                b.box(n, (cx + u0, cy + v0, cz), (cx + u1, cy + v1, cz + depth), tex, **kw)
            elif face == "+x":
                b.box(n, (cx, cy + v0, cz - u1), (cx + depth, cy + v1, cz - u0), tex, **kw)
            elif face == "-x":
                b.box(n, (cx - depth, cy + v0, cz + u0), (cx, cy + v1, cz + u1), tex, **kw)
            else:
                b.box(n, (cx + u0, cy, cz - v1), (cx + u1, cy + depth, cz - v0), tex, **kw)
            s = t + 1


def sphere(b, name, c, r, tex, slices=5, sides=16):
    """A ball: `slices` round discs stacked through it, each as wide as the ball at its middle."""
    x, y, z = c
    for k in range(slices):
        y0 = y - r + 2 * r * k / slices
        y1 = y - r + 2 * r * (k + 1) / slices
        mid = (y0 + y1) / 2 - y
        rr = math.sqrt(max(r * r - mid * mid, 0)) * 1.04
        cyl(b, f"{name}_{k}", (x, z), rr, y0 + (EPS if k else 0), y1, tex, sides=sides)


DECAL_LIFT = 1 / 128    # a painted dot's plate floats this far off its face (no shared plane, no visible gap)


def dot_y(b, name, cx, y, cz, r, tex, sides=16, **kw):
    """A round flat dot painted on a top face at height y, e.g. holes in the cheese: one plate cut to a `sides`-gon
    by its texture (where three crossing boxes stacked their corners)."""
    ap = r * math.cos(math.pi / sides)
    plate = toy_textures.cap(tex, sides, round(2 * ap, 2))
    y += DECAL_LIFT
    b.box(name, (cx - ap, y, cz - ap), (cx + ap, y, cz + ap), plate,
          faces={f: (plate if f == "up" else None) for f in FACE_NAMES}, full_uv=("up",), **kw)


def dot_z(b, name, cx, cy, z, r, depth, tex):
    """A round dot on a front (-z) face at z: an 8-gon disc standing `depth` proud."""
    cyl(b, name, (cx, cy), r, z - depth, z, tex, axis="z", sides=8)


# ------------------------------------------------------------------------------------------------
# 솜사탕 나무 (cotton candy tree): a candy-striped stick, a cloud of pink fluff high enough to walk under
# ------------------------------------------------------------------------------------------------

def cotton_tree():
    b = Build()
    cyl(b, "foot", (0, 0), 0.6, 0, 0.16, "t_white")
    # the stick in rose and white bands (a striped texture breaks up at every face of the octagon)
    for n in range(6):
        y0, y1 = 0.16 + (3.4 - 0.16) * n / 6, 0.16 + (3.4 - 0.16) * (n + 1) / 6
        cyl(b, f"stick_{n}", (0, 0), 0.22, y0, y1, ("t_rose", "t_snow")[n % 2], sides=8, ends=(False, False))
    # the fluff: a round core, a ring of puffs, a deeper pink underside, white tufts on top
    b.rbox("core", (-2.3, 2.95, -2.3), (2.3, 6.85, 2.3), "t_pink", 1.0)
    for k in range(8):
        ang = math.radians(k * 45 + 11)
        rr = 2.25 + (0.12 if k % 2 else -0.05)
        x, z = rr * math.cos(ang), rr * math.sin(ang)
        y = 4.75 + (0.23 if k % 3 == 0 else -0.11 if k % 3 == 1 else 0.07)
        puff(b, f"ring_{k}", (x, y, z), 2.3 + 0.07 * (k % 3), "t_pink")
    for k in range(5):
        ang = math.radians(k * 72 + 30)
        x, z = 1.35 * math.cos(ang), 1.35 * math.sin(ang)
        puff(b, f"under_{k}", (x, 3.05 + 0.04 * k, z), 1.9 + 0.05 * k, "t_rose")
    puff(b, "under_mid", (0.07, 2.92, -0.05), 2.1, "t_rose")
    for k in range(5):
        ang = math.radians(k * 72 + 5)
        x, z = 1.3 * math.cos(ang), 1.3 * math.sin(ang)
        puff(b, f"upper_{k}", (x, 6.55 + 0.05 * k, z), 2.05 + 0.04 * k, "t_pink")
    puff(b, "crown", (0.1, 7.3, 0.05), 2.0, "t_white")
    tufts = ((1.85, 6.15, -1.3, 1.05), (-1.9, 6.05, -0.95, 1.1), (-0.6, 6.5, 1.95, 1.0), (1.35, 6.35, 1.6, 0.95),
             (-1.45, 6.55, -1.95, 0.9), (0.25, 6.95, -1.7, 1.0))
    for n, (x, y, z, s) in enumerate(tufts):
        puff(b, f"tuft_{n}", (x, y, z), s, "t_white")
    return b


# ------------------------------------------------------------------------------------------------
# 크레용 상자 (box of crayons): a yellow box with a white band, crayons of every length, one lying in front
# ------------------------------------------------------------------------------------------------

CRAYONS = ((-1.05, -0.45, 4.55, "t_cherry"), (-0.35, -0.45, 5.35, "t_cobalt"), (0.35, -0.45, 4.15, "t_lime"),
           (1.05, -0.45, 4.95, "t_orange"), (-0.7, 0.42, 5.75, "t_rose"), (0.0, 0.42, 4.75, "t_grape"),
           (0.7, 0.42, 5.5, "t_sky"))


def _crayon_tip(b, name, centre, a0, a1, tex, axis="y"):
    """A sharpened crayon tip from a0 (the body) to a1 (the point): two narrowing steps."""
    d = a1 - a0
    cyl(b, name + "_tip0", centre, 0.24, a0, a0 + d * 0.55, tex, axis=axis, sides=8)
    cyl(b, name + "_tip1", centre, 0.13, a0 + d * 0.55 - EPS, a1, tex, axis=axis, sides=8)


def crayon_box():
    b = Build()
    # the box: a solid body below the crayons, thin rims around them, the back panel higher (the lid flap)
    b.rbox("body", (-1.6, 0, -1.1), (1.6, 1.95, 1.1), "t_butter", 0.12, top=False)
    b.box("rim_front", (-1.6, 1.95, -1.1), (1.6, 2.3, -0.92), "t_butter")
    b.rbox("rim_back", (-1.6, 1.95, 0.92), (1.6, 3.45, 1.1), "t_butter", 0.08, bottom=False)
    b.mirror("rim_side", (-1.6, 1.95 + EPS, -0.92), (-1.42, 2.3, 0.92), "t_butter")
    b.box("flap_band", (-1.62, 2.75, 0.98), (1.62, 3.0, 1.12), "t_white")
    glyph(b, "flap_star", "star", "-z", (0, 2.45 - 0.05, 0.92), 0.07, 0.03, "t_cherry")
    # the white band round the box and a row of crayon-coloured dots on it
    b.rbox("band", (-1.64, 0.75, -1.14), (1.64, 1.35, 1.14), "t_white", 0.1, top=False, bottom=False)
    for n, (_, _, _, tex) in enumerate(CRAYONS[:5]):
        x = 1.0 - n * 0.5
        dot_z(b, f"band_dot_{n}", x, 1.05, -1.14, 0.16, 0.03, tex)
    # a cobalt zigzag drawn on the side
    for n in range(4):
        z = -0.6 + n * 0.4
        x0, x1 = (-1.6, -1.63)
        b.box(f"zigzag_{n}", (x1, 0.25, z - 0.05), (x0, 0.6, z + 0.05), "t_cobalt",
              rotation=("x", 22.5 if n % 2 else -22.5, (-1.65, 0.42, z)))
    # the crayons standing in it: body, two dark wrapper lines, a sharpened tip
    for n, (x, z, top, tex) in enumerate(CRAYONS):
        body_top = top - 0.6
        cyl(b, f"crayon_{n}", (x, z), 0.3, 1.8, body_top, tex, sides=8)
        for m, y in enumerate((body_top - 1.0, body_top - 0.25)):
            if y > 2.35:
                cyl(b, f"crayon_{n}_line_{m}", (x, z), 0.32, y, y + 0.08, "t_ink", sides=8)
        _crayon_tip(b, f"crayon_{n}", (x, z), body_top, top, tex)
    # one crayon lying on the floor in front, its point to +x
    cyl(b, "lying", (0.3, -1.65), 0.3, -1.35, 0.55, "t_mint", axis="x", sides=8)
    cyl(b, "lying_line", (0.3, -1.65), 0.32, -0.95, -0.87, "t_ink", axis="x", sides=8)
    _crayon_tip(b, "lying", (0.3, -1.65), 0.55, 1.15, "t_mint", axis="x")
    return b


# ------------------------------------------------------------------------------------------------
# 구슬 병 (jar of marbles): a clear jar full of marbles, a wooden lid, a ribbon and a label, a few spilled
# ------------------------------------------------------------------------------------------------

JAR_R = 2.25
MARBLES = ("t_cherry", "t_sky", "t_butter", "t_rose", "t_mint", "t_cobalt", "t_lilac", "t_orange")


def _marble(b, name, x, y, z, tex, s=0.62):
    """A marble: a full-width middle and a narrower core through it, top to bottom (a stepped ball: two boxes that
    share no plane, where a chamfered cube with a corner filler poked lumps and slivers out)."""
    h = s / 2
    c = h * 0.62
    b.box(name, (x - h, y + s * 0.2, z - h), (x + h, y + s * 0.8, z + h), tex)
    b.box(name + "_core", (x - c, y, z - c), (x + c, y + s, z + c), tex)


def marble_jar():
    b = Build()
    tube(b, "glass", (0, 0), JAR_R, 0.12, 3.95, 0.08, "t_glass")
    cyl(b, "glass_base", (0, 0), JAR_R, 0, 0.12, "t_glass")
    # the marbles: the outer ring of each layer (the inside cannot be seen) and a heap on top
    k = 0
    for layer in range(5):
        y = 0.13 + layer * 0.6 + (layer % 2) * EPS
        rings = ((1.62, 15),) if layer < 4 else ((1.25, 10), (0.45, 4))
        if layer == 3:
            rings = ((1.62, 15), (0.95, 8))
        for rr, count in rings:
            for i in range(count):
                ang = math.radians(i * 360 / count + layer * 11.25 + rr * 7)
                x, z = rr * math.cos(ang), rr * math.sin(ang)
                _marble(b, f"marble_{layer}_{k}", x, y + (k % 4) * EPS / 2, z, MARBLES[(k * 3 + layer) % len(MARBLES)])
                k += 1
    _marble(b, "marble_top", 0.05, 3.15, -0.08, "t_cherry")
    # the wooden lid with a knob, a ribbon round the neck with a bow in front
    cyl(b, "lid", (0, 0), JAR_R + 0.16, 3.9, 4.5, "t_wood")
    cyl(b, "lid_top", (0, 0), JAR_R - 0.25, 4.5, 4.68, "t_wood")
    cyl(b, "knob", (0, 0), 0.42, 4.68, 5.1, "t_wood", sides=8)
    tube(b, "ribbon", (0, 0), JAR_R + 0.05, 3.45, 3.75, 0.07, "t_rose")
    by, bz = 3.6, -JAR_R - 0.02
    b.box("bow_knot", (-0.2, by - 0.17, bz - 0.2), (0.2, by + 0.17, bz), "t_rose")
    b.mirror("bow_loop", (-0.85, by - 0.24, bz - 0.14), (-0.18, by + 0.24, bz - 0.04), "t_rose",
             rotation=("z", -22.5, (-0.18, by, bz)))
    b.mirror("bow_tail", (-0.38, by - 0.8, bz - 0.12 - EPS), (-0.18, by - 0.12, bz - 0.04 - EPS), "t_rose",
             rotation=("z", 22.5, (-0.18, by - 0.12, bz)))
    nudge(b, "bow_tail_r", (0, 0, -EPS))
    # a white label on the front with a pink heart
    ap = JAR_R * math.cos(math.pi / 16)
    b.box("label", (-0.62, 1.35, -ap - 0.03), (0.62, 2.45, -ap), "t_white")
    glyph(b, "label_heart", "heart", "-z", (0, 1.9, -ap - 0.03), 0.16, 0.03, "t_rose")
    # spilled marbles
    for n, (x, z, tex) in enumerate(((2.55, -1.15, "t_sky"), (2.0, -2.35, "t_cherry"), (-2.6, 1.5, "t_butter"))):
        _marble(b, f"spilled_{n}", x, EPS / 2, z, tex)     # off the jar foot's ground plane
    return b


# ------------------------------------------------------------------------------------------------
# 알파벳 블록 더미 (stack of alphabet blocks): two big blocks corner to corner, a green bridge with a red
# triangle block on top, a cylinder block and a ball beside
# ------------------------------------------------------------------------------------------------

def _alphabet_block(b, name, x0, z0, s, tex, letter_tex, letters):
    """A white rounded block of size s (low corner x0, 0, z0), a colour panel on every face but the bottom, a raised
    letter on each panel (letters: {face: char})."""
    b.rbox(name, (x0, 0, z0), (x0 + s, s, z0 + s), "t_white", 0.32)
    m = 0.42
    d = 0.06
    x1, z1 = x0 + s, z0 + s
    cxm, czm, cym = x0 + s / 2, z0 + s / 2, s / 2
    b.box(name + "_p_n", (x0 + m, m, z0 - d), (x1 - m, s - m, z0), tex)
    b.box(name + "_p_s", (x0 + m, m, z1), (x1 - m, s - m, z1 + d), tex)
    b.box(name + "_p_w", (x0 - d, m, z0 + m), (x0, s - m, z1 - m), tex)
    b.box(name + "_p_e", (x1, m, z0 + m), (x1 + d, s - m, z1 - m), tex)
    b.box(name + "_p_u", (x0 + m, s, z0 + m), (x1 - m, s + d, z1 - m), tex)
    cell = (s - 2 * m) * 0.15
    spots = {"-z": (cxm, cym, z0 - d), "+z": (cxm, cym, z1 + d), "-x": (x0 - d, cym, czm), "+x": (x1 + d, cym, czm),
             "+y": (cxm, s + d, czm)}
    for face, ch in letters.items():
        glyph(b, f"{name}_{face[1]}{'p' if face[0] == '+' else 'm'}", ch, face, spots[face], cell, 0.07,
              letter_tex)


def block_stack():
    b = Build()
    _alphabet_block(b, "yellow", -4.0, 0.0, 4.0, "t_butter", "t_cherry",
                    {"-z": "A", "-x": "B", "+z": "C", "+x": "1", "+y": "2"})
    _alphabet_block(b, "sky", 0.0, -4.0, 4.0, "t_sky", "t_butter",
                    {"-z": "1", "+x": "2", "-x": "3", "+z": "A", "+y": "B"})
    # the green bridge across the two blocks, a red triangle block on it (a turned square, its lower half hidden)
    b.rbox("bridge", (-2.25, 3.97, -2.25), (1.25, 5.4, 1.25), "t_mint", 0.15)
    s = 1.38 * math.sqrt(2)
    b.box("roof", (-0.5 - s / 2, 5.4 - s / 2, -2.05), (-0.5 + s / 2, 5.4 + s / 2, 1.05), "t_cherry",
          rotation=("z", 45, (-0.5, 5.4, -0.5)))
    glyph(b, "bridge_star", "star", "-z", (-0.5, 4.68, -2.25), 0.2, 0.05, "t_white")
    # a cylinder block and a ball in the open corners
    cyl(b, "cylinder", (2.25, 2.3), 0.85, 0, 1.75, "t_lilac")
    cyl(b, "cylinder_top", (2.25, 2.3), 0.62, 1.75, 1.82, "t_white")
    sphere(b, "ball", (-2.65, 0.78, -2.65), 0.78, "t_cherry")
    cyl(b, "ball_stripe", (-2.65, -2.65), 0.83, 0.68, 0.88, "t_white")
    return b


# ------------------------------------------------------------------------------------------------
# 장난감 상자 (toy chests): an open chest with white corner posts and rim, its lid standing open at the back,
# a gold latch, a decal on the front, toys peeking out
# ------------------------------------------------------------------------------------------------

def _toy_chest(b, w, d, h, tex, decal="star", toys=True, lean=22.5):
    hw, hd = w / 2, d / 2
    t = 0.22
    # feet, walls, a felt floor just under the toys
    for sx in (-1, 1):
        for sz in (-1, 1):
            cyl(b, f"foot_{sx}_{sz}", (sx * (hw - 0.3), sz * (hd - 0.3)), 0.22, 0, 0.2, "t_white", sides=8)
    b.box("front", (-hw + 0.2, 0.2, -hd), (hw - 0.2, h - 0.2, -hd + t), tex)
    b.box("back", (-hw + 0.2, 0.2, hd - t), (hw - 0.2, h - 0.2, hd), tex)
    b.mirror("side", (-hw, 0.2 + EPS, -hd + 0.2), (-hw + t, h - 0.2 - EPS, hd - 0.2), tex)
    b.box("floor", (-hw + t, 0.2 + EPS * 2, -hd + t), (hw - t, h - 0.75, hd - t), "t_lilac")
    # white rounded corner posts and the rim
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = sorted((sx * hw, sx * (hw - 0.42)))
            z0, z1 = sorted((sz * hd, sz * (hd - 0.42)))
            b.rbox(f"post_{sx}_{sz}", (x0 + sx * 0.03, 0.18, z0 + sz * 0.03), (x1 + sx * 0.03, h + 0.05, z1 + sz * 0.03),
                   "t_white", 0.1)
    b.box("rim_front", (-hw + 0.38, h - 0.22, -hd - 0.04), (hw - 0.38, h, -hd + t + 0.04), "t_white")
    b.box("rim_back", (-hw + 0.38, h - 0.22, hd - t - 0.04), (hw - 0.38, h, hd + 0.04), "t_white")
    b.mirror("rim_side", (-hw - 0.04, h - 0.22 + EPS, -hd + 0.38), (-hw + t + 0.04, h - EPS, hd - 0.38), "t_white")
    # the lid standing open behind, leaning back a little, a white edge and the decal on its inside
    lt = 0.2
    lid_rot = ("x", lean, (0, h, hd))
    b.box("lid", (-hw, h, hd - lt), (hw, h + d, hd), tex, rotation=lid_rot)
    b.box("lid_edge", (-hw + 0.25, h + d - 0.2, hd - lt - 0.04), (hw - 0.25, h + d - 0.04, hd - lt), "t_white",
          rotation=lid_rot)
    glyph(b, "lid_decal", decal, "-z", (0, h + d * 0.48, hd - lt), min(w, d) * 0.1, 0.04, "t_white", rotation=lid_rot)
    # the latch on the front rim and the decal on the front
    b.rbox("latch", (-0.22, h - 0.55, -hd - 0.14), (0.22, h - 0.05, -hd - 0.04), "t_gold", 0.06)
    glyph(b, "front_decal", decal, "-z", (0, (h - 0.2) * 0.48 + 0.1, -hd), min(w, h) * 0.085, 0.04, "t_white")
    if not toys:
        return
    # toys peeking out: a ball, toy blocks, a star wand, a little drum (by the chest's size)
    top = h - 0.75
    b.rbox("toy_ball", (-hw + 0.35, top - 0.2, -hd + 0.35), (-hw + 1.45, top + 0.9, -hd + 1.45), "t_cherry", 0.33)
    b.box("toy_ball_stripe", (-hw + 0.33, top + 0.22, -hd + 0.62), (-hw + 1.47, top + 0.45, -hd + 1.18), "t_white")
    bx = hw - 1.05
    b.rbox("toy_block", (bx - 0.5, top - 0.1, -0.5), (bx + 0.5, top + 0.9, 0.5), "t_sky", 0.08,
           rotation=("y", 22.5, (bx, top + 0.4, 0)))
    if w >= 3.5:
        b.rbox("toy_block_2", (-0.55, top - 0.05, hd - 1.25), (0.35, top + 0.85, hd - 0.35), "t_butter", 0.08,
               rotation=("y", -22.5, (-0.1, top + 0.4, hd - 0.8)))
    wx, wz = (0.45, -0.2) if w >= 3.5 else (-0.2, 0.35)
    b.box("wand", (wx - 0.06, top, wz - 0.06), (wx + 0.06, top + 1.6, wz + 0.06), "t_white",
          rotation=("z", 22.5, (wx, top, wz)))
    sx = wx - math.sin(math.radians(22.5)) * 1.6
    sy = top + math.cos(math.radians(22.5)) * 1.6
    glyph(b, "wand_star", "star", "-z", (sx, sy, wz - 0.06), 0.1, 0.12, "t_lemon")
    if w >= 5:
        cyl(b, "toy_rings_peg", (2.0, hd - 0.9), 0.1, top - 0.2, top + 1.55, "t_wood", sides=8)
        for n, (rr, tex) in enumerate(((0.55, "t_cherry"), (0.45, "t_butter"), (0.36, "t_mint"),
                                       (0.28, "t_sky"))):
            y0 = top + n * 0.3
            cyl(b, f"toy_ring_{n}", (2.0, hd - 0.9), rr, y0, y0 + 0.27, tex)
        sphere(b, "toy_ball_2", (-2.4, top + 0.3, hd - 0.85), 0.5, "t_mint", slices=4)
        cyl(b, "toy_drum", (-1.0, hd - 0.85), 0.6, top - 0.2, top + 0.65, "t_cobalt")
        cyl(b, "toy_drum_skin", (-1.0, hd - 0.85), 0.5, top + 0.65, top + 0.72, "t_white")
        cyl(b, "toy_drum_band", (-1.0, hd - 0.85), 0.63, top + 0.1, top + 0.25, "t_gold")


def toy_box(hand=1):
    """곰인형 대장's big pink toy chest (7 x 3, 4 high, a white heart) and two toy blocks stacked beside it, on its +x
    side (hand -1: on its -x side, for a mirrored quarter: a model can be turned, not mirrored)."""
    b = Build()
    _toy_chest(b, 7.0, 3.0, 4.0, "t_pink", "heart", lean=0)  # upright: a candy cane stands behind it
    out = "+x" if hand > 0 else "-x"
    x0 = 4.5 if hand > 0 else -6.5
    _alphabet_block(b, "stack_low", x0, -0.5, 2.0, "t_sky", "t_butter", {"-z": "A", out: "B"})
    y0 = 2.0 + EPS * 3
    stack = Build()
    _alphabet_block(stack, "stack_high", x0 + 0.05, -0.45, 1.9, "t_butter", "t_cherry", {"-z": "B", out: "C", "+y": "1"})
    for name, frm, to, tex, kw in stack.boxes:
        b.box(name, (frm[0], frm[1] + y0, frm[2]), (to[0], to[1] + y0, to[2]), tex,
              rotation=("y", 22.5 * hand, (x0 + 1.0, y0 + 0.95, 0.5)))
    return b


def toy_box_mirror():
    return toy_box(-1)


def toy_box_pink():
    b = Build()
    _toy_chest(b, 4.0, 3.0, 3.0, "t_pink", "heart")
    return b


def toy_box_sky():
    b = Build()
    _toy_chest(b, 4.0, 4.0, 3.0, "t_sky", "star")
    return b


def toy_box_yellow():
    b = Build()
    _toy_chest(b, 3.0, 3.0, 5.0, "t_butter", "star")
    return b


# ------------------------------------------------------------------------------------------------
# 젤리 푸딩 (jelly pudding, 젤리 무리): a fluted lime jelly on a plate, whipped cream and a cherry on top
# ------------------------------------------------------------------------------------------------

def jelly():
    b = Build()
    cyl(b, "plate", (0, 0), 1.95, 0, 0.14, "t_white")
    cyl(b, "tier_0", (0, 0), 1.55, 0.14, 1.5, "t_jelly_lime")
    cyl(b, "tier_1", (0, 0), 1.3, 1.5, 2.5, "t_jelly_lime")
    cyl(b, "tier_2", (0, 0), 0.98, 2.5, 3.0, "t_jelly_lime")
    # the mould's flutes: rounded ribs round the two lower tiers (on the axes, and turned 45 degrees between them)
    for k in range(8):
        e = EPS * (k % 2)
        ang = math.radians(k * 45)
        fx, fz = math.cos(ang), math.sin(ang)
        for name, rr, y0, y1, hw in (("rib", 1.48, 0.14, 1.45, 0.22), ("rib_up", 1.25, 1.5, 2.42, 0.18)):
            px, pz = fx * rr, fz * rr
            if k % 2 == 0:
                along_x = k in (0, 4)
                dx, dz = (0.18, hw) if along_x else (hw, 0.18)
                b.box(f"{name}_{k}", (px - dx, y0 + e, pz - dz), (px + dx, y1 - e, pz + dz), "t_jelly_lime")
            else:
                b.box(f"{name}_{k}", (px - 0.18, y0 + e, pz - hw), (px + 0.18, y1 - e, pz + hw), "t_jelly_lime",
                      rotation=("y", -45 if k in (1, 5) else 45, (px, (y0 + y1) / 2, pz)))
    # whipped cream: a ring of dollops draped over the top edge, a swirl in the middle
    for k in range(8):
        ang = math.radians(k * 45 + 22.5)
        x, z = 0.82 * math.cos(ang), 0.82 * math.sin(ang)
        b.rbox(f"cream_{k}", (x - 0.3, 2.72 + EPS * (k % 2), z - 0.3), (x + 0.3, 3.22 - EPS * (k % 2), z + 0.3),
               "t_white", 0.12)
        b.box(f"drip_{k}", (x * 1.15 - 0.1, 2.35, z * 1.15 - 0.1), (x * 1.15 + 0.1, 2.8, z * 1.15 + 0.1), "t_white")
    cyl(b, "swirl_0", (0, 0), 0.62, 3.0, 3.3, "t_white", sides=8)
    cyl(b, "swirl_1", (0, 0), 0.42, 3.3, 3.55, "t_white", sides=8)
    cyl(b, "swirl_2", (0, 0), 0.22, 3.55, 3.72, "t_white", sides=8)
    # the cherry and its stem
    sphere(b, "cherry", (0, 3.98, 0), 0.31, "t_cherry", slices=4, sides=8)
    b.box("cherry_glint", (-0.2, 4.0, -0.32), (-0.08, 4.12, -0.3), "t_white")
    b.box("stem", (0.0, 4.15, -0.03), (0.06, 4.75, 0.03), "t_lime", rotation=("z", -22.5, (0.03, 4.2, 0)))
    return b


# ------------------------------------------------------------------------------------------------
# 치즈와 태엽 열쇠 (태엽 쥐 떼): a big cheese wedge with holes, the mice's wind-up key stuck in its top (cheese_key,
# spins). The key stands at the model's origin; the wedge rises from -x to the key.
# ------------------------------------------------------------------------------------------------

CHEESE_TOP = 3.1


def cheese():
    b = Build()
    zf, zb = -3.5, 3.5
    b.box("base", (-3.5, 0, zf), (0.6, 0.9, zb), "t_cheese")
    b.box("high", (-1.3, 0.9, zf + EPS), (0.6, CHEESE_TOP, zb - EPS), "t_cheese")
    # the slope: a slab laid on 45 degrees from the low end up to the high block (its lower part inside the cheese)
    slope_l = 2.2 * math.sqrt(2)
    rot = ("z", 45, (-1.3, CHEESE_TOP, 0))
    b.box("slope", (-1.3 - slope_l, CHEESE_TOP - 1.2, zf + 2 * EPS), (-1.3, CHEESE_TOP, zb - 2 * EPS), "t_cheese",
          rotation=rot)
    # holes: on the slope (in its own frame), on the top, on the sides and the high end
    for n, (u, z, r) in enumerate(((-1.0, -1.6, 0.36), (-2.1, 0.9, 0.42), (-0.7, 2.3, 0.27), (-2.5, -2.6, 0.25))):
        dot_y(b, f"slope_hole_{n}", -1.3 + u, CHEESE_TOP, z, r, "t_cheese_hole", rotation=rot)
    for n, (x, z, r) in enumerate(((-0.45, 1.5, 0.36), (-0.75, -2.4, 0.26), (0.1, -1.0, 0.2))):
        dot_y(b, f"top_hole_{n}", x, CHEESE_TOP, z, r, "t_cheese_hole")
    sides = (("front", zf, -1, ((-2.75, 0.45, 0.27), (-0.55, 2.25, 0.42), (-1.55, 1.15, 0.33), (0.15, 0.6, 0.24))),
             ("back", zb, 1, ((-2.3, 0.5, 0.3), (-0.4, 1.4, 0.38), (-0.95, 2.5, 0.24), (-3.0, 0.4, 0.2))))
    for side, z, s, holes in sides:
        for n, (x, y, r) in enumerate(holes):
            z0, z1 = sorted((z, z + s * 0.03))
            cyl(b, f"{side}_hole_{n}", (x, y), r, z0, z1, "t_cheese_hole", axis="z", sides=8)
    for n, (y, z, r) in enumerate(((1.2, -1.9, 0.38), (2.3, 0.6, 0.3), (0.6, 2.2, 0.26))):
        cyl(b, f"end_hole_{n}", (y, z), r, 0.6, 0.63, "t_cheese_hole", axis="x", sides=8)
    # the key's socket
    cyl(b, "socket", (0, 0), 0.34, CHEESE_TOP, CHEESE_TOP + 0.18, "t_gold", sides=8)
    return b


def cheese_key():
    """The wind-up key stuck in the cheese: a gold stem and a pink bow of two rings facing ±x (spins about y)."""
    b = Build()
    cyl(b, "stem", (0, 0), 0.17, CHEESE_TOP + 0.1, 5.85, "t_gold", sides=8)
    cyl(b, "collar", (0, 0), 0.28, 4.6, 4.85, "t_gold", sides=8)
    y = 6.75
    cyl(b, "hub", (y, 0), 0.34, -0.17, 0.17, "t_gold", axis="x", sides=8)
    for side in (-1, 1):
        z = side * 1.25
        cyl(b, f"lobe_{side}", (y, z), 1.12, -0.13, 0.13, "t_rose", axis="x")
        cyl(b, f"lobe_hole_{side}", (y, z), 0.46, -0.15, 0.15, "t_white", axis="x", sides=8)
    cyl(b, "cap", (0, 0), 0.22, 7.0, 7.25, "t_gold", sides=8)
    return b


# ------------------------------------------------------------------------------------------------
# 토끼 귀와 회중시계 (시계 토끼): two tall rabbit ears out of the ground, tips leaning outwards, the rabbit's gold
# pocket watch hung between them on a chain, high enough to walk under; its hands tick round (rabbit_watch_hands)
# ------------------------------------------------------------------------------------------------

WATCH_Y = 5.6
WATCH_R = 2.45
EAR_X = 4.5
EAR_BEND = 4.4


def _ear(b, name, s):
    """One ear on side s (-1 left, +1 right): a straight lower part and a tip leaning outwards, pink inside."""
    x = s * EAR_X
    b.rbox(name + "_low", (x - 1.0, 0.1, -0.55), (x + 1.0, EAR_BEND + 0.25, 0.55), "t_fur", 0.4, top=False)
    b.box(name + "_inner_low", (x - 0.55, 0.35, -0.6), (x + 0.55, EAR_BEND + 0.25, -0.55), "t_pink")
    rot = ("z", -22.5 * s, (x, EAR_BEND, 0))
    b.rbox(name + "_tip", (x - 1.0 + EPS, EAR_BEND - 0.1, -0.55 + EPS), (x + 1.0 - EPS, 7.9, 0.55 - EPS), "t_fur", 0.45,
           top=False, bottom=False, rotation=rot)
    b.rbox(name + "_cap", (x - 0.78, 7.75, -0.47), (x + 0.78, 8.55, 0.47), "t_fur", 0.4, bottom=False, rotation=rot)
    b.box(name + "_inner_tip", (x - 0.55, EAR_BEND - 0.1, -0.6 - EPS), (x + 0.55, 7.75, -0.55 + EPS), "t_pink",
          rotation=rot)
    b.box(name + "_inner_top", (x - 0.38, 7.75 - EPS, -0.52), (x + 0.38, 8.15, -0.47 + EPS), "t_pink", rotation=rot)
    # a fluffy root in the grass
    b.rbox(name + "_root", (x - 1.25, 0, -0.65), (x + 1.25, 0.55, 0.65), "t_fur", 0.22, bottom=False)


def rabbit_watch():
    b = Build()
    for s in (-1, 1):
        _ear(b, f"ear_{'l' if s < 0 else 'r'}", s)
    # the watch: a gold case, a white face with hour marks, a crown and a ring on top
    cyl(b, "case", (0, WATCH_Y), WATCH_R, -0.32, 0.32, "t_gold", axis="z")
    cyl(b, "bezel", (0, WATCH_Y), WATCH_R - 0.12, -0.38, -0.32, "t_gold", axis="z")
    cyl(b, "face", (0, WATCH_Y), WATCH_R - 0.38, -0.42, -0.38, "t_white", axis="z")
    cyl(b, "back", (0, WATCH_Y), WATCH_R - 0.5, 0.32, 0.4, "t_gold", axis="z")
    for k in range(12):
        ang = math.radians(90 - k * 30)
        rr = 1.7
        x, y = -rr * math.cos(ang), WATCH_Y + rr * math.sin(ang)    # front view: clockwise towards -x
        big = k % 3 == 0
        h = 0.17 if big else 0.08
        b.box(f"mark_{k}", (x - h, y - h, -0.45), (x + h, y + h, -0.42), "t_cobalt" if big else "t_ink")
    cyl(b, "crown_neck", (0, 0), 0.2, WATCH_Y + WATCH_R - 0.05, WATCH_Y + WATCH_R + 0.25, "t_gold", sides=8)
    cyl(b, "crown", (0, 0), 0.36, WATCH_Y + WATCH_R + 0.25, WATCH_Y + WATCH_R + 0.6, "t_gold", sides=8)
    ring_y = WATCH_Y + WATCH_R + 1.0
    arc_xy(b, "ring", (0, ring_y), 0.38, 0, 337.5, 0.07, -0.07, 0.07, "t_gold")
    # the chain swagged from the ring to each ear (links alternately wide and narrow)
    for s in (-1, 1):
        x0, y0 = s * 0.4, ring_y
        x1, y1 = s * 4.75, ring_y - 4.35 * math.tan(math.radians(22.5))
        n = 9
        for k in range(n):
            t = (k + 0.5) / n
            x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
            wide = k % 2 == 0
            hl, hw, hd = (0.3, 0.09, 0.03) if wide else (0.26, 0.04, 0.08)
            b.box(f"chain_{s}_{k}", (x - hl, y - hw, -hd), (x + hl, y + hw, hd), "t_gold",
                  rotation=("z", -22.5 * s, (x, y, 0)))
    return b


def rabbit_watch_hands():
    """The watch's red hands at ten past twelve and a gold pin (they tick round about z)."""
    b = Build()
    y = WATCH_Y
    bar_xy(b, "hour", (0, y + 0.5), 90, 1.0, 0.1, -0.5, -0.45, "t_cherry")
    d = 1.45
    ang = math.radians(157.5)
    bar_xy(b, "minute", (math.cos(ang) * d / 2, y + math.sin(ang) * d / 2), 157.5, d, 0.07, -0.55, -0.5, "t_cherry")
    cyl(b, "pin", (0, y), 0.16, -0.6, -0.42, "t_gold", axis="z", sides=8)
    return b


# ------------------------------------------------------------------------------------------------
# 강가 모래성·양동이·삽 (by the dragon's sandbox): base y = 0 is the sand's surface for the castle and the spade
# ------------------------------------------------------------------------------------------------

def _crenels(b, name, cx, cz, r, y, h, count, tex):
    """Little square merlons round a tower top."""
    for k in range(count):
        ang = math.radians(k * 360 / count + 22.5 / 2)
        x, z = cx + r * math.cos(ang), cz + r * math.sin(ang)
        b.box(f"{name}_{k}", (x - 0.13, y + EPS * (k % 2), z - 0.13), (x + 0.13, y + h, z + 0.13), tex)


def sand_castle():
    b = Build()
    b.rbox("mound", (-1.95, 0, -1.95), (1.95, 0.3, 1.95), "t_sand", 0.25, bottom=False)
    # the keep: a bucket-moulded drum with ridges, merlons, a toothpick flag
    cyl(b, "keep_0", (0, 0.2), 1.05, 0.3, 1.0, "t_sand")
    cyl(b, "keep_1", (0, 0.2), 0.97, 1.0, 1.75, "t_sand")
    cyl(b, "keep_2", (0, 0.2), 0.9, 1.75, 2.4, "t_sand")
    cyl(b, "keep_ridge", (0, 0.2), 1.0, 1.7, 1.8, "t_sand")
    _crenels(b, "keep_crenel", 0, 0.2, 0.72, 2.4, 0.3, 8, "t_sand")
    b.box("pole", (-0.04, 2.4, 0.16), (0.04, 3.9, 0.24), "t_wood")
    b.box("flag", (0.04, 3.35, 0.18), (0.75, 3.85, 0.22), "t_cherry")
    b.box("flag_tip", (0.74, 3.48, 0.185), (0.95, 3.72, 0.215), "t_cherry")
    # four corner towers with cone roofs, walls between them, a gate in front
    for sx in (-1, 1):
        for sz in (-1, 1):
            cx, cz = sx * 1.4, sz * 1.4
            cyl(b, f"tower_{sx}_{sz}", (cx, cz), 0.52, 0.3, 1.45, "t_sand")
            cyl(b, f"tower_rim_{sx}_{sz}", (cx, cz), 0.6, 1.45, 1.6, "t_sand")
            cyl(b, f"tower_cone0_{sx}_{sz}", (cx, cz), 0.42, 1.6, 1.85, "t_sand", sides=8)
            cyl(b, f"tower_cone1_{sx}_{sz}", (cx, cz), 0.26, 1.85, 2.05, "t_sand", sides=8)
            cyl(b, f"tower_cone2_{sx}_{sz}", (cx, cz), 0.12, 2.05, 2.25, "t_sand", sides=8)
    for name, frm, to in (("wall_front", (-1.0, 0.3, -1.6), (1.0, 1.0, -1.2)), ("wall_back", (-1.0, 0.3, 1.2), (1.0, 1.0, 1.6))):
        b.box(name, frm, to, "t_sand")
        for k in range(3):
            x = -0.6 + k * 0.6
            b.box(f"{name}_crenel_{k}", (x - 0.14, 1.0, frm[2] + 0.04), (x + 0.14, 1.25, to[2] - 0.04), "t_sand")
    b.mirror("wall_side", (-1.6, 0.3 + EPS, -1.0), (-1.2, 1.0 - EPS, 1.0), "t_sand")
    for k in range(3):
        z = -0.6 + k * 0.6
        b.mirror(f"wall_side_crenel_{k}", (-1.56, 1.0 + EPS, z - 0.14), (-1.24, 1.25, z + 0.14), "t_sand")
    b.box("gate", (-0.3, 0.3, -1.62), (0.3, 0.85, -1.6), "t_cocoa")
    b.box("gate_top", (-0.18, 0.85, -1.62), (0.18, 0.95, -1.6), "t_cocoa")
    # shells and a starfish stuck in the sand
    for n, (x, y, z, tex) in enumerate(((-0.65, 0.55, -1.6, "t_pink"), (0.65, 0.6, -1.6, "t_rose"),
                                        (0.0, 1.35, -0.75, "t_pink"))):
        b.box(f"shell_{n}", (x - 0.1, y - 0.08, z - 0.03), (x + 0.1, y + 0.08, z), tex)
    glyph(b, "starfish", "star", "+y", (1.05, 0, -2.45), 0.09, 0.05, "t_orange")
    return b


def sand_bucket():
    b = Build()
    tiers = ((0.0, 0.5, 1.22), (0.5, 1.0, 1.3), (1.0, 1.55, 1.38))
    for n, (y0, y1, r) in enumerate(tiers):
        cyl(b, f"body_{n}", (0, 0), r, y0, y1, "t_sky")
    cyl(b, "base_band", (0, 0), 1.26, 0.08, 0.24, "t_white")
    tube(b, "rim", (0, 0), 1.52, 1.55, 1.92, 0.2, "t_white")
    # sand heaped in it
    cyl(b, "sand", (0, 0), 1.36, 1.5, 1.82, "t_sand")
    cyl(b, "sand_heap", (0.12, -0.05), 0.95, 1.82, 2.12, "t_sand")
    cyl(b, "sand_heap_top", (0.18, -0.1), 0.5, 2.12, 2.32, "t_sand", sides=8)
    # the yellow handle and its hinges
    arc_xy(b, "handle", (0, 1.78), 1.5, 0, 180, 0.07, -0.07, 0.07, "t_lemon")
    for s in (-1, 1):
        x0, x1 = sorted((s * 1.5, s * 1.62))
        cyl(b, f"hinge_{s}", (1.78, 0), 0.14, x0, x1, "t_lemon", axis="x", sides=8)
    # a white starfish moulded on the front
    ap = 1.3 * math.cos(math.pi / 16)
    glyph(b, "star", "star", "-z", (0, 0.75, -ap), 0.09, 0.03, "t_white")
    # a little spilled sand beside it
    cyl(b, "spill_0", (-1.9, 1.75), 0.7, 0, 0.2, "t_sand")
    cyl(b, "spill_0_top", (-1.85, 1.7), 0.42, 0.2, 0.36, "t_sand", sides=8)
    cyl(b, "spill_1", (-0.75, 2.35), 0.42, 0, 0.14, "t_sand", sides=8)
    return b


def sand_spade():
    b = Build()
    # the sand pushed up round the blade
    cyl(b, "heap", (0, 0), 0.8, 0, 0.16, "t_sand")
    cyl(b, "heap_top", (0, 0), 0.5, 0.16, 0.28, "t_sand", sides=8)
    # the yellow blade, half buried, a ridge down its middle, its shoulders and socket
    b.box("blade", (-0.7, 0.04, -0.07), (0.7, 0.95, 0.07), "t_lemon")
    b.box("ridge", (-0.1, 0.06, -0.12), (0.1, 0.95, 0.12), "t_lemon")
    b.box("shoulder", (-0.5, 0.95, -0.07 + EPS), (0.5, 1.12, 0.07 - EPS), "t_lemon")
    cyl(b, "socket", (0, 0), 0.2, 1.05, 1.5, "t_lemon", sides=8)
    # the red shaft, a white band, the D grip
    cyl(b, "shaft", (0, 0), 0.13, 1.45, 3.55, "t_cherry", sides=8)
    cyl(b, "band", (0, 0), 0.16, 3.25, 3.4, "t_white", sides=8)
    for s in (-1, 1):
        bar_xy(b, f"grip_side_{s}", (s * 0.2, 3.77), 90 - 45 * s, 0.66, 0.07, -0.08 - EPS * (s > 0),
               0.08 + EPS * (s > 0), "t_cherry")
    cyl(b, "grip", (4.05, 0), 0.13, -0.5, 0.5, "t_cherry", axis="x", sides=8)
    return b


# ------------------------------------------------------------------------------------------------
# 스펀지 덤불 (sponge bush): a squashed round bush of soft sponge on the lane verges, pink blossoms on top
# ------------------------------------------------------------------------------------------------

def _blossom(b, name, x, y, z, turn):
    """A little pink flower lying on a flat top at height y: five round rose petals round a butter middle."""
    for k in range(5):
        ang = math.radians(turn + k * 72)
        px, pz = x + 0.22 * math.cos(ang), z + 0.22 * math.sin(ang)
        lift = EPS * k
        cyl(b, f"{name}_petal_{k}", (px, pz), 0.2, y + lift, y + 0.08 + lift, ("t_rose", "t_pink")[k % 2], sides=8)
    cyl(b, f"{name}_mid", (x, z), 0.13, y + 5 * EPS, y + 0.13, "t_butter", sides=8)


def _sponge_dome(b, name, cx, cz, cy, rx, ry, step, bottom=0.0, damp_below=0.0, lift=0.0):
    """A squashed ball of sponge (`rx` across, `ry` up, its middle at `cy`) cut flat at `bottom`: round 16-gon slices
    `step` high, a soft stepped dome like the voxel ball it replaces. Slices up to `damp_below` are the damp underside.
    `lift` makes the first slice that much taller, so the slice planes stay off another dome's. Returns every slice's
    (top, radius), bottom first: the terraces the flowers sit on."""
    out = []
    y0, k, top = bottom, 0, cy + ry
    while y0 < top - 0.05:
        y1 = min(top, y0 + step + (lift if k == 0 else 0))
        mid = (y0 + y1) / 2 - cy
        r = rx * math.sqrt(max(0.0, 1 - (mid / ry) ** 2)) * 1.04
        tex = "t_sponge_damp" if y1 <= damp_below + 1e-6 else "t_sponge"
        cyl(b, f"{name}_{k}", (cx, cz), r, y0 + (EPS if k else 0), y1, tex, sides=16)
        out.append((y1, r))
        y0, k = y1, k + 1
    return out


# the bush's flowers on its top terraces: (terrace from the top, angle about y with 0 = +x and 270 = the front),
# mirror symmetric (angle a and 180 - a) so the bush is the same in the mirrored quarters
SPONGE_FLOWERS = ((0, 0), (1, 270), (1, 30), (1, 150), (2, 90), (2, 210), (2, 330), (3, 0), (3, 180))
SPONGE_BUD = (0, -2.15)


def sponge_bush():
    """The verge's sponge bush (the block bush: a squashed ball of sponge on a wet-sponge underside, pink carpet
    blossoms on top): a soft stepped dome of porous sponge, darker damp foam where it sits on the mat, a little bud
    of sponge at its front (the map turns the bushes, so they differ), pink five-petal flowers on the top terraces."""
    b = Build()
    main = _sponge_dome(b, "body", 0, 0, 1.9, 2.45, 2.1, 0.3, damp_below=0.91)
    bx, bz = SPONGE_BUD
    _sponge_dome(b, "bud", bx, bz, 0.5, 0.8, 0.75, 0.25, bottom=2 * EPS, damp_below=0.27, lift=0.07)
    # each flower tucked against the step above its terrace (it overhangs a narrow terrace less)
    for n, (k, ang) in enumerate(SPONGE_FLOWERS):
        y, _ = main[-1 - k]
        rr = main[-k][1] + 0.25 if k else 0.0
        _blossom(b, f"flower_{n}", rr * math.cos(math.radians(ang)), y + DECAL_LIFT, rr * math.sin(math.radians(ang)),
                 n * 29)
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
    "cotton_tree": (cotton_tree, 3, 4.5),
    "crayon_box": (crayon_box, 3, 4.5),
    "marble_jar": (marble_jar, 2, 3.0),
    "block_stack": (block_stack, 4, 4.0),
    "toy_box": (toy_box, 5, 4.5),
    "toy_box_mirror": (toy_box_mirror, 5, 4.5),
    "toy_box_pink": (toy_box_pink, 3, 4.5),
    "toy_box_sky": (toy_box_sky, 3, 4.5),
    "toy_box_yellow": (toy_box_yellow, 3, 4.5),
    "jelly": (jelly, 2, 2.5),
    "cheese": (cheese, 3, 4.5),
    "cheese_key": (cheese_key, 3, 4.5),
    "rabbit_watch": (rabbit_watch, 5, 5.0),
    "rabbit_watch_hands": (rabbit_watch_hands, 2, WATCH_Y),
    "sand_castle": (sand_castle, 2, 2.0),
    "sand_bucket": (sand_bucket, 2, 2.0),
    "sand_spade": (sand_spade, 2, 2.5),
    "sponge_bush": (sponge_bush, 2, 2.5),
}

# made but not on the map yet: moved into PROPS (and the Java PropModels table) when the map places them
PENDING = {}

DISPLAY = {**PROPS, **PENDING}

def _model(fn, scale, centre, name):
    m = fn().centred(name, scale, centre)
    m.check_turned = True       # turned bars must not z-fight either (Model._turned_overlaps)
    return m


ALL = {f"prop/{k}": (lambda fn, sc, c, k: lambda: _model(fn, sc, c, f"prop/{k}"))(fn, sc, c, k)
       for k, (fn, sc, c) in DISPLAY.items()}
