"""
공성전 v2 toy-kingdom buildings (docs/siege-v2.md §7), item models mcpl:structure/<id>:

  장난감 성 망루 (tower)       a cream-brick watchtower on a lilac plinth: arched door, round windows, a team-coloured
                               band and shingled cone roof, a gold finial and a waving team pennant
  왕관 쓴 오르골 성 (core)     a pink music box on gold ball feet (team-coloured lid, a wind-up key turning on its side)
                               carrying a little castle: a keep and four turrets with team roofs, wearing a gold crown
                               that turns slowly like the music box's dancer
  비눗방울 돔 (shields)        a soap-bubble dome per building (t_bubble: the item shader's iridescent film marker)
  사탕 샘물 (fountain)         capture landmark: a candy fountain (pink basin, candy-cane column, syrup streams, a
                               lollipop on top, pennants in the owner's colour)
  망원경 탑 (telescope)        capture landmark: stacked toy blocks with a balcony and a brass telescope that sweeps

Damage stages of the tower and the core (0 intact, 1 cracked + a sticking plaster, 2 heavily damaged: roofs knocked
off, plasters everywhere, toy blocks scattered, 3 the ruin). Buildings are cut into body slabs at display scale 4
(12 blocks each): <id>_body_<stage> (y 0..12) and <id>_top_<stage> (12..24); moving parts are their own models.
Kept in sync with ModelTowerLook.SPECS / CreatureModels (Java).
"""

import math

from mcpl_assets import toy_textures  # noqa: F401  (registers the t_* textures)
from mcpl_assets.toykit import Build

STAGES = 4
BODY = 4                # display scale of building slabs and the core's key
SLAB = 12               # blocks per slab
SHIELD_SCALE = 8
SHIELD_CENTRE = 11.0


def _wall(stage):
    return "t_brick" if stage == 0 else "t_brick_cracked"


def _plaster(b, name, x, y, z, along="z", size=0.9):
    """A sticking plaster (an X of two thin strips) on a wall whose surface is at z (front) or x (side)."""
    h = size / 2
    t = 0.0625
    if along == "z":
        b.box(name + "_a", (x - h, y - 0.15, z - t), (x + h, y + 0.15, z), "t_bandage",
              rotation=("z", 45, (x, y, z)), faces={"south": None})
        b.box(name + "_b", (x - h, y - 0.15, z - 2 * t), (x + h, y + 0.15, z - t), "t_bandage",
              rotation=("z", -45, (x, y, z)), faces={"south": None})
    else:
        b.box(name + "_a", (x, y - 0.15, z - h), (x + t, y + 0.15, z + h), "t_bandage",
              rotation=("x", 45, (x, y, z)), faces={"west": None})
        b.box(name + "_b", (x + t, y - 0.15, z - h), (x + 2 * t, y + 0.15, z + h), "t_bandage",
              rotation=("x", -45, (x, y, z)), faces={"west": None})


TOY_BLOCKS = ("t_pink", "t_sky", "t_butter", "t_mint", "t_lilac")


def _toy_blocks(b, stage, spots):
    """Scattered building blocks: three at stage 2, all of them for the ruin."""
    count = {0: 0, 1: 0, 2: 3, 3: len(spots)}[stage]
    for n, (x, z, s, turn) in enumerate(spots[:count]):
        kw = {"rotation": ("y", turn, (x + s / 2, s / 2, z + s / 2))} if turn else {}
        b.box(f"block_{n}", (x, 0.02, z), (x + s, s, z + s), TOY_BLOCKS[n % len(TOY_BLOCKS)], **kw)


def _window(b, name, cx, cy, z, w=0.8, h=1.0, frame="t_white"):
    """A round-topped window on the front surface z: frame plate, pane, and a sill."""
    b.box(name + "_frame", (cx - w / 2 - 0.125, cy - h / 2, z - 0.0625), (cx + w / 2 + 0.125, cy + h / 2 - 0.2, z),
          frame, faces={"south": None})
    b.box(name + "_frame_top", (cx - w / 2, cy + h / 2 - 0.2, z - 0.0625), (cx + w / 2, cy + h / 2 + 0.05, z),
          frame, faces={"south": None})
    b.box(name, (cx - w / 2 + 0.06, cy - h / 2 + 0.1, z - 0.125), (cx + w / 2 - 0.06, cy + h / 2 - 0.15, z - 0.0625),
          "t_window", faces={"south": None})
    b.box(name + "_sill", (cx - w / 2 - 0.2, cy - h / 2 - 0.15, z - 0.25), (cx + w / 2 + 0.2, cy - h / 2, z),
          "t_stone", faces={"south": None})


