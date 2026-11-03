"""
Builds the MCPL 3D models into the resource pack sources (src/main/resourcepack-src/models/*.bbmodel) and renders
preview sheets. The Gradle `resourcePack` task converts the .bbmodel files into the pack; this script only needs to
run when a model changes (its output is committed).

    python3 -I tools/assetgen/build.py                      # models only
    python3 -I tools/assetgen/build.py --render <out dir>   # + preview PNGs (needs node, playwright and three:
                                                            #   cd tools/assetgen/render && npm install)

Ported from STSV tools/assetgen (same palette formula, texture rules and model checks; see docs/siege.md).
"""

import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from mcpl_assets.palette import PALETTE  # noqa: E402
from models import towers  # noqa: E402

REPO = HERE.parent.parent
MODELS = REPO / "src" / "main" / "resourcepack-src" / "models"
BUILD = REPO / "build" / "assetgen"
RENDER = HERE / "render" / "render.mjs"

# preview tints: what the dye tint does in game with the two default team colours
RED = (0xFF, 0x55, 0x55)
BLUE = (0x55, 0x55, 0xFF)


def main():
    args = sys.argv[1:]
    render_dir = Path(args[args.index("--render") + 1]) if "--render" in args else None
    MODELS.mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(parents=True, exist_ok=True)

    for stale in MODELS.glob("*.bbmodel"):
        if stale.stem not in towers.ALL:
            stale.unlink()
            print(f"removed {stale.name} (model no longer defined)")

    built = {}
    for name, fn in towers.ALL.items():
        model = fn()
        built[name] = model
        model.write(MODELS / f"{name}.bbmodel")
        print(f"model   {name}: {len(model.elements)} elements")

    if render_dir:
        render_dir.mkdir(parents=True, exist_ok=True)
        for tower in ("front", "middle", "core"):
            sheet(built, tower, render_dir / f"tower_{tower}.png")
        lineup(built, render_dir / "towers_lineup.png")
        parts(built, render_dir / "tower_parts.png")


def placed(model, tint=None, scale=1.0, anchor=(8, -16, 8), at=(8, -16, 8)):
    """Preview elements of `model`, scaled around `anchor` (relative to the body scale) and moved onto `at`."""
    data = model.to_preview(tint)
    out = []
    for e in data["elements"]:
        e = dict(e)
        e["from"] = [at[i] + (e["from"][i] - anchor[i]) * scale for i in range(3)]
        e["to"] = [at[i] + (e["to"][i] - anchor[i]) * scale for i in range(3)]
        if e.get("rotation"):
            axis, angle, origin = e["rotation"]
            e["rotation"] = [axis, angle, [at[i] + (origin[i] - anchor[i]) * scale for i in range(3)]]
        out.append(e)
    return data["textures"], out


def tower(built, kind, stage, tint, shielded=False, ox=0.0, oz=0.0):
    """One tower as the game assembles it (body + floating crystal + optional shield), at body scale."""
    spec = towers.PARTS[kind]
    textures, elements = {}, []

    def add(part):
        t, e = part
        textures.update(t)
        elements.extend(e)

    add(placed(built[f"tower_{kind}_{stage}"], tint, at=(8 + ox, -16, 8 + oz)))
    if stage < 3:
        rel = spec["crystal_scale"] / towers.BODY_SCALE
        add(placed(built["tower_crystal"], tint, rel, anchor=(8, 8, 8),
                   at=(8 + ox, -16 + spec["crystal_spot"], 8 + oz)))
    if shielded:
        rel = spec["shield_scale"] / towers.BODY_SCALE
        add(placed(built["tower_shield"], tint, rel, at=(8 + ox, -16, 8 + oz)))
    return textures, elements


def scene(parts, path, views=None, ground=None):
    textures, elements = {}, []
    for t, e in parts:
        textures.update(t)
        elements.extend(e)
    out = {"textures": textures, "elements": elements, "grid": False,
           "views": views or [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": 28, "el": 16, "k": 2.0}]}
    if ground:
        out["ground"] = ground
    path.write_text(json.dumps(out))
    return path


def render(json_path, png_path):
    subprocess.run(["node", str(RENDER), str(json_path), str(png_path)], check=True)


def sheet(built, kind, png):
    """One tower: stages 0-3 (red team) and the shielded intact tower (blue team), front-left above, back-right below."""
    gap = 90
    configs = [(s, RED, False) for s in range(4)] + [(0, BLUE, True)]
    parts = [tower(built, kind, s, tint, shielded, ox=i * gap) for i, (s, tint, shielded) in enumerate(configs)]
    views = []
    for i in range(len(configs)):
        target = [8 + i * gap, 8, 8]
        views.append({"x": i * 320, "y": 0, "w": 320, "h": 620, "az": -35, "el": 12, "target": target, "dist": 150})
        views.append({"x": i * 320, "y": 620, "w": 320, "h": 380, "az": 145, "el": 34, "target": target, "dist": 160})
    render(scene(parts, BUILD / f"{kind}.preview.json", views, ground=[-40, -16, -40, len(configs) * gap, 56]), png)


def lineup(built, png):
    """The three towers in their game order (front -> middle -> core), close up."""
    parts = [tower(built, "front", 0, RED, ox=80), tower(built, "middle", 0, RED, ox=40),
             tower(built, "core", 0, RED, ox=0)]
    views = [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": -28, "el": 10, "target": [48, 6, 8], "dist": 190}]
    render(scene(parts, BUILD / "lineup.preview.json", views, ground=[-20, -16, -20, 120, 40]), png)


def parts(built, png):
    """Crystal (both team colours) and the shield, on their own."""
    p = [placed(built["tower_crystal"], RED, 1.0, anchor=(8, 8, 8), at=(8, 8, 8)),
         placed(built["tower_crystal"], BLUE, 1.0, anchor=(8, 8, 8), at=(32, 8, 8)),
         placed(built["tower_shield"], BLUE, 1.0, at=(80, -16, 8))]
    views = [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": -20, "el": 15, "target": [44, 8, 8], "dist": 150}]
    render(scene(p, BUILD / "parts.preview.json", views), png)


if __name__ == "__main__":
    _ = PALETTE
    main()
