"""
Ability weapons, batch 4 (myth, beasts, nature, martial arts and sport). Same rules as weapons.py: one function per
ability, built with the shared kit (mcpl_assets/weaponkit.py), classic and readable, emissive only where the ability's
element is light or magic. weapons.py merges WEAPONS into its own registry.

Size: unlike the earlier batches, every weapon here sets its in-hand size (`w.size`, the hand poses only; 1 = the
reference ~1.1x vanilla sword) from what it is: the giant weapons (a titan's maul, a labrys, 여의봉, Gungnir, the hydra
spear) are long AND shown big (1.45-1.8), so they dominate the hand in first and third person; staves and fans sit a
little above the reference; fists, wands and the racket stay about vanilla size. GUI / ground / item frame views are
fitted to the model's bounds by the shared preset, so every icon still fills its slot.

  titan        거인       giant maul: a granite block bound in bronze, bronze striking plates, a long leather-bound haft
  minotaur     미노타우로스  labrys: a bronze double-bit axe with flaring crescent blades, bull horns on top, a red cape rag
  wukong       제천대성    여의봉: a very long red staff with gold caps and gold rings at both ends
  odin         오딘       Gungnir: an ash spear with a long leaf blade cut with glowing runes, gold bands, raven feathers
  hydra        히드라      hydra spear: a scale-wrapped spear whose socket sprouts two snake heads beside the venom blade
  medusa       메두사      gorgon staff: a bronze staff wound by a snake that rears over a stone gorgon medallion whose
                         eye glows (the petrifying gaze)
  ra           태양신 라    winged sun sceptre: lapis staff banded in gold, gold falcon wings under a glowing sun disc
                         with a uraeus cobra
  monk         무도가      iron knuckle on a white-wrapped fist, a string of wooden prayer beads with a red tassel
  kiwarrior    기공 전사    wrapped fist with a blue wristband holding up a glowing blue ki ball (기탄)
  bear         곰         bear paw gauntlet: a shaggy brown paw with four thick curved black claws, leather pads
  octopus      문어       tentacle staff: a purple tentacle with sucker rows curling over a bronze cup, an ink-drop pommel
  gumiho       구미호      fox bead staff: red lacquer staff, a white fox head holding a glowing 여우구슬 in a gold
                         crescent, three white fox tails hanging from the pommel
  magicalgirl  마법소녀     heart wand: pink wand with a ribbon bow, a gold-framed glowing pink heart with white wings
  tengu        텐구       hauchiwa: a big fan of black feathers on a red lacquer handle with a gold boss and tassel
  druid        식물술사     gnarled druid staff: dark wood curling into a spiral crook, moss, a vine, leaves, a pink flower
  shaman       무당       무당방울: a cluster of seven brass bells on a brass stem, 오방색 cloth streamers below the grip
  turtle       거북이      tortoise shell shield: a domed plated shell over the hand, rim spikes, a little turtle head
  crystal      크리스탈술사  amethyst club: a cluster of amethyst points on a basalt haft, a glowing violet vein
  sonic        음파술사     sculk tuning fork: dark iron tines with glowing sonic veins and two floating sound rings
  tennis       테니스 천재   tennis racket: black frame with red throat, strung head, taped grip, a tennis ball on it
"""

import math

from mcpl_assets import weapon_textures, weapon_textures_b1, weapon_textures_b2, weapon_textures_b3  # noqa: F401
from mcpl_assets import weapon_textures_b4  # noqa: F401  (registers the batch 4 w_* textures)
from mcpl_assets.weaponkit import Weapon, display


# --- local helpers (the shared kit stays untouched) ----------------------------------------------

FIRST_PERSON = 0.45     # how much of a weapon's extra size (w.size - 1) also shows in first person


def sized(w):
    """w.finish() with the weapon's own size: third person (and the left hand) at w.size; first person grows only
    FIRST_PERSON of the extra, so a giant weapon's head stays on screen (the first-person camera is close and the
    sword pose already reaches the top of the view at size 1). Small weapons (size <= 1) use w.size everywhere."""
    model = w.finish()
    if w.size > 1:
        first = display(model, 1 + (w.size - 1) * FIRST_PERSON)
        for ctx in ("firstperson_righthand", "firstperson_lefthand"):
            model.display[ctx] = first[ctx]
    return model

def ball(w, name, cx, cy, r, tex, row=0.75, z=0.0, depth=None, **kw):
    """A round lump (an orb, a bead, a ball): stacked square slices as wide as the circle at their height. depth
    (optional) = the slices' depth as a fraction of their width (1 = round, less = a flattened lens)."""
    n = max(1, round(2 * r / row))
    h = 2 * r / n
    for i in range(n):
        mid = cy - r + (i + 0.5) * h - cy
        half = round(math.sqrt(max(0.0, r * r - mid * mid)) * 4) / 4
        half = max(half, 0.25)
        d = 2 * half * (depth if depth else 1.0)
        w.centred(f"{name}{i}", cy - r + i * h, cy - r + (i + 1) * h, 2 * half, d, tex, x=cx, z=z, **kw)
    return w


def rows(w, name, spans, z0, z1, tex, **kw):
    """Horizontal rows [(y0, y1, [(x0, x1), ...]), ...] from z0 to z1: a flat shape (a heart, a frame) in pixel rows."""
    k = 0
    for y0, y1, segs in spans:
        for x0, x1 in segs:
            w.box(f"{name}{k}", (x0, y0, z0), (x1, y1, z1), tex, **kw)
            k += 1
    return w


def fist(w, wrap, glove, knuckle, cuff=None):
    """A clenched fist over the grip (the business side, the fingers, on +x; the knuckles leading at the top)."""
    w.group("wrist")
    w.centred("wrist", -3.0, 5.0, 2.75, 2.75, wrap)
    if cuff:
        w.centred("cuff", 4.5, 7.0, 3.75, 3.75, cuff)
    w.group("fist")
    w.box("back", (-2.75, 7.0, -2.25), (1.5, 13.0, 2.25), glove)
    for n in range(4):
        z0 = -2.125 + n * 1.0625
        w.box(f"finger{n}", (1.5, 7.5, z0 + 0.0625), (3.25, 12.75, z0 + 1.0), glove)
    w.box("thumb", (3.25, 8.0, -2.0), (4.0, 9.75, 1.0), glove)
    w.box("thumb_root", (1.5, 7.25, 1.25), (3.5, 9.75, 2.5), glove)
    w.box("knuckles", (-2.25, 13.0, -2.375), (3.25, 13.875, 2.375), knuckle)
    return w


