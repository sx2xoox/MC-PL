"""
Ability weapons, batch 3 (tech, space-time, venom, the sea and the field). Same rules as weapons.py: one function per
ability, built with the shared kit (mcpl_assets/weaponkit.py), classic and readable, emissive only where the ability's
element is light (fire, lightning, a lamp, magic). weapons.py merges WEAPONS into its own registry.

  engineer   공학자     big adjustable wrench: chrome jaws and a brass worm screw, red dipped grip
  hacker     해커      glitched short sword: blued blade cut into slices knocked sideways, terminal-green code in the
                       fuller, a cable with a plug hanging from the pommel
  mech       메카      hydraulic war hammer: riveted armour-plate head with a steel striking face, a brass piston along
                       the haft, a missile pod (three red-nosed rockets) on top
  bomber     폭탄마     stick grenade: olive drab head with a white stencil band, wooden handle, pull ring below, a lit
                       fuse on top (the only glow: fire)
  gravity    중력술사    gravity scepter: a dark violet orb in an iron cradle with a glowing violet orbit ring and a moon
  magnet     자석술사    horseshoe magnet on a handle: red enamel with steel poles, a nail stuck to one pole
  chrono     시간술사    clock-hand sword: a blued steel hand with a gold lozenge, a brass pocket-watch dial as the guard,
                       an hourglass pommel
  blink      순간이동자   phase dagger: a double-edged blade cut in three by glowing violet rifts, violet gems in guard and
                       pommel (magic)
  speedster  스피드스터   winged sabre: a zigzag lightning-bolt steel blade, white wings swept back from the guard
  venom      독술사     wavy poison kris: a poison-green fuller and coated tip, a poison vial as the pommel
  spider     거미      spider-leg blade: jointed black chitin ending in a claw, silk-bound grip, red hourglass guard
  pirate     해적      cutlass: broad curved blade, brass shell guard and knuckle bow, leather grip
  angler     낚시꾼     gaff rod: cork grip, fly reel, a steel gaff hook on top and a red-white bobber on the line
  farmer     농부      sickle (nat): a steel crescent honed on its inner curve, pale wooden handle bound in straw
                       rope, a bundle of wheat tied on
  batter     야구선수    baseball bat: pale ash, taped handle, round knob, a burned-in label
"""

import math

from mcpl_assets import weapon_textures, weapon_textures_b2, weapon_textures_b3  # noqa: F401  (w_* textures)
from mcpl_assets.weaponkit import Weapon


def engineer():
    w = Weapon("weapon/engineer")
    w.group("handle")
    w.centred("end", -4.0, -2.5, 2.75, 1.25, "w_steel")
    w.centred("end_hole_rim", -3.5, -3.0, 1.0, 1.5, "w_iron_dark")
    w.grip(-2.5, 6.0, 3.0, 2.0, tex="w_paint_red")
    w.centred("bar", 6.0, 14.0, 2.5, 1.25, "w_steel")
    w.both_flats("bar_rib", (-0.5, 6.5, 0.625), (0.5, 13.5, 0.75), "w_iron")
    # the head: a fixed jaw on the left, a sliding jaw on the right, the knurled brass worm under it
    w.group("head")
    w.box("neck", (-2.5, 14.0, -1.0), (2.0, 16.0, 1.0), "w_steel")
    w.box("body", (-4.5, 16.0, -1.125), (3.0, 20.5, 1.125), "w_steel")
    w.box("jaw_fixed", (-4.5, 20.5, -1.125), (-1.25, 25.0, 1.125), "w_steel")
    w.box("jaw_fixed_lip", (-1.25, 23.5, -1.0), (0.0, 25.0, 1.0), "w_steel_edge")
    w.box("jaw_slide", (1.25, 20.5, -1.0), (4.5, 23.75, 1.0), "w_iron")
    w.box("jaw_slide_lip", (0.25, 22.5, -0.875), (1.25, 23.75, 0.875), "w_steel_edge")
    w.box("slide_rail", (3.0, 17.0, -0.875), (4.5, 20.5, 0.875), "w_iron")
    w.box("worm", (0.0, 17.25, -1.5), (2.75, 19.25, 1.5), "w_brass")
    for n, x in enumerate((0.5, 1.25, 2.0)):
        w.both_flats(f"knurl{n}", (x, 17.25, 1.5), (x + 0.25, 19.25, 1.625), "w_iron_dark")
    w.rivets("pin", [(-3.0, 17.5)], 1.125, tex="w_iron_dark")
    return w.finish()


