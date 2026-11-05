"""
Ability weapons, batch 1 (registered into weapons.WEAPONS): 15 combat / element abilities. Same contract as
weapons.py (one function per ability, weaponkit space, ORIENTATION RULE: business side on +x) but each weapon has its
own family, silhouette and materials so the lineup never repeats itself:

  ninja      닌자      kusarigama: black sickle on a dark wrapped handle, a chain hanging from the pommel to a shuriken
  blade      검성      katana: navy silk-wrapped hilt, gold round tsuba, long slightly curved blade with a hamon edge
  duelist    결투가     rapier: gold swept hilt (long quillons, knuckle bow, side ring), red leather grip, needle blade
  archer     궁수      recurve bow: wooden limbs with bone tips, leather-wrapped riser, brass arrow rest, taut string
  sniper     저격수     bolt-action rifle: walnut stock, blued receiver and long barrel, black scope with brass rings
  hunter     사냥꾼     Bowie knife: clip-point blade with a saw-back spine, antler handle, rawhide lanyard with a fang
  lancer     창술사     spear: leaf-shaped steel head, red horsehair tassel, long dark shaft with an iron butt spike
  berserker  광전사     double-bit war axe: two flaring iron bits, a top spike, a red rag tied under the head
  guardian   수호자     flanged mace with a blue-and-gold heater-shield guard plate over the hand
  fighter    격투가     fighting glove: red leather fist with steel knuckle studs, white-taped wrist
  pyro       화염술사    brazier torch: black iron staff, an iron cage holding a living flame (emissive)
  frost      빙결술사    ice sword: frosted silver hilt with icicles, a broad blade of jagged pale ice
  thunder    뇌신      storm hammer: a squared iron head with gold bands, a lightning rune and arcs (emissive), wrist strap
  aero       바람술사    war fan: iron-tipped ribs, pleated paper painted with a teal wind band, red tassel
  earth      대지술사    earth-splitter: ash haft, a stone socket bursting into a crown of jagged rock shards, moss
"""

from mcpl_assets import weapon_textures  # noqa: F401  (registers the w_* textures)
from mcpl_assets import weapon_textures_b1  # noqa: F401  (batch 1 textures)
from mcpl_assets.weaponkit import Weapon


def ninja():
    w = Weapon("weapon/ninja")
    w.group("handle")
    w.centred("cap", 13.0, 14.5, 2.25, 2.25, "w_iron_dark")
    w.centred("handle", -3.0, 13.0, 1.75, 1.75, "w_wood_dark")
    w.grip(-2.0, 6.0, 2.125, 2.125, tex="w_wrap")
    w.collar("band", 9.0, 2.0, 2.0, 0.5, "w_iron_dark")
    w.centred("pommel", -4.0, -3.0, 2.25, 2.25, "w_iron_dark")
    # the sickle: a blackened blade leaving the top of the handle towards +x and hooking down to a point
    w.group("sickle")
    w.box("tang", (0.875, 11.0, -0.5), (2.0, 14.0, 0.5), "w_iron_dark")
    w.box("blade_root", (2.0, 11.5, -0.375), (6.0, 14.0, 0.375), "w_iron_dark")
    w.box("blade_edge", (2.0, 10.875, -0.25), (6.0, 11.5, 0.25), "w_steel_edge")
    w.strut("blade_mid", (5.5, 12.5), (8.75, 11.0), 2.5, 0.875, "w_iron_dark", extend=0.5)
    w.strut("blade_mid_edge", (5.75, 11.5), (8.25, 10.25), 0.625, 0.5, "w_steel_edge", extend=0.25)
    w.strut("blade_hook", (8.75, 11.5), (9.75, 8.25), 1.875, 0.75, "w_iron_dark", extend=0.5)
    w.strut("blade_hook_edge", (8.0, 10.25), (8.5, 8.75), 0.5, 0.5, "w_steel_edge", extend=0.25)
    w.strut("blade_point", (9.75, 8.75), (10.25, 6.75), 0.75, 0.5, "w_steel_edge", extend=0.125)
    # the chain (kusari) from the pommel, links alternating flat and edge-on, ending in a shuriken
    w.group("chain")
    w.ring("chain_ring", 0, -4.75, 1.5, 0.375, 0.25, "w_steel")
    for n in range(3):
        y = -6.25 - n * 1.75
        if n % 2 == 0:
            w.centred(f"link{n}", y - 0.75, y + 0.5, 0.375, 1.25, "w_iron_dark")
        else:
            w.ring(f"link{n}", 0, y, 1.25, 0.375, 0.1875, "w_iron_dark")
    w.group("shuriken")
    cy = -11.75
    for n, sx in enumerate((1, -1)):
        z = 0.125 if n else -0.125
        w.strut(f"star{n}", (-2.0 * sx, cy - 2.0), (2.0 * sx, cy + 2.0), 1.75, 0.25, "w_steel", z=z)
        w.strut(f"star_tip{n}", (-2.75 * sx, cy - 2.75), (2.75 * sx, cy + 2.75), 0.75, 0.125, "w_steel_edge", z=z)
    w.box("star_hub", (-0.75, -12.5, -0.375), (0.75, -11.0, 0.375), "w_iron_dark")
    w.size = 0.9  # small: a hand sickle
    return w.finish()