def _side_window(b, name, cx, cy, x, w=0.8, h=1.0):
    """The same window on the east wall (surface x, facing +x)."""
    b.box(name + "_frame", (x, cy - h / 2, cx - w / 2 - 0.125), (x + 0.0625, cy + h / 2 - 0.2, cx + w / 2 + 0.125),
          "t_white", faces={"west": None})
    b.box(name, (x + 0.0625, cy - h / 2 + 0.1, cx - w / 2 + 0.06), (x + 0.125, cy + h / 2 - 0.15, cx + w / 2 - 0.06),
          "t_window", faces={"west": None})
    b.box(name + "_sill", (x, cy - h / 2 - 0.15, cx - w / 2 - 0.2), (x + 0.25, cy - h / 2, cx + w / 2 + 0.2),
          "t_stone", faces={"west": None})


def _door(b, z, w=1.2, h=1.8, y0=0.75):
    """An arched wooden door with a stone frame and a gold knob, on the front surface z."""
    b.box("door_frame_l", (-w / 2 - 0.25, y0, z - 0.125), (-w / 2, y0 + h + 0.2, z), "t_stone", faces={"south": None})
    b.box("door_frame_r", (w / 2, y0, z - 0.125), (w / 2 + 0.25, y0 + h + 0.2, z), "t_stone", faces={"south": None})
    b.box("door_frame_top", (-w / 2 - 0.25, y0 + h + 0.2, z - 0.125), (w / 2 + 0.25, y0 + h + 0.45, z), "t_stone",
          faces={"south": None})
    b.box("door", (-w / 2, y0, z - 0.0625), (w / 2, y0 + h, z), "t_wood", faces={"south": None})
    b.box("door_arch", (-w / 2 + 0.2, y0 + h, z - 0.0625), (w / 2 - 0.2, y0 + h + 0.2, z), "t_wood",
          faces={"south": None})
    b.box("door_knob", (w / 2 - 0.3, y0 + h * 0.45, z - 0.1875), (w / 2 - 0.15, y0 + h * 0.45 + 0.15, z - 0.0625),
          "t_gold", faces={"south": None})


def _cone(b, name, cx, cz, y0, tiers, tex="t_roof_tint", tint=True, r=0.125):
    """A stepped cone roof: tiers = [(half width, height)] from the eaves up."""
    y = y0
    for n, (half, h) in enumerate(tiers):
        b.rbox(f"{name}_{n}", (cx - half, y, cz - half), (cx + half, y + h, cz + half), tex, r, bottom=False,
               tint=tint)
        y += h
    return y


# ------------------------------------------------------------------------------------------------
# 장난감 성 망루 (tower)
# ------------------------------------------------------------------------------------------------

# tier boundaries meet the slab border at y = 12 (a tier split there would z-fight with its own arms)
TOWER_ROOF = [(2.75, 0.95), (2.3, 0.95), (1.85, 0.975), (1.4, 0.85), (0.95, 0.8), (0.55, 0.7)]