# --- the giant weapons ---------------------------------------------------------------------------

def titan():
    w = Weapon("weapon/titan")
    w.size = 1.7
    w.group("haft")
    w.centred("pommel", -6.5, -4.75, 3.25, 3.25, "w_bronze")
    w.centred("pommel_cap", -7.0, -6.5, 2.25, 2.25, "w_bronze")
    w.grip(-4.75, 7.0, 2.75, 2.75, tex="w_leather")
    w.collar("grip_band", 7.0, 3.25, 3.25, 1.0, "w_bronze")
    w.centred("haft", 8.0, 19.0, 2.5, 2.5, "w_wood_dark")
    w.collar("haft_band", 12.5, 2.875, 2.875, 0.75, "w_bronze")
    w.centred("socket", 17.0, 20.75, 3.5, 3.5, "w_bronze")
    w.both_flats("langet", (-0.5, 14.0, 1.25), (0.5, 17.0, 1.5), "w_bronze")
    # the head: a block of granite bound by two bronze bands, bronze plates on both striking faces
    w.group("head")
    w.box("head", (-6.0, 20.5, -3.0), (6.0, 30.5, 3.0), "w_granite")
    w.box("head_top", (-5.0, 30.5, -2.25), (5.0, 31.25, 2.25), "w_granite")
    for n, y in enumerate((21.0, 28.5)):
        w.box(f"band{n}", (-6.5, y, -3.5), (6.5, y + 1.25, 3.5), "w_bronze")
    w.box("face_r", (6.0, 22.75, -2.5), (7.0, 28.0, 2.5), "w_bronze")
    w.box("face_r_boss", (7.0, 24.25, -1.25), (7.5, 26.5, 1.25), "w_bronze")
    w.box("face_l", (-7.0, 22.75, -2.5), (-6.0, 28.0, 2.5), "w_bronze")
    w.rivets("band_rivet", [(-4.5, 21.625), (0.0, 21.625), (4.5, 21.625), (-4.5, 29.125), (0.0, 29.125),
                            (4.5, 29.125)], 3.5, tex="w_iron_dark")
    # chips knocked out of the stone
    w.both_flats("chip0", (-4.0, 25.5, 3.0), (-2.5, 26.25, 3.125), "w_stone")
    w.both_flats("chip1", (1.5, 24.0, 3.0), (3.5, 24.625, 3.125), "w_stone")
    return sized(w)


def minotaur():
    w = Weapon("weapon/minotaur")
    w.size = 1.55
    w.group("haft")
    w.centred("butt_spike", -7.5, -6.0, 1.0, 1.0, "w_bronze")
    w.centred("butt", -6.0, -4.5, 2.5, 2.5, "w_bronze")
    w.centred("haft", -4.5, 18.5, 2.0, 2.0, "w_wood_dark")
    w.grip(-2.5, 5.5, 2.375, 2.375, tex="w_leather")
    w.collar("ferrule", 5.5, 2.5, 2.5, 0.75, "w_bronze")
    # the red cape (붉은 천) knotted under the head, two tails
    w.centred("cape_knot", 15.5, 17.5, 2.75, 2.75, "w_cloth_red")
    w.box("cape_a", (-2.75, 10.0, -0.125), (-1.375, 15.75, 0.375), "w_cloth_red")
    w.box("cape_b", (1.375, 11.75, -0.375), (2.5, 15.75, 0.125), "w_cloth_red")
    # the labrys: a bronze eye, two flaring crescent bits (the business side +x is the same as -x)
    w.group("head")
    w.box("eye", (-2.0, 18.0, -1.75), (2.0, 27.5, 1.75), "w_bronze")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"

        def bx(name, x0, x1, y0, y1, d, tex):
            lo, hi = sorted((side * x0, side * x1))
            w.box(f"{name}_{tag}", (lo, y0, -d), (hi, y1, d), tex)

        bx("neck", 2.0, 4.0, 20.0, 25.5, 1.0, "w_bronze")
        bx("bit0", 4.0, 5.5, 19.0, 26.5, 0.75, "w_iron")
        bx("bit1", 5.5, 7.0, 17.5, 28.0, 0.6875, "w_iron")
        bx("bit2", 7.0, 8.5, 16.0, 29.5, 0.625, "w_iron")
        bx("bit3", 8.5, 9.5, 14.75, 30.75, 0.5625, "w_iron")
        edge = [(8.5, 14.25), (9.5, 16.5), (10.0, 19.5), (10.25, 22.75), (10.0, 26.0), (9.5, 29.0), (8.5, 31.25)]
        w.polyline(f"edge_{tag}", [(side * x, y) for x, y in edge], 0.875, 0.375 + 0.125 * (side < 0),
                   "w_steel_edge")
        bx("inlay", 4.5, 7.5, 22.0, 23.5, 0.8125, "w_bronze")
        bx("inlay_dot", 5.5, 6.5, 21.5, 24.0, 0.875, "w_gold")
    # bull horns curling up from the eye
    w.group("horns")
    w.centred("brow", 27.5, 28.5, 3.0, 2.25, "w_bronze")
    w.curve("horn_r", (1.0, 28.0), [(22.5, 1.5), (45, 1.25), (90, 1.0), (112.5, 0.75)], (1.25, 1.0, 0.75, 0.5),
            1.25, "w_bone")
    w.curve("horn_l", (-1.0, 28.0), [(157.5, 1.5), (135, 1.25), (90, 1.0), (67.5, 0.75)], (1.25, 1.0, 0.75, 0.5),
            1.25, "w_bone", phase=1)
    return sized(w)