def blade():
    w = Weapon("weapon/blade")
    w.group("hilt")
    w.centred("kashira", -4.0, -3.0, 2.5, 2.0, "w_iron_dark")
    w.grip(-3.0, 6.0, 2.25, 1.75, tex="w_tsuka")
    w.both_flats("menuki", (-0.5, 1.0, 0.875), (0.75, 2.25, 1.0), "w_gold")
    w.centred("fuchi", 5.5, 6.0, 2.5, 2.0, "w_gold")
    # round gold tsuba (two crossed slabs read as a disc)
    w.centred("tsuba_a", 6.0, 6.75, 5.0, 3.0, "w_gold")
    w.centred("tsuba_b", 6.125, 6.625, 4.0, 4.0, "w_gold")
    w.centred("habaki", 6.75, 7.75, 2.25, 1.25, "w_brass")
    # the blade: steel body, honed hamon edge on +x, curving back towards the spine near the tip
    w.group("blade")
    segs = [(7.75, 12.0, 0.0), (12.0, 16.0, -0.25), (16.0, 19.5, -0.5), (19.5, 23.0, -0.75)]
    for n, (y0, y1, sx) in enumerate(segs):
        w.box(f"body{n}", (-1.0 + sx, y0, -0.4375), (0.5 + sx, y1, 0.4375), "w_steel")
        w.box(f"edge{n}", (0.5 + sx, y0, -0.25), (1.125 + sx, y1, 0.25), "w_steel_edge")
    w.box("yokote", (-1.875, 23.0, -0.4375), (0.375, 23.25, 0.4375), "w_iron")
    w.box("kissaki_a", (-1.875, 23.25, -0.375), (0.25, 25.0, 0.375), "w_steel_edge")
    w.box("kissaki_b", (-1.875, 25.0, -0.25), (-0.5, 26.25, 0.25), "w_steel_edge")
    w.both_flats("hi", (-0.875, 8.5, 0.4375), (-0.375, 12.0, 0.5), "w_iron")
    return w.finish()


def duelist():
    w = Weapon("weapon/duelist")
    w.group("hilt")
    w.centred("pommel", -4.0, -2.25, 2.25, 2.25, "w_gold")
    w.centred("pommel_button", -4.5, -4.0, 1.0, 1.0, "w_gold")
    w.grip(-2.25, 5.0, 1.75, 1.75, tex="w_glove")
    w.collar("ferrule", -2.25, 2.0, 2.0, 0.5, "w_brass")
    # long straight quillons with knobs, a knuckle bow on +x and a ring round the ricasso
    w.box("quillon", (-5.5, 5.0, -0.5), (5.5, 5.75, 0.5), "w_gold")
    w.centred("quillon_knob_l", 4.75, 6.0, 1.25, 1.25, "w_gold", x=-6.0)
    w.centred("quillon_knob_r", 4.75, 6.0, 1.25, 1.25, "w_gold", x=6.0)
    w.centred("block", 4.5, 6.25, 2.0, 1.5, "w_gold")
    w.polyline("bow", [(3.25, 5.0), (3.75, 2.5), (3.25, -0.5), (1.5, -2.5)], 0.5, 0.5, "w_gold")
    w.ring("side_ring", 0, 8.5, 4.5, 0.5, 0.25, "w_gold")
    # the needle blade: a squared ricasso then a long thin tapering blade
    w.group("blade")
    w.centred("ricasso", 6.25, 10.0, 1.0, 0.75, "w_steel")
    w.centred("blade_a", 10.0, 19.0, 1.25, 0.625, "w_steel")
    w.centred("blade_ridge", 10.0, 19.0, 0.375, 0.75, "w_steel_edge")
    w.centred("blade_b", 19.0, 25.0, 0.875, 0.5, "w_steel")
    w.centred("blade_c", 25.0, 28.0, 0.5, 0.375, "w_steel_edge")
    w.centred("blade_tip", 28.0, 29.0, 0.25, 0.25, "w_steel_edge")
    return w.finish()


