"""Previews of the 공성전 장난감 병정 갑옷 (models/toy_armor.py) on a player model.

    python3 -I tools/assetgen/armor_preview.py <out dir> [--vanilla <26.3 client assets/minecraft dir>]

Writes, for both teams and tiers 0-III:
  toy_armor.png          front and back of every look (what players with the pack see)
  toy_armor_close.png    tier 0 and III large, front 3/4 and back 3/4
  toy_armor_icons.png    the inventory icons (x8) on slot-coloured squares
  toy_armor_vanilla.png  (with --vanilla) what players without the pack see: the team-dyed leather armour with the
                         look's vanilla trim, the trim pattern coloured by the material's palette like the game does
The player is the armour model of 26.3 (outer armour inflated 1 px, leggings 0.5 px, the helmet's hat layer 1.5 px)
over a plain skin; the layers use the vanilla box UV (limbs on the left mirrored). Needs node, playwright and three
(see README.md) and Pillow.
"""

import base64
import io
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from mcpl_assets import armor_icons, armor_textures  # noqa: E402
from mcpl_assets.palette import ramp  # noqa: E402
from models import toy_armor  # noqa: E402

RENDER = HERE / "render" / "render.mjs"
SKY = "#cfe3f2"
TEAM_DYE = {"blue": (0x4F, 0x8D, 0xF5), "red": (0xEF, 0x5A, 0x6F)}   # VanillaCreatureLook.BLUE / RED

# model parts: (texture part, from, to) in pixels, feet at y 0, the front towards -z, the character's right at +x
# (the arms and the right leg a hair forward: in the game they swing, here their faces would z-fight the body's)
BODY = [
    ("head", (-4, 24, -4), (4, 32, 4), False),
    ("body", (-4, 12, -2), (4, 24, 2), False),
    ("arm", (4, 12, -2.06), (8, 24, 1.94), False),
    ("arm", (-8, 12, -2.06), (-4, 24, 1.94), True),
    ("leg", (0, 0, -2.03), (4, 12, 1.97), False),
    ("leg", (-4, 0, -2), (0, 12, 2), True),
]
# which parts each armour layer draws, and how far it is inflated (HumanoidArmorModel: outer 1.0, inner 0.5)
ARMOUR = {
    "helmet": ("humanoid", {"head": 1.0, "hat": 1.5}),
    "chest": ("humanoid", {"body": 1.0, "arm": 1.0}),
    "leggings": ("humanoid_leggings", {"body": 0.5, "leg": 0.5}),
    "boots": ("humanoid", {"leg": 1.0}),
}
DRAW_ORDER = ("leggings", "boots", "chest", "helmet")


def _uri(image):
    buf = io.BytesIO()
    image.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def _skin():
    """A plain 64x64 player skin (peach skin, a simple face, a grey shirt, navy trousers) in the vanilla layout."""
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = im.load()

    def box(u, v, w, h, d, colour):
        for x in range(u, u + 2 * (w + d)):
            for y in range(v + d, v + d + h):
                px[x, y] = colour(x - u, y - v - d)
        for x in range(u + d, u + d + 2 * w):
            for y in range(v, v + d):
                px[x, y] = colour(x - u, -1)
    skin = lambda x, y: ramp("t_peach", 2)  # noqa: E731
    box(0, 0, 8, 8, 8, lambda x, y: ramp("t_cocoa", 1) if y < 2 else skin(x, y))
    for x, y, c in ((9, 12, ramp("t_snow", 3)), (10, 12, ramp("t_ink", 1)), (13, 12, ramp("t_ink", 1)),
                    (14, 12, ramp("t_snow", 3)), (11, 14, ramp("t_rose", 1)), (12, 14, ramp("t_rose", 1))):
        px[x, y] = c
    box(16, 16, 8, 12, 4, lambda x, y: ramp("steel", 4))
    box(40, 16, 4, 12, 4, lambda x, y: ramp("steel", 4) if y < 8 else skin(x, y))
    box(0, 16, 4, 12, 4, lambda x, y: ramp("t_navy", 2))
    box(32, 48, 4, 12, 4, lambda x, y: ramp("steel", 4) if y < 8 else skin(x, y))
    box(16, 48, 4, 12, 4, lambda x, y: ramp("t_navy", 2))
    return im


