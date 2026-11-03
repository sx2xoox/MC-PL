"""
초능력 공성전 towers (docs/siege.md §3): 성문 탑 (front), 중앙 탑 (middle), 코어 (core).

Each tower body comes in four damage stages (0 intact, 1 cracked, 2 heavily damaged, 3 ruin); the crystal and the
shield bubble are separate models so the game can spin, flash and hide them. Team-coloured parts (banners, rune
slits, the crystal) are tinted with the item's dyed colour (minecraft:dye tint), so one model serves both teams.

Model space (before the display scale): footprint x, z in -4..20 (24 units), ground at y = -16. The game shows the
bodies at scale 2 (24 units = 3 blocks, 16 units = 2 blocks), so the full -16..32 range is 6 blocks tall.
"""

from mcpl_assets.model import Model

STAGES = 4

# where the game floats each crystal, in model units above the ground (y = -16), and the crystal scale
# (kept in sync with ModelTowerLook.Spec)
# (kept in sync with ModelTowerLook.Spec): crystal centre in body units above the ground, crystal scale, shield scale
PARTS = {
    "front": {"crystal_spot": 46, "crystal_scale": 1.15, "shield_scale": 2.35},
    "middle": {"crystal_spot": 37.5, "crystal_scale": 0.75, "shield_scale": 2.15},
    "core": {"crystal_spot": 27, "crystal_scale": 1.6, "shield_scale": 2.2},
}
BODY_SCALE = 2


def _wall(stage):
    return ("ashlar", "ashlar_cracked", "ashlar_broken", "ashlar_broken")[stage]


def _keep(stage, index, lost_at_1=(), lost_at_2=()):
    """Whether a breakable detail (merlon, banner, ...) survives the stage."""
    if stage >= 3:
        return False
    if stage >= 1 and index in lost_at_1:
        return False
    if stage >= 2 and index in lost_at_2:
        return False
    return True


def _rubble(m, stage, spots):
    """Fallen stones around the base: a few at stage 2, all of them for the ruin."""
    count = {0: 0, 1: 0, 2: 3, 3: len(spots)}[stage]
    for n, (x, z, w, d, h) in enumerate(spots[:count]):
        m.box(f"rubble_{n}", (x, -16, z), (x + w, -16 + h, z + d), "rubble")


def _merlons(m, stage, y0, y1, ring, lost1, lost2, tex="cap_stone"):
    """Merlons on a parapet ring: ring = list of (x0, z0, x1, z1)."""
    for n, (x0, z0, x1, z1) in enumerate(ring):
        if _keep(stage, n, lost1, lost2):
            m.box(f"merlon_{n}", (x0, y0, z0), (x1, y1, z1), tex)


def _square_ring(lo, hi, t, size, gap, y0=None):
    """Merlon footprints along a square ring [lo, hi] of thickness t: merlons `size` long, `gap` apart."""
    out = []
    pos = lo
    while pos + size <= hi + 1e-6:
        out.append(("n", pos))
        pos += size + gap
    ring = []
    for _, p in out:
        ring.append((p, lo, p + size, lo + t))          # north
        ring.append((p, hi - t, p + size, hi))          # south
    pos = lo + t + gap
    while pos + size <= hi - t - gap + 1e-6:
        ring.append((lo, pos, lo + t, pos + size))      # west
        ring.append((hi - t, pos, hi, pos + size))      # east
        pos += size + gap
    return ring


# ------------------------------------------------------------------------------------------------
# 성문 탑 (front): a square gate keep, 5 blocks tall, with a portcullis gate and a crystal brazier on the roof
# ------------------------------------------------------------------------------------------------