def archer():
    w = Weapon("weapon/archer")
    w.group("riser")
    w.centred("riser", -3.0, 6.0, 1.75, 1.75, "w_wood")
    w.grip(-1.5, 4.5, 2.25, 2.25, tex="w_wrap")
    w.box("rest", (-1.625, 4.5, -1.375), (1.125, 5.25, -0.875), "w_brass")
    # limbs: away from the riser towards the string (+x), recurved tips with bone nocks
    w.group("limbs")
    up = [(0.0, 5.5), (0.75, 9.5), (2.0, 12.5), (2.75, 14.75)]
    w.polyline("limb_up", up, 1.375, 1.5, "w_wood", extend=0.5)
    w.polyline("limb_lo", [(x, 3.0 - y) for x, y in up], 1.375, 1.5, "w_wood", extend=0.5)
    for tag, sign in (("up", 1), ("lo", -1)):
        y = 1.5 + sign * 13.25
        w.strut(f"tip_{tag}", (2.75, 1.5 + sign * 14.5), (2.0, 1.5 + sign * 16.25), 1.0, 1.25, "w_antler",
                extend=0.25)
        w.box(f"nock_{tag}", (2.5, y + sign * 0.5 - 0.375, -0.8125), (3.5, y + sign * 0.5 + 0.375, 0.8125), "w_antler")
    # the string, nock to nock
    w.group("string")
    w.box("string", (3.5, -11.25, -0.125), (3.75, 14.25, 0.125), "w_paper")
    w.box("serving", (3.4375, -0.5, -0.1875), (3.8125, 3.5, 0.1875), "w_hemp")
    return w.finish()


def sniper():
    w = Weapon("weapon/sniper")
    w.group("stock")
    w.box("butt_plate", (-1.5, -8.75, -0.875), (2.25, -8.25, 0.875), "w_iron_dark")
    w.box("butt", (-1.375, -8.25, -0.8125), (2.0, -4.5, 0.8125), "w_wood")
    w.box("comb", (-1.5, -7.75, -0.6875), (-1.375, -5.0, 0.6875), "w_wood")
    w.box("butt_neck", (-1.125, -4.5, -0.75), (1.5, -2.5, 0.75), "w_wood")
    w.grip(-2.5, 5.0, 1.875, 1.5, tex="w_wood")
    w.both_flats("checker", (-0.75, -1.0, 0.75), (0.75, 4.0, 0.8125), "w_wood_dark")
    w.group("action")
    w.box("receiver", (-1.25, 5.0, -0.875), (1.0, 11.0, 0.875), "w_iron_dark")
    w.box("guard_bottom", (1.0, 4.75, -0.375), (2.75, 5.25, 0.375), "w_brass")
    w.box("guard_front", (2.25, 5.25, -0.375), (2.75, 7.25, 0.375), "w_brass")
    w.box("trigger", (1.0, 5.75, -0.25), (1.625, 6.5, 0.25), "w_steel")
    w.box("bolt", (-0.75, 8.25, 0.875), (-0.25, 8.75, 2.5), "w_steel")
    w.box("bolt_knob", (-1.0, 8.0, 2.5), (0.0, 9.0, 3.0), "w_steel")
    w.group("barrel")
    w.box("fore", (-0.5, 11.0, -0.75), (1.75, 18.0, 0.75), "w_wood")
    w.box("fore_cap", (-0.625, 18.0, -0.8125), (1.0, 18.75, 0.8125), "w_iron_dark")
    w.box("barrel", (-0.875, 11.0, -0.5), (0.375, 26.0, 0.5), "w_iron_dark")
    w.box("muzzle", (-1.0, 26.0, -0.625), (0.5, 27.25, 0.625), "w_steel")
    w.group("scope")
    w.box("mount_a", (-2.0, 7.0, -0.375), (-1.25, 8.0, 0.375), "w_brass")
    w.box("mount_b", (-2.0, 12.5, -0.375), (-0.875, 13.5, 0.375), "w_brass")
    w.box("tube", (-3.25, 6.0, -0.625), (-2.0, 15.0, 0.625), "w_lacquer")
    w.box("bell", (-3.5, 15.0, -0.875), (-1.75, 17.5, 0.875), "w_lacquer")
    w.box("eyepiece", (-3.5, 4.5, -0.875), (-1.75, 6.0, 0.875), "w_lacquer")
    w.box("lens", (-3.25, 17.5, -0.625), (-2.0, 17.625, 0.625), "w_glass")
    w.box("turret", (-2.875, 9.5, 0.625), (-2.375, 10.5, 1.125), "w_brass")
    return w.finish()


