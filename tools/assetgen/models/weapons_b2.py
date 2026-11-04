"""
Ability weapons, batch 2: the element, spirit and support abilities (see weapons.py for the rules and the five
reference weapons). Each one has its own shape family, materials and colours, chosen from what the ability does:

  hydro        수류술사   sea-worn bronze trident with barbed prongs and a water gem
  magma        용암술사   volcanic basalt maul on a forged haft, molten cracks glowing through the rock
  light        빛의 사도  white-and-gold sun sceptre: rayed gold sun disc with a glowing holy core
  shadow       그림자     short blackened assassin's dagger, violet-wrapped grip and a shadow-cloth tassel
  vampire      흡혈귀     gothic silver sword with bat-wing guard, ruby heart and a blood channel
  reaper       사신       long war scythe: dark snath with a side grip, curved black blade, a torn black rag
  necromancer  사령술사   vertebrae staff crowned with a skull, ghost-green eyes and a ghost flame
  demon        악마       jagged black hellblade with curling horn quillons and a soul-fire vein
  phoenix      불사조     phoenix-feather blade: a giant red feather on a gold quill, flame along its edge, wing guard
  dragon       드래곤     dragon glaive: scale-wrapped pole, a horned dragon head biting a broad curved blade
  werewolf     늑대인간   fur-bound claw: three hooked bone claws bursting from a shaggy fist
  dokkaebi     도깨비     도깨비 방망이: knobbed dark-wood club with gold studs and a rope grip
  healer       치유사     living birch staff whose branches cradle a glowing green crystal, sprouting leaves
  bard         음유시인   a lute swung by its neck: round-backed body, spruce top with rosette, strings, red ribbon
  alchemist    연금술사   alchemist's flask mace: brass-caged flask of glowing potion on a brass rod, ruby pommel
"""

import math

from mcpl_assets import weapon_textures_b2  # noqa: F401  (registers the batch 2 w_* textures)
from mcpl_assets.weaponkit import Weapon


def hydro():
    w = Weapon("weapon/hydro")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_verdigris")
    w.centred("pommel_drop", -3.75, -3.0, 1.25, 1.25, "w_water")
    w.grip(-2.0, 6.0, 2.0, 2.0, tex="w_wrap_sea")
    w.collar("ferrule", 6.0, 2.25, 2.25, 0.75, "w_verdigris")
    w.centred("shaft", 6.75, 15.0, 1.75, 1.75, "w_wood_dark")
    w.collar("socket", 15.0, 2.5, 2.5, 1.5, "w_verdigris")
    # the head: a bronze crossbar carrying three barbed prongs, a water gem in its middle
    w.group("head")
    w.box("crossbar", (-4.75, 16.5, -1.0), (4.75, 18.0, 1.0), "w_verdigris")
    w.box("crossbar_l", (-5.0, 16.75, -0.75), (-4.75, 17.75, 0.75), "w_verdigris")
    w.box("crossbar_r", (4.75, 16.75, -0.75), (5.0, 17.75, 0.75), "w_verdigris")
    w.both_flats("gem", (-1.0, 16.25, 1.0), (1.0, 18.25, 1.375), "w_water")
    # centre prong
    w.centred("mid_root", 18.0, 19.0, 1.75, 1.5, "w_verdigris")
    w.centred("mid", 19.0, 23.5, 1.25, 1.25, "w_verdigris")
    w.centred("mid_barb", 23.5, 24.5, 2.75, 1.0, "w_verdigris")
    w.centred("mid_point", 24.5, 26.5, 1.5, 0.75, "w_verdigris")
    w.centred("mid_tip", 26.5, 27.5, 0.5, 0.5, "w_steel_edge")
    # side prongs, their barbs pointing outwards
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        x = side * 3.75
        w.centred(f"side_{tag}", 18.0, 22.0, 1.25, 1.25, "w_verdigris", x=x)
        lo, hi = sorted((x, x + side * 1.75))
        w.box(f"side_barb_{tag}", (lo, 21.0, -0.5), (hi, 22.0, 0.5), "w_verdigris")
        w.centred(f"side_point_{tag}", 22.0, 24.0, 0.75, 0.75, "w_verdigris", x=x)
        w.centred(f"side_tip_{tag}", 24.0, 24.75, 0.375, 0.375, "w_steel_edge", x=x)
    return w.finish()