def tower_body(stage):
    b = Build()
    ruin = stage >= 3
    wall = _wall(stage)
    b.rbox("plinth", (-2.75, 0, -2.75), (2.75, 0.75, 2.75), "t_stone", 0.375, bottom=False)
    if ruin:
        # a broken stub with uneven brick lumps, the roof lying beside it
        b.rbox("wall", (-2, 0.75, -2), (2, 2.5, 2), wall, 0.375, bottom=False, top=False)
        b.rbox("lump_0", (-2, 2.5, -2), (-0.25, 3.5, 0.5), wall, 0.25, bottom=False)
        b.rbox("lump_1", (0.5, 2.5, -0.5), (2, 4.25, 2), wall, 0.25, bottom=False)
        b.rbox("lump_2", (-1.75, 2.5, 0.75), (0.25, 3.0, 2), wall, 0.2, bottom=False)
        _door(b, -2.0, h=1.2)
        for n, (half, h) in enumerate(TOWER_ROOF[:3]):
            y = 0.02 + n * 0.55
            b.box(f"fallen_roof_{n}", (3.0 - half * 0.6, y, -1.0 - half * 0.6), (3.0 + half * 0.6, y + 0.55,
                  -1.0 + half * 0.6), "t_roof_tint", tint=True, rotation=("y", 22.5, (3.0, y, -1.0)))
        _plaster(b, "plaster", -1.0, 1.9, -2.0)
        _toy_blocks(b, stage, TOWER_BLOCKS)
        return b
    b.rbox("wall", (-2, 0.75, -2), (2, 8.5, 2), wall, 0.375, bottom=False, top=False)
    b.rbox("band", (-2.0625, 3.0, -2.0625), (2.0625, 3.375, 2.0625), "t_paint_tint", 0.375, top=False,
           bottom=False, tint=True)
    _door(b, -2.0)
    _window(b, "win_l", -0.85, 5.4, -2.0)
    _window(b, "win_r", 0.85, 5.4, -2.0)
    _side_window(b, "win_e", 0, 5.4, 2.0)
    b.mirror("blush", (-1.55, 4.45, -2.03125), (-1.15, 4.6, -2.0), "t_rose", faces={"south": None})
    b.mirror("corbel", (-2.4, 7.75, -0.6), (-2.0, 8.5, 0.6), "t_stone")
    b.box("corbel_n", (-0.6, 7.75, -2.4), (0.6, 8.5, -2.0), "t_stone")
    b.box("corbel_s", (-0.6, 7.75, 2.0), (0.6, 8.5, 2.4), "t_stone")
    b.rbox("cornice", (-2.5, 8.5, -2.5), (2.5, 9.125, 2.5), "t_stone", 0.25, bottom=False)
    roof = TOWER_ROOF if stage < 2 else TOWER_ROOF[:3]
    top = _cone(b, "roof", 0, 0, 9.125, roof)
    if stage < 2:
        b.rbox("finial", (-0.3, top, -0.3), (0.3, top + 0.6, 0.3), "t_gold", 0.1, bottom=False)
        b.box("pole", (-0.0625, top + 0.6, -0.0625), (0.0625, 17.2, 0.0625), "t_metal")
        b.box("pole_ball", (-0.15, 17.2, -0.15), (0.15, 17.5, 0.15), "t_gold")
    else:
        # the top of the roof knocked off: a jagged rim of shingles
        b.box("roof_chip", (-1.0, top, -0.6), (0.2, top + 0.35, 0.8), "t_roof_tint", tint=True)
    if stage >= 1:
        _plaster(b, "plaster", 1.15, 2.2, -2.0)
    if stage >= 2:
        _plaster(b, "plaster_2", -1.2, 6.9, -2.0)
        _plaster(b, "plaster_3", 2.0, 2.4, 0.8, along="x")
        b.box("hole", (0.4, 6.6, -2.0625), (1.2, 7.2, -2.0), "t_navy", faces={"south": None})
    _toy_blocks(b, stage, TOWER_BLOCKS)
    return b


TOWER_BLOCKS = [(-3.9, -2.6, 0.7, 22.5), (3.2, 1.9, 0.6, 0), (-1.0, -4.0, 0.55, 45), (2.6, -3.6, 0.8, 22.5),
                (-3.6, 2.4, 0.6, 0), (1.0, 3.3, 0.5, 45)]


def tower_flag():
    """The pennant on the pole (rotates about the pole: x = z = 0), team colour."""
    b = Build()
    b.box("flag", (0.06, 15.9, -0.03), (1.3, 16.8, 0.03), "t_cloth_tint", tint=True)
    b.box("flag_tail_a", (1.3, 16.35, -0.03), (1.75, 16.8, 0.03), "t_cloth_tint", tint=True)
    b.box("flag_tail_b", (1.3, 15.9, -0.03), (1.75, 16.25, 0.03), "t_cloth_tint", tint=True)
    b.box("flag_heart", (0.5, 16.2, -0.06), (0.8, 16.5, 0.06), "t_white")
    return b


FLAG_CENTRE = 16.35
FLAG_SCALE = 2


# ------------------------------------------------------------------------------------------------
# 왕관 쓴 오르골 성 (core)
# ------------------------------------------------------------------------------------------------