def hunter():
    w = Weapon("weapon/hunter")
    w.group("handle")
    w.centred("butt_cap", -3.0, -2.0, 2.5, 2.0, "w_brass")
    w.grip(-2.0, 5.0, 2.25, 1.75, tex="w_antler")
    for n, y in enumerate((-0.5, 1.75, 4.0)):
        w.box(f"tine{n}", (-1.5, y, -0.625), (-1.125, y + 0.75, 0.625), "w_antler")
    w.box("guard", (-3.0, 5.0, -1.0), (3.0, 6.0, 1.0), "w_brass")
    w.box("guard_l", (-3.5, 3.75, -0.75), (-2.5, 5.0, 0.75), "w_brass")
    w.box("guard_r", (2.5, 6.0, -0.75), (3.5, 7.0, 0.75), "w_brass")
    # rawhide lanyard with a boar fang
    w.strut("thong", (0.0, -3.0), (-0.75, -6.0), 0.375, 0.375, "w_leather", extend=0.25)
    w.strut("fang", (-0.875, -6.25), (-0.25, -8.75), 0.75, 0.75, "w_antler", extend=0.0)
    # the clip-point blade: wide steel with the edge on +x, a saw-back spine
    w.group("blade")
    rows = [(6.0, 15.0, -1.25, 3.0), (15.0, 17.0, -0.25, 3.0), (17.0, 18.75, 0.5, 2.875), (18.75, 20.25, 1.0, 2.5)]
    for n, (y0, y1, spine, edge) in enumerate(rows):
        w.box(f"flat{n}", (spine, y0, -0.375), (edge - 0.625, y1, 0.375), "w_steel")
        w.box(f"edge{n}", (edge - 0.625, y0, -0.25), (edge, y1, 0.25), "w_steel_edge")
    w.box("tip", (1.25, 20.25, -0.25), (2.25, 21.5, 0.25), "w_steel_edge")
    w.box("clip_bevel", (-0.625, 15.0, -0.25), (0.125, 16.875, 0.25), "w_steel_edge")
    w.both_flats("fuller", (-0.75, 7.0, 0.375), (0.0, 14.0, 0.4375), "w_iron")
    for n in range(5):
        y = 7.25 + n * 1.5
        w.box(f"tooth{n}", (-1.75, y, -0.25), (-0.875, y + 0.875, 0.25), "w_steel",
              rotation=("z", 45, (-1.3125, y + 0.4375, 0)))
    w.size = 0.9  # small: a knife
    return w.finish()


def lancer():
    w = Weapon("weapon/lancer")
    w.group("shaft")
    w.centred("butt_spike", -6.0, -5.0, 0.75, 0.75, "w_iron")
    w.centred("butt_cap", -5.0, -4.0, 2.0, 2.0, "w_iron")
    w.centred("shaft", -4.0, 20.0, 1.75, 1.75, "w_wood_dark")
    w.grip(-2.0, 5.0, 2.0, 2.0, tex="w_wrap")
    w.group("tassel")
    w.centred("tassel_knot", 19.25, 20.5, 2.5, 2.5, "w_cloth_red")
    w.centred("tassel_top", 17.5, 19.25, 3.25, 3.25, "w_cloth_red")
    w.centred("tassel_low", 15.5, 17.5, 2.75, 2.75, "w_cloth_red")
    for n, (x, z) in enumerate(((-1.25, -1.0), (1.125, 0.75), (0.0, 1.0), (-0.75, 0.875), (1.0, -1.125))):
        w.centred(f"strand{n}", 14.0 + 0.5 * (n % 2), 15.5, 0.625, 0.625, "w_cloth_red", x=x, z=z)
    w.group("head")
    w.centred("socket", 20.0, 22.0, 2.0, 2.0, "w_iron")
    w.centred("socket_ring", 21.0, 21.5, 2.25, 2.25, "w_iron_dark")
    for n, (y0, y1, wd) in enumerate(((22.0, 23.25, 1.5), (23.25, 25.0, 3.0), (25.0, 27.25, 3.75),
                                      (27.25, 29.0, 2.75), (29.0, 30.5, 1.5))):
        w.centred(f"leaf{n}", y0, y1, wd, 0.75, "w_steel")
    w.centred("ridge", 22.0, 30.5, 0.5, 1.0, "w_steel_edge")
    w.centred("point", 30.5, 31.75, 0.5, 0.5, "w_steel_edge")
    w.size = 1.7  # huge: a spear longer than its owner is tall
    w.size_first = 1.15  # smaller on the own screen
    return w.finish()