def hacker():
    w = Weapon("weapon/hacker")
    w.group("handle")
    w.box("plug", (-1.0, -5.5, -0.75), (1.0, -3.0, 0.75), "w_iron_dark")
    w.box("plug_tip", (-0.625, -6.5, -0.375), (0.625, -5.5, 0.375), "w_steel")
    w.grip(-3.0, 6.0, 1.75, 1.75)
    w.collar("strain", 6.0, 2.25, 2.25, 1.0, "w_iron_dark")
    w.centred("cable", 7.0, 8.5, 1.0, 1.0, "w_grip")
    # the keyboard: a long dark board with pale keycaps on the front, one green enter key
    w.group("board")
    w.box("board", (-3.5, 8.5, -1.0), (3.5, 25.5, 1.0), "w_lacquer")
    w.box("bezel", (-3.75, 8.0, -0.75), (3.75, 8.5, 0.75), "w_iron_dark")
    cols = (-3.0, -1.375, 0.25, 1.875)
    for r in range(8):
        y = 9.25 + r * 2.0
        for c, x in enumerate(cols):
            if c == 0 and 1 <= r <= 4:
                continue        # the space bar runs down the first column
            tex = "w_venom" if (r, c) == (6, 3) else "w_ivory"
            w.both_flats(f"key{r}_{c}", (x, y, 1.0), (x + 1.25, y + 1.5, 1.375), tex)
    w.both_flats("space", (-3.0, 11.25, 1.0), (-1.75, 18.75, 1.375), "w_ivory")
    return w.finish()


def mech():
    w = Weapon("weapon/mech")
    w.group("handle")
    w.pommel(-2.0, w=3.0, d=3.0, h=1.5, tex="w_plate", cap="w_iron")
    w.grip(-2.0, 6.0, 2.5, 2.5)
    w.collar("trigger_ring", 6.0, 3.0, 3.0, 0.75, "w_iron")
    # the motor housing: riveted plates, cooling slots, a piston and a small missile pod on the side
    w.group("housing")
    w.box("housing", (-3.0, 6.75, -2.5), (3.0, 13.5, 2.5), "w_plate")
    w.vents("slot", -2.0, 2.0, 8.0, 3, 2.5, tex="w_iron_dark", pitch=1.5)
    w.rivets("rivet", [(-2.375, 7.5), (2.375, 7.5), (-2.375, 12.75), (2.375, 12.75)], 2.5, tex="w_iron")
    w.box("cyl", (3.0, 7.5, -0.875), (4.5, 11.0, 0.875), "w_brass")
    w.box("rod", (3.375, 11.0, -0.375), (4.125, 14.0, 0.375), "w_steel")
    w.box("pod", (-5.25, 7.5, -1.5), (-3.0, 11.75, 1.5), "w_olive")
    for n, z in enumerate((-0.75, 0.75)):
        w.centred(f"rocket{n}", 11.75, 12.75, 1.0, 1.0, "w_steel", x=-4.125, z=z)
        w.centred(f"nose{n}", 12.75, 13.5, 0.5, 0.5, "w_paint_red", x=-4.125, z=z)
    # the chuck and a stepped drill bit with dark spiral flutes
    w.group("drill")
    w.centred("chuck", 13.5, 15.25, 4.25, 4.25, "w_iron")
    w.centred("chuck_ring", 14.0, 14.75, 4.5, 4.5, "w_iron_dark")
    steps = ((15.25, 17.75, 4.0), (17.75, 20.0, 3.25), (20.0, 22.25, 2.5), (22.25, 24.25, 1.75), (24.25, 26.0, 1.0))
    for n, (y0, y1, s) in enumerate(steps):
        w.centred(f"bit{n}", y0, y1, s, s, "w_steel")
        x = (-0.25 if n % 2 else 0.25) * s
        w.both_flats(f"flute{n}", (x - s * 0.2, y0 + 0.25, s / 2), (x + s * 0.2, y1 - 0.25, s / 2 + 0.125),
                     "w_iron_dark")
    w.centred("point", 26.0, 27.25, 0.5, 0.5, "w_steel_edge")
    return w.finish()