def front(stage):
    m = Model(f"tower_front_{stage}")
    wall = _wall(stage)
    ruin = stage == 3

    m.group("base")
    m.box("plinth", (-4, -16, -4), (20, -12, 20), "ashlar_dark")
    if ruin:
        # the stub of the keep: jagged broken walls around the gate, the rubble-filled inside
        m.box("stub_w", (-3, -12, -3), (3, -2, 19), wall)
        m.box("stub_e", (13, -12, -3), (19, -6, 19), wall)
        m.box("stub_s", (3, -12, 13), (13, -4, 19), wall)
        m.box("stub_fill", (3, -12, 1), (13, -9, 13), "rubble")
        m.box("stub_tooth_w", (-3, -2, 9), (1, 3, 19), wall)
        m.box("stub_tooth_e", (15, -6, 13), (19, -1, 19), wall)
        m.box("gate_bar", (3, -12, 0.5), (13, -10, 1), "gate", faces={"south": None})
        m.box("fallen_banner", (4, -12, -3.5), (12, -11.5, 0.5), "banner", tint=True)
        _rubble(m, stage, [(-9, 2, 5, 6, 3), (20, 8, 5, 7, 4), (3, 20, 7, 4, 2), (-8, 13, 4, 5, 2), (13, 20, 5, 4, 3), (2, -9, 6, 5, 2)])
        return m

    m.group("keep")
    # walls around the gate recess (gate opening x 3..13, y -12..2, 4 units deep)
    m.box("wall_w", (-3, -12, -3), (3, 14, 19), wall)
    m.box("wall_e", (13, -12, -3), (19, 14, 19), wall)
    m.box("lintel", (3, 2, -3), (13, 14, 19), wall)
    m.box("gate_back", (3, -12, 1), (13, 2, 19), wall, faces={"north": "gate"})
    m.box("portcullis", (3, -12, 0.5), (13, 2, 1), "gate", faces={"south": None})
    # arch: voussoir band and keystone
    m.box("arch", (2, 2, -4), (14, 4, -3), "cap_stone")
    m.box("keystone", (6.5, 2, -4.5), (9.5, 5, -4), "cap_stone")
    # corner buttresses
    for n, (x, z) in enumerate(((-4, -4), (16, -4), (-4, 16), (16, 16))):
        top = 10 if stage < 2 or n % 3 else 4
        m.box(f"buttress_{n}", (x, -12, z), (x + 4, top, z + 4), wall)
        m.box(f"buttress_cap_{n}", (x - 0.5, top, z - 0.5), (x + 4.5, top + 1, z + 4.5), "cap_stone")

    # team banner over the gate and glowing arrow slits
    m.group("team")
    if _keep(stage, 0):
        m.box("banner_pole", (4, 13, -4), (12, 14, -3), "wood_beam")
        bottom = 5 if stage == 0 else 8
        m.box("banner", (5, bottom, -3.5), (11, 13, -3), "banner", tint=True, full_uv=("north",))
    for n, (frm, to) in enumerate((((19, 0, 7), (19.5, 8, 9)), ((-3.5, 0, 7), (-3, 8, 9)), ((7, 0, 19), (9, 8, 19.5)))):
        if _keep(stage, n, (), (0,)):
            m.box(f"slit_{n}", frm, to, "rune", tint=True, glow=True)

    # corbelled ledge and the parapet
    m.group("parapet")
    if stage < 2:
        m.box("ledge", (-4, 14, -4), (20, 16, 20), "cap_stone")
        m.box("parapet_n", (-4, 16, -4), (20, 21, -2), wall)
        m.box("parapet_s", (-4, 16, 18), (20, 21, 20), wall)
        m.box("parapet_w", (-4, 16, -2), (-2, 21, 18), wall)
        m.box("parapet_e", (18, 16, -2), (20, 21, 18), wall)
    else:
        # the north-east corner has fallen in
        m.box("ledge_a", (-4, 14, -4), (10, 16, 20), "cap_stone")
        m.box("ledge_b", (10, 14, 4), (20, 16, 20), "cap_stone")
        m.box("parapet_n", (-4, 16, -4), (8, 19, -2), wall)
        m.box("parapet_s", (-4, 16, 18), (20, 21, 20), wall)
        m.box("parapet_w", (-4, 16, -2), (-2, 21, 18), wall)
        m.box("parapet_e", (18, 16, 6), (20, 19, 18), wall)
    ring = _square_ring(-4, 20, 2, 4, 2)
    lost2 = tuple(i for i, (x0, z0, x1, z1) in enumerate(ring) if (z0 < 0 or x1 > 18) or i % 3 == 0)
    if stage < 2:
        _merlons(m, stage, 21, 24, ring, (1, 6), ())
    else:
        _merlons(m, stage, 21, 24, ring, (1, 6), lost2)

    # crystal brazier on the roof (the crystal itself is a separate model floating above it)
    m.group("brazier")
    if stage < 2:
        m.box("roof", (-2, 16, -2), (18, 16.5, 18), "cap_stone", faces={"down": None})
    else:
        m.box("roof_a", (-2, 16, -2), (10, 16.5, 18), "cap_stone", faces={"down": None})
        m.box("roof_b", (10, 16, 4), (18, 16.5, 18), "cap_stone", faces={"down": None})
    if stage < 2:
        m.box("brazier_foot", (5, 16.5, 5), (11, 19, 11), "cap_stone")
        m.box("brazier_bowl", (4, 19, 4), (12, 20.5, 12), "gold_trim")
        m.box("brazier_glow", (5, 20.5, 5), (11, 21, 11), "rune", tint=True, glow=True, faces={"down": None})
    else:
        m.box("brazier_foot", (5, 16.5, 5), (11, 18, 11), "cap_stone")
        m.box("brazier_bowl", (6, 16.5, 11), (12, 17.5, 15), "gold_trim")   # knocked over
    _rubble(m, stage, [(-9, 2, 5, 6, 3), (20, 8, 5, 7, 4), (3, 20, 7, 4, 2), (-8, 13, 4, 5, 2), (13, 20, 5, 4, 3), (2, -9, 6, 5, 2)])
    return m


