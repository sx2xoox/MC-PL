"""
MCPL palette (STSV palette v3): every ramp is GENERATED from a few numbers by one shared formula.

Why: hand-picked ramps drift (each new material ends up with its own brightness steps and saturation).
Here a material only chooses hue / chroma / lightness band; the formula applies the same rules to all:

  1. Lightness ladder in OKLCH (perceptually even), so step i of stone is as bright as step i of wood.
  2. Shared light colour: shadows lean towards blue-violet, highlights towards warm yellow (hue shift),
     so unrelated objects look lit by the same sun.
  3. Chroma fades at the extremes (dark shadows and bright highlights are less saturated).

Targets were measured from vanilla 26.3 block textures (see STSV docs/ART_STYLE.md):
  natural materials  L 0.40-0.75, chroma 0.05-0.10   -> "vanilla-friendly" base
  dyed / metals      chroma 0.11-0.16
  gems / light       chroma 0.16-0.21                 -> reserved for ACCENT materials (fire, lamp, magic, gold)
"""

from .color import oklch_to_rgb

SHADOW_HUE = 285.0   # blue-violet
LIGHT_HUE = 85.0     # warm yellow
HUE_SHIFT = 10.0     # max degrees a ramp end rotates towards those hues

CHROMA_BOOST = 1.35          # natural / structural materials
ACCENT_CHROMA_BOOST = 1.15   # accents were already loud
EDGE_CHROMA_FALLOFF = 0.18   # dark/light ends lose a little colour
MIN_LIGHTNESS = 0.24         # darkest step never goes below this (no muddy near-blacks)

# name: (hue, chroma, center lightness, lightness span, steps, accent)
#   hue may be a (start, end) tuple for ramps that change colour along the ladder (fire)
MATERIALS = {
    # --- structural (from STSV) -----------------------------------------------------------------
    "soot":        (32, 0.035, 0.33, 0.20, 4, False),
    "mortar":      (55, 0.015, 0.40, 0.12, 3, False),
    "stone":       (95, 0.006, 0.58, 0.22, 4, False),
    "steel":       (250, 0.014, 0.50, 0.40, 6, False),
    "wood_dark":   (62, 0.060, 0.42, 0.22, 4, False),   # spruce-like
    "leather":     (55, 0.060, 0.36, 0.20, 4, False),
    "gold":        (92, 0.160, 0.78, 0.32, 4, True),
    "fire":        ((28, 100), 0.170, 0.62, 0.70, 6, True),
    # --- MCPL castle (공성전 towers) ---------------------------------------------------------------
    "castle":      (78, 0.016, 0.60, 0.30, 5, False),   # warm grey ashlar, a touch lighter than vanilla stone bricks
    "castle_dark": (70, 0.020, 0.40, 0.18, 4, False),   # plinths, deep joints, rubble shadows
    "slate":       (255, 0.030, 0.44, 0.24, 4, False),  # roof tiles
    "moss":        (128, 0.070, 0.46, 0.18, 3, False),  # ruin overgrowth
    # tintable parts are drawn in near-white greys: the minecraft:dye tint multiplies them with the team colour
    "tint":        (85, 0.004, 0.80, 0.36, 5, False),
    "shield":      (215, 0.080, 0.86, 0.16, 3, True),   # pale energy bubble (drawn translucent)
}


def _toward(h, target, amount):
    d = (target - h + 540) % 360 - 180
    return h + max(-abs(d), min(abs(d), amount)) * (1 if d >= 0 else -1)


def build_ramp(hue, chroma, center, span, steps, accent=False):
    out = []
    for i in range(steps):
        t = i / (steps - 1) if steps > 1 else 0.5
        L = center + span * (t - 0.5)
        if isinstance(hue, tuple):
            h = hue[0] + (hue[1] - hue[0]) * t
        else:
            h = hue
        edge = abs(t - 0.5) * 2                     # 0 at the middle, 1 at both ends
        shift = HUE_SHIFT * edge * (0.5 if accent else 1.0)
        h = _toward(h, LIGHT_HUE if t > 0.5 else SHADOW_HUE, shift)
        c = chroma * (ACCENT_CHROMA_BOOST if accent else CHROMA_BOOST) * (1 - EDGE_CHROMA_FALLOFF * edge)
        if chroma < 0.02:                           # near-greys still pick up a hint of the light colour
            c = max(c, 0.010 if chroma >= 0.006 else chroma)
        out.append(oklch_to_rgb(max(L, MIN_LIGHTNESS), c, h % 360))
    return out


PALETTE = {name: build_ramp(*spec) for name, spec in MATERIALS.items()}


def ramp(name: str, i: int, alpha: int = 255):
    r = PALETTE[name]
    return (*r[max(0, min(len(r) - 1, i))], alpha)


def size(name: str) -> int:
    return len(PALETTE[name])