def bomber():
    w = Weapon("weapon/bomber")
    w.group("pull")
    w.ring("pull_ring", 0, -7.0, 2.25, 0.375, 0.25, "w_steel")
    w.box("cord", (-0.25, -5.75, -0.25), (0.25, -4.5, 0.25), "w_ivory")
    w.group("handle")
    w.centred("cap", -4.5, -3.0, 2.5, 2.5, "w_iron")
    w.grip(-3.0, 15.0, 2.0, 2.0, tex="w_wood")
    w.collar("handle_ring", 14.0, 2.5, 2.5, 1.0, "w_iron")
    # the head: an olive-drab can with a white stencil band
    w.group("head")
    w.centred("head", 15.0, 23.0, 4.5, 4.5, "w_olive")
    w.collar("rim_low", 15.25, 5.0, 5.0, 0.75, "w_iron_dark")
    w.collar("band", 18.5, 4.75, 4.75, 1.0, "w_ivory")
    w.collar("rim_high", 22.0, 5.0, 5.0, 0.75, "w_iron_dark")
    w.centred("lid", 23.0, 23.75, 3.0, 3.0, "w_iron")
    # a lit fuse (fire: the only glowing part)
    w.group("fuse")
    w.centred("fuse_a", 23.75, 25.25, 0.5, 0.5, "w_leather")
    w.box("fuse_b", (0.25, 24.75, -0.25), (1.25, 25.25, 0.25), "w_leather")
    w.box("fuse_c", (0.75, 25.25, -0.25), (1.25, 26.0, 0.25), "w_leather")
    w.edge_strip("spark", (0.375, 26.0, -0.625), (1.625, 27.25, 0.625), "w_heat")
    return w.finish()


def gravity():
    w = Weapon("weapon/gravity")
    w.group("handle")
    w.pommel(-2.0, w=2.75, d=2.75, h=1.25, tex="w_iron_dark", cap="w_void")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap")
    w.centred("cap", 5.5, 7.0, 2.75, 2.75, "w_iron_dark")
    # a short chain of alternating links
    w.group("chain")
    w.ring("link0", 0, 8.25, 2.0, 0.5, 0.25, "w_iron")
    w.box("link1_l", (-0.25, 9.0, -1.0), (0.25, 11.5, -0.5), "w_iron")
    w.box("link1_r", (-0.25, 9.0, 0.5), (0.25, 11.5, 1.0), "w_iron")
    w.box("link1_b", (-0.25, 9.0, -0.5), (0.25, 9.5, 0.5), "w_iron")
    w.box("link1_t", (-0.25, 11.0, -0.5), (0.25, 11.5, 0.5), "w_iron")
    w.ring("link2", 0, 12.25, 2.0, 0.5, 0.25, "w_iron")
    w.centred("socket", 13.25, 14.5, 2.0, 2.0, "w_iron_dark")
    # the dark orb (rows of square slices) inside a glowing orbit ring with a small moon
    w.group("orb")
    for n, (y0, y1, s) in enumerate(((14.5, 15.25, 2.5), (15.25, 16.25, 4.0), (16.25, 20.0, 5.0),
                                     (20.0, 21.0, 4.0), (21.0, 21.75, 2.5))):
        w.centred(f"orb{n}", y0, y1, s, s, "w_void")
    w.group("ring")
    w.octagon("orbit", 0, 18.125, 6.25, 0.75, 0.375, "w_rift", glow=True)
    w.centred("moon", 22.75, 23.75, 1.0, 1.0, "w_ivory", x=-4.25)
    return w.finish()