def berserker():
    w = Weapon("weapon/berserker")
    w.group("haft")
    w.centred("pommel", -4.0, -3.0, 2.5, 2.5, "w_iron_dark")
    w.centred("haft", -3.0, 15.0, 2.0, 2.0, "w_wood")
    w.grip(-2.0, 5.5, 2.25, 2.25, tex="w_wrap")
    # a red rag knotted under the head, two tails hanging
    w.centred("rag", 12.5, 14.0, 2.5, 2.5, "w_cloth_red")
    w.box("rag_tail_a", (1.25, 10.0, -0.125), (2.0, 12.75, 0.375), "w_cloth_red")
    w.box("rag_tail_b", (-1.75, 9.25, -0.375), (-1.25, 12.75, 0.125), "w_cloth_red")
    # the double-bit head: an iron eye, a flaring bit on each side, a short top spike
    w.group("head")
    w.box("eye", (-1.75, 14.5, -1.5), (1.75, 21.5, 1.5), "w_iron_dark")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"

        def bx(name, x0, x1, y0, y1, d, tex):
            lo, hi = sorted((side * x0, side * x1))
            w.box(f"{name}_{tag}", (lo, y0, -d), (hi, y1, d), tex)

        bx("neck", 1.75, 3.0, 15.5, 20.5, 1.0, "w_iron")
        bx("cheek", 3.0, 5.25, 14.0, 22.0, 0.75, "w_iron")
        bx("horn_low", 4.25, 5.25, 12.25, 14.0, 0.625, "w_iron")
        bx("horn_high", 4.25, 5.25, 22.0, 23.75, 0.625, "w_iron")
        bx("edge", 5.25, 6.0, 12.25, 23.75, 0.5, "w_steel_edge")
        bx("notch", 4.0, 4.5, 17.0, 18.5, 0.875, "w_iron_dark")
    w.centred("spike", 21.5, 23.5, 1.5, 1.5, "w_iron")
    w.centred("spike_tip", 23.5, 25.0, 0.75, 0.75, "w_steel_edge")
    w.rivets("eye_rivet", [(0, 16.0), (0, 20.0)], 1.5, tex="w_iron")
    w.size = 1.5  # huge: a two-handed war axe
    w.size_first = 1.15  # smaller on the own screen
    return w.finish()


def guardian():
    w = Weapon("weapon/guardian")
    w.group("handle")
    w.centred("pommel", -4.0, -2.5, 2.5, 2.5, "w_gold")
    w.grip(-2.5, 4.5, 2.0, 2.0, tex="w_wrap")
    # the heater shield over the hand: blue enamel, gold rim and cross, pointed at the bottom
    w.group("shield")
    w.box("shield_top", (-3.75, 9.0, -0.75), (3.75, 12.0, 0.75), "w_enamel")
    w.box("shield_mid", (-3.25, 7.0, -0.75), (3.25, 9.0, 0.75), "w_enamel")
    w.box("shield_low", (-2.25, 5.5, -0.75), (2.25, 7.0, 0.75), "w_enamel")
    w.box("shield_point", (-1.0, 4.5, -0.75), (1.0, 5.5, 0.75), "w_enamel")
    w.box("rim_top", (-4.25, 12.0, -0.875), (4.25, 12.75, 0.875), "w_gold")
    w.box("rim_l", (-4.25, 9.0, -0.875), (-3.75, 12.0, 0.875), "w_gold")
    w.box("rim_r", (3.75, 9.0, -0.875), (4.25, 12.0, 0.875), "w_gold")
    w.both_flats("cross_v", (-0.375, 5.5, 0.75), (0.375, 11.5, 0.9375), "w_gold")
    w.both_flats("cross_h", (-2.75, 9.25, 0.75), (-0.375, 10.0, 0.9375), "w_gold")
    w.both_flats("cross_h2", (0.375, 9.25, 0.75), (2.75, 10.0, 0.9375), "w_gold")
    # the haft and a flanged steel mace head with gold bands
    w.group("mace")
    w.centred("haft", 12.75, 17.0, 1.75, 1.75, "w_iron_dark")
    w.centred("band_low", 17.0, 17.75, 2.75, 2.75, "w_gold")
    w.centred("core", 17.75, 24.0, 2.5, 2.5, "w_iron")
    for n, (x, z, wd, dp) in enumerate(((2.25, 0, 2.0, 0.75), (-2.25, 0, 2.0, 0.75),
                                        (0, 2.25, 0.75, 2.0), (0, -2.25, 0.75, 2.0))):
        w.centred(f"flange{n}", 18.25, 23.5, wd, dp, "w_steel", x=x, z=z)
        w.centred(f"flange_top{n}", 23.5, 24.25, wd / 2 if wd > 1 else wd, dp / 2 if dp > 1 else dp, "w_steel",
                  x=x * 0.75, z=z * 0.75)
    w.centred("band_top", 24.0, 24.75, 2.25, 2.25, "w_gold")
    w.centred("finial", 24.75, 26.0, 1.0, 1.0, "w_gold")
    w.size = 1.4  # huge: a heavy mace behind a shield plate
    w.size_first = 1.1  # smaller on the own screen
    return w.finish()


