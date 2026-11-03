"""
공성전 v2 creatures (docs/siege-v2.md §2 §4 §7), item models mcpl:creature/<kind> (+ mcpl:creature/<kind>_<part>
for a moving part). The game shows them on item displays that follow the creature's vanilla mob, only to players with
the pack (game/siege/creature/CreatureModels mirrors CREATURES below: scale, centre height and motion of every part).

  minions (team colour by tint)
    soldier   태엽 병정    a toy soldier: tall black shako with a plume, team jacket, white trousers, a little sabre,
                           a wind-up key turning on his back
    archer    장난감 궁수   a toy archer: feathered cap and tunic in the team colour, a wooden bow, a quiver
    cannon    장난감 대포   a toy cannon on a team-coloured carriage with big wooden wheels (and eyes)
  jungle
    jelly     젤리         a strawberry gummy slime (three per camp), wobbles
    mouse     태엽 쥐       a lilac clockwork mouse with pink ears, the key on its back spinning
    teddy     곰인형 대장    a big plush teddy captain with a captain's cap and a red bow tie
    rabbit    시계 토끼      the white rabbit in a waistcoat, holding a big gold pocket watch
    dragon    장난감 드래곤  the epic boss: a big mint plush dragon with butter horns, pink spikes, flapping wings and
                           a wind-up key on its back
  shop
    merchant  태엽 상인     a chubby clockwork shopkeeper: top hat, moustache, apron with a gold coin, key on the back

Designed in blocks at their real size (feet at y = 0, the face towards -z); small parts are scaled up with
Build.scaled so the cute proportions stay readable from a distance (big heads, short legs, dot eyes and blush).
"""

from mcpl_assets import toy_textures  # noqa: F401  (registers the t_* textures)
from mcpl_assets.toykit import Build


# ------------------------------------------------------------------------------------------------
# minions
# ------------------------------------------------------------------------------------------------

def _chibi_legs(b, trousers="t_white", boots="t_ink"):
    b.mirror_rbox("boot", (-0.23, 0, -0.16), (-0.03, 0.14, 0.14), boots, 0.03)
    b.mirror("leg", (-0.19, 0.14, -0.1), (-0.04, 0.42, 0.1), trousers)


def _head(b, y0=0.86, half=0.24, depth=0.22, eye_y=None):
    b.rbox("head", (-half, y0, -depth), (half, y0 + 0.44, depth), "t_peach", 0.07)
    b.eyes("eye", eye_y or y0 + 0.2, -depth, 0.2, size=0.075)
    b.cheeks("cheek", y0 + 0.11, -depth, 0.32, w=0.08, h=0.04)
    b.box("mouth", (-0.03, y0 + 0.07, -depth - 0.01), (0.03, y0 + 0.095, -depth), "t_rose")


def soldier():
    b = Build()
    _chibi_legs(b)
    b.rbox("jacket", (-0.26, 0.4, -0.17), (0.26, 0.87, 0.17), "t_cloth_tint", 0.06, tint=True)
    b.rbox("belt", (-0.275, 0.47, -0.185), (0.275, 0.54, 0.185), "t_white", 0.06, top=False, bottom=False)
    b.box("buckle", (-0.05, 0.465, -0.2), (0.05, 0.545, -0.185), "t_gold")
    for n, y in enumerate((0.6, 0.7, 0.8)):
        b.box(f"button_{n}", (-0.03, y, -0.19), (0.03, y + 0.05, -0.17), "t_gold")
    b.mirror_rbox("arm", (-0.37, 0.5, -0.08), (-0.26, 0.85, 0.08), "t_cloth_tint", 0.03, tint=True)
    b.mirror("hand", (-0.36, 0.43, -0.07), (-0.27, 0.5, 0.07), "t_peach")
    b.mirror("epaulette", (-0.39, 0.83, -0.1), (-0.22, 0.89, 0.1), "t_gold")
    _head(b)
    b.rbox("shako", (-0.2, 1.26, -0.18), (0.2, 1.64, 0.18), "t_ink", 0.04)
    b.box("visor", (-0.2, 1.26, -0.27), (0.2, 1.3, -0.18), "t_ink")
    b.box("badge", (-0.06, 1.4, -0.195), (0.06, 1.52, -0.18), "t_gold")
    b.box("strap", (-0.21, 1.2, -0.12), (0.21, 1.26, 0.12), "t_gold")
    b.rbox("plume", (-0.06, 1.64, -0.06), (0.06, 1.82, 0.06), "t_paint_tint", 0.02, tint=True)
    # the sabre in his right hand (+x), upright
    b.box("grip", (0.29, 0.39, -0.035), (0.37, 0.49, 0.035), "t_gold")
    b.box("guard", (0.27, 0.49, -0.07), (0.39, 0.53, 0.07), "t_gold")
    b.box("blade", (0.31, 0.53, -0.02), (0.35, 1.08, 0.02), "t_metal")
    return b.scaled(1.15)