TURRETS = [(-1, -1), (1, -1), (-1, 1), (1, 1)]
TURRET_ROOF = [(1.15, 0.75), (0.85, 0.75), (0.55, 0.7), (0.28, 0.55)]   # 9.8 + 2.2 = the slab border


def core_body(stage):
    b = Build()
    ruin = stage >= 3
    wall = _wall(stage)
    # the music box
    for n, (sx, sz) in enumerate(TURRETS):
        b.rbox(f"foot_{n}", (sx * 3.0 - 0.45, 0, sz * 3.0 - 0.45), (sx * 3.0 + 0.45, 0.55, sz * 3.0 + 0.45),
               "t_gold", 0.15)
    b.rbox("box", (-3.5, 0.5, -3.5), (3.5, 3.25, 3.5), "t_pink", 0.375, top=False)
    b.rbox("trim_low", (-3.5625, 0.75, -3.5625), (3.5625, 1.0, 3.5625), "t_gold", 0.375, top=False, bottom=False)
    b.rbox("trim_high", (-3.5625, 2.75, -3.5625), (3.5625, 3.0, 3.5625), "t_gold", 0.375, top=False, bottom=False)
    # the front plaque: a cream drawer with a gold music note
    b.box("plaque", (-1.4, 1.2, -3.5625), (1.4, 2.5, -3.5), "t_cream", faces={"south": None})
    b.box("note_head", (-0.35, 1.45, -3.625), (0.05, 1.75, -3.5625), "t_gold", faces={"south": None})
    b.box("note_stem", (-0.05, 1.75, -3.625), (0.05, 2.3, -3.5625), "t_gold", faces={"south": None})
    b.box("note_flag", (0.05, 2.05, -3.625), (0.35, 2.3, -3.5625), "t_gold", faces={"south": None})
    # the key's socket on the east side
    b.box("key_socket", (-3.625, 1.65, -0.3), (-3.5, 2.35, 0.3), "t_gold", faces={"east": None})
    if ruin:
        # the lid knocked askew, the castle in pieces
        b.box("lid", (-3.6, 3.25, -3.6), (3.6, 3.7, 3.6), "t_paint_tint", tint=True,
              rotation=("x", 22.5, (0, 3.25, 3.6)))
        b.rbox("keep", (-1.8, 3.4, -1.0), (0.4, 4.8, 1.4), wall, 0.25, bottom=False)
        b.rbox("turret_stub", (1.6, 3.4, 1.4), (3.0, 4.2, 2.8), wall, 0.2, bottom=False)
        _plaster(b, "plaster", -2.0, 2.1, -3.5)
        _toy_blocks(b, stage, CORE_BLOCKS)
        return b
    b.rbox("lid", (-3.75, 3.25, -3.75), (3.75, 3.75, 3.75), "t_paint_tint", 0.25, bottom=False, tint=True)
    # the castle: a keep between four corner turrets (they stand apart so the keep's door shows)
    b.rbox("keep", (-1.75, 3.75, -1.75), (1.75, 10.0, 1.75), wall, 0.25, bottom=False, top=False)
    _door(b, -1.75, w=1.1, h=1.6, y0=3.75)
    _window(b, "win", 0, 7.4, -1.75, w=1.1, h=1.2)
    _side_window(b, "win_e", 0, 7.2, 1.75)
    b.rbox("cornice", (-2.05, 10.0, -2.05), (2.05, 10.5, 2.05), "t_stone", 0.2, bottom=False)
    for n, (sx, sz) in enumerate(TURRETS):
        cx, cz = sx * 2.75, sz * 2.75
        fallen = stage >= 2 and n in (1, 2)
        top = 8.0 if fallen else 9.5
        b.rbox(f"turret_{n}", (cx - 0.75, 3.75, cz - 0.75), (cx + 0.75, top, cz + 0.75), wall, 0.18, bottom=False,
               top=fallen)
        b.box(f"turret_win_{n}", (cx - 0.2, 6.2, cz - 0.7625), (cx + 0.2, 6.9, cz - 0.75), "t_window",
              faces={"south": None})
        if fallen:
            continue
        b.rbox(f"turret_cornice_{n}", (cx - 0.9, top, cz - 0.9), (cx + 0.9, top + 0.3, cz + 0.9), "t_stone", 0.15,
               bottom=False)
        y = _cone(b, f"turret_roof_{n}", cx, cz, top + 0.3, TURRET_ROOF)
        b.box(f"turret_ball_{n}", (cx - 0.15, y, cz - 0.15), (cx + 0.15, y + 0.3, cz + 0.15), "t_gold")
    if stage >= 1:
        _plaster(b, "plaster", -2.2, 2.0, -3.5)
        _plaster(b, "plaster_2", 1.0, 5.6, -1.75)
    if stage >= 2:
        _plaster(b, "plaster_3", 1.75, 6.0, -0.6, along="x")
        b.box("hole", (-1.4, 5.0, -1.8125), (-0.8, 5.6, -1.75), "t_navy", faces={"south": None})
    _toy_blocks(b, stage, CORE_BLOCKS)
    return b


