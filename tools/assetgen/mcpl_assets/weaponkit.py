"""
Shared kit for the ability weapons (tools/assetgen/models/weapons.py): an upright weapon builder, common parts
(grip, pommel, collar, guard, blade, ring, rivets, slats, emissive accents) and ONE display preset that every weapon uses,
so all of them sit the same way in the hand, in the inventory, on the ground and in item frames.

Weapon space (model units, 16 = one block at display scale 1):
  - the weapon stands UPRIGHT along +y: pommel at the bottom, tip at the top
  - x = the weapon's broad plane (blade width, axe/pick head, gun profile): edge / business side towards +x
    (ORIENTATION RULE: the cutting edge, striking face, pick point, hook, trigger guard... is always on +x; the tip /
    head is at the top, the hand at GRIP_Y. The preset turns +x to face forward/down in hand, like a real swing and
    like a vanilla axe's blade, and to the upper-left in the inventory icon, like the vanilla axe / hoe sprites)
  - z = thickness (keep it thin: 1-3 units for blades, up to 5 for heads); the two z sides are the two flats
  - x and z are relative to the centre line (model x = 8 + x, z = 8 + z), y is the model's own y
  - the HAND holds (0, GRIP_Y, 0): put the middle of the grip there
  - fit in x -7..7, y -4..28, z -3..3 (the vanilla model limit is -16..32 per axis; the preset scales to fit)

The preset (display()) is the vanilla handheld sword pose (item/handheld.json) turned so the upright weapon lies
where the sword sprite's diagonal lies, with the hand on the grip: firstperson/thirdperson right and left hand. GUI,
ground and item frame views are fitted to the model's own bounds, so a long spear and a short knife both fill the
slot.
"""

import math

import numpy as np

from .model import Model

GRIP_Y = 1.5            # where the hand closes on the grip (model y, centre line)
LENGTH = 26.0           # reference length (pommel to tip) the hand poses are tuned for; longer weapons shrink a bit
HAND_SCALE = 0.85       # a LENGTH-long weapon in hand is ~1.1x a vanilla sword sprite (diagonal ~19.8 units)

# vanilla item/handheld.json (sword pose) and item/generated.json, rotation XYZ in degrees
HANDHELD = {
    "thirdperson_righthand": ((0, -90, 55), (0, 4.0, 0.5), 0.85),
    "firstperson_righthand": ((0, -90, 25), (1.13, 3.2, 1.13), 0.68),
}
# The inventory looks at a model from its south side (+z, x to the right): the sword sprite's tip is at (15, 15).
SPRITE_GRIP = (3.5, 3.5, 8.0)     # where the hand sits on the vanilla sword sprite (model units)
DIAGONAL = -45                    # an upright weapon turned this much about z lies on the sprite's diagonal


def rot_xyz(rx, ry, rz):
    """Rotation matrix of a display transform: JOML rotationXYZ = Rx * Ry * Rz (degrees)."""
    a, b, c = (math.radians(v) for v in (rx, ry, rz))
    x = np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])
    y = np.array([[math.cos(b), 0, math.sin(b)], [0, 1, 0], [-math.sin(b), 0, math.cos(b)]])
    z = np.array([[math.cos(c), -math.sin(c), 0], [math.sin(c), math.cos(c), 0], [0, 0, 1]])
    return x @ y @ z


def euler_xyz(m):
    """The XYZ Euler angles (degrees) of a rotation matrix m = Rx * Ry * Rz (inverse of rot_xyz)."""
    b = math.asin(max(-1.0, min(1.0, m[0][2])))
    if abs(m[0][2]) < 0.999999:
        a = math.atan2(-m[1][2], m[2][2])
        c = math.atan2(-m[0][1], m[0][0])
    else:                                   # gimbal lock: put it all in x
        a = math.atan2(m[2][1], m[1][1])
        c = 0.0
    return tuple(math.degrees(v) for v in (a, b, c))


def business_side(rotation):
    """A display rotation applied after turning the weapon 180 degrees about its own long axis (y), so the +x
    business side ends up where the vanilla sword pose puts a vanilla axe's blade (see ORIENTATION RULE above)."""
    return euler_xyz(rot_xyz(*rotation) @ rot_xyz(0, 180, 0))


def _r(v):
    return [round(float(n), 3) + 0.0 for n in v]