def magnet():
    w = Weapon("weapon/magnet")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_iron")
    w.grip(-2.0, 6.0, 2.25, 2.25, tex="w_wrap")
    w.collar("ferrule", 6.0, 2.5, 2.5, 0.75, "w_iron")
    w.centred("neck", 6.75, 10.5, 1.75, 1.75, "w_iron")
    # the horseshoe: red enamel U, bare steel poles
    w.group("magnet")
    w.box("bow_low", (-3.25, 10.5, -1.5), (3.25, 11.75, 1.5), "w_paint_red")
    w.box("bow", (-4.75, 11.75, -1.5), (4.75, 14.5, 1.5), "w_paint_red")
    for side, tag in ((1, "r"), (-1, "l")):
        lo, hi = sorted((side * 1.75, side * 4.75))
        w.box(f"arm_{tag}", (lo, 14.5, -1.5), (hi, 21.0, 1.5), "w_paint_red")
        w.box(f"pole_{tag}", (lo, 21.0, -1.375), (hi, 24.0, 1.375), "w_steel")
        w.box(f"pole_face_{tag}", (lo + 0.25, 24.0, -1.125), (hi - 0.25, 24.25, 1.125), "w_steel_edge")
    # a nail it picked up
    w.group("nail")
    w.box("nail", (2.75, 24.25, -0.25), (6.75, 24.75, 0.25), "w_iron", rotation=("z", 22.5, (2.75, 24.25, 0)))
    w.box("nail_head", (2.5, 23.75, -0.625), (3.0, 25.25, 0.625), "w_iron",
          rotation=("z", 22.5, (2.75, 24.25, 0)))
    return w.finish()


def chrono():
    w = Weapon("weapon/chrono")
    w.group("hourglass")
    w.centred("glass_cap_low", -5.5, -5.0, 2.75, 2.75, "w_brass")
    w.centred("sand_low", -5.0, -3.75, 2.0, 2.0, "w_cork")
    w.centred("neck", -3.75, -3.25, 0.75, 0.75, "w_cork")
    w.centred("glass_high", -3.25, -2.0, 2.0, 2.0, "w_glass")
    w.centred("glass_cap_high", -2.0, -1.5, 2.75, 2.75, "w_brass")
    for n, (x, z) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        w.centred(f"post{n}", -5.0, -2.0, 0.25, 0.25, "w_brass", x=x * 1.25, z=z * 1.25)
    w.group("handle")
    w.grip(-1.5, 5.25, 2.0, 2.0, tex="w_leather")
    w.collar("ferrule", 5.25, 2.5, 2.5, 0.75, "w_brass")
    # a pendulum rod with a regulating nut
    w.group("rod")
    w.centred("rod", 6.0, 15.5, 1.25, 1.0, "w_brass")
    w.centred("nut", 11.0, 12.25, 2.0, 1.5, "w_iron_dark")
    # the bob: a heavy brass clock with a dial on both flats
    w.group("dial")
    cy = 20.75
    w.disc("case", 0, cy, 5.25, -1.25, 1.25, "w_brass")
    w.disc("face_a", 0, cy, 4.25, 1.25, 1.375, "w_ivory", row=0.5)
    w.disc("face_b", 0, cy, 4.25, -1.375, -1.25, "w_ivory", row=0.5)
    for n, (x, y) in enumerate(((0, cy + 3.25), (3.25, cy), (0, cy - 3.25), (-3.25, cy))):
        w.both_flats(f"mark{n}", (x - 0.375, y - 0.375, 1.375), (x + 0.375, y + 0.375, 1.5), "w_iron_dark")
    w.both_flats("hand_min", (-0.25, cy - 0.25, 1.375), (0.25, cy + 2.75, 1.5), "w_iron_dark")
    w.both_flats("hand_hour", (0.25, cy - 0.25, 1.375), (2.0, cy + 0.25, 1.5), "w_iron_dark")
    w.both_flats("hub", (-0.375, cy - 0.375, 1.5), (0.375, cy + 0.375, 1.625), "w_gold")
    w.centred("finial", cy + 5.25, cy + 6.25, 1.5, 1.5, "w_brass")
    w.ring("bow", 0, cy + 7.0, 2.0, 0.5, 0.25, "w_brass")
    return w.finish()


def blink():
    w = Weapon("weapon/blink")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.25, tex="w_iron_dark", cap="w_rift", cap_glow=True)
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap")
    w.guard(5.5, 3.5, h=1.25, d=2.0, tex="w_iron_dark")
    w.both_flats("gem", (-0.625, 5.625, 1.0), (0.625, 6.625, 1.25), "w_rift", glow=True)
    # the blade, cut in three by glowing rifts (the pieces it blinked)
    w.group("blade")
    pieces = ((6.75, 12.0, 1.75, 0.0), (13.25, 18.25, 1.5, 0.75), (19.5, 23.0, 1.25, -0.5))
    for n, (y0, y1, half, dx) in enumerate(pieces):
        w.box(f"core{n}", (dx - half + 0.75, y0, -0.5), (dx + half - 0.75, y1, 0.5), "w_steel")
        w.box(f"edge{n}_l", (dx - half, y0, -0.25), (dx - half + 0.75, y1, 0.25), "w_steel_edge")
        w.box(f"edge{n}_r", (dx + half - 0.75, y0, -0.25), (dx + half, y1, 0.25), "w_steel_edge")
    for n, (y, dx) in enumerate(((12.0, 0.375), (18.25, 0.125))):
        w.box(f"rift{n}", (dx - 2.25, y + 0.375, -0.125), (dx + 2.25, y + 0.875, 0.125), "w_rift", glow=True)
    w.box("tip_a", (-1.25, 23.0, -0.375), (0.25, 24.75, 0.375), "w_steel")
    w.box("tip_b", (-0.875, 24.75, -0.25), (-0.125, 26.25, 0.25), "w_steel_edge")
    return w.finish()


