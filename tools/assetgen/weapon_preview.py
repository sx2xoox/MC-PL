"""
Preview sheets of the ability weapons, placed the way the game shows them (weaponkit.display_matrix applies the
model's own display transforms):
The inventory looks at a model from its south side (+z, x to the right); an item frame (fixed: y 180) from -z.
  weapon_<id>.png   3/4 view, inventory icon (+ the same icon at 16 and 32 px), first person (both hands),
                    third person on a player silhouette (front and side), ground and item frame
  weapons_lineup.png  every weapon: icons, 16 px icons and third person
"""

import base64
import io
import json

import numpy as np
from PIL import Image

from mcpl_assets import textures as T
from mcpl_assets.weaponkit import display_matrix, rotate, scale, translate

SLOT = "#8b8b8b"        # inventory slot grey
SKY = "#1d2230"         # a dark night backdrop


def _col(m):
    return [float(v) for v in np.asarray(m).T.reshape(-1)]


def placed(model, matrix):
    data = model.to_preview()
    for e in data["elements"]:
        e["matrix"] = _col(matrix)
    return data["textures"], data["elements"]


# --- the player (entity model space: pixels, y down; the renderer's entity transform puts it in world blocks) ---

def entity(x=0.0, z=0.0):
    """Model space -> world for a player standing at (x, 0, z) facing +z (LivingEntityRenderer: scale -1,-1,1)."""
    return translate(x, 0, z) @ rotate("y", 180) @ scale(-1, -1, 1) @ translate(0, -1.501, 0)


ARM_PITCH = -18         # HumanoidModel ITEM pose: the holding arm swings forward by pi/10


def _part(base, pivot, frm, to, pitch=0.0):
    m = base @ translate(*(np.array(pivot) / 16)) @ rotate("x", pitch) @ scale(1 / 16, 1 / 16, 1 / 16)
    return {"from": list(frm), "to": list(to), "glow": False, "tint": None, "translucent": False, "rotation": None,
            "faces": {f: {"uv": [0, 0, 16, 16], "texture": "figure"} for f in
                      ("north", "east", "south", "west", "up", "down")}, "matrix": _col(m)}


def player(x=0.0, z=0.0):
    base = entity(x, z)
    els = [
        _part(base, (0, 0, 0), (-4, -8, -4), (4, 0, 4)),
        _part(base, (0, 0, 0), (-4, 0, -2), (4, 12, 2)),
        _part(base, (-5, 2, 0), (-3, -2, -2), (1, 10, 2), ARM_PITCH),
        _part(base, (5, 2, 0), (-1, -2, -2), (3, 10, 2)),
        _part(base, (-1.9, 12, 0), (-2, 0, -2), (2, 12, 2)),
        _part(base, (1.9, 12, 0), (-2, 0, -2), (2, 12, 2)),
    ]
    buf = io.BytesIO()
    T.get("figure").save(buf, "PNG")
    return {"figure": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()}, els


def third_person(model, x=0.0, z=0.0):
    """ItemInHandLayer: hand -> rotate x -90, y 180 -> translate (1/16, 2/16, -10/16) -> thirdperson_righthand."""
    hand = (entity(x, z) @ translate(-5 / 16, 2 / 16, 0) @ rotate("x", ARM_PITCH) @ rotate("x", -90)
            @ rotate("y", 180) @ translate(1 / 16, 0.125, -0.625))
    return placed(model, hand @ display_matrix(model.display["thirdperson_righthand"]))


def first_person(model):
    """ItemInHandRenderer#applyItemArmTransform (no equip / swing): the camera sits at 0 looking along -z."""
    right = translate(0.56, -0.52, -0.72) @ display_matrix(model.display["firstperson_righthand"])
    left = translate(-0.56, -0.52, -0.72) @ display_matrix(model.display["firstperson_lefthand"], left=True)
    t1, e1 = placed(model, right)
    _, e2 = placed(model, left)
    return t1, e1 + e2


def scene(parts, views, path, ground=None):
    textures, elements = {}, []
    for t, e in parts:
        textures.update(t)
        elements.extend(e)
    out = {"textures": textures, "elements": elements, "views": views, "groundColor": "#2a2e38"}
    if ground:
        out["ground"] = ground
    path.write_text(json.dumps(out))
    return path