class Weapon:
    """Builds one weapon model in weapon space (see the module comment)."""

    def __init__(self, name):
        self.model = Model(name)
        self.size = 1.0     # hand pose size multiplier (1 = a LENGTH-long weapon is ~1.1x a vanilla sword)

    # --- raw boxes ---------------------------------------------------------------------------------

    def group(self, name):
        self.model.group(name)
        return self

    def box(self, name, frm, to, tex, rotation=None, **kw):
        """A box from (x, y, z) to (x, y, z) in weapon space; rotation = (axis, angle, (x, y, z) origin)."""
        conv = lambda p: (8 + p[0], p[1], 8 + p[2])
        if rotation:
            axis, angle, origin = rotation
            rotation = (axis, angle, conv(origin))
        self.model.box(name, conv(frm), conv(to), tex, rotation=rotation, **kw)
        return self

    def centred(self, name, y0, y1, w, d, tex, x=0.0, z=0.0, **kw):
        """A box centred on (x, z) across, w wide (x) and d deep (z), from y0 to y1."""
        return self.box(name, (x - w / 2, y0, z - d / 2), (x + w / 2, y1, z + d / 2), tex, **kw)

    def both_flats(self, name, frm, to, tex, **kw):
        """A detail on the +z flat and its mirror on the -z flat (frm/to given for the +z side)."""
        self.box(name + "_a", frm, to, tex, **kw)
        self.box(name + "_b", (frm[0], frm[1], -to[2]), (to[0], to[1], -frm[2]), tex, **kw)
        return self

    # --- common parts ------------------------------------------------------------------------------

    def grip(self, y0, y1, w=2.0, d=2.0, tex="w_grip"):
        """The handle the hand holds (keep GRIP_Y inside y0..y1, 6-8 units long)."""
        return self.centred("grip", y0, y1, w, d, tex)

    def pommel(self, y, w=2.75, d=2.75, h=1.5, tex="w_iron", cap=None, cap_glow=False):
        """A pommel block below the grip (top at y), optionally with a cap of another material (cap_glow: magic only)."""
        self.centred("pommel", y - h, y, w, d, tex)
        if cap:
            self.centred("pommel_cap", y - h - 0.5, y - h, w - 1.0, d - 1.0, cap, glow=cap_glow)
        return self

    def collar(self, name, y, w, d, h=1.0, tex="w_iron"):
        """A band round a shaft (a ferrule, a bolster, a ring): just a slightly larger box."""
        return self.centred(name, y, y + h, w, d, tex)

    def guard(self, y, half, h=1.5, d=2.5, tex="w_iron", tips=None, tip_glow=False):
        """A crossguard from x -half to +half, optionally with end tips of another material (1 unit each end)."""
        if tips:
            self.box("guard", (-half + 1, y, -d / 2), (half - 1, y + h, d / 2), tex)
            self.box("guard_tip_l", (-half, y + 0.125, -d / 2 + 0.125), (-half + 1, y + h - 0.125, d / 2 - 0.125),
                     tips, glow=tip_glow)
            self.box("guard_tip_r", (half - 1, y + 0.125, -d / 2 + 0.125), (half, y + h - 0.125, d / 2 - 0.125),
                     tips, glow=tip_glow)
        else:
            self.box("guard", (-half, y, -d / 2), (half, y + h, d / 2), tex)
        return self

    def blade(self, name, y0, y1, spine, edge, thick, tex="w_steel", steps=(), edge_tex=None, edge_w=0.75,
              edge_glow=False, tip=None):
        """
        A straight blade from y0 to y1 between x = spine and x = edge (edge > spine), `thick` deep. The honed edge is
        a separate thinner strip `edge_w` wide on the edge side (edge_tex; edge_glow only for an elemental blade: fire,
        lightning, magic). steps: (y, cut) pairs that cut the edge side back by `cut` units from y upwards (a stepped
        taper towards the tip).
        tip: (height, x) a final narrowing point above y1 centred on x.
        """
        cuts = [(y0, 0.0)] + sorted(steps) + [(y1, None)]
        for n in range(len(cuts) - 1):
            ya, cut = cuts[n]
            yb = cuts[n + 1][0]
            e = edge - cut
            self.box(f"{name}_{n}", (spine, ya, -thick / 2), (e - edge_w, yb, thick / 2), tex)
            if edge_tex:
                self.box(f"{name}_edge_{n}", (e - edge_w, ya, -thick / 2 + 0.25), (e, yb, thick / 2 - 0.25), edge_tex,
                         glow=edge_glow)
        if tip:
            h, x = tip
            w = max(0.5, (edge - cuts[-2][1] - spine) / 2)
            self.box(f"{name}_tip", (x - w / 2, y1, -thick / 2 + 0.25), (x + w / 2, y1 + h, thick / 2 - 0.25),
                     edge_tex or tex, glow=edge_glow)
        return self

    def edge_strip(self, name, frm, to, tex, glow=True, **kw):
        """An emissive strip (a hot face, a lamp, a spark): glow boxes render at full light, unshaded. Only where the
        ability's element is light (fire, lightning, a lamp, magic)."""
        return self.box(name, frm, to, tex, glow=glow, **kw)

    def ring(self, name, cx, cy, outer, bar, z, tex="w_steel"):
        """A square ring in the x/y plane (a lanyard loop, a handcuff, a chain link): four bars `bar` thick, `outer`
        across, centred on (cx, cy), z = half its depth."""
        h = outer / 2
        self.box(name + "_b", (cx - h, cy - h, -z), (cx + h, cy - h + bar, z), tex)
        self.box(name + "_t", (cx - h, cy + h - bar, -z), (cx + h, cy + h, z), tex)
        self.box(name + "_l", (cx - h, cy - h + bar, -z), (cx - h + bar, cy + h - bar, z), tex)
        self.box(name + "_r", (cx + h - bar, cy - h + bar, -z), (cx + h, cy + h - bar, z), tex)
        return self

    def rivets(self, name, points, z, tex="w_brass", s=0.5):
        """Small studs on both flats at (x, y) points, `z` = the flat's half depth."""
        for n, (x, y) in enumerate(points):
            self.both_flats(f"{name}{n}", (x - s / 2, y - s / 2, z), (x + s / 2, y + s / 2, z + 0.25), tex)
        return self

    def vents(self, name, x0, x1, y0, count, z, tex="w_iron_dark", pitch=1.0, **kw):
        """Horizontal slats / grooves on both flats (glow=True with an emissive texture only for a fire weapon)."""
        for n in range(count):
            y = y0 + n * pitch
            self.both_flats(f"{name}{n}", (x0, y, z), (x1, y + pitch / 2, z + 0.25), tex, **kw)
        return self

    def taper(self, name, y0, y1, w0, w1, d0, d1, tex, steps=3, x=0.0, **kw):
        """A shaft / head that narrows (or widens) from w0 x d0 at y0 to w1 x d1 at y1 in `steps` stacked boxes."""
        h = (y1 - y0) / steps
        for n in range(steps):
            t = n / max(1, steps - 1)
            self.centred(f"{name}_{n}", y0 + n * h, y0 + (n + 1) * h, w0 + (w1 - w0) * t, d0 + (d1 - d0) * t, tex,
                         x=x, **kw)
        return self

    def bar(self, name, start, angle, length, width, depth, tex, z=0.0, offset=0.0, **kw):
        """
        A straight bar in the x/y plane from `start` (x, y) heading `angle` degrees (counter-clockwise from +x, any
        multiple of 22.5), `width` across and `depth` deep (centred on z). offset shifts it sideways (+ = to the
        bar's left). Returns the end point, so bars chain into curves (horns, claws, a scythe blade).
        """
        assert angle % 22.5 == 0, f"{name}: bar angles go in 22.5 degree steps"
        a = (angle + 180) % 360 - 180
        base = round(a / 90) * 90
        rem = a - base
        rad = math.radians(a)
        sx = start[0] - math.sin(rad) * offset      # shifted along the bar's left normal
        sy = start[1] + math.cos(rad) * offset
        hw = width / 2
        if base == 0:
            frm, to = (sx, sy - hw), (sx + length, sy + hw)
        elif base in (180, -180):
            frm, to = (sx - length, sy - hw), (sx, sy + hw)
        elif base == 90:
            frm, to = (sx - hw, sy), (sx + hw, sy + length)
        else:
            frm, to = (sx - hw, sy - length), (sx + hw, sy)
        rotation = ("z", rem, (sx, sy, z)) if rem else None
        self.box(name, (frm[0], frm[1], z - depth / 2), (to[0], to[1], z + depth / 2), tex, rotation=rotation, **kw)
        return start[0] + math.cos(rad) * length, start[1] + math.sin(rad) * length

    def curve(self, name, start, path, width, depth, tex, z=0.0, offset=0.0, overlap=0.0, **kw):
        """
        Bars chained along `path` = [(angle, length), ...] from `start`; width may be one number or one per bar (a
        tapering horn or claw). overlap starts each later bar that much further back so the bends close.
        Returns the list of joint points (start ... end).
        """
        points = [start]
        p = start
        for n, (angle, length) in enumerate(path):
            w = width[n] if isinstance(width, (list, tuple)) else width
            back = overlap if n else 0.0
            rad = math.radians(angle)
            q = (p[0] - math.cos(rad) * back, p[1] - math.sin(rad) * back)
            p = self.bar(f"{name}_{n}", q, angle, length + back, w, depth, tex, z=z, offset=offset, **kw)
            points.append(p)
        return points

    def disc(self, name, cx, cy, r, z0, z1, tex, row=1.0, **kw):
        """A round plate in the x/y plane (a clock dial, a coin, a bobber's side): rows `row` tall, each as wide as
        the circle of radius r at that height, from z0 to z1 (relative to the centre line)."""
        n = max(1, round(2 * r / row))
        for i in range(n):
            ya = cy - r + i * row
            mid = ya + row / 2 - cy
            half = math.sqrt(max(0.0, r * r - mid * mid))
            half = round(half * 4) / 4
            if half > 0:
                self.box(f"{name}{i}", (cx - half, ya, z0), (cx + half, ya + row, z1), tex, **kw)
        return self

    def octagon(self, name, cx, cy, r, bar, z, tex, **kw):
        """An octagonal ring in the x/y plane (a halo, an orbit, a dial rim): eight bars `bar` wide whose outer
        edges are r from (cx, cy), z = half its depth. The four diagonal bars are turned 45 degrees about z."""
        a = r * math.tan(math.radians(22.5)) + bar * 0.2     # half a side, a little overlap at the corners
        c = r - bar / 2
        for k in range(8):
            t = math.radians(45 * k)
            x, y = cx + c * math.cos(t), cy + c * math.sin(t)
            if k % 2 == 0:
                if k in (0, 4):
                    self.box(f"{name}{k}", (x - bar / 2, y - a, -z), (x + bar / 2, y + a, z), tex, **kw)
                else:
                    self.box(f"{name}{k}", (x - a, y - bar / 2, -z), (x + a, y + bar / 2, z), tex, **kw)
            else:
                angle = -45 if k in (1, 5) else 45
                self.box(f"{name}{k}", (x - a, y - bar / 2, -z), (x + a, y + bar / 2, z), tex,
                         rotation=("z", angle, (x, y, 0)), **kw)
    def strut(self, name, p0, p1, w, d, tex, z=0.0, extend=0.0, **kw):
        """
        A bar from (x, y) point p0 to p1 in the weapon's broad plane, `w` wide and `d` deep, centred on depth z. Item
        models only turn elements in 22.5 degree steps, so the bar's direction snaps to the nearest step (any of
        0..180); `extend` lengthens both ends (overlap at the joints of a bent chain: a bow limb, a sickle, a bolt).
        Returns the snapped angle from +y (degrees, positive towards +x).
        """
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        length = math.hypot(dx, dy) + 2 * extend
        mx, my = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
        a = (math.degrees(math.atan2(dx, dy)) + 90) % 180 - 90
        a = round(a / 22.5) * 22.5
        if abs(a) <= 45:
            frm, to, angle = (mx - w / 2, my - length / 2, z - d / 2), (mx + w / 2, my + length / 2, z + d / 2), -a
        else:
            b = 90 - a
            b = b - 180 if b > 90 else b
            frm, to, angle = (mx - length / 2, my - w / 2, z - d / 2), (mx + length / 2, my + w / 2, z + d / 2), b
        rotation = ("z", angle, (mx, my, z)) if angle else None
        self.box(name, frm, to, tex, rotation=rotation, **kw)
        return a

    def polyline(self, name, points, w, d, tex, z=0.0, extend=None, **kw):
        """Struts through consecutive (x, y) points (a curved limb, blade or bolt); joints overlap by w/2."""
        for n in range(len(points) - 1):
            self.strut(f"{name}{n}", points[n], points[n + 1], w, d, tex, z=z,
                       extend=w / 2 if extend is None else extend, **kw)
        return self

    # --- output ------------------------------------------------------------------------------------

    def finish(self):
        """Sets the shared display preset and returns the model."""
        self.model.display = display(self.model, self.size)
        return self.model