def magma():
    w = Weapon("weapon/magma")
    w.group("handle")
    w.pommel(-2.0, w=2.75, d=2.75, h=1.25, tex="w_basalt")
    w.grip(-2.0, 6.0, 2.25, 2.25, tex="w_wrap")
    w.collar("ferrule", 6.0, 2.5, 2.5, 1.0, "w_iron_dark")
    w.centred("haft", 7.0, 14.5, 2.0, 2.0, "w_iron_dark")
    w.both_flats("haft_crack", (-0.25, 11.5, 1.0), (0.25, 14.5, 1.0625), "w_heat", glow=True)
    # the head: a rough lump of basalt, taller than wide, with molten cracks glowing through it
    w.group("head")
    w.box("rock", (-3.25, 14.5, -2.5), (3.25, 24.0, 2.5), "w_basalt")
    w.box("lump_l", (-4.25, 16.0, -2.0), (-3.25, 22.5, 2.0), "w_basalt")
    w.box("lump_r", (3.25, 15.25, -2.0), (4.5, 21.0, 2.0), "w_basalt")
    w.box("lump_r2", (3.25, 21.0, -1.5), (4.0, 23.0, 1.5), "w_basalt")
    w.box("lump_top", (-2.5, 24.0, -2.0), (2.0, 25.25, 2.0), "w_basalt")
    w.box("shard_l", (-2.25, 25.25, -0.75), (-1.0, 26.75, 0.75), "w_basalt")
    w.box("shard_r", (0.5, 25.25, -1.0), (1.75, 26.0, 1.0), "w_basalt")
    w.box("lump_bottom", (-2.5, 13.75, -2.0), (2.5, 14.5, 2.0), "w_basalt")
    # molten veins on both flats: a forked crack from the crater down to the haft
    cracks = [(-0.25, 21.5, 0.5, 24.0), (-1.0, 19.5, -0.25, 21.75), (-1.75, 17.5, -1.0, 19.75),
              (0.5, 18.75, 1.25, 21.75), (1.25, 16.25, 2.0, 19.0), (-2.5, 15.25, -1.75, 17.75)]
    for n, (x0, y0, x1, y1) in enumerate(cracks):
        w.both_flats(f"crack{n}", (x0, y0, 2.5), (x1, y1, 2.5625), "w_heat", glow=True)
    w.edge_strip("crack_side_r", (4.5, 17.0, -0.375), (4.5625, 19.5, 0.375), "w_heat")
    w.edge_strip("crack_side_l", (-4.3125, 18.0, -0.375), (-4.25, 20.5, 0.375), "w_heat")
    w.edge_strip("crater", (-1.5, 25.25, -1.25), (0.25, 25.375, 1.25), "w_heat")
    return w.finish()


def light():
    w = Weapon("weapon/light")
    w.group("handle")
    w.pommel(-2.0, w=2.75, d=2.75, h=1.25, tex="w_gold")
    w.centred("pommel_knob", -4.0, -3.25, 1.5, 1.5, "w_gold")
    w.grip(-2.0, 6.0, 2.0, 2.0, tex="w_wrap_pale")
    w.collar("band_low", 6.0, 2.5, 2.5, 1.0, "w_gold")
    w.centred("shaft", 7.0, 14.0, 1.75, 1.75, "w_pearl")
    w.collar("band_high", 14.0, 2.5, 2.5, 1.0, "w_gold")
    w.centred("neck", 15.0, 16.0, 1.5, 1.5, "w_pearl")
    # the sun: a gold disc ring with a glowing holy core and eight rays
    w.group("sun")
    cy = 20.5
    w.ring("disc", 0, cy, 7.0, 1.25, 0.875, "w_gold")
    w.box("core", (-2.25, cy - 2.25, -0.75), (2.25, cy + 2.25, 0.75), "w_radiant", glow=True)
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        lo, hi = sorted((side * 3.5, side * 5.75))
        w.box(f"ray_{tag}", (lo, cy - 0.625, -0.5), (hi, cy + 0.625, 0.5), "w_gold")
        lo, hi = sorted((side * 5.75, side * 6.5))
        w.box(f"ray_{tag}_tip", (lo, cy - 0.25, -0.25), (hi, cy + 0.25, 0.25), "w_gold")
    w.box("ray_t", (-0.625, cy + 3.5, -0.5), (0.625, cy + 5.75, 0.5), "w_gold")
    w.box("ray_t_tip", (-0.25, cy + 5.75, -0.25), (0.25, cy + 6.5, 0.25), "w_gold")
    for n, angle in enumerate((45, 135, -45, -135)):
        rad = math.radians(angle)
        start = (math.cos(rad) * 3.75, cy + math.sin(rad) * 3.75)
        w.bar(f"ray_d{n}", start, angle, 1.75, 0.875, 0.75, "w_gold")
    return w.finish()