def soldier_key():
    """The wind-up key on his back (turns about the z axis through x = 0, y = SOLDIER_KEY)."""
    b = Build()
    c = SOLDIER_KEY / 1.15
    b.box("shaft", (-0.03, c - 0.03, 0.17), (0.03, c + 0.03, 0.33), "t_gold")
    b.rbox("hub", (-0.06, c - 0.06, 0.3), (0.06, c + 0.06, 0.36), "t_gold", 0.015)
    b.mirror_rbox("bow", (-0.24, c - 0.09, 0.31), (-0.06, c + 0.09, 0.35), "t_gold", 0.04)
    return b.scaled(1.15)


SOLDIER_KEY = 0.72


def archer():
    b = Build()
    _chibi_legs(b, trousers="t_caramel", boots="t_wood")
    b.rbox("tunic", (-0.26, 0.38, -0.17), (0.26, 0.87, 0.17), "t_cloth_tint", 0.06, tint=True)
    b.rbox("belt", (-0.275, 0.48, -0.185), (0.275, 0.55, 0.185), "t_caramel", 0.06, top=False, bottom=False)
    b.mirror_rbox("arm", (-0.37, 0.5, -0.08), (-0.26, 0.85, 0.08), "t_cloth_tint", 0.03, tint=True)
    b.mirror("hand", (-0.36, 0.43, -0.07), (-0.27, 0.5, 0.07), "t_peach")
    _head(b)
    # a feathered cap
    b.rbox("cap_brim", (-0.27, 1.24, -0.25), (0.27, 1.32, 0.25), "t_cloth_tint", 0.05, tint=True)
    b.rbox("cap", (-0.19, 1.32, -0.15), (0.19, 1.46, 0.19), "t_cloth_tint", 0.05, tint=True)
    b.box("cap_tip", (-0.07, 1.46, -0.03), (0.07, 1.54, 0.12), "t_cloth_tint", tint=True)
    b.box("feather", (0.17, 1.3, 0.0), (0.21, 1.62, 0.06), "t_butter", rotation=("z", -22.5, (0.19, 1.3, 0.03)))
    # the bow in the left hand (-x), upright
    x0, x1 = -0.42, -0.36
    b.box("bow_grip", (x0, 0.4, -0.13), (x1, 0.56, -0.07), "t_wood")
    b.box("bow_top", (x0, 0.56, -0.12), (x1, 0.92, -0.08), "t_wood", rotation=("x", 22.5, (-0.39, 0.56, -0.1)))
    b.box("bow_bottom", (x0, 0.04, -0.12), (x1, 0.4, -0.08), "t_wood", rotation=("x", -22.5, (-0.39, 0.4, -0.1)))
    b.box("bow_string", (-0.395, 0.1, 0.02), (-0.385, 0.86, 0.03), "t_white")
    # the quiver on the back with three arrows
    b.rbox("quiver", (0.02, 0.5, 0.17), (0.2, 0.9, 0.29), "t_caramel", 0.03)
    for n, x in enumerate((0.05, 0.11, 0.17)):
        b.box(f"arrow_{n}", (x - 0.015, 0.9, 0.22), (x + 0.015, 1.02, 0.25), "t_wood")
        b.box(f"fletch_{n}", (x - 0.03, 1.0, 0.21), (x + 0.03, 1.08, 0.26), "t_white")
    return b.scaled(1.15)


