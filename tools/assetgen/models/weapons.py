"""
Ability weapons (docs/siege.md §11): in 공성전 a player's sword becomes the 3D weapon of their ability
(item model mcpl:weapon/<ability id>, AbilityWeaponModels). One function per ability builds it with the shared kit
(mcpl_assets/weaponkit.py: weapon space, common parts, the display preset) and the w_* textures
(mcpl_assets/weapon_textures.py). Register it in WEAPONS and in AbilityWeaponModels.MODELED.

Style (tools/assetgen/README.md): the weapon is what that job / ability would naturally carry, classic and readable
(no sci-fi). Emissive parts only where the ability's element is light (fire, lightning, a lamp, magic).

Reference weapons (the 공성전 default pool):
  police      경찰     black nightstick: ribbed grip, gold police badge, lanyard ring with a pair of handcuffs
  miner       광부     miner's pickaxe: forged iron double pick on a wooden haft, leather grip, a lit lantern on top
  blacksmith  대장장이  forge hammer: heavy iron head with a cross peen and a red-hot striking face, leather-wrapped haft
  gunslinger  총잡이    western revolver with a bayonet: walnut grip, brass guard, blued frame and cylinder, steel blade
  chef        요리사    chef's cleaver: wide steel blade with a hanging hole, wooden handle with brass rivets
"""

from mcpl_assets import weapon_textures  # noqa: F401  (registers the w_* textures)
from mcpl_assets.weaponkit import Weapon


def police():
    w = Weapon("weapon/police")
    w.group("handcuffs")
    # lanyard ring under the knob, a short leather strap and a pair of handcuffs hanging from it
    w.ring("lanyard", 0, -4.5, 2.0, 0.375, 0.25, "w_steel")
    w.box("strap", (-0.25, -6.75, -0.25), (0.25, -5.5, 0.25), "w_leather")
    w.ring("cuff_l", -1.375, -8.25, 2.75, 0.5, 0.375, "w_steel")
    w.ring("cuff_r", 1.375, -8.25, 2.75, 0.5, 0.375, "w_steel")
    w.group("handle")
    w.pommel(-2.0, w=3.0, d=3.0, h=1.5, tex="w_lacquer")
    w.grip(-2.0, 6.0, 2.5, 2.5)
    w.collar("collar", 6.0, 3.0, 3.0, 1.0, "w_lacquer")
    # the gold badge on both flats, just above the grip
    w.group("badge")
    w.both_flats("badge", (-1.25, 8.0, 1.25), (1.25, 10.5, 1.5), "w_gold")
    w.both_flats("badge_point", (-0.625, 7.25, 1.25), (0.625, 8.0, 1.5), "w_gold")
    w.both_flats("badge_star", (-0.5, 8.75, 1.5), (0.5, 9.75, 1.625), "w_brass")
    # the stick: black lacquer, slightly tapered, a steel ring and a rounded tip
    w.group("shaft")
    w.centred("shaft_low", 7.0, 15.0, 2.5, 2.5, "w_lacquer")
    w.centred("ring", 15.0, 15.5, 2.75, 2.75, "w_steel")
    w.centred("shaft_high", 15.5, 24.0, 2.25, 2.25, "w_lacquer")
    w.centred("tip", 24.0, 25.0, 2.0, 2.0, "w_lacquer")
    w.centred("tip_cap", 25.0, 25.5, 1.25, 1.25, "w_lacquer")
    return w.finish()