# ------------------------------------------------------------------------------------------------
# 중앙 탑 (middle): a round-ish (octagonal) tower, 6 blocks tall, the crystal in an open lantern chamber
# ------------------------------------------------------------------------------------------------

def _octagon(m, name, y0, y1, r, chamfer, tex, faces=None, cx=8):
    """A stepped octagon: a cross of two slabs plus a square that fills the chamfered corners."""
    lo, hi = cx - r, cx + r
    c = r - chamfer
    m.box(f"{name}_x", (lo, y0, cx - c), (hi, y1, cx + c), tex, faces=faces)
    m.box(f"{name}_z_n", (cx - c, y0, lo), (cx + c, y1, cx - c), tex, faces=faces)
    m.box(f"{name}_z_s", (cx - c, y0, cx + c), (cx + c, y1, hi), tex, faces=faces)
    # corner fillers: four small boxes between the arms
    k = r - chamfer / 2
    for n, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        x0, x1 = sorted((cx + sx * c, cx + sx * k))
        z0, z1 = sorted((cx + sz * c, cx + sz * k))
        m.box(f"{name}_corner_{n}", (x0, y0, z0), (x1, y1, z1), tex, faces=faces)


def middle(stage):
    m = Model(f"tower_middle_{stage}")
    wall = _wall(stage)

    m.group("base")
    _octagon(m, "plinth", -16, -12, 12, 4, "ashlar_dark")
    if stage == 3:
        _octagon(m, "stub", -12, -4, 10, 4, wall)
        m.box("stub_tooth_a", (-2, -4, 4), (2, 4, 12), wall)
        m.box("stub_tooth_b", (4, -4, 14), (12, 0, 18), wall)
        m.box("stub_fill", (4, -4, 2), (14, -2, 12), "rubble")
        m.box("fallen_roof", (8, -12, -4), (18, -10, 2), "slate_roof")
        _rubble(m, stage, [(20, 3, 5, 6, 3), (-9, 9, 5, 6, 4), (6, 20, 6, 4, 2), (21, 13, 4, 4, 2), (-8, 0, 4, 4, 3), (11, -8, 5, 4, 2)])
        return m

    m.group("shaft")
    _octagon(m, "shaft", -12, 12, 10, 4, wall)
    # glowing team slits on the four main faces
    m.group("team")
    for n, (frm, to) in enumerate((((7, 0, -2.5), (9, 9, -2)), ((7, 0, 18), (9, 9, 18.5)),
                                   ((-2.5, 0, 7), (-2, 9, 9)), ((18, 0, 7), (18.5, 9, 9)))):
        if _keep(stage, n, (), (1, 2)):
            m.box(f"slit_{n}", frm, to, "rune", tint=True, glow=True)
    # door on the north face
    m.box("door", (5, -12, -2.5), (11, -2, -2), "wood_beam", faces={"south": None})
    m.box("door_arch", (4, -2, -3), (12, -0.5, -2), "cap_stone", faces={"south": None})
    # banners hanging under the ledge, east and west
    for n, (x0, x1) in enumerate(((-2.5, -2), (18, 18.5))):
        if _keep(stage, n, (1,), (0,)):
            m.box(f"banner_{n}", (x0, 1 if stage == 0 else 5, 11), (x1, 11.5, 15), "banner", tint=True)

    m.group("ledge")
    _octagon(m, "ledge", 12, 14, 12, 4, "cap_stone")
    m.group("lantern")
    pillars = ((-1, -1), (14, -1), (-1, 14), (14, 14))
    for n, (x, z) in enumerate(pillars):
        if stage == 2 and n == 1:
            m.box(f"pillar_{n}", (x, 14, z), (x + 3, 18, z + 3), wall)    # snapped
            continue
        m.box(f"pillar_{n}", (x, 14, z), (x + 3, 27, z + 3), wall)
    if stage < 2:
        m.box("pedestal", (5, 14, 5), (11, 15.5, 11), "cap_stone")
        m.box("pedestal_glow", (6, 15.5, 6), (10, 16, 10), "rune", tint=True, glow=True, faces={"down": None})
        # roof: cap slab and a stepped slate spire
        m.box("roof_slab", (-2, 27, -2), (18, 28.5, 18), "cap_stone")
        m.box("roof_1", (0, 28.5, 0), (16, 29.5, 16), "slate_roof")
        m.box("roof_2", (2.5, 29.5, 2.5), (13.5, 30.5, 13.5), "slate_roof")
        m.box("roof_3", (5, 30.5, 5), (11, 31.5, 11), "slate_roof")
        m.box("finial", (7, 31.5, 7), (9, 32, 9), "gold_trim")
        lost1 = ()
        m.group("roof_merlons")
        ring = _square_ring(-2, 18, 1.5, 3, 2.5)
        for n, (x0, z0, x1, z1) in enumerate(ring):
            if n % 4 == 1 and stage == 1:
                continue
            m.box(f"crenel_{n}", (x0, 28.5, z0), (x1, 30, z1), "cap_stone")
        _ = lost1
    else:
        m.box("pedestal", (5, 14, 5), (11, 15.5, 11), "cap_stone")
        # half the roof has caved in
        m.box("roof_slab", (-2, 27, 8), (18, 28.5, 18), "cap_stone")
        m.box("roof_1", (0, 28.5, 9), (16, 29.5, 16), "slate_roof")
        m.box("roof_debris", (-1, 14, 4), (5, 15, 9), "slate_roof")
    _rubble(m, stage, [(20, 3, 5, 6, 3), (-9, 9, 5, 6, 4), (6, 20, 6, 4, 2), (21, 13, 4, 4, 2), (-8, 0, 4, 4, 3), (11, -8, 5, 4, 2)])
    return m