def wukong():
    w = Weapon("weapon/wukong")
    w.size = 1.8
    w.group("staff")
    for end, (y0, y1, sign) in (("low", (-15.0, -10.5, 1)), ("high", (27.5, 32.0, -1))):
        w.centred(f"cap_{end}", y0, y1, 3.0, 3.0, "w_gold")
        lip = y1 if sign > 0 else y0 - 0.75
        w.centred(f"lip_{end}", lip, lip + 0.75, 3.25, 3.25, "w_gold")
        for n, k in enumerate((1.75, 3.0)):
            y = y1 + k if sign > 0 else y0 - k - 0.5
            w.centred(f"ring_{end}{n}", y, y + 0.5, 2.875, 2.875, "w_gold")
    w.centred("rod", -10.5, 27.5, 2.5, 2.5, "w_paint_red")
    w.grip(-2.5, 5.5, 2.75, 2.75, tex="w_wrap_blood")
    # a cloud scroll engraved in gold at the middle of the upper half
    w.both_flats("cloud", (-0.75, 14.0, 1.25), (0.75, 16.5, 1.375), "w_gold")
    w.both_flats("cloud_l", (-1.25, 14.5, 1.25), (-0.75, 15.5, 1.375), "w_gold")
    return sized(w)


def odin():
    w = Weapon("weapon/odin")
    w.size = 1.5
    w.group("shaft")
    w.centred("butt", -10.0, -8.5, 2.375, 2.375, "w_gold")
    w.centred("butt_tip", -10.75, -10.0, 1.25, 1.25, "w_gold")
    w.centred("shaft", -8.5, 18.5, 2.0, 2.0, "w_ash")
    w.grip(-2.0, 5.0, 2.25, 2.25, tex="w_leather")
    for n, y in enumerate((-6.0, 7.5, 11.5)):
        w.collar(f"band{n}", y, 2.375, 2.375, 0.75, "w_gold")
    # two raven feathers (Huginn and Muninn) tied under the head
    w.group("feathers")
    w.collar("tie", 15.0, 2.375, 2.375, 0.75, "w_leather")
    w.bar("feather_l", (-0.75, 15.25), -112.5, 6.0, 1.5, 0.25, "w_crow", z=0.375)
    w.bar("feather_r", (0.75, 15.25), -67.5, 5.0, 1.25, 0.25, "w_crow", z=-0.375)
    # the head: a gold socket, a long leaf blade with a ridge, runes glowing on both flats
    w.group("head")
    w.centred("socket", 18.0, 20.5, 2.5, 2.5, "w_gold")
    w.centred("socket_ring", 18.75, 19.375, 2.75, 2.75, "w_gold")
    for n, (y0, y1, wd) in enumerate(((20.5, 21.5, 3.0), (21.5, 23.5, 4.5), (23.5, 27.0, 5.5), (27.0, 29.5, 4.25),
                                      (29.5, 31.0, 2.5))):
        w.centred(f"leaf{n}", y0, y1, wd, 0.875, "w_steel")
    w.centred("ridge", 20.5, 31.25, 0.75, 1.25, "w_steel_edge")
    w.centred("point", 31.0, 32.0, 0.75, 0.75, "w_steel_edge")
    for side in (1, -1):
        lo, hi = sorted((side * 0.75, side * 1.625))
        w.both_flats(f"rune_{'r' if side > 0 else 'l'}", (lo, 21.75, 0.4375), (hi, 29.0, 0.5625), "w_rune",
                     glow=True)
    return sized(w)


def hydra():
    w = Weapon("weapon/hydra")
    w.size = 1.45
    w.group("shaft")
    w.centred("butt", -8.0, -6.5, 2.5, 2.5, "w_bronze")
    w.centred("shaft", -6.5, 18.5, 2.0, 2.0, "w_hydra")
    w.grip(-2.0, 5.0, 2.375, 2.375, tex="w_wrap")
    w.collar("band", 9.0, 2.375, 2.375, 0.75, "w_bronze")
    w.centred("socket", 17.5, 20.75, 2.75, 2.75, "w_bronze")
    # the middle head: a venom-grooved spear blade
    w.group("blade")
    for n, (y0, y1, wd) in enumerate(((20.75, 22.0, 2.5), (22.0, 26.0, 3.5), (26.0, 28.5, 2.5), (28.5, 30.0, 1.25))):
        w.centred(f"blade{n}", y0, y1, wd, 0.75, "w_steel")
    w.centred("point", 30.0, 31.25, 0.5, 0.5, "w_steel_edge")
    w.both_flats("venom", (-0.375, 21.25, 0.375), (0.375, 28.5, 0.5), "w_venom")
    # two snake necks bending out of the socket, their heads rearing beside the blade
    w.group("heads")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        path = [(90 - side * 90, 2.0), (90 - side * 45, 2.25), (90, 3.25), (90 + side * 22.5, 1.5)]
        pts = w.curve(f"neck_{tag}", (side * 1.25, 19.25), path, (1.375, 1.25, 1.25, 1.125), 1.25, "w_hydra",
                      phase=0 if side > 0 else 1)
        hx, hy = pts[-1]
        w.box(f"head_{tag}", (hx - 1.25, hy - 0.5, -1.0), (hx + 1.25, hy + 1.75, 1.0), "w_hydra")
        w.box(f"snout_{tag}", (hx - 0.75, hy + 1.75, -0.75), (hx + 0.75, hy + 2.75, 0.75), "w_hydra")
        w.both_flats(f"eye_{tag}", (hx - 0.75, hy + 0.75, 1.0), (hx - 0.125, hy + 1.375, 1.125), "w_gold")
        w.box(f"fang_{tag}", (hx - 0.375, hy + 2.75, -0.25), (hx + 0.375, hy + 3.25, 0.25), "w_bone")
    return sized(w)


# --- staves and sceptres -------------------------------------------------------------------------