def shadow():
    w = Weapon("weapon/shadow")
    w.group("tassel")
    w.ring("loop", 0, -4.25, 1.5, 0.25, 0.125, "w_iron_dark")
    w.centred("tassel_knot", -6.0, -5.0, 1.25, 1.25, "w_shade")
    w.centred("tassel", -9.0, -6.0, 1.75, 0.75, "w_shade")
    w.centred("tassel_end", -9.75, -9.0, 1.25, 0.5, "w_shade")
    w.group("handle")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_iron_dark")
    w.grip(-2.0, 5.0, 1.75, 1.75, tex="w_wrap_shade")
    # a short guard with its quillons dropped towards the hand
    w.guard(5.0, 2.75, h=1.0, d=1.75, tex="w_iron_dark")
    w.box("quillon_l", (-2.75, 4.0, -0.625), (-2.0, 5.0, 0.625), "w_iron_dark")
    w.box("quillon_r", (2.0, 4.0, -0.625), (2.75, 5.0, 0.625), "w_iron_dark")
    # the blade: blackened, double-edged, tapering in steps to a needle point
    w.group("blade")
    w.centred("ricasso", 6.0, 7.0, 1.75, 1.0, "w_iron_dark")
    w.centred("core_0", 7.0, 12.0, 1.5, 1.0, "w_iron_dark")
    w.centred("core_1", 12.0, 16.0, 1.25, 0.875, "w_iron_dark")
    w.centred("core_2", 16.0, 19.0, 0.875, 0.75, "w_iron_dark")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        for n, (y0, y1, x0, x1) in enumerate(((7.0, 12.0, 0.75, 1.5), (12.0, 16.0, 0.625, 1.25),
                                               (16.0, 19.0, 0.4375, 0.875))):
            lo, hi = sorted((side * x0, side * x1))
            w.box(f"edge_{tag}{n}", (lo, y0, -0.25), (hi, y1, 0.25), "w_steel_edge")
    w.centred("point", 19.0, 20.75, 0.75, 0.5, "w_steel_edge")
    w.centred("needle", 20.75, 21.75, 0.375, 0.375, "w_steel_edge")
    return w.finish()


def vampire():
    w = Weapon("weapon/vampire")
    w.group("handle")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_iron_dark")
    w.centred("pommel_ruby", -4.25, -3.0, 1.5, 1.5, "w_ruby")
    w.grip(-2.0, 5.0, 1.75, 1.75, tex="w_wrap_blood")
    # bat-wing guard: two black wings spreading up and out, scalloped below, a ruby heart in the middle
    w.group("guard")
    w.box("guard_core", (-1.75, 5.0, -1.0), (1.75, 7.0, 1.0), "w_iron_dark")
    w.both_flats("heart", (-0.75, 5.375, 1.0), (0.75, 6.625, 1.25), "w_ruby")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        a = 22.5 if side > 0 else 157.5
        end = w.bar(f"wing_arm_{tag}", (side * 1.5, 6.5), a, 5.5, 0.75, 1.0, "w_iron_dark")
        w.bar(f"wing_tip_{tag}", end, 45 if side > 0 else 135, 1.25, 0.5, 0.75, "w_iron_dark")
        # membrane: three scallops hanging under the arm, shorter towards the tip
        for n, (x, h) in enumerate(((2.5, 2.25), (3.75, 2.5), (5.0, 2.5))):
            top = 6.5 + (x - 1.5) * 0.414
            lo, hi = sorted((side * (x - 0.75), side * (x + 0.75)))
            w.box(f"membrane_{tag}{n}", (lo, top - h, -0.25), (hi, top, 0.25), "w_shade")
    # the blade: polished silver, a dark blood channel on both flats, both edges honed
    w.group("blade")
    w.centred("blade_0", 7.0, 21.0, 1.75, 0.875, "w_steel")
    w.centred("blade_1", 21.0, 24.0, 1.25, 0.75, "w_steel")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        lo, hi = sorted((side * 0.875, side * 1.375))
        w.box(f"edge_{tag}0", (lo, 7.0, -0.25), (hi, 21.0, 0.25), "w_steel_edge")
        lo, hi = sorted((side * 0.625, side * 1.0))
        w.box(f"edge_{tag}1", (lo, 21.0, -0.25), (hi, 24.0, 0.25), "w_steel_edge")
    w.both_flats("blood", (-0.375, 7.5, 0.4375), (0.375, 20.0, 0.5), "w_blood")
    w.centred("point", 24.0, 25.5, 1.0, 0.5, "w_steel_edge")
    w.centred("tip", 25.5, 26.5, 0.375, 0.375, "w_steel_edge")
    return w.finish()


def reaper():
    w = Weapon("weapon/reaper")
    w.group("snath")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_iron_dark")
    w.grip(-2.0, 5.0, 1.75, 1.75, tex="w_wrap_shade")
    w.centred("snath", 5.0, 25.0, 1.5, 1.5, "w_lacquer")
    # the side grip (nib) of a war scythe, pointing back, bound with bone rings
    w.box("nib", (-3.5, 10.0, -0.5), (-0.75, 11.0, 0.5), "w_lacquer")
    w.box("nib_cap", (-4.0, 9.875, -0.625), (-3.5, 11.125, 0.625), "w_bone")
    w.box("nib_band", (-1.0, 9.75, -0.875), (1.0, 11.25, 0.875), "w_bone")
    w.collar("bone_ring", 5.0, 2.0, 2.0, 0.75, "w_bone")
    w.collar("socket", 23.0, 2.0, 2.0, 2.75, "w_bone")
    # a torn black rag tied under the blade
    w.box("rag_knot", (-1.125, 21.75, -1.0), (1.125, 22.75, 1.0), "w_shade")
    w.box("rag_long", (-1.5, 17.5, -0.125), (-0.75, 21.75, 0.125), "w_shade")
    w.box("rag_short", (-2.5, 19.0, -0.125), (-1.5, 21.75, 0.125), "w_shade")
    # the blade: a long curve of dark iron sweeping out and down towards +x, its honed edge on the inside
    w.group("blade")
    path = [(0, 3.0), (-22.5, 3.0), (-45, 2.75), (-67.5, 2.5), (-90, 1.5)]
    w.curve("blade", (0.5, 24.75), path, (2.75, 2.25, 1.75, 1.25, 0.75), 0.75, "w_iron_dark")
    w.curve("edge", (0.75, 24.75), [(0, 2.875)] + path[1:], 0.625, 0.5, "w_steel_edge", offset=-1.375)
    w.box("blade_heel", (-0.875, 24.0, -0.5), (0.75, 26.0, 0.5), "w_iron_dark")
    return w.finish()