# ------------------------------------------------------------------------------------------------
# 코어 (core): a stepped dais with four obelisk pylons framing the big floating crystal
# ------------------------------------------------------------------------------------------------

def core(stage):
    m = Model(f"tower_core_{stage}")
    wall = _wall(stage)

    m.group("dais")
    m.box("step_0", (-4, -16, -4), (20, -13, 20), "ashlar_dark")
    m.box("step_1", (-2, -13, -2), (18, -10, 18), "cap_stone")
    # team rune band on the lower riser, all four sides
    m.group("team")
    if stage < 3:
        for n, (frm, to) in enumerate((((-1, -15, -4.5), (17, -14, -4)), ((-1, -15, 20), (17, -14, 20.5)),
                                       ((-4.5, -15, -1), (-4, -14, 17)), ((20, -15, -1), (20.5, -14, 17)))):
            if _keep(stage, n, (), (1,)):
                m.box(f"rune_band_{n}", frm, to, "rune", tint=True, glow=True)

    m.group("pedestal")
    if stage == 3:
        m.box("pedestal", (4, -10, 4), (12, -8, 12), "cap_stone")
        m.box("shards", (5, -8, 6), (10, -7, 10), "crystal", tint=True)   # dead, unlit crystal shards
        m.box("shard_b", (10, -10, 12), (12, -8, 15), "crystal", tint=True)
    else:
        m.box("pedestal", (4, -10, 4), (12, -6, 12), "cap_stone")
        m.box("pedestal_ring", (3, -6, 3), (13, -5, 13), "gold_trim")
        m.box("pedestal_glow", (5, -5, 5), (11, -4.5, 11), "rune", tint=True, glow=True, faces={"down": None})
        for n, (x, z) in enumerate(((3, 3), (11, 3), (3, 11), (11, 11))):
            m.box(f"claw_{n}", (x, -5, z), (x + 2, -2, z + 2), "gold_trim")

    m.group("pylons")
    heights = {0: (26, 26, 26, 26), 1: (26, 26, 26, 22), 2: (14, 26, 6, 18), 3: (-2, 4, -6, 0)}[stage]
    for n, (x, z) in enumerate(((-2, -2), (14, -2), (-2, 14), (14, 14))):
        top = heights[n]
        m.box(f"pylon_{n}", (x, -10, z), (x + 4, top, z + 4), wall)
        if top >= 26:
            m.box(f"pylon_cap_{n}", (x - 0.5, 26, z - 0.5), (x + 4.5, 27.5, z + 4.5), "cap_stone")
            m.box(f"pylon_tip_{n}", (x + 1, 27.5, z + 1), (x + 3, 30, z + 3), "gold_trim")
            m.box(f"pylon_spark_{n}", (x + 1.5, 30, z + 1.5), (x + 2.5, 31, z + 2.5), "rune", tint=True, glow=True)
        # inward-facing glowing slits
        if top >= 18 and stage < 3:
            sx = x + 4 if x < 8 else x - 0.5
            sz = z + 4 if z < 8 else z - 0.5
            m.box(f"pylon_slit_x_{n}", (sx, 4, z + 1.5), (sx + 0.5, 14, z + 2.5), "rune", tint=True, glow=True)
            m.box(f"pylon_slit_z_{n}", (x + 1.5, 4, sz), (x + 2.5, 14, sz + 0.5), "rune", tint=True, glow=True)
    # stone frame between the pylon tops
    if stage < 2:
        m.group("frame")
        m.box("beam_n", (2, 20, -1.5), (14, 22, 1.5), "cap_stone")
        m.box("beam_s", (2, 20, 14.5), (14, 22, 17.5), "cap_stone")
        m.box("beam_w", (-1.5, 20, 2), (1.5, 22, 14), "cap_stone")
        if stage == 0:
            m.box("beam_e", (14.5, 20, 2), (17.5, 22, 14), "cap_stone")
    _rubble(m, stage, [(-10, 5, 5, 6, 3), (21, 10, 4, 6, 3), (2, 21, 6, 4, 3), (12, -9, 5, 4, 2), (-9, 14, 4, 5, 4), (21, 0, 4, 5, 2)])
    return m