def medusa():
    w = Weapon("weapon/medusa")
    w.size = 1.25
    w.group("staff")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_verdigris")
    w.centred("pommel_tail", -4.0, -3.0, 1.0, 1.0, "w_snake")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_leather")
    w.centred("staff", 5.5, 20.0, 1.75, 1.75, "w_verdigris")
    w.centred("cup", 19.5, 21.0, 3.0, 2.25, "w_verdigris")
    # the stone gorgon medallion in a bronze rim: a stone face whose eyes glow (the petrifying gaze)
    w.group("medallion")
    cy = 24.0
    w.disc("stone", 0.0, cy, 3.25, -0.75, 0.75, "w_stone")
    w.octagon("rim", 0.0, cy, 3.75, 0.75, 1.0, "w_verdigris")
    for side, tag in ((1, "r"), (-1, "l")):
        lo, hi = sorted((side * 0.375, side * 1.625))
        w.both_flats(f"gaze_{tag}", (lo, cy + 0.25, 0.75), (hi, cy + 1.125, 0.875), "w_gaze", glow=True)
        w.both_flats(f"brow_{tag}", (lo, cy + 1.375, 0.75), (hi, cy + 1.75, 0.8125), "w_iron_dark")
    w.both_flats("mouth", (-1.0, cy - 1.75, 0.75), (1.0, cy - 1.375, 0.8125), "w_iron_dark")
    # the snake: winding up the staff (deeper than it), rising past the medallion and rearing over it, its head
    # striking towards the business side
    w.group("snake")
    w.polyline("coil", [(-0.75, 6.0), (0.75, 9.5), (-0.75, 13.0), (0.75, 16.5), (-0.75, 20.0)], 1.0, 2.25,
               "w_snake")
    w.polyline("neck", [(-0.75, 20.0), (-4.75, 21.75), (-5.0, 26.5), (-2.75, 28.75)], 1.25, 1.5, "w_snake",
               extend=0.5)
    w.box("head", (-3.5, 28.0, -1.0), (0.25, 30.25, 1.0), "w_snake")
    w.box("snout", (0.25, 28.375, -0.75), (1.75, 29.875, 0.75), "w_snake")
    w.both_flats("head_eye", (-1.0, 29.25, 1.0), (-0.375, 29.875, 1.125), "w_gaze", glow=True)
    w.box("fang", (1.0, 27.75, -0.25), (1.5, 28.375, 0.25), "w_bone")
    w.box("tongue", (1.75, 28.75, -0.125), (2.75, 29.125, 0.125), "w_blood")
    return sized(w)


def ra():
    w = Weapon("weapon/ra")
    w.size = 1.3
    w.group("staff")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_gold")
    w.centred("pommel_tip", -3.75, -3.0, 1.25, 1.25, "w_gold")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap_blue")
    w.centred("staff", 5.5, 19.0, 1.75, 1.75, "w_lapis")
    for n, y in enumerate((5.5, 8.5, 11.5, 14.5, 17.5)):
        w.collar(f"band{n}", y, 2.0, 2.0, 0.75, "w_gold")
    # falcon wings spread under the sun: gold and lapis feather rows, the tips swept up
    w.group("wings")
    w.centred("body", 18.75, 22.25, 2.5, 2.0, "w_gold")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"

        def bx(name, x0, x1, y0, y1, d, tex):
            lo, hi = sorted((side * x0, side * x1))
            w.box(f"{name}_{tag}", (lo, y0, -d), (hi, y1, d), tex)

        bx("cov", 1.25, 8.0, 20.75, 22.0, 0.5, "w_gold")
        bx("mid", 1.25, 7.25, 19.75, 20.75, 0.4375, "w_lapis")
        bx("low", 1.25, 6.0, 19.0, 19.75, 0.375, "w_gold")
        bx("low2", 1.25, 4.5, 18.25, 19.0, 0.3125, "w_lapis")
        w.bar(f"tip_{tag}", (side * 7.5, 21.375), 90 - side * 67.5, 2.25, 1.25, 1.125, "w_gold")
    # the sun disc (glowing) in a gold ring, a gold cobra rearing on its face
    w.group("sun")
    w.disc("sun", 0.0, 25.25, 3.0, -0.75, 0.75, "w_sun", glow=True)
    w.octagon("ring", 0.0, 25.25, 3.5, 0.5, 0.875, "w_gold")
    w.both_flats("cobra", (-0.375, 22.0, 0.75), (0.375, 24.5, 1.0), "w_gold")
    w.both_flats("cobra_hood", (-0.75, 24.5, 0.75), (0.75, 26.0, 1.0), "w_gold")
    w.both_flats("cobra_eye", (-0.25, 25.25, 1.0), (0.25, 25.625, 1.0625), "w_ruby")
    return sized(w)


def octopus():
    w = Weapon("weapon/octopus")
    w.size = 1.2
    w.group("staff")
    ball(w, "ink_drop", 0.0, -3.75, 1.25, "w_ink", row=0.5)
    w.pommel(-2.0, w=2.25, d=2.25, h=0.75, tex="w_verdigris")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap_sea")
    w.centred("staff", 5.5, 14.0, 1.75, 1.75, "w_wood_dark")
    w.collar("band", 9.0, 2.0, 2.0, 0.75, "w_verdigris")
    w.centred("cup", 13.0, 15.5, 3.5, 3.0, "w_verdigris")
    w.centred("cup_lip", 15.5, 16.0, 4.0, 3.25, "w_verdigris")
    # the big tentacle rising out of the cup and curling over to the business side, suckers on its inner side
    w.group("tentacles")
    path = [(90, 5.0), (67.5, 2.5), (45, 2.0), (0, 1.75), (-45, 1.5), (-90, 1.25), (-135, 1.0)]
    w.curve("arm", (0.0, 15.5), path, (2.25, 2.0, 1.75, 1.5, 1.25, 1.0, 0.75), 1.75, "w_octo")
    w.curve("suckers", (0.0, 15.5), path[:4], 0.625, 2.0, "w_sucker", offset=-0.3125)
    # two small tentacles curling outwards at the sides
    w.curve("arm_l", (-1.0, 15.5), [(112.5, 2.5), (135, 1.75), (180, 1.25), (-135, 1.0)], (1.25, 1.0, 0.75, 0.5),
            1.25, "w_octo")
    w.curve("arm_r", (1.25, 15.25), [(22.5, 1.75), (0, 1.25), (-45, 1.0), (-90, 0.75)], (1.0, 0.875, 0.75, 0.5),
            1.375, "w_octo", phase=1)
    return sized(w)


