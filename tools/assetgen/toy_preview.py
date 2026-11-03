"""
Preview sheets of the 공성전 v2 toy kingdom (models/toys.py, models/creatures.py), placed the way the game shows them
(item displays at their display scale and centre height; moving parts in their rest pose):
  tower.png / core.png    damage stages 0-3 in red, then the intact building in blue behind its soap bubble;
                          attackers' 3/4 view above, the back below; a player silhouette for scale
  buildings.png           tower, core, fountain and telescope side by side, neutral and team coloured
  minions.png             soldier, archer, cannon in both team colours, front and back
  jungle.png              jelly x3, mice x4, teddy, rabbit, the dragon and the merchant
  creature_<kind>.png     one creature large: front 3/4, side, back
  scale_lineup.png        every creature and building in a row next to a player
"""

import json
import math

from mcpl_assets.toykit import Build
from models import creatures, toys

RED = (0xFF, 0x55, 0x55)
BLUE = (0x55, 0x55, 0xFF)
SKY = "#cfe3f2"
GRASS = "#9fcf8f"


def _matrix(translation, scale, yaw=0.0):
    """Column-major 4x4: world = translation + Ry(yaw) * scale * (p / 16 - 0.5)."""
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    r = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    k = scale / 16
    m = [[r[i][j] * k for j in range(3)] for i in range(3)]
    t = [translation[i] - sum(m[i][j] * 8 for j in range(3)) for i in range(3)]
    return [m[0][0], m[1][0], m[2][0], 0, m[0][1], m[1][1], m[2][1], 0, m[0][2], m[1][2], m[2][2], 0,
            t[0], t[1], t[2], 1]


def placed(model, tint, translation, scale, yaw=0.0):
    data = model.to_preview(tint)
    mat = _matrix(translation, scale, yaw)
    for e in data["elements"]:
        e["matrix"] = mat
    return data["textures"], data["elements"]


def building(built, kind, stage, tint, shielded=False, ox=0.0, oz=0.0, yaw=0.0):
    textures, elements = {}, []
    for part, stages, centre, scale, _motion in toys.PARTS[kind]:
        if part == "shield":
            if not shielded:
                continue
            name = f"structure/{kind}_shield"
        elif stages:
            if stage >= stages:
                continue
            name = f"structure/{kind}_{part}_{stage}"
        else:
            if stage >= (2 if part == "flag" else 3):
                continue
            name = f"structure/{kind}_{part}"
        t, e = placed(built[name], tint, (ox, centre, oz), scale, yaw)
        textures.update(t)
        elements.extend(e)
    return textures, elements


def landmark(built, kind, tint, ox=0.0, oz=0.0):
    parts = [placed(built["structure/fountain"], tint, (ox, toys.FOUNTAIN_CENTRE, oz), toys.FOUNTAIN_SCALE)] \
        if kind == "fountain" else [
        placed(built["structure/telescope"], tint, (ox, toys.TELESCOPE_CENTRE, oz), toys.TELESCOPE_SCALE),
        placed(built["structure/telescope_scope"], tint, (ox, toys.SCOPE_CENTRE, oz), toys.SCOPE_SCALE, 30)]
    textures, elements = {}, []
    for t, e in parts:
        textures.update(t)
        elements.extend(e)
    return textures, elements


def creature(built, kind, tint, x=0.0, z=0.0, yaw=0.0):
    textures, elements = {}, []
    for part, _fn, scale, centre, _motion in creatures.CREATURES[kind]:
        t, e = placed(built[creatures.model_id(kind, part)], tint, (x, centre, z), scale, yaw)
        textures.update(t)
        elements.extend(e)
    return textures, elements


_FIGURE = None