CORE_BLOCKS = [(-4.6, -3.0, 0.7, 22.5), (4.0, 2.4, 0.6, 0), (-1.8, -4.9, 0.6, 45), (4.1, -3.4, 0.8, 22.5),
               (-4.4, 2.8, 0.6, 0), (1.2, 4.2, 0.55, 45)]


def core_crown():
    """The big gold crown on the keep (turns about y like the music box's dancer): a band with tall points at the
    sides, small ones at the corners, team-coloured gems and a team velvet cushion."""
    b = Build()
    y0, h, half = CROWN_Y, 0.9, 1.8
    b.rbox("cushion", (-1.5, y0, -1.5), (1.5, y0 + 0.8, 1.5), "t_paint_tint", 0.25, bottom=False, tint=True)
    b.ring("band", half, y0 + 0.1, y0 + 0.1 + h, 0.3, "t_gold")
    top = y0 + 0.1 + h
    for n in range(4):
        ang = n * 90
        cx = (half - 0.15) * math.sin(math.radians(ang))
        cz = -(half - 0.15) * math.cos(math.radians(ang))
        wx, wz = (0.35, 0.15) if n % 2 == 0 else (0.15, 0.35)
        b.box(f"point_{n}", (cx - wx, top, cz - wz), (cx + wx, top + 0.9, cz + wz), "t_gold")
        b.box(f"point_top_{n}", (cx - wx * 0.6, top + 0.9, cz - wz * 0.6 - 0.01), (cx + wx * 0.6, top + 1.25,
              cz + wz * 0.6 + 0.01), "t_gold")
        b.rbox(f"tip_{n}", (cx - 0.2, top + 1.25, cz - 0.2), (cx + 0.2, top + 1.6, cz + 0.2), "t_paint_tint", 0.06,
               tint=True, glow=True)
        gx, gz = (0.22, 0.34) if n % 2 == 0 else (0.34, 0.22)
        b.box(f"gem_{n}", (cx - gx, y0 + 0.35, cz - gz), (cx + gx, y0 + 0.75, cz + gz), "t_paint_tint",
              tint=True, glow=True)
    for n, (sx, sz) in enumerate(TURRETS):
        b.box(f"corner_{n}", (sx * 1.65 - 0.15, top, sz * 1.65 - 0.15), (sx * 1.65 + 0.15, top + 0.5,
              sz * 1.65 + 0.15), "t_gold")
        b.box(f"corner_ball_{n}", (sx * 1.65 - 0.12, top + 0.5, sz * 1.65 - 0.12), (sx * 1.65 + 0.12, top + 0.75,
              sz * 1.65 + 0.12), "t_white")
    return b


CROWN_Y = 10.5
CROWN_CENTRE = 12.0
CROWN_SCALE = 2


def core_key():
    """The music box's wind-up key on the west side (turns about the x axis through y = KEY_CENTRE, z = 0)."""
    b = Build()
    c = KEY_CENTRE
    b.box("shaft", (-4.4, c - 0.12, -0.12), (-3.625, c + 0.12, 0.12), "t_gold")
    b.box("collar", (-4.1, c - 0.22, -0.22), (-3.9, c + 0.22, 0.22), "t_gold")
    b.rbox("bow_hub", (-4.7, c - 0.3, -0.3), (-4.4, c + 0.3, 0.3), "t_gold", 0.08)
    for side in (-1, 1):
        z0, z1 = (0.3, 1.05) if side > 0 else (-1.05, -0.3)
        b.rbox(f"bow_{side}", (-4.65, c - 0.45, z0), (-4.45, c + 0.45, z1), "t_gold", 0.12)
    return b