def speedster():
    w = Weapon("weapon/speedster")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.25, tex="w_brass")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap")
    w.centred("guard", 5.5, 7.0, 3.5, 2.25, "w_brass")
    # white wings swept up and back from the guard
    w.group("wings")
    feathers = ((1.75, 4.5, 5.75, 6.75), (2.5, 5.75, 6.75, 7.75), (3.5, 6.5, 7.75, 9.0))
    for side, tag in ((1, "r"), (-1, "l")):
        for n, (x0, x1, y0, y1) in enumerate(feathers):
            lo, hi = sorted((side * x0, side * x1))
            w.box(f"wing_{tag}{n}", (lo, y0, -0.5), (hi, y1, 0.5), "w_ivory")
    # the blade: a zigzag lightning bolt in steel
    w.group("blade")
    bolt = ((7.0, 12.0, -1.0, 1.25), (12.0, 13.75, -1.0, 3.25), (13.75, 18.0, 1.0, 3.25), (18.0, 19.75, -1.75, 3.25),
            (19.75, 24.0, -1.75, 0.5), (24.0, 25.5, -1.25, 0.0))
    for n, (y0, y1, x0, x1) in enumerate(bolt):
        w.box(f"bolt{n}", (x0, y0, -0.5), (x1, y1, 0.5), "w_gold")
    w.box("bolt_tip", (-0.75, 25.5, -0.375), (-0.25, 27.0, 0.375), "w_steel_edge")
    w.both_flats("edge0", (0.75, 7.0, 0.5), (1.25, 12.0, 0.625), "w_steel_edge")
    w.both_flats("edge1", (2.75, 13.75, 0.5), (3.25, 18.0, 0.625), "w_steel_edge")
    w.both_flats("edge2", (0.0, 19.75, 0.5), (0.5, 24.0, 0.625), "w_steel_edge")
    return w.finish()


def venom():
    w = Weapon("weapon/venom")
    w.group("staff")
    w.centred("ferrule", -4.0, -3.0, 1.75, 1.75, "w_iron_dark")
    w.grip(-3.0, 5.0, 2.0, 2.0, tex="w_wrap")
    w.centred("staff", 5.0, 21.5, 1.5, 1.5, "w_wood_dark")
    # a green snake coiling up the staff (front crossings rise one way, back crossings the other)
    w.group("snake")
    for n in range(5):
        y = 6.5 + n * 2.75
        w.box(f"front{n}", (-2.0, y, 0.75), (2.0, y + 1.0, 1.5), "w_snake", rotation=("z", 22.5, (0, y + 0.5, 1.0)))
        w.box(f"back{n}", (-2.0, y + 1.375, -1.5), (2.0, y + 2.375, -0.75), "w_snake",
              rotation=("z", -22.5, (0, y + 1.875, -1.0)))
        w.box(f"side_r{n}", (1.25, y + 0.25, -1.125), (2.0, y + 2.0, 1.125), "w_snake")
        if n < 4:
            w.box(f"side_l{n}", (-2.0, y + 1.5, -1.125), (-1.25, y + 3.0, 1.125), "w_snake")
    # its head rearing over the top of the staff, fangs bared
    w.group("head")
    w.box("neck", (-1.0, 20.25, -1.0), (1.0, 23.0, 1.0), "w_snake")
    w.box("head", (-1.25, 23.0, -1.25), (3.25, 24.75, 1.25), "w_snake")
    w.box("snout", (3.25, 23.25, -0.875), (4.75, 24.5, 0.875), "w_snake")
    w.box("jaw", (-0.75, 21.5, -0.875), (4.25, 22.25, 0.875), "w_snake")
    w.box("fang_a", (3.5, 22.25, 0.25), (3.875, 23.25, 0.625), "w_ivory")
    w.box("fang_b", (3.5, 22.25, -0.625), (3.875, 23.25, -0.25), "w_ivory")
    w.both_flats("eye", (1.75, 23.75, 1.25), (2.5, 24.375, 1.375), "w_paint_red")
    # a poison vial tied under the head
    w.group("vial")
    w.box("cord", (-1.875, 18.5, -0.125), (-1.625, 20.5, 0.125), "w_leather")
    w.box("vial_neck", (-2.25, 17.5, -0.375), (-1.25, 18.5, 0.375), "w_cork")
    w.box("vial", (-2.75, 14.75, -0.75), (-0.75, 17.5, 0.75), "w_venom")
    return w.finish()