def cannon():
    b = Build()
    b.rbox("carriage", (-0.32, 0.22, -0.45), (0.32, 0.55, 0.5), "t_paint_tint", 0.06, tint=True)
    b.rbox("trail", (-0.18, 0.06, 0.4), (0.18, 0.3, 0.85), "t_paint_tint", 0.05, tint=True)
    for side in (-1, 1):
        x0, x1 = (0.32, 0.43) if side > 0 else (-0.43, -0.32)
        z = 0.05
        b.box(f"wheel_{side}", (x0, 0.02, z - 0.22), (x1, 0.66, z + 0.22), "t_wood")
        b.box(f"wheel_{side}_b", (x0 + 0.005, 0.12, z - 0.32), (x1 - 0.005, 0.56, z + 0.32), "t_wood")
        hx0, hx1 = (x1, x1 + 0.05) if side > 0 else (x0 - 0.05, x0)
        b.box(f"hub_{side}", (hx0, 0.26, z - 0.08), (hx1, 0.42, z + 0.08), "t_gold")
    b.box("star", (-0.08, 0.3, -0.465), (0.08, 0.46, -0.45), "t_butter")
    # the barrel: black enamel, gold muzzle ring and knob
    b.box("barrel", (-0.17, 0.6, -0.8), (0.17, 0.86, 0.3), "t_iron")
    b.box("barrel_b", (-0.13, 0.555, -0.79), (0.13, 0.905, 0.29), "t_iron")
    b.box("muzzle", (-0.21, 0.52, -0.92), (0.21, 0.94, -0.78), "t_gold")
    b.box("bore", (-0.1, 0.63, -0.925), (0.1, 0.83, -0.92), "t_ink")
    b.box("ring", (-0.19, 0.54, -0.2), (0.19, 0.92, -0.12), "t_gold")
    b.rbox("knob", (-0.1, 0.63, 0.3), (0.1, 0.83, 0.42), "t_gold", 0.03)
    b.box("cradle", (-0.2, 0.55, -0.3), (0.2, 0.6, 0.2), "t_wood")
    # a friendly face on the muzzle ring's top... no: on the carriage front, under the barrel
    b.eyes("eye", 0.42, -0.45, 0.36, size=0.08)
    return b.scaled(1.45)


# ------------------------------------------------------------------------------------------------
# jungle
# ------------------------------------------------------------------------------------------------

def jelly():
    b = Build()
    b.rbox("body", (-0.34, 0, -0.34), (0.34, 0.56, 0.34), "t_jelly", 0.1)
    b.rbox("dome", (-0.22, 0.5, -0.22), (0.22, 0.68, 0.22), "t_jelly", 0.06, bottom=False)
    b.box("glint", (-0.26, 0.42, -0.345), (-0.16, 0.5, -0.34), "t_white")
    b.eyes("eye", 0.3, -0.34, 0.24, size=0.08)
    b.cheeks("cheek", 0.21, -0.34, 0.38, w=0.08, h=0.04)
    b.box("mouth", (-0.04, 0.17, -0.35), (0.04, 0.2, -0.34), "t_ink")
    return b.scaled(1.2)