def necromancer():
    w = Weapon("weapon/necromancer")
    w.group("handle")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_bone")
    w.centred("pommel_claw", -4.0, -3.0, 1.0, 1.0, "w_bone")
    w.grip(-2.0, 6.0, 2.0, 2.0, tex="w_wrap_shade")
    # the shaft is a spine: vertebrae alternating with thin discs
    w.group("spine")
    y = 6.0
    n = 0
    while y < 16.5:
        w.centred(f"vertebra{n}", y, y + 1.0, 2.5, 2.0, "w_bone")
        w.box(f"process{n}", (-0.375, y + 0.25, -1.75), (0.375, y + 0.75, -1.0), "w_bone")
        w.centred(f"disc{n}", y + 1.0, y + 1.5, 1.5, 1.5, "w_horn")
        y += 1.5
        n += 1
    # the skull, facing the viewer (+z): jaw, cranium, dark sockets with ghost-green eyes, a ghost flame above
    w.group("skull")
    w.box("jaw", (-1.75, y, -1.5), (1.75, y + 1.5, 1.75), "w_bone")
    w.box("teeth", (-1.25, y + 1.0, 1.75), (1.25, y + 1.5, 2.0), "w_bone")
    w.box("cranium", (-2.75, y + 1.5, -2.25), (2.75, y + 6.5, 2.25), "w_bone")
    w.box("crown", (-2.25, y + 6.5, -1.75), (2.25, y + 7.0, 1.75), "w_bone")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        lo, hi = sorted((side * 0.375, side * 2.0))
        w.box(f"socket_{tag}", (lo, y + 3.0, 2.25), (hi, y + 4.5, 2.375), "w_iron_dark")
        lo, hi = sorted((side * 0.75, side * 1.625))
        w.box(f"eye_{tag}", (lo, y + 3.25, 2.375), (hi, y + 4.25, 2.5), "w_ghost", glow=True)
    w.box("nose", (-0.375, y + 2.0, 2.25), (0.375, y + 2.75, 2.375), "w_iron_dark")
    w.centred("flame_0", y + 7.0, y + 8.5, 2.0, 1.5, "w_ghost", glow=True)
    w.centred("flame_1", y + 8.5, y + 9.75, 1.25, 1.0, "w_ghost", glow=True, x=0.25)
    w.centred("flame_2", y + 9.75, y + 10.5, 0.5, 0.5, "w_ghost", glow=True, x=0.5)
    return w.finish()


def demon():
    w = Weapon("weapon/demon")
    w.group("handle")
    w.centred("pommel_spike", -4.25, -3.0, 1.0, 1.0, "w_horn")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_iron_dark")
    w.grip(-2.0, 5.0, 2.0, 2.0, tex="w_wrap_blood")
    # the guard: an iron block with a soul-fire eye, two horns curling up beside the blade
    w.group("horns")
    w.box("guard", (-2.0, 5.0, -1.25), (2.0, 7.0, 1.25), "w_iron_dark")
    w.both_flats("eye", (-0.625, 5.5, 1.25), (0.625, 6.5, 1.375), "w_soulfire", glow=True)
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        path = [(-22.5, 1.75), (22.5, 1.5), (67.5, 1.75), (90, 1.5), (112.5, 1.0)]
        if side < 0:
            path = [(180 - a, l) for a, l in path]
        w.curve(f"horn_{tag}", (side * 1.75, 6.0), path, (1.5, 1.25, 1.0, 0.75, 0.5), 1.0, "w_horn")
    # the blade: wide blackened iron with serrated edges and a soul-fire vein down the middle
    w.group("blade")
    w.centred("blade_0", 7.0, 17.0, 3.0, 1.0, "w_iron_dark")
    w.centred("blade_1", 17.0, 22.0, 2.25, 0.875, "w_iron_dark")
    w.centred("blade_2", 22.0, 24.5, 1.25, 0.75, "w_iron_dark")
    w.centred("tip", 24.5, 26.0, 0.5, 0.5, "w_iron_dark")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        for n, (y0, y1, x0, x1) in enumerate(((8.0, 9.5, 1.5, 2.25), (10.5, 12.0, 1.5, 2.25), (13.0, 14.5, 1.5, 2.25),
                                               (15.5, 17.0, 1.5, 2.0), (18.0, 19.5, 1.125, 1.75),
                                               (20.5, 22.0, 1.125, 1.625))):
            lo, hi = sorted((side * x0, side * x1))
            w.box(f"tooth_{tag}{n}", (lo, y0, -0.25), (hi, y1, 0.25), "w_steel")
    w.both_flats("vein", (-0.25, 8.0, 0.5), (0.25, 21.0, 0.5625), "w_soulfire", glow=True)
    return w.finish()


