"""
공성전 v2 toy-kingdom buildings (docs/siege-v2.md §7), item models mcpl:structure/<id>:

  장난감 성 망루 (tower)       a cream-brick watchtower on a lilac plinth: arched door, round windows, a team-coloured
                               band and shingled cone roof, a gold finial and a waving team pennant
  왕관 쓴 오르골 성 (core)     1.5 times its old size: a pink music box on gold ball feet (music notes, the drum
                               and comb in a front window, a team lid set with glowing gems, a wind-up key turning on
                               its side) carrying a castle: a keep with a face, a tall team spire whose tip jewel
                               turns, four turrets with team bands and roofs, a big gold crown turning round the
                               spire's foot like the music box's dancer
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
from mcpl_assets.toykit import EPS, Build

STAGES = 4
BODY = 4                # display scale of building slabs and the core's key
SLAB = 12               # blocks per slab
SHIELD_SCALE = 8
SHIELD_CENTRE = 11.0


def _wall(stage):
    return "t_brick" if stage == 0 else "t_brick_cracked"


def _plaster(b, name, x, y, z, along="z", size=0.9):
    """A sticking plaster (an X of two thin strips) on a wall whose surface is at z (front) or x (side). Keep it on
    the flat wall, clear of door and window frames: its outer strip is as deep as a frame (z-fighting)."""
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
        _plaster(b, "plaster", -1.25, 1.9, -2.0, size=0.62)
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
        _plaster(b, "plaster", 1.25, 2.2, -2.0, size=0.62)
    if stage >= 2:
        _plaster(b, "plaster_2", -1.2, 6.9, -2.0)
        _plaster(b, "plaster_3", 2.0, 2.4, 0.8, along="x")
        b.box("hole", (0.4, 6.6, -2.0625), (1.2, 7.2, -2.0), "t_navy", faces={"south": None})
    _toy_blocks(b, stage, TOWER_BLOCKS)
    return b


TOWER_BLOCKS = [(-3.9, -2.6, 0.7, 22.5), (3.2, 1.9, 0.6, 0), (-1.0, -4.0, 0.55, 45), (2.6, -4.4, 0.8, 22.5),
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

# The core is designed at twice the old size (a 14 x 14 music box, the spire's jewel at ~34 blocks) as slabs of 24
# blocks at display scale 8, and shown at CORE_SHOWN of that (every part's centre and scale, PARTS; ModelTowerLook
# CORE_SIZE): 1.5 times the old size, a 10.5 x 10.5 music box under a ~26-block spire. Stepped roof tiers end exactly on
# the slab border y = 24 (as designed).
CORE_SHOWN = 0.75
CORE_BODY = 8
CORE_SLAB = 24
CORE_TURRET_ROOF = [(2.0, 1.5), (1.55, 1.5), (1.05, 1.4), (0.55, 1.2)]     # 19.6 + 4.4 = the slab border
SPIRE_Y = 20.0
SPIRE = [(2.4, 1.4), (2.0, 1.3), (1.65, 1.3), (1.3, 1.8), (1.0, 1.9), (0.7, 2.0), (0.38, 1.8)]   # 20 + 4.0 = 24
SPIRE_TOP = SPIRE_Y + sum(h for _w, h in SPIRE)


def _note(b, name, x, y, z, side):
    """A gold music note (head, stem, flag) on a box face: side 'south' = the front (surface z), 'east'/'west' = the
    side surfaces x (x is the surface, z the note's centre)."""
    t = 0.125
    if side == "south":
        b.box(name + "_head", (x - 0.45, y, z - t), (x + 0.15, y + 0.45, z), "t_gold", faces={"south": None})
        b.box(name + "_stem", (x + 0.0, y + 0.45, z - t), (x + 0.15, y + 1.5, z), "t_gold", faces={"south": None})
        b.box(name + "_flag", (x + 0.15, y + 1.05, z - t), (x + 0.6, y + 1.5, z), "t_gold", faces={"south": None})
        return
    sign = 1 if side == "east" else -1
    x0, x1 = (x, x + t) if sign > 0 else (x - t, x)
    hide = {"west": None} if sign > 0 else {"east": None}
    b.box(name + "_head", (x0, y, z - 0.3), (x1, y + 0.45, z + 0.3), "t_gold", faces=hide)
    b.box(name + "_stem", (x0, y + 0.45, z + 0.15), (x1, y + 1.5, z + 0.3), "t_gold", faces=hide)
    b.box(name + "_flag", (x0, y + 1.05, z + 0.3), (x1, y + 1.5, z + 0.75), "t_gold", faces=hide)


def core_body(stage):
    """왕관 쓴 오르골 성 as designed (shown at CORE_SHOWN): a pink music box on gold ball feet with gold bands, music
    notes on every side and its open drum and comb in a cream window at the front, a team lid set with gems; on it a
    tall keep with a face of round windows, a team spire to 31.5 blocks, four corner turrets with team bands and roofs."""
    b = Build()
    ruin = stage >= 3
    wall = _wall(stage)
    for n, (sx, sz) in enumerate(TURRETS):
        b.rbox(f"foot_{n}", (sx * 6.0 - 0.9, 0, sz * 6.0 - 0.9), (sx * 6.0 + 0.9, 1.1, sz * 6.0 + 0.9), "t_gold", 0.3)
    b.rbox("box", (-7, 1.0, -7), (7, 6.5, 7), "t_pink", 0.75, top=False)
    b.rbox("trim_low", (-7.125, 1.5, -7.125), (7.125, 2.0, 7.125), "t_gold", 0.75, top=False, bottom=False)
    b.rbox("trim_high", (-7.125, 5.5, -7.125), (7.125, 6.0, 7.125), "t_gold", 0.75, top=False, bottom=False)
    # the front: a cream window with the music drum (gold, white pins) over its tin comb
    b.box("window_frame", (-4.0, 2.25, -7.125), (4.0, 5.25, -7.0), "t_cream", faces={"south": None})
    b.box("window_back", (-3.6, 2.55, -7.25), (3.6, 4.95, -7.125), "t_navy", faces={"south": None})
    b.box("drum", (-3.2, 3.55, -7.75), (3.2, 4.55, -7.25), "t_gold", faces={"south": None})
    b.box("drum_end_l", (-3.45, 3.4, -7.825), (-3.2, 4.7, -7.25), "t_gold", faces={"south": None})
    b.box("drum_end_r", (3.2, 3.4, -7.825), (3.45, 4.7, -7.25), "t_gold", faces={"south": None})
    for n in range(7):
        x = -2.7 + n * 0.9
        y = 3.7 if n % 2 else 4.15
        b.box(f"pin_{n}", (x - 0.1, y, -7.875), (x + 0.1, y + 0.2, -7.75), "t_white", faces={"south": None})
    b.box("comb", (-3.2, 2.8, -7.5), (3.2, 3.3, -7.25), "t_metal", faces={"south": None})
    # music notes on the sides and the back, the key's socket on the west side
    _note(b, "note_e1", 7.0, 2.6, -2.5, "east")
    _note(b, "note_e2", 7.0, 3.2, 2.0, "east")
    _note(b, "note_w1", -7.0, 2.6, -4.0, "west")
    _note(b, "note_w2", -7.0, 3.2, 3.5, "west")
    b.box("key_socket", (-7.25, 3.3, -0.6), (-7.0, 4.7, 0.6), "t_gold", faces={"east": None})
    if ruin:
        b.box("lid", (-7.6, 6.5, -7.6), (7.6, 7.4, 7.6), "t_paint_tint", tint=True,
              rotation=("x", 22.5, (0, 6.5, 7.6)))
        b.rbox("keep", (-3.6, 6.8, -2.0), (0.8, 9.6, 2.8), wall, 0.5, bottom=False)
        b.rbox("turret_stub", (3.2, 6.8, 2.8), (6.0, 8.4, 5.6), wall, 0.4, bottom=False)
        b.box("spire_tip", (-6.2, 0.02, 9.0), (-4.8, 1.4, 10.4), "t_roof_tint", tint=True,
              rotation=("y", 22.5, (-5.5, 0.7, 9.7)))
        _plaster(b, "plaster", -4.85, 4.2, -7.0, size=1.8)
        _toy_blocks(b, stage, CORE_BLOCKS)
        return b
    b.rbox("lid", (-7.5, 6.5, -7.5), (7.5, 7.5, 7.5), "t_paint_tint", 0.5, bottom=False, tint=True)
    b.rbox("lid_band", (-7.625, 6.6, -7.625), (7.625, 6.9, 7.625), "t_gold", 0.5, top=False, bottom=False)
    for n, x in enumerate((-5.0, -1.7, 1.7, 5.0)):
        b.box(f"lid_gem_{n}", (x - 0.3, 6.6, -7.75), (x + 0.3, 7.2, -7.5), "t_paint_tint", tint=True, glow=True,
              faces={"south": None})
    # the keep, with a face: two round windows over a blushing balcony window and the door
    b.rbox("keep", (-3.5, 7.5, -3.5), (3.5, 19.0, 3.5), wall, 0.5, bottom=False, top=False)
    _door(b, -3.5, w=2.2, h=3.2, y0=7.5)
    _window(b, "win", 0, 13.4, -3.5, w=2.0, h=2.2)
    b.rbox("balcony", (-1.7, 11.9, -4.1), (1.7, 12.25, -3.5), "t_stone", 0.12, bottom=False)
    _window(b, "eye_l", -1.7, 16.4, -3.5, w=1.1, h=1.2)
    _window(b, "eye_r", 1.7, 16.4, -3.5, w=1.1, h=1.2)
    b.mirror("blush", (-3.0, 15.2, -3.53125), (-2.4, 15.45, -3.5), "t_rose", faces={"south": None})
    _side_window(b, "win_e", 0, 13.4, 3.5, w=1.6, h=2.0)
    b.rbox("cornice", (-4.1, 19.0, -4.1), (4.1, 20.0, 4.1), "t_stone", 0.4, bottom=False)
    spire = SPIRE if stage < 2 else SPIRE[:3]
    top = _cone(b, "spire", 0, 0, SPIRE_Y, spire)
    if stage < 2:
        b.rbox("finial", (-0.35, top, -0.35), (0.35, top + 0.6, 0.35), "t_gold", 0.12, bottom=False)
    else:
        b.box("spire_chip", (-1.4, top, -0.8), (0.4, top + 0.4, 1.0), "t_roof_tint", tint=True)
    for n, (sx, sz) in enumerate(TURRETS):
        cx, cz = sx * 5.5, sz * 5.5
        fallen = stage >= 2 and n in (1, 2)
        ttop = 16.0 if fallen else 19.0
        b.rbox(f"turret_{n}", (cx - 1.5, 7.5, cz - 1.5), (cx + 1.5, ttop, cz + 1.5), wall, 0.36, bottom=False,
               top=fallen)
        b.rbox(f"turret_band_{n}", (cx - 1.5625, 10.0, cz - 1.5625), (cx + 1.5625, 10.5, cz + 1.5625),
               "t_paint_tint", 0.36, top=False, bottom=False, tint=True)
        b.box(f"turret_win_{n}", (cx - 0.4, 12.4, cz - 1.5625), (cx + 0.4, 13.8, cz - 1.5), "t_window",
              faces={"south": None})
        if fallen:
            continue
        b.rbox(f"turret_cornice_{n}", (cx - 1.75, ttop, cz - 1.75), (cx + 1.75, ttop + 0.6, cz + 1.75), "t_stone",
               0.3, bottom=False)
        y = _cone(b, f"turret_roof_{n}", cx, cz, ttop + 0.6, CORE_TURRET_ROOF)
        b.box(f"turret_ball_{n}", (cx - 0.3, y, cz - 0.3), (cx + 0.3, y + 0.6, cz + 0.3), "t_gold")
    if stage >= 1:
        _plaster(b, "plaster", -5.2, 3.9, -7.0, size=1.6)
        _plaster(b, "plaster_2", 2.05, 9.6, -3.5, size=1.4)
    if stage >= 2:
        _plaster(b, "plaster_3", 3.5, 12.0, -1.6, along="x", size=1.4)
        b.box("hole", (-2.6, 9.6, -3.5625), (-1.6, 10.6, -3.5), "t_navy", faces={"south": None})
    _toy_blocks(b, stage, CORE_BLOCKS)
    return b


CORE_BLOCKS = [(-9.2, -6.0, 1.4, 22.5), (8.4, 4.8, 1.2, 0), (-3.6, -9.6, 1.2, 45), (8.2, -6.8, 1.6, 22.5),
               (-8.8, 5.6, 1.2, 0), (2.4, 8.6, 1.1, 45)]


def core_crown():
    """The big gold crown around the spire's foot (turns about y like the music box's dancer): a band with four tall
    points at the sides tipped with team gems, four short ones with pearls at the corners, a glowing team jewel set in
    the band under every tall point."""
    b = Build()
    y0, h, half, thick = SPIRE_Y, 1.4, 3.2, 0.45
    b.ring("band", half, y0, y0 + h, thick, "t_gold")
    b.ring("band_rim", half + 0.06, y0 + 0.1, y0 + 0.35, 0.1, "t_white")
    top = y0 + h
    for n in range(4):
        ang = n * 90
        s, c = math.sin(math.radians(ang)), -math.cos(math.radians(ang))
        cx, cz = (half - thick / 2) * s, (half - thick / 2) * c
        wx, wz = (0.75, 0.2) if n % 2 == 0 else (0.2, 0.75)
        b.box(f"point_{n}", (cx - wx, top, cz - wz), (cx + wx, top + 1.5, cz + wz), "t_gold")
        b.box(f"point_mid_{n}", (cx - wx * 0.55, top + 1.5, cz - wz * 0.55 - 0.01), (cx + wx * 0.55, top + 2.2,
              cz + wz * 0.55 + 0.01), "t_gold")
        b.rbox(f"tip_{n}", (cx - 0.35, top + 2.2, cz - 0.35), (cx + 0.35, top + 2.9, cz + 0.35), "t_paint_tint", 0.1,
               tint=True, glow=True)
        gx, gz = (0.45, thick / 2 + 0.12) if n % 2 == 0 else (thick / 2 + 0.12, 0.45)
        ex, ez = half * s, half * c
        b.box(f"gem_{n}", (ex - gx, y0 + 0.45, ez - gz), (ex + gx, y0 + 1.15, ez + gz), "t_paint_tint", tint=True,
              glow=True)
    for n, (sx, sz) in enumerate(TURRETS):
        x, z = sx * (half - 0.3), sz * (half - 0.3)
        b.box(f"corner_{n}", (x - 0.25, top, z - 0.25), (x + 0.25, top + 0.8, z + 0.25), "t_gold")
        b.box(f"corner_pearl_{n}", (x - 0.22, top + 0.8, z - 0.22), (x + 0.22, top + 1.25, z + 0.22), "t_white")
    return b


CROWN_CENTRE = 22.8
CROWN_SCALE = 4


def core_star():
    """The team jewel on the spire's tip (turns slowly): a faceted gem in a gold four-prong setting."""
    b = Build()
    y = SPIRE_TOP + 0.6
    for n, (half, h) in enumerate(((0.25, 0.3), (0.55, 0.4), (0.8, 0.5), (0.55, 0.4), (0.3, 0.3), (0.12, 0.3))):
        b.box(f"gem_{n}", (-half, y, -half), (half, y + h, half), "t_paint_tint", tint=True, glow=True)
        y += h
    for n in range(4):
        ang = n * 90
        s, c = math.sin(math.radians(ang)), -math.cos(math.radians(ang))
        x, z = 0.9 * s, 0.9 * c
        b.box(f"prong_{n}", (x - 0.1, SPIRE_TOP + 0.75, z - 0.1), (x + 0.1, SPIRE_TOP + 1.7, z + 0.1), "t_gold")
    return b


STAR_CENTRE = SPIRE_TOP + 1.6
STAR_SCALE = 2


def core_key():
    """The music box's wind-up key on the west side (turns about the x axis through y = KEY_CENTRE, z = 0)."""
    b = Build()
    c = KEY_CENTRE
    b.box("shaft", (-8.8, c - 0.24, -0.24), (-7.25, c + 0.24, 0.24), "t_gold")
    b.box("collar", (-8.2, c - 0.44, -0.44), (-7.8, c + 0.44, 0.44), "t_gold")
    b.rbox("bow_hub", (-9.4, c - 0.6, -0.6), (-8.8, c + 0.6, 0.6), "t_gold", 0.16)
    for side in (-1, 1):
        z0, z1 = (0.6, 2.1) if side > 0 else (-2.1, -0.6)
        b.rbox(f"bow_{side}", (-9.3, c - 0.9, z0), (-8.9, c + 0.9, z1), "t_gold", 0.24)
        b.box(f"bow_gem_{side}", (-9.375, c - 0.25, (z0 + z1) / 2 - 0.25), (-9.3, c + 0.25, (z0 + z1) / 2 + 0.25),
              "t_paint_tint", tint=True, glow=True, faces={"east": None})
    return b


KEY_CENTRE = 4.0


# ------------------------------------------------------------------------------------------------
# 비눗방울 돔 (shield)
# ------------------------------------------------------------------------------------------------

def bubble(rx, height, tiers=9, sink=0.5):
    """A soap-bubble dome: stacked octagonal rings of thin film plates following an ellipse (rx wide, `height`
    tall above the ground, starting `sink` blocks below it). Each plate is exactly as long as its ring's outer side, so
    neighbours meet corner to corner on the outside (no corner sticks out); the turned diagonal plates reach EPS
    further up and down, so the small wedge where neighbours overlap shares no top or bottom face plane."""
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
        side = 2 * (r + t / 2) * math.tan(math.pi / 8)
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
                b.box(f"r{n}_{k}", (cx - side / 2, y[0] - EPS, cz - t / 2), (cx + side / 2, y[1] + EPS, cz + t / 2),
                      "t_bubble", glow=True, rotation=("y", angle, (cx, (y0 + y1) / 2, cz)))
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


# kind -> (half width, height, display scale, centre), as designed: the core's bubble is shown at CORE_SHOWN of it
# (radius 7.35, 26.6 high, inside its clearing of radius 10)
SHIELDS = {"tower": (4.4, 18.0, SHIELD_SCALE, SHIELD_CENTRE), "core": (9.8, 35.5, 16, 14.0)}


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

def _slab(builder, kind, part, slab, scale=BODY, height=SLAB):
    def make(stage):
        models = builder(stage).slabs(f"structure/{kind}_{part}_{stage}", 2, scale, height)
        m = models[slab]
        if m is None:
            return None
        m.name = f"structure/{kind}_{part}_{stage}"
        return m
    return make


ALL = {}
for _s in range(STAGES):
    ALL[f"structure/tower_body_{_s}"] = (lambda s: lambda: _slab(tower_body, "tower", "body", 0)(s))(_s)
    ALL[f"structure/core_body_{_s}"] = (lambda s: lambda: _slab(core_body, "core", "body", 0, CORE_BODY,
                                                                CORE_SLAB)(s))(_s)
for _s in range(STAGES - 1):
    ALL[f"structure/tower_top_{_s}"] = (lambda s: lambda: _slab(tower_body, "tower", "top", 1)(s))(_s)
    ALL[f"structure/core_top_{_s}"] = (lambda s: lambda: _slab(core_body, "core", "top", 1, CORE_BODY,
                                                               CORE_SLAB)(s))(_s)
ALL["structure/tower_flag"] = lambda: tower_flag().centred("structure/tower_flag", FLAG_SCALE, FLAG_CENTRE)
ALL["structure/core_crown"] = lambda: core_crown().centred("structure/core_crown", CROWN_SCALE, CROWN_CENTRE)
ALL["structure/core_key"] = lambda: core_key().centred("structure/core_key", CORE_BODY, KEY_CENTRE)
ALL["structure/core_star"] = lambda: core_star().centred("structure/core_star", STAR_SCALE, STAR_CENTRE)
for _k, (_rx, _h, _sc, _c) in SHIELDS.items():
    ALL[f"structure/{_k}_shield"] = (lambda k, rx, h, sc, c: lambda: bubble(rx, h).centred(
        f"structure/{k}_shield", sc, c))(_k, _rx, _h, _sc, _c)
ALL["structure/fountain"] = lambda: fountain().centred("structure/fountain", FOUNTAIN_SCALE, FOUNTAIN_CENTRE)
ALL["structure/telescope"] = lambda: telescope().centred("structure/telescope", TELESCOPE_SCALE, TELESCOPE_CENTRE)
ALL["structure/telescope_scope"] = lambda: telescope_scope().centred("structure/telescope_scope", SCOPE_SCALE,
                                                                     SCOPE_CENTRE)

# preview placement of every part: building -> [(model id or None for the staged slabs, centre, scale, motion)]
PARTS = {
    "tower": [("body", 4, 6.0, BODY, "none"), ("top", 3, 18.0, BODY, "none"),
              ("flag", 0, FLAG_CENTRE, FLAG_SCALE, "flag"), ("shield", 0, SHIELD_CENTRE, SHIELD_SCALE, "shield")],
    "core": [(part, stages, centre * CORE_SHOWN, scale * CORE_SHOWN, motion)
             for part, stages, centre, scale, motion in (
                 ("body", 4, 12.0, CORE_BODY, "none"), ("top", 3, 36.0, CORE_BODY, "none"),
                 ("crown", 0, CROWN_CENTRE, CROWN_SCALE, "crown"), ("star", 0, STAR_CENTRE, STAR_SCALE, "crown"),
                 ("key", 0, KEY_CENTRE, CORE_BODY, "key"),
                 ("shield", 0, SHIELDS["core"][3], SHIELDS["core"][2], "shield"))],
}