def spider():
    w = Weapon("weapon/spider")
    w.group("handle")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.5, tex="w_chitin")
    w.grip(-2.0, 5.0, 2.0, 2.0, tex="w_silk")
    # the guard: a widow's body (red hourglass on both flats) with two legs a side, bent down at the knee
    w.group("body")
    w.disc("body", 0, 7.5, 2.75, -1.25, 1.25, "w_chitin", row=0.75)
    for n, (y0, y1, wd) in enumerate(((6.25, 7.0, 1.5), (7.0, 7.75, 0.5), (7.75, 8.5, 1.5))):
        w.both_flats(f"hourglass{n}", (-wd / 2, y0, 1.25), (wd / 2, y1, 1.375), "w_paint_red")
    for side, tag in ((1, "r"), (-1, "l")):
        for k, (y, rise) in enumerate(((8.25, 45), (6.75, 22.5))):
            x0 = side * 2.25
            lo, hi = sorted((x0, x0 + side * 3.0))
            w.box(f"thigh_{tag}{k}", (lo, y - 0.375, -0.375), (hi, y + 0.375, 0.375), "w_chitin",
                  rotation=("z", rise * side, (x0, y, 0)))
            r = math.radians(rise)
            kx, ky = x0 + side * 3.0 * math.cos(r), y + 3.0 * math.sin(r)
            w.box(f"shin_{tag}{k}", (kx - 0.3125, ky - 3.0, -0.3125), (kx + 0.3125, ky, 0.3125), "w_chitin",
                  rotation=("z", 22.5 * side, (kx, ky, 0)))
    # the blade: a jointed, bristly spider leg ending in a steel claw
    w.group("leg")
    w.centred("femur", 10.0, 16.5, 2.0, 1.75, "w_chitin")
    for n, y in enumerate((11.0, 13.0, 15.0)):
        w.box(f"hair_l{n}", (-1.75, y, -0.25), (-1.0, y + 0.5, 0.25), "w_hair")
        w.box(f"hair_r{n}", (1.0, y + 1.0, -0.25), (1.75, y + 1.5, 0.25), "w_hair")
    w.centred("knee", 16.5, 18.0, 2.5, 2.25, "w_chitin")
    w.centred("knee_band", 17.0, 17.5, 2.75, 2.5, "w_paint_red")
    angle = math.radians(22.5)
    w.box("tibia", (-0.875, 17.75, -0.75), (0.875, 24.0, 0.75), "w_chitin", rotation=("z", -22.5, (0, 17.75, 0)))
    ex, ey = 6.25 * math.sin(angle), 17.75 + 6.25 * math.cos(angle)
    w.box("claw_root", (ex - 0.75, ey - 0.75, -0.625), (ex + 0.75, ey + 0.5, 0.625), "w_chitin",
          rotation=("z", -22.5, (ex, ey, 0)))
    w.box("claw", (ex - 0.375, ey, -0.375), (ex + 0.375, ey + 3.25, 0.375), "w_steel_edge",
          rotation=("z", 22.5, (ex, ey, 0)))
    return w.finish()