def _bounds(model):
    pts = []
    for e in model.elements:
        f, t = e["from"], e["to"]
        corners = np.array([[x, y, z] for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])], float)
        if e["rotation"]:
            axis, angle, origin = e["rotation"]
            r = rot_xyz(*[angle if a == axis else 0 for a in "xyz"])
            corners = (corners - origin) @ r.T + origin
        pts.append(corners)
    return np.concatenate(pts)


def _fit(model, rotation, extent, centre_to=(0, 0, 0), scale_cap=1.6):
    """Scale + translation that rotate the model and fit its projected (x, y) bounds in `extent` units."""
    rotation = business_side(rotation)
    r = rot_xyz(*rotation)
    p = (_bounds(model) - 8) @ r.T
    lo, hi = p.min(axis=0), p.max(axis=0)
    s = min(scale_cap, extent / max(hi[0] - lo[0], hi[1] - lo[1]))
    mid = (lo + hi) / 2 * s
    return {"rotation": _r(rotation), "translation": _r(np.array(centre_to) - mid), "scale": _r([s, s, s])}


GUI_ROTATION = (0, -30, DIAGONAL)     # the diagonal icon, turned a little to show the weapon's depth
FIXED_ROTATION = (0, 180, DIAGONAL)   # item frames show the back of the icon
GROUND_ROTATION = (0, 0, DIAGONAL)


