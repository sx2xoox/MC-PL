"""
초능력 공성전 towers (docs/siege.md §3), cyberpunk megacity edition:
  방화벽 게이트 (front)  a fortified firewall gate: twin armoured pylons, an energy barrier, holo sign, cable bundles
  데이터 허브 (middle)   a data-centre spire: four server-rack stacks round a glowing data column, cooling fins,
                         two spinning holographic data rings, a floating data core and an antenna
  메인프레임 (core)      the HQ mainframe: a tiered monolith with light conduits running down to the base, a giant
                         floating processor crystal (the heart) with a spinning halo, satellite dishes and a spire

Everything is designed in BLOCK coordinates relative to the tower base: x across (left -, right +), y up from the
ground, z depth with the FRONT facing -z (north; the game turns it towards the attackers).

A Minecraft model only spans -16..32 units per axis, so a tower is made of several models, each shown by its own item
display (ModelTowerLook.SPECS, kept in sync with PARTS below):
  - body slabs at display scale 4 (16 units = 4 blocks, 64px textures = 16 texels per block like vanilla): a slab covers
    x/z -6..6 and 12 blocks of height; slab k starts 12k blocks above the ground. Boxes crossing a slab border are
    split, so a tower is designed as one piece. Damage stages 0 intact, 1 cracked, 2 heavily damaged, 3 ruin; a part
    with fewer stages collapses when the tower falls.
  - moving parts (barrier, heart, halo, rings, data core) are separate models centred on (8, 8, 8)
  - the hex energy shield, one per tower size, at display scale 8.
Team-coloured parts (neon, racks, data, holograms, the crystal, the barrier) are tinted with the item's dyed colour
(minecraft:dye tint), so one model serves both teams.
"""

import math

from mcpl_assets.model import Model

STAGES = 4
BODY_SCALE = 4          # display scale of body slabs and moving parts
SHIELD_SCALE = 8        # display scale of the shields
SLAB = 12               # blocks of height per body slab
SHIELD_CENTRE = 11.0    # shield models are centred this many blocks above the ground

# part -> stages (0 = one model, not staged), centre height in blocks, display scale, motion
# (kept in sync with ModelTowerLook.SPECS)
PARTS = {
    "front": [
        ("body", 4, 6.0, BODY_SCALE, "none"),
        ("barrier", 3, 6.0, BODY_SCALE, "barrier"),
        ("shield", 0, SHIELD_CENTRE, SHIELD_SCALE, "shield"),
    ],
    "middle": [
        ("base", 4, 6.0, BODY_SCALE, "none"),
        ("top", 3, 18.0, BODY_SCALE, "none"),
        ("ring", 0, 6.5, BODY_SCALE, "ring"),
        ("ring", 0, 12.2, BODY_SCALE * 0.8, "ring_reverse"),
        ("core", 0, 15.3, BODY_SCALE, "core"),
        ("shield", 0, SHIELD_CENTRE, SHIELD_SCALE, "shield"),
    ],
    "core": [
        ("base", 4, 6.0, BODY_SCALE, "none"),
        ("top", 3, 18.0, BODY_SCALE, "none"),
        ("heart", 0, 11.0, BODY_SCALE * 1.25, "heart"),
        ("halo", 0, 11.0, BODY_SCALE, "halo"),
        ("halo", 0, 11.0, BODY_SCALE * 1.15, "halo_reverse"),
        ("shield", 0, SHIELD_CENTRE, SHIELD_SCALE, "shield"),
    ],
}


# ------------------------------------------------------------------------------------------------
# Block-space builder
# ------------------------------------------------------------------------------------------------