def _icon(png, box, sizes=(16, 32)):
    """Downsamples the icon view to real inventory sizes and pastes them (blown up, pixelated) under it."""
    im = Image.open(png).convert("RGB")
    x, y, w, h = box
    icon = im.crop((x, y, x + w, y + h))
    out = []
    for s in sizes:
        out.append(icon.resize((s, s), Image.BOX).resize((128, 128), Image.NEAREST))
    return im, out


def sheet(model, render, build_dir, png):
    """One weapon, every way the game shows it."""
    off = 50.0     # each sub-scene sits far from the others so views never see their neighbours
    parts = [
        placed(model, translate(0, 0, 0) @ scale(1 / 16, 1 / 16, 1 / 16) @ translate(-8, -8, -8)),
        placed(model, translate(off, 0, 0) @ display_matrix(model.display["gui"])),
        first_person_at(model, 2 * off),
        third_person(model, 3 * off),
        player(3 * off),
        placed(model, translate(4 * off, 0, 0) @ display_matrix(model.display["ground"])),
        placed(model, translate(5 * off, 0, 0) @ display_matrix(model.display["fixed"])),
    ]
    p = 3 * off
    views = [
        {"x": 0, "y": 0, "w": 500, "h": 620, "az": 35, "el": 18, "target": [0, 0.1, 0], "dist": 4.2, "background": SKY},
        {"x": 500, "y": 0, "w": 300, "h": 300, "ortho": 0.5, "eye": [off, 0, 10], "target": [off, 0, 0],
         "background": SLOT},
        {"x": 800, "y": 0, "w": 800, "h": 450, "fov": 70, "eye": [2 * off, 0, 0], "target": [2 * off, 0, -1],
         "background": SKY},
        {"x": 800, "y": 450, "w": 400, "h": 550, "eye": [p - 2.3, 1.55, 2.7], "target": [p - 0.35, 1.0, 0.2],
         "fov": 40, "background": SKY},
        {"x": 1200, "y": 450, "w": 400, "h": 550, "eye": [p - 3.2, 1.2, -0.3], "target": [p - 0.3, 1.05, 0.25],
         "fov": 40, "background": SKY},
        {"x": 0, "y": 620, "w": 400, "h": 380, "az": 30, "el": 35, "target": [4 * off, 0.1, 0], "dist": 1.6,
         "background": "#3a4a32"},
        {"x": 400, "y": 620, "w": 400, "h": 380, "ortho": 0.6, "eye": [5 * off, 0, -10], "target": [5 * off, 0, 0],
         "background": "#6b4f33"},
    ]
    json_path = scene(parts, views, build_dir / f"{model.name.replace('/', '_')}.preview.json")
    render(json_path, png)
    im, icons = _icon(png, (500, 0, 300, 300))
    im.paste(icons[0], (520, 310))
    im.paste(icons[1], (660, 310))
    im.save(png)


def first_person_at(model, x):
    t, e = first_person(model)
    shift = translate(x, 0, 0)
    for el in e:
        m = np.array(el["matrix"]).reshape(4, 4).T
        el["matrix"] = _col(shift @ m)
    return t, e


def lineup(models, render, build_dir, png):
    """Every weapon: inventory icons (top), the same at 16 px (middle), held in third person (bottom)."""
    n = len(models)
    w = 1600 // n
    off = 50.0
    parts, views = [], []
    for i, model in enumerate(models):
        parts.append(placed(model, translate(i * off, 10, 0) @ display_matrix(model.display["gui"])))
        parts.append(third_person(model, i * off))
        parts.append(player(i * off))
        views.append({"x": i * w, "y": 0, "w": w, "h": w, "ortho": 0.5, "eye": [i * off, 10, 10],
                      "target": [i * off, 10, 0], "background": SLOT})
        views.append({"x": i * w, "y": 500, "w": w, "h": 500, "eye": [i * off - 2.3, 1.5, 2.7],
                      "target": [i * off - 0.3, 1.0, 0.2], "fov": 40, "background": SKY})
    render(scene(parts, views, build_dir / "weapons_lineup.preview.json"), png)
    im = Image.open(png).convert("RGB")
    for i in range(n):
        icon = im.crop((i * w, 0, i * w + w, w))
        for k, s in enumerate((16, 32)):
            im.paste(icon.resize((s, s), Image.BOX).resize((96, 96), Image.NEAREST), (i * w + 40 + k * 120, w + 10))
    im.save(png)
