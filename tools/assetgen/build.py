"""
Builds the MCPL 3D models into the resource pack sources (src/main/resourcepack-src/models/*.bbmodel) and renders
preview sheets. The Gradle `resourcePack` task converts the .bbmodel files into the pack; this script only needs to
run when a model changes (its output is committed).

    python3 -I tools/assetgen/build.py                      # models only
    python3 -I tools/assetgen/build.py --render <out dir>   # + preview PNGs (needs node, playwright and three:
                                                            #   cd tools/assetgen/render && npm install)
    python3 -I tools/assetgen/build.py --render <out dir> --only weapons   # just the ability weapon sheets

Ported from STSV tools/assetgen (same palette formula, texture rules and model checks; see docs/siege.md).
"""

import json
import math
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from mcpl_assets.palette import PALETTE  # noqa: E402
from models import towers, weapons  # noqa: E402
import weapon_preview  # noqa: E402

REPO = HERE.parent.parent
MODELS = REPO / "src" / "main" / "resourcepack-src" / "models"
BUILD = REPO / "build" / "assetgen"
RENDER = HERE / "render" / "render.mjs"

# preview tints: what the dye tint does in game with the two default team colours
RED = (0xFF, 0x55, 0x55)
BLUE = (0x55, 0x55, 0xFF)


def _matrix(translation, scale, yaw=0.0, tilt=0.0):
    """Column-major 4x4 for three.js: world = translation + Ry(yaw) Rx(tilt) * scale * (p / 16 - 0.5)."""
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    ct, st = math.cos(math.radians(tilt)), math.sin(math.radians(tilt))
    # R = Ry * Rx
    r = [[cy, sy * st, sy * ct],
         [0, ct, -st],
         [-sy, cy * st, cy * ct]]
    k = scale / 16
    m = [[r[i][j] * k for j in range(3)] for i in range(3)]
    t = [translation[i] - sum(m[i][j] * 8 for j in range(3)) for i in range(3)]
    return [m[0][0], m[1][0], m[2][0], 0, m[0][1], m[1][1], m[2][1], 0, m[0][2], m[1][2], m[2][2], 0,
            t[0], t[1], t[2], 1]


def placed(model, tint, translation, scale, yaw=0.0, tilt=0.0):
    data = model.to_preview(tint)
    mat = _matrix(translation, scale, yaw, tilt)
    for e in data["elements"]:
        e["matrix"] = mat
    return data["textures"], data["elements"]


# preview poses of the moving parts (the game animates them)
POSE = {"ring": (0, 0), "ring_reverse": (22.5, 0), "halo": (20, 24), "halo_reverse": (70, -28), "heart": (20, 0),
        "core": (30, 0)}


def tower(built, kind, stage, tint, shielded=False, ox=0.0, oz=0.0):
    """One tower as the game assembles it, in blocks, standing on (ox, 0, oz)."""
    textures, elements = {}, []
    for part, stages, y, scale, motion in towers.PARTS[kind]:
        if part == "shield":
            if not shielded:
                continue
            name = f"tower_{kind}_shield"
        elif stages:
            if stage >= stages:
                continue            # collapsed with the tower
            name = f"tower_{kind}_{part}_{stage}"
        else:
            if stage >= 3:
                continue            # shattered with the tower
            name = f"tower_{kind}_{part}"
        yaw, tilt = POSE.get(motion, (0, 0))
        t, e = placed(built[name], tint, (ox, y, oz), scale, yaw, tilt)
        textures.update(t)
        elements.extend(e)
    return textures, elements


def figure(built, x, z):
    """The 1.8-block player silhouette for scale."""
    return placed(built["_figure"], None, (x, 6, z), towers.BODY_SCALE)


def figure_model():
    from mcpl_assets.model import Model
    m = Model("_figure")
    b = towers.Build()
    b.box("leg_l", (-0.25, 0, -0.125), (0, 0.675, 0.125), "figure")
    b.box("leg_r", (0, 0, -0.125), (0.25, 0.675, 0.125), "figure")
    b.box("body", (-0.25, 0.675, -0.125), (0.25, 1.35, 0.125), "figure")
    b.box("arm_l", (-0.475, 0.675, -0.1125), (-0.25, 1.35, 0.1125), "figure")
    b.box("arm_r", (0.25, 0.675, -0.1125), (0.475, 1.35, 0.1125), "figure")
    b.box("head", (-0.225, 1.35, -0.225), (0.225, 1.8, 0.225), "figure")
    for name, frm, to, tex, kw in b.boxes:
        towers._add(m, name, frm, to, tex, kw, 0, towers.BODY_SCALE)
    return m


GROUND = "#24262c"


def scene(parts, path, views, ground=None):
    textures, elements = {}, []
    for t, e in parts:
        textures.update(t)
        elements.extend(e)
    out = {"textures": textures, "elements": elements, "grid": False, "views": views, "groundColor": GROUND}
    if ground:
        out["ground"] = ground
    path.write_text(json.dumps(out))
    return path