SKIN_UV = {"head": (0, 0, 8, 8, 8), "body": (16, 16, 8, 12, 4), "arm": (40, 16, 4, 12, 4),
           "arm_l": (32, 48, 4, 12, 4), "leg": (0, 16, 4, 12, 4), "leg_l": (16, 48, 4, 12, 4)}


def _faces(uv, size, mirror):
    """Renderer faces (uv in 0..16 of the texture) for a Minecraft box UV (u, v, w, h, d) on a size (tw, th) sheet."""
    u, v, w, h, d = uv
    tw, th = size
    regions = {
        "up": (u + d, v, w, d), "down": (u + d + w, v, w, d),
        "east": (u, v + d, d, h), "north": (u + d, v + d, w, h),
        "west": (u + d + w, v + d, d, h), "south": (u + 2 * d + w, v + d, w, h),
    }
    if mirror:
        regions["east"], regions["west"] = regions["west"], regions["east"]
    out = {}
    for face, (x, y, rw, rh) in regions.items():
        u1, v1, u2, v2 = x * 16 / tw, y * 16 / th, (x + rw) * 16 / tw, (y + rh) * 16 / th
        if face in ("up", "down"):
            u1, v1, u2, v2 = u2, v2, u1, v1      # entity tops: the front at the bottom, the right side on the left
        if mirror:
            u1, u2 = u2, u1
        out[face] = [u1, v1, u2, v2]
    return out


def figure(textures, layers, x0, z0=0.0, yaw=0.0):
    """Elements of one player at (x0, z0) blocks: the skin plus the armour layers {kind: texture name}."""
    import math
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    k = 1 / 16
    matrix = [cy * k, 0, -sy * k, 0, 0, k, 0, 0, sy * k, 0, cy * k, 0, x0, 0, z0, 1]
    elements = []

    def add(tex, uv, size, frm, to, grow, mirror):
        frm = [c - grow for c in frm]
        to = [c + grow for c in to]
        faces = {f: {"uv": u, "texture": tex} for f, u in _faces(uv, size, mirror).items()}
        elements.append({"from": frm, "to": to, "glow": False, "tint": None, "translucent": False,
                         "rotation": None, "faces": faces, "matrix": matrix})
    for part, frm, to, mirror in BODY:
        key = part + ("_l" if mirror and part in ("arm", "leg") else "")
        add("skin", SKIN_UV[key], (64, 64), frm, to, 0, False)
    for kind in DRAW_ORDER:
        if kind not in layers:
            continue
        layer, parts = ARMOUR[kind]
        for part, frm, to, mirror in BODY:
            for armour_part, grow in parts.items():
                if armour_part == part or (armour_part == "hat" and part == "head"):
                    add(layers[kind] + "@" + layer, armor_textures.PARTS[armour_part], (64, 32), frm, to, grow, mirror)
    return elements


def _sheet(out, figures, views, path, width=1600, height=1000):
    textures, elements = {}, []
    for t, e in figures:
        textures.update(t)
        elements.extend(e)
    for v in views:
        v.setdefault("background", SKY)
    data = {"width": width, "height": height, "textures": textures, "elements": elements, "views": views,
            "ground": [-4, 0, -6, 26, 6], "groundColor": "#9fcf8f"}
    work = out / (path.stem + ".preview.json")
    work.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(work), str(path)], check=True)
    work.unlink()


def _dressed(look_textures, team, tier, x, yaw=0.0):
    tex = {"skin": _uri(_skin())}
    names = {}
    for layer, image in look_textures(team, tier).items():
        tex[f"{team}{tier}@{layer}"] = _uri(image)
    for kind in ARMOUR:
        names[kind] = f"{team}{tier}"
    return tex, figure(tex, names, x, yaw=yaw)


def toy_textures(team, tier):
    return {layer: paint(team, tier) for layer, paint in armor_textures.LAYERS.items()}


def lineup(out, look_textures, path):
    """Both teams, tiers 0-III, front (top row) and back (bottom row)."""
    figures, views = [], []
    col = 0
    for team in ("blue", "red"):
        for tier in armor_textures.TIERS:
            x = col * 3.0
            figures.append(_dressed(look_textures, team, tier, x))
            target = [x, 1.0, 0]
            views.append({"x": col * 200, "y": 0, "w": 200, "h": 500, "az": -18, "el": 8, "target": target,
                          "dist": 6.2, "fov": 26})
            views.append({"x": col * 200, "y": 500, "w": 200, "h": 500, "az": 162, "el": 12, "target": target,
                          "dist": 6.2, "fov": 26})
            col += 1
    _sheet(out, figures, views, path)