def mouse():
    b = Build()
    b.rbox("body", (-0.17, 0.04, -0.24), (0.17, 0.34, 0.28), "t_lilac", 0.07)
    b.rbox("head", (-0.14, 0.07, -0.42), (0.14, 0.3, -0.2), "t_lilac", 0.05)
    b.rbox("snout", (-0.07, 0.1, -0.5), (0.07, 0.2, -0.4), "t_lilac", 0.02)
    b.box("nose", (-0.035, 0.15, -0.53), (0.035, 0.21, -0.5), "t_rose")
    b.mirror_rbox("ear", (-0.22, 0.27, -0.33), (-0.07, 0.43, -0.29), "t_lilac", 0.03)
    b.mirror("ear_in", (-0.2, 0.29, -0.335), (-0.09, 0.41, -0.33), "t_pink")
    b.eyes("eye", 0.24, -0.42, 0.14, size=0.05, depth=0.02)
    b.mirror("wheel", (-0.15, 0, -0.18), (-0.1, 0.06, -0.06), "t_ink")
    b.mirror("wheel_b", (-0.15, 0, 0.12), (-0.1, 0.06, 0.24), "t_ink")
    b.box("tail", (-0.02, 0.14, 0.28), (0.02, 0.18, 0.46), "t_pink")
    b.box("tail_up", (-0.02, 0.18, 0.42), (0.02, 0.32, 0.46), "t_pink")
    return b.scaled(1.35)


def mouse_key():
    """The key on the mouse's back (turns about the y axis)."""
    b = Build()
    b.box("shaft", (-0.02, 0.34, -0.02), (0.02, 0.43, 0.02), "t_gold")
    b.mirror_rbox("bow", (-0.13, 0.41, -0.015), (-0.02, 0.5, 0.015), "t_gold", 0.02)
    return b.scaled(1.35)


def teddy():
    b = Build()
    b.mirror_rbox("leg", (-0.72, 0, -0.42), (-0.14, 0.62, 0.32), "t_cocoa", 0.15)
    b.mirror("pad", (-0.62, 0.1, -0.435), (-0.24, 0.48, -0.42), "t_caramel")
    b.rbox("body", (-0.7, 0.5, -0.48), (0.7, 1.6, 0.48), "t_cocoa", 0.22)
    b.rbox("belly", (-0.45, 0.68, -0.5), (0.45, 1.36, -0.46), "t_caramel", 0.1, top=False, bottom=False)
    b.mirror_rbox("arm", (-0.98, 0.8, -0.24), (-0.66, 1.5, 0.18), "t_cocoa", 0.1)
    b.mirror("paw", (-0.95, 0.82, -0.25), (-0.69, 1.0, -0.24), "t_caramel")
    b.rbox("head", (-0.64, 1.55, -0.52), (0.64, 2.5, 0.46), "t_cocoa", 0.12)
    b.rbox("muzzle", (-0.28, 1.68, -0.66), (0.28, 2.0, -0.5), "t_caramel", 0.07)
    b.box("nose", (-0.11, 1.88, -0.69), (0.11, 1.98, -0.66), "t_ink")
    b.eyes("eye", 2.14, -0.52, 0.52, size=0.15, depth=0.04)
    b.cheeks("cheek", 1.92, -0.52, 0.72, w=0.14, h=0.06)
    b.mirror_rbox("ear", (-0.7, 2.3, -0.16), (-0.34, 2.7, 0.1), "t_cocoa", 0.1)
    b.mirror("ear_in", (-0.62, 2.38, -0.17), (-0.42, 2.6, -0.16), "t_caramel")
    # the red bow tie and the captain's cap
    b.mirror("tie", (-0.32, 1.48, -0.56), (-0.06, 1.66, -0.48), "t_rose")
    b.box("tie_knot", (-0.06, 1.51, -0.58), (0.06, 1.63, -0.48), "t_rose")
    b.rbox("cap", (-0.4, 2.48, -0.36), (0.4, 2.74, 0.36), "t_navy", 0.08, bottom=False)
    b.box("cap_brim", (-0.36, 2.48, -0.56), (0.36, 2.54, -0.36), "t_ink")
    b.box("cap_badge", (-0.09, 2.56, -0.375), (0.09, 2.68, -0.36), "t_gold")
    b.rbox("tail", (-0.14, 0.62, 0.48), (0.14, 0.88, 0.62), "t_cocoa", 0.05)
    return b