def miner():
    w = Weapon("weapon/miner")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_iron")
    w.grip(-2.0, 6.0, 2.25, 2.25, tex="w_wrap")
    w.collar("ferrule", 6.0, 2.5, 2.5, 0.75, "w_iron")
    w.centred("haft", 6.75, 17.0, 2.0, 2.0, "w_wood")
    w.centred("haft_top", 22.0, 22.5, 1.75, 1.75, "w_wood")
    # the forged head: an iron eye with two spikes curving down (each a horizontal box turned about its root)
    w.group("head")
    w.box("eye", (-2.0, 17.0, -1.75), (2.0, 22.0, 1.75), "w_iron")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"

        def seg(name, x0, x1, y0, y1, d, tex, angle, root):
            lo, hi = sorted((side * x0, side * x1))
            w.box(f"{name}_{tag}", (lo, y0, -d), (hi, y1, d), tex,
                  rotation=("z", -angle * side, (side * root[0], root[1], 0)))

        lo, hi = sorted((side * 2.0, side * 4.5))
        w.box(f"root_{tag}", (lo, 18.25, -1.375), (hi, 21.25, 1.375), "w_iron")
        seg("arm", 4.5, 8.5, 18.75, 21.0, 1.0, "w_iron", 22.5, (4.5, 19.875))
        seg("hook", 7.875, 11.375, 17.625, 19.125, 0.75, "w_iron", 45, (7.875, 18.375))
        seg("point", 10.125, 12.375, 15.5, 16.25, 0.375, "w_steel_edge", 45, (10.125, 15.875))
    # a small brass lantern riding on top of the head (the miner's lamp: lit)
    w.group("lantern")
    w.centred("lamp_base", 22.5, 23.0, 2.25, 2.25, "w_brass")
    w.centred("lamp_flame", 23.0, 24.75, 1.5, 1.5, "w_amber", glow=True)
    for n, (x, z) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        w.centred(f"lamp_post{n}", 23.0, 24.75, 0.25, 0.25, "w_brass", x=x * 0.875, z=z * 0.875)
    w.centred("lamp_cap", 24.75, 25.25, 2.25, 2.25, "w_brass")
    w.ring("lamp_handle", 0, 26.0, 1.5, 0.25, 0.125, "w_brass")
    return w.finish()


def blacksmith():
    w = Weapon("weapon/blacksmith")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_iron")
    w.grip(-2.0, 7.0, 2.25, 2.25, tex="w_wrap")
    w.centred("haft", 7.0, 16.5, 2.0, 2.0, "w_wood")
    # iron langets riveted along the haft under the head
    w.both_flats("langet", (-0.5, 12.5, 1.0), (0.5, 16.5, 1.25), "w_iron")
    w.rivets("langet_rivet", [(0, 13.5), (0, 15.25)], 1.25, tex="w_iron_dark")
    # the head: a heavy iron block, red-hot striking face on the right, cross peen on the left
    w.group("head")
    w.box("head_core", (-3.0, 16.5, -2.75), (3.0, 23.0, 2.75), "w_iron")
    w.box("neck_r", (3.0, 17.0, -2.5), (6.0, 22.5, 2.5), "w_iron")
    w.box("face_rim", (6.0, 16.75, -2.75), (6.75, 22.75, 2.75), "w_iron_dark")
    w.edge_strip("face_hot", (6.75, 17.25, -2.25), (7.25, 22.25, 2.25), "w_heat")
    w.box("taper_l", (-5.0, 17.25, -2.0), (-3.0, 22.25, 2.0), "w_iron")
    w.box("peen", (-7.0, 17.75, -1.0), (-5.0, 21.75, 1.0), "w_iron")
    w.box("peen_edge", (-7.5, 18.0, -0.5), (-7.0, 21.5, 0.5), "w_steel")
    w.centred("eye_top", 23.0, 23.5, 1.75, 1.75, "w_wood")
    w.centred("wedge", 23.5, 23.75, 0.5, 1.75, "w_iron_dark")
    w.rivets("head_rivet", [(-2.0, 22.0), (2.0, 22.0), (-2.0, 17.5), (2.0, 17.5)], 2.75, tex="w_iron_dark")
    return w.finish()