def fighter():
    w = Weapon("weapon/fighter")
    w.group("wrist")
    w.centred("wrist", -2.5, 4.5, 2.75, 2.75, "w_handwrap")
    w.centred("tape_end", -3.0, -2.5, 2.25, 2.25, "w_handwrap")
    w.centred("cuff", 4.5, 6.5, 4.0, 4.0, "w_glove")
    w.centred("cuff_trim", 4.25, 4.5, 3.5, 3.5, "w_antler")
    # the fist: back of the hand on -x, four curled fingers side by side (across z) on +x, the thumb lying across
    # them, the knuckles leading at the top
    w.group("fist")
    w.box("back", (-3.0, 6.5, -2.5), (1.75, 13.0, 2.5), "w_glove")
    for n in range(4):
        z0 = -2.375 + n * 1.1875
        w.box(f"finger{n}", (1.75, 7.25, z0 + 0.0625), (3.5, 12.75, z0 + 1.125), "w_glove")
    w.box("thumb", (3.5, 8.0, -2.25), (4.25, 9.75, 1.25), "w_glove")
    w.box("thumb_root", (1.75, 7.0, 1.375), (3.75, 9.75, 2.75), "w_glove")
    for n in range(4):
        z0 = -2.375 + n * 1.1875
        w.box(f"knuckle{n}", (-2.0, 13.0, z0 + 0.0625), (3.0, 13.875, z0 + 1.125), "w_glove")
        w.box(f"stud{n}", (-0.25, 13.875, z0 + 0.3125), (1.25, 14.375, z0 + 0.875), "w_steel")
    w.size = 0.85  # small: a glove
    return w.finish()


def pyro():
    w = Weapon("weapon/pyro")
    w.group("staff")
    w.centred("pommel", -4.5, -3.0, 2.25, 2.25, "w_iron_dark")
    w.centred("shaft", -3.0, 15.0, 1.75, 1.75, "w_iron_dark")
    w.grip(-2.0, 5.0, 2.125, 2.125, tex="w_wrap")
    w.collar("band", 10.0, 2.25, 2.25, 0.75, "w_brass")
    # the brazier: an iron bowl and four curved prongs holding a living flame
    w.group("brazier")
    w.centred("bowl_neck", 15.0, 16.0, 2.5, 2.5, "w_iron")
    w.centred("bowl", 16.0, 17.5, 4.5, 4.0, "w_iron")
    w.centred("embers", 17.5, 18.0, 3.75, 3.25, "w_heat", glow=True)
    for side in (1, -1):
        w.polyline(f"prong_x{'r' if side > 0 else 'l'}", [(side * 2.25, 17.0), (side * 3.25, 20.5),
                                                           (side * 2.75, 23.0)], 0.625, 0.625, "w_iron_dark")
        w.box(f"prong_z{'r' if side > 0 else 'l'}", (-0.3125, 17.0, side * 2.0 - 0.3125),
              (0.3125, 21.5, side * 2.0 + 0.3125), "w_iron_dark")
    w.group("flame")
    w.centred("flame_core", 18.0, 21.5, 3.0, 2.5, "w_flame", glow=True)
    w.centred("flame_mid", 21.5, 24.0, 2.0, 1.75, "w_flame", glow=True, x=0.25)
    w.centred("flame_tip", 24.0, 25.75, 1.0, 1.0, "w_flame", glow=True, x=-0.25)
    w.centred("flame_lick", 22.0, 23.5, 0.75, 0.75, "w_flame", glow=True, x=-1.25)
    w.centred("flame_lick2", 21.5, 22.5, 0.75, 0.75, "w_flame", glow=True, x=1.625)
    return w.finish()