def phoenix():
    w = Weapon("weapon/phoenix")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_gold")
    w.centred("pommel_flame", -3.75, -3.0, 1.0, 1.0, "w_heat", glow=True)
    w.grip(-2.0, 5.0, 2.0, 2.0, tex="w_wrap_blood")
    # the guard: a gold crest with two fans of red feathers, like spread wings
    w.group("wings")
    w.box("crest", (-1.75, 5.0, -1.0), (1.75, 7.0, 1.0), "w_gold")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        for n, (a, length, width, y) in enumerate(((67.5, 4.5, 0.875, 6.5), (45, 5.5, 1.0, 6.25),
                                                    (22.5, 4.25, 1.0, 6.0))):
            ang = a if side > 0 else 180 - a
            w.bar(f"feather_{tag}{n}", (side * 1.25, y), ang, length, width, 0.625 - 0.125 * (n % 2), "w_feather")
    # the blade: one great feather, a gold quill up its middle, flame licking along its outer edge
    w.group("feather")
    w.centred("vane_0", 7.0, 10.0, 2.5, 0.75, "w_feather")
    w.centred("vane_1", 10.0, 19.0, 3.5, 0.75, "w_feather")
    w.centred("vane_2", 19.0, 22.5, 2.75, 0.75, "w_feather")
    w.centred("vane_3", 22.5, 24.5, 1.75, 0.75, "w_feather")
    w.centred("vane_4", 24.5, 25.75, 0.75, 0.5, "w_feather", x=0.25)
    w.centred("quill", 7.0, 24.5, 0.5, 1.0, "w_gold")
    w.edge_strip("flame_0", (1.25, 7.5, -0.25), (1.75, 10.0, 0.25), "w_heat")
    w.edge_strip("flame_1", (1.75, 10.0, -0.25), (2.25, 19.0, 0.25), "w_heat")
    w.edge_strip("flame_2", (1.375, 19.0, -0.25), (1.875, 22.5, 0.25), "w_heat")
    w.edge_strip("flame_3", (0.875, 22.5, -0.25), (1.375, 24.5, 0.25), "w_heat")
    # notches in the vane (a feather splits): small gaps cut into the trailing side
    for n, y in enumerate((12.0, 16.0)):
        w.both_flats(f"split{n}", (-1.75, y, 0.375), (-0.75, y + 0.375, 0.4375), "w_wrap_blood")
    return w.finish()


def dragon():
    w = Weapon("weapon/dragon")
    w.group("pole")
    w.centred("butt_spike", -5.0, -3.5, 1.0, 1.0, "w_iron_dark")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.5, tex="w_iron_dark")
    w.grip(-2.0, 6.0, 1.75, 1.75, tex="w_wrap")
    w.centred("pole", 6.0, 13.5, 1.75, 1.75, "w_scales")
    w.collar("pole_band", 9.5, 2.0, 2.0, 0.75, "w_brass")
    w.box("neck_ring", (-1.25, 12.75, -1.25), (1.25, 13.5, 1.25), "w_brass")
    # the dragon head, its jaws holding the blade: snout towards the edge (+x), horns sweeping back
    w.group("head")
    w.box("skull", (-2.0, 13.5, -1.75), (2.0, 18.0, 1.75), "w_scales")
    w.box("snout", (2.0, 14.0, -1.375), (4.25, 15.75, 1.375), "w_scales")
    w.box("upper_jaw", (2.0, 16.5, -1.25), (4.75, 17.75, 1.25), "w_scales")
    w.box("teeth", (2.25, 15.75, -1.0), (4.25, 16.5, 1.0), "w_bone")
    w.both_flats("eye", (0.5, 16.5, 1.75), (1.375, 17.25, 1.875), "w_breath", glow=True)
    w.box("brow", (0.0, 17.25, -1.875), (1.875, 17.75, 1.875), "w_scales")
    w.curve("horn", (-1.25, 17.5), [(157.5, 2.5), (-157.5, 2.0), (-135, 1.5)], (1.25, 1.0, 0.625), 1.25, "w_bone")
    w.box("mane", (-2.75, 13.75, -1.25), (-2.0, 16.75, 1.25), "w_scales")
    # the blade: a broad, sweeping 언월도 blade growing from the jaws, a notched back, edge on +x
    w.group("blade")
    rows = [(18.0, 20.5, -1.25, 2.0), (20.5, 23.0, -1.0, 2.75), (23.0, 25.0, -0.5, 3.25), (25.0, 26.5, 0.25, 3.25),
            (26.5, 27.75, 1.0, 3.0)]
    for n, (y0, y1, x0, x1) in enumerate(rows):
        w.box(f"blade_{n}", (x0, y0, -0.5), (x1 - 0.75, y1, 0.5), "w_steel")
        w.box(f"edge_{n}", (x1 - 0.75, y0, -0.375), (x1, y1, 0.375), "w_steel_edge")
    w.box("point", (2.0, 27.75, -0.25), (3.0, 28.75, 0.25), "w_steel_edge")
    w.both_flats("fuller", (-0.25, 18.5, 0.5), (0.25, 24.5, 0.5625), "w_iron_dark")
    w.both_flats("inlay", (0.75, 19.0, 0.5), (1.5, 19.75, 0.5625), "w_brass")
    w.box("notch", (-2.25, 21.0, -0.25), (-1.0, 22.0, 0.25), "w_steel")
    w.box("notch_tip", (-2.75, 21.75, -0.25), (-2.0, 23.0, 0.25), "w_steel")
    return w.finish()