KEY_CENTRE = 2.0


# ------------------------------------------------------------------------------------------------
# 비눗방울 돔 (shield)
# ------------------------------------------------------------------------------------------------

def bubble(rx, height, tiers=9, sink=0.5):
    """A soap-bubble dome: stacked octagonal rings of thin film plates following an ellipse (rx wide, `height`
    tall above the ground, starting `sink` blocks below it)."""
    b = Build()
    t = 0.0625
    prof = []
    for n in range(tiers + 1):
        a = (n / tiers) * (math.pi / 2)
        prof.append((-sink + (height + sink) * math.sin(a), rx * math.cos(a)))
    for n in range(tiers):
        (y0, r0), (y1, r1) = prof[n], prof[n + 1]
        r = (r0 + r1) / 2
        if r < 0.6:
            b.box(f"cap_{n}", (-r0 * 0.75, y0 + t, -r0 * 0.75), (r0 * 0.75, y0 + 2 * t, r0 * 0.75), "t_bubble",
                  glow=True)
            break
        side = 2 * r * math.tan(math.pi / 8) + 0.02
        for k in range(8):
            ang = k * 45
            cx, cz = r * math.sin(math.radians(ang)), -r * math.cos(math.radians(ang))
            y = (y0, y1)
            if k in (0, 4):
                b.box(f"r{n}_{k}", (cx - side / 2, y[0], cz - t / 2), (cx + side / 2, y[1], cz + t / 2), "t_bubble",
                      glow=True)
            elif k in (2, 6):
                b.box(f"r{n}_{k}", (cx - t / 2, y[0], cz - side / 2), (cx + t / 2, y[1], cz + side / 2), "t_bubble",
                      glow=True)
            else:
                angle = -45 if k in (1, 5) else 45
                b.box(f"r{n}_{k}", (cx - side / 2, y[0], cz - t / 2), (cx + side / 2, y[1], cz + t / 2), "t_bubble",
                      glow=True, rotation=("y", angle, (cx, (y0 + y1) / 2, cz)))
        # the ledge to the next (narrower) ring
        if n + 1 < tiers:
            nr = (prof[n + 1][1] + prof[min(tiers, n + 2)][1]) / 2
            w = r - nr
            if w > 0.05:
                nside = 2 * nr * math.tan(math.pi / 8)
                b.box(f"l{n}_n", (-nside / 2, y1, -r), (nside / 2, y1 + t, -nr), "t_bubble", glow=True)
                b.box(f"l{n}_s", (-nside / 2, y1, nr), (nside / 2, y1 + t, r), "t_bubble", glow=True)
                b.box(f"l{n}_w", (-r, y1, -nside / 2), (-nr, y1 + t, nside / 2), "t_bubble", glow=True)
                b.box(f"l{n}_e", (nr, y1, -nside / 2), (r, y1 + t, nside / 2), "t_bubble", glow=True)
    return b


SHIELDS = {"tower": (4.4, 18.0), "core": (5.75, 14.75)}


# ------------------------------------------------------------------------------------------------
# 사탕 샘물 (fountain) and 망원경 탑 (telescope): capture landmarks
# ------------------------------------------------------------------------------------------------