def gumiho():
    w = Weapon("weapon/gumiho")
    w.size = 1.05
    w.group("tails")
    w.centred("tail_knot", -4.25, -3.0, 1.5, 1.5, "w_gold")
    for n, (angle, length, width, z) in enumerate(((-112.5, 6.0, 1.75, -0.375), (-90, 7.0, 2.0, 0.0),
                                                   (-67.5, 6.0, 1.75, 0.375))):
        end = w.bar(f"tail{n}", (0.0, -3.75), angle, length, width, 1.25 + 0.125 * n, "w_foxfur", z=z)
        w.centred(f"tail_tip{n}", end[1] - 0.75, end[1] + 0.75, width - 0.5, 1.0 + 0.125 * n, "w_pearl", x=end[0],
                  z=z)
    w.group("staff")
    w.pommel(-2.0, w=2.25, d=2.25, h=1.0, tex="w_gold")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap_blood")
    w.centred("staff", 5.5, 15.0, 1.75, 1.75, "w_paint_red")
    w.collar("band", 10.0, 2.0, 2.0, 0.75, "w_gold")
    # a white fox head with gold-lined ears; a gold crescent over it holds the glowing fox bead (여우구슬)
    w.group("fox")
    w.box("fox_head", (-1.5, 15.0, -1.25), (1.5, 17.25, 1.25), "w_foxfur")
    w.box("fox_snout", (1.5, 15.25, -0.75), (2.75, 16.5, 0.75), "w_foxfur")
    w.box("fox_nose", (2.75, 15.875, -0.375), (3.0, 16.5, 0.375), "w_ink")
    for side, tag in ((1, "r"), (-1, "l")):
        lo, hi = sorted((side * 0.375, side * 1.5))
        w.box(f"ear_{tag}", (lo, 17.25, -0.5), (hi, 18.5, 0.5), "w_foxfur")
    w.both_flats("fox_eye", (0.5, 16.25, 1.25), (1.0, 16.75, 1.375), "w_ink")
    w.curve("crescent_r", (0.75, 18.0), [(45, 1.5), (90, 3.25), (135, 1.25)], (0.75, 0.75, 0.625), 0.75, "w_gold")
    w.curve("crescent_l", (-0.75, 18.0), [(135, 1.5), (90, 3.25), (45, 1.25)], (0.75, 0.75, 0.625), 0.75, "w_gold",
            phase=1)
    ball(w, "bead", 0.0, 21.0, 2.25, "w_foxfire", row=0.75, glow=True)
    return sized(w)


def druid():
    w = Weapon("weapon/druid")
    w.size = 1.35
    w.group("staff")
    w.centred("root", -6.0, -4.5, 2.0, 1.75, "w_wood_dark", x=0.25)
    w.centred("foot", -4.5, -2.0, 2.25, 2.0, "w_wood_dark")
    w.grip(-2.0, 5.5, 2.25, 2.25, tex="w_hemp")
    # a gnarled shaft: segments that wander a little, knots and moss
    for n, (y0, y1, x, wd) in enumerate(((5.5, 10.0, 0.25, 2.0), (10.0, 14.5, -0.25, 2.125), (14.5, 19.0, 0.25, 2.0),
                                         )):
        w.centred(f"shaft{n}", y0, y1, wd, 1.875 + 0.125 * (n % 2), "w_wood_dark", x=x)
    w.box("knot0", (-1.875, 11.5, -0.75), (-1.375, 12.75, 0.75), "w_wood_dark")
    w.box("knot1", (1.25, 16.0, -0.625), (1.75, 17.0, 0.625), "w_wood_dark")
    w.centred("moss0", 9.5, 10.5, 2.75, 2.375, "w_moss", x=0.125)
    w.centred("moss1", 18.5, 19.5, 2.75, 2.25, "w_moss", x=0.125)
    # a vine winding up the shaft with two leaves
    w.polyline("vine", [(-0.75, 6.0), (0.75, 9.5), (-0.75, 13.0), (0.75, 16.5)], 0.5, 2.5, "w_leaf", extend=0.25)
    w.bar("leaf0", (0.75, 9.5), 22.5, 2.25, 1.0, 0.25, "w_leaf")
    w.bar("leaf1", (-0.75, 13.0), 157.5, 2.25, 1.0, 0.25, "w_leaf")
    # the crook: the wood curls back into a spiral, a flower blooming in its eye, leaves sprouting off it
    w.group("crook")
    path = [(90, 5.0), (112.5, 2.0), (135, 2.0), (180, 2.25), (-135, 2.0), (-90, 1.75), (-45, 1.5), (0, 1.0)]
    w.curve("crook", (0.0, 19.0), path, (2.0, 2.0, 1.875, 1.75, 1.5, 1.25, 1.0, 0.875), 2.0, "w_wood_dark", cycle=3)
    w.bar("sprout", (0.5, 24.5), 45, 2.5, 1.0, 0.25, "w_leaf", z=0.5)
    w.bar("sprout_b", (0.75, 22.0), 22.5, 3.0, 1.25, 0.25, "w_leaf", z=-0.5)
    w.group("flower")
    cx, cy = -2.5, 25.0
    for n, (dx, dy) in enumerate(((0, 0.875), (0.875, 0), (0, -0.875), (-0.875, 0))):
        w.box(f"petal{n}", (cx + dx - 0.5, cy + dy - 0.5, -0.375 - 0.0625 * n),
              (cx + dx + 0.5, cy + dy + 0.5, 0.375 + 0.0625 * n), "w_rose")
    w.box("flower_heart", (cx - 0.375, cy - 0.375, -0.75), (cx + 0.375, cy + 0.375, 0.75), "w_gold")
    return sized(w)