def frost():
    w = Weapon("weapon/frost")
    w.group("hilt")
    w.centred("pommel", -4.5, -2.5, 1.5, 1.5, "w_ice")
    w.centred("pommel_seat", -2.5, -2.0, 2.25, 2.25, "w_frost")
    w.grip(-2.0, 5.0, 2.0, 1.75, tex="w_wrap_blue")
    w.box("guard", (-4.5, 5.0, -1.0), (4.5, 6.25, 1.0), "w_frost")
    w.box("guard_tip_l", (-5.5, 5.5, -0.5), (-4.5, 7.0, 0.5), "w_frost")
    w.box("guard_tip_r", (4.5, 5.5, -0.5), (5.5, 7.0, 0.5), "w_frost")
    # icicles hanging from the guard
    for n, (x, l) in enumerate(((-3.5, 2.0), (-2.25, 1.25), (2.5, 1.5), (3.75, 2.25))):
        w.centred(f"icicle{n}", 5.0 - l, 5.0, 0.5, 0.5, "w_ice", x=x)
    # the blade: a broad slab of pale ice with shards breaking out of both edges
    w.group("blade")
    w.centred("blade_a", 6.25, 18.0, 3.5, 1.25, "w_ice")
    w.centred("blade_b", 18.0, 22.0, 2.75, 1.0, "w_ice")
    w.centred("blade_c", 22.0, 25.0, 1.75, 0.75, "w_ice")
    w.centred("blade_tip", 25.0, 27.0, 0.75, 0.5, "w_ice")
    w.centred("core", 6.25, 21.0, 0.75, 1.5, "w_frost")
    for n, (y, side) in enumerate(((8.5, 1), (11.0, -1), (13.5, 1), (16.0, -1), (19.5, 1))):
        x = side * (1.75 if y < 18 else 1.375)
        w.strut(f"shard{n}", (x - side * 0.25, y), (x + side * 1.25, y + 1.75), 0.75, 0.75, "w_ice")
    return w.finish()


def thunder():
    w = Weapon("weapon/thunder")
    w.group("handle")
    w.ring("strap", 0, -5.75, 3.0, 0.5, 0.25, "w_leather")
    w.centred("pommel", -4.25, -3.0, 2.25, 2.25, "w_gold")
    w.grip(-3.0, 6.0, 2.0, 2.0, tex="w_wrap")
    w.centred("neck", 6.0, 8.0, 2.25, 2.25, "w_iron_dark")
    w.collar("collar", 7.0, 2.75, 2.75, 1.0, "w_gold")
    # the head: a heavy squared block across the handle, gold-banded ends, a lightning rune burning on each flat
    w.group("head")
    w.box("head", (-5.0, 8.0, -2.5), (5.0, 15.0, 2.5), "w_iron")
    for side in (1, -1):
        lo, hi = sorted((side * 5.0, side * 6.25))
        w.box(f"face{side}", (lo, 7.75, -2.75), (hi, 15.25, 2.75), "w_iron_dark")
        lo, hi = sorted((side * 3.25, side * 4.0))
        w.box(f"band{side}", (lo, 7.875, -2.625), (hi, 15.125, 2.625), "w_gold")
    w.group("rune")
    for name, frm, to in (("r0", (0.25, 13.5), (1.5, 14.25)), ("r1", (-0.5, 11.5), (0.75, 13.5)),
                          ("r2", (-0.5, 11.0), (1.25, 11.75)), ("r3", (0.25, 9.0), (1.0, 11.0)),
                          ("r4", (-0.5, 8.75), (0.5, 9.25))):
        w.both_flats(name, (frm[0], frm[1], 2.5), (to[0], to[1], 2.625), "w_spark", glow=True)
    # crackling arcs over the top of the head
    w.box("arc", (-6.0, 15.25, -0.125), (6.0, 17.25, 0.125), "w_arc", glow=True)
    w.size = 1.45  # huge: a storm hammer
    w.size_first = 1.1  # smaller on the own screen
    return w.finish()