# ------------------------------------------------------------------------------------------------
# crystal and shield (separate parts)
# ------------------------------------------------------------------------------------------------

def crystal():
    """Elongated bipyramid of glowing tinted crystal, centred on (8, 8, 8) so it spins in place."""
    m = Model("tower_crystal")
    o = (8, 8, 8)
    r45 = ("y", 45, o)
    m.box("body", (5, 0, 5), (11, 16, 11), "crystal", tint=True, glow=True, rotation=r45)
    m.box("upper", (6, 16, 6), (10, 20, 10), "crystal", tint=True, glow=True, rotation=r45)
    m.box("upper_tip", (7, 20, 7), (9, 22, 9), "crystal", tint=True, glow=True, rotation=r45)
    m.box("lower", (6, -4, 6), (10, 0, 10), "crystal", tint=True, glow=True, rotation=r45)
    m.box("lower_tip", (7, -6, 7), (9, -4, 9), "crystal", tint=True, glow=True, rotation=r45)
    m.box("shard_w", (1, 1, 6.5), (4, 9, 9.5), "crystal", tint=True, glow=True, rotation=("z", 22.5, (2.5, 1, 8)))
    m.box("shard_e", (12, 1, 6.5), (15, 9, 9.5), "crystal", tint=True, glow=True, rotation=("z", -22.5, (13.5, 1, 8)))
    m.box("shard_n", (6.5, 3, 1), (9.5, 10, 4), "crystal", tint=True, glow=True, rotation=("x", -22.5, (8, 3, 2.5)))
    return m