def werewolf():
    w = Weapon("weapon/werewolf")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_bone")
    w.centred("pommel_fang", -4.5, -3.0, 0.75, 0.75, "w_bone")
    w.grip(-2.0, 6.0, 2.0, 2.0, tex="w_wrap")
    # a shaggy fist of fur over a leather cuff
    w.group("fist")
    w.collar("cuff", 6.0, 2.75, 2.75, 1.0, "w_leather")
    w.box("fur_0", (-2.5, 7.0, -2.25), (2.5, 9.0, 2.25), "w_fur")
    w.box("fur_1", (-3.25, 9.0, -2.5), (3.25, 13.0, 2.5), "w_fur")
    w.box("tuft_l", (-3.75, 8.0, -1.75), (-3.25, 11.0, 1.75), "w_fur")
    w.box("tuft_r", (3.25, 8.5, -1.75), (3.75, 11.5, 1.75), "w_fur")
    w.box("tuft_b", (-1.75, 6.25, -1.75), (1.25, 7.0, 1.75), "w_fur")
    w.box("knuckles", (-3.0, 13.0, -2.0), (3.0, 14.0, 2.0), "w_fur")
    # three hooked claws, growing up and bending towards the edge side (+x)
    w.group("claws")
    for n, x in enumerate((-2.0, 0.0, 2.0)):
        top = 14.0 + (0.5 if n == 1 else 0)
        w.centred(f"claw_root{n}", 13.75, top + 1.0, 1.5, 1.5, "w_horn", x=x)
        w.curve(f"claw{n}", (x, top + 0.75), [(90, 4.5 + (1.0 if n == 1 else 0)), (67.5, 3.0), (45, 2.0),
                                              (22.5, 1.5)], (1.25, 1.0, 0.75, 0.5), 1.0 + 0.25 * (n == 1), "w_bone")
    return w.finish()