def rabbit():
    b = Build()
    b.mirror_rbox("foot", (-0.3, 0, -0.42), (-0.06, 0.14, 0.1), "t_fur", 0.04)
    b.mirror("leg", (-0.23, 0.14, -0.12), (-0.08, 0.52, 0.1), "t_fur")
    b.rbox("vest", (-0.32, 0.5, -0.24), (0.32, 1.16, 0.24), "t_grape", 0.08)
    b.box("shirt", (-0.12, 0.72, -0.255), (0.12, 1.16, -0.24), "t_white")
    b.box("tie", (-0.08, 1.06, -0.27), (0.08, 1.14, -0.255), "t_rose")
    for n, y in enumerate((0.6, 0.7)):
        b.box(f"button_{n}", (0.14, y, -0.26), (0.19, y + 0.05, -0.24), "t_gold")
    b.mirror_rbox("arm", (-0.44, 0.7, -0.24), (-0.32, 1.08, 0.06), "t_fur", 0.03)
    # the big pocket watch held in front, its chain to the waistcoat
    b.rbox("watch", (-0.24, 0.62, -0.46), (0.24, 1.02, -0.32), "t_gold", 0.07)
    b.box("dial", (-0.17, 0.68, -0.47), (0.17, 0.96, -0.46), "t_white")
    b.box("hand_h", (-0.015, 0.8, -0.48), (0.015, 0.92, -0.47), "t_ink")
    b.box("hand_m", (-0.015, 0.8, -0.475), (0.11, 0.83, -0.47), "t_ink")
    b.box("crown", (-0.04, 1.02, -0.42), (0.04, 1.08, -0.36), "t_gold")
    b.box("chain", (0.2, 0.72, -0.32), (0.24, 0.75, -0.24), "t_gold")
    b.rbox("head", (-0.3, 1.14, -0.28), (0.3, 1.64, 0.24), "t_fur", 0.1)
    b.eyes("eye", 1.42, -0.28, 0.26, size=0.08)
    b.cheeks("cheek", 1.3, -0.28, 0.38, w=0.08, h=0.04)
    b.box("nose", (-0.04, 1.32, -0.3), (0.04, 1.37, -0.28), "t_rose")
    b.mirror_rbox("ear", (-0.22, 1.6, -0.06), (-0.07, 2.28, 0.07), "t_fur", 0.04)
    b.mirror("ear_in", (-0.19, 1.68, -0.07), (-0.1, 2.2, -0.06), "t_pink")
    b.rbox("tail", (-0.1, 0.52, 0.24), (0.1, 0.7, 0.38), "t_fur", 0.05)
    return b.scaled(1.1)