def close(out, look_textures, path):
    figures, views = [], []
    for n, (team, tier) in enumerate((("blue", 0), ("blue", 3), ("red", 0), ("red", 3))):
        x = n * 3.0
        figures.append(_dressed(look_textures, team, tier, x))
        target = [x, 1.0, 0]
        views.append({"x": n * 400, "y": 0, "w": 400, "h": 500, "az": -35, "el": 14, "target": target, "dist": 4.4,
                      "fov": 30})
        views.append({"x": n * 400, "y": 500, "w": 400, "h": 500, "az": 150, "el": 18, "target": target, "dist": 4.4,
                      "fov": 30})
    _sheet(out, figures, views, path)


def icons(path):
    k = 8
    cell = 16 * k + 16
    sheet = Image.new("RGBA", (cell * 4 * 2 + 16, cell * 4 + 16), (198, 198, 198, 255))
    for t, team in enumerate(("blue", "red")):
        for tier in armor_textures.TIERS:
            for p, piece in enumerate(armor_icons.PIECES):
                x = 8 + (t * 4 + tier) * cell
                y = 8 + p * cell
                slot = Image.new("RGBA", (16 * k + 8, 16 * k + 8), (139, 139, 139, 255))
                sheet.paste(slot, (x, y))
                image = armor_icons.icon(piece, team, tier).resize((16 * k, 16 * k), Image.NEAREST)
                sheet.alpha_composite(image, (x + 4, y + 4))
    sheet.save(path)


# ------------------------------------------------------------------------------------------------ the vanilla look

def vanilla_textures(assets):
    """look -> {layer: image}: dyed leather + overlay + the vanilla trim (pattern greys mapped to the material's
    palette), as a client without the pack draws the dressed armour."""
    base = Image.open(assets / "textures/palettes/trim_base.png").convert("RGBA")
    keys = [base.getpixel((x, 0))[:3] for x in range(base.width)]

    def paint(team, tier):
        _, pattern, material = toy_armor.LOOKS[(team, tier)]
        pal = Image.open(assets / f"textures/palettes/trim/{material.split(':')[1]}.png").convert("RGBA")
        mapping = {keys[i]: pal.getpixel((i, 0)) for i in range(len(keys))}
        out = {}
        for layer in ("humanoid", "humanoid_leggings"):
            leather = Image.open(assets / f"textures/entity/equipment/{layer}/leather.png").convert("RGBA")
            overlay = Image.open(assets / f"textures/entity/equipment/{layer}/leather_overlay.png").convert("RGBA")
            dye = TEAM_DYE[team]
            px = leather.load()
            for y in range(leather.height):
                for x in range(leather.width):
                    r, g, b, a = px[x, y]
                    px[x, y] = (r * dye[0] // 255, g * dye[1] // 255, b * dye[2] // 255, a)
            leather.alpha_composite(overlay)
            trim = Image.open(assets / f"textures/trims/entity/{layer}/{pattern.split(':')[1]}.png").convert("RGBA")
            tp = trim.load()
            for y in range(trim.height):
                for x in range(trim.width):
                    r, g, b, a = tp[x, y]
                    if a:
                        tp[x, y] = (*mapping.get((r, g, b), (r, g, b, 255))[:3], 255)
            leather.alpha_composite(trim)
            out[layer] = leather
        return out
    return paint


def main_sheets(out):
    lineup(out, toy_textures, out / "toy_armor.png")
    close(out, toy_textures, out / "toy_armor_close.png")
    icons(out / "toy_armor_icons.png")


def main():
    args = sys.argv[1:]
    out = Path(args[0])
    out.mkdir(parents=True, exist_ok=True)
    main_sheets(out)
    if "--vanilla" in args:
        assets = Path(args[args.index("--vanilla") + 1])
        lineup(out, vanilla_textures(assets), out / "toy_armor_vanilla.png")


if __name__ == "__main__":
    main()