def crystal():
    w = Weapon("weapon/crystal")
    w.size = 1.2
    w.group("haft")
    w.centred("pommel_point", -4.5, -3.0, 1.25, 1.25, "w_amethyst")
    w.pommel(-2.0, w=2.5, d=2.5, h=1.0, tex="w_basalt")
    w.grip(-2.0, 5.5, 2.0, 2.0, tex="w_wrap_shade")
    w.centred("haft", 5.5, 13.5, 2.0, 2.0, "w_basalt")
    w.collar("band", 9.0, 2.25, 2.25, 0.75, "w_iron_dark")
    w.centred("cup", 13.0, 15.0, 3.75, 3.0, "w_basalt")
    # the cluster: one tall point, two leaning ones, two small ones, a glowing vein in the big one
    w.group("cluster")
    w.centred("main", 15.0, 24.0, 3.0, 2.5, "w_amethyst")
    w.centred("main_tip0", 24.0, 25.75, 2.25, 1.875, "w_amethyst")
    w.centred("main_tip1", 25.75, 27.0, 1.25, 1.125, "w_amethyst")
    w.centred("main_tip2", 27.0, 27.75, 0.5, 0.5, "w_amethyst")
    w.both_flats("vein", (-0.375, 16.5, 1.25), (0.375, 24.5, 1.375), "w_rift", glow=True)
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        p = w.bar(f"lean_{tag}", (side * 1.0, 14.5), 90 - side * 22.5, 7.0, 2.25, 2.0, "w_amethyst")
        q = w.bar(f"lean_tip_{tag}", p, 90 - side * 22.5, 1.5, 1.25, 1.5, "w_amethyst")
        w.bar(f"lean_pt_{tag}", q, 90 - side * 22.5, 0.75, 0.5, 1.0, "w_amethyst")
        p = w.bar(f"small_{tag}", (side * 1.5, 14.0), 90 - side * 45, 3.5, 1.25, 1.625 if side > 0 else 1.75,
                  "w_amethyst")
        w.bar(f"small_tip_{tag}", p, 90 - side * 45, 0.75, 0.5, 1.0, "w_amethyst")
    return sized(w)


def sonic():
    w = Weapon("weapon/sonic")
    w.size = 1.2
    w.group("handle")
    w.centred("sensor", -4.0, -2.0, 3.0, 3.0, "w_sculk")
    for n, (x, z) in enumerate(((-0.875, -0.875), (0.875, 0.875), (0.875, -0.875), (-0.875, 0.875))):
        w.centred(f"tendril{n}", -5.0 - 0.5 * (n % 2), -4.0, 0.5, 0.5, "w_sonic", x=x, z=z, glow=True)
    w.grip(-2.0, 6.0, 2.0, 2.0, tex="w_wrap_shade")
    w.collar("collar", 6.0, 2.5, 2.5, 1.0, "w_sculk")
    w.centred("stem", 7.0, 14.5, 1.75, 1.75, "w_iron_dark")
    # the tuning fork: a U of dark iron, sculk grown over its base, glowing sonic veins along the tines
    w.group("fork")
    w.box("base", (-4.0, 14.5, -1.0), (4.0, 16.5, 1.0), "w_iron_dark")
    w.box("sculk", (-2.0, 14.0, -1.25), (2.0, 16.0, 1.25), "w_sculk")
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        lo, hi = sorted((side * 2.25, side * 4.0))
        w.box(f"tine_{tag}", (lo, 16.5, -0.75), (hi, 29.0, 0.75), "w_iron_dark")
        w.box(f"tine_cap_{tag}", (lo + 0.25, 29.0, -0.5), (hi - 0.25, 29.5, 0.5), "w_sculk")
        mid = side * 3.125
        w.both_flats(f"vein_{tag}", (mid - 0.25, 17.5, 0.75), (mid + 0.25, 28.0, 0.875), "w_sonic", glow=True)
    # two sound rings hanging between the tines
    w.octagon("ring_low", 0.0, 21.0, 1.875, 0.375, 0.25, "w_sonic", glow=True)
    w.octagon("ring_high", 0.0, 26.0, 1.5, 0.375, 0.25, "w_sonic", glow=True)
    return sized(w)


def shaman():
    w = Weapon("weapon/shaman")
    w.size = 1.0
    w.group("streamers")
    # 오방색 streamers hanging below the grip
    for n, (tex, length) in enumerate((("w_cloth_blue", 9.0), ("w_cloth_red", 10.5), ("w_cloth_ochre", 8.0),
                                       ("w_cloth_white", 9.75), ("w_cloth_jade", 7.5))):
        x = -2.0 + n * 1.0
        d = 0.125 + 0.0625 * (n % 2)
        w.box(f"streamer{n}", (x - 0.375, -3.0 - length, -d), (x + 0.375, -3.0, d), tex)
    w.centred("knot", -3.5, -2.0, 3.0, 1.5, "w_cloth_red")
    w.group("handle")
    w.grip(-2.0, 6.0, 1.75, 1.75, tex="w_wrap_blood")
    w.collar("ferrule", 6.0, 2.0, 2.0, 0.75, "w_brass")
    w.centred("stem", 6.75, 15.0, 1.25, 1.25, "w_brass")
    w.centred("hub", 14.5, 16.0, 2.25, 2.0, "w_brass")
    # five brass bells on wires spreading from the hub
    w.group("bells")
    bells = ((0.0, 23.5), (-3.0, 21.25), (3.0, 21.25), (-3.75, 17.5), (3.75, 17.5))
    for n, (bx, by) in enumerate(bells):
        if n:
            w.strut(f"wire{n}", (0.0, 15.25), (bx, by + 0.75), 0.375, 0.375 + 0.0625 * n, "w_brass")
        else:
            w.centred("wire0", 16.0, 23.0, 0.375, 0.375, "w_brass")
        w.box(f"bell{n}", (bx - 1.125, by - 0.5, -1.125), (bx + 1.125, by + 1.0, 1.125), "w_brass")
        w.box(f"bell_crown{n}", (bx - 0.625, by + 1.0, -0.625), (bx + 0.625, by + 1.625, 0.625), "w_brass")
        w.box(f"bell_lip{n}", (bx - 1.375, by - 1.0, -1.375), (bx + 1.375, by - 0.5, 1.375), "w_brass")
        w.both_flats(f"bell_slot{n}", (bx - 0.625, by - 0.25, 1.125), (bx + 0.625, by + 0.125, 1.1875), "w_iron_dark")
    return sized(w)


# --- fans, wands, fists and the rest ---------------------------------------------------------------