def pirate():
    w = Weapon("weapon/pirate")
    w.group("handle")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.25, tex="w_brass")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap")
    # the knuckle bow and the shell guard (brass)
    w.group("guard")
    w.box("bow_low", (0.75, -2.75, -0.375), (4.0, -2.0, 0.375), "w_brass")
    w.box("bow", (3.25, -2.0, -0.375), (4.0, 5.5, 0.375), "w_brass")
    w.box("shell", (-2.75, 5.5, -1.5), (4.5, 6.75, 1.5), "w_brass")
    w.box("shell_lip", (-3.25, 5.75, -1.0), (-2.75, 6.5, 1.0), "w_brass")
    w.rivets("shell_boss", [(0.5, 6.125)], 1.5, tex="w_gold", s=0.75)
    # the cutlass: broad, curving back towards the spine near the tip
    w.group("blade")
    w.box("ricasso", (-1.25, 6.75, -0.5), (1.5, 8.0, 0.5), "w_steel")
    w.blade("blade", 8.0, 17.0, -1.25, 2.5, 0.75, edge_tex="w_steel_edge", edge_w=0.625)
    w.box("spine", (-1.5, 8.0, -0.5), (-1.25, 17.0, 0.5), "w_iron")
    bend = ("z", 22.5, (2.75, 16.5, 0))
    w.box("upper", (-1.25, 16.5, -0.375), (2.125, 23.5, 0.375), "w_steel", rotation=bend)
    w.box("upper_edge", (2.125, 16.5, -0.25), (2.75, 23.5, 0.25), "w_steel_edge", rotation=bend)
    w.box("upper_spine", (-1.5, 16.5, -0.5), (-1.25, 22.5, 0.5), "w_iron", rotation=bend)
    w.box("clip", (-1.0, 23.5, -0.25), (2.75, 24.5, 0.25), "w_steel_edge", rotation=bend)
    w.box("point", (0.5, 24.5, -0.25), (2.75, 25.75, 0.25), "w_steel_edge", rotation=bend)
    return w.finish()


def angler():
    w = Weapon("weapon/angler")
    w.group("handle")
    w.centred("butt", -4.0, -3.0, 2.5, 2.5, "w_iron_dark")
    w.grip(-3.0, 5.0, 2.25, 2.25, tex="w_cork")
    w.collar("seat", 5.0, 2.5, 2.5, 1.0, "w_brass")
    # the fly reel under the rod
    w.group("reel")
    w.box("reel_foot", (-1.75, 5.5, -0.5), (-1.125, 8.5, 0.5), "w_brass")
    w.disc("reel", -4.0, 7.0, 2.25, -0.75, 0.75, "w_iron_dark", row=0.75)
    w.disc("spool_a", -4.0, 7.0, 1.25, 0.75, 1.0, "w_brass", row=0.75)
    w.disc("spool_b", -4.0, 7.0, 1.25, -1.0, -0.75, "w_brass", row=0.75)
    w.box("reel_knob", (-5.25, 5.25, 1.0), (-4.5, 6.0, 1.75), "w_cork")
    # the rod, its guides and the line
    w.group("rod")
    w.centred("rod", 6.0, 20.0, 1.5, 1.5, "w_wood")
    w.collar("wrap1", 12.0, 1.75, 1.75, 0.5, "w_wrap")
    w.collar("wrap2", 17.5, 1.75, 1.75, 0.5, "w_wrap")
    w.box("guide1", (-1.75, 12.0, -0.25), (-0.75, 12.5, 0.25), "w_steel")
    w.box("guide2", (-1.75, 17.5, -0.25), (-0.75, 18.0, 0.25), "w_steel")
    w.box("line", (-1.75, 8.5, -0.125), (-1.5, 22.0, 0.125), "w_ivory")
    w.collar("tip_ferrule", 20.0, 2.0, 2.0, 1.5, "w_iron")
    # the gaff hook on top
    w.group("hook")
    w.box("stem", (-0.5, 21.5, -0.5), (0.5, 25.5, 0.5), "w_steel")
    w.box("bend", (-0.5, 25.5, -0.5), (4.0, 26.75, 0.5), "w_steel")
    w.box("drop", (3.0, 21.5, -0.5), (4.25, 25.5, 0.5), "w_steel")
    w.box("barb", (2.0, 21.0, -0.375), (3.0, 22.5, 0.375), "w_steel_edge")
    w.box("point", (2.75, 20.25, -0.25), (3.75, 21.5, 0.25), "w_steel_edge")
    # the bobber, hanging off the line from the top guide
    w.group("bobber")
    w.box("drop_line", (-2.875, 18.75, -0.125), (-2.625, 22.0, 0.125), "w_ivory")
    w.box("line_top", (-2.875, 22.0, -0.125), (-1.5, 22.25, 0.125), "w_ivory")
    for n, (y0, y1, wd, tex) in enumerate(((15.0, 15.5, 1.0, "w_ivory"), (15.5, 16.75, 2.25, "w_ivory"),
                                          (16.75, 18.0, 2.25, "w_paint_red"), (18.0, 18.75, 1.0, "w_paint_red"))):
        w.box(f"bob{n}", (-2.75 - wd / 2, y0, -wd / 2), (-2.75 + wd / 2, y1, wd / 2), tex)
    return w.finish()