def figure(x, z, yaw=0.0):
    """The 1.8-block player silhouette for scale."""
    global _FIGURE
    if _FIGURE is None:
        b = Build()
        b.box("leg_l", (-0.25, 0, -0.125), (0, 0.675, 0.125), "figure")
        b.box("leg_r", (0, 0, -0.125), (0.25, 0.675, 0.125), "figure")
        b.box("body", (-0.25, 0.675, -0.125), (0.25, 1.35, 0.125), "figure")
        b.box("arm_l", (-0.475, 0.675, -0.1125), (-0.25, 1.35, 0.1125), "figure")
        b.box("arm_r", (0.25, 0.675, -0.1125), (0.475, 1.35, 0.1125), "figure")
        b.box("head", (-0.225, 1.35, -0.225), (0.225, 1.8, 0.225), "figure")
        _FIGURE = b.centred("_figure", 1, 1.0)
    return placed(_FIGURE, None, (x, 1.0, z), 1, yaw)


def scene(parts, path, views, ground=None):
    textures, elements = {}, []
    for t, e in parts:
        textures.update(t)
        elements.extend(e)
    for v in views:
        v.setdefault("background", SKY)
    out = {"textures": textures, "elements": elements, "grid": False, "views": views, "groundColor": GRASS}
    if ground:
        out["ground"] = ground
    path.write_text(json.dumps(out))
    return path


def stages_sheet(built, kind, render, work, png):
    gap = 16 if kind == "tower" else 18
    configs = [(s, RED, False) for s in range(4)] + [(0, BLUE, True)]
    parts = [building(built, kind, s, tint, shielded, ox=i * gap) for i, (s, tint, shielded) in enumerate(configs)]
    parts += [figure(i * gap - 3.2, -4.6) for i in range(len(configs))]
    views = []
    for i in range(len(configs)):
        target = [i * gap, 7.5, 0]
        views.append({"x": i * 320, "y": 0, "w": 320, "h": 600, "az": -30, "el": 12, "target": target, "dist": 40})
        views.append({"x": i * 320, "y": 600, "w": 320, "h": 400, "az": 150, "el": 26, "target": target, "dist": 42})
    render(scene(parts, work / f"{kind}.preview.json", views, ground=[-12, 0, -12, len(configs) * gap, 12]), png)


def buildings_sheet(built, render, work, png):
    parts = [building(built, "tower", 0, BLUE, ox=-24), building(built, "core", 0, RED, ox=-6),
             landmark(built, "fountain", None, ox=10), landmark(built, "telescope", BLUE, ox=22),
             landmark(built, "fountain", RED, ox=10, oz=12)]
    parts += [figure(x, -4.0) for x in (-27, -10, 7, 19)]
    views = [{"x": 0, "y": 0, "w": 1600, "h": 640, "az": -14, "el": 10, "target": [-1, 6, 0], "dist": 58},
             {"x": 0, "y": 640, "w": 800, "h": 360, "az": -40, "el": 30, "target": [10, 2, 0], "dist": 16},
             {"x": 800, "y": 640, "w": 800, "h": 360, "az": -30, "el": 20, "target": [22, 4.5, 0], "dist": 18}]
    render(scene(parts, work / "buildings.preview.json", views, ground=[-34, 0, -14, 30, 20]), png)


def minions_sheet(built, render, work, png):
    # a row (seen from -z, so +x is on the left): figure, red soldier, archer, cannon, blue soldier, archer, cannon
    parts = [figure(1.6, 0)]
    for i, kind in enumerate(("soldier", "archer", "cannon")):
        parts.append(creature(built, kind, RED, x=-i * 1.7, z=0))
        parts.append(creature(built, kind, BLUE, x=-(i + 3) * 1.7 - 0.6, z=0))
    views = [{"x": 0, "y": 0, "w": 1600, "h": 560, "az": -12, "el": 10, "target": [-3.4, 0.8, 0], "dist": 10.5},
             {"x": 0, "y": 560, "w": 1600, "h": 440, "az": 165, "el": 16, "target": [-3.4, 0.8, 0], "dist": 10.5}]
    render(scene(parts, work / "minions.preview.json", views, ground=[-11, 0, -4, 4, 4]), png)