def aero():
    w = Weapon("weapon/aero")
    w.group("handle")
    w.centred("pommel", -3.5, -2.5, 2.0, 1.5, "w_lacquer")
    w.grip(-2.5, 5.0, 1.75, 1.25, tex="w_lacquer")
    w.ring("tassel_ring", 0, -4.0, 1.25, 0.25, 0.125, "w_brass")
    w.centred("tassel_knot", -6.0, -4.625, 1.0, 1.0, "w_cloth_red")
    w.centred("tassel", -9.5, -6.0, 1.25, 1.25, "w_cloth_red")
    # the open fan: pleated paper panels between iron-tipped ribs radiating from the pivot rivet
    w.group("fan")
    import math
    pivot = (0.0, 5.0)
    angles = [-67.5, -45.0, -22.5, 0.0, 22.5, 45.0, 67.5]
    for n, a in enumerate(angles):
        dx, dy = math.sin(math.radians(a)), math.cos(math.radians(a))
        z = 0.125 if n % 2 else -0.125
        tex = "w_paper_wind"
        w.strut(f"panel_in{n}", (pivot[0] + dx * 3.5, pivot[1] + dy * 3.5), (pivot[0] + dx * 7.0, pivot[1] + dy * 7.0),
                2.25, 0.25, "w_paper", z=z)
        w.strut(f"panel_out{n}", (pivot[0] + dx * 7.0, pivot[1] + dy * 7.0),
                (pivot[0] + dx * 10.5, pivot[1] + dy * 10.5), 3.75, 0.25, tex, z=z, ends=(0.0, -0.0625))
        outer = n in (0, len(angles) - 1)
        w.strut(f"rib{n}", (pivot[0] + dx * 1.0, pivot[1] + dy * 1.0), (pivot[0] + dx * 10.5, pivot[1] + dy * 10.5),
                0.75 if outer else 0.375, 1.0 if outer else 0.75, "w_lacquer")
        w.strut(f"rib_tip{n}", (pivot[0] + dx * 10.5, pivot[1] + dy * 10.5),
                (pivot[0] + dx * 11.75, pivot[1] + dy * 11.75), 0.5, 0.5, "w_steel_edge")
    w.box("rivet", (-0.75, 4.25, -0.75), (0.75, 5.75, 0.75), "w_brass")
    return w.finish()


def earth():
    w = Weapon("weapon/earth")
    w.group("handle")
    w.centred("pommel", -4.5, -3.0, 2.75, 2.75, "w_granite")
    w.grip(-3.0, 5.0, 2.25, 2.25, tex="w_wrap")
    w.centred("haft", 5.0, 9.0, 2.0, 2.0, "w_wood_dark")
    # the slab: a rough stone blade hafted with rope lashings, chipped along both edges, moss in the cracks
    w.group("slab")
    w.box("slab_root", (-2.25, 7.0, -1.25), (2.75, 10.0, 1.25), "w_stone")
    w.box("lash_a", (-2.5, 7.5, -1.375), (3.0, 8.25, 1.375), "w_hemp")
    w.box("lash_b", (-2.5, 8.75, -1.375), (3.0, 9.375, 1.375), "w_hemp")
    rows = [(10.0, 14.0, -2.75, 3.25), (14.0, 17.5, -2.5, 3.75), (17.5, 21.0, -2.75, 3.5),
            (21.0, 23.5, -2.25, 3.0), (23.5, 25.25, -1.5, 2.25), (25.25, 26.5, -0.75, 1.25)]
    for n, (y0, y1, x0, x1) in enumerate(rows):
        w.box(f"slab{n}", (x0, y0, -1.125 + 0.0625 * n), (x1, y1, 1.125 - 0.0625 * n), "w_stone")
    # a lighter chipped face along the business edge
    w.box("chip_a", (3.25, 11.0, -0.75), (3.75, 13.5, 0.75), "w_granite")
    w.box("chip_b", (3.75, 14.5, -0.75), (4.25, 17.0, 0.75), "w_granite")
    w.both_flats("crack", (0.0, 15.0, 1.0), (0.5, 20.0, 1.125), "w_granite")
    w.both_flats("moss_a", (-2.5, 10.0, 1.125), (-0.5, 11.5, 1.375), "w_moss")
    w.box("moss_b", (1.25, 17.5, 0.9375), (3.0, 18.25, 1.25), "w_moss")
    w.box("moss_top", (-1.5, 25.25, -0.5), (0.25, 25.5, 0.75), "w_moss")
    w.size = 1.45  # huge: a rock-crowned earth-splitter
    w.size_first = 1.1  # smaller on the own screen
    return w.finish()


WEAPONS_B1 = {
    "ninja": ninja,
    "blade": blade,
    "duelist": duelist,
    "archer": archer,
    "sniper": sniper,
    "hunter": hunter,
    "lancer": lancer,
    "berserker": berserker,
    "guardian": guardian,
    "fighter": fighter,
    "pyro": pyro,
    "frost": frost,
    "thunder": thunder,
    "aero": aero,
    "earth": earth,
}