def gunslinger():
    w = Weapon("weapon/gunslinger")
    w.group("grip")
    w.centred("butt_cap", -3.0, -2.0, 2.75, 2.25, "w_brass")
    w.grip(-2.0, 5.0, 2.25, 2.0, tex="w_wood")
    w.rivets("grip_screw", [(0, 1.5)], 1.0, tex="w_brass")
    w.group("action")
    w.box("frame", (-2.5, 5.0, -1.25), (2.5, 9.5, 1.25), "w_iron_dark")
    w.box("guard_bottom", (1.0, 3.75, -0.375), (3.5, 4.25, 0.375), "w_brass")
    w.box("guard_front", (3.0, 4.25, -0.375), (3.5, 5.0, 0.375), "w_brass")
    w.box("trigger", (1.125, 4.25, -0.25), (1.75, 5.0, 0.25), "w_steel")
    w.box("hammer", (-3.75, 4.75, -0.375), (-2.5, 6.0, 0.375), "w_steel", rotation=("z", 22.5, (-2.5, 5.0, 0)))
    # six-shot cylinder: blued steel with bright flutes on both flats
    w.box("cylinder", (-2.25, 5.75, -2.0), (1.25, 8.75, 2.0), "w_iron_dark")
    for n, x in enumerate((-1.0, 0.25)):
        w.both_flats(f"flute{n}", (x - 0.1875, 6.25, 2.0), (x + 0.1875, 8.25, 2.0625), "w_iron")
    w.box("cylinder_pin", (-1.0, 8.75, -0.5), (0.0, 9.5, 0.5), "w_steel")
    # long barrel with an ejector rod beside it and a brass front sight
    w.group("barrel")
    w.box("barrel", (-3.0, 9.5, -1.0), (-0.5, 23.0, 1.0), "w_iron_dark")
    w.box("barrel_rib", (-3.25, 9.5, -0.375), (-3.0, 23.0, 0.375), "w_steel")
    w.box("ejector", (-0.5, 9.5, -0.5), (0.25, 17.5, 0.5), "w_steel")
    w.box("muzzle", (-3.25, 23.0, -1.125), (-0.25, 23.75, 1.125), "w_steel")
    w.box("sight", (-3.75, 21.75, -0.25), (-3.25, 22.75, 0.25), "w_brass")
    # bayonet: clamped round the barrel, its blade running past the muzzle
    w.group("bayonet")
    w.box("clamp", (-3.375, 18.0, -1.25), (0.75, 19.5, 1.25), "w_steel")
    w.blade("blade", 19.5, 27.5, -0.25, 2.25, 0.75, steps=[(25.0, 0.5)], edge_tex="w_steel_edge", edge_w=0.5,
            tip=(2.0, 0.5))
    w.both_flats("fuller", (0.125, 20.0, 0.375), (0.625, 26.5, 0.4375), "w_iron")
    return w.finish()


def chef():
    w = Weapon("weapon/chef")
    w.group("handle")
    w.centred("butt", -3.5, -2.5, 2.5, 2.0, "w_steel")
    w.grip(-2.5, 5.5, 2.25, 1.75, tex="w_wood")
    w.rivets("handle_rivet", [(0, -1.25), (0, 1.5), (0, 4.25)], 0.875)
    w.box("bolster", (-1.5, 5.5, -1.125), (1.5, 7.0, 1.125), "w_steel")
    # the cleaver: thick spine, wide flat with a hanging hole, honed edge
    w.group("blade")
    w.box("spine", (-1.5, 7.0, -0.625), (-0.5, 22.5, 0.625), "w_iron")
    w.box("flat_low", (-0.5, 7.0, -0.375), (5.75, 18.5, 0.375), "w_steel")
    w.box("flat_hole_l", (-0.5, 18.5, -0.375), (0.5, 20.5, 0.375), "w_steel")
    w.box("flat_hole_r", (2.0, 18.5, -0.375), (5.75, 20.5, 0.375), "w_steel")
    w.box("flat_top", (-0.5, 20.5, -0.375), (5.75, 22.5, 0.375), "w_steel")
    w.box("edge", (5.75, 7.5, -0.25), (6.5, 22.5, 0.25), "w_steel_edge")
    w.box("heel", (5.75, 7.0, -0.25), (6.5, 7.5, 0.25), "w_steel")
    w.box("top_bevel", (-1.5, 22.5, -0.375), (6.5, 23.0, 0.375), "w_steel_edge")
    return w.finish()


# ability id -> builder (the model is written to models/weapon/<id>.bbmodel = item model mcpl:weapon/<id>)
WEAPONS = {
    "police": police,
    "miner": miner,
    "blacksmith": blacksmith,
    "gunslinger": gunslinger,
    "chef": chef,
}
from models import weapons_b2  # noqa: E402  (batch 2: element, spirit and support abilities)
WEAPONS.update(weapons_b2.WEAPONS)

from models.weapons_b3 import WEAPONS as _B3  # noqa: E402  (batch 3: engineer .. batter)
WEAPONS.update(_B3)