class Build:
    """Boxes in block coordinates; `slabs()` cuts them into body slab models, `centred()` into one centred model."""

    def __init__(self):
        self.boxes = []

    def box(self, name, frm, to, tex, **kw):
        self.boxes.append((name, tuple(float(v) for v in frm), tuple(float(v) for v in to), tex, kw))

    def mirror(self, name, frm, to, tex, **kw):
        """The box and its mirror image across x = 0 (rotations flip their angle)."""
        self.box(name + "_l", frm, to, tex, **kw)
        kw2 = dict(kw)
        if kw.get("rotation"):
            axis, angle, (ox, oy, oz) = kw["rotation"]
            kw2["rotation"] = (axis, angle if axis == "x" else -angle, (-ox, oy, oz))
        if kw.get("faces"):
            swap = {"east": "west", "west": "east"}
            kw2["faces"] = {swap.get(f, f): t for f, t in kw["faces"].items()}
        self.box(name + "_r", (-to[0], frm[1], frm[2]), (-frm[0], to[1], to[2]), tex, **kw2)

    def around(self, name, frm, to, tex, **kw):
        """The box (drawn for the north side) on all four sides, turned about the y axis (no element rotation)."""
        assert not kw.get("rotation"), name
        for k in range(4):
            f, t = _turn(frm, to, k)
            kw2 = dict(kw)
            if kw.get("faces"):
                kw2["faces"] = {_turn_face(face, k): tx for face, tx in kw["faces"].items()}
            self.box(f"{name}_{k}", f, t, tex, **kw2)

    def slabs(self, prefix, count=2):
        """Body slab models: slab k covers y 12k..12k+12 (empty slabs are None)."""
        models = [Model(prefix) for _ in range(count)]
        used = [False] * count
        for name, frm, to, tex, kw in self.boxes:
            if kw.get("rotation"):
                k = min(count - 1, int(frm[1] // SLAB + 1e-6))
                _add(models[k], name, frm, to, tex, kw, k * SLAB, BODY_SCALE)
                used[k] = True
                continue
            for k in range(count):
                lo, hi = k * SLAB, (k + 1) * SLAB
                if to[1] <= lo + 1e-6 or frm[1] >= hi - 1e-6:
                    continue
                f = (frm[0], max(frm[1], lo), frm[2])
                t = (to[0], min(to[1], hi), to[2])
                _add(models[k], name if (f[1], t[1]) == (frm[1], to[1]) else f"{name}_s{k}", f, t, tex, kw,
                     k * SLAB, BODY_SCALE)
                used[k] = True
        return [m if u else None for m, u in zip(models, used)]


def _add(model, name, frm, to, tex, kw, y0, scale):
    """Adds a block-space box to a model whose y = -16 sits at block height y0 (x/z centred on 8)."""
    u = 16 / scale

    def conv(p):
        return (8 + p[0] * u, -16 + (p[1] - y0) * u, 8 + p[2] * u)

    kw = dict(kw)
    if kw.get("rotation"):
        axis, angle, origin = kw["rotation"]
        kw["rotation"] = (axis, angle, conv(origin))
    model.box(name, conv(frm), conv(to), tex, **kw)


def _turn_point(p, k):
    x, y, z = p
    for _ in range(k):
        x, z = -z, x       # north (-z) -> east (+x) -> south -> west
    return (x, y, z)


def _turn(frm, to, k):
    a, b = _turn_point(frm, k), _turn_point(to, k)
    return (min(a[0], b[0]), frm[1], min(a[2], b[2])), (max(a[0], b[0]), to[1], max(a[2], b[2]))


def _turn_face(face, k):
    order = ["north", "east", "south", "west"]
    if face not in order:
        return face
    return order[(order.index(face) + k) % 4]


def _ring(b, name, apothem, y0, y1, thick, tex, sides=8, **kw):
    """A hollow regular octagon of thin plates around the y axis (both faces drawn)."""
    side = 2 * apothem * math.tan(math.pi / 8) + 0.02
    for n in range(sides):
        ang = n * 45
        cx, cz = apothem * math.sin(math.radians(ang)), -apothem * math.cos(math.radians(ang))
        if n % 2 == 0:
            if n in (0, 4):
                b.box(f"{name}_{n}", (cx - side / 2, y0, cz - thick / 2), (cx + side / 2, y1, cz + thick / 2), tex, **kw)
            else:
                b.box(f"{name}_{n}", (cx - thick / 2, y0, cz - side / 2), (cx + thick / 2, y1, cz + side / 2), tex, **kw)
        else:
            angle = -45 if n in (1, 5) else 45
            b.box(f"{name}_{n}", (cx - side / 2, y0, cz - thick / 2), (cx + side / 2, y1, cz + thick / 2), tex,
                  rotation=("y", angle, (cx, (y0 + y1) / 2, cz)), **kw)


def _neon(stage, flicker_from=1, dead_from=3):
    """Neon texture at a stage: steady, flickering, or dead (dark frame)."""
    if stage >= dead_from:
        return "frame"
    return "neon_flicker" if stage >= flicker_from else "neon"


def _hull(stage):
    return ("hull", "hull_cracked", "hull_broken", "hull_broken")[stage]


def _glow(tex):
    return tex in ("neon", "neon_flicker")


def strip(b, name, frm, to, stage, flicker_from=1, dead_from=3, mirror=False, around=False):
    """A team-coloured neon strip (dead at `dead_from`)."""
    tex = _neon(stage, flicker_from, dead_from)
    kw = {"tint": _glow(tex), "glow": _glow(tex)}
    if around:
        b.around(name, frm, to, tex, **kw)
    elif mirror:
        b.mirror(name, frm, to, tex, **kw)
    else:
        b.box(name, frm, to, tex, **kw)


def debris(b, stage, spots):
    """Scrap piles: a few at stage 2, all of them for the ruin."""
    count = {0: 0, 1: 0, 2: 3, 3: len(spots)}[stage]
    for n, (x, z, w, d, h) in enumerate(spots[:count]):
        b.box(f"debris_{n}", (x, 0.03125, z), (x + w, h, z + d), "debris")


def sparks(b, name, frm, to):
    b.box(name, frm, to, "sparks", glow=True)


# ------------------------------------------------------------------------------------------------
# 방화벽 게이트 (front): ~9.5 wide, 5.5 deep, 12 tall
# ------------------------------------------------------------------------------------------------

FRONT_DEBRIS = [(-6, -4.5, 1.5, 1.2, 0.6), (4.6, -3.8, 1.2, 1.5, 0.55), (-1.2, -4.8, 1.8, 1.0, 0.4),
                (-5.8, 2.6, 1.3, 1.5, 0.7), (4.4, 3.0, 1.4, 1.2, 0.5), (1.6, -5.4, 1.0, 1.0, 0.35)]


def front_body(stage):
    b = Build()
    hull = _hull(stage)
    ruin = stage == 3

    # foundation with a team neon lip and hazard edges
    b.box("plinth", (-5, 0, -2.75), (5, 0.5, 2.75), "hull_dark", faces={"north": "hazard", "south": "hazard"})
    strip(b, "plinth_neon", (-4.75, 0.5, -2.75), (4.75, 0.5625, -2.5), stage, flicker_from=2)
    b.box("cable_run", (-4.25, 0.5, 2.0), (-3.25, 0.875, 3.5), "cable")
    b.box("cable_run_r", (3.25, 0.5, 2.0), (4.25, 0.875, 3.5), "cable")

    if ruin:
        # pylon stubs with torn frames, the bridge fallen across the gate, scrap everywhere
        b.box("stub_l", (-4.25, 0.5, -1.75), (-2.5, 3.0, 1.75), hull, faces={"north": "hazard"})
        b.box("stub_l_frame", (-4.0, 3.0, -1.25), (-2.75, 4.5, 1.0), "frame")
        b.box("stub_r", (2.5, 0.5, -1.75), (4.25, 5.0, 1.75), hull)
        b.box("stub_r_frame", (2.75, 5.0, -1.25), (4.0, 6.25, 1.25), "frame")
        b.box("stub_r_tooth", (3.25, 6.25, 0.0), (4.0, 7.0, 1.0), "frame")
        b.box("buttress_l", (-4.75, 0.5, -2.25), (-4.25, 2.25, 2.25), hull)
        b.box("buttress_r", (4.25, 0.5, -2.25), (4.75, 3.0, 2.25), hull)
        b.box("bridge_fallen", (-2.75, 0.5, -3.0), (2.25, 1.75, -1.0), hull,
              faces={"north": "hazard"}, rotation=("y", 22.5, (0, 1, -2)))
        b.box("floor_emitter", (-2.5, 0.5, -0.5), (2.5, 0.75, 0.5), "hull_dark")
        b.box("cable_snake", (-2.0, 0.5, 1.0), (1.5, 0.8, 1.5), "cable", rotation=("y", -22.5, (0, 0.6, 1.25)))
        debris(b, stage, FRONT_DEBRIS)
        return b

    top_l = 10 if stage < 2 else 7.25
    # twin pylons: armoured columns on wide hazard-striped buttresses
    for side, sx in (("l", -1), ("r", 1)):
        x0, x1 = sorted((sx * 2.5, sx * 4.25))
        top = top_l if side == "l" else 10
        b.box(f"pylon_{side}", (x0, 0.5, -1.75), (x1, top, 1.75), hull)
        bx0, bx1 = sorted((sx * 2.375, sx * 4.75))
        b.box(f"buttress_{side}", (bx0, 0.5, -2.25), (bx1, 3.5, 2.25), hull, faces={"north": "hazard"})
        cx0, cx1 = sorted((sx * 2.375, sx * 4.5))
        b.box(f"buttress_cap_{side}", (cx0, 3.5, -2.0), (cx1, 3.875, 2.0), "hull_dark")
        # outer cooling grille
        gx0, gx1 = sorted((sx * 4.25, sx * 4.375))
        b.box(f"grille_{side}", (gx0, 4.25, -1.25), (gx1, min(top, 9.5) - 0.5, 1.25), "grate")
        # inner emitter rail that holds the barrier
        ex0, ex1 = sorted((sx * 2.25, sx * 2.5))
        b.box(f"emitter_{side}", (ex0, 0.75, -0.5), (ex1, min(top, 8.0), 0.5), "hull_dark")
        nx0, nx1 = sorted((sx * 2.125, sx * 2.25))
        strip(b, f"emitter_neon_{side}", (nx0, 0.875, -0.125), (nx1, min(top, 8.0) - 0.125, 0.125), stage,
              flicker_from=1 if side == "l" else 2)
        # front neon strips
        for n, (a, c) in enumerate(((3.875, 3.625), (3.125, 2.875))):
            xa, xb = sorted((sx * a, sx * c))
            strip(b, f"pylon_neon_{side}{n}", (xa, 4.25, -1.875), (xb, top - 0.5, -1.75), stage,
                  flicker_from=1 if (side, n) == ("r", 0) else 2, dead_from=2 if side == "l" and n == 0 else 3)
        # amber warning lamps on the buttress
        lx0, lx1 = sorted((sx * 4.25, sx * 4.625))
        b.box(f"lamp_{side}", (lx0, 3.875, -1.875), (lx1, 4.125, -1.625), "amber", glow=stage < 2)
        # cable bundles down the back
        kx0, kx1 = sorted((sx * 3.5, sx * 4.0))
        b.box(f"cable_back_{side}", (kx0, 0.875, 1.75), (kx1, top - 0.25, 2.375), "cable")
        jx0, jx1 = sorted((sx * 2.75, sx * 3.125))
        b.box(f"cable_back2_{side}", (jx0, 0.5, 1.75), (jx1, top - 1.0, 2.125), "cable")

        # sloped armour leaning on the outer side
        if not (stage == 2 and side == "l"):
            wx0, wx1 = sorted((sx * 5.0, sx * 5.5))
            b.box(f"armour_{side}", (wx0, 0.5, -1.5), (wx1, 5.5, 1.5), hull, faces={"north": "hazard"},
                  rotation=("z", 22.5 * sx, (sx * 5.0, 0.5, 0)))
        if top >= 10:
            # angled horns on the outer edge of the cap
            hx0, hx1 = sorted((sx * 4.25, sx * 4.5))
            for n, (z0, z1) in enumerate(((-1.75, -0.875), (0.875, 1.75))):
                b.box(f"horn_{side}{n}", (hx0, 10.5, z0), (hx1, 11.875, z1), "hull_dark",
                      rotation=("z", -22.5 * sx, (sx * 4.375, 10.5, (z0 + z1) / 2)))
            # cap with a holo emitter and an antenna with a warning light
            hx0, hx1 = sorted((sx * 2.25, sx * 4.5))
            b.box(f"cap_{side}", (hx0, 10, -2.0), (hx1, 10.5, 2.0), "hull_dark")
            ax0, ax1 = sorted((sx * 3.0, sx * 3.75))
            b.box(f"holo_emitter_{side}", (ax0, 10.5, -1.5), (ax1, 10.875, -0.75), "steel_light")
            strip(b, f"holo_emitter_glow_{side}", (ax0 + 0.125, 10.875, -1.375), (ax1 - 0.125, 11.0, -0.875), stage)
            mx0, mx1 = sorted((sx * 3.25, sx * 3.5))
            b.box(f"mast_{side}", (mx0, 10.5, 0.75), (mx1, 11.75, 1.0), "steel_light")
            b.box(f"mast_lamp_{side}", (mx0 - 0.0625, 11.75, 0.6875), (mx1 + 0.0625, 12.0, 1.0625), "amber",
                  glow=stage < 2)
        else:
            # sheared off: the torn frame shows, sparks fly
            fx0, fx1 = sorted((sx * 2.75, sx * 4.0))
            b.box(f"torn_{side}", (fx0, top, -1.25), (fx1, top + 1.0, 1.25), "frame")
            sparks(b, f"sparks_top_{side}", (fx0, top + 0.25, -1.4375), (fx1, top + 1.25, -1.375))

    # the bridge over the gate (with the hologram sign and hazard chevrons)
    if stage < 2:
        b.box("bridge", (-2.5, 8, -1.5), (2.5, 10, 1.5), hull)
        b.box("bridge_hazard", (-2.5, 7.75, -1.75), (2.5, 8.0, 1.75), "hazard")
        b.box("holo_sign", (-2.25, 8.25, -1.8125), (2.25, 9.75, -1.6875), "holo", tint=True, glow=True)
        strip(b, "bridge_emitter", (-2.125, 7.625, -0.125), (2.125, 7.75, 0.125), stage)
        b.box("bridge_cable", (-2.25, 10.0, 1.0), (2.25, 10.375, 1.5), "cable")
        if stage == 1:
            sparks(b, "sparks_bridge", (1.0, 8.25, -1.9375), (2.25, 9.5, -1.875))
            sparks(b, "sparks_pylon", (-4.25, 5.5, -1.9375), (-3.0, 6.75, -1.875))
    else:
        # the left end tore loose: its frame hangs off the stump; the sign is gone
        b.box("bridge", (-0.75, 8, -1.5), (2.5, 10, 1.5), hull)
        b.box("bridge_hazard", (-0.75, 7.75, -1.75), (2.5, 8.0, 1.75), "hazard")
        b.box("bridge_sag", (-2.0, 6.75, -1.0), (-0.75, 9.5, 1.0), "frame")
        b.box("bridge_cable", (0.0, 10.0, 1.0), (2.25, 10.375, 1.5), "cable")
        b.box("cable_hanging", (-0.5, 6.0, 1.0), (-0.25, 10.0, 1.25), "cable", rotation=("z", 22.5, (-0.375, 10, 1.125)))
        sparks(b, "sparks_bridge", (-1.0, 7.5, -1.9375), (0.25, 8.75, -1.875))
        sparks(b, "sparks_pylon", (2.75, 5.0, -1.9375), (4.0, 6.25, -1.875))

    # floor emitter
    b.box("floor_emitter", (-2.375, 0.5, -0.5), (2.375, 0.75, 0.5), "hull_dark")
    strip(b, "floor_neon", (-2.25, 0.75, -0.125), (2.25, 0.875, 0.125), stage, flicker_from=2)
    # generator housing behind the gate
    b.box("generator", (-1.75, 0.5, 1.0), (1.75, 2.25, 2.75), hull, faces={"south": "grate", "up": "hull_dark"})
    strip(b, "generator_neon", (-1.5, 1.625, 2.75), (1.5, 1.75, 2.875), stage, flicker_from=2)
    debris(b, stage, FRONT_DEBRIS)
    return b


def front_barrier(stage):
    b = Build()
    if stage == 0:
        b.box("barrier", (-2.25, 0.875, -0.0625), (2.25, 7.625, 0.0625), "energy", tint=True, glow=True)
    elif stage == 1:
        b.box("barrier", (-2.25, 0.875, -0.0625), (2.25, 7.625, 0.0625), "energy_broken", tint=True, glow=True)
    else:
        b.box("barrier_a", (0.25, 0.875, -0.0625), (2.25, 7.625, 0.0625), "energy_broken", tint=True, glow=True)
        b.box("barrier_b", (-2.25, 0.875, -0.0625), (0.25, 3.25, 0.0625), "energy_broken", tint=True, glow=True)
    return b


# ------------------------------------------------------------------------------------------------
# 데이터 허브 (middle): 7.5 x 7.5, 18.5 tall with the antenna
# ------------------------------------------------------------------------------------------------

MIDDLE_DEBRIS = [(4.0, -2.0, 1.3, 1.6, 0.6), (-5.4, 0.5, 1.5, 1.3, 0.7), (-1.0, 4.0, 1.8, 1.2, 0.5),
                 (-4.6, -4.8, 1.2, 1.2, 0.4), (3.0, 4.2, 1.4, 1.4, 0.6), (0.8, -5.2, 1.2, 1.0, 0.4)]

RACKS = [(-1, -1), (1, -1), (-1, 1), (1, 1)]


def middle_body(stage):
    b = Build()
    hull = _hull(stage)
    ruin = stage == 3
    b.box("plinth", (-3.75, 0, -3.75), (3.75, 0.5, 3.75), "hull_dark",
          faces={"north": "hazard", "south": "hazard", "east": "hazard", "west": "hazard"})
    strip(b, "plinth_neon", (-3.5, 0.5, -3.75), (3.5, 0.5625, -3.5), stage, flicker_from=2, around=True)

    if ruin:
        heights = (2.0, 4.0, 1.25, 3.0)
        for n, (sx, sz) in enumerate(RACKS):
            x0, x1 = sorted((sx * 1.25, sx * 3.25))
            z0, z1 = sorted((sz * 1.25, sz * 3.25))
            b.box(f"rack_stub_{n}", (x0, 0.5, z0), (x1, 0.5 + heights[n], z1), hull)
            b.box(f"rack_frame_{n}", (x0 + 0.25, 0.5 + heights[n], z0 + 0.25), (x1 - 0.25, 1.5 + heights[n], z1 - 0.5),
                  "frame")
        b.box("column_stub", (-1, 0.5, -1), (1, 2.5, 1), "frame")
        b.box("crown_fallen", (-3.0, 0.5, -5.5), (2.0, 1.5, -3.75), hull, rotation=("y", -22.5, (-0.5, 1, -4.6)))
        b.box("mast_fallen", (1.0, 0.5, 3.8), (5.5, 0.75, 4.05), "steel_light", rotation=("y", 22.5, (3.25, 0.6, 3.9)))
        debris(b, stage, MIDDLE_DEBRIS)
        return b

    rack_top = 10.0
    for n, (sx, sz) in enumerate(RACKS):
        x0, x1 = sorted((sx * 1.25, sx * 3.25))
        z0, z1 = sorted((sz * 1.25, sz * 3.25))
        top = rack_top if not (stage == 2 and n == 1) else 7.0
        inner_x = "east" if sx < 0 else "west"
        inner_z = "south" if sz < 0 else "north"
        b.box(f"rack_{n}", (x0, 0.5, z0), (x1, top, z1), hull, faces={inner_x: "grate", inner_z: "grate"})
        # rack fronts (glowing LEDs in the team colour) on both outer faces
        alive = stage < 2 or n in (0, 3)
        rack_tex = "rack" if alive else "frame"
        fx = x0 - 0.125 if sx < 0 else x1
        fz = z0 - 0.125 if sz < 0 else z1
        b.box(f"rack_face_x_{n}", (fx, 1.0, z0 + 0.25), (fx + 0.125, top - 0.5, z1 - 0.25), rack_tex,
              tint=alive, glow=alive)
        b.box(f"rack_face_z_{n}", (x0 + 0.25, 1.0, fz), (x1 - 0.25, top - 0.5, fz + 0.125), rack_tex,
              tint=alive, glow=alive)
        # corner post
        px0, px1 = sorted((sx * 3.25, sx * 3.5))
        pz0, pz1 = sorted((sz * 3.25, sz * 3.5))
        b.box(f"rack_post_{n}", (px0, 0.5, pz0), (px1, top, pz1), "hull_dark")
        if top < rack_top:
            b.box(f"rack_torn_{n}", (x0 + 0.25, top, z0 + 0.25), (x1 - 0.25, top + 1.0, z1 - 0.25), "frame")
            sparks(b, f"rack_sparks_{n}", (x0 + 0.25, top - 0.5, fz + 0.125 + 0.0625), (x1 - 0.25, top + 0.75, fz + 0.25))
    # the data column glowing between the racks
    b.box("data_column", (-1, 0.5, -1), (1, rack_top, 1), "data", tint=True, glow=True)
    # cross braces over the gaps, with cooling fins
    for k in range(4):
        for y in (3.25, 6.75):
            f, t = _turn((-1.25, y, -3.25), (1.25, y + 0.5, -2.75), k)
            b.box(f"brace_{k}_{int(y)}", f, t, hull)
    if stage == 1:
        sparks(b, "sparks_rack", (1.5, 4.0, -3.4375), (3.0, 5.25, -3.375))
    if stage == 2:
        sparks(b, "sparks_brace", (-1.0, 6.25, -3.3125), (0.5, 7.5, -3.25))
    # crown
    if stage < 2:
        b.box("crown", (-3.5, 10, -3.5), (3.5, 11, 3.5), hull)
        strip(b, "crown_neon", (-3.25, 10.375, -3.625), (3.25, 10.625, -3.5), stage, around=True)
    else:
        b.box("crown_a", (-3.5, 10, -0.5), (3.5, 11, 3.5), hull)
        b.box("crown_b", (-3.5, 10, -3.5), (0.5, 10.75, -0.5), "frame")
        strip(b, "crown_neon", (-3.25, 10.375, 3.5), (3.25, 10.625, 3.625), stage)

    # cooling tower with stacked fins
    if stage < 2:
        b.box("cooler", (-2.25, 11, -2.25), (2.25, 13.5, 2.25), "grate", faces={"up": hull, "down": hull})
        for n, (sx, sz) in enumerate(RACKS):
            px0, px1 = sorted((sx * 2.25, sx * 2.75))
            pz0, pz1 = sorted((sz * 2.25, sz * 2.75))
            b.box(f"cooler_post_{n}", (px0, 11, pz0), (px1, 13.5, pz1), "hull_dark")
        for n, y in enumerate((11.625, 12.375, 13.125)):
            b.around(f"fin_{n}", (-2.25, y, -2.75), (2.25, y + 0.125, -2.25), "steel_light")
        # deck, cage and antenna
        b.box("deck", (-3.0, 13.5, -3.0), (3.0, 14.0, 3.0), "hull_dark")
        strip(b, "deck_neon", (-2.75, 13.625, -3.125), (2.75, 13.875, -3.0), stage, around=True)
        b.box("core_pad", (-0.75, 14.0, -0.75), (0.75, 14.25, 0.75), "circuit", tint=True, glow=True)
        for n, (sx, sz) in enumerate(RACKS):
            px0, px1 = sorted((sx * 1.75, sx * 2.0))
            pz0, pz1 = sorted((sz * 1.75, sz * 2.0))
            b.box(f"cage_post_{n}", (px0, 14.0, pz0), (px1, 16.5, pz1), "steel_light")
            strip(b, f"cage_neon_{n}", (px0 + 0.0625, 14.5, pz0 - 0.0625 if sz < 0 else pz1),
                  (px1 - 0.0625, 16.0, pz0 if sz < 0 else pz1 + 0.0625), stage)
        b.box("cage_roof", (-2.25, 16.5, -2.25), (2.25, 16.875, 2.25), hull)
        b.box("mast", (-0.1875, 16.875, -0.1875), (0.1875, 18.25, 0.1875), "steel_light")
        b.box("mast_bar", (-0.75, 17.375, -0.0625), (0.75, 17.5, 0.0625), "steel_light")
        b.box("mast_bar2", (-0.0625, 17.75, -0.5), (0.0625, 17.875, 0.5), "steel_light")
        b.box("mast_lamp", (-0.25, 18.25, -0.25), (0.25, 18.625, 0.25), "amber", glow=True)
    else:
        b.box("cooler", (-2.25, 11, -0.5), (2.25, 12.75, 2.25), "grate", faces={"up": hull, "down": hull})
        b.box("cooler_torn", (-2.0, 11, -2.25), (1.0, 11.75, -0.5), "frame")
        b.box("deck_hanging", (-3.0, 11.0, 1.0), (1.5, 11.5, 3.0), "hull_dark", rotation=("x", 22.5, (0, 11.25, 3)))
        b.box("cage_post", (1.75, 12.75, 1.75), (2.0, 14.25, 2.0), "steel_light")
        sparks(b, "sparks_top", (-1.5, 11.0, -0.5625), (0.5, 12.25, -0.5))
    debris(b, stage, MIDDLE_DEBRIS)
    return b


def middle_ring():
    """Holographic data ring: an octagon of glyph plates (spins)."""
    b = Build()
    _ring(b, "ring", 4.5, -0.625, 0.625, 0.125, "holo", tint=True, glow=True)
    _ring(b, "rim_top", 4.5625, 0.625, 0.6875, 0.0625, "neon", tint=True, glow=True)
    _ring(b, "rim_bottom", 4.5625, -0.6875, -0.625, 0.0625, "neon", tint=True, glow=True)
    return b


def data_core():
    """Floating data core: a glowing octahedral crystal with orbiting shards, centred on 0."""
    b = Build()
    r45 = lambda y: ("y", 45, (0, y, 0))    # noqa: E731
    b.box("body", (-0.5, -0.625, -0.5), (0.5, 0.625, 0.5), "crystal", tint=True, glow=True, rotation=r45(0))
    b.box("upper", (-0.3125, 0.625, -0.3125), (0.3125, 1.0, 0.3125), "crystal", tint=True, glow=True, rotation=r45(0.8))
    b.box("lower", (-0.3125, -1.0, -0.3125), (0.3125, -0.625, 0.3125), "crystal", tint=True, glow=True,
          rotation=r45(-0.8))
    for n, (x, z) in enumerate(((-1.0, 0), (1.0, 0), (0, -1.0), (0, 1.0))):
        b.box(f"shard_{n}", (x - 0.125, -0.25, z - 0.125), (x + 0.125, 0.25, z + 0.125), "crystal", tint=True,
              glow=True, rotation=r45(0))
    return b


# ------------------------------------------------------------------------------------------------
# 메인프레임 (core): 9 x 9, 19 tall with the beacon
# ------------------------------------------------------------------------------------------------

CORE_DEBRIS = [(-6.0, -2.0, 1.5, 1.8, 0.8), (4.6, -4.0, 1.2, 1.6, 0.6), (-1.5, -5.8, 2.0, 1.2, 0.5),
               (-5.2, 3.6, 1.4, 1.4, 0.6), (3.8, 4.6, 1.8, 1.2, 0.7), (5.0, 0.8, 1.0, 1.4, 0.5)]


def core_body(stage):
    b = Build()
    hull = _hull(stage)
    ruin = stage == 3
    conduits = ((0, -1.625), (1, 1.375))

    # tier 0: tread-plate foundation, conduit ends glowing on its front face
    b.box("tier0", (-4.5, 0, -4.5), (4.5, 1, 4.5), "hull_dark")
    b.around("tier0_hazard", (-4.25, 0.125, -4.5625), (4.25, 0.375, -4.5), "hazard")
    for k, x in conduits:
        strip(b, f"conduit_t0_face_{k}", (x, 0.375, -4.5625), (x + 0.25, 1.0, -4.5), stage, flicker_from=2,
              around=True)
        strip(b, f"conduit_t0_top_{k}", (x, 1.0, -4.5), (x + 0.25, 1.0625, -3.875), stage, flicker_from=2,
              around=True)

    if ruin:
        b.box("tier1_stub", (-3.75, 1, -3.75), (3.75, 3.5, 3.75), hull)
        b.box("tier1_tooth_a", (-3.75, 3.5, 0.5), (-1.0, 5.25, 3.75), hull)
        b.box("tier1_tooth_b", (1.5, 3.5, -3.75), (3.75, 4.5, -1.0), hull)
        b.box("tier1_frame", (-1.0, 3.5, -2.5), (1.5, 4.25, 2.5), "frame")
        b.box("chip_dead", (-1.25, 3.5, 1.0), (1.25, 3.625, 3.5), "frame")
        b.box("corner_stub_0", (-4.0, 1, -4.0), (-3.0, 2.5, -3.0), "hull_dark")
        b.box("corner_stub_3", (3.0, 1, 3.0), (4.0, 4.0, 4.0), "hull_dark")
        b.box("spire_fallen", (0.5, 1.0, -5.75), (4.5, 2.0, -4.75), hull, rotation=("y", -22.5, (2.5, 1.5, -5.25)))
        b.box("dish_fallen", (-5.75, 1.0, -1.0), (-4.75, 2.5, 1.0), "steel_light", rotation=("z", 22.5, (-5.25, 1, 0)))
        debris(b, stage, CORE_DEBRIS)
        return b

    # tier 1: the monolith, corner pillars, a sealed blast door between smart-glass bands
    b.box("tier1", (-3.75, 1, -3.75), (3.75, 4.5, 3.75), hull)
    for n, (sx, sz) in enumerate(RACKS):
        x0, x1 = sorted((sx * 3.5, sx * 4.0))
        z0, z1 = sorted((sz * 3.5, sz * 4.0))
        b.box(f"corner_{n}", (x0, 1, z0), (x1, 5.25 if stage < 2 or n != 1 else 3.25, z1), "hull_dark")
    b.box("door", (-0.875, 1, -3.875), (0.875, 3.0, -3.75), "hazard")
    b.box("door_frame", (-1.125, 3.0, -3.9375), (1.125, 3.25, -3.75), "hull_dark")
    b.box("door_glass", (-2.875, 2.0, -3.8125), (-1.875, 3.75, -3.75), "glass_dark")
    b.box("door_glass_r", (1.875, 2.0, -3.8125), (2.875, 3.75, -3.75), "glass_dark")
    for k in (1, 2, 3):
        f, t = _turn((-2.875, 2.0, -3.8125), (2.875, 3.75, -3.75), k)
        b.box(f"window_band_{k}", f, t, "glass_dark")
    # light conduits: x = -1.625 and 1.375 on every face, from the heart chamber down to the foundation
    for k, x in conduits:
        strip(b, f"conduit_t1_{k}", (x, 1.0, -3.875), (x + 0.25, 4.5, -3.75), stage, flicker_from=2, around=True)
        strip(b, f"conduit_ledge_face_{k}", (x, 4.5, -4.0), (x + 0.25, 4.875, -3.875), stage, flicker_from=2,
              around=True)
        strip(b, f"conduit_ledge_{k}", (x, 4.875, -3.875), (x + 0.25, 4.9375, -3.0), stage, flicker_from=2,
              around=True)
        strip(b, f"conduit_t2_{k}", (x, 4.875, -3.125), (x + 0.25, 8.0, -3.0), stage, flicker_from=1, around=True)
        strip(b, f"conduit_floor_{k}", (x, 8.0, -3.375), (x + 0.25, 8.5, -3.25), stage, flicker_from=1, around=True)
    # ledge and tier 2 with smart-glass bands and cooling vents
    b.box("ledge", (-3.875, 4.5, -3.875), (3.875, 4.875, 3.875), "hull_dark")
    b.box("tier2", (-3, 4.875, -3), (3, 8.0, 3), hull)
    b.around("tier2_glass", (-1.125, 5.5, -3.0625), (1.125, 7.5, -3.0), "glass_dark")
    b.around("tier2_vent_l", (-2.75, 5.5, -3.0625), (-1.875, 7.25, -3.0), "grate")
    b.around("tier2_vent_r", (1.875, 5.5, -3.0625), (2.75, 7.25, -3.0), "grate")
    if stage >= 1:
        sparks(b, "sparks_t2", (-2.75, 5.75, -3.1875), (-1.5, 7.0, -3.125))
    if stage >= 2:
        b.box("tier1_breach", (1.75, 2.75, -3.9375), (3.25, 4.25, -3.75), "frame")
        sparks(b, "sparks_t1", (1.75, 3.0, -4.0625), (3.25, 4.25, -4.0))

    # the heart chamber: processor die floor, four pillars with inward neon
    b.box("chamber_floor", (-3.25, 8.0, -3.25), (3.25, 8.5, 3.25), "hull_dark")
    b.box("processor", (-1.75, 8.5, -1.75), (1.75, 8.75, 1.75), "circuit", tint=True, glow=True)
    b.around("processor_pins", (-1.5, 8.5, -2.125), (1.5, 8.625, -1.75), "steel_light")
    for n, (sx, sz) in enumerate(RACKS):
        x0, x1 = sorted((sx * 2.0, sx * 3.0))
        z0, z1 = sorted((sz * 2.0, sz * 3.0))
        if stage == 2 and n == 1:
            b.box(f"pillar_{n}", (x0, 8.5, z0), (x1, 10.0, z1), hull)
            b.box(f"pillar_torn_{n}", (x0 + 0.25, 10.0, z0 + 0.25), (x1 - 0.25, 10.75, z1 - 0.25), "frame")
            continue
        b.box(f"pillar_{n}", (x0, 8.5, z0), (x1, 13.5, z1), hull)
        ix = x1 if sx < 0 else x0 - 0.125
        iz = z1 if sz < 0 else z0 - 0.125
        strip(b, f"pillar_neon_x_{n}", (ix, 9.0, z0 + 0.375), (ix + 0.125, 13.0, z1 - 0.375), stage)
        strip(b, f"pillar_neon_z_{n}", (x0 + 0.375, 9.0, iz), (x1 - 0.375, 13.0, iz + 0.125), stage)
    # crown and spire
    if stage < 2:
        b.box("crown", (-3.25, 13.5, -3.25), (3.25, 14.5, 3.25), hull)
        strip(b, "crown_neon", (-3.0, 13.875, -3.375), (3.0, 14.125, -3.25), stage, around=True)
        strip(b, "heart_emitter", (-1.0, 13.375, -1.0), (1.0, 13.5, 1.0), stage)
        b.box("spire_0", (-2.0, 14.5, -2.0), (2.0, 15.75, 2.0), hull, faces={"up": "hull_dark"})
        b.box("spire_1", (-1.25, 15.75, -1.25), (1.25, 17.25, 1.25), hull)
        strip(b, "spire_neon", (-0.125, 15.75, -1.375), (0.125, 17.25, -1.25), stage, around=True)
        strip(b, "spire_band", (-1.875, 15.0, -2.125), (1.875, 15.25, -2.0), stage, around=True)
        b.box("mast", (-0.25, 17.25, -0.25), (0.25, 18.5, 0.25), "steel_light")
        strip(b, "beacon", (-0.375, 18.5, -0.375), (0.375, 19.0, 0.375), stage)
        _dish(b, "dish_a", -2.625, 14.5, -2.625, stage)
        _dish(b, "dish_b", 2.625, 14.5, 2.625, stage)
    else:
        b.box("crown_a", (-3.25, 13.5, -0.5), (3.25, 14.5, 3.25), hull)
        b.box("crown_b", (-3.25, 13.5, -3.25), (-0.5, 14.0, -0.5), "frame")
        b.box("spire_0", (-2.0, 14.5, -0.25), (2.0, 15.5, 2.0), hull, faces={"up": "hull_dark"})
        b.box("spire_torn", (-1.25, 15.5, 0.25), (1.25, 16.25, 1.75), "frame")
        sparks(b, "sparks_spire", (-1.25, 14.75, -0.375), (0.25, 16.0, -0.3125))
        _dish(b, "dish_b", 2.625, 14.5, 2.625, stage)
    debris(b, stage, CORE_DEBRIS)
    return b


def _dish(b, name, x, y, z, stage):
    """A satellite dish on a short mast, its bowl tilted up."""
    r, rim = 0.75, 0.625
    b.box(f"{name}_mast", (x - 0.125, y, z - 0.125), (x + 0.125, y + 0.75, z + 0.125), "steel_light")
    cy = y + 0.75
    b.box(f"{name}_bowl", (x - r, cy, z - r), (x + r, cy + 0.125, z + r), "steel_light")
    b.box(f"{name}_rim_n", (x - r, cy, z - r - rim), (x + r, cy + 0.125, z - r), "steel_light",
          rotation=("x", -22.5, (x, cy, z - r)))
    b.box(f"{name}_rim_s", (x - r, cy, z + r), (x + r, cy + 0.125, z + r + rim), "steel_light",
          rotation=("x", 22.5, (x, cy, z + r)))
    b.box(f"{name}_rim_w", (x - r - rim, cy, z - r), (x - r, cy + 0.125, z + r), "steel_light",
          rotation=("z", 22.5, (x - r, cy, z)))
    b.box(f"{name}_rim_e", (x + r, cy, z - r), (x + r + rim, cy + 0.125, z + r), "steel_light",
          rotation=("z", -22.5, (x + r, cy, z)))
    b.box(f"{name}_feed", (x - 0.0625, cy + 0.125, z - 0.0625), (x + 0.0625, cy + 1.0, z + 0.0625), "steel_light")
    tex = _neon(stage)
    b.box(f"{name}_feed_tip", (x - 0.125, cy + 1.0, z - 0.125), (x + 0.125, cy + 1.125, z + 0.125), tex,
          tint=_glow(tex), glow=_glow(tex))


def core_heart():
    """The mainframe's heart: a giant processor crystal, centred on 0 (spins and pulses)."""
    b = Build()
    r45 = lambda y: ("y", 45, (0, y, 0))    # noqa: E731
    b.box("body", (-0.875, -0.875, -0.875), (0.875, 0.875, 0.875), "crystal", tint=True, glow=True, rotation=r45(0))
    b.box("upper", (-0.625, 0.875, -0.625), (0.625, 1.375, 0.625), "crystal", tint=True, glow=True, rotation=r45(1))
    b.box("upper_tip", (-0.3125, 1.375, -0.3125), (0.3125, 1.75, 0.3125), "crystal", tint=True, glow=True,
          rotation=r45(1.5))
    b.box("lower", (-0.625, -1.375, -0.625), (0.625, -0.875, 0.625), "crystal", tint=True, glow=True, rotation=r45(-1))
    b.box("lower_tip", (-0.3125, -1.75, -0.3125), (0.3125, -1.375, 0.3125), "crystal", tint=True, glow=True,
          rotation=r45(-1.5))
    # processor bands wrapped round the waist (circuit plates on the four flat faces)
    b.box("band_x", (-1.0, -0.25, -0.25), (1.0, 0.25, 0.25), "circuit", tint=True, glow=True)
    b.box("band_z", (-0.1875, -0.1875, -1.0), (0.1875, 0.1875, 1.0), "circuit", tint=True, glow=True)
    # orbiting shards
    for n, (x, z, axis, ang) in enumerate(((-1.5, 0, "z", 22.5), (1.5, 0, "z", -22.5), (0, -1.5, "x", -22.5),
                                           (0, 1.5, "x", 22.5))):
        b.box(f"shard_{n}", (x - 0.1875, -0.5, z - 0.1875), (x + 0.1875, 0.5, z + 0.1875), "crystal", tint=True,
              glow=True, rotation=(axis, ang, (x, 0, z)))
    return b


def core_halo():
    """A thin neon halo round the heart (spins tilted)."""
    b = Build()
    _ring(b, "halo", 2.125, -0.0625, 0.0625, 0.125, "neon", tint=True, glow=True)
    return b


# ------------------------------------------------------------------------------------------------
# hex energy shields (one per tower size, display scale 8, centred SHIELD_CENTRE blocks up)
# ------------------------------------------------------------------------------------------------

def shield(rx, rz, height, tiers=7):
    """A stepped octagonal hex-energy dome: rx, rz half widths, `height` blocks tall (hollow, thin plates)."""
    b = Build()
    t = 0.125
    profile = []
    wall = height * 0.45
    for n in range(tiers):
        y0 = 0 if n == 0 else wall + (height - wall) * (1 - math.cos(math.pi / 2 * (n - 1) / (tiers - 1)))
        y1 = wall + (height - wall) * (1 - math.cos(math.pi / 2 * n / (tiers - 1)))
        k = math.sqrt(max(0.0, 1 - ((y0 - wall) / (height - wall)) ** 2)) if n > 0 else 1.0
        profile.append((y0 - 0.25 if n == 0 else y0, y1, rx * k, rz * k))
    for n, (y0, y1, ax, az) in enumerate(profile):
        c = min(ax, az) * 0.42
        b.box(f"n_{n}", (-ax + c, y0, -az), (ax - c, y1, -az + t), "shield", glow=True)
        b.box(f"s_{n}", (-ax + c, y0, az - t), (ax - c, y1, az), "shield", glow=True)
        b.box(f"w_{n}", (-ax, y0, -az + c), (-ax + t, y1, az - c), "shield", glow=True)
        b.box(f"e_{n}", (ax - t, y0, -az + c), (ax, y1, az - c), "shield", glow=True)
        L = c * math.sqrt(2)
        for m, (sx, sz) in enumerate(RACKS):
            cx, cz = sx * (ax - c / 2), sz * (az - c / 2)
            angle = 45 if sx * sz < 0 else -45
            b.box(f"d_{n}_{m}", (cx - L / 2, y0, cz - t / 2), (cx + L / 2, y1, cz + t / 2), "shield", glow=True,
                  rotation=("y", angle, (cx, (y0 + y1) / 2, cz)))
        # the step up to the next tier (or the top cap)
        if n + 1 < len(profile):
            _, _, bx, bz = profile[n + 1]
        else:
            bx, bz = 0, 0
        if bx == 0:
            b.box(f"cap_{n}", (-ax + c, y1, -az + c), (ax - c, y1 + t, az - c), "shield", glow=True)
            b.box(f"cap_n_{n}", (-ax + c, y1, -az), (ax - c, y1 + t, -az + c), "shield", glow=True)
            b.box(f"cap_s_{n}", (-ax + c, y1, az - c), (ax - c, y1 + t, az), "shield", glow=True)
            b.box(f"cap_w_{n}", (-ax, y1, -az + c), (-ax + c, y1 + t, az - c), "shield", glow=True)
            b.box(f"cap_e_{n}", (ax - c, y1, -az + c), (ax, y1 + t, az - c), "shield", glow=True)
            continue
        nc = min(bx, bz) * 0.42
        b.box(f"step_n_{n}", (-bx + nc, y1, -az), (bx - nc, y1 + t, -bz), "shield", glow=True)
        b.box(f"step_s_{n}", (-bx + nc, y1, bz), (bx - nc, y1 + t, az), "shield", glow=True)
        b.box(f"step_w_{n}", (-ax, y1, -bz + nc), (-bx, y1 + t, bz - nc), "shield", glow=True)
        b.box(f"step_e_{n}", (bx, y1, -bz + nc), (ax, y1 + t, bz - nc), "shield", glow=True)
        dw = max(ax - bx, az - bz) + 0.1
        for m, (sx, sz) in enumerate(RACKS):
            cx, cz = sx * (ax - c / 2 + bx - nc / 2) / 2, sz * (az - c / 2 + bz - nc / 2) / 2
            angle = 45 if sx * sz < 0 else -45
            b.box(f"step_d_{n}_{m}", (cx - L / 2 - dw / 2, y1, cz - dw / 2), (cx + L / 2 + dw / 2, y1 + t, cz + dw / 2),
                  "shield", glow=True, rotation=("y", angle, (cx, y1, cz)))
    return b


SHIELDS = {"front": (5.75, 4.0, 13.5), "middle": (5.25, 5.25, 19.75), "core": (6.75, 6.75, 20.5)}


# ------------------------------------------------------------------------------------------------
# registry
# ------------------------------------------------------------------------------------------------

def _staged(tower, part, builder, slab):
    def make(stage):
        models = builder(stage).slabs(f"tower_{tower}_{part}_{stage}")
        m = models[slab]
        m.name = f"tower_{tower}_{part}_{stage}"
        return m
    return make


def _centred(name, builder, scale=BODY_SCALE, centre=0.0):
    def make():
        b = builder()
        m = Model(name)
        for bname, frm, to, tex, kw in b.boxes:
            _add(m, bname, frm, to, tex, kw, centre - 1.5 * scale, scale)
        return m
    return make


ALL = {}
for _s in range(STAGES):
    ALL[f"tower_front_body_{_s}"] = (lambda s: lambda: _staged("front", "body", front_body, 0)(s))(_s)
    ALL[f"tower_middle_base_{_s}"] = (lambda s: lambda: _staged("middle", "base", middle_body, 0)(s))(_s)
    ALL[f"tower_core_base_{_s}"] = (lambda s: lambda: _staged("core", "base", core_body, 0)(s))(_s)
for _s in range(STAGES - 1):
    ALL[f"tower_front_barrier_{_s}"] = (lambda s: lambda: _staged("front", "barrier", front_barrier, 0)(s))(_s)
    ALL[f"tower_middle_top_{_s}"] = (lambda s: lambda: _staged("middle", "top", middle_body, 1)(s))(_s)
    ALL[f"tower_core_top_{_s}"] = (lambda s: lambda: _staged("core", "top", core_body, 1)(s))(_s)
ALL["tower_middle_ring"] = _centred("tower_middle_ring", middle_ring)
ALL["tower_middle_core"] = _centred("tower_middle_core", data_core)
ALL["tower_core_heart"] = _centred("tower_core_heart", core_heart)
ALL["tower_core_halo"] = _centred("tower_core_halo", core_halo)
for _t, (_rx, _rz, _h) in SHIELDS.items():
    ALL[f"tower_{_t}_shield"] = (lambda t, rx, rz, h: _centred(
        f"tower_{t}_shield", lambda: shield(rx, rz, h), SHIELD_SCALE, SHIELD_CENTRE))(_t, _rx, _rz, _h)


def builders():
    """Block-space builders per tower and stage, for previews and checks: {tower: fn(stage) -> Build}."""
    return {"front": front_body, "middle": middle_body, "core": core_body}