def tengu():
    w = Weapon("weapon/tengu")
    w.size = 1.15
    w.group("handle")
    w.ring("tassel_loop", 0.0, -4.0, 1.5, 0.375, 0.25, "w_gold")
    w.centred("tassel", -8.0, -5.0, 1.25, 1.25, "w_cloth_red")
    w.centred("tassel_cap", -5.0, -4.5, 1.5, 1.5, "w_gold")
    w.pommel(-2.0, w=2.0, d=2.0, h=1.0, tex="w_gold")
    w.grip(-2.0, 7.0, 1.75, 1.75, tex="w_paint_red")
    w.collar("ferrule", 6.0, 2.0, 2.0, 0.75, "w_gold")
    # the hauchiwa: black feathers fanning out from a gold boss (each feather its own depth: they all meet there)
    w.group("fan")
    feathers = ((22.5, 10.5), (45, 12.0), (67.5, 13.0), (90, 13.5), (112.5, 13.0), (135, 12.0), (157.5, 10.5))
    order = (0, 2, 4, 6, 5, 3, 1)
    for n, (angle, length) in enumerate(feathers):
        d = 0.375 + 0.125 * order[n]
        end = w.bar(f"feather{n}", (0.0, 9.0), angle, length, 2.75, d, "w_crow")
        w.bar(f"feather_tip{n}", end, angle, 1.0, 1.5, d, "w_crow")
    w.disc("boss", 0.0, 9.0, 2.0, -1.375, 1.375, "w_gold", row=0.5)
    w.both_flats("boss_gem", (-0.5, 8.5, 1.375), (0.5, 9.5, 1.5), "w_cloth_red")
    return sized(w)


def magicalgirl():
    w = Weapon("weapon/magicalgirl")
    w.size = 1.0
    w.group("wand")
    ball(w, "pommel", 0.0, -3.0, 1.0, "w_gold", row=0.5)
    w.grip(-2.0, 5.0, 1.75, 1.75, tex="w_wrap_pale")
    w.centred("wand", 5.0, 16.5, 1.5, 1.5, "w_rose")
    for n, y in enumerate((5.0, 9.0, 13.0)):
        w.collar(f"band{n}", y, 1.75, 1.75, 0.5, "w_gold")
    # a ribbon bow under the head
    w.centred("bow_knot", 14.5, 15.75, 1.75, 1.75, "w_wrap_rose")
    w.ring("bow_l", -1.75, 15.125, 2.0, 0.5, 0.25, "w_wrap_rose")
    w.ring("bow_r", 1.75, 15.125, 2.0, 0.5, 0.25, "w_wrap_rose")
    w.box("bow_tail_l", (-1.5, 11.5, -0.125), (-0.875, 14.5, 0.125), "w_wrap_rose")
    w.box("bow_tail_r", (0.875, 12.25, -0.125), (1.5, 14.5, 0.125), "w_wrap_rose")
    # the heart: a glowing pink gem in a gold frame, two little white wings
    w.group("heart")
    heart = [(17.0, 17.75, [(-0.75, 0.75)]), (17.75, 18.75, [(-1.75, 1.75)]), (18.75, 19.75, [(-2.75, 2.75)]),
             (19.75, 21.75, [(-3.5, 3.5)]), (21.75, 22.75, [(-3.5, -0.5), (0.5, 3.5)]),
             (22.75, 23.5, [(-3.0, -1.0), (1.0, 3.0)])]
    frame = [(16.5, 17.0, [(-0.5, 0.5)]), (17.0, 17.75, [(-1.5, 1.5)]), (17.75, 18.75, [(-2.5, 2.5)]),
             (18.75, 19.75, [(-3.5, 3.5)]), (19.75, 21.75, [(-4.25, 4.25)]), (21.75, 22.75, [(-4.25, 4.25)]),
             (22.75, 23.5, [(-4.0, -0.25), (0.25, 4.0)]), (23.5, 24.25, [(-3.5, -0.75), (0.75, 3.5)])]
    rows(w, "frame", frame, -0.625, 0.625, "w_gold")
    rows(w, "gem", heart, -0.875, 0.875, "w_heart", glow=True)
    for side in (1, -1):
        tag = "r" if side > 0 else "l"
        w.bar(f"wing_{tag}", (side * 4.0, 20.25), 90 - side * 67.5, 3.25, 1.25, 0.375, "w_pearl")
        w.bar(f"wing2_{tag}", (side * 4.0, 19.25), 90 - side * 90, 2.5, 1.0, 0.5, "w_pearl")
    w.centred("star", 24.25, 25.25, 0.5, 0.5, "w_gold")
    return sized(w)


def monk():
    w = Weapon("weapon/monk")
    fist(w, "w_cloth_white", "w_cloth_white", "w_iron", cuff="w_leather")
    w.group("knuckle")
    for n in range(4):
        z0 = -2.125 + n * 1.0625
        w.box(f"stud{n}", (-0.25, 13.875, z0 + 0.25), (1.25, 14.5, z0 + 0.875), "w_iron_dark")
    # a string of wooden prayer beads round the wrist, its loop hanging with a red tassel
    w.group("beads")
    ring = ((-1.875, 0.0), (1.875, 0.0), (0.0, -1.875), (0.0, 1.875), (-1.375, -1.375), (1.375, -1.375),
            (-1.375, 1.375), (1.375, 1.375))
    for n, (x, z) in enumerate(ring):
        w.centred(f"bead{n}", 0.875, 2.0, 1.125, 1.125, "w_jade" if n == 3 else "w_wood_dark", x=x, z=z)
    for n in range(4):
        y = -0.25 - n * 1.0
        w.centred(f"hang{n}", y - 0.875, y, 0.875, 0.875, "w_wood_dark", x=-2.0)
    w.centred("tassel_cap", -5.0, -4.25, 1.0, 1.0, "w_jade", x=-2.0)
    w.centred("tassel", -7.75, -5.0, 1.375, 1.25, "w_cloth_red", x=-2.0)
    return sized(w)


def kiwarrior():
    w = Weapon("weapon/kiwarrior")
    fist(w, "w_tape", "w_tape", "w_tape", cuff="w_cloth_blue")
    # the ki ball (기탄) gathered over the knuckles, a faint rim of light round it
    w.group("ki")
    ball(w, "ki", 0.5, 18.75, 3.25, "w_ki", row=0.75, glow=True)
    return sized(w)