def fountain():
    b = Build()
    b.rbox("basin", (-2.5, 0, -2.5), (2.5, 0.9, 2.5), "t_pink", 0.3, bottom=False, top=False)
    b.ring("rim", 2.6, 0.9, 1.2, 0.35, "t_white")
    b.box("pool", (-2.25, 0.8, -2.25), (2.25, 1.0, 2.25), "t_syrup", glow=True)
    b.rbox("column", (-0.35, 1.0, -0.35), (0.35, 2.6, 0.35), "t_candy", 0.08, bottom=False)
    b.rbox("bowl", (-1.15, 2.6, -1.15), (1.15, 3.0, 1.15), "t_mint", 0.2, top=False)
    b.ring("bowl_rim", 1.25, 3.0, 3.2, 0.2, "t_white")
    b.box("bowl_syrup", (-1.05, 2.9, -1.05), (1.05, 3.08, 1.05), "t_syrup", glow=True)
    for n, (dx, dz) in enumerate(((0, -1), (1, 0), (0, 1), (-1, 0))):
        if dz:
            b.box(f"stream_{n}", (-0.1, 1.0, dz * 1.22 - 0.05), (0.1, 2.9, dz * 1.22 + 0.05), "t_syrup", glow=True)
        else:
            b.box(f"stream_{n}", (dx * 1.22 - 0.05, 1.0, -0.1), (dx * 1.22 + 0.05, 2.9, 0.1), "t_syrup", glow=True)
    b.box("stick", (-0.06, 3.08, -0.06), (0.06, 3.9, 0.06), "t_white")
    b.rbox("lollipop", (-0.5, 3.9, -0.12), (0.5, 4.9, 0.12), "t_candy", 0.2)
    b.box("bow_l", (-0.35, 3.6, -0.08), (-0.06, 3.85, 0.08), "t_cloth_tint", tint=True)
    b.box("bow_r", (0.06, 3.6, -0.08), (0.35, 3.85, 0.08), "t_cloth_tint", tint=True)
    for n, (sx, sz) in enumerate(TURRETS):
        x, z = sx * 2.4, sz * 2.4
        b.box(f"post_{n}", (x - 0.08, 1.2, z - 0.08), (x + 0.08, 2.4, z + 0.08), "t_white")
        b.box(f"post_ball_{n}", (x - 0.14, 2.4, z - 0.14), (x + 0.14, 2.65, z + 0.14), "t_gold")
        b.box(f"pennant_{n}", (x + 0.08, 1.85, z - 0.03), (x + 0.5, 2.35, z + 0.03), "t_cloth_tint", tint=True)
    return b


FOUNTAIN_SCALE = 2
FOUNTAIN_CENTRE = 2.5


def telescope():
    b = Build()
    b.rbox("block_0", (-1.6, 0, -1.6), (1.6, 1.6, 1.6), "t_sky", 0.2, bottom=False)
    b.rbox("block_1", (-1.3, 1.6, -1.3), (1.3, 3.0, 1.3), "t_butter", 0.2, bottom=False)
    b.box("block_2", (-1.0, 3.0, -1.0), (1.0, 4.4, 1.0), "t_pink", rotation=("y", 45, (0, 3.7, 0)))
    b.rbox("block_3", (-1.2, 4.4, -1.2), (1.2, 5.6, 1.2), "t_mint", 0.2, bottom=False)
    # big painted letters / shapes on the blocks: a star, a moon, a heart dot
    b.box("star", (-0.35, 0.45, -1.65), (0.35, 1.15, -1.6), "t_butter", faces={"south": None})
    b.box("moon", (-0.35, 1.95, -1.35), (0.35, 2.65, -1.3), "t_navy", faces={"south": None})
    b.box("dot", (-0.3, 4.7, -1.25), (0.3, 5.3, -1.2), "t_rose", faces={"south": None})
    b.rbox("balcony", (-1.7, 5.6, -1.7), (1.7, 5.9, 1.7), "t_wood", 0.15, bottom=False)
    for n, (sx, sz) in enumerate(TURRETS):
        b.box(f"rail_post_{n}", (sx * 1.55 - 0.08, 5.9, sz * 1.55 - 0.08), (sx * 1.55 + 0.08, 6.5, sz * 1.55 + 0.08),
              "t_white")
    b.ring("rail", 1.65, 6.4, 6.55, 0.12, "t_white")
    b.box("pole", (1.45, 6.55, 1.45), (1.55, 7.8, 1.55), "t_white")
    b.box("pennant", (1.55, 7.3, 1.47), (2.2, 7.75, 1.53), "t_cloth_tint", tint=True)
    return b


TELESCOPE_SCALE = 3
TELESCOPE_CENTRE = 4.5