def dragon():
    b = Build()
    for n, (x, z) in enumerate(((-0.75, -0.85), (0.75, -0.85), (-0.75, 0.75), (0.75, 0.75))):
        b.rbox(f"leg_{n}", (x - 0.36, 0, z - 0.36), (x + 0.36, 0.8, z + 0.36), "t_plush", 0.12)
        b.box(f"claw_{n}", (x - 0.24, 0.0, z - 0.4), (x + 0.24, 0.12, z - 0.36), "t_white")
    b.rbox("body", (-1.1, 0.6, -1.3), (1.1, 2.2, 1.3), "t_plush", 0.35)
    b.rbox("belly", (-0.75, 0.75, -1.33), (0.75, 1.9, -1.28), "t_butter", 0.15, top=False, bottom=False)
    b.rbox("neck", (-0.6, 1.6, -1.6), (0.6, 2.4, -1.0), "t_plush", 0.15)
    b.rbox("head", (-0.85, 1.95, -2.25), (0.85, 3.15, -0.95), "t_plush", 0.3)
    b.rbox("snout", (-0.55, 2.0, -2.75), (0.55, 2.6, -2.15), "t_plush", 0.15)
    b.mirror("nostril", (-0.32, 2.38, -2.77), (-0.2, 2.48, -2.75), "t_mint_dark")
    b.box("smile", (-0.25, 2.12, -2.77), (0.25, 2.17, -2.75), "t_mint_dark")
    b.eyes("eye", 2.82, -2.25, 0.86, size=0.24, depth=0.05)
    b.cheeks("cheek", 2.45, -2.25, 1.3, w=0.2, h=0.08)
    b.mirror_rbox("horn", (-0.62, 3.1, -1.75), (-0.38, 3.6, -1.5), "t_butter", 0.06, bottom=False)
    b.mirror("fin", (-0.95, 2.55, -1.4), (-0.85, 2.95, -1.05), "t_rose")
    for n, z in enumerate((-0.85, 0.75)):
        b.rbox(f"spike_{n}", (-0.18, 2.2, z - 0.22), (0.18, 2.62, z + 0.22), "t_rose", 0.06, bottom=False)
    b.rbox("tail_0", (-0.5, 0.8, 1.3), (0.5, 1.6, 2.0), "t_plush", 0.15)
    b.rbox("tail_1", (-0.35, 1.0, 2.0), (0.35, 1.6, 2.55), "t_plush", 0.1)
    b.rbox("tail_2", (-0.22, 1.3, 2.55), (0.22, 1.75, 2.9), "t_plush", 0.07)
    b.rbox("tail_tip", (-0.2, 1.75, 2.6), (0.2, 2.1, 2.85), "t_rose", 0.06)
    b.rbox("spike_t", (-0.12, 1.6, 1.55), (0.12, 1.85, 1.85), "t_rose", 0.04, bottom=False)
    return b.scaled(DRAGON)


DRAGON = 1.15


def dragon_wings():
    """The two wings (flap: the game squeezes this model in x and lifts it)."""
    b = Build()
    for side in (-1, 1):
        x0, x1 = (1.05, 2.3) if side > 0 else (-2.3, -1.05)
        b.box(f"wing_{side}", (x0, 1.95, -0.7), (x1, 2.05, 0.7), "t_lilac",
              rotation=("z", 22.5 * side, (side * 1.05, 2.0, 0)))
        b.box(f"wing_bone_{side}", (x0, 2.05, -0.75), (x1, 2.15, -0.55), "t_grape",
              rotation=("z", 22.5 * side, (side * 1.05, 2.0, 0)))
    return b.scaled(DRAGON)


def dragon_key():
    """The big wind-up key on the dragon's back (turns about the y axis)."""
    b = Build()
    b.box("shaft", (-0.08, 2.2, -0.08), (0.08, 2.7, 0.08), "t_gold")
    b.mirror_rbox("bow", (-0.6, 2.6, -0.06), (-0.08, 3.1, 0.06), "t_gold", 0.12)
    b.rbox("hub", (-0.14, 2.62, -0.11), (0.14, 2.9, 0.11), "t_gold", 0.04)
    return b.scaled(DRAGON)


# ------------------------------------------------------------------------------------------------
# shop
# ------------------------------------------------------------------------------------------------