def bear():
    w = Weapon("weapon/bear")
    w.size = 1.15
    w.group("handle")
    w.pommel(-2.5, w=2.75, d=2.75, h=1.0, tex="w_leather")
    w.grip(-2.5, 5.0, 2.5, 2.5, tex="w_leather")
    w.centred("cuff", 5.0, 8.0, 4.0, 4.0, "w_bearfur")
    w.centred("strap", 6.0, 7.0, 4.25, 4.25, "w_leather")
    # the paw: a shaggy brown block, toe pads on the business side, four thick black claws hooking forward
    w.group("paw")
    w.box("paw", (-3.5, 8.0, -2.75), (3.5, 15.0, 2.75), "w_bearfur")
    w.box("pad", (3.5, 9.0, -1.75), (3.875, 12.5, 1.75), "w_leather")
    for n in range(4):
        x = -2.625 + n * 1.75
        w.box(f"toe{n}", (x - 0.75, 15.0, -2.0 + 0.125 * (n % 2)), (x + 0.75, 16.25, 2.0 - 0.125 * (n % 2)),
              "w_bearfur")
        w.curve(f"claw{n}", (x, 16.0), [(90, 2.0), (67.5, 2.0), (45, 1.75), (22.5, 1.25), (0, 0.75)],
                (1.25, 1.125, 1.0, 0.75, 0.5), 1.0 + 0.25 * (n % 2), "w_horn")
    return sized(w)


def turtle():
    w = Weapon("weapon/turtle")
    w.size = 1.1
    w.group("handle")
    w.pommel(-2.5, w=2.5, d=2.5, h=1.0, tex="w_bronze")
    w.grip(-2.5, 6.25, 2.25, 2.25, tex="w_leather")
    # the shell: a domed tortoise shell (layers of plates), a dark rim, three horn spikes on the business side
    w.group("shell")
    cy = 13.25
    w.disc("rim", 0.0, cy, 7.25, -0.75, 0.75, "w_horn", row=0.5)
    w.disc("plates", 0.0, cy, 6.5, -1.375, 1.375, "w_shell", row=0.5)
    w.disc("dome", 0.0, cy, 4.5, -2.0, 2.0, "w_shell", row=0.75)
    w.disc("crown", 0.0, cy, 2.25, -2.5, 2.5, "w_shell", row=0.75)
    for n, y in enumerate((cy - 3.5, cy, cy + 3.5)):
        w.box(f"spike{n}", (7.0, y - 0.75, -0.5), (8.0, y + 0.75, 0.5), "w_bone")
        w.box(f"spike_tip{n}", (8.0, y - 0.375, -0.25), (8.75, y + 0.375, 0.25), "w_bone")
    # a little turtle head peeking out at the top
    w.group("head")
    w.box("neck", (-1.0, cy + 6.75, -0.875), (1.0, cy + 8.0, 0.875), "w_jade")
    w.box("head", (-1.375, cy + 8.0, -1.125), (1.375, cy + 10.0, 1.125), "w_jade")
    w.both_flats("eye", (0.25, cy + 8.75, 1.125), (0.75, cy + 9.25, 1.25), "w_ink")
    return sized(w)


def tennis():
    w = Weapon("weapon/tennis")
    w.size = 1.05
    w.group("handle")
    w.centred("butt", -4.0, -3.0, 2.5, 2.25, "w_lacquer")
    w.grip(-3.0, 6.0, 2.25, 2.0, tex="w_tape")
    w.collar("grip_band", 6.0, 2.5, 2.25, 0.5, "w_paint_red")
    w.centred("shaft", 6.5, 9.0, 1.75, 1.5, "w_lacquer")
    # the throat: two arms opening into the head, a red bridge
    w.group("throat")
    for side in (1, -1):
        w.strut(f"arm_{'r' if side > 0 else 'l'}", (side * 0.25, 8.5), (side * 2.5, 13.0), 0.875, 1.25 + 0.0625 *
                (side > 0), "w_lacquer")
    w.box("bridge", (-2.25, 12.5, -0.75), (2.25, 13.25, 0.75), "w_paint_red")
    # the head: an oval frame in pixel rows, strung
    w.group("head")
    cx, cy, rx, ry, t = 0.0, 20.0, 4.75, 7.25, 0.875
    n = 20
    h = 2 * ry / n
    spans = []
    for i in range(n):
        y0 = cy - ry + i * h
        mid = y0 + h / 2 - cy
        outer = round(rx * math.sqrt(max(0.0, 1 - (mid / ry) ** 2)) * 4) / 4
        ri, rj = rx - t, ry - t
        if abs(mid) < rj:
            inner = round(ri * math.sqrt(max(0.0, 1 - (mid / rj) ** 2)) * 4) / 4
            spans.append((y0, y0 + h, [(-outer, -inner), (inner, outer)]))
        else:
            spans.append((y0, y0 + h, [(-outer, outer)]))
    rows(w, "frame", spans, -0.5, 0.5, "w_lacquer")
    ri, rj = rx - t, ry - t
    for k, x in enumerate((-2.75, -1.75, -0.75, 0.25, 1.25, 2.25, 3.25)):
        x = x - 0.25
        half = rj * math.sqrt(max(0.0, 1 - ((x + 0.125) / ri) ** 2))
        w.box(f"string_v{k}", (x, cy - half, -0.125), (x + 0.25, cy + half, 0.125), "w_strings")
    for k in range(11):
        y = cy - 5.25 + k * 1.0
        half = ri * math.sqrt(max(0.0, 1 - ((y + 0.125 - cy) / rj) ** 2))
        w.box(f"string_h{k}", (-half, y, -0.1875), (half, y + 0.25, 0.1875), "w_strings")
    # the ball resting on the strings
    w.group("ball")
    ball(w, "ball", 1.5, 18.0, 1.5, "w_felt", row=0.5, z=1.75)
    return sized(w)


# ability id -> builder (models/weapon/<id>.bbmodel = item model mcpl:weapon/<id>); weapons.py merges it
WEAPONS = {
    "titan": titan,
    "minotaur": minotaur,
    "wukong": wukong,
    "odin": odin,
    "hydra": hydra,
    "medusa": medusa,
    "ra": ra,
    "octopus": octopus,
    "gumiho": gumiho,
    "druid": druid,
    "crystal": crystal,
    "sonic": sonic,
    "shaman": shaman,
    "tengu": tengu,
    "magicalgirl": magicalgirl,
    "monk": monk,
    "kiwarrior": kiwarrior,
    "bear": bear,
    "turtle": turtle,
    "tennis": tennis,
}