def farmer():
    w = Weapon("weapon/farmer")
    w.group("handle")
    w.centred("butt", -3.75, -2.75, 2.5, 2.5, "w_wood")
    w.grip(-2.75, 6.0, 2.25, 2.25, tex="w_rope")
    w.centred("handle", 6.0, 9.0, 2.0, 2.0, "w_wood")
    w.collar("ferrule", 9.0, 2.5, 2.5, 1.5, "w_iron")
    # a bundle of wheat tied on with straw rope
    w.group("wheat")
    for n, (x, ang) in enumerate(((0.5, 22.5), (1.0, 0), (1.5, -22.5))):
        w.box(f"stalk{n}", (x - 0.125, 4.0, 1.125), (x + 0.125, 9.5, 1.375), "w_cork",
              rotation=("z", ang, (x, 4.5, 1.25)))
        w.box(f"ear{n}", (x - 0.375, 9.5, 1.0), (x + 0.375, 11.75, 1.5), "w_gold",
              rotation=("z", ang, (x, 4.5, 1.25)))
    w.collar("tie", 5.0, 2.75, 2.75, 0.75, "w_rope")
    # the sickle (nat): a crescent blade rising from the ferrule and hooking back, honed on its inner curve
    w.group("blade")
    w.centred("tang", 10.5, 11.75, 1.5, 1.0, "w_iron")
    path = [(90, 2.0), (67.5, 3.0), (90, 2.5), (112.5, 2.75), (135, 2.75), (157.5, 2.5), (180, 2.25), (202.5, 2.0),
            (225, 1.75)]
    widths = [2.0, 2.25, 2.25, 2.25, 2.0, 1.75, 1.5, 1.125, 0.75]
    points = w.curve("blade", (0.0, 11.75), path, widths, 0.75, "w_steel", overlap=0.375)
    w.curve("edge", points[2], path[2:7], 0.5, 0.5, "w_steel_edge", offset=1.25, overlap=0.375)
    w.curve("spine", points[3], path[3:6], 0.5, 1.0, "w_iron", offset=-1.0, overlap=0.375)
    return w.finish()


def batter():
    w = Weapon("weapon/batter")
    w.group("handle")
    w.centred("knob", -4.0, -3.0, 3.0, 3.0, "w_ash")
    w.centred("knob_neck", -3.0, -2.5, 2.25, 2.25, "w_ash")
    w.grip(-2.5, 6.0, 1.75, 1.75, tex="w_tape")
    # the bat: pale ash swelling into the barrel, a burned-in label
    w.group("bat")
    for n, (y0, y1, s) in enumerate(((6.0, 9.5, 2.0), (9.5, 12.5, 2.5), (12.5, 15.0, 3.0), (15.0, 25.0, 3.5))):
        w.centred(f"bat{n}", y0, y1, s, s, "w_ash")
    w.centred("end", 25.0, 25.75, 3.0, 3.0, "w_ash")
    w.both_flats("label", (-0.75, 18.0, 1.75), (0.75, 21.5, 1.875), "w_wood_dark")
    w.both_flats("label_dot", (-0.25, 19.25, 1.875), (0.25, 20.25, 2.0), "w_ivory")
    return w.finish()


# ability id -> builder (weapons.py merges these into its WEAPONS)
WEAPONS = {
    "engineer": engineer,
    "hacker": hacker,
    "mech": mech,
    "bomber": bomber,
    "gravity": gravity,
    "magnet": magnet,
    "chrono": chrono,
    "blink": blink,
    "speedster": speedster,
    "venom": venom,
    "spider": spider,
    "pirate": pirate,
    "angler": angler,
    "farmer": farmer,
    "batter": batter,
}