def jungle_sheet(built, render, work, png):
    parts = []
    for n, (x, z) in enumerate(((-6.2, 0.2), (-5.3, -0.4), (-5.6, 0.8))):
        parts.append(creature(built, "jelly", None, x, z, yaw=n * 20 - 20))
    for n, (x, z) in enumerate(((-3.6, -0.3), (-3.0, 0.4), (-3.4, 1.0), (-2.6, -0.5))):
        parts.append(creature(built, "mouse", None, x, z, yaw=n * 25 - 30))
    parts.append(creature(built, "teddy", None, -0.6, 0))
    parts.append(creature(built, "rabbit", None, 1.6, 0))
    parts.append(creature(built, "merchant", None, 3.4, 0))
    parts.append(creature(built, "dragon", None, 8.5, 0.5, yaw=-25))
    parts.append(figure(5.0, -0.5))
    views = [{"x": 0, "y": 0, "w": 1600, "h": 620, "az": -12, "el": 12, "target": [1.6, 1.3, 0], "dist": 19},
             {"x": 0, "y": 620, "w": 800, "h": 380, "az": 140, "el": 20, "target": [-2.0, 0.8, 0], "dist": 10},
             {"x": 800, "y": 620, "w": 800, "h": 380, "az": 150, "el": 22, "target": [8.5, 1.8, 0.5], "dist": 13}]
    render(scene(parts, work / "jungle.preview.json", views, ground=[-9, 0, -5, 13, 6]), png)


def creature_sheet(built, kind, render, work, png):
    tint = RED if kind in ("soldier", "archer", "cannon") else None
    parts = [creature(built, kind, tint), figure(-1.3 if kind != "dragon" else -3.6, 0.2)]
    size = {"dragon": 3.4, "teddy": 1.6}.get(kind, 1.0)
    target = [0, 0.75 * size + 0.1, 0]
    dist = 4.2 * size + 1.2
    views = [{"x": 0, "y": 0, "w": 800, "h": 1000, "az": -32, "el": 12, "target": target, "dist": dist},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 90, "el": 8, "target": target, "dist": dist},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 160, "el": 22, "target": target, "dist": dist}]
    render(scene(parts, work / f"creature_{kind}.preview.json", views, ground=[-5, 0, -5, 5, 5]), png)


def scale_lineup(built, render, work, png):
    parts = [figure(0, 0)]
    x = 1.3
    for kind, step in (("jelly", 1.1), ("mouse", 1.0), ("soldier", 1.2), ("archer", 1.2), ("cannon", 1.9),
                       ("merchant", 1.4), ("rabbit", 1.6), ("teddy", 2.4), ("dragon", 5.5)):
        tint = BLUE if kind in ("soldier", "archer", "cannon") else None
        x += step / 2
        parts.append(creature(built, kind, tint, x, 0, yaw=-15))
        x += step / 2
    parts.append(landmark(built, "fountain", None, x + 3.5, 0))
    parts.append(building(built, "tower", 0, RED, ox=x + 10, oz=1))
    views = [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": -8, "el": 8, "target": [x / 2 + 4, 3.2, 0], "dist": 30}]
    render(scene(parts, work / "scale.preview.json", views, ground=[-3, 0, -6, x + 15, 6]), png)


def render_all(built, render, work, out):
    stages_sheet(built, "tower", render, work, out / "tower.png")
    stages_sheet(built, "core", render, work, out / "core.png")
    buildings_sheet(built, render, work, out / "buildings.png")
    minions_sheet(built, render, work, out / "minions.png")
    jungle_sheet(built, render, work, out / "jungle.png")
    for kind in creatures.CREATURES:
        creature_sheet(built, kind, render, work, out / f"creature_{kind}.png")
    scale_lineup(built, render, work, out / "scale_lineup.png")