def telescope_scope():
    """The telescope on the balcony (turns about y), looking out to -z and a little up."""
    b = Build()
    c = SCOPE_CENTRE
    b.box("leg_l", (-0.45, 5.9, -0.05), (-0.35, c - 0.1, 0.05), "t_wood", rotation=("z", -22.5, (-0.4, 5.9, 0)))
    b.box("leg_r", (0.35, 5.9, -0.05), (0.45, c - 0.1, 0.05), "t_wood", rotation=("z", 22.5, (0.4, 5.9, 0)))
    b.box("leg_b", (-0.05, 5.9, 0.35), (0.05, c - 0.1, 0.45), "t_wood", rotation=("x", 22.5, (0, 5.9, 0.4)))
    b.box("tube", (-0.25, c - 0.25, -1.4), (0.25, c + 0.25, 0.6), "t_sky", rotation=("x", 22.5, (0, c, 0)))
    b.box("tube_front", (-0.35, c - 0.35, -1.7), (0.35, c + 0.35, -1.3), "t_gold", rotation=("x", 22.5, (0, c, 0)))
    b.box("lens", (-0.25, c - 0.25, -1.75), (0.25, c + 0.25, -1.7), "t_window", rotation=("x", 22.5, (0, c, 0)))
    b.box("eyepiece", (-0.12, c - 0.12, 0.6), (0.12, c + 0.12, 0.9), "t_gold", rotation=("x", 22.5, (0, c, 0)))
    b.box("band", (-0.3, c - 0.3, -0.2), (0.3, c + 0.3, 0.1), "t_gold", rotation=("x", 22.5, (0, c, 0)))
    return b


SCOPE_CENTRE = 6.8
SCOPE_SCALE = 2


# ------------------------------------------------------------------------------------------------
# registry: model id (below models/, without .bbmodel) -> builder of the Model
# ------------------------------------------------------------------------------------------------

def _slab(builder, kind, part, slab):
    def make(stage):
        models = builder(stage).slabs(f"structure/{kind}_{part}_{stage}", 2, BODY, SLAB)
        m = models[slab]
        if m is None:
            return None
        m.name = f"structure/{kind}_{part}_{stage}"
        return m
    return make


ALL = {}
for _s in range(STAGES):
    ALL[f"structure/tower_body_{_s}"] = (lambda s: lambda: _slab(tower_body, "tower", "body", 0)(s))(_s)
    ALL[f"structure/core_body_{_s}"] = (lambda s: lambda: _slab(core_body, "core", "body", 0)(s))(_s)
for _s in range(STAGES - 1):
    ALL[f"structure/tower_top_{_s}"] = (lambda s: lambda: _slab(tower_body, "tower", "top", 1)(s))(_s)
    ALL[f"structure/core_top_{_s}"] = (lambda s: lambda: _slab(core_body, "core", "top", 1)(s))(_s)
ALL["structure/tower_flag"] = lambda: tower_flag().centred("structure/tower_flag", FLAG_SCALE, FLAG_CENTRE)
ALL["structure/core_crown"] = lambda: core_crown().centred("structure/core_crown", CROWN_SCALE, CROWN_CENTRE)
ALL["structure/core_key"] = lambda: core_key().centred("structure/core_key", BODY, KEY_CENTRE)
for _k, (_rx, _h) in SHIELDS.items():
    ALL[f"structure/{_k}_shield"] = (lambda k, rx, h: lambda: bubble(rx, h).centred(
        f"structure/{k}_shield", SHIELD_SCALE, SHIELD_CENTRE))(_k, _rx, _h)
ALL["structure/fountain"] = lambda: fountain().centred("structure/fountain", FOUNTAIN_SCALE, FOUNTAIN_CENTRE)
ALL["structure/telescope"] = lambda: telescope().centred("structure/telescope", TELESCOPE_SCALE, TELESCOPE_CENTRE)
ALL["structure/telescope_scope"] = lambda: telescope_scope().centred("structure/telescope_scope", SCOPE_SCALE,
                                                                     SCOPE_CENTRE)

# preview placement of every part: building -> [(model id or None for the staged slabs, centre, scale, motion)]
PARTS = {
    "tower": [("body", 4, 6.0, BODY, "none"), ("top", 3, 18.0, BODY, "none"),
              ("flag", 0, FLAG_CENTRE, FLAG_SCALE, "flag"), ("shield", 0, SHIELD_CENTRE, SHIELD_SCALE, "shield")],
    "core": [("body", 4, 6.0, BODY, "none"), ("top", 3, 18.0, BODY, "none"),
             ("crown", 0, CROWN_CENTRE, CROWN_SCALE, "crown"), ("key", 0, KEY_CENTRE, BODY, "key"),
             ("shield", 0, SHIELD_CENTRE, SHIELD_SCALE, "shield")],
}