def shield():
    """A stepped translucent dome of thin plates around a tower (hollow, so you see both walls through it)."""
    m = Model("tower_shield")
    tiers = [(-16, 10, 16), (10.5, 20, 14), (20.5, 27, 11), (27.5, 31, 7)]   # (y0, y1, half size)
    t = 0.5
    for n, (y0, y1, r) in enumerate(tiers):
        lo, hi = 8 - r, 8 + r
        m.box(f"wall_n_{n}", (lo, y0, lo), (hi, y1, lo + t), "shield", glow=True)
        m.box(f"wall_s_{n}", (lo, y0, hi - t), (hi, y1, hi), "shield", glow=True)
        m.box(f"wall_w_{n}", (lo, y0, lo + t), (lo + t, y1, hi - t), "shield", glow=True)
        m.box(f"wall_e_{n}", (hi - t, y0, lo + t), (hi, y1, hi - t), "shield", glow=True)
        # the step to the next tier (or the top cap)
        nr = tiers[n + 1][2] if n + 1 < len(tiers) else 0
        nlo, nhi = 8 - nr, 8 + nr
        if nr == 0:
            m.box(f"cap_{n}", (lo, y1, lo), (hi, y1 + t, hi), "shield", glow=True)
            continue
        m.box(f"step_n_{n}", (lo, y1, lo), (hi, y1 + t, nlo), "shield", glow=True)
        m.box(f"step_s_{n}", (lo, y1, nhi), (hi, y1 + t, hi), "shield", glow=True)
        m.box(f"step_w_{n}", (lo, y1, nlo), (nlo, y1 + t, nhi), "shield", glow=True)
        m.box(f"step_e_{n}", (nhi, y1, nlo), (hi, y1 + t, nhi), "shield", glow=True)
    return m


ALL = {}
for _s in range(STAGES):
    ALL[f"tower_front_{_s}"] = (lambda s: lambda: front(s))(_s)
    ALL[f"tower_middle_{_s}"] = (lambda s: lambda: middle(s))(_s)
    ALL[f"tower_core_{_s}"] = (lambda s: lambda: core(s))(_s)
ALL["tower_crystal"] = crystal
ALL["tower_shield"] = shield