def dokkaebi():
    w = Weapon("weapon/dokkaebi")
    w.group("handle")
    w.centred("end", -3.25, -2.0, 2.25, 2.25, "w_wood_dark")
    w.grip(-2.0, 6.0, 2.25, 2.25, tex="w_rope")
    w.ring("cord", 0, -4.5, 1.75, 0.375, 0.25, "w_blood")
    # the club: dark wood swelling towards a rounded head
    w.group("club")
    w.taper("club", 6.0, 21.0, 2.75, 5.25, 2.75, 4.75, "w_wood_dark", steps=5)
    w.centred("head_0", 21.0, 23.5, 5.25, 4.75, "w_wood_dark")
    w.centred("head_1", 23.5, 24.75, 4.25, 3.75, "w_wood_dark")
    w.centred("head_2", 24.75, 25.5, 2.75, 2.25, "w_wood_dark")
    # knobs: big wooden bumps all over (they make the silhouette lumpy), two of them gold (금 나와라 뚝딱)
    def half(y):
        k = min(4, int((y - 6.0) // 3)) if y < 21.0 else 4
        return (2.75 + 2.5 * k / 4) / 2, (2.75 + 2.0 * k / 4) / 2

    def knob(name, x, y, side, flat, tex):
        """A two-step bump on a flat (z, flat=True) or on an edge (x) of the club at height y."""
        hx, hz = half(y)
        if flat:
            w.both_flats(name, (x - 0.75, y - 0.75, hz), (x + 0.75, y + 0.75, hz + 0.5), tex)
            w.both_flats(name + "_cap", (x - 0.375, y - 0.375, hz + 0.5), (x + 0.375, y + 0.375, hz + 0.875), tex)
        else:
            lo, hi = sorted((side * hx, side * (hx + 0.625)))
            w.box(name, (lo, y - 0.75, -0.75), (hi, y + 0.75, 0.75), tex)
            lo, hi = sorted((side * (hx + 0.625), side * (hx + 1.0)))
            w.box(name + "_cap", (lo, y - 0.375, -0.375), (hi, y + 0.375, 0.375), tex)

    for n, (x, y, tex) in enumerate(((-0.5, 10.0, "w_wood"), (0.75, 13.5, "w_gold"), (-1.0, 16.5, "w_wood"),
                                     (1.25, 19.5, "w_wood"), (-1.25, 22.25, "w_gold"), (1.0, 23.75, "w_wood"))):
        knob(f"knob{n}", x, y, 1, True, tex)
    for n, (y, side, tex) in enumerate(((11.0, -1, "w_wood"), (13.0, 1, "w_wood"), (16.0, -1, "w_wood"),
                                        (17.5, 1, "w_gold"), (20.5, -1, "w_wood"), (21.75, 1, "w_wood"),
                                        (8.0, 1, "w_wood"))):
        knob(f"side_knob{n}", 0, y, side, False, tex)
    w.centred("top_knob", 25.5, 26.25, 1.0, 1.0, "w_wood")
    return w.finish()


def healer():
    w = Weapon("weapon/healer")
    w.group("staff")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_brass")
    w.grip(-2.0, 6.0, 2.0, 2.0, tex="w_wrap_pale")
    w.centred("staff", 6.0, 17.0, 1.5, 1.5, "w_birch")
    w.collar("knot", 11.0, 1.875, 1.875, 1.0, "w_birch")
    w.bar("leaf_low", (0.75, 12.5), 22.5, 2.25, 0.875, 0.25, "w_leaf")
    # two living branches part and close again round a glowing crystal
    w.group("branches")
    w.curve("branch_r", (0.5, 16.5), [(45, 2.5), (90, 4.0), (135, 2.0), (157.5, 1.0)], (1.25, 1.0, 1.0, 0.75), 1.25,
            "w_birch")
    w.curve("branch_l", (-0.5, 16.5), [(135, 2.5), (90, 4.0), (45, 2.0), (22.5, 1.0)], (1.25, 1.0, 1.0, 0.75), 1.25,
            "w_birch", phase=1)     # the two tips cross: out of step
    w.box("crystal_0", (-1.0, 18.0, -1.0), (1.0, 19.0, 1.0), "w_crystal", glow=True)
    w.box("crystal_1", (-1.5, 19.0, -1.25), (1.5, 22.0, 1.25), "w_crystal", glow=True)
    w.box("crystal_2", (-1.0, 22.0, -1.0), (1.0, 23.0, 1.0), "w_crystal", glow=True)
    w.box("crystal_3", (-0.5, 23.0, -0.5), (0.5, 23.75, 0.5), "w_crystal", glow=True)
    # leaves sprouting from the branches
    w.bar("leaf_r", (3.0, 20.5), 22.5, 2.5, 1.0, 0.25, "w_leaf")
    w.bar("leaf_l", (-3.0, 19.0), 157.5, 2.25, 1.0, 0.25, "w_leaf")
    w.bar("leaf_t", (-1.0, 24.5), 112.5, 2.0, 0.875, 0.25, "w_leaf")
    return w.finish()


def bard():
    w = Weapon("weapon/bard")
    w.group("pegbox")
    # the pegbox at the bottom (the end of the neck the bard swings it by), pegs and a red ribbon
    w.box("pegbox", (-1.0, -5.0, -0.875), (1.0, -1.5, 0.875), "w_wood_dark")
    for n, y in enumerate((-4.5, -3.5, -2.5)):
        w.box(f"peg_l{n}", (-1.75, y, -0.25), (-1.0, y + 0.5, 0.25), "w_bone")
        w.box(f"peg_r{n}", (1.0, y + 0.5, -0.25), (1.75, y + 1.0, 0.25), "w_bone")
    w.box("ribbon_knot", (-1.25, -1.5, -1.0), (1.25, -0.75, 1.0), "w_blood")
    w.box("ribbon_a", (-2.25, -4.0, -0.125), (-1.5, -0.75, 0.125), "w_blood")
    w.box("ribbon_b", (1.5, -3.25, -0.125), (2.25, -0.75, 0.125), "w_blood")
    # the neck is the grip: dark wood with an ebony fingerboard and frets
    w.group("neck")
    w.box("neck", (-1.0, -0.75, -0.875), (1.0, 9.0, 0.625), "w_wood_dark")
    w.box("fingerboard", (-1.0, -0.75, 0.625), (1.0, 9.0, 0.875), "w_iron_dark")
    for n, y in enumerate((1.0, 3.0, 5.0, 7.0)):
        w.box(f"fret{n}", (-1.0, y, 0.875), (1.0, y + 0.25, 1.0), "w_brass")
    # the body: round-backed staves, a pale spruce top with a rosette and bridge, four strings
    w.group("body")
    rows = [(9.0, 10.5, 2.5), (10.5, 12.0, 3.5), (12.0, 13.5, 4.5), (13.5, 15.0, 5.5), (15.0, 17.0, 6.5),
            (17.0, 21.5, 7.5), (21.5, 23.0, 7.0), (23.0, 24.25, 5.5), (24.25, 25.25, 3.5)]
    for n, (y0, y1, width) in enumerate(rows):
        back = 2.75 if 15.0 <= y0 < 23 else 2.0
        w.box(f"back{n}", (-width / 2, y0, -back), (width / 2, y1, 0.5), "w_wood_dark")
        w.box(f"top{n}", (-width / 2 + 0.375, y0 + (0.25 if n == len(rows) - 1 else 0), 0.5),
              (width / 2 - 0.375, y1 - (0.25 if n == len(rows) - 1 else 0), 0.875), "w_birch")
    # the rosette: a dark sound hole in a brass ring
    w.box("rosette", (-1.5, 16.5, 0.875), (1.5, 19.5, 0.9375), "w_iron_dark")
    for name, frm, to in (("ring_b", (-2.0, 16.0), (2.0, 16.5)), ("ring_t", (-2.0, 19.5), (2.0, 20.0)),
                          ("ring_l", (-2.0, 16.5), (-1.5, 19.5)), ("ring_r", (1.5, 16.5), (2.0, 19.5))):
        w.box(f"rosette_{name}", (frm[0], frm[1], 0.875), (to[0], to[1], 1.0), "w_brass")
    w.box("bridge", (-1.75, 22.0, 0.875), (1.75, 22.75, 1.125), "w_wood_dark")
    for n, x in enumerate((-0.625, -0.125, 0.375)):
        w.box(f"string{n}", (x, 1.0, 1.0), (x + 0.125, 9.5, 1.125), "w_steel_edge")
        w.box(f"string_body{n}", (x, 9.5, 1.0625), (x + 0.125, 22.0, 1.1875), "w_steel_edge")
    return w.finish()


def alchemist():
    w = Weapon("weapon/alchemist")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_brass")
    w.centred("stone", -4.0, -3.0, 1.5, 1.5, "w_ruby")
    w.grip(-2.0, 6.0, 2.0, 2.0, tex="w_wrap")
    w.collar("ferrule", 6.0, 2.5, 2.5, 1.0, "w_brass")
    w.centred("rod", 7.0, 13.0, 1.5, 1.5, "w_brass")
    w.collar("rod_ring", 9.5, 2.0, 2.0, 0.75, "w_iron_dark")
    # the flask: a round glowing bulb held by a brass cage, a glass neck and a cork
    w.group("flask")
    w.centred("cradle", 13.0, 13.75, 3.0, 3.0, "w_brass")
    # the bulb, rounded in layers: glowing potion up to its shoulder, empty glass above
    w.centred("bulb_0", 13.75, 14.5, 4.0, 4.0, "w_potion", glow=True)
    w.centred("bulb_1", 14.5, 15.25, 5.5, 5.5, "w_potion", glow=True)
    w.centred("bulb_2", 15.25, 18.0, 6.0, 6.0, "w_potion", glow=True)
    w.centred("bulb_2x", 15.75, 17.5, 7.0, 4.5, "w_potion", glow=True)
    w.centred("bulb_2z", 15.75, 17.5, 4.5, 7.0, "w_potion", glow=True)
    w.centred("bulb_3", 18.0, 18.75, 5.5, 5.5, "w_potion", glow=True)
    w.centred("bulb_4", 18.75, 19.5, 4.0, 4.0, "w_glass_clear")
    w.centred("shoulder", 19.5, 20.0, 2.5, 2.5, "w_glass_clear")
    # the brass cage: four ribs from the cradle over the bulb and an equator band
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        lo, hi = sorted((side * 3.5, side * 3.875))
        w.box(f"rib_{tag}", (lo, 15.25, -0.375), (hi, 18.0, 0.375), "w_brass")
        w.box(f"rib_z{tag}", (-0.375, 15.25, lo), (0.375, 18.0, hi), "w_brass")
    w.box("band_x", (-3.625, 16.25, -2.375), (3.625, 16.75, 2.375), "w_brass")
    w.box("band_z", (-2.375, 16.25, -3.625), (2.375, 16.75, 3.625), "w_brass")
    w.centred("neck", 20.0, 23.0, 1.75, 1.75, "w_glass_clear")
    w.centred("lip", 23.0, 23.5, 2.25, 2.25, "w_brass")
    w.centred("cork", 23.5, 25.0, 1.5, 1.5, "w_wood")
    w.centred("seal", 25.0, 25.5, 1.75, 1.75, "w_blood")
    return w.finish()


WEAPONS = {
    "hydro": hydro,
    "magma": magma,
    "light": light,
    "shadow": shadow,
    "vampire": vampire,
    "reaper": reaper,
    "necromancer": necromancer,
    "demon": demon,
    "phoenix": phoenix,
    "dragon": dragon,
    "werewolf": werewolf,
    "dokkaebi": dokkaebi,
    "healer": healer,
    "bard": bard,
    "alchemist": alchemist,
}