def render(json_path, png_path):
    subprocess.run(["node", str(RENDER), str(json_path), str(png_path)], check=True)


def sheet(built, kind, png):
    """One tower: stages 0-3 (red team) and the shielded intact tower (blue team); attackers' view above, the
    defenders' side below."""
    gap = 22
    configs = [(s, RED, False) for s in range(4)] + [(0, BLUE, True)]
    parts = [tower(built, kind, s, tint, shielded, ox=i * gap) for i, (s, tint, shielded) in enumerate(configs)]
    parts += [figure(built, i * gap - 3.5, -5.5) for i in range(len(configs))]
    views = []
    for i in range(len(configs)):
        target = [i * gap, 8.5, 0]
        views.append({"x": i * 320, "y": 0, "w": 320, "h": 600, "az": -30, "el": 10, "target": target, "dist": 46})
        views.append({"x": i * 320, "y": 600, "w": 320, "h": 400, "az": 150, "el": 28, "target": target, "dist": 50})
    render(scene(parts, BUILD / f"{kind}.preview.json", views, ground=[-12, 0, -12, len(configs) * gap, 12]), png)


def lineup(built, png):
    """The three towers in their game order (front -> middle -> core) with a player for scale."""
    parts = [tower(built, "front", 0, RED, ox=18), tower(built, "middle", 0, RED, ox=0),
             tower(built, "core", 0, RED, ox=-18)]
    parts += [figure(built, x, -6.0) for x in (-18, 0, 18)]
    views = [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": -16, "el": 9, "target": [0, 8, 0], "dist": 62}]
    render(scene(parts, BUILD / "lineup.preview.json", views, ground=[-30, 0, -14, 30, 14]), png)


def closeups(built, png):
    """Each tower large, from the attackers' side (left) and in the blue team colour behind its shield (right)."""
    parts = []
    views = []
    for i, kind in enumerate(("front", "middle", "core")):
        ox = i * 40
        parts.append(tower(built, kind, 0, RED, ox=ox))
        parts.append(tower(built, kind, 0, BLUE, ox=ox + 20, shielded=False))
        parts.append(figure(built, ox - 4, -5.5))
        views.append({"x": i * 533, "y": 0, "w": 533, "h": 620, "az": -35, "el": 14, "target": [ox, 8.5, 0],
                      "dist": 36})
        views.append({"x": i * 533, "y": 620, "w": 533, "h": 380, "az": 200, "el": 40, "target": [ox + 20, 7, 0],
                      "dist": 40})
    render(scene(parts, BUILD / "closeups.preview.json", views, ground=[-14, 0, -14, 114, 14]), png)


def main():
    args = sys.argv[1:]
    render_dir = Path(args[args.index("--render") + 1]) if "--render" in args else None
    MODELS.mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(parents=True, exist_ok=True)

    for stale in MODELS.glob("*.bbmodel"):
        if stale.stem.startswith("tower_") and stale.stem not in towers.ALL:
            stale.unlink()
            print(f"removed {stale.name} (model no longer defined)")

    only = args[args.index("--only") + 1] if "--only" in args else None

    weapon_dir = MODELS / "weapon"
    weapon_dir.mkdir(exist_ok=True)
    for stale in weapon_dir.glob("*.bbmodel"):
        if stale.stem not in weapons.WEAPONS:
            stale.unlink()
            print(f"removed weapon/{stale.name} (weapon no longer defined)")
    armoury = []
    for ability, fn in weapons.WEAPONS.items():
        model = fn()
        assert model.name == f"weapon/{ability}", f"{ability}: the model must be named weapon/{ability}"
        armoury.append(model)
        model.write(weapon_dir / f"{ability}.bbmodel")
        print(f"weapon  {ability}: {len(model.elements)} elements")

    built = {}
    for name, fn in towers.ALL.items():
        model = fn()
        built[name] = model
        model.write(MODELS / f"{name}.bbmodel")
        print(f"model   {name}: {len(model.elements)} elements")

    if render_dir and only in (None, "weapons"):
        render_dir.mkdir(parents=True, exist_ok=True)
        for model in armoury:
            weapon_preview.sheet(model, render, BUILD, render_dir / f"{model.name.replace('/', '_')}.png")
        weapon_preview.lineup(armoury, render, BUILD, render_dir / "weapons_lineup.png")
    if render_dir and only in (None, "towers"):
        render_dir.mkdir(parents=True, exist_ok=True)
        built["_figure"] = figure_model()
        for kind in ("front", "middle", "core"):
            sheet(built, kind, render_dir / f"tower_{kind}.png")
        lineup(built, render_dir / "towers_lineup.png")
        closeups(built, render_dir / "towers_closeup.png")


if __name__ == "__main__":
    _ = PALETTE
    main()