def merchant():
    b = Build()
    b.mirror_rbox("shoe", (-0.28, 0, -0.2), (-0.05, 0.12, 0.14), "t_ink", 0.03)
    b.mirror("leg", (-0.24, 0.12, -0.12), (-0.07, 0.34, 0.1), "t_navy")
    b.rbox("coat", (-0.4, 0.3, -0.32), (0.4, 1.1, 0.32), "t_sky", 0.12)
    b.box("apron", (-0.28, 0.32, -0.335), (0.28, 0.96, -0.32), "t_white")
    b.box("coin", (-0.09, 0.56, -0.345), (0.09, 0.74, -0.335), "t_gold")
    b.mirror_rbox("arm", (-0.52, 0.55, -0.12), (-0.4, 1.02, 0.12), "t_sky", 0.03)
    b.mirror("hand", (-0.51, 0.47, -0.1), (-0.41, 0.55, 0.1), "t_peach")
    b.rbox("head", (-0.3, 1.08, -0.27), (0.3, 1.58, 0.27), "t_peach", 0.08)
    b.eyes("eye", 1.43, -0.27, 0.24, size=0.075)
    b.cheeks("cheek", 1.35, -0.27, 0.42, w=0.07, h=0.04)
    b.box("moustache", (-0.18, 1.24, -0.3), (0.18, 1.3, -0.27), "t_caramel")
    b.mirror("moustache_curl", (-0.24, 1.27, -0.3), (-0.18, 1.33, -0.27), "t_caramel")
    b.box("nose", (-0.05, 1.3, -0.32), (0.05, 1.38, -0.27), "t_rose")
    b.mirror("tie", (-0.14, 1.04, -0.35), (-0.02, 1.12, -0.3), "t_rose")
    b.rbox("hat_brim", (-0.33, 1.56, -0.32), (0.33, 1.62, 0.32), "t_ink", 0.06, top=False, bottom=False)
    b.rbox("hat", (-0.22, 1.62, -0.2), (0.22, 2.02, 0.2), "t_ink", 0.04)
    b.box("hat_band", (-0.225, 1.64, -0.205), (0.225, 1.72, 0.205), "t_rose")
    return b.scaled(1.1)


def merchant_key():
    """The key on the shopkeeper's back (turns about the z axis through x = 0, y = MERCHANT_KEY)."""
    b = Build()
    c = MERCHANT_KEY / 1.1
    b.box("shaft", (-0.035, c - 0.035, 0.32), (0.035, c + 0.035, 0.48), "t_gold")
    b.mirror_rbox("bow", (-0.26, c - 0.1, 0.45), (-0.06, c + 0.1, 0.5), "t_gold", 0.04)
    b.rbox("hub", (-0.07, c - 0.07, 0.44), (0.07, c + 0.07, 0.51), "t_gold", 0.015)
    return b.scaled(1.1)


MERCHANT_KEY = 0.8


# ------------------------------------------------------------------------------------------------
# registry: kind -> [(part, builder, display scale, centre height, motion)]; part "body" = mcpl:creature/<kind>,
# others = mcpl:creature/<kind>_<part>. Kept in sync with CreatureModels.java.
# ------------------------------------------------------------------------------------------------

CREATURES = {
    "soldier": [("body", soldier, 1.0, 1.0, "waddle"), ("key", soldier_key, 0.5, SOLDIER_KEY, "key_z")],
    "archer": [("body", archer, 1.0, 1.0, "waddle")],
    "cannon": [("body", cannon, 1.0, 1.0, "roll")],
    "jelly": [("body", jelly, 1.0, 1.0, "wobble")],
    "mouse": [("body", mouse, 1.0, 1.0, "scurry"), ("key", mouse_key, 0.5, 0.6, "key_y")],
    "teddy": [("body", teddy, 1.0, 1.4, "waddle")],
    "rabbit": [("body", rabbit, 1.0, 1.1, "hop")],
    "dragon": [("body", dragon, 2.5, 2.0, "stomp"), ("wings", dragon_wings, 2.0, 2.3, "flap"),
               ("key", dragon_key, 1.0, 3.05, "key_y")],
    "merchant": [("body", merchant, 1.0, 1.0, "bob"), ("key", merchant_key, 0.5, MERCHANT_KEY, "key_z")],
}


def model_id(kind, part):
    return f"creature/{kind}" if part == "body" else f"creature/{kind}_{part}"


ALL = {}
for _kind, _parts in CREATURES.items():
    for _part, _fn, _scale, _centre, _motion in _parts:
        ALL[model_id(_kind, _part)] = (lambda kind, part, fn, scale, centre:
                                       lambda: fn().centred(model_id(kind, part), scale, centre))(
            _kind, _part, _fn, _scale, _centre)