def display(model, size=1.0):
    """The shared display preset (see the module comment); size scales the hand poses only."""
    span = _bounds(model)
    length = max(LENGTH, span[:, 1].max() - span[:, 1].min())
    k = size * HAND_SCALE * LENGTH / length
    out = {}
    grip = np.array([8, GRIP_Y, 8], float)
    for ctx, (hrot, htr, hs) in HANDHELD.items():
        rot = business_side((hrot[0], hrot[1], hrot[2] + DIAGONAL))
        s = hs * k
        target = np.array(htr) + rot_xyz(*hrot) @ ((np.array(SPRITE_GRIP) - 8) * hs)
        t = target - rot_xyz(*rot) @ ((grip - 8) * s)
        out[ctx] = {"rotation": _r(rot), "translation": _r(t), "scale": _r([s, s, s])}
        # left hand: the vanilla convention (handheld.json) mirrors y and z rotation, same translation
        left = ctx.replace("right", "left")
        out[left] = {"rotation": _r((rot[0], -rot[1], -rot[2])), "translation": _r(t), "scale": _r([s, s, s])}
    out["gui"] = _fit(model, GUI_ROTATION, 15.5)
    out["fixed"] = _fit(model, FIXED_ROTATION, 15.0)
    ground = _fit(model, GROUND_ROTATION, 9.0)
    ground["translation"][1] += 2.0
    out["ground"] = ground
    return out


# ------------------------------------------------------------------------------------------------
# Preview placement (build.py renders the weapons the way the game shows them)
# ------------------------------------------------------------------------------------------------

def display_matrix(transform, left=False):
    """4x4: model units -> blocks for one display context, around the model centre (8, 8, 8), as the game does."""
    rx, ry, rz = transform["rotation"]
    tx, ty, tz = transform["translation"]
    if left:
        # what the client does with a left hand transform (ItemTransform#apply): mirror x, negate y/z rotation
        ry, rz, tx = -ry, -rz, -tx
    m = np.eye(4)
    m[:3, :3] = rot_xyz(rx, ry, rz) * np.array(transform["scale"])
    m[:3, 3] = np.array([tx, ty, tz]) / 16
    centre = np.eye(4)
    centre[:3, 3] = -0.5
    unit = np.diag([1 / 16, 1 / 16, 1 / 16, 1])
    return m @ centre @ unit


def translate(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def rotate(axis, degrees):
    m = np.eye(4)
    m[:3, :3] = rot_xyz(*[degrees if a == axis else 0 for a in "xyz"])
    return m


def scale(x, y, z):
    return np.diag([x, y, z, 1])
